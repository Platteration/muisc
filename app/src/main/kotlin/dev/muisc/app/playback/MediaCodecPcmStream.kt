package dev.muisc.app.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.muisc.audio.AudioDecodeException
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.GaplessInfo
import dev.muisc.audio.PcmStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * A [PcmStream] over `MediaExtractor` + `MediaCodec` in **synchronous** mode (DESIGN.md §7.1).
 *
 * Frames are decoder-relative: frame 0 is the first PCM frame the platform decoder emits for this file, before any
 * gapless trimming (that is [GaplessTrimPcmStream]'s job, applied by [AndroidEngineStreamFactory]) and before
 * resampling. Output is planar float in [-1, 1]; `ENCODING_PCM_FLOAT` is requested from the codec and 8/16/24/32-bit
 * integer output is converted when the codec ignores the request.
 *
 * **Threading.** One instance belongs to exactly one thread at a time (the `PcmRing` producer thread for body decks,
 * the coordinator thread for the renderer/analyser). Nothing here is synchronised; [close] must not race with a read.
 *
 * **Positioning.** [seek] is cheap: it repositions the extractor, flushes (or re-creates) the codec and records how
 * many frames must be discarded — the discarding decode happens inside the next [read], i.e. on the decoder thread,
 * never on the audio thread that called `PcmRing.seek`.
 *  - Containers with reliable sample times (FLAC/WAV/OGG/Opus/ALAC, see [exactExtractorSeeks]) seek with
 *    `SEEK_TO_PREVIOUS_SYNC` plus a counted discard, which is frame-exact.
 *  - MP3/AAC sample times are not frame-exact across OEM decoders, so a seam-critical seek (this stream was opened
 *    for the renderer/analyser, [decodeFromZero] = true) or a backward seek **decodes from frame 0 and discards**
 *    (50–100× real time). A forward user seek in a body deck uses [userSeek], which trusts the extractor timestamp
 *    and sets [inexact] so the player applies the 20 ms seam fade instead of the 5 ms one.
 */
class MediaCodecPcmStream(
    private val context: Context?,
    val source: AudioSourceId,
    /** True when this stream feeds the renderer or the analyser: every seek is frame-exact (decode from zero). */
    val decodeFromZero: Boolean = false,
) : PcmStream {

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    private var trackIndex = -1
    private var trackFormat: MediaFormat? = null

    /** Container mime of the selected audio track ("audio/mpeg", "audio/mp4a-latm", "audio/flac", ...). */
    var mimeType: String = ""
        private set

    /** Codec name, part of the analysis fingerprint (`decoderId`, DESIGN.md §3.3). */
    var decoderName: String = ""
        private set

    /** True when `MediaExtractor` sample times can be trusted frame-exactly for this container. */
    val exactExtractorSeeks: Boolean get() = exactSeeks

    /** Encoder delay/padding the platform decoder reported in its `MediaFormat` (null when it reported none). */
    var formatGapless: GaplessInfo? = null
        private set

    /** True when the current position came from an untrusted extractor timestamp (see [userSeek]). */
    var inexact: Boolean = false
        private set

    private var exactSeeks = false
    private var srate = 0
    private var chans = 0
    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var total = -1L
    private var pos = 0L
    private var discard = 0L

    private var pending: Array<FloatArray> = emptyArray()
    private var pendingStart = 0
    private var pendingEnd = 0

    private var inputEos = false
    private var outputEos = false
    private var formatAdopted = false
    private var closed = false

    override val sampleRate: Int get() = srate
    override val channelCount: Int get() = chans
    override val totalFrames: Long get() = total
    override val position: Long get() = pos

    init {
        try {
            openExtractor()
            selectTrack()
            createCodec()
            // Prime: decode until the codec has published its output format, so sampleRate / channelCount /
            // totalFrames are final before the first read (ResamplingPcmStream reads them in its constructor).
            fill()
        } catch (e: Throwable) {
            releaseQuietly()
            if (e is AudioDecodeException) throw e
            throw AudioDecodeException("cannot decode ${source.value}: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------------------------------------ open

    private fun openExtractor() {
        try {
            extractor.setDataSourceCompat(context, source)
        } catch (e: AudioDecodeException) {
            throw e
        } catch (e: Exception) {
            throw AudioDecodeException("cannot open ${source.value}", e)
        }
    }

    private fun selectTrack() {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            trackIndex = i
            trackFormat = fmt
            mimeType = mime
            break
        }
        val fmt = trackFormat ?: throw AudioDecodeException("no audio track in ${source.value}")
        extractor.selectTrack(trackIndex)
        srate = fmt.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
        chans = max(1, fmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2))
        exactSeeks = mimeType.lowercase() in EXACT_SEEK_MIMES
        val delay = fmt.intOr(KEY_ENCODER_DELAY, -1)
        val padding = fmt.intOr(KEY_ENCODER_PADDING, -1)
        if (delay >= 0 || padding >= 0) formatGapless = GaplessInfo(max(0, delay), max(0, padding))
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1L
        total = if (durationUs > 0) Math.round(durationUs / 1_000_000.0 * srate) else -1L
    }

    private fun createCodec() {
        val fmt = trackFormat ?: throw AudioDecodeException("no audio track")
        val c = try {
            MediaCodec.createDecoderByType(mimeType)
        } catch (e: Exception) {
            throw AudioDecodeException("no decoder for $mimeType", e)
        }
        try {
            // Ask for float output; decoders that ignore it keep their integer encoding, which we convert.
            fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            c.configure(fmt, null, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (ignored: Exception) { }
            throw AudioDecodeException("cannot start decoder for $mimeType", e)
        }
        decoderName = try { c.name } catch (e: Exception) { mimeType }
        codec = c
        inputEos = false
        outputEos = false
        formatAdopted = false
    }

    private fun adoptOutputFormat(fmt: MediaFormat) {
        val newRate = fmt.intOr(MediaFormat.KEY_SAMPLE_RATE, srate)
        val newChannels = max(1, fmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, chans))
        pcmEncoding = fmt.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        if (newRate != srate && total > 0) total = Math.round(total * (newRate.toDouble() / srate))
        if (newChannels != chans) { pending = emptyArray(); pendingStart = 0; pendingEnd = 0 }
        srate = newRate
        chans = newChannels
        formatAdopted = true
    }

    // ------------------------------------------------------------------------------------------------ reading

    override fun read(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        require(dst.size == chans) { "dst has ${dst.size} channels, stream has $chans" }
        if (frames <= 0 || closed) return 0
        var produced = 0
        while (produced < frames) {
            var avail = pendingEnd - pendingStart
            if (discard > 0) {
                if (avail > 0) {
                    val d = min(avail.toLong(), discard).toInt()
                    pendingStart += d
                    discard -= d
                    continue
                }
                if (!fill()) { discard = 0; break }
                continue
            }
            if (avail <= 0) {
                if (!fill()) break
                avail = pendingEnd - pendingStart
                if (avail <= 0) break
            }
            val n = min(avail, frames - produced)
            for (c in 0 until chans) System.arraycopy(pending[c], pendingStart, dst[c], offset + produced, n)
            pendingStart += n
            produced += n
            pos += n
        }
        if (total < 0 && outputEos && pendingEnd - pendingStart == 0) total = pos
        return produced
    }

    /** Decodes until at least one frame is buffered. Returns false at the end of the stream. */
    private fun fill(): Boolean {
        var guard = 0
        while (pendingEnd - pendingStart <= 0 && !outputEos && guard++ < MAX_PUMPS) pump()
        return pendingEnd - pendingStart > 0
    }

    private fun pump() {
        val c = codec ?: return
        if (!inputEos) {
            val inIndex = try { c.dequeueInputBuffer(TIMEOUT_US) } catch (e: IllegalStateException) { -1 }
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex)
                if (buf == null) {
                    c.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                } else {
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        c.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        val ts = extractor.sampleTime
                        c.queueInputBuffer(inIndex, 0, size, if (ts < 0) 0L else ts, 0)
                        extractor.advance()
                    }
                }
            }
        }
        val outIndex = try {
            c.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
        } catch (e: IllegalStateException) {
            outputEos = true
            return
        }
        when {
            outIndex >= 0 -> {
                if (!formatAdopted) {
                    try { adoptOutputFormat(c.outputFormat) } catch (e: Exception) { formatAdopted = true }
                }
                if (bufferInfo.size > 0) {
                    val buf = c.getOutputBuffer(outIndex)
                    if (buf != null) append(buf, bufferInfo.offset, bufferInfo.size)
                }
                val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                try { c.releaseOutputBuffer(outIndex, false) } catch (e: IllegalStateException) { }
                if (eos) outputEos = true
            }
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                try { adoptOutputFormat(c.outputFormat) } catch (e: Exception) { }
            }
            else -> {
                // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_BUFFERS_CHANGED: keep pumping.
            }
        }
    }

    /** Converts one decoded buffer to planar float and appends it to the pending FIFO. */
    private fun append(buf: ByteBuffer, offset: Int, size: Int) {
        val ch = chans
        val bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            ENCODING_PCM_32BIT -> 4
            ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val frames = size / (bytesPerSample * ch)
        if (frames <= 0) return
        ensureCapacity(frames)
        val nio: java.nio.Buffer = buf
        nio.limit(offset + size)
        nio.position(offset)
        buf.order(ByteOrder.nativeOrder())
        var w = pendingEnd
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                var k = 0
                for (f in 0 until frames) {
                    for (c in 0 until ch) pending[c][w] = fb.get(k++)
                    w++
                }
            }
            ENCODING_PCM_32BIT -> {
                val ib = buf.asIntBuffer()
                var k = 0
                for (f in 0 until frames) {
                    for (c in 0 until ch) pending[c][w] = (ib.get(k++) / 2147483648.0).toFloat()
                    w++
                }
            }
            ENCODING_PCM_24BIT_PACKED -> {
                var p = offset
                for (f in 0 until frames) {
                    for (c in 0 until ch) {
                        val b0 = buf.get(p).toInt() and 0xFF
                        val b1 = buf.get(p + 1).toInt() and 0xFF
                        val b2 = buf.get(p + 2).toInt() // signed: carries the sign bit
                        pending[c][w] = (b0 or (b1 shl 8) or (b2 shl 16)) / 8388608.0f
                        p += 3
                    }
                    w++
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                var p = offset
                for (f in 0 until frames) {
                    for (c in 0 until ch) {
                        pending[c][w] = ((buf.get(p).toInt() and 0xFF) - 128) / 128.0f
                        p++
                    }
                    w++
                }
            }
            else -> {
                val sb = buf.asShortBuffer()
                var k = 0
                for (f in 0 until frames) {
                    for (c in 0 until ch) pending[c][w] = sb.get(k++) / 32768.0f
                    w++
                }
            }
        }
        pendingEnd = w
    }

    private fun ensureCapacity(extra: Int) {
        val live = pendingEnd - pendingStart
        if (pending.size != chans) {
            pending = Array(chans) { FloatArray(max(DEFAULT_PENDING, extra)) }
            pendingStart = 0
            pendingEnd = 0
            return
        }
        if (pendingEnd + extra <= pending[0].size) return
        if (live + extra <= pending[0].size) {
            for (c in 0 until chans) System.arraycopy(pending[c], pendingStart, pending[c], 0, live)
        } else {
            val cap = max(pending[0].size * 2, live + extra)
            val grown = Array(chans) { FloatArray(cap) }
            for (c in 0 until chans) System.arraycopy(pending[c], pendingStart, grown[c], 0, live)
            pending = grown
        }
        pendingStart = 0
        pendingEnd = live
    }

    // ------------------------------------------------------------------------------------------------ seeking

    /**
     * Frame-exact reposition (the default for every engine-internal seek). Cheap: the discarding decode happens in
     * the next [read]. Lossy formats decode from frame 0 when the seek goes backwards or when this stream serves the
     * renderer/analyser.
     */
    override fun seek(frame: Long) {
        if (closed) return
        val target = clampTarget(frame)
        if (target == pos && discard == 0L) return
        if (exactSeeks) {
            seekWithExtractor(target, trustTimestamp = true)
        } else if (decodeFromZero || target < pos) {
            restartFromZero()
            pos = target
            discard = target
            inexact = false
        } else {
            // Forward seek in a body deck of a lossy file: the extractor's timestamp is the base and the stream is
            // marked inexact (DESIGN.md §7.1); seam-critical readers set [decodeFromZero] and never land here.
            seekWithExtractor(target, trustTimestamp = false)
        }
    }

    /**
     * Reposition for a **user seek** in a body deck: MP3/AAC use the extractor's timestamp as the base and mark the
     * stream [inexact] (the player then uses `EngineLimits.inexactSeamFadeFrames` for the following seam). Exact
     * containers behave exactly like [seek].
     */
    fun userSeek(frame: Long) {
        if (closed) return
        val target = clampTarget(frame)
        if (target == pos && discard == 0L) return
        seekWithExtractor(target, trustTimestamp = exactSeeks)
    }

    private fun clampTarget(frame: Long): Long {
        val t = frame.coerceAtLeast(0L)
        return if (total >= 0) min(t, total) else t
    }

    /** Seeks the extractor to the sync sample at or before [target] and discards the difference. */
    private fun seekWithExtractor(target: Long, trustTimestamp: Boolean) {
        val us = (target.toDouble() / srate * 1_000_000.0).toLong()
        try {
            extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        } catch (e: Exception) {
            restartFromZero()
            pos = target
            discard = target
            inexact = false
            return
        }
        val sampleUs = extractor.sampleTime
        val base = if (sampleUs < 0) 0L else Math.round(sampleUs / 1_000_000.0 * srate)
        resetCodec()
        if (base > target) {
            // Landed after the target (broken index): start over from the beginning, which is always exact.
            restartFromZero()
            pos = target
            discard = target
            inexact = false
            return
        }
        pos = target
        discard = target - base
        inexact = !trustTimestamp
    }

    /** Rewinds the extractor to frame 0 and gives the codec a clean slate. */
    private fun restartFromZero() {
        try {
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        } catch (e: Exception) {
            // Nothing we can do; the codec restart below still yields silence rather than a crash.
        }
        resetCodec()
    }

    /**
     * Drops everything buffered and returns the codec to a state that accepts input. A codec that already saw
     * end-of-stream is re-created: `flush()` after EOS is not reliable across OEM decoders.
     */
    private fun resetCodec() {
        pendingStart = 0
        pendingEnd = 0
        discard = 0
        val c = codec
        if (c == null) {
            createCodec()
            return
        }
        if (inputEos || outputEos) {
            try { c.stop() } catch (e: Exception) { }
            try { c.release() } catch (e: Exception) { }
            codec = null
            createCodec()
        } else {
            try {
                c.flush()
            } catch (e: Exception) {
                try { c.release() } catch (ignored: Exception) { }
                codec = null
                createCodec()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseQuietly()
    }

    private fun releaseQuietly() {
        val c = codec
        codec = null
        if (c != null) {
            try { c.stop() } catch (e: Exception) { }
            try { c.release() } catch (e: Exception) { }
        }
        try { extractor.release() } catch (e: Exception) { }
        pending = emptyArray()
        pendingStart = 0
        pendingEnd = 0
    }

    private companion object {
        const val TIMEOUT_US = 2_000L
        const val MAX_PUMPS = 4_000
        const val DEFAULT_PENDING = 1 shl 14

        /** `AudioFormat.ENCODING_PCM_24BIT_PACKED` (API 31) as a literal, so minSdk 26 code can compare against it. */
        const val ENCODING_PCM_24BIT_PACKED = 21

        /** `AudioFormat.ENCODING_PCM_32BIT` (API 31). */
        const val ENCODING_PCM_32BIT = 22

        /** AOSP decoder keys; public API only since 31, but the keys are honoured much further back. */
        const val KEY_ENCODER_DELAY = "encoder-delay"
        const val KEY_ENCODER_PADDING = "encoder-padding"

        /** Containers whose extractor sample times address the exact PCM frame (seek tables / granule positions). */
        val EXACT_SEEK_MIMES = setOf(
            "audio/flac", "audio/x-flac", "audio/raw", "audio/wav", "audio/x-wav", "audio/wave",
            "audio/vorbis", "audio/opus", "audio/alac", "audio/x-alac",
        )

        fun MediaFormat.intOr(key: String, default: Int): Int =
            if (containsKey(key)) try { getInteger(key) } catch (e: Exception) { default } else default
    }
}
