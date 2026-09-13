package dev.muisc.player

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.WavIo
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where rendered blocks go. Android: `AudioTrack`; JVM: a WAV file ([WavFileSink]), the sound card ([JavaSoundSink])
 * or memory ([CapturingSink]). [write] blocks until the sink has taken the frames: it is the clock of the audio thread.
 */
interface AudioSink : AutoCloseable {
    val sampleRate: Int
    val channels: Int

    /** Writes [frames] interleaved frames (`frames * channels` floats in [-1, 1]); blocking. */
    fun write(interleaved: FloatArray, frames: Int)

    /** Frames queued in the sink that have not been heard yet. */
    fun latencyFrames(): Int
    fun pause()
    fun flush()
    fun resume()
}

/** Collects everything written, for tests and offline rendering. */
class CapturingSink(override val sampleRate: Int, override val channels: Int) : AudioSink {
    private val chunks = ArrayList<FloatArray>()
    private var total = 0
    var paused: Boolean = false
        private set
    var flushes: Int = 0
        private set

    val frames: Int get() = total

    override fun write(interleaved: FloatArray, frames: Int) {
        if (frames <= 0) return
        chunks += interleaved.copyOf(frames * channels)
        total += frames
    }

    override fun latencyFrames(): Int = 0
    override fun pause() { paused = true }
    override fun flush() { flushes++ }
    override fun resume() { paused = false }
    override fun close() {}

    /** Everything written so far as a planar buffer. */
    fun toBuffer(): AudioBuffer {
        val out = Array(channels) { FloatArray(total) }
        var pos = 0
        for (chunk in chunks) {
            val n = chunk.size / channels
            var j = 0
            for (i in 0 until n) for (c in 0 until channels) out[c][pos + i] = chunk[j++]
            pos += n
        }
        return AudioBuffer(sampleRate, out)
    }
}

/**
 * Writes a RIFF/WAVE file incrementally (32-bit float by default, or 16/24-bit PCM); the header sizes are patched
 * on [close]. Until then the file is a valid stream with a placeholder length.
 */
class WavFileSink(
    val file: File,
    override val sampleRate: Int,
    override val channels: Int,
    val encoding: WavIo.Encoding = WavIo.Encoding.FLOAT32,
) : AudioSink {
    private val bytesPerSample = encoding.bitsPerSample / 8
    private val out: BufferedOutputStream
    private var dataBytes = 0L
    private var bb = ByteBuffer.allocate(4096 * channels * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
    private var closed = false

    val framesWritten: Long get() = dataBytes / (channels * bytesPerSample)

    init {
        require(sampleRate > 0 && channels > 0)
        file.parentFile?.mkdirs()
        out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        out.write(header(0L))
    }

    private fun header(dataSize: Long): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt((36 + dataSize).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)
        h.putShort(if (encoding.isFloat) 3 else 1)
        h.putShort(channels.toShort())
        h.putInt(sampleRate)
        h.putInt(sampleRate * channels * bytesPerSample)
        h.putShort((channels * bytesPerSample).toShort())
        h.putShort(encoding.bitsPerSample.toShort())
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(dataSize.toInt())
        return h.array()
    }

    override fun write(interleaved: FloatArray, frames: Int) {
        check(!closed) { "sink closed" }
        val samples = frames * channels
        if (samples <= 0) return
        if (bb.capacity() < samples * bytesPerSample) bb = ByteBuffer.allocate(samples * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        bb.clear()
        for (i in 0 until samples) {
            val v = interleaved[i].coerceIn(-1f, 1f)
            when (encoding) {
                WavIo.Encoding.PCM16 -> bb.putShort((v * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                WavIo.Encoding.PCM24 -> { val s = (v * 8388608f).roundToInt().coerceIn(-8388608, 8388607); bb.put(s.toByte()); bb.put((s shr 8).toByte()); bb.put((s shr 16).toByte()) }
                WavIo.Encoding.FLOAT32 -> bb.putFloat(v)
            }
        }
        out.write(bb.array(), 0, bb.position())
        dataBytes += bb.position()
    }

    override fun latencyFrames(): Int = 0
    override fun pause() {}
    override fun flush() { out.flush() }
    override fun resume() {}

    override fun close() {
        if (closed) return
        closed = true
        out.flush()
        out.close()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(dataBytes))
        }
    }
}

/**
 * Plays through `javax.sound.sampled` (16-bit PCM on a [SourceDataLine]) for `muisc play`. Not exercised by tests.
 */
class JavaSoundSink(override val sampleRate: Int, override val channels: Int, bufferMs: Int = 200) : AudioSink {
    private val format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate.toFloat(), 16, channels, channels * 2, sampleRate.toFloat(), false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).also {
        it.open(format, (bufferMs.toLong() * sampleRate / 1000L).toInt() * channels * 2)
        it.start()
    }
    private var bytes = ByteArray(0)

    override fun write(interleaved: FloatArray, frames: Int) {
        val n = frames * channels * 2
        if (bytes.size < n) bytes = ByteArray(n)
        var j = 0
        for (i in 0 until frames * channels) {
            val s = (interleaved[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            bytes[j++] = s.toByte(); bytes[j++] = (s shr 8).toByte()
        }
        var off = 0
        while (off < n) off += line.write(bytes, off, n - off)
    }

    override fun latencyFrames(): Int = (line.bufferSize - line.available()) / (channels * 2)
    override fun pause() { line.stop() }
    override fun flush() { line.flush() }
    override fun resume() { line.start() }
    override fun close() { line.drain(); line.stop(); line.close() }
}

/**
 * Drives a [ProgramPlayer] into an [AudioSink]: renders blocks of `player.limits.blockFrames`, interleaves and
 * writes until the player reports the end of the program (or [stop] is called). [onBlock] runs after every block
 * (drain events, apply commands) on the pumping thread.
 */
class SinkPump(val player: ProgramPlayer, val sink: AudioSink, private val onBlock: (SinkPump) -> Unit = {}) {
    init { require(sink.sampleRate == player.sampleRate && sink.channels == player.channels) { "sink format mismatch" } }

    private val blockFrames = player.limits.blockFrames
    private val block = Array(player.channels) { FloatArray(blockFrames) }
    private val interleaved = FloatArray(blockFrames * player.channels)
    @Volatile private var stopped = false

    /** Frames written to the sink so far. */
    var framesWritten: Long = 0L
        private set

    fun stop() { stopped = true }

    /** Blocks until the program ends. Returns the frames written. */
    fun run(): Long {
        while (!stopped) {
            val n = player.render(block, blockFrames)
            if (n <= 0) break
            interleave(block, interleaved, n, player.channels)
            sink.write(interleaved, n)
            framesWritten += n
            onBlock(this)
        }
        return framesWritten
    }

    companion object {
        fun interleave(planar: Array<FloatArray>, out: FloatArray, frames: Int, channels: Int) {
            for (c in 0 until channels) {
                val ch = planar[c]
                var j = c
                for (i in 0 until frames) { out[j] = ch[i]; j += channels }
            }
        }

        /** Convenience: copies [frames] planar frames into [buffer] growing storage. */
        fun copyFrames(src: Array<FloatArray>, frames: Int, dst: Array<FloatArray>, dstOffset: Int) {
            for (c in src.indices) System.arraycopy(src[c], 0, dst[c], dstOffset, min(frames, dst[c].size - dstOffset))
        }
    }
}
