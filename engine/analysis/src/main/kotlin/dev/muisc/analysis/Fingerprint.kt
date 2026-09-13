package dev.muisc.analysis

import dev.muisc.audio.AudioBuffer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max

/**
 * Content fingerprints for the analysis cache (`TrackAnalysis.fingerprint`).
 *
 * **Files** ([ofFile] / [ofSource]): lowercase hex of `SHA-256(sizeBytes ‖ mtimeMillis ‖ head ‖ tail)` where
 * `head` is the first [HEAD_BYTES] (1 MiB) of the file and `tail` the last [TAIL_BYTES] (64 KiB) — for a file
 * shorter than `HEAD_BYTES + TAIL_BYTES` the whole file is hashed exactly once (the tail region starts at
 * `max(HEAD_BYTES, size - TAIL_BYTES)`). Size and mtime are encoded as 8-byte big-endian longs. Consequences,
 * by design:
 *  - Re-tagging changes the fingerprint only when the edit touches the hashed regions or the size / mtime.
 *    ID3v2 (head of an MP3), Vorbis comments and FLAC metadata blocks (head), ID3v1 and APE tags (tail) all do —
 *    so a re-tagged file is re-analysed even though its audio did not change (safe, slightly wasteful). An
 *    edit strictly inside the middle of a large file with size and mtime preserved is **not** detected; only
 *    the size / mtime are cheap enough for every library scan, and hashing the whole file is not.
 *  - Copying a file keeps its fingerprint only if the copy preserves the modification time.
 * Android computes the same value for a `content://` URI through [ofSource] with the size and mtime from the
 * MediaStore and an `InputStream`-opening lambda (the stream is opened once and read sequentially: head, skip,
 * tail — no random access needed).
 *
 * **Bytes** ([ofBytes]): the same recipe on an in-memory byte array (size = the array length unless given).
 *
 * **Buffers** ([ofBuffer]): for synthetic / in-memory audio. Hashes the sample rate, channel count, frame count
 * and, per channel, every `stride`-th sample quantised to 16 bits (`stride = ceil(frames / 65536)`, so at most
 * ~64 k samples per channel are hashed) — a content hash that is stable across runs and cheap, changes when
 * the audio changes materially, but is *not* a perceptual hash (a resampled or re-encoded copy gets a
 * different value).
 *
 * The cache key also includes the decoder identity (see [AnalysisService]); this object only hashes content.
 */
object Fingerprint {
    const val HEAD_BYTES: Long = 1L shl 20
    const val TAIL_BYTES: Long = 64L shl 10
    private const val ALGORITHM = "SHA-256"
    private const val MAX_BUFFER_SAMPLES = 1 shl 16

    /** Fingerprint of a file: size, mtime, first 1 MiB and last 64 KiB. */
    fun ofFile(file: File): String {
        if (!file.isFile) throw IOException("not a file: $file")
        return ofSource(file.length(), file.lastModified()) { file.inputStream().buffered(1 shl 16) }
    }

    /**
     * Fingerprint of any byte source of [sizeBytes] bytes modified at [mtimeMillis]. [open] must return a fresh
     * stream positioned at byte 0; it is opened exactly once and closed here.
     */
    fun ofSource(sizeBytes: Long, mtimeMillis: Long, open: () -> InputStream): String {
        require(sizeBytes >= 0) { "sizeBytes must be >= 0" }
        val md = digest()
        putLong(md, sizeBytes)
        putLong(md, mtimeMillis)
        val headEnd = minOf(sizeBytes, HEAD_BYTES)
        val tailStart = max(HEAD_BYTES, sizeBytes - TAIL_BYTES)
        open().use { input ->
            copyRange(input, md, headEnd)
            if (tailStart < sizeBytes) {
                skipFully(input, tailStart - headEnd)
                copyRange(input, md, sizeBytes - tailStart)
            }
        }
        return hex(md.digest())
    }

    /** Same recipe as [ofSource] on an in-memory array. */
    fun ofBytes(bytes: ByteArray, sizeBytes: Long = bytes.size.toLong(), mtimeMillis: Long = 0L): String =
        ofSource(sizeBytes, mtimeMillis) { bytes.inputStream() }

    /** Content hash of decoded audio (see the object KDoc). */
    fun ofBuffer(audio: AudioBuffer): String {
        val md = digest()
        putLong(md, audio.sampleRate.toLong())
        putLong(md, audio.channelCount.toLong())
        putLong(md, audio.frames.toLong())
        val frames = audio.frames
        val stride = max(1, (frames + MAX_BUFFER_SAMPLES - 1) / MAX_BUFFER_SAMPLES)
        val block = ByteArray(2 * ((frames + stride - 1) / stride).coerceAtLeast(0))
        for (c in 0 until audio.channelCount) {
            val ch = audio[c]
            var j = 0
            var i = 0
            while (i < frames) {
                val q = Math.round(ch[i].coerceIn(-1f, 1f) * 32767f).toInt()
                block[j++] = (q ushr 8).toByte()
                block[j++] = q.toByte()
                i += stride
            }
            md.update(block, 0, j)
        }
        return hex(md.digest())
    }

    private fun digest(): MessageDigest = MessageDigest.getInstance(ALGORITHM)

    private fun putLong(md: MessageDigest, v: Long) {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = (v ushr (56 - 8 * i)).toByte()
        md.update(b)
    }

    private fun copyRange(input: InputStream, md: MessageDigest, count: Long) {
        if (count <= 0) return
        val buf = ByteArray(1 shl 16)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("stream ended ${remaining} bytes early")
            md.update(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(1 shl 16)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) { remaining -= skipped; continue }
            // skip() may return 0 without reaching EOF: fall back to reading.
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("stream ended $remaining bytes early while skipping")
            remaining -= n
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[2 * i] = HEX[v ushr 4]
            out[2 * i + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
