package dev.muisc.audio.jvm

import dev.muisc.audio.AudioDecodeException
import dev.muisc.audio.AudioDecoder
import dev.muisc.audio.AudioFormatInfo
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.PcmStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.min

/**
 * JVM decoder built on javax.sound.sampled. WAV/AIFF/AU work out of the box; MP3, FLAC and OGG-Vorbis are
 * provided by the SPI jars on the classpath (mp3spi, jflac, vorbisspi). Everything is converted to 16-bit
 * signed little-endian PCM at the file's native sample rate and channel count, then to floats.
 *
 * Seeking: compressed formats are re-opened and decoded forward from the start (no index), which is fine for
 * offline rendering/analysis but is why the Android app has its own MediaCodec-based decoder.
 */
class JavaSoundDecoder : AudioDecoder {
    private val extensions = setOf("wav", "wave", "aif", "aiff", "au", "mp3", "flac", "ogg", "oga")

    override fun canDecode(source: AudioSourceId): Boolean {
        val ext = File(source.value).extension.lowercase()
        return ext in extensions
    }

    override fun probe(source: AudioSourceId): AudioFormatInfo {
        val file = File(source.value)
        if (!file.isFile) throw AudioDecodeException("file not found: $file")
        try {
            val aff = AudioSystem.getAudioFileFormat(file)
            val fmt = aff.format
            val sr = if (fmt.sampleRate > 0) fmt.sampleRate.toInt() else 44100
            val ch = if (fmt.channels > 0) fmt.channels else 2
            var frames = aff.frameLength.toLong()
            val isPcm = fmt.encoding == AudioFormat.Encoding.PCM_SIGNED || fmt.encoding == AudioFormat.Encoding.PCM_UNSIGNED || fmt.encoding == AudioFormat.Encoding.PCM_FLOAT
            if (!isPcm || frames <= 0) {
                // mp3spi / vorbisspi expose "duration" in microseconds; jflac exposes frame length when known.
                val durUs = (aff.properties()["duration"] as? Long) ?: (aff.properties()["duration"] as? Int)?.toLong()
                frames = if (durUs != null && durUs > 0) durUs * sr / 1_000_000L else -1L
            }
            return AudioFormatInfo(sampleRate = sr, channelCount = ch, totalFrames = frames, encoding = fmt.encoding.toString())
        } catch (e: Exception) {
            throw AudioDecodeException("cannot probe $file: ${e.message}", e)
        }
    }

    override fun open(source: AudioSourceId): PcmStream {
        val file = File(source.value)
        if (!file.isFile) throw AudioDecodeException("file not found: $file")
        val info = probe(source)
        return JavaSoundPcmStream(file, info)
    }

    private class JavaSoundPcmStream(private val file: File, private val info: AudioFormatInfo) : PcmStream {
        override val sampleRate: Int get() = info.sampleRate
        override val channelCount: Int get() = info.channelCount
        override val totalFrames: Long get() = info.totalFrames
        private var pos = 0L
        override val position: Long get() = pos

        private var ais: AudioInputStream = openDecoded()
        private val frameBytes = channelCount * 2
        private var byteBuf = ByteArray(0)

        private fun openDecoded(): AudioInputStream {
            try {
                val raw = AudioSystem.getAudioInputStream(file)
                val base = raw.format
                val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, info.sampleRate.toFloat(), 16, info.channelCount, info.channelCount * 2, info.sampleRate.toFloat(), false)
                return if (base.matches(target)) raw else AudioSystem.getAudioInputStream(target, raw)
            } catch (e: Exception) {
                throw AudioDecodeException("cannot decode $file: ${e.message}", e)
            }
        }

        override fun read(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
            require(dst.size == channelCount) { "dst has ${dst.size} channels, stream has $channelCount" }
            if (frames <= 0) return 0
            val want = frames * frameBytes
            if (byteBuf.size < want) byteBuf = ByteArray(want)
            var got = 0
            while (got < want) {
                val n = ais.read(byteBuf, got, want - got)
                if (n <= 0) break
                got += n
            }
            val gotFrames = got / frameBytes
            var j = 0
            for (i in 0 until gotFrames) for (c in 0 until channelCount) {
                val lo = byteBuf[j].toInt() and 0xFF
                val hi = byteBuf[j + 1].toInt()
                dst[c][offset + i] = ((hi shl 8) or lo).toShort() / 32768f
                j += 2
            }
            pos += gotFrames
            return gotFrames
        }

        override fun seek(frame: Long) {
            val target = frame.coerceAtLeast(0)
            if (target < pos) {
                ais.close()
                ais = openDecoded()
                pos = 0
            }
            var remaining = target - pos
            val skipBuf = ByteArray(1 shl 16)
            while (remaining > 0) {
                val n = ais.read(skipBuf, 0, min(skipBuf.size.toLong(), remaining * frameBytes).toInt())
                if (n <= 0) break
                val f = n / frameBytes
                pos += f
                remaining -= f
            }
        }

        override fun close() { ais.close() }
    }
}
