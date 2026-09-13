package dev.muisc.audio

import java.io.Closeable
import kotlin.math.min

/**
 * A seekable stream of decoded PCM frames. This is the contract every decoder implements
 * (JVM: javax.sound SPI; Android: MediaCodec) and every consumer reads from (analysis, transition
 * rendering, the real-time player).
 *
 * Frames are delivered planar into caller-provided channel arrays, as floats in [-1, 1].
 */
interface PcmStream : Closeable {
    val sampleRate: Int
    val channelCount: Int

    /** Total frames if known, else -1 (e.g. some VBR MP3 streams). */
    val totalFrames: Long

    /** Current read position in frames from the start of the track. */
    val position: Long

    /**
     * Reads up to [frames] frames into `dst[channel][offset + i]`. Returns the number of frames
     * actually read, or 0 at end of stream. `dst.size` must equal [channelCount].
     */
    fun read(dst: Array<FloatArray>, offset: Int, frames: Int): Int

    /**
     * Repositions the stream. Implementations may land slightly before the requested frame
     * (e.g. at a keyframe) and must then decode/skip forward so that [position] == [frame] on
     * return, unless [frame] is beyond the end.
     */
    fun seek(frame: Long)

    val durationSec: Double get() = if (totalFrames < 0) Double.NaN else totalFrames.toDouble() / sampleRate
}

/** Reads all remaining frames into memory. Use with care on long tracks; prefer [readRange]. */
fun PcmStream.readAll(chunkFrames: Int = 1 shl 16): AudioBuffer {
    val chunks = ArrayList<Array<FloatArray>>()
    var total = 0
    val tmp = Array(channelCount) { FloatArray(chunkFrames) }
    while (true) {
        val n = read(tmp, 0, chunkFrames)
        if (n <= 0) break
        chunks += Array(channelCount) { tmp[it].copyOfRange(0, n) }
        total += n
    }
    val out = Array(channelCount) { FloatArray(total) }
    var pos = 0
    for (chunk in chunks) {
        val n = chunk[0].size
        for (c in 0 until channelCount) System.arraycopy(chunk[c], 0, out[c], pos, n)
        pos += n
    }
    return AudioBuffer(sampleRate, out)
}

/**
 * Reads exactly [frames] frames starting at [startFrame]. If the stream ends early, the remainder is
 * zero-filled so the returned buffer always has [frames] frames (callers rely on this for tail/head
 * windows near the ends of a track).
 */
fun PcmStream.readRange(startFrame: Long, frames: Int): AudioBuffer {
    require(startFrame >= 0) { "startFrame must be >= 0" }
    require(frames >= 0) { "frames must be >= 0" }
    if (position != startFrame) seek(startFrame)
    val out = Array(channelCount) { FloatArray(frames) }
    var got = 0
    while (got < frames) {
        val n = read(out, got, min(frames - got, 1 shl 16))
        if (n <= 0) break
        got += n
    }
    return AudioBuffer(sampleRate, out)
}

fun PcmStream.readRangeSeconds(startSec: Double, durationSec: Double): AudioBuffer =
    readRange(Math.round(startSec * sampleRate), Math.round(durationSec * sampleRate).toInt())

/** A [PcmStream] over an in-memory [AudioBuffer]. Handy for tests and for rendered segments. */
class BufferPcmStream(private val buffer: AudioBuffer) : PcmStream {
    override val sampleRate: Int get() = buffer.sampleRate
    override val channelCount: Int get() = buffer.channelCount
    override val totalFrames: Long get() = buffer.frames.toLong()
    private var pos = 0
    override val position: Long get() = pos.toLong()

    override fun read(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        require(dst.size == channelCount) { "dst has ${dst.size} channels, stream has $channelCount" }
        val n = min(frames, buffer.frames - pos)
        if (n <= 0) return 0
        for (c in 0 until channelCount) System.arraycopy(buffer.channels[c], pos, dst[c], offset, n)
        pos += n
        return n
    }

    override fun seek(frame: Long) {
        pos = frame.coerceIn(0L, buffer.frames.toLong()).toInt()
    }

    override fun close() {}
}
