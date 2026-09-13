package dev.muisc.dsp.fx

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.OnePole
import dev.muisc.dsp.filter.OnePoleMode
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Feedback delay ("echo") with a fractional delay line, filtered feedback loop, dry/wet mix and stereo
 * ping-pong.
 *
 * Structure per channel (Schroeder-style recirculating delay):
 *
 *     y[n]  = line[n - D]                         (3rd-order Lagrange fractional read)
 *     f[n]  = HP(LP(y[n]))                        (one-pole low-pass + one-pole high-pass in the loop)
 *     line[n] = x[n] + feedback * f[n]            (ping-pong: L gets mono x + fb * f_R, R gets fb * f_L)
 *     out[n] = (1 - mix) * x[n] + mix * y[n]
 *
 * The fractional read uses the 3rd-order Lagrange interpolator on the four samples around `n - D`
 * (`c_{-1} = -f(f-1)(f-2)/6, c_0 = (f+1)(f-1)(f-2)/2, c_1 = -(f+1)f(f-2)/2, c_2 = (f+1)f(f-1)/6`), so the
 * minimum delay is [MIN_DELAY] frames. Delay-time changes ([setDelayFrames] / [setDelayBeats]) are performed
 * by crossfading (equal power, [crossfadeMs]) from a read head at the old time to one at the new time — the
 * pitch never changes; a change requested while a crossfade is running is queued until it finishes.
 *
 * Allocation-free after construction; the impulse response is a train of echoes at multiples of `D` with
 * amplitude `feedback^k` (times the loop filters' gain).
 */
class Delay(
    val sampleRate: Int,
    val channels: Int,
    val maxDelaySeconds: Double = 2.0,
    val crossfadeMs: Double = 20.0,
) {
    init {
        require(sampleRate > 0 && channels > 0) { "sampleRate and channels must be positive" }
        require(maxDelaySeconds > 0.0 && crossfadeMs >= 0.0) { "maxDelaySeconds must be > 0 and crossfadeMs >= 0" }
    }

    /** Largest delay in frames. */
    val maxDelayFrames: Int = ceil(maxDelaySeconds * sampleRate).toInt()
    private val size = nextPow2(maxDelayFrames + 8)
    private val mask = size - 1
    private val lines = Array(channels) { FloatArray(size) }
    private var w = 0

    /** Feedback gain in [0, 0.999]. */
    var feedback: Double = 0.5
        set(v) { field = v.coerceIn(0.0, 0.999) }

    /** Dry/wet mix in [0, 1]: 0 = dry only, 1 = wet only. */
    var mix: Double = 0.5
        set(v) { field = v.coerceIn(0.0, 1.0) }

    /** Stereo ping-pong (only effective with exactly 2 channels): echoes alternate between left and right. */
    var pingPong: Boolean = false

    private val lp = OnePole(channels, OnePoleMode.LOW_PASS)
    private val hp = OnePole(channels, OnePoleMode.HIGH_PASS)
    private val xfLen = Math.round(crossfadeMs * sampleRate / 1000.0).toInt().coerceAtLeast(1)
    private var delayA = (0.25 * sampleRate).coerceIn(MIN_DELAY.toDouble(), maxDelayFrames.toDouble())
    private var delayB = delayA
    private var xfPos = -1
    private var pending = -1.0
    private val yTmp = FloatArray(channels)
    private val fbTmp = FloatArray(channels)
    private val xTmp = FloatArray(channels)

    init {
        setLowPass(8000.0)
        setHighPass(80.0)
    }

    /** Delay time in effect (the target of a running crossfade). */
    val delayFrames: Double get() = if (pending >= 0.0) pending else if (xfPos >= 0) delayB else delayA

    /** Delay time in seconds. */
    val delaySeconds: Double get() = delayFrames / sampleRate

    /** Cutoff of the one-pole low-pass in the feedback loop (Hz). */
    fun setLowPass(hz: Double) = lp.setCutoff(hz.coerceIn(1.0, sampleRate * 0.499), sampleRate.toDouble())

    /** Cutoff of the one-pole high-pass in the feedback loop (Hz). */
    fun setHighPass(hz: Double) = hp.setCutoff(hz.coerceIn(1.0, sampleRate * 0.499), sampleRate.toDouble())

    /**
     * Sets the delay time in frames (clamped to `[MIN_DELAY, maxDelayFrames]`). With [immediate] the read head
     * jumps (may click); otherwise the change is crossfaded over [crossfadeMs].
     */
    fun setDelayFrames(frames: Double, immediate: Boolean = false) {
        val d = frames.coerceIn(MIN_DELAY.toDouble(), maxDelayFrames.toDouble())
        if (immediate) { delayA = d; delayB = d; xfPos = -1; pending = -1.0; return }
        if (xfPos >= 0) { pending = d; return }
        if (d == delayA) return
        delayB = d
        xfPos = 0
    }

    /** Sets the delay time as a number of beats, e.g. `setDelayBeats(0.1875, beatFrames)` for a 3/16 echo. */
    fun setDelayBeats(beats: Double, beatFrames: Double, immediate: Boolean = false) =
        setDelayFrames(beats * beatFrames, immediate)

    /** Clears the delay lines and filter states (keeps the settings; completes a pending time change). */
    fun reset() {
        for (l in lines) l.fill(0f)
        w = 0
        lp.reset(); hp.reset()
        if (pending >= 0.0) { delayA = pending; pending = -1.0 } else if (xfPos >= 0) delayA = delayB
        delayB = delayA
        xfPos = -1
    }

    /** Processes [frames] frames; [input] and [output] may be the same arrays. */
    fun process(input: Array<FloatArray>, output: Array<FloatArray>, frames: Int, inputOffset: Int = 0, outputOffset: Int = 0) {
        require(input.size >= channels && output.size >= channels) { "need $channels channels" }
        val fb = feedback.toFloat()
        val dry = (1.0 - mix).toFloat()
        val wet = mix.toFloat()
        val pp = pingPong && channels == 2
        for (n in 0 until frames) {
            for (c in 0 until channels) xTmp[c] = input[c][inputOffset + n]
            // Read heads.
            if (xfPos >= 0) {
                val theta = (xfPos.toDouble() / xfLen) * (PI / 2)
                val ga = cos(theta).toFloat()
                val gb = sin(theta).toFloat()
                for (c in 0 until channels) yTmp[c] = ga * read(lines[c], delayA) + gb * read(lines[c], delayB)
                xfPos++
                if (xfPos >= xfLen) {
                    delayA = delayB
                    xfPos = -1
                    if (pending >= 0.0) { delayB = pending; pending = -1.0; if (delayB != delayA) xfPos = 0 }
                }
            } else {
                for (c in 0 until channels) yTmp[c] = read(lines[c], delayA)
            }
            // Loop filters and feedback.
            for (c in 0 until channels) fbTmp[c] = fb * hp.processSample(lp.processSample(yTmp[c], c), c)
            if (pp) {
                val mono = 0.5f * (xTmp[0] + xTmp[1])
                lines[0][w] = mono + fbTmp[1]
                lines[1][w] = fbTmp[0]
            } else {
                for (c in 0 until channels) lines[c][w] = xTmp[c] + fbTmp[c]
            }
            w = (w + 1) and mask
            for (c in 0 until channels) output[c][outputOffset + n] = dry * xTmp[c] + wet * yTmp[c]
        }
    }

    fun processInPlace(buffer: Array<FloatArray>, frames: Int = buffer[0].size) = process(buffer, buffer, frames)

    /**
     * "Echo out": [dry] is played through the delay up to [cutFrame], then the input is cut and only the feedback
     * tail (at [tailFeedback], default the current feedback) is rendered for [tailFrames] more frames. The result
     * has exactly `cutFrame + tailFrames` frames. Resets the delay state first; restores the feedback afterwards.
     */
    fun echoOut(dry: AudioBuffer, cutFrame: Int, tailFrames: Int, tailFeedback: Double = feedback): AudioBuffer {
        require(dry.channelCount == channels) { "dry buffer must have $channels channels" }
        require(tailFrames >= 0) { "tailFrames must be >= 0" }
        val cut = cutFrame.coerceIn(0, dry.frames)
        reset()
        val out = Array(channels) { FloatArray(cut + tailFrames) }
        process(dry.channels, out, cut, 0, 0)
        val saved = feedback
        feedback = tailFeedback
        val block = 4096
        val zeros = Array(channels) { FloatArray(minOf(block, maxOf(tailFrames, 1))) }
        var done = 0
        while (done < tailFrames) {
            val n = minOf(block, tailFrames - done)
            process(zeros, out, n, 0, cut + done)
            done += n
        }
        feedback = saved
        return AudioBuffer(dry.sampleRate, out)
    }

    /** Lagrange 3rd-order read of `line` at `delay` frames before the write position. */
    private fun read(line: FloatArray, delay: Double): Float {
        val p = w - delay
        val fl = floor(p)
        val i0 = fl.toInt()
        val f = (p - fl).toFloat()
        val xm1 = line[(i0 - 1) and mask]
        val x0 = line[i0 and mask]
        val x1 = line[(i0 + 1) and mask]
        val x2 = line[(i0 + 2) and mask]
        val fm1 = f - 1f
        val fm2 = f - 2f
        val fp1 = f + 1f
        val cm1 = -f * fm1 * fm2 / 6f
        val c0 = fp1 * fm1 * fm2 / 2f
        val c1 = -fp1 * f * fm2 / 2f
        val c2 = fp1 * f * fm1 / 6f
        return cm1 * xm1 + c0 * x0 + c1 * x1 + c2 * x2
    }

    companion object {
        /** Smallest delay in frames the Lagrange read head supports. */
        const val MIN_DELAY = 4

        private fun nextPow2(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }
    }
}
