package dev.muisc.dsp.resample

import dev.muisc.audio.AudioBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kaiser-windowed sinc interpolation kernel sampled on a fine grid ("polyphase table with linear
 * interpolation between phases", the approach used by libsamplerate's sinc converters).
 *
 * The prototype is the ideal low-pass `h(t) = rolloff * sinc(rolloff * t)` (t in samples of the *lower* of
 * the two rates, cutoff at `rolloff * Nyquist`) multiplied by a Kaiser window `I0(beta sqrt(1 - (t/T)^2)) /
 * I0(beta)` of half-length `T = taps / 2`. Only `t >= 0` is stored (symmetric kernel) with [phases] entries per
 * sample; [at] evaluates it at any real `t` by linear interpolation between the two neighbouring grid points.
 *
 * With the defaults (32 taps, beta = 9, 128 phases, rolloff 0.94) the stop-band attenuation is about 90 dB
 * (measured: a 15 kHz tone down-sampled 44.1 -> 22.05 kHz leaves < -95 dB), the pass-band is flat within
 * 0.001 dB up to 0.36 fs and rolls off to -3 dB at 0.45 fs of the lower rate (20 kHz at 44.1 kHz), and the
 * phase-interpolation error is below -85 dB. Use more taps for a sharper edge near Nyquist.
 */
class SincKernel(val taps: Int = 32, val phases: Int = 128, val kaiserBeta: Double = 9.0, val rolloff: Double = 0.94) {
    init {
        require(taps >= 4 && taps % 2 == 0) { "taps must be an even number >= 4" }
        require(phases >= 8) { "phases must be >= 8" }
        require(rolloff > 0 && rolloff <= 1) { "rolloff must be in (0, 1]" }
    }

    /** Half-length of the kernel support in samples. */
    val halfTaps: Double = taps / 2.0
    private val n = (taps / 2) * phases
    private val table = FloatArray(n + 2)

    init {
        val i0b = besselI0(kaiserBeta)
        for (i in 0..n) {
            val t = i.toDouble() / phases
            val x = t / halfTaps
            val w = if (x >= 1.0) 0.0 else besselI0(kaiserBeta * sqrt(1.0 - x * x)) / i0b
            val s = if (i == 0) 1.0 else sin(PI * rolloff * t) / (PI * rolloff * t)
            table[i] = (rolloff * s * w).toFloat()
        }
        table[n + 1] = 0f
    }

    /** Kernel value at real offset [t] (samples); zero outside (-halfTaps, halfTaps). */
    fun at(t: Double): Float {
        val a = abs(t) * phases
        val i = a.toInt()
        if (i >= n) return 0f
        val f = (a - i).toFloat()
        val lo = table[i]
        return lo + f * (table[i + 1] - lo)
    }

    /**
     * Interpolates [x] at fractional position [p] (in input samples) using the kernel scaled by [scale]
     * (1 for upsampling, `outRate / inRate` for downsampling, which stretches the kernel and lowers its cutoff to
     * the output Nyquist). Samples outside `[from, to)` are treated as zero.
     */
    fun interpolate(x: FloatArray, from: Int, to: Int, p: Double, scale: Double): Float {
        val span = halfTaps / scale
        var k0 = ceil(p - span).toInt()
        var k1 = floor(p + span).toInt()
        if (k0 < from) k0 = from
        if (k1 >= to) k1 = to - 1
        var acc = 0.0
        val step = scale * phases
        // t = (p - k) * scale ; walk the table index directly for speed.
        var a = (p - k0) * step
        for (k in k0..k1) {
            val aa = if (a < 0) -a else a
            val i = aa.toInt()
            if (i < n) {
                val f = (aa - i).toFloat()
                val lo = table[i]
                acc += x[k] * (lo + f * (table[i + 1] - lo))
            }
            a -= step
        }
        return (acc * scale).toFloat()
    }

    companion object {
        /** Modified Bessel function of the first kind, order 0 (power series; converges for all finite x). */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4.0
            var k = 1
            while (true) {
                term *= q / (k.toDouble() * k)
                sum += term
                if (term < sum * 1e-17) break
                k++
                if (k > 500) break
            }
            return sum
        }

        val DEFAULT: SincKernel by lazy { SincKernel() }
    }
}

/**
 * Band-limited sample-rate conversion with a Kaiser-windowed sinc polyphase kernel ([SincKernel]).
 *
 * The output sample `n` is evaluated at input position `p = n * inRate / outRate` by a symmetric (zero-phase)
 * windowed-sinc interpolation centred on `p`, so **a feature at time t seconds in the input is at t seconds in
 * the output** (no group delay to compensate; the one-shot [resample] simply zero-pads the input on both sides,
 * the [StreamingResampler] instead reports its look-ahead as latency). Output length is
 * `round(frames * outRate / inRate)`.
 *
 * Down-sampling stretches the kernel by `inRate / outRate` so the cutoff sits at the output Nyquist
 * (times [SincKernel.rolloff]) — content above it is attenuated by ~90 dB rather than aliased.
 */
class Resampler(val kernel: SincKernel = SincKernel.DEFAULT) {

    /** Number of output frames for [inputFrames] converted from [inRate] to [outRate]. */
    fun outputLength(inputFrames: Int, inRate: Int, outRate: Int): Int =
        Math.round(inputFrames.toLong() * outRate / inRate.toDouble()).toInt()

    /** Converts a single channel. Output sample n corresponds to input time `n / outRate` seconds. */
    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        require(inRate > 0 && outRate > 0)
        if (inRate == outRate) return input.copyOf()
        val ratio = outRate.toDouble() / inRate
        val scale = min(1.0, ratio)
        val outLen = outputLength(input.size, inRate, outRate)
        val out = FloatArray(outLen)
        val step = inRate.toDouble() / outRate
        for (nIdx in 0 until outLen) {
            out[nIdx] = kernel.interpolate(input, 0, input.size, nIdx * step, scale)
        }
        return out
    }

    /** Converts every channel of [buffer] to [targetRate]; returns the input itself if the rate already matches. */
    fun resample(buffer: AudioBuffer, targetRate: Int): AudioBuffer {
        if (buffer.sampleRate == targetRate) return buffer
        return AudioBuffer(targetRate, Array(buffer.channelCount) { resample(buffer[it], buffer.sampleRate, targetRate) })
    }

    companion object {
        /** One-shot conversion with the default kernel. */
        fun resample(buffer: AudioBuffer, targetRate: Int): AudioBuffer = Resampler().resample(buffer, targetRate)
    }
}

/**
 * Streaming fixed-ratio resampler (same kernel and alignment as [Resampler]) for real-time use.
 *
 * Input is fed block by block with [process]. The output sample grid is identical to the one-shot [Resampler]
 * (output sample `n` is input time `n / outRate`; there is no time shift), but output frame n can only be
 * computed once the kernel's look-ahead of [latencyInputFrames] = `ceil(halfTaps / scale)` input frames beyond
 * its position has arrived, so the stream is *emitted* [latencyOutputFramesExact] output frames behind the
 * input (a real-time system must account for that buffering delay). Call [flush] at the end of the stream to
 * push out the tail; the total output length then equals the one-shot length within one frame.
 *
 * Allocation-free after construction: blocks larger than [maxBlockFrames] are processed in chunks. The output
 * arrays must have room for `ceil(frames * outRate / inRate) + 2` frames per call ([maxOutputFrames]).
 */
class StreamingResampler(
    val inRate: Int,
    val outRate: Int,
    val channels: Int,
    val kernel: SincKernel = SincKernel.DEFAULT,
    private val maxBlockFrames: Int = 4096,
) {
    init { require(inRate > 0 && outRate > 0 && channels > 0 && maxBlockFrames > 0) }

    val ratio: Double = outRate.toDouble() / inRate
    private val scale: Double = min(1.0, ratio)
    private val step: Double = inRate.toDouble() / outRate
    private val span: Int = ceil(kernel.halfTaps / scale).toInt()

    /** Look-ahead in input frames (kernel half-length at the working rate). */
    val latencyInputFrames: Int = span
    /** Exact delay of the output stream in output frames (`latencyInputFrames * ratio`, possibly fractional). */
    val latencyOutputFramesExact: Double = span * ratio
    /** Delay of the output stream in output frames, rounded to the nearest frame. */
    val latencyOutputFrames: Int = Math.round(latencyOutputFramesExact).toInt()

    // Work buffer: [history (span) | span look-ahead already-received | new block]
    private val capacity = 2 * span + maxBlockFrames + 4
    private val work = Array(channels) { FloatArray(capacity) }
    private var filled = 0          // valid samples in work
    private var pos = 0.0           // next output position, in work-buffer coordinates
    private var flushed = false

    /** Upper bound on output frames produced by one [process] call of [frames] input frames. */
    fun maxOutputFrames(frames: Int): Int = ceil(frames * ratio).toInt() + 2

    fun reset() {
        for (w in work) w.fill(0f)
        // Pre-fill `span` zeros of history; input frame j sits at work index span + j, and the first output
        // position is input frame 0 (work index span), so output n is exactly at input time n * inRate / outRate.
        filled = span; pos = span.toDouble(); flushed = false
    }

    init { reset() }

    /**
     * Feeds [frames] input frames (per channel from [input] at [inputOffset]) and writes the output frames that
     * become computable into [output] at [outputOffset]. Returns the number of output frames written.
     */
    fun process(input: Array<FloatArray>, frames: Int, output: Array<FloatArray>, inputOffset: Int = 0, outputOffset: Int = 0): Int {
        var written = 0
        var done = 0
        while (done < frames) {
            val n = min(maxBlockFrames, frames - done)
            written += processChunk(input, inputOffset + done, n, output, outputOffset + written)
            done += n
        }
        return written
    }

    private fun processChunk(input: Array<FloatArray>, inOff: Int, n: Int, output: Array<FloatArray>, outOff: Int): Int {
        for (ch in 0 until channels) System.arraycopy(input[ch], inOff, work[ch], filled, n)
        filled += n
        // Output positions p are valid while p + span <= filled - 1  (all kernel taps available).
        val limit = filled - span
        var written = 0
        while (pos <= limit - 1 + 1e-9) {
            for (ch in 0 until channels) output[ch][outOff + written] = kernel.interpolate(work[ch], 0, filled, pos, scale)
            pos += step
            written++
        }
        // Keep only what future outputs still need: everything from floor(pos) - span onwards.
        val keepFrom = (floor(pos).toInt() - span).coerceAtLeast(0)
        if (keepFrom > 0) {
            val keep = filled - keepFrom
            for (ch in 0 until channels) System.arraycopy(work[ch], keepFrom, work[ch], 0, keep)
            filled = keep; pos -= keepFrom
        }
        return written
    }

    /**
     * Pushes [latencyInputFrames] zeros through so that all pending output (including the delayed tail) is
     * emitted. Returns frames written; the resampler must be [reset] before reuse.
     */
    fun flush(output: Array<FloatArray>, outputOffset: Int = 0): Int {
        if (flushed) return 0
        flushed = true
        val zeros = Array(channels) { FloatArray(span) }
        return process(zeros, span, output, 0, outputOffset)
    }
}
