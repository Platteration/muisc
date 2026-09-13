package dev.muisc.dsp.stretch

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.resample.Resampler
import dev.muisc.dsp.resample.SincKernel
import kotlin.math.pow

/**
 * Pitch shift by a number of semitones with the duration preserved, as "time-stretch then resample":
 *
 *  1. the input is time-stretched by `r = 2^(semitones/12)` with [stretcher] (exact length `round(frames * r)`),
 *  2. the stretched signal is resampled by `1/r` with the band-limited Kaiser-sinc [Resampler] (the resampler's
 *     `inRate / outRate` are set to `stretchedFrames / frames`, which makes the output length exactly
 *     `input.frames` and gives the sinc low-pass the right cutoff when shifting up).
 *
 * Positive semitones raise the pitch (`+12` = one octave up, 440 Hz -> 880 Hz), negative lower it. The output
 * has exactly the input's length, sample rate and channel count; `semitones = 0` returns a copy. Formants are
 * not preserved (this is the classic "chipmunk" DJ pitch shift, which is what key-shifting a whole track needs).
 */
class PitchShifter(
    val stretcher: TimeStretcher = WsolaStretcher(),
    val kernel: SincKernel = SincKernel.DEFAULT,
) {
    /**
     * Shifts [input] by [semitones]. [transients] (input frame positions) is forwarded to the stretcher's
     * transient protection.
     */
    fun shift(input: AudioBuffer, semitones: Double, transients: IntArray? = null): AudioBuffer {
        require(semitones.isFinite()) { "semitones must be finite" }
        val frames = input.frames
        if (frames == 0) return input.copy()
        val r = semitonesToRatio(semitones)
        val stretchedFrames = Math.round(frames * r).toInt().coerceAtLeast(1)
        if (stretchedFrames == frames) return input.copy()
        val stretched = stretcher.stretchToLength(input, stretchedFrames, transients)
        val resampler = Resampler(kernel)
        val out = Array(input.channelCount) { c ->
            val y = resampler.resample(stretched[c], stretchedFrames, frames)
            if (y.size == frames) y else y.copyOf(frames)
        }
        return AudioBuffer(input.sampleRate, out)
    }

    companion object {
        /** Frequency ratio of a shift of [semitones]: `2^(semitones / 12)`. */
        fun semitonesToRatio(semitones: Double): Double = 2.0.pow(semitones / 12.0)
    }
}
