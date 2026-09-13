package dev.muisc.dsp.fx

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.resample.VariableRateResampler
import kotlin.math.exp
import kotlin.math.pow

/** Shape of the speed curve of a [VinylBrake]. */
enum class BrakeCurve {
    /** `speed(t) = (1 - t)^exponent` — constant deceleration for exponent 1, a "heavy platter" for 2. */
    POWER,
    /** `speed(t) = (e^{-k t} - e^{-k}) / (1 - e^{-k})` — fast initial drop, long tail, reaching exactly 0 at t = 1. */
    EXPONENTIAL,
}

/**
 * Turntable "power off" brake and "power on" spin-up, rendered with the vinyl-style [VariableRateResampler]
 * (4-point Hermite interpolation with a per-block ratio ramp): pitch and speed drop together, exactly like a
 * platter slowing down.
 *
 * The playback ratio follows [speedAt] (1 -> 0 over the brake, 0 -> 1 over a spin-up) sampled per block of
 * [blockFrames] with linear interpolation inside the block. With [velocityGain] (default) the output is also
 * multiplied by the instantaneous speed, modelling a magnetic cartridge whose output is proportional to stylus
 * velocity; it guarantees that a brake ends in exact digital silence (and a spin-up starts from it) instead of
 * freezing on a DC value.
 *
 * Output lengths are exact: [brake] returns `outputFrames` frames (default `startFrame + brakeFrames`), the
 * part after the brake being silence; [spinUp] returns `outputFrames` frames with normal playback after the
 * ramp.
 */
class VinylBrake(
    val curve: BrakeCurve = BrakeCurve.POWER,
    val exponent: Double = 2.0,
    val expK: Double = 4.0,
    val velocityGain: Boolean = true,
    val blockFrames: Int = 64,
) {
    init {
        require(exponent > 0.0 && expK > 0.0) { "exponent and expK must be positive" }
        require(blockFrames > 0) { "blockFrames must be positive" }
    }

    /** Playback speed (ratio) at normalised brake time `t` in [0, 1]: 1 at t = 0, exactly 0 at t = 1, monotonic. */
    fun speedAt(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return when (curve) {
            BrakeCurve.POWER -> (1.0 - x).pow(exponent)
            BrakeCurve.EXPONENTIAL -> {
                val e = exp(-expK)
                ((exp(-expK * x) - e) / (1.0 - e)).coerceAtLeast(0.0)
            }
        }
    }

    /**
     * Plays [input] normally up to [startFrame], then brakes to a stop over [brakeFrames] frames. The result has
     * exactly [outputFrames] frames; everything after the brake is silence.
     */
    fun brake(input: AudioBuffer, startFrame: Int, brakeFrames: Int, outputFrames: Int = startFrame + brakeFrames): AudioBuffer {
        require(startFrame >= 0 && brakeFrames >= 0 && outputFrames >= 0) { "frame counts must be >= 0" }
        val ch = input.channelCount
        val out = Array(ch) { FloatArray(outputFrames) }
        val pre = minOf(startFrame, outputFrames, input.frames)
        for (c in 0 until ch) System.arraycopy(input[c], 0, out[c], 0, pre)
        val rs = VariableRateResampler(ch)
        rs.reset(startFrame.toDouble())
        renderRamp(input, out, startFrame, brakeFrames, outputFrames, rs, reverse = false)
        return AudioBuffer(input.sampleRate, out)
    }

    /**
     * Starts [input] at [startFrame] from standstill, accelerating to normal speed over [spinUpFrames] frames,
     * then plays on normally until the output reaches exactly [outputFrames] frames.
     */
    fun spinUp(input: AudioBuffer, spinUpFrames: Int, outputFrames: Int, startFrame: Int = 0): AudioBuffer {
        require(startFrame >= 0 && spinUpFrames >= 0 && outputFrames >= 0) { "frame counts must be >= 0" }
        val ch = input.channelCount
        val out = Array(ch) { FloatArray(outputFrames) }
        val rs = VariableRateResampler(ch)
        rs.reset(startFrame.toDouble())
        renderRamp(input, out, 0, spinUpFrames, outputFrames, rs, reverse = true)
        val rest = outputFrames - spinUpFrames
        if (rest > 0) {
            rs.setRatio(1.0)
            rs.process(input.channels, out, rest, spinUpFrames)
        }
        return AudioBuffer(input.sampleRate, out)
    }

    /** Renders [rampFrames] frames of ramp at `out[outOffset..]`; `reverse` plays the speed curve from 0 to 1. */
    private fun renderRamp(
        input: AudioBuffer, out: Array<FloatArray>, outOffset: Int, rampFrames: Int, outputFrames: Int,
        rs: VariableRateResampler, reverse: Boolean,
    ) {
        if (rampFrames <= 0) return
        val ch = input.channelCount
        var done = 0
        val inv = 1.0 / rampFrames
        while (done < rampFrames && outOffset + done < outputFrames) {
            val n = minOf(blockFrames, rampFrames - done, outputFrames - outOffset - done)
            val t0 = done * inv
            val t1 = (done + n) * inv
            val s0 = if (reverse) speedAt(1.0 - t0) else speedAt(t0)
            val s1 = if (reverse) speedAt(1.0 - t1) else speedAt(t1)
            rs.setRatioRamp(s0, s1)
            rs.process(input.channels, out, n, outOffset + done)
            if (velocityGain) {
                for (i in 0 until n) {
                    // Gain at the *end* of the frame's advance so the very last brake frame is exactly silent.
                    val t = (done + i + 1) * inv
                    val g = (if (reverse) speedAt(1.0 - t + inv) else speedAt(t)).toFloat()
                    val idx = outOffset + done + i
                    for (c in 0 until ch) out[c][idx] *= g
                }
            }
            done += n
        }
    }
}
