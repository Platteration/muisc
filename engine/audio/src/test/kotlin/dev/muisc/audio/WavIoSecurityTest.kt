package dev.muisc.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WavIoSecurityTest {
    @Test
    fun rejectsDataChunkBeforeAllocatingTheDeclaredSize() {
        val wav = headerWithChunk("data", 0x7FFFFFFFL)

        assertFailsWith<IllegalArgumentException> { WavIo.read(wav.inputStream()) }
    }

    @Test
    fun rejectsOversizedFormatChunkBeforeReadingItsPayload() {
        val wav = headerWithChunk("fmt ", (1L shl 20) + 1)

        assertFailsWith<IllegalArgumentException> { WavIo.read(wav.inputStream()) }
    }

    private fun headerWithChunk(id: String, size: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size.toInt()).array())
        return out.toByteArray()
    }
}
