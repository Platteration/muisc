package dev.muisc.audio

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Minimal, dependency-free RIFF/WAVE reader and writer.
 * Reads PCM 8/16/24/32-bit integer and 32/64-bit float (incl. WAVE_FORMAT_EXTENSIBLE), any channel count.
 * Writes 16-bit PCM (default), 24-bit PCM, or 32-bit float.
 */
object WavIo {
    /**
     * WAV chunk lengths come from the file, so they must never be used as an
     * allocation size without a product limit. These limits also keep a
     * malformed file from exhausting the process while it is being inspected.
     */
    private const val MAX_FMT_CHUNK_BYTES = 1L shl 20
    private const val MAX_DATA_CHUNK_BYTES = 128L shl 20
    private const val MAX_DECODED_SAMPLES = 32L shl 20
    private const val UNKNOWN_CHUNK_SIZE = 0xFFFFFFFFL

    enum class Encoding(val bitsPerSample: Int, val isFloat: Boolean) { PCM16(16, false), PCM24(24, false), FLOAT32(32, true) }

    fun read(file: File): AudioBuffer = file.inputStream().buffered().use { read(it) }

    fun read(input: InputStream): AudioBuffer {
        val din = DataInputStream(input)
        val riff = ByteArray(4).also { din.readFully(it) }
        require(String(riff, Charsets.US_ASCII) == "RIFF") { "not a RIFF file" }
        din.readFully(ByteArray(4)) // riff size
        val wave = ByteArray(4).also { din.readFully(it) }
        require(String(wave, Charsets.US_ASCII) == "WAVE") { "not a WAVE file" }

        var formatTag = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var data: ByteArray? = null
        val hdr = ByteArray(8)
        while (data == null) {
            val n = din.read(hdr)
            if (n < 8) break
            val id = String(hdr, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    require(size >= 16L) { "WAVE fmt chunk is too small" }
                    require(size <= MAX_FMT_CHUNK_BYTES) { "WAVE fmt chunk is too large" }
                    val fmt = readExactly(din, size.toInt())
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    formatTag = bb.short.toInt() and 0xFFFF
                    channels = bb.short.toInt() and 0xFFFF
                    sampleRate = bb.int
                    bb.int // byte rate
                    bb.short // block align
                    bits = bb.short.toInt() and 0xFFFF
                    if (formatTag == 0xFFFE && size >= 40) { // WAVE_FORMAT_EXTENSIBLE: sub-format GUID's first two bytes hold the real tag
                        bb.short // cbSize
                        bb.short // valid bits
                        bb.int // channel mask
                        formatTag = bb.short.toInt() and 0xFFFF
                    }
                    if (size % 2L == 1L) din.readByte()
                }
                "data" -> {
                    data = if (size == UNKNOWN_CHUNK_SIZE || size == 0L) {
                        readUntilEof(din, MAX_DATA_CHUNK_BYTES)
                    } else {
                        require(size <= MAX_DATA_CHUNK_BYTES) { "WAVE data chunk is too large" }
                        readExactly(din, size.toInt())
                    }
                }
                else -> {
                    skipExactly(din, size + (size % 2L))
                }
            }
        }
        val bytes = data ?: throw IllegalArgumentException("WAVE file has no data chunk")
        require(channels > 0 && sampleRate > 0) { "WAVE file has no fmt chunk" }
        val isFloat = formatTag == 3
        require(formatTag == 1 || isFloat) { "unsupported WAVE format tag $formatTag" }
        require(if (isFloat) bits == 32 || bits == 64 else bits == 8 || bits == 16 || bits == 24 || bits == 32) {
            "unsupported bit depth $bits"
        }
        val bytesPerSample = bits / 8
        val frameBytes = bytesPerSample.toLong() * channels
        require(frameBytes > 0 && frameBytes <= Int.MAX_VALUE) { "invalid WAVE frame size" }
        val frameCount = bytes.size.toLong() / frameBytes
        require(frameCount <= MAX_DECODED_SAMPLES / channels) { "WAVE audio is too large to decode" }
        val frames = frameCount.toInt()
        val out = Array(channels) { FloatArray(frames) }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        when {
            isFloat && bits == 32 -> for (i in 0 until frames) for (c in 0 until channels) out[c][i] = bb.float
            isFloat && bits == 64 -> for (i in 0 until frames) for (c in 0 until channels) out[c][i] = bb.double.toFloat()
            bits == 8 -> for (i in 0 until frames) for (c in 0 until channels) out[c][i] = ((bb.get().toInt() and 0xFF) - 128) / 128f
            bits == 16 -> for (i in 0 until frames) for (c in 0 until channels) out[c][i] = bb.short / 32768f
            bits == 24 -> for (i in 0 until frames) for (c in 0 until channels) {
                val b0 = bb.get().toInt() and 0xFF; val b1 = bb.get().toInt() and 0xFF; val b2 = bb.get().toInt()
                out[c][i] = ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
            }
            bits == 32 -> for (i in 0 until frames) for (c in 0 until channels) out[c][i] = (bb.int / 2147483648.0).toFloat()
            else -> throw IllegalArgumentException("unsupported bit depth $bits")
        }
        return AudioBuffer(sampleRate, out)
    }

    private fun readExactly(input: DataInputStream, size: Int): ByteArray =
        ByteArray(size).also { input.readFully(it) }

    private fun readUntilEof(input: DataInputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= limit) { "WAVE data chunk is too large" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    private fun skipExactly(input: DataInputStream, requested: Long) {
        var remaining = requested
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) throw EOFException("truncated WAVE chunk")
            remaining--
        }
    }

    fun write(file: File, buffer: AudioBuffer, encoding: Encoding = Encoding.PCM16) {
        file.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { write(it, buffer, encoding) }
    }

    fun write(out: OutputStream, buffer: AudioBuffer, encoding: Encoding = Encoding.PCM16) {
        val channels = buffer.channelCount
        val bytesPerSample = encoding.bitsPerSample / 8
        val dataSize = buffer.frames.toLong() * channels * bytesPerSample
        require(dataSize < 0xFFFFFFFFL - 44) { "buffer too large for a WAVE file" }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(if (encoding.isFloat) 3 else 1)
        header.putShort(channels.toShort())
        header.putInt(buffer.sampleRate)
        header.putInt(buffer.sampleRate * channels * bytesPerSample)
        header.putShort((channels * bytesPerSample).toShort())
        header.putShort(encoding.bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize.toInt())
        out.write(header.array())

        val chunkFrames = 4096
        val bb = ByteBuffer.allocate(chunkFrames * channels * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < buffer.frames) {
            bb.clear()
            val n = minOf(chunkFrames, buffer.frames - i)
            for (f in 0 until n) for (c in 0 until channels) {
                val v = buffer.channels[c][i + f].coerceIn(-1f, 1f)
                when (encoding) {
                    Encoding.PCM16 -> bb.putShort((v * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                    Encoding.PCM24 -> { val s = (v * 8388608f).roundToInt().coerceIn(-8388608, 8388607); bb.put(s.toByte()); bb.put((s shr 8).toByte()); bb.put((s shr 16).toByte()) }
                    Encoding.FLOAT32 -> bb.putFloat(v)
                }
            }
            out.write(bb.array(), 0, bb.position())
            i += n
        }
        out.flush()
    }
}
