package dev.muisc.dsp.loudness

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.resample.SincKernel
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min

/**
 * Look-ahead brick-wall true-peak limiter (the classical "delay + sliding-minimum gain computer + box smoothing"
 * design, as described e.g. in Zölzer's *DAFX* and used by most transparent mastering limiters).
 *
 * Signal path (per frame `t`, all channels share one gain — linked stereo):
 *  1. **Sidechain detection.** [TruePeak.processEnvelope] gives the channel-linked 4x-oversampled peak estimate
 *     `e[t]` (sample magnitude and the interpolated magnitudes on both sides of the sample), 16 frames late.
 *  2. **Gain computer.** Required gain `g_req = min(1, c / e)` where `c` is the linear ceiling lowered by
 *     [safetyMarginDb] (the gain modulation itself adds a little new spectral content that the oversampled
 *     detector cannot see in advance; 0.05 dB covers it comfortably in practice).
 *  3. **Release.** One-pole "peak-hold" follower on the gain: instant downwards, exponential recovery towards 1
 *     with time constant [releaseMs] (`g_rel[t] = min(g_req[t], g_rel[t-1] + (1 - g_rel[t-1]) (1 - e^{-1/(tau fs)}))`).
 *  4. **Look-ahead minimum.** `g_min[t] = min(g_rel[t .. t+L])`, L = [lookaheadFrames] (sliding-window minimum
 *     with a monotonic deque, O(1) per frame), so the gain is already down when the peak arrives.
 *  5. **Smoothing.** A box (moving-average) filter of length L over `g_min` turns the step into a linear ramp of
 *     exactly L frames that reaches the required gain precisely at the peak: instantaneous attack without any
 *     discontinuity in the gain (no clicks), and pumping-free release because the box also rounds off the release.
 *     Because every `g_min` in the box window contains `g_req[t]`, the smoothed gain is *guaranteed* `<= g_req[t]`,
 *     i.e. the output never exceeds the (margin-adjusted) ceiling on the oversampled grid.
 *  6. **Apply.** The input, delayed by [latencyFrames] = 16 + L frames, is multiplied by the gain.
 *
 * When no limiting is needed the gain is *exactly* 1.0 (tracked with a counter of non-unity values in the box,
 * so no floating-point residue creeps in) and the output is the bit-identical, delayed input.
 *
 * Streaming: [process] / [processInPlace] accept any block size (chunked internally); output is delayed by
 * [latencyFrames]; [flush] pushes the tail out. The one-shot [processInPlace] on an [AudioBuffer] compensates
 * the delay so the result is time-aligned with the input. Allocation-free after construction.
 */
class TruePeakLimiter(
    val sampleRate: Int,
    val channels: Int,
    /** Output ceiling in dBTP. */
    val ceilingDbtp: Double = -1.0,
    /** Look-ahead (= attack ramp length) in milliseconds. */
    val lookaheadMs: Double = 5.0,
    /** Release time constant in milliseconds. */
    val releaseMs: Double = 80.0,
    /** Extra headroom below the ceiling applied by the gain computer, in dB. */
    val safetyMarginDb: Double = 0.05,
    kernel: SincKernel = SincKernel.DEFAULT,
    private val maxBlockFrames: Int = 4096,
) {
    init {
        require(sampleRate > 0 && channels > 0 && maxBlockFrames > 0)
        require(lookaheadMs > 0 && releaseMs > 0) { "lookahead and release must be positive" }
        require(safetyMarginDb >= 0) { "safetyMarginDb must be >= 0" }
    }

    /** Look-ahead window / attack ramp length in frames (>= 1). */
    val lookaheadFrames: Int = Math.round(lookaheadMs / 1000.0 * sampleRate).toInt().coerceAtLeast(1)

    private val detector = TruePeak(channels, kernel, maxBlockFrames)

    /** Delay of the output relative to the input: detector look-ahead plus the limiter look-ahead. */
    val latencyFrames: Int = detector.latencyFrames + lookaheadFrames

    /** Linear ceiling actually enforced by the gain computer (`ceilingDbtp - safetyMarginDb`). */
    val effectiveCeilingLinear: Double = TruePeak.dbtpToLinear(ceilingDbtp - safetyMarginDb)

    private val releaseCoef: Double = 1.0 - exp(-1.0 / (releaseMs / 1000.0 * sampleRate))

    private val env = FloatArray(maxBlockFrames)

    // Delay line per channel.
    private val delay = Array(channels) { FloatArray(latencyFrames) }
    private var delayPos = 0

    // Sliding minimum over W = lookaheadFrames + 1 values (monotonic deque of (index, value)).
    private val window = lookaheadFrames + 1
    private val dqIndex = LongArray(window)
    private val dqValue = DoubleArray(window)
    private var dqHead = 0
    private var dqSize = 0
    private var counter = 0L

    // Box smoothing over lookaheadFrames values.
    private val box = DoubleArray(lookaheadFrames)
    private var boxPos = 0
    private var boxSum = 0.0
    private var nonUnity = 0

    private var gRel = 1.0

    /** Smallest gain applied so far (1.0 = no limiting yet). */
    var minGain: Double = 1.0
        private set

    /** Largest gain reduction applied so far in dB (>= 0). */
    val maxGainReductionDb: Double get() = -20.0 * log10(minGain)

    /** Clears delay lines, gain state and statistics. */
    fun reset() {
        detector.reset()
        for (d in delay) d.fill(0f)
        delayPos = 0
        dqHead = 0; dqSize = 0; counter = 0L
        box.fill(1.0); boxPos = 0; boxSum = lookaheadFrames.toDouble(); nonUnity = 0
        gRel = 1.0
        minGain = 1.0
    }

    init { reset() }

    /**
     * Processes [frames] frames from [input] (at [inputOffset]) into [output] (at [outputOffset]); the arrays may
     * be the same (in-place). Output frame `i` is the limited input frame `i - latencyFrames`.
     */
    fun process(input: Array<FloatArray>, output: Array<FloatArray>, frames: Int, inputOffset: Int = 0, outputOffset: Int = 0) {
        var done = 0
        while (done < frames) {
            val n = min(maxBlockFrames, frames - done)
            block(input, inputOffset + done, output, outputOffset + done, n)
            done += n
        }
    }

    /** In-place streaming variant of [process]. */
    fun processInPlace(buffer: Array<FloatArray>, frames: Int = buffer[0].size) = process(buffer, buffer, frames)

    /**
     * Pushes [latencyFrames] zeros through and writes the resulting tail to [output] at [outputOffset]
     * (which must have room for [latencyFrames] frames). Returns the number of frames written.
     */
    fun flush(output: Array<FloatArray>, outputOffset: Int = 0): Int {
        val zeros = zeroBlock
        var done = 0
        while (done < latencyFrames) {
            val n = min(zeros[0].size, latencyFrames - done)
            block(zeros, 0, output, outputOffset + done, n)
            done += n
        }
        return latencyFrames
    }
    private val zeroBlock = Array(channels) { FloatArray(min(latencyFrames, maxBlockFrames)) }

    private fun block(input: Array<FloatArray>, inOff: Int, output: Array<FloatArray>, outOff: Int, n: Int) {
        // 1. Sidechain envelope for this block (16 frames late).
        detector.processEnvelope(input, n, env, inOff, 0)

        val ceiling = effectiveCeilingLinear
        val relCoef = releaseCoef
        val w = window
        val l = lookaheadFrames
        val invL = 1.0 / l
        var g = gRel
        var minG = minGain
        var dPos = delayPos
        val dLen = latencyFrames

        for (i in 0 until n) {
            // 2. Gain computer.
            val e = env[i].toDouble()
            val req = if (e <= ceiling) 1.0 else ceiling / e
            // 3. Release follower.
            g = if (req < g) req else g + (1.0 - g) * relCoef
            if (g > 0.999999999) g = 1.0
            // 4. Sliding minimum over the look-ahead window.
            val idx = counter++
            while (dqSize > 0) {
                val tail = (dqHead + dqSize - 1) % w
                if (dqValue[tail] >= g) dqSize-- else break
            }
            val tail = (dqHead + dqSize) % w
            dqIndex[tail] = idx; dqValue[tail] = g; dqSize++
            if (dqIndex[dqHead] <= idx - w) { dqHead = (dqHead + 1) % w; dqSize-- }
            val gMin = dqValue[dqHead]
            // 5. Box smoothing.
            val old = box[boxPos]
            box[boxPos] = gMin
            boxPos++; if (boxPos == l) boxPos = 0
            if (old != 1.0) nonUnity--
            if (gMin != 1.0) nonUnity++
            val gain: Float
            if (nonUnity == 0) {
                boxSum = l.toDouble()
                gain = 1f
            } else {
                boxSum += gMin - old
                val s = boxSum * invL
                if (s < minG) minG = s
                gain = s.toFloat()
            }
            // 6. Delay and apply.
            for (ch in 0 until channels) {
                val d = delay[ch]
                val x = input[ch][inOff + i]
                val delayed = d[dPos]
                d[dPos] = x
                output[ch][outOff + i] = if (gain == 1f) delayed else delayed * gain
            }
            dPos++; if (dPos == dLen) dPos = 0
        }
        gRel = g
        minGain = minG
        delayPos = dPos
    }

    companion object {
        /**
         * One-shot: limits [buffer] in place with the output time-aligned to the input (the [latencyFrames]
         * delay is compensated, so an already compliant signal comes back bit-identical). Returns the maximum
         * gain reduction applied in dB (0 when nothing was limited).
         */
        fun processInPlace(
            buffer: AudioBuffer,
            ceilingDbtp: Double = -1.0,
            lookaheadMs: Double = 5.0,
            releaseMs: Double = 80.0,
            safetyMarginDb: Double = 0.05,
        ): Double {
            val limiter = TruePeakLimiter(buffer.sampleRate, buffer.channelCount, ceilingDbtp, lookaheadMs, releaseMs, safetyMarginDb)
            val d = limiter.latencyFrames
            val frames = buffer.frames
            limiter.processInPlace(buffer.channels, frames)
            val tail = Array(buffer.channelCount) { FloatArray(d) }
            limiter.flush(tail)
            for (ch in 0 until buffer.channelCount) {
                val x = buffer.channels[ch]
                val keep = frames - d
                if (keep > 0) {
                    System.arraycopy(x, d, x, 0, keep)
                    System.arraycopy(tail[ch], 0, x, keep, d)
                } else {
                    System.arraycopy(tail[ch], -keep, x, 0, frames)
                }
            }
            return limiter.maxGainReductionDb
        }
    }
}
