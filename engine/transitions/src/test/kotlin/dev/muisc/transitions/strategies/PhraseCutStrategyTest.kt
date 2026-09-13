package dev.muisc.transitions.strategies

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.gain.Curves
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.dsp.stems.Stems
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.PairFeatures
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
 * `phraseCut` from a 120 BPM C major track into a cold-starting 126 BPM A minor track (its intended pair), plus
 * the same A with an unconfident grid (blocked).
 *
 * Musical assertions: the cut really is a phrase start of A and B's mix-in downbeat lands on that very frame
 * (bit-exact, not ±1), the reverb tail of A rings under B and is gone before the segment ends, and the pre-cut
 * duck attenuates A by exactly the requested number of dB.
 */
class PhraseCutStrategyTest {
    private val sr = 44100
    private val loader = SyntheticTrackLoader()
    private val strategy = PhraseCutStrategy()

    private val a: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 120.0, tonic = 0, mode = Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4), "a120C")
    }
    /** B starts cold on a downbeat: the classic cut target. */
    private val b: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 24, introBars = 0, outroBars = 4), "bCold126Am")
    }

    private val prefs: TransitionPrefs by lazy {
        TransitionPrefs(targetLufs = minOf(a.analysis.loudness.integratedLufs, b.analysis.loudness.integratedLufs).toDouble() - 3.0, sampleRate = sr, channels = 2)
    }
    private val features: PairFeatures by lazy { DefaultPairAnalyzer().features(a.analysis, b.analysis, prefs) }
    private val stems = object : StemProvider {
        override fun aTail(): Stems = throw UnsupportedOperationException("no stems needed")
        override fun bHead(): Stems = throw UnsupportedOperationException("no stems needed")
    }

    private fun aAudio(plan: TransitionPlan) =
        loader.load(a.trackRef, plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }

    private fun input(plan: TransitionPlan, silenceA: Boolean = false, silenceB: Boolean = false): TransitionInput {
        val aa = aAudio(plan)
        val bb = loader.load(b.trackRef, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
        return TransitionInput(
            plan, a.trackRef, b.trackRef, features,
            if (silenceA) AudioBuffer.silence(sr, aa.channelCount, aa.frames) else aa,
            if (silenceB) AudioBuffer.silence(sr, bb.channelCount, bb.frames) else bb,
            stems,
        )
    }

    private fun rms(x: AudioBuffer, from: Int, to: Int): Double {
        var s = 0.0
        val n = (to - from).coerceAtLeast(1)
        for (c in 0 until x.channelCount) { val ch = x[c]; for (i in from until to) s += ch[i].toDouble() * ch[i] }
        return sqrt(s / (n * x.channelCount))
    }

    /** Output frame of the cut: the dry pre-roll plus the bar of A that plays inside the segment. */
    private fun cutOut(plan: TransitionPlan): Int =
        Splice.GUARD_FRAMES + (60.0 / a.analysis.grid.bpm * sr * a.analysis.grid.beatsPerBar).toInt()

    @Test
    fun applicabilityFitsAColdStartAndNeedsAConfidentGrid() {
        val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
        assertTrue(app.applicable, "COLD_START must be applicable: ${app.blockers}")
        assertTrue(app.score > 0.6, "score ${app.score}")
        assertTrue(app.reasons.any { it.contains("COLD_START") }, app.reasons.toString())

        val shaky = a.analysis.copy(grid = a.analysis.grid.copy(confidence = 0.2f))
        val shakyFeatures = features.copy(gridConfidenceA = 0.2)
        val blocked = strategy.applicability(shakyFeatures, shaky, b.analysis, prefs)
        assertTrue(!blocked.applicable && blocked.blockers.any { it.contains("confidence") }, "unconfident grid must block: $blocked")

        assertEquals("phraseCut", strategy.id)
        assertEquals(listOf("tailMs", "tailDb", "tailDampHz", "preDuckDb", "blendMs", "cutOnPhrase"), strategy.params.map { it.id })
    }

    @Test
    fun renderHonoursTheSpliceContractAndIsClean() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val g = Splice.GUARD_FRAMES
        val tail = Math.round(0.4 * sr).toInt()
        assertEquals(cutOut(plan) + tail + g, plan.expectedOutputFrames)
        assertEquals(0, plan.aExitOffset)
        assertTrue(plan.bEntryOffset >= g)
        val input = input(plan)
        val rendered = strategy.render(input, RenderContext(prefs, SEED))
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames)
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input))
        val artifacts = ArtifactDetector(sr).analyze(rendered.audio)
        assertTrue(artifacts.clicks.isEmpty(), "clicks: ${artifacts.clicks}")
        assertTrue(rendered.audio.peak() <= 1.0f, "peak ${rendered.audio.peak()}")
        assertEquals(listOf("pre-cut (duck / reverb send)", "cut: B enters", "reverb tail ends"), rendered.markers.map { it.label })
        assertTrue(plan.lanes.any { it.id == "masterBeat" && it.points.size > 4 }, "a masterBeat lane with one point per A beat")

        val again = strategy.render(input(plan), RenderContext(prefs, SEED))
        for (c in 0 until rendered.audio.channelCount) assertTrue(rendered.audio[c].contentEquals(again.audio[c]), "renders are bit-identical")
    }

    /** Musical assertion 1: the cut is a phrase start of A and B's downbeat is on the same output frame. */
    @Test
    fun theCutIsAPhraseStartOfAAndBsDownbeatLandsOnIt() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("tailMs", 0.0), prefs, SEED)
        val cut = cutOut(plan)
        val cutFrameA = plan.aExitFrame + cut
        val beat = a.analysis.grid.beatAtFrame(cutFrameA)
        assertEquals(beat, Math.round(beat).toDouble(), 1e-6, "the cut is on an exact A beat ($beat)")
        val beatIndex = Math.round(beat).toInt()
        assertTrue(a.analysis.grid.isPhraseStart(beatIndex), "beat $beatIndex is a phrase start of A")
        assertTrue(beatIndex >= a.analysis.cues.mixOutBeat, "the cut ($beatIndex) is at or after A's mix-out cue ${a.analysis.cues.mixOutBeat}")

        // B's window starts at its mix-in downbeat, and that frame is what plays at the cut.
        val bBeat = b.analysis.grid.beatAtFrame(plan.bWindow.start)
        assertEquals(bBeat, Math.round(bBeat).toDouble(), 1e-6, "B's window starts on an exact B beat ($bBeat)")
        assertTrue(b.analysis.grid.isDownbeat(Math.round(bBeat).toInt()), "B enters on one of its downbeats")
        assertEquals(b.analysis.cues.mixInBeat, Math.round(bBeat).toInt())

        // With no reverb tail the output after the micro-blend IS B, sample for sample, from its downbeat.
        val input = input(plan)
        val rendered = strategy.render(input, RenderContext(prefs, SEED))
        val blend = Math.round(0.016 * sr).toInt()
        for (c in 0 until rendered.audio.channelCount) {
            for (i in 0 until input.bAudio.frames - blend) {
                assertEquals(input.bAudio[c][blend + i], rendered.audio[c][cut + blend + i], 1e-6f, "B is dry from the cut (channel $c, frame $i)")
            }
        }
    }

    /** Musical assertion 2: A's reverb tail rings under B's first bar and is gone before the segment ends. */
    @Test
    fun theReverbTailOfARingsUnderBAndIsContained() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("tailMs", 800.0), prefs, SEED)
        val cut = cutOut(plan)
        val tail = Math.round(0.8 * sr).toInt()
        val onlyA = strategy.render(input(plan, silenceB = true), RenderContext(prefs, SEED)).audio
        val window = tail / 4
        val e1 = rms(onlyA, cut, cut + window)
        val e2 = rms(onlyA, cut + window, cut + 2 * window)
        val e3 = rms(onlyA, cut + 2 * window, cut + 3 * window)
        assertTrue(e1 > 1e-4, "the tail is audible right after the cut ($e1)")
        assertTrue(e2 < e1 && e3 < e2, "the tail decays monotonically ($e1 > $e2 > $e3)")
        assertTrue(Curves.linearToDb(e3 / e1) < -6.0, "the tail has lost at least 6 dB by its third quarter")
        assertEquals(0.0, rms(onlyA, plan.expectedOutputFrames - Splice.GUARD_FRAMES, plan.expectedOutputFrames), 1e-9, "nothing of A in the dry post-roll")
        assertEquals(0.0, rms(onlyA, cut + tail, plan.expectedOutputFrames), 1e-9, "the tail is released to digital silence at its end")

        // tailMs = 0 means no tail at all: A is gone one micro-blend after the cut.
        val dryPlan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("tailMs", 0.0), prefs, SEED)
        val dryOnlyA = strategy.render(input(dryPlan, silenceB = true), RenderContext(prefs, SEED)).audio
        val blend = Math.round(0.016 * sr).toInt()
        assertEquals(0.0, rms(dryOnlyA, cut + blend, dryOnlyA.frames), 1e-9)
    }

    /** Musical assertion 3: the pre-cut duck attenuates A by exactly the dB it promises. */
    @Test
    fun thePreCutDuckAttenuatesAByTheRequestedAmount() {
        for (db in doubleArrayOf(3.0, 9.0)) {
            val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("tailMs", 0.0).with("preDuckDb", db), prefs, SEED)
            val cut = cutOut(plan)
            val onlyA = strategy.render(input(plan, silenceB = true), RenderContext(prefs, SEED)).audio
            val dry = aAudio(plan)
            val win = Math.round(0.02 * sr).toInt()
            val ducked = rms(onlyA, cut - win, cut)
            val reference = rms(dry, cut - win, cut)
            assertTrue(reference > 1e-4, "A has signal just before the cut")
            assertEquals(Curves.dbToLinear(-db), ducked / reference, 0.03, "${db} dB duck")
            // ... and the dry pre-roll is untouched, whatever the duck does afterwards.
            assertEquals(0.0, abs(rms(onlyA, 0, Splice.GUARD_FRAMES) - rms(dry, 0, Splice.GUARD_FRAMES)), 1e-9)
            assertTrue(plan.notes.any { it.contains("ducks") }, plan.notes.toString())
        }
    }

    private companion object { const val SEED = 99L }
}
