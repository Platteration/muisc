package dev.muisc.dsp.texture

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.RealFft
import dev.muisc.dsp.window.Window
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Spectral freeze / Paulstretch-style texture bed: turns a short excerpt into a stationary "pad" of arbitrary
 * length whose long-term spectrum equals the excerpt's.
 *
 * Algorithm (Paul Nasca's Paulstretch with an infinite stretch factor, i.e. a spectral hold):
 *  1. [analyze]: STFT of the source with a large sqrt-Hann frame ([frameSize], default 8192) at [hop]; the
 *     magnitude template `M[k] = sqrt(mean_t |X_t[k]|^2)` is the RMS magnitude over all frames.
 *  2. Synthesis: for every output frame the template gets a uniformly random phase per bin (seeded
 *     `kotlin.random.Random`; a new draw per frame so consecutive frames are uncorrelated), is inverse-transformed,
 *     sqrt-Hann windowed and overlap-added at [hop]. The gain `sqrt(4 hop / frameSize)` makes the expected output
 *     power equal to the source power for any hop (frames are uncorrelated so their powers add).
 *  3. Optional slow morph: [targetBandGainsDb] (31 one-third-octave band gains, see [ThirdOctaveBands]) are
 *     interpolated to bins and applied with a weight rising linearly from 0 at the start to 1 at the end of the
 *     output.
 *  4. Optional [tonalEmphasis]: bins that do not rise at least [tonalThresholdDb] above the local median
 *     magnitude (over ±[medianHalfWidth] bins) are attenuated by [tonalRejectDb], leaving the harmonic peaks —
 *     a pad-like bed instead of a noisy one (a threshold of 0 dB keeps exactly the bins above the median).
 *
 * Stereo sources are analysed per channel and synthesised with per-channel seeds (decorrelated). The output
 * length is exact, it starts and ends at full level (frames overlap the edges), and it is bit-identical for a
 * given seed.
 */
class SpectralFreeze(
    val sampleRate: Int,
    val frameSize: Int = 8192,
    val hop: Int = frameSize / 4,
    val seed: Int = 1,
    val tonalEmphasis: Boolean = false,
    val medianHalfWidth: Int = 24,
    val tonalThresholdDb: Double = 6.0,
    val tonalRejectDb: Double = -40.0,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(frameSize >= 16 && (frameSize and (frameSize - 1)) == 0) { "frameSize must be a power of two >= 16" }
        require(hop in 1..frameSize) { "hop must be in [1, frameSize]" }
        require(medianHalfWidth >= 1) { "medianHalfWidth must be >= 1" }
    }

    /** Number of bins per frame. */
    val bins: Int = frameSize / 2 + 1
    private val window = Window.sqrtHann(frameSize)
    private val rfft = RealFft(frameSize)

    /** RMS magnitude template of [source] (length [bins]); tonal emphasis applied when enabled. */
    fun analyze(source: FloatArray): FloatArray {
        val n = frameSize
        val frame = FloatArray(n)
        val re = FloatArray(bins)
        val im = FloatArray(bins)
        val acc = DoubleArray(bins)
        var frames = 0
        var start = 0
        val last = maxOf(0, source.size - n)
        while (true) {
            for (i in 0 until n) {
                val idx = start + i
                frame[i] = if (idx < source.size) source[idx] * window[i] else 0f
            }
            rfft.forward(frame, re, im)
            for (k in 0 until bins) acc[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            frames++
            if (start >= last) break
            start = minOf(start + hop, last)
        }
        val mag = FloatArray(bins) { sqrt(acc[it] / frames).toFloat() }
        if (tonalEmphasis) applyTonalEmphasis(mag)
        return mag
    }

    /** Attenuates every bin that is not at least [tonalThresholdDb] above the local median (±medianHalfWidth bins). */
    fun applyTonalEmphasis(mag: FloatArray) {
        val hw = medianHalfWidth
        val work = FloatArray(2 * hw + 1)
        val keep = BooleanArray(mag.size)
        val thr = 10.0.pow(tonalThresholdDb / 20.0).toFloat()
        for (k in mag.indices) {
            val a = maxOf(0, k - hw)
            val b = minOf(mag.size - 1, k + hw)
            val len = b - a + 1
            System.arraycopy(mag, a, work, 0, len)
            java.util.Arrays.sort(work, 0, len)
            val med = if (len % 2 == 1) work[len / 2] else 0.5f * (work[len / 2 - 1] + work[len / 2])
            keep[k] = mag[k] > med * thr
        }
        val att = 10.0.pow(tonalRejectDb / 20.0).toFloat()
        for (k in mag.indices) if (!keep[k]) mag[k] *= att
    }

    /** Renders a bed of exactly [outputFrames] frames from [source] (one template and seed per channel). */
    fun render(source: AudioBuffer, outputFrames: Int, targetBandGainsDb: FloatArray? = null): AudioBuffer {
        require(source.sampleRate == sampleRate) { "source sample rate ${source.sampleRate} != $sampleRate" }
        val out = Array(source.channelCount) { c -> synthesize(analyze(source[c]), outputFrames, seed + 7919 * c, targetBandGainsDb) }
        return AudioBuffer(sampleRate, out)
    }

    /** Renders [channels] decorrelated channels of exactly [outputFrames] frames from a magnitude template. */
    fun renderFromSpectrum(magnitude: FloatArray, outputFrames: Int, channels: Int = 1, targetBandGainsDb: FloatArray? = null): AudioBuffer =
        AudioBuffer(sampleRate, Array(channels) { c -> synthesize(magnitude, outputFrames, seed + 7919 * c, targetBandGainsDb) })

    /** Random-phase overlap-add synthesis of one channel. */
    fun synthesize(magnitude: FloatArray, outputFrames: Int, channelSeed: Int, targetBandGainsDb: FloatArray? = null): FloatArray {
        require(magnitude.size >= bins) { "template needs $bins bins" }
        require(outputFrames >= 0) { "outputFrames must be >= 0" }
        val n = frameSize
        val out = FloatArray(outputFrames)
        if (outputFrames == 0) return out
        val rnd = Random(channelSeed)
        val re = FloatArray(bins)
        val im = FloatArray(bins)
        val frame = FloatArray(n)
        val targetDb = targetBandGainsDb?.let { ThirdOctaveBands.interpolateToBins(it, n, sampleRate) }
        val gain = FloatArray(bins) { 1f }
        val norm = sqrt(4.0 * hop / n).toFloat()
        var start = -(n - hop)
        while (start < outputFrames) {
            if (targetDb != null) {
                val frac = ((start + n / 2).toDouble() / outputFrames).coerceIn(0.0, 1.0)
                for (k in 0 until bins) gain[k] = 10.0.pow(targetDb[k] * frac / 20.0).toFloat()
            }
            for (k in 0 until bins) {
                val m = magnitude[k] * gain[k]
                val phi = rnd.nextDouble() * 2.0 * PI
                re[k] = (m * cos(phi)).toFloat()
                im[k] = (m * sin(phi)).toFloat()
            }
            // DC and Nyquist must be real: keep a random sign only.
            re[0] = if (rnd.nextBoolean()) magnitude[0] * gain[0] else -magnitude[0] * gain[0]; im[0] = 0f
            re[bins - 1] = if (rnd.nextBoolean()) magnitude[bins - 1] * gain[bins - 1] else -magnitude[bins - 1] * gain[bins - 1]; im[bins - 1] = 0f
            rfft.inverse(re, im, frame)
            val a = maxOf(0, -start)
            val b = minOf(n, outputFrames - start)
            for (i in a until b) out[start + i] += frame[i] * window[i] * norm
            start += hop
        }
        return out
    }
}
