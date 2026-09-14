package dev.muisc.app.playback

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.GaplessInfo
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Encoder delay / padding for gapless playback (DESIGN.md §7.1).
 *
 * Two sources, in this order:
 *  1. the platform decoder itself — when `MediaFormat` carries `encoder-delay` / `encoder-padding` the AOSP
 *     decoders already trim, so our own trim must be **skipped** ([fromMediaFormat] returns what they will trim);
 *  2. our own tag parsing: the LAME/Xing (or `Info`) frame of an MP3, the `iTunSMPB` atom of an AAC/M4A file.
 *
 * FLAC / WAV / OGG / Opus need no trimming (their frame counts are exact), so [parse] returns [GaplessInfo.NONE].
 * Every entry point is defensive: any malformed tag yields [GaplessInfo.NONE] rather than an exception, because a
 * wrong trim is far worse than no trim.
 */
class GaplessTagParser(private val context: Context?) {

    /**
     * Reads the encoder delay/padding of [source]. [mimeType] is the container mime when known (from MediaStore or
     * `MediaExtractor`); when null the file extension decides.
     */
    fun parse(source: AudioSourceId, mimeType: String? = null): GaplessInfo {
        val v = source.value
        val kind = kindOf(mimeType, v)
        return try {
            when (kind) {
                Kind.MP3 -> readHead(source, HEAD_BYTES)?.let { parseXingLame(it) } ?: GaplessInfo.NONE
                Kind.AAC -> {
                    val head = readHead(source, HEAD_BYTES)
                    val fromHead = head?.let { findItunSmpb(it) }
                    fromHead ?: readTail(source, TAIL_BYTES)?.let { findItunSmpb(it) } ?: GaplessInfo.NONE
                }
                Kind.OTHER -> GaplessInfo.NONE
            }
        } catch (e: Exception) {
            GaplessInfo.NONE
        }
    }

    private enum class Kind { MP3, AAC, OTHER }

    private fun kindOf(mimeType: String?, path: String): Kind {
        val mime = mimeType?.lowercase()
        if (mime != null) {
            if (mime.contains("mpeg") && !mime.contains("mp4")) return Kind.MP3
            if (mime.contains("mp3")) return Kind.MP3
            if (mime.contains("mp4") || mime.contains("aac") || mime.contains("m4a")) return Kind.AAC
            if (mime.contains("flac") || mime.contains("wav") || mime.contains("ogg") ||
                mime.contains("opus") || mime.contains("vorbis") || mime.contains("raw")
            ) return Kind.OTHER
        }
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> Kind.MP3
            "m4a", "mp4", "aac", "m4b" -> Kind.AAC
            else -> Kind.OTHER
        }
    }

    // ------------------------------------------------------------------------------------------------ file I/O

    private fun openStream(source: AudioSourceId): InputStream? {
        val v = source.value
        return try {
            when {
                v.startsWith("content://") -> context?.contentResolver?.openInputStream(Uri.parse(v))
                v.startsWith("file://") -> FileInputStream(Uri.parse(v).path ?: return null)
                else -> FileInputStream(v)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readHead(source: AudioSourceId, n: Int): ByteArray? {
        val stream = openStream(source) ?: return null
        return stream.use {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = it.read(out, got, n - got)
                if (r <= 0) break
                got += r
            }
            if (got <= 0) null else if (got == n) out else out.copyOf(got)
        }
    }

    /** Last [n] bytes of the file (an `iTunSMPB` atom sits in `moov`, which may be written at the end). */
    private fun readTail(source: AudioSourceId, n: Int): ByteArray? {
        val v = source.value
        return try {
            when {
                v.startsWith("content://") -> {
                    val ctx = context ?: return null
                    ctx.contentResolver.openFileDescriptor(Uri.parse(v), "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { fis -> readTail(fis, pfd.statSize, n) }
                    }
                }
                else -> {
                    val file = java.io.File(if (v.startsWith("file://")) (Uri.parse(v).path ?: return null) else v)
                    FileInputStream(file).use { fis -> readTail(fis, file.length(), n) }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readTail(stream: FileInputStream, size: Long, n: Int): ByteArray? {
        if (size <= 0) return null
        val want = min(size, n.toLong()).toInt()
        val from = size - want
        val channel = stream.channel
        channel.position(from)
        val out = ByteArray(want)
        var got = 0
        while (got < want) {
            val r = stream.read(out, got, want - got)
            if (r <= 0) break
            got += r
        }
        return if (got <= 0) null else if (got == want) out else out.copyOf(got)
    }

    companion object {
        private const val HEAD_BYTES = 256 * 1024
        private const val TAIL_BYTES = 256 * 1024

        /**
         * What the platform decoder itself will trim, or null when it reported nothing. When this is non-null the
         * decoder has already removed the priming/padding and [AndroidEngineStreamFactory] must not trim again.
         */
        fun fromMediaFormat(format: MediaFormat): GaplessInfo? {
            val delay = intOrNull(format, "encoder-delay")
            val padding = intOrNull(format, "encoder-padding")
            if (delay == null && padding == null) return null
            return GaplessInfo(max(0, delay ?: 0), max(0, padding ?: 0))
        }

        private fun intOrNull(format: MediaFormat, key: String): Int? =
            if (format.containsKey(key)) try { format.getInteger(key) } catch (e: Exception) { null } else null

        /**
         * Parses the LAME/Xing (or `Info`) tag inside the first MPEG audio frame of [head] (the first bytes of an
         * MP3 file, ID3v2 included). Returns [GaplessInfo.NONE] when there is no such tag.
         */
        fun parseXingLame(head: ByteArray): GaplessInfo {
            val start = skipId3v2(head)
            val frame = findMpegFrame(head, start) ?: return GaplessInfo.NONE
            val tag = xingOffset(head, frame) ?: return GaplessInfo.NONE
            val flags = beInt(head, tag + 4) ?: return GaplessInfo.NONE
            var p = tag + 8
            if (flags and 0x01 != 0) p += 4   // frame count
            if (flags and 0x02 != 0) p += 4   // byte count
            if (flags and 0x04 != 0) p += 100 // seek table
            if (flags and 0x08 != 0) p += 4   // quality
            // The 36-byte LAME extension starts here; delay/padding are 12 bits each at offset 21.
            if (p + 24 > head.size) return GaplessInfo.NONE
            // The extension starts with the encoder short name ("LAME3.100", "Lavc58.18", ...): printable ASCII.
            for (k in 0 until 4) {
                val ch = head[p + k].toInt() and 0xFF
                if (ch < 0x20 || ch > 0x7E) return GaplessInfo.NONE
            }
            val b21 = head[p + 21].toInt() and 0xFF
            val b22 = head[p + 22].toInt() and 0xFF
            val b23 = head[p + 23].toInt() and 0xFF
            val delay = (b21 shl 4) or (b22 shr 4)
            val padding = ((b22 and 0x0F) shl 8) or b23
            if (delay !in 0..10000 || padding !in 0..30000) return GaplessInfo.NONE
            return GaplessInfo(delay, padding)
        }

        /** Byte offset of an ID3v2 tag's end (0 when there is none). */
        private fun skipId3v2(b: ByteArray): Int {
            if (b.size < 10) return 0
            if (b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return 0
            val flags = b[5].toInt() and 0xFF
            val size = ((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or
                ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)
            val footer = if (flags and 0x10 != 0) 10 else 0
            val end = 10 + size + footer
            return if (end in 0..b.size) end else 0
        }

        /** Index of the first plausible MPEG audio frame header at or after [from]. */
        private fun findMpegFrame(b: ByteArray, from: Int): Int? {
            var i = max(0, from)
            val limit = min(b.size - 4, from + 8192)
            while (i <= limit) {
                val b0 = b[i].toInt() and 0xFF
                val b1 = b[i + 1].toInt() and 0xFF
                if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                    val version = (b1 shr 3) and 0x03   // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5, 1 = reserved
                    val layer = (b1 shr 1) and 0x03     // 1 = layer III
                    if (version != 1 && layer == 1) return i
                }
                i++
            }
            return null
        }

        /** Offset of the "Xing"/"Info" magic for the frame header at [frame], or null when the frame carries none. */
        private fun xingOffset(b: ByteArray, frame: Int): Int? {
            if (frame + 4 > b.size) return null
            val version = ((b[frame + 1].toInt() and 0xFF) shr 3) and 0x03
            val mode = ((b[frame + 3].toInt() and 0xFF) shr 6) and 0x03
            val mono = mode == 3
            val mpeg1 = version == 3
            val side = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
            val at = frame + 4 + side
            if (at + 8 > b.size) return null
            val magic = String(b, at, 4, Charsets.US_ASCII)
            return if (magic == "Xing" || magic == "Info") at else null
        }

        private fun beInt(b: ByteArray, at: Int): Int? {
            if (at < 0 || at + 4 > b.size) return null
            return ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        }

        /**
         * Finds an `iTunSMPB` free-form atom in [bytes] and parses its value. The value is ASCII hexadecimal, e.g.
         * ` 00000000 00000840 000001C0 0000000001F1E000 …`: field 1 is the encoder delay, field 2 the padding.
         */
        fun findItunSmpb(bytes: ByteArray): GaplessInfo? {
            val at = indexOf(bytes, "iTunSMPB".toByteArray(Charsets.US_ASCII)) ?: return null
            // The value follows in a "data" atom; scan a generous window for the hex fields.
            val from = at + 8
            val to = min(bytes.size, from + 512)
            if (to <= from) return null
            val text = String(bytes, from, to - from, Charsets.US_ASCII)
            return parseItunSmpb(text)
        }

        /**
         * Parses the hexadecimal fields of an `iTunSMPB` value: the first three are always eight digits, the first
         * of them zero, then the encoder delay and the padding. The window handed in still contains the surrounding
         * atom bytes (`data`, lengths), whose characters can look like hex, so the triple is located by shape rather
         * than by position.
         */
        fun parseItunSmpb(text: String): GaplessInfo? {
            val tokens = ArrayList<String>(16)
            val token = StringBuilder()
            for (ch in text) {
                if ((ch in '0'..'9') || (ch in 'A'..'F') || (ch in 'a'..'f')) {
                    token.append(ch)
                } else if (token.isNotEmpty()) {
                    tokens += token.toString()
                    token.setLength(0)
                    if (tokens.size >= 32) break
                }
            }
            if (token.isNotEmpty() && tokens.size < 32) tokens += token.toString()
            for (i in 0 until tokens.size - 2) {
                if (tokens[i].length != 8 || tokens[i + 1].length != 8 || tokens[i + 2].length != 8) continue
                if (tokens[i].toLongOrNull(16) != 0L) continue
                val delay = tokens[i + 1].toLongOrNull(16) ?: continue
                val padding = tokens[i + 2].toLongOrNull(16) ?: continue
                if (delay in 0..100_000 && padding in 0..300_000) return GaplessInfo(delay.toInt(), padding.toInt())
            }
            return null
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
            if (needle.isEmpty() || haystack.size < needle.size) return null
            val last = haystack.size - needle.size
            outer@ for (i in 0..last) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return null
        }
    }
}
