package dev.muisc.dsp.fft

import dev.muisc.dsp.window.Window

/**
 * Complex short-time Fourier transform of a mono signal: `frames` frames of `frameSize/2 + 1` bins.
 *
 * Storage is `re[frame][bin]` / `im[frame][bin]` so that a single frame is a contiguous `FloatArray`, which is
 * what per-frame processors (masking, phase vocoder) want. [magnitude] and [power] allocate new frame arrays of
 * the same shape; the `Into` variants reuse caller arrays.
 *
 * When [centered] is true (librosa convention, the default of [Stft]), frame `t` is centred on input sample
 * `t * hop`; otherwise frame `t` starts at sample `t * hop` and is centred on `t * hop + frameSize/2`.
 */
class Spectrogram(
    val frameSize: Int,
    val hop: Int,
    val sampleRate: Int,
    val frames: Int,
    val re: Array<FloatArray>,
    val im: Array<FloatArray>,
    val centered: Boolean = true,
) {
    init {
        require(frameSize > 0 && hop > 0 && sampleRate > 0) { "frameSize, hop and sampleRate must be positive" }
        require(re.size == frames && im.size == frames) { "re/im must hold exactly $frames frames" }
        require(frames == 0 || (re[0].size == bins && im[0].size == bins)) { "frames must have ${frameSize / 2 + 1} bins" }
    }

    /** Number of bins per frame, `frameSize/2 + 1`. */
    val bins: Int get() = frameSize / 2 + 1

    /** Centre frequency in Hz of bin [k]: `k * sampleRate / frameSize`. */
    fun binFrequency(k: Int): Double = k.toDouble() * sampleRate / frameSize

    /** Time in seconds of the centre of frame [t]. */
    fun frameTime(t: Int): Double =
        (t.toLong() * hop + (if (centered) 0 else frameSize / 2)).toDouble() / sampleRate

    /** Frame index whose centre is nearest to [seconds] (clamped to the valid range). */
    fun frameAt(seconds: Double): Int {
        val centreOffset = if (centered) 0 else frameSize / 2
        val f = Math.round((seconds * sampleRate - centreOffset) / hop).toInt()
        return f.coerceIn(0, maxOf(0, frames - 1))
    }

    /** Magnitude `|X|` as `[frame][bin]` (newly allocated). */
    fun magnitude(): Array<FloatArray> = magnitudeInto(Array(frames) { FloatArray(bins) })

    /** Power `|X|^2` as `[frame][bin]` (newly allocated). */
    fun power(): Array<FloatArray> = powerInto(Array(frames) { FloatArray(bins) })

    /** Writes `|X|` into [out] (shape `[frames][bins]`) and returns it. */
    fun magnitudeInto(out: Array<FloatArray>): Array<FloatArray> {
        for (t in 0 until frames) magnitude(re[t], im[t], out[t], bins)
        return out
    }

    /** Writes `|X|^2` into [out] (shape `[frames][bins]`) and returns it. */
    fun powerInto(out: Array<FloatArray>): Array<FloatArray> {
        for (t in 0 until frames) power(re[t], im[t], out[t], bins)
        return out
    }

    /** Deep copy. */
    fun copy(): Spectrogram = Spectrogram(
        frameSize, hop, sampleRate, frames,
        Array(frames) { re[it].copyOf() }, Array(frames) { im[it].copyOf() }, centered,
    )

    override fun toString(): String =
        "Spectrogram(frameSize=$frameSize, hop=$hop, sr=$sampleRate, frames=$frames, bins=$bins, centered=$centered)"
}

/** Receives one STFT frame at a time. The arrays are reused by the caller: copy them if you keep them. */
fun interface StftFrameSink {
    fun onFrame(index: Int, re: FloatArray, im: FloatArray)
}

/**
 * Streaming and one-shot short-time Fourier transform.
 *
 * Analysis: frame `t` covers samples `[t*hop, t*hop + frameSize)` of the (optionally zero-padded) input, is
 * multiplied by [window] and transformed with [RealFft] (unscaled). With [center] = true the input is
 * conceptually padded with `frameSize/2` zeros at both ends (librosa `center=True`), so frame `t` is centred
 * on input sample `t*hop` and the frame count is `1 + floor(len / hop)`; otherwise the count is
 * `1 + floor((len - frameSize) / hop)` (0 if `len < frameSize`).
 *
 * Streaming use: [process] pushes any number of samples and calls the sink once per completed frame; call
 * [flush] at the end of the stream to emit the frames that need the trailing padding (centred mode only) and
 * [reset] before reusing the instance. The streaming path allocates nothing after construction.
 *
 * One-shot use: [analyze] and [magnitudes] wrap the streaming path over a whole array.
 */
class Stft(
    val frameSize: Int,
    val hop: Int,
    val window: FloatArray = Window.hann(frameSize),
    val center: Boolean = true,
) {
    init {
        require(hop in 1..frameSize) { "hop must be in [1, frameSize], was $hop" }
        require(window.size == frameSize) { "window length ${window.size} != frameSize $frameSize" }
    }

    /** Number of bins per frame, `frameSize/2 + 1`. */
    val bins: Int = frameSize / 2 + 1
    private val rfft = RealFft(frameSize)
    private val pad = if (center) frameSize / 2 else 0
    private val fifo = FloatArray(2 * frameSize)
    private var fifoLen = 0
    private val frameBuf = FloatArray(frameSize)
    private val reBuf = FloatArray(bins)
    private val imBuf = FloatArray(bins)
    private var frameIndex = 0
    private var flushed = false

    init { reset() }

    /** Clears streaming state (and re-applies the leading zero padding in centred mode). */
    fun reset() {
        fifoLen = 0
        frameIndex = 0
        flushed = false
        if (pad > 0) { java.util.Arrays.fill(fifo, 0, pad, 0f); fifoLen = pad }
    }

    /** Number of frames emitted so far since [reset]. */
    val framesEmitted: Int get() = frameIndex

    /** Number of frames [analyze] produces for an input of [length] samples. */
    fun frameCount(length: Int): Int {
        val padded = length + 2 * pad
        return if (padded < frameSize) 0 else 1 + (padded - frameSize) / hop
    }

    /** Pushes `length` samples from `input[offset..]` and emits every frame that becomes complete. */
    fun process(input: FloatArray, offset: Int = 0, length: Int = input.size - offset, sink: StftFrameSink) {
        check(!flushed) { "flush() already called; call reset() before pushing more samples" }
        var pos = offset
        var remaining = length
        while (remaining > 0) {
            val take = minOf(remaining, fifo.size - fifoLen)
            System.arraycopy(input, pos, fifo, fifoLen, take)
            fifoLen += take
            pos += take
            remaining -= take
            drain(sink)
        }
    }

    /** Ends the stream: pushes the trailing zero padding (centred mode) and emits the remaining frames. */
    fun flush(sink: StftFrameSink) {
        if (flushed) return
        if (pad > 0) {
            var remaining = pad
            while (remaining > 0) {
                val take = minOf(remaining, fifo.size - fifoLen)
                java.util.Arrays.fill(fifo, fifoLen, fifoLen + take, 0f)
                fifoLen += take
                remaining -= take
                drain(sink)
            }
        }
        flushed = true
    }

    private fun drain(sink: StftFrameSink) {
        while (fifoLen >= frameSize) {
            for (i in 0 until frameSize) frameBuf[i] = fifo[i] * window[i]
            rfft.forward(frameBuf, reBuf, imBuf)
            sink.onFrame(frameIndex, reBuf, imBuf)
            frameIndex++
            fifoLen -= hop
            System.arraycopy(fifo, hop, fifo, 0, fifoLen)
        }
    }

    /** One-shot complex STFT of [x]. Resets the streaming state. */
    fun analyze(x: FloatArray, sampleRate: Int): Spectrogram {
        reset()
        val frames = frameCount(x.size)
        val re = Array(frames) { FloatArray(bins) }
        val im = Array(frames) { FloatArray(bins) }
        val sink = StftFrameSink { t, r, i ->
            System.arraycopy(r, 0, re[t], 0, bins)
            System.arraycopy(i, 0, im[t], 0, bins)
        }
        process(x, 0, x.size, sink)
        flush(sink)
        check(frameIndex == frames) { "frame count mismatch: emitted $frameIndex, expected $frames" }
        reset()
        return Spectrogram(frameSize, hop, sampleRate, frames, re, im, center)
    }

    /** One-shot magnitude spectrogram `[frame][bin]` of [x]. Resets the streaming state. */
    fun magnitudes(x: FloatArray): Array<FloatArray> {
        reset()
        val frames = frameCount(x.size)
        val out = Array(frames) { FloatArray(bins) }
        val sink = StftFrameSink { t, r, i -> magnitude(r, i, out[t], bins) }
        process(x, 0, x.size, sink)
        flush(sink)
        reset()
        return out
    }
}

/**
 * Inverse STFT by weighted overlap-add with window-sum normalisation (Griffin & Lim's least-squares
 * synthesis): each frame is inverse-transformed, multiplied by the synthesis [window], overlap-added, and the
 * result divided by `sum_t window^2[n - t*hop]`. With the same window used for analysis and synthesis this
 * reconstructs the input exactly (up to float rounding) for any window/hop whose squared overlap-add sum is
 * non-zero, e.g. Hann or sqrt-Hann at 50 % or 75 % overlap, and also for non-COLA hops.
 *
 * With [center] = true the first `frameSize/2` output samples (the analysis padding) are discarded so that
 * output sample `n` corresponds to input sample `n`.
 *
 * Streaming: [pushFrame] adds one frame and writes the `hop` samples that are now final (fewer while the
 * leading padding is skipped); [flush] writes the tail. Allocates nothing after construction.
 */
class Istft(
    val frameSize: Int,
    val hop: Int,
    val window: FloatArray = Window.hann(frameSize),
    val center: Boolean = true,
) {
    init {
        require(hop in 1..frameSize) { "hop must be in [1, frameSize], was $hop" }
        require(window.size == frameSize) { "window length ${window.size} != frameSize $frameSize" }
    }

    private val rfft = RealFft(frameSize)
    private val frameBuf = FloatArray(frameSize)
    private val ola = FloatArray(frameSize)
    private val wsum = FloatArray(frameSize)
    private val wsq = FloatArray(frameSize) { window[it] * window[it] }
    private var skip = 0

    init { reset() }

    /** Clears the overlap-add state. */
    fun reset() {
        java.util.Arrays.fill(ola, 0f)
        java.util.Arrays.fill(wsum, 0f)
        skip = if (center) frameSize / 2 else 0
    }

    /**
     * Adds one frame (`re`/`im` of length ≥ frameSize/2 + 1) and writes the samples that became final to
     * `out[outOffset..]`. Returns the number written (at most [hop]).
     */
    fun pushFrame(re: FloatArray, im: FloatArray, out: FloatArray, outOffset: Int): Int {
        rfft.inverse(re, im, frameBuf)
        for (i in 0 until frameSize) {
            ola[i] += frameBuf[i] * window[i]
            wsum[i] += wsq[i]
        }
        val written = emit(hop, out, outOffset)
        val keep = frameSize - hop
        System.arraycopy(ola, hop, ola, 0, keep)
        System.arraycopy(wsum, hop, wsum, 0, keep)
        java.util.Arrays.fill(ola, keep, frameSize, 0f)
        java.util.Arrays.fill(wsum, keep, frameSize, 0f)
        return written
    }

    /** Writes the remaining `frameSize - hop` tail samples (minus any padding still to skip) and resets. Returns the count. */
    fun flush(out: FloatArray, outOffset: Int): Int {
        val written = emit(frameSize - hop, out, outOffset)
        reset()
        return written
    }

    private fun emit(count: Int, out: FloatArray, outOffset: Int): Int {
        var written = 0
        for (i in 0 until count) {
            val w = wsum[i]
            val v = if (w > WSUM_EPS) ola[i] / w else ola[i]
            if (skip > 0) skip-- else { out[outOffset + written] = v; written++ }
        }
        return written
    }

    /**
     * One-shot inverse of [spec]. [length] trims or zero-pads the result to an exact sample count (pass the
     * original signal length for a sample-exact round trip). The default is `(frames - 1) * hop` in centred mode
     * (the original length rounded down to a hop multiple) and `(frames - 1) * hop + frameSize` otherwise.
     */
    fun synthesize(spec: Spectrogram, length: Int = -1): FloatArray {
        require(spec.frameSize == frameSize && spec.hop == hop) { "spectrogram geometry does not match this Istft" }
        reset()
        val frames = spec.frames
        val buf = FloatArray(frames * hop + frameSize)
        var n = 0
        for (t in 0 until frames) n += pushFrame(spec.re[t], spec.im[t], buf, n)
        n += flush(buf, n)
        val target = if (length >= 0) length else if (center) maxOf(0, (frames - 1) * hop) else maxOf(0, (frames - 1) * hop + frameSize)
        return if (target == buf.size) buf else buf.copyOf(target)
    }

    companion object {
        /** Window-sum values below this are treated as "no coverage" and left unnormalised. */
        const val WSUM_EPS = 1e-8f
    }
}
