package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.Mode
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.fft.RealFft
import dev.muisc.dsp.filter.Biquad
import dev.muisc.dsp.filter.BiquadFilter
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.dsp.stems.PseudoStemSeparator
import dev.muisc.dsp.stems.Stems
import dev.muisc.dsp.window.Window
import dev.muisc.transitions.AutomationLane
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.StemProvider
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.core.SongFixtures
import dev.muisc.transitions.core.SpliceCheck
import dev.muisc.transitions.synthetic.SyntheticTrack
import dev.muisc.transitions.synthetic.SyntheticTrackLoader
import dev.muisc.transitions.synthetic.SyntheticTracks
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fixtures for the stems / texture / bridge strategy tests: ground-truth [SyntheticTracks] pairs (real cues, bar
 * features, LTAS), features from [DefaultPairAnalyzer], inputs loaded through [SyntheticTrackLoader] with the deck
 * gain applied and a lazy pseudo-stem provider, plus the contract assertions every strategy test runs and a few
 * numeric helpers (band RMS, dominant frequency, kick alignment).
 */
internal object SbFixtures {
    const val SR = 44100
    /** Largest FFT the `dsp` RealFft supports; longer regions are truncated to their first 1.49 s. */
    const val MAX_FFT = 65536

    class Pair(val a: SyntheticTrack, val b: SyntheticTrack, val prefs: TransitionPrefs, val features: PairFeatures, val loader: SyntheticTrackLoader)

    fun song(bpm: Double, tonic: Int, mode: Mode, bars: Int = 32, introBars: Int = 4, outroBars: Int = 4, seed: Int = 7, outroFade: Boolean = false): SyntheticSong =
        SyntheticSong(bpm = bpm, tonic = tonic, mode = if (mode == Mode.MAJOR) dev.muisc.audio.synth.Mode.MAJOR else dev.muisc.audio.synth.Mode.MINOR,
            bars = bars, introBars = introBars, outroBars = outroBars, outroFade = outroFade, sampleRate = SR, seed = seed)

    /** Ground-truth pair; prefs target 3 LU under the quieter song so the dry material stays below 0 dBFS after the deck gain. */
    fun pair(songA: SyntheticSong, songB: SyntheticSong): Pair {
        val base = TransitionPrefs(sampleRate = SR, channels = 2)
        val a = SyntheticTracks.trackRef(songA, id = "A:" + SyntheticTracks.sourceId(songA), prefs = base)
        val b = SyntheticTracks.trackRef(songB, id = "B:" + SyntheticTracks.sourceId(songB), prefs = base)
        val prefs = base.copy(targetLufs = minOf(a.analysis.loudness.integratedLufs, b.analysis.loudness.integratedLufs).toDouble() - 3.0)
        val features = DefaultPairAnalyzer().features(a.analysis, b.analysis, prefs)
        return Pair(a, b, prefs, features, SyntheticTrackLoader(listOf(a, b)))
    }

    /** The renderer's input for a plan: windows via the loader (zero-padded), deck gains applied, lazy pseudo-stems. */
    fun input(plan: TransitionPlan, pair: Pair, features: PairFeatures = pair.features): TransitionInput {
        val aAudio = pair.loader.load(pair.a.trackRef, plan.aWindow, pair.prefs)
        val bAudio = pair.loader.load(pair.b.trackRef, plan.bWindow, pair.prefs)
        DeckGain.applyInPlace(aAudio, DeckGain.of(pair.a.analysis, pair.prefs))
        DeckGain.applyInPlace(bAudio, DeckGain.of(pair.b.analysis, pair.prefs))
        val stems = object : StemProvider {
            private val sa by lazy { PseudoStemSeparator().separate(aAudio) }
            private val sb by lazy { PseudoStemSeparator().separate(bAudio) }
            override fun aTail(): Stems = sa
            override fun bHead(): Stems = sb
        }
        return TransitionInput(plan, pair.a.trackRef, pair.b.trackRef, features, aAudio, bAudio, stems)
    }

    /** plan + input + render in one go. */
    fun render(strategy: TransitionStrategy, pair: Pair, params: Params = Params.EMPTY, seed: Long = 1L): Triple<TransitionPlan, TransitionInput, RenderedTransition> {
        val plan = strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, params, pair.prefs, seed)
        val input = input(plan, pair)
        return Triple(plan, input, strategy.render(input, RenderContext(pair.prefs, seed)))
    }

    /** The contract every strategy must honour: exact length, splice check clean, no clicks, peak ≤ 0 dBFS, finite. */
    fun assertContract(name: String, plan: TransitionPlan, input: TransitionInput, rendered: RenderedTransition) {
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames, "$name: output length == plan.expectedOutputFrames")
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input), "$name: splice contract")
        val report = ArtifactDetector(SR).analyze(rendered.audio)
        assertTrue(report.clicks.isEmpty(), "$name: clicks ${report.clicks}")
        assertTrue(report.clipRuns.isEmpty(), "$name: clipping ${report.clipRuns}")
        assertTrue(rendered.audio.peak() <= 1.0f, "$name: peak ${rendered.audio.peak()}")
        assertTrue(rendered.report.warnings.none { it.startsWith("click") || it.startsWith("clipping") }, "$name: ${rendered.report.warnings}")
        for (c in 0 until rendered.audio.channelCount) assertTrue(rendered.audio[c].all { it.isFinite() }, "$name: non-finite samples")
    }

    fun assertDeterministic(name: String, r1: RenderedTransition, r2: RenderedTransition) {
        assertEquals(r1.audio.frames, r2.audio.frames, "$name: same length")
        for (c in 0 until r1.audio.channelCount) assertTrue(r1.audio[c].contentEquals(r2.audio[c]), "$name: bit-identical renders (channel $c)")
    }

    fun lane(rendered: RenderedTransition, id: String): AutomationLane = rendered.plan.lanes.first { it.id == id }

    fun db(x: Double): Double = if (x <= 1e-12) -240.0 else 20.0 * log10(x)

    /** RMS of the mono mix of `[start, end)` band-limited to `[lowHz, highHz]` (two Butterworth biquads each side). */
    fun bandRms(audio: AudioBuffer, start: Int, end: Int, lowHz: Double, highHz: Double): Double {
        val s = start.coerceIn(0, audio.frames); val e = end.coerceIn(s, audio.frames)
        if (e <= s) return 0.0
        val x = audio.slice(s, e).mono().copyOf()
        val fs = audio.sampleRate.toDouble()
        if (lowHz > 0) { val hp = BiquadFilter(1, Biquad.highPass(lowHz, fs)); hp.process(x, x, x.size); hp.reset(); hp.process(x, x, x.size) }
        if (highHz < fs / 2) { val lp = BiquadFilter(1, Biquad.lowPass(highHz, fs)); lp.process(x, x, x.size); lp.reset(); lp.process(x, x, x.size) }
        var acc = 0.0
        for (v in x) acc += v.toDouble() * v
        return sqrt(acc / x.size)
    }

    fun rms(audio: AudioBuffer, start: Int, end: Int): Double = audio.slice(start.coerceIn(0, audio.frames), end.coerceIn(0, audio.frames)).rms().toDouble()

    /** Power spectrum (Hann, one FFT of the next power of two ≥ length) of the mono mix of `[start, end)`; index → Hz via [binHz]. */
    fun spectrum(audio: AudioBuffer, start: Int, end: Int): DoubleArray {
        val full = audio.slice(start, end).mono()
        val x = if (full.size > MAX_FFT) full.copyOf(MAX_FFT) else full
        var n = 1024
        while (n < x.size) n = n shl 1
        val w = Window.hann(x.size)
        val frame = FloatArray(n)
        for (i in x.indices) frame[i] = x[i] * w[i]
        val fft = RealFft(n)
        val re = FloatArray(fft.bins); val im = FloatArray(fft.bins)
        fft.forward(frame, re, im)
        return DoubleArray(fft.bins) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    fun binHz(spectrum: DoubleArray, sr: Int = SR): Double = sr / (2.0 * (spectrum.size - 1))

    /** Peak power of [spectrum] within ±[tolerance] (fraction) of [hz]. */
    fun peakNear(spectrum: DoubleArray, hz: Double, tolerance: Double = 0.03): Double {
        val bw = binHz(spectrum)
        val lo = ((hz * (1 - tolerance)) / bw).toInt().coerceAtLeast(0); val hi = ((hz * (1 + tolerance)) / bw).toInt().coerceAtMost(spectrum.size - 1)
        var m = 0.0
        for (k in lo..hi) if (spectrum[k] > m) m = spectrum[k]
        return m
    }

    /** Sample-wise `x - y` of two equally shaped renders (isolates what one parameter changed). */
    fun difference(x: AudioBuffer, y: AudioBuffer): AudioBuffer {
        require(x.frames == y.frames && x.channelCount == y.channelCount) { "shapes differ" }
        return AudioBuffer(x.sampleRate, Array(x.channelCount) { c -> FloatArray(x.frames) { i -> x[c][i] - y[c][i] } })
    }

    /** Summed power of [spectrum] over `[fromHz, toHz]` (FFT-domain, so no filter leakage between decades). */
    fun bandPower(spectrum: DoubleArray, fromHz: Double, toHz: Double): Double {
        val bw = binHz(spectrum)
        val lo = (fromHz / bw).toInt().coerceAtLeast(1); val hi = (toHz / bw).toInt().coerceAtMost(spectrum.size - 1)
        var s = 0.0
        for (k in lo..hi) s += spectrum[k]
        return s
    }

    /** Frequency of the strongest bin in `[fromHz, toHz]`. */
    fun dominantHz(spectrum: DoubleArray, fromHz: Double, toHz: Double): Double {
        val bw = binHz(spectrum)
        val lo = (fromHz / bw).toInt().coerceAtLeast(1); val hi = (toHz / bw).toInt().coerceAtMost(spectrum.size - 1)
        var best = lo
        for (k in lo..hi) if (spectrum[k] > spectrum[best]) best = k
        return best * bw
    }

    /** MIDI → Hz. */
    fun hz(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

    /** Bass root MIDI note (octave 2) of a synthetic song's bar. */
    fun bassRootMidi(song: SyntheticSong, bar: Int): Int {
        val scale = if (song.mode == dev.muisc.audio.synth.Mode.MAJOR) intArrayOf(0, 2, 4, 5, 7, 9, 11) else intArrayOf(0, 2, 3, 5, 7, 8, 10)
        val (deg, _) = song.chordForBar(bar)
        return 12 * 2 + song.tonic + scale[deg]
    }

    /**
     * Kick alignment against the `masterBeat` lane: for master beats `[fromBeat, toBeat)` the kick onset in the
     * render is compared with the lane's frame; the detector's own lag is cancelled with the bias measured on the
     * dry source at its own beats. Returns the offsets in ms (after bias removal).
     */
    fun kickOffsetsMs(rendered: RenderedTransition, fromBeat: Int, toBeat: Int, biasSource: AudioBuffer, biasBeatFrames: LongArray): DoubleArray {
        val lane = lane(rendered, "masterBeat")
        val expected = LongArray(toBeat - fromBeat) { k -> Math.round(lane.points[fromBeat + k].outputSec * SR) }
        val bias = SongFixtures.median(SongFixtures.kickOffsets(biasSource, biasBeatFrames))
        val offsets = SongFixtures.kickOffsets(rendered.audio, expected)
        return DoubleArray(offsets.size) { (offsets[it] - bias) / SR * 1000.0 }
    }

    fun assertMostWithin(name: String, offsetsMs: DoubleArray, maxMs: Double, fraction: Double = 0.9) {
        val ok = offsetsMs.count { abs(it) <= maxMs }
        assertTrue(ok >= Math.ceil(fraction * offsetsMs.size).toInt(), "$name: only $ok/${offsetsMs.size} kicks within $maxMs ms (offsets ${offsetsMs.map { "%.1f".format(it) }})")
    }
}
