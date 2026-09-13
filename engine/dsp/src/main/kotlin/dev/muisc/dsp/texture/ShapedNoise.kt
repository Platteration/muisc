package dev.muisc.dsp.texture

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.RealFft
import dev.muisc.dsp.resample.VariableRateResampler
import dev.muisc.dsp.window.Window
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Seeded white noise shaped to a target one-third-octave envelope by STFT-domain multiplication.
 *
 * - Source: uniform white noise from `kotlin.random.Random(seed + 7919 * channel)` scaled to unit RMS.
 * - Shaping: sqrt-Hann STFT at [frameSize] with 50 % overlap (WOLA: analysis × synthesis window = Hann, exactly
 *   constant-overlap-add, so unity gains reproduce the noise sample-exactly); every bin is multiplied by the
 *   gain of its band, `g_b = 10^(L_b / 20) / sqrt(bins_b / (frameSize / 2))`, so that the band's RMS level is
 *   `L_b` dBFS in the [ThirdOctaveBands.measureDb] convention (bins are assigned to bands the same way).
 * - Morph: with [render]'s `endEnvelopeDb` the gains move linearly in dB from the start to the end envelope
 *   over the output (evaluated at each frame centre).
 *
 * Bands that hold no bin at the chosen frame size (the lowest bands at small frame sizes) are skipped.
 * Output length is exact and the result is bit-identical for a seed. Levels are absolute: keep envelopes
 * around -30 dB per band for a mix-ready bed (31 bands at -30 dB sum to about -15 dBFS RMS).
 */
class ShapedNoise(
    val sampleRate: Int,
    val frameSize: Int = ThirdOctaveBands.DEFAULT_FRAME,
    val seed: Int = 1,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(frameSize >= 16 && (frameSize and (frameSize - 1)) == 0) { "frameSize must be a power of two >= 16" }
    }

    val bins: Int = frameSize / 2 + 1
    private val hop = frameSize / 2
    private val window = Window.sqrtHann(frameSize)
    private val rfft = RealFft(frameSize)
    private val binBand = ThirdOctaveBands.binBands(frameSize, sampleRate)
    private val binCount = ThirdOctaveBands.binCounts(frameSize, sampleRate)

    /** Per-bin linear gains for unit-RMS white noise that produce [envelopeDb] (31 band levels in dBFS). */
    fun binGains(envelopeDb: FloatArray, out: FloatArray? = null): FloatArray {
        require(envelopeDb.size == ThirdOctaveBands.BANDS) { "need ${ThirdOctaveBands.BANDS} band levels" }
        val g = out ?: FloatArray(bins)
        val half = frameSize / 2
        for (k in 0 until bins) {
            val b = binBand[k]
            g[k] = if (b < 0 || binCount[b] == 0) 0f else (10.0.pow(envelopeDb[b] / 20.0) / sqrt(binCount[b].toDouble() / half)).toFloat()
        }
        return g
    }

    /**
     * Renders [outputFrames] frames of noise shaped to [envelopeDb], optionally morphing to [endEnvelopeDb] by the
     * end; [channels] independent (decorrelated) channels.
     */
    fun render(outputFrames: Int, envelopeDb: FloatArray, endEnvelopeDb: FloatArray? = null, channels: Int = 1): AudioBuffer {
        require(outputFrames >= 0 && channels > 0) { "outputFrames must be >= 0 and channels > 0" }
        val gA = binGains(envelopeDb)
        val gB = endEnvelopeDb?.let { binGains(it) }
        val out = Array(channels) { c -> renderChannel(outputFrames, gA, gB, seed + 7919 * c) }
        return AudioBuffer(sampleRate, out)
    }

    private fun renderChannel(outputFrames: Int, gA: FloatArray, gB: FloatArray?, channelSeed: Int): FloatArray {
        val n = frameSize
        val out = FloatArray(outputFrames)
        if (outputFrames == 0) return out
        val rnd = Random(channelSeed)
        val amp = sqrt(3.0).toFloat() // uniform in [-1, 1] has RMS 1/sqrt(3)
        val noise = FloatArray(outputFrames + n) { amp * (rnd.nextFloat() * 2f - 1f) }
        val frame = FloatArray(n)
        val re = FloatArray(bins)
        val im = FloatArray(bins)
        val gain = FloatArray(bins)
        val lnA = if (gB != null) FloatArray(bins) { if (gA[it] > 0f) ln(gA[it].toDouble()).toFloat() else -60f } else null
        val lnB = if (gB != null) FloatArray(bins) { if (gB[it] > 0f) ln(gB[it].toDouble()).toFloat() else -60f } else null
        var start = -hop
        while (start < outputFrames) {
            if (gB != null && lnA != null && lnB != null) {
                val frac = ((start + n / 2).toDouble() / outputFrames).coerceIn(0.0, 1.0).toFloat()
                for (k in 0 until bins) gain[k] = if (gA[k] > 0f && gB[k] > 0f) exp((lnA[k] + (lnB[k] - lnA[k]) * frac).toDouble()).toFloat() else 0f
            } else {
                System.arraycopy(gA, 0, gain, 0, bins)
            }
            for (i in 0 until n) {
                val idx = start + i
                frame[i] = if (idx >= 0) noise[idx] * window[i] else 0f
            }
            rfft.forward(frame, re, im)
            for (k in 0 until bins) { re[k] *= gain[k]; im[k] *= gain[k] }
            rfft.inverse(re, im, frame)
            val a = maxOf(0, -start)
            val b = minOf(n, outputFrames - start)
            for (i in a until b) out[start + i] += frame[i] * window[i]
            start += hop
        }
        return out
    }
}

/**
 * Granular texture generator: turns a short source excerpt into an endless, evolving texture.
 *
 * [voices] independent voices each play a stream of grains back to back with [overlap]-fold overlap (a new
 * grain starts every `length / overlap` frames). Every grain has a seeded random length in
 * `[minGrainMs, maxGrainMs]`, a random source position anywhere in the excerpt, a random pitch offset within
 * ±[pitchJitterSemitones] (read with 4-point Hermite interpolation, [VariableRateResampler.hermite]) and a Tukey
 * window with taper fraction [tukeyAlpha] (cosine ramps at both ends, flat top), which is what keeps the
 * output click-free: every grain starts and ends at zero. The voices start at random negative offsets so the
 * density is full from the first sample. Output gain is `1 / sqrt(voices * overlap * mean(window^2))`, so the
 * texture's RMS is about the excerpt's RMS. Multi-channel sources keep their image (all channels share the
 * grain schedule). Deterministic for a seed; exact output length.
 */
class Granulator(
    val sampleRate: Int,
    val seed: Int = 1,
    val minGrainMs: Double = 120.0,
    val maxGrainMs: Double = 300.0,
    val voices: Int = 6,
    val pitchJitterSemitones: Double = 0.3,
    val tukeyAlpha: Double = 0.5,
    val overlap: Double = 2.0,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(minGrainMs > 0.0 && maxGrainMs >= minGrainMs) { "grain length range invalid" }
        require(voices >= 1 && overlap >= 1.0) { "voices >= 1 and overlap >= 1 required" }
        require(tukeyAlpha in 0.0..1.0) { "tukeyAlpha must be in [0, 1]" }
        require(pitchJitterSemitones >= 0.0) { "pitchJitterSemitones must be >= 0" }
    }

    /** Renders exactly [outputFrames] frames from `source[sourceStart, sourceEnd)`. */
    fun render(source: AudioBuffer, outputFrames: Int, sourceStart: Int = 0, sourceEnd: Int = source.frames): AudioBuffer {
        require(outputFrames >= 0) { "outputFrames must be >= 0" }
        val s0 = sourceStart.coerceIn(0, source.frames)
        val s1 = sourceEnd.coerceIn(s0, source.frames)
        require(s1 > s0) { "empty source range" }
        val ch = source.channelCount
        val out = Array(ch) { FloatArray(outputFrames) }
        if (outputFrames == 0) return AudioBuffer(source.sampleRate, out)
        val rnd = Random(seed)
        val minL = maxOf(4, Math.round(minGrainMs * sampleRate / 1000.0).toInt())
        val maxL = maxOf(minL, Math.round(maxGrainMs * sampleRate / 1000.0).toInt())
        val meanW2 = (1.0 - tukeyAlpha) + tukeyAlpha * 3.0 / 8.0
        val gain = (1.0 / sqrt(voices * overlap * meanW2)).toFloat()
        val srcLen = source.frames
        for (v in 0 until voices) {
            var t = -(rnd.nextDouble() * maxL).toInt()
            while (t < outputFrames) {
                val len = minL + rnd.nextInt(maxL - minL + 1)
                val pitch = if (pitchJitterSemitones > 0.0) 2.0.pow(pitchJitterSemitones * (2.0 * rnd.nextDouble() - 1.0) / 12.0) else 1.0
                val span = ceil(len * pitch).toInt() + 4
                val maxPos = (s1 - s0) - span
                val pos0 = if (maxPos > 0) s0 + rnd.nextDouble() * maxPos else s0.toDouble()
                val taper = tukeyAlpha * (len - 1) / 2.0
                val a = maxOf(0, -t)
                val b = minOf(len, outputFrames - t)
                for (c in 0 until ch) {
                    val src = source[c]
                    val dst = out[c]
                    for (i in a until b) {
                        val d = minOf(i, len - 1 - i).toDouble()
                        val w = if (d < taper) (0.5 - 0.5 * cos(PI * d / taper)).toFloat() else 1f
                        dst[t + i] += VariableRateResampler.hermite(src, srcLen, pos0 + i * pitch) * w * gain
                    }
                }
                t += maxOf(1, Math.round(len / overlap).toInt())
            }
        }
        return AudioBuffer(source.sampleRate, out)
    }
}
