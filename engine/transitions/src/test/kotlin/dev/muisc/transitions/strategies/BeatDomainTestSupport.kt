package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.fft.RealFft
import dev.muisc.dsp.filter.Biquad
import dev.muisc.dsp.filter.BiquadFilter
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.dsp.stems.Stems
import dev.muisc.dsp.window.Window
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.StemProvider
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.core.SongFixtures
import dev.muisc.transitions.core.SpliceCheck
import dev.muisc.transitions.synthetic.SyntheticTrack
import dev.muisc.transitions.synthetic.SyntheticTrackLoader
import dev.muisc.transitions.synthetic.SyntheticTracks
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test helpers for beat-domain strategies (reusable by other beat-domain packages): ground-truth synthetic pairs
 * with `DefaultPairAnalyzer` features, decoded inputs with deck gain, the onset-vs-`masterBeat` alignment
 * measurement, band energy / spectral-peak probes and the common contract assertions.
 */
internal object BeatDomainTestSupport {
    const val SR = 44100
    const val G = dev.muisc.transitions.core.Splice.GUARD_FRAMES

    fun song(bpm: Double, tonic: Int, mode: Mode, bars: Int = 24, introBars: Int = 4, outroBars: Int = 4, seed: Int = 7): SyntheticSong =
        SyntheticSong(bpm = bpm, tonic = tonic, mode = if (mode == Mode.MAJOR) dev.muisc.audio.synth.Mode.MAJOR else dev.muisc.audio.synth.Mode.MINOR,
            bars = bars, introBars = introBars, outroBars = outroBars, sampleRate = SR, seed = seed)

    /** A pair of ground-truth tracks with prefs targeting 3 LU under the quieter track (so the dry material never clips). */
    class Pair(val a: SyntheticTrack, val b: SyntheticTrack, maxStretchPercent: Double = 8.0, maxPitchShiftSemitones: Double = 1.0) {
        val prefs: TransitionPrefs = TransitionPrefs(
            targetLufs = min(a.analysis.loudness.integratedLufs, b.analysis.loudness.integratedLufs).toDouble() - 3.0,
            sampleRate = SR, channels = 2, maxStretchPercent = maxStretchPercent, maxPitchShiftSemitones = maxPitchShiftSemitones,
        )
        val features: PairFeatures = DefaultPairAnalyzer().features(a.analysis, b.analysis, prefs)
        private val loader = SyntheticTrackLoader(listOf(a, b))

        /**
         * What the renderer hands a strategy: both windows decoded (zero-padded) with the deck gain applied. Pass a
         * modified [aAnalysis] / [bAnalysis] (same audio) to test analysis-driven behaviour such as vocal ducking.
         */
        fun input(plan: TransitionPlan, aAnalysis: TrackAnalysis = a.analysis, bAnalysis: TrackAnalysis = b.analysis): TransitionInput {
            val aAudio = loader.load(a.trackRef, plan.aWindow, prefs)
            val bAudio = loader.load(b.trackRef, plan.bWindow, prefs)
            DeckGain.applyInPlace(aAudio, DeckGain.of(aAnalysis, prefs))
            DeckGain.applyInPlace(bAudio, DeckGain.of(bAnalysis, prefs))
            return TransitionInput(plan, a.trackRef.copy(analysis = aAnalysis), b.trackRef.copy(analysis = bAnalysis), features, aAudio, bAudio, NO_STEMS)
        }
    }

    private val NO_STEMS = object : StemProvider {
        override fun aTail(): Stems = throw UnsupportedOperationException("no stems in fixtures")
        override fun bHead(): Stems = throw UnsupportedOperationException("no stems in fixtures")
    }

    fun pair(a: SyntheticSong, b: SyntheticSong, maxStretchPercent: Double = 8.0, maxPitchShiftSemitones: Double = 1.0): Pair =
        Pair(SyntheticTracks.trackRef(a), SyntheticTracks.trackRef(b), maxStretchPercent, maxPitchShiftSemitones)

    /** The same input with A and/or B replaced by silence (to measure one deck's contribution to the mix). */
    fun silenced(input: TransitionInput, silenceA: Boolean, silenceB: Boolean): TransitionInput {
        val a = if (silenceA) AudioBuffer.silence(input.aAudio.sampleRate, input.aAudio.channelCount, input.aAudio.frames) else input.aAudio
        val b = if (silenceB) AudioBuffer.silence(input.bAudio.sampleRate, input.bAudio.channelCount, input.bAudio.frames) else input.bAudio
        return TransitionInput(input.plan, input.a, input.b, input.features, a, b, input.stems)
    }

    // ---------------------------------------------------------------------------------------- contract

    /** Length, splice contract, clicks, peak, finite samples, lanes and notes — what every beat-domain render must satisfy. */
    fun assertContract(name: String, plan: TransitionPlan, input: TransitionInput, rendered: RenderedTransition) {
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames, "$name: output length equals plan.expectedOutputFrames")
        assertTrue(plan.aExitFrame in plan.aWindow && plan.bWindow.end >= plan.bEntryFrame, "$name: plan frames valid")
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input), "$name: splice contract")
        val report = ArtifactDetector(SR).analyze(rendered.audio)
        assertTrue(report.clicks.isEmpty(), "$name: clicks ${report.clicks}")
        assertTrue(rendered.audio.peak() <= 1.0f, "$name: peak ${rendered.audio.peak()}")
        for (c in 0 until rendered.audio.channelCount) assertTrue(rendered.audio[c].all { it.isFinite() }, "$name: finite samples")
        assertTrue(rendered.plan.lanes.any { it.id == BeatDomain.LANE_MASTER_BEAT }, "$name: masterBeat lane present")
        assertTrue(plan.notes.isNotEmpty(), "$name: notes explain the plan")
        assertTrue(rendered.report.warnings.none { it.startsWith("click") }, "$name: ${rendered.report.warnings}")
    }

    fun assertBitIdentical(r1: RenderedTransition, r2: RenderedTransition, name: String = "render") {
        assertEquals(r1.audio.frames, r2.audio.frames, "$name: same length")
        for (c in 0 until r1.audio.channelCount) assertTrue(r1.audio[c].contentEquals(r2.audio[c]), "$name: channel $c bit-identical")
    }

    // ---------------------------------------------------------------------------------------- beat alignment

    /** Output frames of the master beats from the rendered plan's `masterBeat` lane (index = beat). */
    fun masterBeatFrames(rendered: RenderedTransition): LongArray {
        val lane = rendered.plan.lanes.first { it.id == BeatDomain.LANE_MASTER_BEAT }
        return LongArray(lane.points.size) { Math.round(lane.points[it].outputSec * SR) }
    }

    /** Local beat periods (frames) of the master grid: `F_{k+1} - F_k`. */
    fun localPeriods(masterBeats: LongArray): LongArray = LongArray(masterBeats.size - 1) { masterBeats[it + 1] - masterBeats[it] }

    class Alignment(val offsetsFrames: IntArray, val biasFrames: Double, val toleranceMs: Double) {
        val corrected: DoubleArray get() = DoubleArray(offsetsFrames.size) { offsetsFrames[it] - biasFrames }
        val medianMs: Double get() = SongFixtures.median(IntArray(offsetsFrames.size) { Math.round(abs(offsetsFrames[it] - biasFrames)).toInt() }) / SR * 1000.0
        val maxMs: Double get() = corrected.maxOf { abs(it) } / SR * 1000.0
        val within: Int get() = corrected.count { abs(it) <= toleranceMs / 1000.0 * SR }
        override fun toString(): String = "median ${"%.1f".format(medianMs)} ms, max ${"%.1f".format(maxMs)} ms, $within/${offsetsFrames.size} within $toleranceMs ms (bias ${"%.1f".format(biasFrames)} frames, offsets ${offsetsFrames.toList()})"
    }

    /**
     * Kick onsets of the render at the given master beats vs the `masterBeat` lane. The onset detector's own lag
     * is measured on [reference] (the dry track) at its own beat frames [referenceBeatFrames] and subtracted.
     */
    fun kickAlignment(rendered: RenderedTransition, masterBeats: IntRange, reference: AudioBuffer, referenceBeatFrames: LongArray, toleranceMs: Double = 12.0): Alignment {
        val frames = masterBeatFrames(rendered)
        val expected = LongArray(masterBeats.count()) { frames[masterBeats.first + it] }
        val offsets = SongFixtures.kickOffsets(rendered.audio, expected)
        val bias = SongFixtures.median(SongFixtures.kickOffsets(reference, referenceBeatFrames))
        return Alignment(offsets, bias, toleranceMs)
    }

    /** Frames of [track]'s beats `[from, to)` (its own grid, drums present only in body bars). */
    fun beatFrames(track: SyntheticTrack, from: Int, to: Int): LongArray = LongArray(to - from) { track.analysis.grid.beatFrames[from + it] }

    fun assertAligned(name: String, al: Alignment, minFraction: Double = 0.9, maxMedianMs: Double = 5.0) {
        assertTrue(al.within >= Math.ceil(minFraction * al.offsetsFrames.size).toInt(), "$name: $al")
        assertTrue(al.medianMs <= maxMedianMs, "$name: median ${"%.1f".format(al.medianMs)} ms > $maxMedianMs ms ($al)")
    }

    // ---------------------------------------------------------------------------------------- probes

    /** RMS in dBFS of the mono mix of `[from, to)` after a zero-phase 4th-order low-pass (or high-pass) at [cutoffHz]. */
    fun bandRmsDb(audio: AudioBuffer, from: Long, to: Long, cutoffHz: Double, lowPass: Boolean = true): Double {
        val x = audio.slice(from.toInt(), to.toInt()).mono().copyOf()
        fun pass(sig: FloatArray) {
            val c = if (lowPass) Biquad.lowPass(cutoffHz, SR.toDouble()) else Biquad.highPass(cutoffHz, SR.toDouble())
            BiquadFilter(1, c).process(sig, sig, sig.size)
            BiquadFilter(1, c).process(sig, sig, sig.size)
        }
        pass(x); x.reverse(); pass(x); x.reverse()
        var acc = 0.0
        for (v in x) acc += v.toDouble() * v
        return 10.0 * log10(max(1e-20, acc / x.size))
    }

    /** RMS in dBFS of `[from, to)`. */
    fun rmsDb(audio: AudioBuffer, from: Long, to: Long): Double = 20.0 * log10(max(1e-10, audio.slice(from.toInt(), to.toInt()).rms().toDouble()))

    /**
     * Frequency (Hz) of the strongest spectral peak in `[minHz, maxHz]` of the mono mix of `[from, to)`: Hann window,
     * 65536-point real FFT of the middle of the region (zero-padded when shorter), parabolic sub-bin interpolation.
     */
    fun peakHz(audio: AudioBuffer, from: Long, to: Long, minHz: Double, maxHz: Double): Double {
        val n = 65536
        val region = audio.slice(from.toInt(), to.toInt()).mono()
        val len = min(n, region.size)
        val start = (region.size - len) / 2
        val win = Window.hann(len, periodic = false)
        val x = FloatArray(n)
        for (i in 0 until len) x[i] = region[start + i] * win[i]
        val fft = RealFft(n)
        val re = FloatArray(fft.bins); val im = FloatArray(fft.bins)
        fft.forward(x, re, im)
        val binHz = SR.toDouble() / n
        val k0 = Math.ceil(minHz / binHz).toInt().coerceAtLeast(1)
        val k1 = Math.floor(maxHz / binHz).toInt().coerceAtMost(fft.bins - 2)
        var best = k0; var bestP = -1.0
        val p = DoubleArray(fft.bins) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        for (k in k0..k1) if (p[k] > bestP) { bestP = p[k]; best = k }
        val a = sqrt(p[best - 1]); val b = sqrt(p[best]); val c = sqrt(p[best + 1])
        val denom = a - 2 * b + c
        val delta = if (abs(denom) < 1e-12) 0.0 else 0.5 * (a - c) / denom
        return (best + delta) * binHz
    }
}
