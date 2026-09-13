package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.Mode
import dev.muisc.dsp.loudness.TruePeak
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.core.SongFixtures
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.core.SpliceCheck
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossfadeStrategyTest {
    private val sr = SongFixtures.SR
    private val a by lazy { SongFixtures.song("A120", 120.0, 0, Mode.MAJOR, bars = 32, introBars = 4, outroBars = 4) }
    private val b by lazy { SongFixtures.song("B126", 126.0, 9, Mode.MINOR, bars = 32, introBars = 4, outroBars = 4) }
    private val prefs by lazy { SongFixtures.prefs(a, b) }
    private val features by lazy { SongFixtures.features(a, b) }
    private val strategy = CrossfadeStrategy()

    private fun renderWith(params: Params, seed: Long = 1L) = run {
        val plan = strategy.plan(a.analysis, b.analysis, features, params, prefs, seed)
        val input = SongFixtures.input(plan, a, b, prefs, features)
        Triple(plan, input, strategy.render(input, RenderContext(prefs, seed)))
    }

    @Test
    fun applicabilityIsNeverBlocked() {
        val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
        assertTrue(app.applicable && app.blockers.isEmpty())
        assertEquals(0.05, app.score, 1e-9, "5 % stretch within the 8 % limit, compatible keys (8B→8A): floor")
        val far = features.copy(stretchPercent = 20.0, tempoRatio = 1.2, camelotDistance = 5, camelotDistanceAfterShift = 5)
        val app2 = strategy.applicability(far, a.analysis, b.analysis, prefs)
        assertEquals(0.3, app2.score, 1e-9); assertTrue(app2.reasons.size >= 3)
        assertEquals("crossfade", strategy.id)
        assertEquals(listOf("fadeSec", "law", "alignToBeat", "bStartAtMixIn"), strategy.params.map { it.id })
    }

    @Test
    fun renderHonoursContractAndIsClean() {
        val (plan, input, rendered) = renderWith(Params.EMPTY)
        val g = Splice.GUARD_FRAMES
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames, "output length is exactly what the plan promised")
        assertEquals(6 * sr + 2 * g, plan.expectedOutputFrames)
        assertEquals(0, plan.aExitOffset, "the segment starts at aWindow.start")
        assertEquals(6 * sr + g, plan.aWindow.length, "A window = pre-roll + fade")
        assertEquals(6 * sr + g, plan.bWindow.length, "B window = fade + post-roll")
        assertEquals(plan.bWindow.end, plan.bEntryFrame)
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input))
        val report = ArtifactDetector(sr).analyze(rendered.audio)
        assertTrue(report.clicks.isEmpty(), "clicks: ${report.clicks}")
        assertTrue(rendered.audio.peak() <= 1.0f, "peak ${rendered.audio.peak()}")
        // The crossfade region is limited to -1 dBTP; the dry guard regions are the decks' own material and may sit higher.
        val dryPeak = maxOf(TruePeak.measureDbtp(input.aAudio), TruePeak.measureDbtp(input.bAudio))
        assertTrue(rendered.report.truePeakDbtp <= maxOf(-0.9, dryPeak + 0.05), "true peak ${rendered.report.truePeakDbtp} dBTP (dry material $dryPeak)")
        assertTrue(rendered.report.warnings.none { it.startsWith("click") }, rendered.report.warnings.toString())
        assertEquals(listOf(CrossfadeStrategy.MARKER_B_ENTERS, CrossfadeStrategy.MARKER_A_GONE), rendered.markers.map { it.label })
        assertEquals(g.toLong(), rendered.markers[0].frame); assertEquals((g + 6 * sr).toLong(), rendered.markers[1].frame)
        assertEquals(2, plan.lanes.size)
        assertTrue(plan.notes.isNotEmpty())
        // deck gain: the fixture targets 3 LU under the quieter track, so that deck sits at -3 dB and the louder one lower
        val ga = DeckGain.of(a.analysis, prefs); val gb = DeckGain.of(b.analysis, prefs)
        assertTrue(ga <= -2.99f && gb <= -2.99f && (abs(ga + 3f) < 1e-3f || abs(gb + 3f) < 1e-3f), "deck gains $ga / $gb")
        assertEquals(input.aAudio[0][100], a.audio[0][plan.aWindow.start.toInt() + 100] * DeckGain.linear(ga), 1e-6f, "input carries the deck gain")
        // The fade actually happens: A energy vanishes at the end, B is absent at the start.
        val out = rendered.audio
        val aStart = out.slice(0, g).rms(); val bEnd = out.slice(out.frames - g, out.frames).rms()
        assertTrue(aStart > 0.01f && bEnd > 0.01f)
        assertTrue(out.slice(g, g + 1000).rms() > 0.01f)
    }

    @Test
    fun deterministicAndLengthFollowsFadeSeconds() {
        val (_, _, r1) = renderWith(Params.EMPTY.with("fadeSec", 4.0), seed = 7)
        val (_, _, r2) = renderWith(Params.EMPTY.with("fadeSec", 4.0), seed = 7)
        for (c in 0 until 2) assertTrue(r1.audio[c].contentEquals(r2.audio[c]), "bit-identical renders")
        for (sec in doubleArrayOf(1.0, 2.5, 4.0, 12.0)) {
            val (plan, input, r) = renderWith(Params.EMPTY.with("fadeSec", sec))
            assertEquals(Math.round(sec * sr).toInt() + 2 * Splice.GUARD_FRAMES, plan.expectedOutputFrames, "fadeSec $sec")
            assertEquals(plan.expectedOutputFrames, r.audio.frames)
            assertEquals(emptyList(), SpliceCheck.verify(r, input), "fadeSec $sec")
        }
        val clamped = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("fadeSec", 40.0), prefs, 0)
        assertEquals(12.0, clamped.params.double(CrossfadeStrategy.P.fadeSec), "fadeSec clamps to the spec range")
        assertEquals(12 * sr + 2 * Splice.GUARD_FRAMES, clamped.expectedOutputFrames)
        for (law in listOf("LINEAR", "S_CURVE")) {
            val (_, input, r) = renderWith(Params.EMPTY.with("law", law).with("fadeSec", 2.0))
            assertEquals(emptyList(), SpliceCheck.verify(r, input), law)
        }
    }

    @Test
    fun alignToBeatPutsBDownbeatOnADownbeat() {
        val (plan, _, rendered) = renderWith(Params.EMPTY)
        val enters = rendered.markers.first { it.label == CrossfadeStrategy.MARKER_B_ENTERS }
        val aFrame = plan.aExitFrame + enters.frame
        val aBeat = a.grid.beatAtFrame(aFrame)
        assertEquals(aBeat, floor(aBeat + 0.5), 1e-6, "B enters on an exact A beat ($aBeat)")
        assertTrue(a.grid.isDownbeat(Math.round(aBeat).toInt()), "beat $aBeat is an A downbeat")
        // B's first window frame is what plays at the "B enters" marker (output frame g maps to bWindow.start).
        val bFrame = plan.bWindow.start + (enters.frame - (plan.expectedOutputFrames - plan.aWindow.length))
        assertEquals(plan.bWindow.start, bFrame)
        val bBeat = b.grid.beatAtFrame(bFrame)
        assertEquals(bBeat, floor(bBeat + 0.5), 1e-6, "B starts on an exact B beat ($bBeat)")
        assertTrue(b.grid.isDownbeat(Math.round(bBeat).toInt()), "beat $bBeat is a B downbeat")
        assertEquals(b.analysis.cues.mixInBeat, Math.round(bBeat).toInt(), "B starts at its mix-in cue")
        assertTrue(abs((plan.aWindow.end - a.analysis.trimEndFrame)) <= 2L * sr, "fade ends within a bar of A's end (${plan.aWindow.end} vs ${a.analysis.trimEndFrame})")
        assertTrue(plan.aWindow.end <= a.analysis.trimEndFrame)
        // without alignment the fade ends exactly at trimEnd and B starts at its trim start
        val raw = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("alignToBeat", false).with("bStartAtMixIn", false), prefs, 0)
        assertEquals(a.analysis.trimEndFrame, raw.aWindow.end)
        assertEquals(b.analysis.trimStartFrame, raw.bWindow.start)
        // an unconfident grid disables alignment
        val shaky = a.analysis.copy(grid = a.grid.copy(confidence = 0.2f))
        val p2 = strategy.plan(shaky, b.analysis, features, Params.EMPTY, prefs, 0)
        assertEquals(a.analysis.trimEndFrame, p2.aWindow.end)
        assertTrue(p2.notes.any { it.contains("unaligned") })
    }
}
