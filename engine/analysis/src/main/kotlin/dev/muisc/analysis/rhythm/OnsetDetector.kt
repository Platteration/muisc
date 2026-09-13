package dev.muisc.analysis.rhythm

import dev.muisc.dsp.fft.Stft
import dev.muisc.dsp.fft.StftFrameSink
import dev.muisc.dsp.fft.magnitude
import dev.muisc.dsp.mel.MelFilterbank
import dev.muisc.dsp.window.Window
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Frame-rate features produced by [OnsetDetector] for one mono signal. Frame `t` is centred on sample
 * `t * hop` (librosa `center=True` convention), i.e. at `t * hopSeconds` seconds from the start of the signal.
 *
 * - [odf] is the SuperFlux onset detection function, normalised by a running maximum (≈ 0..1, exactly 1 at the
 *   maximum of every ~5 s neighbourhood). `odf[0]` is always 0.
 * - [lowOdf] is the same flux restricted to the mel bands below ~150 Hz (kick drum / bass), normalised the same
 *   way. It drives the tempo-octave and downbeat decisions.
 * - [rawOdf] / [rawLowOdf] are the un-normalised sums (log-magnitude units) so callers can measure how much of
 *   the flux lives in the low band.
 * - [chroma] is a 12-bin pitch-class magnitude profile per frame (C = 0), un-normalised.
 * - [energy] is the mean log-compressed mel magnitude per frame (a loudness proxy in natural-log units).
 */
class OnsetFeatures(
    val sampleRate: Int,
    val hop: Int,
    val odf: FloatArray,
    val lowOdf: FloatArray,
    val rawOdf: FloatArray,
    val rawLowOdf: FloatArray,
    val chroma: Array<FloatArray>,
    val energy: FloatArray,
) {
    /** Seconds between consecutive ODF frames (`hop / sampleRate`, ≈ 11.6 ms for 256 @ 22.05 kHz). */
    val hopSeconds: Double get() = hop.toDouble() / sampleRate
    val frames: Int get() = odf.size

    /** Time in seconds of the centre of frame [t]. */
    fun frameTime(t: Int): Double = t * hopSeconds

    /** Frame whose centre is nearest to [seconds], clamped to the valid range. */
    fun frameAt(seconds: Double): Int = (seconds / hopSeconds).roundToInt().coerceIn(0, max(0, frames - 1))

    /** Share of the raw flux that lives in the low band (0..1); ~0 for material without kick / bass. */
    val lowBandFraction: Double by lazy {
        var a = 0.0; var b = 0.0
        for (t in 0 until frames) { a += rawOdf[t]; b += rawLowOdf[t] }
        if (a > 0.0) b / a else 0.0
    }

    /** Weight of the low-band ODF in the combined signals, see [lowBandWeightFor]. */
    fun lowBandWeight(): Double = lowBandWeightFor(lowBandFraction)

    /**
     * The onset function the beat tracker uses: `(odf + g * lowOdf) / (1 + g)` with `g = lowBandWeight()`,
     * i.e. the broadband flux with kick / bass onsets emphasised. Unit scale like [odf].
     */
    fun trackingOdf(lowWeight: Double = lowBandWeight()): FloatArray {
        val g = lowWeight.toFloat()
        val k = 1f / (1f + g)
        return FloatArray(frames) { (odf[it] + g * lowOdf[it]) * k }
    }

    /**
     * Peak-picked onset times in seconds: a frame is an onset when it is the maximum of its ±[localMaxFrames]
     * neighbourhood, at least [threshold] and at least [delta] above the mean ODF of the surrounding
     * [localMeanSec] (adaptive threshold, cf. Böck, Krebs & Schedl 2012), and at least [minIntervalSec] after the
     * previous onset. Times are ODF frame centres plus [leadCompensationSec]: the flux of a percussive event peaks
     * while the STFT window is still sliding onto the transient, i.e. slightly *before* the waveform onset, and
     * the compensation (measured on click tracks, ≈ half a hop) re-centres the reported time on the transient.
     */
    fun onsetTimes(
        threshold: Float = 0.1f,
        delta: Float = 0.05f,
        localMeanSec: Double = 1.0,
        localMaxFrames: Int = 2,
        minIntervalSec: Double = 0.03,
        leadCompensationSec: Double = ONSET_LEAD_COMPENSATION_SEC,
    ): DoubleArray {
        val n = frames
        if (n == 0) return DoubleArray(0)
        val prefix = DoubleArray(n + 1)
        for (t in 0 until n) prefix[t + 1] = prefix[t] + odf[t]
        val half = max(1, (localMeanSec / hopSeconds / 2).roundToInt())
        val minGap = max(1, (minIntervalSec / hopSeconds).roundToInt())
        val out = ArrayList<Double>()
        var last = -minGap
        for (t in 0 until n) {
            val v = odf[t]
            if (v < threshold) continue
            var isMax = true
            val lo = max(0, t - localMaxFrames)
            val hi = min(n - 1, t + localMaxFrames)
            for (u in lo..hi) {
                if (u == t) continue
                // Ties resolve to the earliest frame so a plateau yields exactly one onset.
                if (odf[u] > v || (odf[u] == v && u < t)) { isMax = false; break }
            }
            if (!isMax) continue
            val a = max(0, t - half)
            val b = min(n, t + half + 1)
            val mean = (prefix[b] - prefix[a]) / (b - a)
            if (v < mean + delta) continue
            if (t - last < minGap) continue
            out.add(max(0.0, frameTime(t) + leadCompensationSec))
            last = t
        }
        return out.toDoubleArray()
    }

    companion object {
        /** Default lead compensation for [onsetTimes] (half of the default 256-sample hop at 22.05 kHz). */
        const val ONSET_LEAD_COMPENSATION_SEC = 0.5 * 256.0 / RhythmAnalyzer.ANALYSIS_SAMPLE_RATE
        /** Low-band flux share below which the low band is considered absent (transient splatter alone gives ~4 %). */
        const val LOW_BAND_ZERO_FRACTION = 0.04
        /** Low-band flux share at which the low-band ODF gets full weight (kick / bass driven music has 0.12+). */
        const val LOW_BAND_FULL_FRACTION = 0.12

        /**
         * Weight (0..1) of the low-band ODF for a given share of flux in the low band: 0 up to
         * [LOW_BAND_ZERO_FRACTION] (the splatter any broadband transient leaves in the lowest bands), rising
         * linearly to 1 at [LOW_BAND_FULL_FRACTION], so leakage in material without low end is never amplified.
         */
        fun lowBandWeightFor(fraction: Double): Double =
            ((fraction - LOW_BAND_ZERO_FRACTION) / (LOW_BAND_FULL_FRACTION - LOW_BAND_ZERO_FRACTION)).coerceIn(0.0, 1.0)
    }
}

/**
 * Spectral-flux onset detection function in the style of SuperFlux (Böck & Widmer, "Maximum filter vibrato
 * suppression for onset detection", DAFx 2013):
 *
 * 1. STFT with a [frameSize]-point Hann window and hop [hop] (defaults 1024 / 256 at 22.05 kHz ≈ 46 ms / 11.6 ms).
 * 2. Magnitudes are scaled so a full-scale sine reads ≈ 1, summed into [nMels] triangular mel bands
 *    ([fMin]..[fMax] Hz, peak-normalised filters) and log-compressed as `ln(1 + lambda * M)`.
 * 3. Flux: for every band, the half-wave-rectified difference between the current frame and a maximum filter over
 *    `2 * maxFilterHalfWidth + 1` neighbouring bands of the previous frame, summed over bands (the maximum filter
 *    makes slow pitch drifts / vibrato produce no flux).
 * 4. Normalisation by the running maximum over ±[normWindowSec]/2 (floored at [normFloor] of the global maximum
 *    so silence is not amplified), giving a unit-scale ODF suitable for the [BeatTracker].
 *
 * The same flux restricted to the mel bands whose lower edge is below [lowBandMaxHz] is returned as the
 * low-band ODF. A 12-bin chroma (55 Hz .. 4.2 kHz) and a frame energy are computed from the same spectrum so
 * that the downbeat estimator needs no second STFT.
 *
 * Processing is streaming per frame (only two mel frames are alive at any time) and deterministic.
 */
class OnsetDetector(
    val sampleRate: Int = RhythmAnalyzer.ANALYSIS_SAMPLE_RATE,
    val frameSize: Int = 1024,
    val hop: Int = 256,
    val nMels: Int = 40,
    val fMin: Double = 50.0,
    val fMax: Double = 11000.0,
    val lambda: Float = 10f,
    val maxFilterHalfWidth: Int = 1,
    val lowBandMaxHz: Double = 150.0,
    val normWindowSec: Double = 5.0,
    val normFloor: Float = 0.1f,
) {
    init {
        require(sampleRate > 0 && frameSize > 0 && hop in 1..frameSize)
        require(fMax <= sampleRate / 2.0) { "fMax must not exceed Nyquist" }
        require(maxFilterHalfWidth >= 0)
    }

    private val window = Window.hann(frameSize)
    private val mel = MelFilterbank(frameSize, sampleRate, nMels, fMin, fMax, normalize = false)
    private val bins = frameSize / 2 + 1
    private val magScale: Float = run { var s = 0.0; for (w in window) s += w; (2.0 / s).toFloat() }

    /** Number of leading mel bands that form the low-band ODF (bands whose lower edge is below [lowBandMaxHz]). */
    val lowBandCount: Int = run {
        var n = 0
        for (m in 0 until nMels) if (mel.edgeFrequencies[m] < lowBandMaxHz) n = m + 1
        max(1, n)
    }

    /** Seconds per ODF frame. */
    val hopSeconds: Double get() = hop.toDouble() / sampleRate

    /** Pitch class (0 = C) of each FFT bin in the chroma range, or -1 outside it. */
    private val chromaClass = IntArray(bins) { k ->
        val f = k.toDouble() * sampleRate / frameSize
        if (f < CHROMA_MIN_HZ || f > CHROMA_MAX_HZ) -1
        else Math.floorMod((12.0 * log2(f / 440.0)).roundToInt() + 9, 12)
    }

    /** Computes the features of a mono signal at [sampleRate]. Never throws on empty input (returns one zero frame). */
    fun analyze(mono: FloatArray): OnsetFeatures {
        val stft = Stft(frameSize, hop, window, center = true)
        val frames = stft.frameCount(mono.size)
        val rawOdf = FloatArray(frames)
        val rawLow = FloatArray(frames)
        val energy = FloatArray(frames)
        val chroma = Array(frames) { FloatArray(12) }

        val mag = FloatArray(bins)
        var cur = FloatArray(nMels)
        var prev = FloatArray(nMels)
        val lam = lambda
        val hw = maxFilterHalfWidth
        val lowCount = lowBandCount
        val cls = chromaClass
        val sink = StftFrameSink { idx, re, im ->
            magnitude(re, im, mag, bins)
            for (k in 0 until bins) mag[k] *= magScale
            mel.apply(mag, cur)
            var e = 0f
            for (m in 0 until nMels) { val v = ln(1f + lam * cur[m]); cur[m] = v; e += v }
            energy[idx] = e / nMels
            val c = chroma[idx]
            for (k in 0 until bins) { val pc = cls[k]; if (pc >= 0) c[pc] += mag[k] }
            if (idx > 0) {
                var flux = 0f
                var low = 0f
                for (m in 0 until nMels) {
                    val lo = max(0, m - hw)
                    val hi = min(nMels - 1, m + hw)
                    var ref = prev[lo]
                    for (j in lo + 1..hi) if (prev[j] > ref) ref = prev[j]
                    val d = cur[m] - ref
                    if (d > 0f) { flux += d; if (m < lowCount) low += d }
                }
                rawOdf[idx] = flux
                rawLow[idx] = low
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        stft.process(mono, 0, mono.size, sink)
        stft.flush(sink)

        val normWin = max(1, (normWindowSec / hopSeconds).roundToInt())
        val odf = normaliseByRunningMax(rawOdf, normWin, normFloor)
        val lowOdf = normaliseByRunningMax(rawLow, normWin, normFloor)
        return OnsetFeatures(sampleRate, hop, odf, lowOdf, rawOdf, rawLow, chroma, energy)
    }

    companion object {
        const val CHROMA_MIN_HZ = 55.0
        const val CHROMA_MAX_HZ = 4200.0

        /**
         * Divides [x] by its running maximum over a centred window of about [windowFrames] frames (computed
         * block-wise in O(n): five blocks of `windowFrames / 5`), floored at `floorFraction * max(x)`.
         * Returns zeros when the signal is entirely zero.
         */
        fun normaliseByRunningMax(x: FloatArray, windowFrames: Int, floorFraction: Float): FloatArray {
            val n = x.size
            val out = FloatArray(n)
            if (n == 0) return out
            var global = 0f
            for (v in x) if (v > global) global = v
            if (global <= 0f) return out
            val block = max(1, windowFrames / 5)
            val nBlocks = (n + block - 1) / block
            val blockMax = FloatArray(nBlocks)
            for (t in 0 until n) { val b = t / block; if (x[t] > blockMax[b]) blockMax[b] = x[t] }
            val floor = global * floorFraction
            for (t in 0 until n) {
                val b = t / block
                var m = floor
                for (j in max(0, b - 2)..min(nBlocks - 1, b + 2)) if (blockMax[j] > m) m = blockMax[j]
                out[t] = x[t] / m
            }
            return out
        }
    }
}
