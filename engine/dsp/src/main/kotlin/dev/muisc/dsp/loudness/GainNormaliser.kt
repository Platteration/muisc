package dev.muisc.dsp.loudness

import dev.muisc.audio.AudioBuffer
import kotlin.math.pow

/**
 * The outcome of a loudness-normalisation computation ([GainNormaliser.plan]).
 *
 * `gainDb` is the gain that brings [inputLufs] to [targetLufs], reduced when necessary so that the true peak
 * after the gain does not exceed [ceilingDbtp]; [peakLimited] tells whether that cap was hit (the result is then
 * quieter than the target by `targetLufs - outputLufs` LU). For silent input (`inputLufs = -inf`) the gain is
 * 0 dB and [peakLimited] is false.
 */
data class NormalisationPlan(
    val inputLufs: Double,
    val targetLufs: Double,
    val inputTruePeakDbtp: Double,
    val ceilingDbtp: Double,
    val gainDb: Double,
    val peakLimited: Boolean,
) {
    /** The gain as a linear factor. */
    val gainLinear: Float get() = 10.0.pow(gainDb / 20.0).toFloat()

    /** Expected integrated loudness after applying the gain (-inf for silent input). */
    val outputLufs: Double get() = inputLufs + gainDb

    /** Expected true peak after applying the gain (-inf for silent input). */
    val outputTruePeakDbtp: Double get() = inputTruePeakDbtp + gainDb
}

/**
 * Static loudness normalisation: a single gain that moves a track's integrated loudness (BS.1770-4, via
 * [LoudnessMeter]) to a target, capped by a true-peak ceiling ([TruePeak]) — the same rule streaming services
 * and EBU R128 delivery use (`gain = min(target - loudness, ceiling - truePeak)`). Since the gain is constant,
 * loudness and true peak both shift by exactly `gainDb` and no limiting is involved; use [TruePeakLimiter]
 * afterwards if a louder target must be reached regardless of the peaks.
 *
 * Defaults: -14 LUFS target (the common streaming reference) and a -1 dBTP ceiling.
 */
object GainNormaliser {
    const val DEFAULT_TARGET_LUFS: Double = -14.0
    const val DEFAULT_CEILING_DBTP: Double = -1.0

    /**
     * Computes the plan from already measured values: [inputLufs] (integrated) and [inputTruePeakDbtp].
     * The gain is `targetLufs - inputLufs`, reduced to `ceilingDbtp - inputTruePeakDbtp` when that is smaller.
     */
    fun plan(
        inputLufs: Double,
        inputTruePeakDbtp: Double,
        targetLufs: Double = DEFAULT_TARGET_LUFS,
        ceilingDbtp: Double = DEFAULT_CEILING_DBTP,
    ): NormalisationPlan {
        if (inputLufs == Double.NEGATIVE_INFINITY || inputLufs.isNaN()) {
            return NormalisationPlan(inputLufs, targetLufs, inputTruePeakDbtp, ceilingDbtp, 0.0, false)
        }
        val wanted = targetLufs - inputLufs
        val maxByPeak = if (inputTruePeakDbtp == Double.NEGATIVE_INFINITY) Double.POSITIVE_INFINITY else ceilingDbtp - inputTruePeakDbtp
        return if (maxByPeak < wanted) {
            NormalisationPlan(inputLufs, targetLufs, inputTruePeakDbtp, ceilingDbtp, maxByPeak, true)
        } else {
            NormalisationPlan(inputLufs, targetLufs, inputTruePeakDbtp, ceilingDbtp, wanted, false)
        }
    }

    /** Measures [buffer] (integrated loudness and true peak) and computes the plan; the buffer is not modified. */
    fun plan(buffer: AudioBuffer, targetLufs: Double = DEFAULT_TARGET_LUFS, ceilingDbtp: Double = DEFAULT_CEILING_DBTP): NormalisationPlan =
        plan(LoudnessMeter.integratedLufs(buffer), TruePeak.measureDbtp(buffer), targetLufs, ceilingDbtp)

    /** Applies [plan] to [buffer] (multiplies every sample by [NormalisationPlan.gainLinear]); a 0 dB plan leaves it untouched. */
    fun applyInPlace(buffer: AudioBuffer, plan: NormalisationPlan): AudioBuffer {
        if (plan.gainDb != 0.0) buffer.applyGainInPlace(plan.gainLinear)
        return buffer
    }

    /** Measures, plans and applies in one step; returns the plan that was applied. */
    fun normaliseInPlace(buffer: AudioBuffer, targetLufs: Double = DEFAULT_TARGET_LUFS, ceilingDbtp: Double = DEFAULT_CEILING_DBTP): NormalisationPlan {
        val p = plan(buffer, targetLufs, ceilingDbtp)
        applyInPlace(buffer, p)
        return p
    }
}
