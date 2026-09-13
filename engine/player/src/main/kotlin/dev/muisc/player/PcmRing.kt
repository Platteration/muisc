package dev.muisc.player

import dev.muisc.audio.PcmStream
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

/**
 * Single-producer / single-consumer ring of planar floats fed from a [PcmStream].
 *
 * The consumer (audio thread) calls [read] / [peek] / [skip], which never block and touch only [readPos]; the
 * producer fills the ring chunk by chunk with [fill] — either from its own thread ([start], which blocks with a short
 * park when the ring is full) or synchronously from the consumer ([readBlocking] when no producer thread runs, the
 * offline-rendering mode). The stream is opened lazily by whoever fills first, so decoder I/O never happens on the
 * audio thread when a producer thread is used.
 *
 * [seek] repositions the stream (cheaply when the target is already buffered: the last [historyFrames] consumed
 * frames are kept so small backward seeks after a live segment do not re-decode). [position] is the stream frame
 * of the next sample the consumer will read; [atEnd] is true when the stream ended and everything was consumed.
 */
class PcmRing(
    private val opener: () -> PcmStream,
    val channels: Int,
    val capacityFrames: Int,
    val chunkFrames: Int = 4096,
    val historyFrames: Int = min(capacityFrames / 4, 8192),
) : AutoCloseable {
    constructor(stream: PcmStream, capacityFrames: Int, chunkFrames: Int = 4096) : this({ stream }, stream.channelCount, capacityFrames, chunkFrames)

    init { require(channels > 0 && capacityFrames > chunkFrames && chunkFrames > 0) }

    private val buf = Array(channels) { FloatArray(capacityFrames) }
    private val chunk = Array(channels) { FloatArray(chunkFrames) }
    private val lock = Any()

    @Volatile private var readPos: Long = 0L
    @Volatile private var writePos: Long = 0L
    @Volatile private var eofAt: Long = -1L
    @Volatile private var baseFrame: Long = 0L
    @Volatile private var closed = false
    private var stream: PcmStream? = null
    private var producer: Thread? = null

    /** Stream frame of the next sample [read] returns. */
    val position: Long get() = baseFrame + readPos

    /** Frames buffered and not yet consumed. */
    val available: Int get() = (writePos - readPos).toInt()

    /** True once the stream has ended (the buffered remainder may still be unread). */
    val isEof: Boolean get() = eofAt >= 0

    /** True when the stream ended and nothing is left to read. */
    val atEnd: Boolean get() { val e = eofAt; return e >= 0 && readPos >= e }

    /** Whether a producer thread is running. */
    val threaded: Boolean get() = producer != null

    /** Starts the producer thread (idempotent). */
    fun start(name: String = "muisc-decoder"): PcmRing {
        synchronized(lock) {
            if (producer != null || closed) return this
            val t = Thread({ produceLoop() }, name)
            t.isDaemon = true
            t.priority = Thread.MAX_PRIORITY - 1
            producer = t
            t.start()
        }
        return this
    }

    private fun produceLoop() {
        while (!closed) {
            val added = try { fill() } catch (e: Exception) { synchronized(lock) { eofAt = writePos }; 0 }
            if (added == 0) LockSupport.parkNanos(1_000_000L)
        }
    }

    /**
     * Reads one chunk from the stream into the ring (opening the stream on first use). Returns the frames added:
     * 0 when the ring is full, the stream has ended, or the ring is closed.
     */
    fun fill(): Int {
        synchronized(lock) {
            if (closed || eofAt >= 0) return 0
            val s = stream ?: opener().also { st -> stream = st; if (st.position != baseFrame) st.seek(baseFrame) }
            val rp = readPos
            val keep = min(rp, historyFrames.toLong())
            val free = capacityFrames - (writePos - (rp - keep))
            val want = min(chunkFrames.toLong(), free).toInt()
            if (want <= 0) return 0
            val got = s.read(chunk, 0, want)
            if (got <= 0) { eofAt = writePos; return 0 }
            val idx = (writePos % capacityFrames).toInt()
            val first = min(got, capacityFrames - idx)
            for (c in 0 until channels) {
                System.arraycopy(chunk[c], 0, buf[c], idx, first)
                if (got > first) System.arraycopy(chunk[c], first, buf[c], 0, got - first)
            }
            writePos += got
            return got
        }
    }

    private fun copyOut(dst: Array<FloatArray>, offset: Int, n: Int, from: Long) {
        val idx = (from % capacityFrames).toInt()
        val first = min(n, capacityFrames - idx)
        for (c in 0 until channels) {
            System.arraycopy(buf[c], idx, dst[c], offset, first)
            if (n > first) System.arraycopy(buf[c], 0, dst[c], offset + first, n - first)
        }
    }

    /** Non-blocking read of up to [frames] frames; returns how many were copied (0 when starved or at the end). */
    fun read(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        val n = min(frames, available)
        if (n <= 0) return 0
        copyOut(dst, offset, n, readPos)
        readPos += n
        producer?.let { LockSupport.unpark(it) }
        return n
    }

    /** Like [read] but leaves the position untouched. */
    fun peek(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        val n = min(frames, available)
        if (n <= 0) return 0
        copyOut(dst, offset, n, readPos)
        return n
    }

    /** Consumes up to [frames] buffered frames without copying; returns how many. */
    fun skip(frames: Int): Int {
        val n = min(frames, available)
        if (n <= 0) return 0
        readPos += n
        producer?.let { LockSupport.unpark(it) }
        return n
    }

    /**
     * Blocks until [frames] frames are buffered or the stream ends; without a producer thread the ring fills itself
     * on the calling thread. Returns how many frames are now available (≤ frames means end of stream).
     */
    fun ensureAvailable(frames: Int): Int {
        while (available < frames && !isEof && !closed) {
            if (producer != null) { LockSupport.unpark(producer); LockSupport.parkNanos(200_000L) } else if (fill() == 0 && eofAt < 0) {
                // Full without reaching `frames`: the request exceeds the capacity.
                break
            }
        }
        return available
    }

    /** [read] that waits for data (see [ensureAvailable]); returns fewer than [frames] only at the end of the stream. */
    fun readBlocking(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        var done = 0
        while (done < frames) {
            val n = read(dst, offset + done, frames - done)
            if (n > 0) { done += n; continue }
            if (atEnd || closed) break
            ensureAvailable(min(frames - done, capacityFrames - historyFrames - chunkFrames).coerceAtLeast(1))
            if (available == 0 && isEof) break
        }
        return done
    }

    /** [peek] that waits for data. */
    fun peekBlocking(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        ensureAvailable(frames)
        return peek(dst, offset, frames)
    }

    /**
     * Repositions so that the next read returns stream frame [frame]. Served from the buffer when the frame is
     * within the unread data or the retained history; otherwise the stream is sought and the ring emptied.
     */
    fun seek(frame: Long) {
        synchronized(lock) {
            val pos = position
            val delta = frame - pos
            if (delta == 0L) return
            if (delta > 0 && delta <= available) { readPos += delta; producer?.let { LockSupport.unpark(it) }; return }
            if (delta < 0 && -delta <= min(readPos, historyFrames.toLong())) { readPos += delta; return }
            stream?.let { if (it.position != frame) it.seek(frame) }
            baseFrame = frame
            readPos = 0L
            writePos = 0L
            eofAt = -1L
            producer?.let { LockSupport.unpark(it) }
        }
    }

    /** Length of the stream if known (-1 otherwise; the stream must have been opened). */
    val totalFrames: Long get() = synchronized(lock) { stream?.totalFrames ?: -1L }

    override fun close() {
        closed = true
        producer?.let { LockSupport.unpark(it); it.join(500) }
        synchronized(lock) { stream?.close(); stream = null }
    }
}
