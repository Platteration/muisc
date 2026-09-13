package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.Mode
import dev.muisc.transitions.Params
import dev.muisc.transitions.core.Splice
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmbientBridgeStrategyTest {
    private val sr = SbFixtures.SR
    private val strategy = AmbientBridgeStrategy()

    /** A mixable pair: 120 → 126 BPM (5 % stretch), C major (8B) → A minor (8A), ambient outro into ambient intro. */
    private val ok by lazy { SbFixtures.pair(SbFixtures.song(120.0, 0, Mode.MAJOR), SbFixtures.song(126.0, 9, Mode.MINOR)) }

    /** An unmixable pair: 120 → 174 BPM (half-time 0.725, 32 % stretch) and a hard stop into an ambient intro. */
    private val bad by lazy { SbFixtures.pair(SbFixtures.song(120.0, 0, Mode.MAJOR, outroBars = 0), SbFixtures.song(174.0, 6, Mode.MINOR)) }

    private val shortParams = Params.EMPTY.with("bridgeSec", 4.0).with("bFadeBars", 1)
    private val rendered by lazy { SbFixtures.render(strategy, ok, shortParams, seed = 21L) }

    @Test
    fun applicabilityIsNeverBlockedAndRisesForIncompatiblePairs() {
        assertEquals("ambientBridge", strategy.id)
        assertEquals(
            listOf("bridgeSec", "padDb", "padOctave", "padCutoffHz", "reverbSize", "reverbDb", "textureDb", "morphToB", "swellSec", "bFadeBars"),
            strategy.params.map { it.id },
        )
        val fine = strategy.applicability(ok.features, ok.a.analysis, ok.b.analysis, ok.prefs)
        assertTrue(fine.applicable && fine.blockers.isEmpty(), "never blocked: ${fine.blockers}")
        assertTrue(fine.score < 0.3, "a mixable pair only gets the floor-ish score, was ${fine.score} (${fine.reasons})")
        assertTrue(fine.score >= AmbientBridgeStrategy.FLOOR_SCORE, "floor ${fine.score}")
        val poor = strategy.applicability(bad.features, bad.a.analysis, bad.b.analysis, bad.prefs)
        assertTrue(poor.applicable && poor.blockers.isEmpty())
        assertEquals(AmbientBridgeStrategy.INCOMPATIBLE_SCORE, poor.score, 1e-9, "32 % stretch saturates the tempo term: ${poor.reasons}")
        assertTrue(poor.reasons.any { it.contains("stretch beyond") }, poor.reasons.toString())
        // a distant key alone also raises it, without any tempo problem
        val farKey = ok.features.copy(camelotDistance = 4, camelotDistanceAfterShift = 4)
        val keyOnly = strategy.applicability(farKey, ok.a.analysis, ok.b.analysis, ok.prefs)
        assertEquals(AmbientBridgeStrategy.INCOMPATIBLE_SCORE, keyOnly.score, 1e-9)
        assertTrue(keyOnly.reasons.any { it.contains("distance 4") }, keyOnly.reasons.toString())
    }

    @Test
    fun planPlacesTheBridgeBetweenAsEndAndBsMixIn() {
        val plan = strategy.plan(ok.a.analysis, ok.b.analysis, ok.features, shortParams, ok.prefs, 0L)
        val g = Splice.GUARD_FRAMES.toLong()
        val swell = Math.round(1.0 * sr)              // swellSec default
        val bridge = Math.round(4.0 * sr)
        val bFade = Math.round(4 * 60.0 * sr / 126.0) // 1 bar of B
        assertEquals(ok.a.analysis.trimEndFrame - g - swell, plan.aExitFrame, "A's dry pre-roll ends `swellSec` before its end")
        assertEquals(ok.a.analysis.trimEndFrame, plan.aWindow.end)
        assertEquals(ok.b.analysis.grid.beatFrames[16], plan.bWindow.start, "B's window starts at its mixInBeat")
        assertEquals((g + swell + bridge + bFade + g).toInt(), plan.expectedOutputFrames)
        assertEquals(plan.bWindow.start + bFade + g, plan.bEntryFrame)
        assertEquals(plan.bEntryFrame, plan.bWindow.end)
        assertTrue(plan.lanes.map { it.id }.containsAll(listOf("gainReverb", "gainBed", "padEnvelope", "gainB")))
        assertTrue(plan.notes.any { it.contains("pad on A2") }, plan.notes.toString())
        assertEquals(plan, strategy.plan(ok.a.analysis, ok.b.analysis, ok.features, shortParams, ok.prefs, 0L), "deterministic")
        // bridgeSec is the only thing that changes the length, frame for frame
        val longer = strategy.plan(ok.a.analysis, ok.b.analysis, ok.features, shortParams.with("bridgeSec", 10.0), ok.prefs, 0L)
        assertEquals(Math.round(6.0 * sr).toInt(), longer.expectedOutputFrames - plan.expectedOutputFrames)
        // both key strengths below 0.5 → no pad at all
        val vague = ok.features.copy(keyStrengthA = 0.2, keyStrengthB = 0.2)
        val noPad = strategy.plan(ok.a.analysis, ok.b.analysis, vague, shortParams, ok.prefs, 0L)
        assertTrue(noPad.notes.any { it.contains("pad omitted") }, noPad.notes.toString())
    }

    @Test
    fun renderHonoursContractAndIsDeterministic() {
        val (plan, input, r) = rendered
        SbFixtures.assertContract("ambientBridge", plan, input, r)
        val (_, _, r2) = SbFixtures.render(strategy, ok, shortParams, seed = 21L)
        SbFixtures.assertDeterministic("ambientBridge", r, r2)
        assertEquals(
            listOf(AmbientBridgeStrategy.MARKER_SWELL, AmbientBridgeStrategy.MARKER_FREEZE, AmbientBridgeStrategy.MARKER_B_ENTERS, AmbientBridgeStrategy.MARKER_B_FULL),
            r.markers.map { it.label },
        )
        assertEquals(Splice.GUARD_FRAMES.toLong(), r.markers[0].frame)
        assertEquals(45.0, r.report.metrics["padMidi"]!!, 1e-9, "A2: the pitch class A is in both C major and A minor")
        // an unmixable pair renders just as cleanly (this is the ladder's floor)
        val (p2, i2, rb) = SbFixtures.render(strategy, bad, shortParams, seed = 21L)
        SbFixtures.assertContract("ambientBridge (incompatible pair)", p2, i2, rb)
    }

    @Test
    fun theBridgeIsGeneratedMaterial() {
        val (_, _, r) = rendered
        val tA = r.markers.first { it.label == AmbientBridgeStrategy.MARKER_FREEZE }.frame.toInt()
        val tB = r.markers.first { it.label == AmbientBridgeStrategy.MARKER_B_ENTERS }.frame.toInt()
        // Control: every generated layer silenced. Neither song plays between A's end and B's entry, so the bridge
        // collapses to (almost) digital silence — proof that what fills it is the reverb freeze, bed and pad.
        val (_, _, bare) = SbFixtures.render(
            strategy, ok, shortParams.with("padDb", -80.0).with("textureDb", -80.0).with("reverbDb", -80.0), seed = 21L,
        )
        val full = SbFixtures.rms(r.audio, tA + sr / 10, tB - sr / 10)
        val empty = SbFixtures.rms(bare.audio, tA + sr / 10, tB - sr / 10)
        assertTrue(SbFixtures.db(empty) < -60.0, "with every layer silenced the bridge is empty (${SbFixtures.db(empty)} dB)")
        assertTrue(full > 30.0 * empty, "the bridge is generated material: ${SbFixtures.db(full)} dB vs ${SbFixtures.db(empty)} dB")
        // ... and it dies away completely before B's dry post-roll (the splice contract's end), so nothing bleeds over.
        val g = Splice.GUARD_FRAMES
        val tail = SbFixtures.rms(bare.audio, bare.audio.frames - g, bare.audio.frames)
        assertTrue(tail > 0.0, "the post-roll is B's own audio, not silence")
    }

    @Test
    fun thePadSoundsACommonPitchClassOfBothKeys() {
        val (_, _, r) = rendered
        val tA = r.markers.first { it.label == AmbientBridgeStrategy.MARKER_FREEZE }.frame.toInt()
        val tB = r.markers.first { it.label == AmbientBridgeStrategy.MARKER_B_ENTERS }.frame.toInt()
        val (_, _, noPad) = SbFixtures.render(strategy, ok, shortParams.with("padDb", -80.0), seed = 21L)
        val a2 = SbFixtures.hz(45)          // A2 = 110 Hz, in C major and in A minor
        val with = SbFixtures.peakNear(SbFixtures.spectrum(r.audio, tA, tB), a2, tolerance = 0.02)
        val without = SbFixtures.peakNear(SbFixtures.spectrum(noPad.audio, tA, tB), a2, tolerance = 0.02)
        assertTrue(with > 16.0 * without, "the pad adds a line at ${a2} Hz: ${SbFixtures.db(Math.sqrt(with))} dB vs ${SbFixtures.db(Math.sqrt(without))} dB without it")
        // the strongest low line of the bridge IS the pad's root, not some artefact of the frozen reverb
        val dominant = SbFixtures.dominantHz(SbFixtures.spectrum(r.audio, tA, tB), 60.0, 200.0)
        assertTrue(abs(dominant - a2) < 0.03 * a2, "dominant low line $dominant Hz, pad root $a2 Hz")
        // and a key the planner cannot trust gets no pad at all
        val vague = ok.features.copy(keyStrengthA = 0.2, keyStrengthB = 0.2)
        val plan = strategy.plan(ok.a.analysis, ok.b.analysis, vague, shortParams, ok.prefs, 0L)
        val input = SbFixtures.input(plan, ok, vague)
        val silentPad = strategy.render(input, dev.muisc.transitions.RenderContext(ok.prefs, 21L))
        assertEquals(-1.0, silentPad.report.metrics["padMidi"]!!, 1e-9)
        SbFixtures.assertContract("ambientBridge (no pad)", plan, input, silentPad)
    }
}
