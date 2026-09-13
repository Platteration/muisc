package dev.muisc.audio

/**
 * Opens audio files/URIs as [PcmStream]s. Platform-specific:
 *  - JVM/CLI: [dev.muisc.audio.jvm.JavaSoundDecoder] (WAV/AIFF natively, MP3/FLAC/OGG via SPI providers)
 *  - Android: `MediaCodecDecoder` in the app module (all formats the device supports)
 *
 * [AudioSourceId] is an opaque string: a file path on the JVM, a `content://` URI or path on Android.
 */
interface AudioDecoder {
    /** Whether this decoder believes it can open [source] (by extension/mime; cheap, no I/O required). */
    fun canDecode(source: AudioSourceId): Boolean

    /** Opens a stream. Throws [AudioDecodeException] on unsupported/corrupt input. */
    fun open(source: AudioSourceId): PcmStream

    /** Cheap metadata probe without decoding the whole file (duration may be an estimate). */
    fun probe(source: AudioSourceId): AudioFormatInfo
}

@JvmInline
value class AudioSourceId(val value: String) {
    override fun toString(): String = value
}

data class AudioFormatInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val totalFrames: Long,
    val encoding: String,
) {
    val durationSec: Double get() = if (totalFrames < 0) Double.NaN else totalFrames.toDouble() / sampleRate
}

class AudioDecodeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A decoder that tries a list of decoders in order. */
class CompositeDecoder(private val decoders: List<AudioDecoder>) : AudioDecoder {
    override fun canDecode(source: AudioSourceId) = decoders.any { it.canDecode(source) }
    override fun open(source: AudioSourceId): PcmStream =
        decoders.firstOrNull { it.canDecode(source) }?.open(source)
            ?: throw AudioDecodeException("No decoder for $source")
    override fun probe(source: AudioSourceId): AudioFormatInfo =
        decoders.firstOrNull { it.canDecode(source) }?.probe(source)
            ?: throw AudioDecodeException("No decoder for $source")
}
