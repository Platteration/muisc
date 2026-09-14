package dev.muisc.app.playback

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.BufferPcmStream
import dev.muisc.audio.GaplessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pure-JVM tests of the gapless tag parsing and the trim stream (no Android APIs are touched: the parsing entry
 * points used here take a `ByteArray` / `String`, and the trim stream wraps any [dev.muisc.audio.PcmStream]).
 */
class GaplessTagParserTest {

    /** Builds the head of an MP3 file: optional ID3v2, one frame header, side info, a Xing/LAME tag. */
    private fun mp3Head(delay: Int, padding: Int, withId3: Boolean, mpeg1: Boolean = true, mono: Boolean = false): ByteArray {
        val out = ArrayList<Byte>()
        fun put(vararg values: Int) { for (v in values) out.add(v.toByte()) }
        if (withId3) {
            out.addAll("ID3".toByteArray(Charsets.US_ASCII).toList())
            put(4, 0, 0)            // version, flags
            put(0, 0, 0, 100)       // syncsafe size
            repeat(100) { put(0) }
        }
        put(0xFF, if (mpeg1) 0xFB else 0xF3, 0x90, if (mono) 0xC4 else 0x64)
        val side = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        repeat(side) { put(0) }
        out.addAll("Xing".toByteArray(Charsets.US_ASCII).toList())
        put(0, 0, 0, 0x0F)          // flags: frames | bytes | toc | quality
        put(0, 0, 0x10, 0)          // frame count
        put(0, 0x10, 0, 0)          // byte count
        repeat(100) { put(0) }      // seek table
        put(0, 0, 0, 100)           // quality
        out.addAll("LAME3.100".toByteArray(Charsets.US_ASCII).toList())
        repeat(21 - 9) { put(0) }
        put(delay shr 4, ((delay and 0x0F) shl 4) or (padding shr 8), padding and 0xFF)
        repeat(64) { put(0) }
        return out.toByteArray()
    }

    @Test
    fun `reads LAME delay and padding behind an ID3v2 tag`() {
        val info = GaplessTagParser.parseXingLame(mp3Head(576, 1234, withId3 = true))
        assertEquals(576, info.encoderDelayFrames)
        assertEquals(1234, info.encoderPaddingFrames)
    }

    @Test
    fun `reads LAME delay without an ID3v2 tag`() {
        val info = GaplessTagParser.parseXingLame(mp3Head(1105, 0, withId3 = false))
        assertEquals(1105, info.encoderDelayFrames)
        assertEquals(0, info.encoderPaddingFrames)
    }

    @Test
    fun `honours the side-info size of mono and MPEG2 frames`() {
        val mono = GaplessTagParser.parseXingLame(mp3Head(576, 288, withId3 = true, mono = true))
        assertEquals(576, mono.encoderDelayFrames)
        assertEquals(288, mono.encoderPaddingFrames)
        val mpeg2 = GaplessTagParser.parseXingLame(mp3Head(2000, 3000, withId3 = false, mpeg1 = false))
        assertEquals(2000, mpeg2.encoderDelayFrames)
        assertEquals(3000, mpeg2.encoderPaddingFrames)
    }

    @Test
    fun `garbage yields no trim at all`() {
        assertEquals(GaplessInfo.NONE, GaplessTagParser.parseXingLame(ByteArray(400)))
    }

    @Test
    fun `parses an iTunSMPB value`() {
        val info = GaplessTagParser.parseItunSmpb(
            " 00000000 00000840 000001C0 0000000000E7C9C0 00000000 00000000 00000000 00000000"
        )
        assertNotNull(info)
        assertEquals(0x840, info.encoderDelayFrames)
        assertEquals(0x1C0, info.encoderPaddingFrames)
    }

    @Test
    fun `finds iTunSMPB even behind atom bytes that look like hex`() {
        val noisy = "   data        00000000 00000840 000001C0 0000000000E7C9C0 00000000"
        val info = GaplessTagParser.findItunSmpb(("xxxx" + "iTunSMPB" + noisy).toByteArray(Charsets.ISO_8859_1))
        assertNotNull(info)
        assertEquals(0x840, info.encoderDelayFrames)
        assertEquals(0x1C0, info.encoderPaddingFrames)
        assertNull(GaplessTagParser.findItunSmpb(ByteArray(64)))
    }
}

class GaplessTrimPcmStreamTest {

    private fun trimmed(delay: Int, padding: Int, frames: Int = 1000): GaplessTrimPcmStream {
        val ramp = FloatArray(frames) { it.toFloat() }
        return GaplessTrimPcmStream(BufferPcmStream(AudioBuffer(44100, arrayOf(ramp))), GaplessInfo(delay, padding))
    }

    @Test
    fun `frame zero is the first sample after the encoder delay`() {
        val stream = trimmed(10, 20)
        assertEquals(970L, stream.totalFrames)
        assertEquals(0L, stream.position)
        val dst = Array(1) { FloatArray(8) }
        assertEquals(5, stream.read(dst, 0, 5))
        assertEquals(10f, dst[0][0])
        assertEquals(14f, dst[0][4])
        assertEquals(5L, stream.position)
    }

    @Test
    fun `seek maps outer frames to inner frames`() {
        val stream = trimmed(10, 20)
        stream.seek(100)
        assertEquals(100L, stream.position)
        val dst = Array(1) { FloatArray(1) }
        assertEquals(1, stream.read(dst, 0, 1))
        assertEquals(110f, dst[0][0])
    }

    @Test
    fun `reading stops before the encoder padding`() {
        val stream = trimmed(10, 20)
        stream.seek(965)
        val dst = Array(1) { FloatArray(8) }
        var total = 0
        while (true) {
            val n = stream.read(dst, 0, 8)
            if (n <= 0) break
            total += n
        }
        assertEquals(5, total)
        assertEquals(970L, stream.position)
    }

    @Test
    fun `a stream without gapless info is a pass-through`() {
        val stream = trimmed(0, 0)
        assertEquals(1000L, stream.totalFrames)
        assertEquals(0L, stream.position)
    }
}
