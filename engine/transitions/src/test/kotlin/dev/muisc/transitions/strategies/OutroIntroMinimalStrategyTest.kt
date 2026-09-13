package dev.muisc.transitions.strategies

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.dsp.stems.Stems
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.StemProvider
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.core.SpliceCheck
import dev.muisc.transitions.synthetic.SyntheticTrack
import dev.muisc.transitions.synthetic.SyntheticTrackLoader
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `outroIntroMinimal` on ground-truth synthetic pairs: a 120 BPM C major track that fades out into a 126 BPM
 * A minor track with an ambient intro (its intended pair), and the same A into a cold-start B (blocked).
 *
 * Musical assertions: B's fade-in really is one bar of B's own grid long and equal-power (−3 dB at its midpoint),
 * and B's entry is timed from A's short-term loudness curve, not from its trim end.
 */
class OutroIntroMinimalStrategyTest {
    private val sr = 44100
    private val loader = SyntheticTrackLoader()
    private val strategy = OutroIntroMinimalStrategy()

    /** A fades out (FADE_OUT), B has an ambient intro: exactly what this strategy is for. */
    private val a: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 120.0, tonic = 0, mode = Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4, outroFade = true), "aFade120C")
    }
    private val b: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 24, introBars = 4, outroBars = 4), "b126Am")
    }

    /** Same B but starting cold (no intro bars): the pair this strategy must refuse. */
    private val bCold: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 24, introBars = 0, outroBars = 4), "bCold126Am")
    }

    private val prefs: TransitionPrefs by lazy {
        TransitionPrefs(targetLufs = minOf(a.analysis.loudness.integratedLufs, b.analysis.loudness.integratedLufs).toDouble() - 3.0, sampleRate = sr, channels = 2)
    }
    private val features by lazy { DefaultPairAnalyzer().features(a.analysis, b.analysis, prefs) }
    private val stems = object : StemProvider {
        override fun aTail(): Stems = throw UnsupportedOperationException("no stems needed")
        override fun bHead(): Stems = throw UnsupportedOperationException("no stems needed")
    }

    private fun input(plan: TransitionPlan, silenceA: Boolean = false, silenceB: Boolean = false): TransitionInput {
        val aAudio = loader.load(a.trackRef, plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
        val bAudio = loader.load(b.trackRef, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
        return TransitionInput(
            plan, a.trackRef, b.trackRef, features,
            if (silenceA) AudioBuffer.silence(sr, aAudio.channelCount, aAudio.frames) else aAudio,
            if (silenceB) AudioBuffer.silence(sr, bAudio.channelCount, bAudio.frames) else bAudio,
            stems,
        )
    }

    private fun rms(x: AudioBuffer, from: Int, to: Int): Double {
        var s = 0.0
        val n = (to - from).coerceAtLeast(1)
        for (c in 0 until x.channelCount) { val ch = x[c]; for (i in from until to) s += ch[i].toDouble() * ch[i] }
        return sqrt(s / (n * x.channelCount))
    }

    @Test
    fun applicabilityFitsFadeOutIntoAmbientIntroAndBlocksColdStarts() {
        val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
        assertTrue(app.applicable, "FADE_OUT into AMBIENT_INTRO must be applicable: ${app.blockers}")
        assertTrue(app.score > 0.6, "score ${app.score} should be high for the intended pair")
        assertTrue(app.reasons.any { it.contains("FADE_OUT") }, app.reasons.toString())

        val coldFeatures = DefaultPairAnalyzer().features(a.analysis, bCold.analysis, prefs)
        val cold = strategy.applicability(coldFeatures, a.analysis, bCold.analysis, prefs)
        assertTrue(!cold.applicable && cold.blockers.any { it.contains("cold") }, "cold start must be blocked: $cold")

        assertEquals("outroIntroMinimal", strategy.id)
        assertEquals(listOf("overlapSec", "thresholdLufs", "bFadeBars", "holdAfterSec", "aFadeLaw", "bStartAtMixIn"), strategy.params.map { it.id })
    }

    @Test
    fun renderHonoursTheSpliceContractAndIsClean() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val g = Splice.GUARD_FRAMES
        val overlap = 4 * sr
        assertEquals(overlap + 2 * g, plan.expectedOutputFrames, "4 s overlap plus both guards")
        assertEquals(0, plan.aExitOffset)
        assertEquals(plan.bWindow.end, plan.bEntryFrame)
        val input = input(plan)
        val rendered = strategy.render(input, RenderContext(prefs, SEED))
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames)
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input))
        val artifacts = ArtifactDetector(sr).analyze(rendered.audio)
        assertTrue(artifacts.clicks.isEmpty(), "clicks: ${artifacts.clicks}")
        assertTrue(rendered.audio.peak() <= 1.0f, "peak ${rendered.audio.peak()}")
        assertEquals(listOf("B enters", "B at full level", "A gone"), rendered.markers.map { it.label })
        assertTrue(plan.notes.isNotEmpty() && plan.lanes.size == 2)

        val again = strategy.render(input(plan), RenderContext(prefs, SEED))
        for (c in 0 until rendered.audio.channelCount) assertTrue(rendered.audio[c].contentEquals(again.audio[c]), "renders are bit-identical")
    }

    /** Musical assertion 1: B's fade-in is one bar of B's grid and follows the equal-power law. */
    @Test
    fun bFadesInOverExactlyOneBarOfItsOwnGridEqualPower() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val g = Splice.GUARD_FRAMES
        val barFrames = (60.0 / b.analysis.grid.bpm * b.analysis.grid.beatsPerBar * sr).toInt()
        val full = plan.lanes.first { it.id == "gainB" }.points.last()
        assertEquals(barFrames.toDouble() / sr, full.outputSec - g.toDouble() / sr, 0.01, "B reaches full level one bar after it enters")

        // Isolate B by silencing A: the level at the fade's midpoint must be the equal-power -3 dB point.
        val onlyB = strategy.render(input(plan, silenceA = true), RenderContext(prefs, SEED)).audio
        val dryB = loader.load(b.trackRef, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
        val half = barFrames / 2
        val win = barFrames / 8
        val faded = rms(onlyB, g + half - win, g + half + win)
        val dry = rms(dryB, half - win, half + win)
        assertTrue(dry > 1e-4, "B has signal at the fade midpoint")
        val ratio = faded / dry
        assertEquals(sqrt(0.5), ratio, 0.06, "equal-power midpoint (-3 dB), measured $ratio")
        // ... and nothing of B before it enters.
        assertEquals(0.0, rms(onlyB, 0, g - 1), 1e-9)

        // A four-bar fade really is four times as long — when the overlap is long enough to hold it; four bars of
        // B are 7.6 s, so against the default 4 s overlap the fade is clamped to the overlap.
        val clamped = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("bFadeBars", 4), prefs, SEED)
        assertEquals(4.0, clamped.lanes.first { it.id == "gainB" }.points.last().outputSec - g.toDouble() / sr, 0.01, "clamped to the overlap")
        val slow = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("bFadeBars", 4).with("overlapSec", 8.0), prefs, SEED)
        val slowFull = slow.lanes.first { it.id == "gainB" }.points.last()
        assertEquals(4.0 * barFrames / sr, slowFull.outputSec - g.toDouble() / sr, 0.02)
    }

    /** Musical assertion 2: the entry is timed from A's loudness curve, not from its trim end. */
    @Test
    fun bEntersWhereAsShortTermLoudnessCrossesTheThreshold() {
        val curve = a.analysis.loudness.shortTermLufs
        val hop = a.analysis.loudness.shortTermHopSec.toDouble()
        assertTrue(curve.isNotEmpty(), "the synthetic ground truth carries a short-term loudness curve")
        fun crossingSec(threshold: Double): Double {
            var last = -1
            for (k in curve.indices) if (curve[k] >= threshold) last = k
            assertTrue(last in 0 until curve.size - 1, "the curve crosses $threshold LUFS (last loud window $last of ${curve.size})")
            return (last + 1 + 15) * hop // centre of the first 3 s window that is below the threshold
        }
        fun entrySec(params: Params): Double =
            (strategy.plan(a.analysis, b.analysis, features, params, prefs, SEED).aExitFrame + Splice.GUARD_FRAMES).toDouble() / sr

        // This fade bottoms out at -29.5 LUFS measured over 3 s, so -20 is crossed while A is still playing.
        val at20 = entrySec(Params.EMPTY.with("thresholdLufs", -20.0))
        assertEquals(crossingSec(-20.0), at20, 0.15, "B enters at the -20 LUFS crossing (±150 ms)")
        assertTrue(at20 < a.analysis.trimEndFrame.toDouble() / sr - 4.0, "the crossing is well inside A's music")

        // A looser threshold brings B in earlier; holdAfterSec then shifts the entry by exactly that much.
        val at15 = entrySec(Params.EMPTY.with("thresholdLufs", -15.0))
        assertTrue(at15 < at20 - 0.5, "a looser threshold brings B in earlier ($at15 vs $at20)")
        val held = entrySec(Params.EMPTY.with("thresholdLufs", -20.0).with("holdAfterSec", -2.0))
        assertEquals(-2.0, held - at20, 0.001)

        // A threshold the fade never reaches falls back to trimEnd - overlap, and says so in the notes.
        val deep = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("thresholdLufs", -45.0), prefs, SEED)
        assertEquals(a.analysis.trimEndFrame - 4L * sr, deep.aExitFrame + Splice.GUARD_FRAMES, "fallback entry")
        assertTrue(deep.notes.any { it.contains("never crosses") }, deep.notes.toString())
        // The default (-30 LUFS) is that same fallback for this fixture: the 3 s window never quite gets there.
        val byDefault = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        assertEquals(deep.aExitFrame, byDefault.aExitFrame)
    }

    @Test
    fun overlapLengthFollowsTheParameterAndTheContractHolds() {
        for (sec in doubleArrayOf(1.0, 2.5, 8.0)) {
            val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("overlapSec", sec), prefs, SEED)
            assertEquals(Math.round(sec * sr).toInt() + 2 * Splice.GUARD_FRAMES, plan.expectedOutputFrames, "overlapSec $sec")
            val input = input(plan)
            val rendered = strategy.render(input, RenderContext(prefs, SEED))
            assertEquals(plan.expectedOutputFrames, rendered.audio.frames)
            assertEquals(emptyList(), SpliceCheck.verify(rendered, input), "overlapSec $sec")
            assertTrue(abs(rendered.audio.peak()) <= 1.0f)
        }
    }

    private companion object { const val SEED = 4242L }
}
