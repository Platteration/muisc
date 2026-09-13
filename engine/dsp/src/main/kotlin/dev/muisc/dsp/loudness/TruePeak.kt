package dev.muisc.dsp.loudness

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.resample.SincKernel
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * True-peak (dBTP) meter per ITU-R BS.1770-4 Annex 2: the signal is 4x oversampled and the largest absolute
 * value of the oversampled signal is the true peak, which catches inter-sample overs that the plain sample
 * peak misses (up to ~3 dB for a sine near fs/4 with an unlucky phase).
 *
 * **Implementation choice.** Annex 2 gives a 48-tap, 4-phase polyphase FIR designed for 48 kHz. This meter
 * instead derives its 4-phase polyphase interpolator from the stage-1 [SincKernel] (Kaiser-windowed sinc,
 * 32 taps, beta = 9, rolloff 0.94, the kernel behind `Resampler`): taps for phase `p/4` are the kernel sampled at
 * `p/4 + 15 - j`, j = 0..31, i.e. exactly the values `Resampler` would use for a 4x up-sampling. Reasons:
 *  - it is sample-rate independent (the Annex filter is only correct at 48 kHz, and at 44.1 kHz the standard
 *    itself says any equivalent 4x interpolator may be used);
 *  - it is more accurate: its pass-band is flat within 0.001 dB up to 0.36 fs and its stop-band is ~90 dB down,
 *    whereas the Annex 2 filter under-reads high-frequency peaks by up to ~0.5 dB;
 *  - it reuses tested code. Phase 0 (the sample itself) is taken exactly rather than filtered.
 * The oversampled signal is never materialised: the three inter-sample phases are evaluated directly as 32-tap
 * dot products, so a whole song costs ~100 multiply-adds per sample per channel and no allocation.
 *
 * **Steps.** Like any BS.1770 meter this reports the peak of the *reconstructed* signal, so a hard edit or a
 * signal that starts abruptly at full level (a step for the interpolation filter) legitimately reads up to
 * ~1.1 dB above its sample peak: the Kaiser-sinc step response overshoots by 13 % at the half-sample phase.
 *
 * **Latency.** The interpolated values around sample `n` need samples up to `n + 16`, so the inter-sample part
 * of the measurement runs [latencyFrames] = 16 frames behind the input. [process] updates the running peaks
 * with everything it can; call [flush] at the end of a stream to account for the last 16 frames (the one-shot
 * [measureDbtp] does this). The sample peak of every frame fed is always included immediately.
 *
 * **Envelope.** [processEnvelope] emits, for each frame, the channel-linked peak estimate
 * `e[n] = max(|x[n]|, q[n-1], q[n])` where `q[n]` is the largest interpolated magnitude strictly between samples
 * n and n+1 (assigned to both neighbours so a gain computed on the sample grid covers it). This is the sidechain
 * of [TruePeakLimiter].
 *
 * Deterministic and allocation-free after construction (blocks larger than [maxBlockFrames] are chunked).
 */
class TruePeak(
    val channels: Int,
    val kernel: SincKernel = SincKernel.DEFAULT,
    private val maxBlockFrames: Int = 4096,
) {
    init { require(channels > 0 && maxBlockFrames > 0) { "channels and maxBlockFrames must be positive" } }

    /** Oversampling factor of the measurement. */
    val oversampling: Int = 4

    private val taps: Int = kernel.taps
    private val half: Int = taps / 2

    /** Frames of look-ahead the inter-sample measurement needs (half the kernel length). */
    val latencyFrames: Int = half

    // Polyphase taps for the three inter-sample phases 1/4, 1/2, 3/4: value at n + phase = sum_j x[n - half + 1 + j] * t[j].
    private val t1 = FloatArray(taps) { kernel.at(0.25 + (half - 1) - it).toFloat() }
    private val t2 = FloatArray(taps) { kernel.at(0.50 + (half - 1) - it).toFloat() }
    private val t3 = FloatArray(taps) { kernel.at(0.75 + (half - 1) - it).toFloat() }

    // work[ch] = [taps frames of history | up to maxBlockFrames new frames]
    private val work = Array(channels) { FloatArray(taps + maxBlockFrames) }
    private val q = FloatArray(maxBlockFrames)
    private val smp = FloatArray(maxBlockFrames)
    private val channelPeaks = FloatArray(channels)
    private var prevQ = 0f

    /** Total frames fed since the last [reset]. */
    var framesProcessed: Long = 0L
        private set

    /** Clears history and peaks. */
    fun reset() {
        for (w in work) w.fill(0f)
        channelPeaks.fill(0f)
        prevQ = 0f
        framesProcessed = 0L
    }

    /** Largest true-peak magnitude seen so far on [channel] (linear). */
    fun channelPeakLinear(channel: Int): Float = channelPeaks[channel]

    /** Largest true-peak magnitude seen so far on [channel] in dBTP (-inf for silence). */
    fun channelPeakDbtp(channel: Int): Double = linearToDbtp(channelPeaks[channel].toDouble())

    /** Largest true-peak magnitude seen so far over all channels (linear). */
    val peakLinear: Float get() { var p = 0f; for (v in channelPeaks) if (v > p) p = v; return p }

    /** Largest true-peak over all channels in dBTP (-inf for silence). */
    val peakDbtp: Double get() = linearToDbtp(peakLinear.toDouble())

    /** Feeds [frames] frames (per channel from [input] at [offset]) and updates the running peaks. */
    fun process(input: Array<FloatArray>, frames: Int, offset: Int = 0) {
        var done = 0
        while (done < frames) {
            val n = min(maxBlockFrames, frames - done)
            block(input, offset + done, n, null, 0)
            done += n
        }
        framesProcessed += frames
    }

    /**
     * Feeds [frames] frames and writes the channel-linked peak envelope (see class doc) to [out] starting at
     * [outputOffset]: `out[outputOffset + i]` belongs to input frame `i - latencyFrames` of this call (frames
     * before the stream start read as zero-padded).
     */
    fun processEnvelope(input: Array<FloatArray>, frames: Int, out: FloatArray, inputOffset: Int = 0, outputOffset: Int = 0) {
        var done = 0
        while (done < frames) {
            val n = min(maxBlockFrames, frames - done)
            block(input, inputOffset + done, n, out, outputOffset + done)
            done += n
        }
        framesProcessed += frames
    }

    /**
     * Pushes [latencyFrames] zeros through so that the inter-sample peaks of the last frames are measured (and,
     * when [out] is given, the trailing envelope values are emitted). Returns [latencyFrames]. The meter can keep
     * being fed afterwards, but the zeros then count as signal; [reset] between unrelated signals.
     */
    fun flush(out: FloatArray? = null, outputOffset: Int = 0): Int {
        val zeros = zeroBlock
        if (out == null) process(zeros, half) else processEnvelope(zeros, half, out, 0, outputOffset)
        return half
    }
    private val zeroBlock = Array(channels) { FloatArray(half) }

    private fun block(input: Array<FloatArray>, inOff: Int, n: Int, out: FloatArray?, outOff: Int) {
        val t1 = t1; val t2 = t2; val t3 = t3
        val taps = taps
        val half = half
        val q = q
        val smp = smp
        for (ch in 0 until channels) {
            val w = work[ch]
            System.arraycopy(input[ch], inOff, w, taps, n)
            var peak = channelPeaks[ch]
            // Sample peak of the new frames (available immediately).
            for (i in taps until taps + n) { val a = w[i]; val v = if (a < 0f) -a else a; if (v > peak) peak = v }
            // Inter-sample peaks between positions p and p + 1 for p = half .. half + n - 1 (work coordinates);
            // p + half is the last frame available, so the estimate lags the input by `half` frames.
            for (i in 0 until n) {
                val base = i + 1 // = p - (half - 1)
                var s1 = 0f; var s2 = 0f; var s3 = 0f
                for (j in 0 until taps) {
                    val x = w[base + j]
                    s1 += x * t1[j]; s2 += x * t2[j]; s3 += x * t3[j]
                }
                if (s1 < 0f) s1 = -s1; if (s2 < 0f) s2 = -s2; if (s3 < 0f) s3 = -s3
                var m = s1; if (s2 > m) m = s2; if (s3 > m) m = s3
                if (m > peak) peak = m
                if (out != null) {
                    val a = w[half + i]
                    val v = if (a < 0f) -a else a
                    if (ch == 0) { q[i] = m; smp[i] = v } else { if (m > q[i]) q[i] = m; if (v > smp[i]) smp[i] = v }
                }
            }
            channelPeaks[ch] = peak
            // Keep the last `taps` frames as history.
            System.arraycopy(w, n, w, 0, taps)
        }
        if (out != null) {
            var pq = prevQ
            for (i in 0 until n) {
                var e = smp[i]
                if (pq > e) e = pq
                val cur = q[i]
                if (cur > e) e = cur
                out[outOff + i] = e
                pq = cur
            }
            prevQ = pq
        }
    }

    companion object {
        /** Linear magnitude to dBTP (-inf for 0). */
        fun linearToDbtp(v: Double): Double = if (v <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(v)

        /** dBTP to linear magnitude. */
        fun dbtpToLinear(db: Double): Double = 10.0.pow(db / 20.0)

        /** One-shot true peak of a whole buffer over all channels (linear magnitude, >= sample peak). */
        fun measureLinear(buffer: AudioBuffer, kernel: SincKernel = SincKernel.DEFAULT): Float {
            val m = TruePeak(buffer.channelCount, kernel)
            m.process(buffer.channels, buffer.frames)
            m.flush()
            return m.peakLinear
        }

        /** One-shot true peak of a whole buffer in dBTP (-inf for silence). */
        fun measureDbtp(buffer: AudioBuffer, kernel: SincKernel = SincKernel.DEFAULT): Double =
            linearToDbtp(measureLinear(buffer, kernel).toDouble())
    }
}
