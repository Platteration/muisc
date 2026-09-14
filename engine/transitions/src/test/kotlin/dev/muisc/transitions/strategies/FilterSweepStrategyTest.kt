package dev.muisc.transitions.strategies

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.filter.Biquad
import dev.muisc.dsp.filter.BiquadFilter
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
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `filterSweep` on a 120 BPM C major → 126 BPM A minor pair (5 % apart, so nothing is beat-matched here) and on a
 * half-time 120 → 63 BPM pair, plus a B without a grid (blocked).
 *
 * Musical assertions: A's low band falls monotonically across the sweep (the high-pass really does climb), B's
 * high band rises monotonically (its low-pass really does open), and the sweep resolves on a downbeat of BOTH
 * tracks at the same output frame.
 */
class FilterSweepStrategyTest {
    private val sr = 44100
    private val loader = SyntheticTrackLoader()
    private val strategy = FilterSweepStrategy()

    private val a: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 120.0, tonic = 0, mode = Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4), "a120C")
    }
    private val b: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 24, introBars = 4, outroBars = 4), "b126Am")
    }
    /** Half-time partner: 63 BPM against A's 120, i.e. beat-matchable only as double time. */
    private val bHalf: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 63.0, tonic = 7, mode = Mode.MAJOR, bars = 16, introBars = 4, outroBars = 4), "b63G")
    }

    private val prefs: TransitionPrefs by lazy {
        TransitionPrefs(targetLufs = minOf(a.analysis.loudness.integratedLufs, b.analysis.loudness.integratedLufs).toDouble() - 3.0, sampleRate = sr, channels = 2)
    }
    private val features: PairFeatures by lazy { DefaultPairAnalyzer().features(a.analysis, b.analysis, prefs) }
    private val stems = object : StemProvider {
        override fun aTail(): Stems = throw UnsupportedOperationException("no stems needed")
        override fun bHead(): Stems = throw UnsupportedOperationException("no stems needed")
    }

    private fun input(plan: TransitionPlan, other: SyntheticTrack = b, f: PairFeatures = features, silenceA: Boolean = false, silenceB: Boolean = false): TransitionInput {
        val aa = loader.load(a.trackRef, plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
        val bb = loader.load(other.trackRef, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(other.analysis, prefs)) }
        return TransitionInput(
            plan, a.trackRef, other.trackRef, f,
            if (silenceA) AudioBuffer.silence(sr, aa.channelCount, aa.frames) else aa,
            if (silenceB) AudioBuffer.silence(sr, bb.channelCount, bb.frames) else bb,
            stems,
        )
    }

    /** Mono mix through two cascaded Butterworth sections (a 4th-order band edge). */
    private fun band(x: AudioBuffer, lowPassHz: Double? = null, highPassHz: Double? = null): FloatArray {
        val m = x.mono()
        if (lowPassHz != null) repeat(2) { BiquadFilter(1, Biquad.lowPass(lowPassHz, sr.toDouble())).process(m, m, m.size) }
        if (highPassHz != null) repeat(2) { BiquadFilter(1, Biquad.highPass(highPassHz, sr.toDouble())).process(m, m, m.size) }
        return m
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from).coerceAtLeast(1))
    }

    /** [windows] equal RMS windows of [x] across `[from, to)`. */
    private fun profile(x: FloatArray, from: Int, to: Int, windows: Int): DoubleArray {
        val step = (to - from) / windows
        return DoubleArray(windows) { rms(x, from + it * step, from + (it + 1) * step) }
    }

    private fun sweepFrames(): Int = (8 * 4 * 60.0 / a.analysis.grid.bpm * sr).toInt()

    @Test
    fun applicabilityLikesUnmatchableTemposAndNeedsBothGrids() {
        val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
        assertTrue(app.applicable, "a 5 % pair with two confident grids: ${app.blockers}")
        assertTrue(app.score > 0.5, "score ${app.score}")

        val halfFeatures = DefaultPairAnalyzer().features(a.analysis, bHalf.analysis, prefs)
        val half = strategy.applicability(halfFeatures, a.analysis, bHalf.analysis, prefs)
        assertTrue(half.applicable, "the half-time pair is fine too (B is never stretched): ${half.blockers}")

        val noGrid = b.analysis.copy(grid = b.analysis.grid.copy(beatFrames = LongArray(0)))
        val blocked = strategy.applicability(features, a.analysis, noGrid, prefs)
        assertTrue(!blocked.applicable && blocked.blockers.any { it.contains("B has no beat grid") }, "$blocked")
        val shaky = strategy.applicability(features.copy(gridConfidenceA = 0.2), a.analysis.copy(grid = a.analysis.grid.copy(confidence = 0.2f)), b.analysis, prefs)
        assertTrue(!shaky.applicable, "an unconfident A grid must block: $shaky")

        assertEquals("filterSweep", strategy.id)
        assertEquals(listOf("sweepBars", "hpfToHz", "resonance", "bLpfFromHz", "hpfFromHz", "cutOnPhrase"), strategy.params.map { it.id })
    }

    @Test
    fun renderHonoursTheSpliceContractAndIsClean() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val g = Splice.GUARD_FRAMES
        assertEquals(sweepFrames() + 2 * g, plan.expectedOutputFrames, "8 bars of A plus both guards")
        assertEquals(0, plan.aExitOffset)
        assertEquals(plan.bWindow.end, plan.bEntryFrame)
        val input = input(plan)
        val rendered = strategy.render(input, RenderContext(prefs, SEED))
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames)
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input))
        val artifacts = ArtifactDetector(sr).analyze(rendered.audio)
        assertTrue(artifacts.clicks.isEmpty(), "clicks: ${artifacts.clicks}")
        assertTrue(rendered.audio.peak() <= 1.0f, "peak ${rendered.audio.peak()}")
        assertEquals(listOf("sweep starts, B enters filtered", "sweep resolves: B open, A gone"), rendered.markers.map { it.label })
        assertTrue(plan.lanes.any { it.id == "hpfA" } && plan.lanes.any { it.id == "lpfB" })
        assertTrue(plan.lanes.any { it.id == "beatsA" && it.points.size >= 32 }, "a beatsA lane with one point per A beat")

        val again = strategy.render(input(plan), RenderContext(prefs, SEED))
        for (c in 0 until rendered.audio.channelCount) assertTrue(rendered.audio[c].contentEquals(again.audio[c]), "renders are bit-identical")
    }

    /** Musical assertion 1: A's low band falls monotonically across the sweep. */
    @Test
    fun aLowBandFallsMonotonicallyAndBHighBandRises() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val g = Splice.GUARD_FRAMES
        val sweep = sweepFrames()

        // Measured against the DRY decks in the same windows, so the tracks' own bar-to-bar variation cancels and
        // what is left is exactly what the sweep did to each band. A's high-pass is measured without resonance:
        // with Q = 2 the resonant peak audibly BOOSTS the bass on its way up (see the second half of this test).
        val flat = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("resonance", 0.7071), prefs, SEED)
        val dryA = loader.load(a.trackRef, flat.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
        val onlyA = strategy.render(input(flat, silenceB = true), RenderContext(prefs, SEED)).audio
        val aOut = profile(band(onlyA, lowPassHz = 200.0), g, g + sweep, 8)
        val aRef = profile(band(dryA, lowPassHz = 200.0), g, g + sweep, 8)
        val lows = DoubleArray(aOut.size) { aOut[it] / aRef[it] }
        assertTrue(lows.first() > 0.9, "A is still unfiltered when the sweep starts (${lows.first()})")
        for (i in 1 until lows.size) {
            assertTrue(lows[i] < lows[i - 1], "A's low band must keep falling: window $i (${lows.joinToString { "%.4f".format(it) }})")
        }
        assertTrue(lows.last() < 0.01, "A's low band is essentially gone at the end (${lows.last()})")

        // With the default Q = 2 the same measurement shows the resonant peak lifting the bass as it sweeps
        // through it, and then the same collapse: that bump is the sound of the strategy.
        val resonantOnlyA = strategy.render(input(plan, silenceB = true), RenderContext(prefs, SEED)).audio
        val resonant = profile(band(resonantOnlyA, lowPassHz = 200.0), g, g + sweep, 8).let { out ->
            DoubleArray(out.size) { out[it] / aRef[it] }
        }
        assertTrue(resonant.max() > 1.15, "the resonance lifts A's low band on the way up (${resonant.joinToString { "%.3f".format(it) }})")
        assertTrue(resonant.last() < 0.01, "and it still collapses to nothing (${resonant.last()})")

        val dryB = loader.load(b.trackRef, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
        val onlyB = strategy.render(input(plan, silenceA = true), RenderContext(prefs, SEED)).audio
        val bOut = profile(band(onlyB, highPassHz = 500.0), g, g + sweep, 6)
        val bRef = profile(band(dryB, highPassHz = 500.0), 0, sweep, 6)
        val highs = DoubleArray(bOut.size) { bOut[it] / bRef[it] }
        for (i in 1 until highs.size) {
            assertTrue(highs[i] > highs[i - 1], "B's high band must keep rising: window $i (${highs.joinToString { "%.4f".format(it) }})")
        }
        assertTrue(highs.first() < 0.15, "B arrives muffled, ~18 dB down above 500 Hz (${highs.first()})")
        assertTrue(highs.last() > 0.8, "B is wide open when the sweep resolves (${highs.last()})")
    }

    /** Musical assertion 2: the sweep resolves on a downbeat of both tracks, on the same output frame. */
    @Test
    fun theSweepResolvesOnADownbeatOfBothTracks() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val g = Splice.GUARD_FRAMES
        val sweep = sweepFrames()
        val resolveA = plan.aExitFrame + g + sweep
        val beatA = a.analysis.grid.beatAtFrame(resolveA)
        assertEquals(beatA, Math.round(beatA).toDouble(), 1e-6, "the sweep ends on an exact A beat ($beatA)")
        assertTrue(a.analysis.grid.isPhraseStart(Math.round(beatA).toInt()), "beat $beatA is a phrase start of A")

        val resolveB = plan.bWindow.start + sweep
        val beatB = b.analysis.grid.beatAtFrame(resolveB)
        assertEquals(beatB, Math.round(beatB).toDouble(), 1e-6, "B is exactly on one of its beats there ($beatB)")
        assertTrue(b.analysis.grid.isDownbeat(Math.round(beatB).toInt()), "beat $beatB is a downbeat of B")
        assertTrue(Math.round(beatB).toInt() >= b.analysis.cues.mixInBeat, "B lands at or after its mix-in cue")
        // B is never stretched: the frames between its entry and the resolve are exactly `sweep` of B's own audio.
        assertEquals(sweep.toLong(), resolveB - plan.bWindow.start)

        // The same holds for the half-time partner, whose bars are almost twice as long.
        val halfFeatures = DefaultPairAnalyzer().features(a.analysis, bHalf.analysis, prefs)
        val halfPlan = strategy.plan(a.analysis, bHalf.analysis, halfFeatures, Params.EMPTY, prefs, SEED)
        val halfBeat = bHalf.analysis.grid.beatAtFrame(halfPlan.bWindow.start + sweep)
        assertEquals(halfBeat, Math.round(halfBeat).toDouble(), 1e-6)
        assertTrue(bHalf.analysis.grid.isDownbeat(Math.round(halfBeat).toInt()))
        val halfInput = input(halfPlan, other = bHalf, f = halfFeatures)
        val halfRender = strategy.render(halfInput, RenderContext(prefs, SEED))
        assertEquals(emptyList(), SpliceCheck.verify(halfRender, halfInput))
    }

    @Test
    fun sweepLengthAndResonanceFollowTheParameters() {
        for (bars in intArrayOf(2, 4, 16)) {
            val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("sweepBars", bars), prefs, SEED)
            val expected = (bars * 4 * 60.0 / a.analysis.grid.bpm * sr).toInt()
            assertEquals(expected + 2 * Splice.GUARD_FRAMES, plan.expectedOutputFrames, "$bars bars")
            val input = input(plan)
            val rendered = strategy.render(input, RenderContext(prefs, SEED))
            assertEquals(emptyList(), SpliceCheck.verify(rendered, input), "$bars bars")
            assertTrue(rendered.audio.peak() <= 1.0f)
        }
        // A screaming Q still has to come out under 0 dBFS (the true-peak limiter is the safety net).
        val loud = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("resonance", 6.0).with("sweepBars", 2), prefs, SEED)
        val loudInput = input(loud)
        val loudRender = strategy.render(loudInput, RenderContext(prefs, SEED))
        assertTrue(loudRender.audio.peak() <= 1.0f, "peak ${loudRender.audio.peak()}")
        assertEquals(emptyList(), SpliceCheck.verify(loudRender, loudInput))
    }

    private companion object { const val SEED = 17L }
}
