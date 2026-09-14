package dev.muisc.app.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.muisc.audio.AudioDecodeException
import dev.muisc.audio.AudioFormatInfo
import dev.muisc.audio.AudioDecoder
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.PcmStream
import kotlin.math.max

/**
 * The Android [AudioDecoder]: every format the device's `MediaCodec` supports, decoded by [MediaCodecPcmStream].
 *
 * [open] returns a **native-rate** stream (no resampling, no channel adaptation, no gapless trim) — the engine
 * format is produced by [AndroidEngineStreamFactory]. Use this decoder where a plain decoded stream is wanted
 * (probing, tools, the analyser's own fingerprinting).
 */
class AndroidAudioDecoder(
    private val context: Context,
    /** True when the streams this decoder opens must seek frame-exactly (renderer/analyser). */
    private val decodeFromZero: Boolean = false,
) : AudioDecoder {

    override fun canDecode(source: AudioSourceId): Boolean {
        val v = source.value
        if (v.startsWith("content://")) {
            val type = try { context.contentResolver.getType(Uri.parse(v)) } catch (e: Exception) { null }
            // An unknown type is not a reason to refuse: MediaStore audio URIs are what we are given.
            return type == null || type.startsWith("audio/") || type == "application/ogg"
        }
        val ext = v.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    override fun open(source: AudioSourceId): PcmStream =
        MediaCodecPcmStream(context, source, decodeFromZero = decodeFromZero)

    override fun probe(source: AudioSourceId): AudioFormatInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSourceCompat(context, source)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                val channels = max(1, if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2)
                var durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1L
                if (durationUs <= 0) durationUs = retrieverDurationUs(source)
                val frames = if (durationUs > 0) Math.round(durationUs / 1_000_000.0 * rate) else -1L
                return AudioFormatInfo(rate, channels, frames, mime)
            }
            throw AudioDecodeException("no audio track in ${source.value}")
        } catch (e: AudioDecodeException) {
            throw e
        } catch (e: Exception) {
            throw AudioDecodeException("cannot probe ${source.value}", e)
        } finally {
            try { extractor.release() } catch (e: Exception) { }
        }
    }

    private fun retrieverDurationUs(source: AudioSourceId): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            val v = source.value
            if (v.startsWith("content://")) retriever.setDataSource(context, Uri.parse(v))
            else retriever.setDataSource(if (v.startsWith("file://")) (Uri.parse(v).path ?: v) else v)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            if (ms > 0) ms * 1000L else -1L
        } catch (e: Exception) {
            -1L
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "mp4", "aac", "flac", "wav", "wave", "ogg", "oga", "opus", "mkv", "webm",
            "3gp", "amr", "awb", "mid", "xmf", "ts", "aif", "aiff",
        )
    }
}

/**
 * Points a `MediaExtractor` at [source], which may be a `content://` URI (needs [context]), a `file://` URI or a
 * plain path. Shared by [AndroidAudioDecoder] and [MediaCodecPcmStream].
 */
internal fun MediaExtractor.setDataSourceCompat(context: Context?, source: AudioSourceId) {
    val v = source.value
    when {
        v.startsWith("content://") -> {
            val ctx = context ?: throw AudioDecodeException("a Context is required to open $v")
            setDataSource(ctx, Uri.parse(v), null)
        }
        v.startsWith("file://") -> setDataSource(Uri.parse(v).path ?: v)
        else -> setDataSource(v)
    }
}
