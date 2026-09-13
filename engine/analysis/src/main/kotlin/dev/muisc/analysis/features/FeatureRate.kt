package dev.muisc.analysis.features

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.resample.Resampler
import kotlin.math.log10

/**
 * Shared conventions of the feature package.
 *
 * All spectral internals run on a **mono downmix at [SAMPLE_RATE]** (22.05 kHz, like the rhythm package),
 * but every position that leaves the package is in the caller's engine-rate frames (the input
 * [AudioBuffer.sampleRate]). Sample `n` of a downmix produced by [downmix] for the range `[start, end)`
 * sits at engine frame `start + n * engineRate / SAMPLE_RATE` (the [Resampler] is zero-phase).
 */
object FeatureRate {
    /** Analysis rate of every STFT-based feature in this package. */
    const val SAMPLE_RATE: Int = 22050

    /** Value used for "-inf dB" in float outputs (silence). */
    const val SILENCE_DB: Float = -120f

    /**
     * Mono downmix of engine frames `[startFrame, endFrame)` of [audio], resampled to [targetRate].
     * The range is clamped to the buffer; an empty range yields an empty array (never throws).
     */
    fun downmix(audio: AudioBuffer, startFrame: Long, endFrame: Long, resampler: Resampler, targetRate: Int = SAMPLE_RATE): FloatArray {
        val (start, end) = clampRange(audio, startFrame, endFrame)
        if (end <= start) return FloatArray(0)
        val mono = audio.mono()
        val region = if (start == 0 && end == audio.frames) mono else mono.copyOfRange(start, end)
        return if (audio.sampleRate == targetRate) (if (region === mono) mono.copyOf() else region) else resampler.resample(region, audio.sampleRate, targetRate)
    }

    /** Clamps an engine-frame range to `[0, frames]` with `start <= end`. */
    fun clampRange(audio: AudioBuffer, startFrame: Long, endFrame: Long): Pair<Int, Int> {
        val start = startFrame.coerceIn(0L, audio.frames.toLong()).toInt()
        val end = endFrame.coerceIn(start.toLong(), audio.frames.toLong()).toInt()
        return start to end
    }

    /** `10 log10(power)` floored at [SILENCE_DB]. */
    fun powerDb(power: Double): Float = if (power <= 0.0) SILENCE_DB else (10.0 * log10(power)).toFloat().coerceAtLeast(SILENCE_DB)

    /** `20 log10(amplitude)` floored at [SILENCE_DB]. */
    fun amplitudeDb(amplitude: Double): Float = if (amplitude <= 0.0) SILENCE_DB else (20.0 * log10(amplitude)).toFloat().coerceAtLeast(SILENCE_DB)
}
