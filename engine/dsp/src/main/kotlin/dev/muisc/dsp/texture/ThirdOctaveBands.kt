package dev.muisc.dsp.texture

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.RealFft
import dev.muisc.dsp.window.Window
import kotlin.math.log10
import kotlin.math.pow

/**
 * The 31 ISO 266 one-third-octave bands from 20 Hz to 20 kHz (band `b` has exact centre
 * `1000 * 10^((b - 17) / 10)` Hz and edges a factor `10^(1/20)` on either side; [CENTRES] holds the usual
 * nominal values) plus a long-term average spectrum (LTAS) measurement in those bands.
 *
 * Level convention: [measureDb] returns, per band, `10 log10` of the signal power in the band, i.e. the dBFS RMS
 * level of the band-limited signal. A full-scale sine (amplitude 1) gives -3.01 dB in its band and [FLOOR_DB]
 * elsewhere; unit-RMS white noise at 44.1 kHz gives about `10 log10(bandwidth / 22050)` per band (-30.9 dB in
 * the 1 kHz band). The same convention is used by [ShapedNoise] for its target envelopes, so
 * `measureDb(ShapedNoise.render(env))` reproduces `env`.
 *
 * The LTAS is a Welch estimate: Hann-windowed frames of [DEFAULT_FRAME] samples at 50 % overlap, power spectra
 * averaged, `S[k] = c_k |X[k]|^2 / (N * sum(w^2))` (`c_k = 2` for one-sided bins except DC and Nyquist), then
 * summed per band. Bins are assigned to the band whose centre is nearest on a log-frequency scale
 * ([bandOf]); DC is skipped; bins above 22.4 kHz fall in the top band.
 */
object ThirdOctaveBands {
    const val BANDS = 31
    const val DEFAULT_FRAME = 8192
    const val FLOOR_DB = -120f

    /** Nominal ISO centre frequencies in Hz. */
    val CENTRES: DoubleArray = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
        800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0,
        16000.0, 20000.0,
    )

    /** Exact (base-10) centre frequency of [band]. */
    fun exactCentre(band: Int): Double = 1000.0 * 10.0.pow((band - 17) / 10.0)

    /** Lower band edge `centre / 10^(1/20)`. */
    fun lowerEdge(band: Int): Double = exactCentre(band) / 10.0.pow(0.05)

    /** Upper band edge `centre * 10^(1/20)`. */
    fun upperEdge(band: Int): Double = exactCentre(band) * 10.0.pow(0.05)

    /** Index of the band whose centre is nearest to [freqHz] on a log scale (clamped to 0..30); -1 for f <= 0. */
    fun bandOf(freqHz: Double): Int {
        if (freqHz <= 0.0) return -1
        val b = Math.round(10.0 * log10(freqHz / 1000.0)).toInt() + 17
        return b.coerceIn(0, BANDS - 1)
    }

    /** Band index per FFT bin (`frameSize / 2 + 1` entries; DC = -1). */
    fun binBands(frameSize: Int, sampleRate: Int): IntArray =
        IntArray(frameSize / 2 + 1) { k -> if (k == 0) -1 else bandOf(k.toDouble() * sampleRate / frameSize) }

    /** Number of bins per band for a given geometry (bands with 0 bins cannot be represented at that frame size). */
    fun binCounts(frameSize: Int, sampleRate: Int): IntArray {
        val counts = IntArray(BANDS)
        for (b in binBands(frameSize, sampleRate)) if (b >= 0) counts[b]++
        return counts
    }

    /**
     * Interpolates per-band values (dB) to per-bin values (`frameSize / 2 + 1` entries) linearly in
     * log-frequency between the exact band centres, holding the edge bands outside the range; DC gets band 0.
     */
    fun interpolateToBins(bandsDb: FloatArray, frameSize: Int, sampleRate: Int, out: FloatArray? = null): FloatArray {
        require(bandsDb.size == BANDS) { "need $BANDS band values, got ${bandsDb.size}" }
        val bins = frameSize / 2 + 1
        val dst = out ?: FloatArray(bins)
        require(dst.size >= bins) { "out too small" }
        val logC = DoubleArray(BANDS) { log10(exactCentre(it)) }
        for (k in 0 until bins) {
            val f = k.toDouble() * sampleRate / frameSize
            if (f <= exactCentre(0)) { dst[k] = bandsDb[0]; continue }
            if (f >= exactCentre(BANDS - 1)) { dst[k] = bandsDb[BANDS - 1]; continue }
            val lf = log10(f)
            var b = 0
            while (b < BANDS - 2 && logC[b + 1] <= lf) b++
            val t = (lf - logC[b]) / (logC[b + 1] - logC[b])
            dst[k] = (bandsDb[b] + (bandsDb[b + 1] - bandsDb[b]) * t).toFloat()
        }
        return dst
    }

    /** LTAS of [x] in dB per band (see class doc). Signals shorter than a frame are zero-padded to one frame. */
    fun measureDb(x: FloatArray, sampleRate: Int, frameSize: Int = DEFAULT_FRAME): FloatArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val n = frameSize
        val hop = n / 2
        val window = Window.hann(n)
        var wpow = 0.0
        for (w in window) wpow += w.toDouble() * w
        val rfft = RealFft(n)
        val bins = rfft.bins
        val frame = FloatArray(n)
        val re = FloatArray(bins)
        val im = FloatArray(bins)
        val acc = DoubleArray(bins)
        var frames = 0
        var start = 0
        val last = maxOf(0, x.size - n)
        while (true) {
            for (i in 0 until n) {
                val idx = start + i
                frame[i] = if (idx < x.size) x[idx] * window[i] else 0f
            }
            rfft.forward(frame, re, im)
            for (k in 0 until bins) acc[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            frames++
            if (start >= last) break
            start = minOf(start + hop, last)
        }
        val bands = binBands(n, sampleRate)
        val power = DoubleArray(BANDS)
        val norm = 1.0 / (frames.toDouble() * n * wpow)
        for (k in 1 until bins) {
            val c = if (k == bins - 1) 1.0 else 2.0
            power[bands[k]] += c * acc[k] * norm
        }
        return FloatArray(BANDS) { b -> if (power[b] <= 1e-12) FLOOR_DB else (10.0 * log10(power[b])).toFloat().coerceAtLeast(FLOOR_DB) }
    }

    /** LTAS of the mono mix of [buffer]. */
    fun measureDb(buffer: AudioBuffer, frameSize: Int = DEFAULT_FRAME): FloatArray =
        measureDb(buffer.mono(), buffer.sampleRate, frameSize)
}
