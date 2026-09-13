package dev.muisc.player

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.AudioDecoder
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.BufferPcmStream
import dev.muisc.audio.EngineStreamFactory
import dev.muisc.audio.PcmStream
import dev.muisc.audio.jvm.JavaSoundDecoder
import dev.muisc.dsp.resample.SincKernel
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Parameters of the Kaiser-windowed sinc kernel used by [ResamplingPcmStream] (512 phases: interpolation error ≈ −90 dB). */
data class SincKernelSpec(val taps: Int = 32, val phases: Int = 512, val kaiserBeta: Double = 9.0) {
    /** The (cached) `dsp` kernel for this spec; tables are built once per spec. */
    fun kernel(): SincKernel = cache.computeIfAbsent(this) { SincKernel(taps = it.taps, phases = it.phases, kaiserBeta = it.kaiserBeta) }

    companion object {
        val DEFAULT = SincKernelSpec()
        private val cache = ConcurrentHashMap<SincKernelSpec, SincKernel>()
    }
}

/**
 * Position-deterministic sample-rate converter over any [PcmStream].
 *
 * Output frame `n` is the windowed-sinc interpolation of the input at position `p = n * inRate / targetRate`
 * (zero-phase, no time shift: a feature at t seconds stays at t seconds). Every tap is evaluated from the absolute
 * input index and the absolute output index — never from where reading started — and the input window is
 * re-primed from `floor(p) - taps` after a [seek], so two instances positioned independently produce bit-identical
 * samples for the same output frame. Input frames before 0 and past the end read as zero. When the rates match the
 * stream is a pure pass-through (no filtering).
 *
 * [totalFrames] is `round(inner.totalFrames * targetRate / inRate)`; when the inner length is unknown (-1) it is
 * learned when the inner stream ends. Allocation-free after construction (blocks larger than [maxBlockFrames] are
 * processed in chunks).
 */
class ResamplingPcmStream(
    val inner: PcmStream,
    val targetRate: Int,
    val kernel: SincKernelSpec = SincKernelSpec.DEFAULT,
    private val maxBlockFrames: Int = 4096,
) : PcmStream {
    init { require(targetRate > 0 && maxBlockFrames > 0) }

    val inRate: Int = inner.sampleRate
    override val sampleRate: Int get() = targetRate
    override val channelCount: Int get() = inner.channelCount

    /** True when no conversion happens (rates equal): reads and seeks go straight to [inner]. */
    val identity: Boolean = inRate == targetRate

    private val step: Double = inRate.toDouble() / targetRate
    private val ratio: Double = targetRate.toDouble() / inRate
    private val scale: Double = min(1.0, ratio)
    private val sinc: SincKernel = kernel.kernel()
    /** Kernel half-width in input frames, plus one for the ceil/floor rounding. */
    private val halfSpan: Double = sinc.halfTaps / scale
    private val span: Int = ceil(halfSpan).toInt() + 1

    private var innerTotal: Long = inner.totalFrames
    private var outPos: Long = 0L

    private val capacity = ceil(maxBlockFrames * step).toInt() + 2 * span + 8
    private val window = Array(channelCount) { FloatArray(capacity) }
    /** Absolute input index of `window[c][0]`. */
    private var winStart: Long = 0L
    private var winLen: Int = 0

    override val totalFrames: Long
        get() = if (identity) inner.totalFrames else if (innerTotal < 0) -1L else outputLength(innerTotal)

    override val position: Long get() = if (identity) inner.position else outPos

    /** Output frames for [inputFrames] input frames (`round(in * out / in)`, same rule as `Resampler.outputLength`). */
    fun outputLength(inputFrames: Long): Long = Math.round(inputFrames * (targetRate / inRate.toDouble()))

    override fun read(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        require(dst.size == channelCount) { "dst has ${dst.size} channels, stream has $channelCount" }
        if (identity) return inner.read(dst, offset, frames)
        if (frames <= 0) return 0
        var done = 0
        while (done < frames) {
            val n = readChunk(dst, offset + done, min(maxBlockFrames, frames - done))
            if (n == 0) break
            done += n
        }
        return done
    }

    private fun readChunk(dst: Array<FloatArray>, off: Int, want: Int): Int {
        var n = want
        if (innerTotal >= 0) {
            n = min(n.toLong(), outputLength(innerTotal) - outPos).toInt()
            if (n <= 0) return 0
        }
        val p0 = outPos * step
        val pLast = (outPos + n - 1) * step
        val needFrom = floor(p0).toLong() - span
        val needTo = ceil(pLast).toLong() + span
        ensureWindow(needFrom, needTo)
        if (innerTotal >= 0) {
            // The inner length may have been discovered just now.
            n = min(n.toLong(), outputLength(innerTotal) - outPos).toInt()
            if (n <= 0) return 0
        }
        for (i in 0 until n) {
            val p = (outPos + i) * step
            val k0 = ceil(p - halfSpan).toLong()
            val k1 = floor(p + halfSpan).toLong()
            val base = (k0 - winStart).toInt()
            val count = (k1 - k0 + 1).toInt()
            for (c in 0 until channelCount) {
                val x = window[c]
                var acc = 0.0
                var k = k0.toDouble()
                var idx = base
                for (j in 0 until count) {
                    acc += x[idx] * sinc.at((p - k) * scale)
                    k += 1.0
                    idx++
                }
                dst[c][off + i] = (acc * scale).toFloat()
            }
        }
        outPos += n
        return n
    }

    /** Makes the window hold the absolute input frames `[from, to]` (zeros outside the inner stream). */
    private fun ensureWindow(from: Long, to: Long) {
        val needLen = (to - from + 1).toInt()
        check(needLen <= capacity) { "window of $needLen frames exceeds capacity $capacity" }
        if (from >= winStart && from < winStart + winLen) {
            val drop = (from - winStart).toInt()
            if (drop > 0) {
                val keep = winLen - drop
                for (c in 0 until channelCount) System.arraycopy(window[c], drop, window[c], 0, keep)
                winLen = keep
                winStart = from
            }
        } else {
            winStart = from
            winLen = 0
        }
        while (winStart + winLen <= to) {
            val pos = winStart + winLen
            val want = (to - pos + 1).toInt()
            if (pos < 0) {
                val z = min(want.toLong(), -pos).toInt()
                zeroFill(winLen, z)
                winLen += z
                continue
            }
            if (innerTotal >= 0 && pos >= innerTotal) {
                zeroFill(winLen, want)
                winLen += want
                break
            }
            if (inner.position != pos) inner.seek(pos)
            val got = if (inner.position == pos) inner.read(window, winLen, want) else 0
            if (got <= 0) {
                innerTotal = if (inner.position < pos) inner.position else pos
                zeroFill(winLen, want)
                winLen += want
                break
            }
            winLen += got
        }
    }

    private fun zeroFill(at: Int, n: Int) {
        for (c in 0 until channelCount) java.util.Arrays.fill(window[c], at, at + n, 0f)
    }

    override fun seek(frame: Long) {
        if (identity) { inner.seek(frame); return }
        var f = frame.coerceAtLeast(0L)
        val total = totalFrames
        if (total >= 0 && f > total) f = total
        outPos = f
    }

    override fun close() { inner.close() }
}

/**
 * Adapts the channel count of a [PcmStream]: mono → N duplicates, N → mono averages, otherwise extra channels carry
 * the mono mix and surplus input channels are dropped (the same rules as [AudioBuffer.withChannels]).
 * Pass-through when the counts already match.
 */
class ChannelAdaptingPcmStream(val inner: PcmStream, override val channelCount: Int) : PcmStream {
    init { require(channelCount > 0) }

    override val sampleRate: Int get() = inner.sampleRate
    override val totalFrames: Long get() = inner.totalFrames
    override val position: Long get() = inner.position
    val identity: Boolean = inner.channelCount == channelCount

    private val inCh = inner.channelCount
    private var scratch = Array(inCh) { FloatArray(0) }

    override fun read(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        require(dst.size == channelCount) { "dst has ${dst.size} channels, stream has $channelCount" }
        if (identity) return inner.read(dst, offset, frames)
        if (frames <= 0) return 0
        if (scratch[0].size < frames) scratch = Array(inCh) { FloatArray(frames) }
        val n = inner.read(scratch, 0, frames)
        if (n <= 0) return 0
        when {
            inCh == 1 -> for (c in 0 until channelCount) System.arraycopy(scratch[0], 0, dst[c], offset, n)
            channelCount == 1 -> mixdown(dst[0], offset, n)
            else -> {
                for (c in 0 until channelCount) {
                    if (c < inCh) System.arraycopy(scratch[c], 0, dst[c], offset, n) else mixdown(dst[c], offset, n)
                }
            }
        }
        return n
    }

    private fun mixdown(out: FloatArray, offset: Int, n: Int) {
        val s = 1f / inCh
        java.util.Arrays.fill(out, offset, offset + n, 0f)
        for (c in 0 until inCh) { val x = scratch[c]; for (i in 0 until n) out[offset + i] += x[i] * s }
    }

    override fun seek(frame: Long) = inner.seek(frame)
    override fun close() = inner.close()
}

/**
 * JVM [EngineStreamFactory]: decoder → [ResamplingPcmStream] → [ChannelAdaptingPcmStream]. Gapless trimming is
 * not needed on the JVM (the SPI decoders emit what the file holds; frame 0 is the decoder's first frame).
 */
class JvmEngineStreamFactory(
    private val decoder: AudioDecoder = JavaSoundDecoder(),
    private val kernel: SincKernelSpec = SincKernelSpec.DEFAULT,
) : EngineStreamFactory {
    override fun open(source: AudioSourceId, sampleRate: Int, channels: Int): PcmStream =
        adapt(decoder.open(source), sampleRate, channels, kernel)

    companion object {
        /** Wraps [stream] so that it delivers [sampleRate] / [channels] (no wrapping when it already does). */
        fun adapt(stream: PcmStream, sampleRate: Int, channels: Int, kernel: SincKernelSpec = SincKernelSpec.DEFAULT): PcmStream {
            var s = stream
            if (s.sampleRate != sampleRate) s = ResamplingPcmStream(s, sampleRate, kernel)
            if (s.channelCount != channels) s = ChannelAdaptingPcmStream(s, channels)
            return s
        }
    }
}

/**
 * [EngineStreamFactory] over in-memory buffers keyed by source id (synthetic tracks, tests, the Lab). Every
 * `open` returns a fresh [BufferPcmStream] adapted to the requested format.
 */
class MemoryEngineStreamFactory(initial: Map<AudioSourceId, AudioBuffer> = emptyMap(), private val kernel: SincKernelSpec = SincKernelSpec.DEFAULT) : EngineStreamFactory {
    private val buffers = ConcurrentHashMap<AudioSourceId, AudioBuffer>(initial)

    fun register(source: AudioSourceId, audio: AudioBuffer): MemoryEngineStreamFactory { buffers[source] = audio; return this }
    operator fun set(source: AudioSourceId, audio: AudioBuffer) { buffers[source] = audio }
    operator fun get(source: AudioSourceId): AudioBuffer? = buffers[source]

    override fun open(source: AudioSourceId, sampleRate: Int, channels: Int): PcmStream {
        val audio = buffers[source] ?: throw IllegalArgumentException("no audio registered for $source")
        return JvmEngineStreamFactory.adapt(BufferPcmStream(audio), sampleRate, channels, kernel)
    }
}
