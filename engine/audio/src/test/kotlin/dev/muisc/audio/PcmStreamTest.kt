package dev.muisc.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PcmStreamTest {
    @Test
    fun readRangeZeroFillsPastEnd() {
        val b = AudioBuffer.mono(10, FloatArray(10) { it.toFloat() })
        val s = BufferPcmStream(b)
        val r = s.readRange(7, 6)
        assertEquals(6, r.frames)
        assertContentEquals(floatArrayOf(7f, 8f, 9f, 0f, 0f, 0f), r[0])
        val all = BufferPcmStream(b).readAll(chunkFrames = 3)
        assertContentEquals(b[0], all[0])
    }

    @Test
    fun readRangeSeeksBackwards() {
        val b = AudioBuffer.mono(10, FloatArray(10) { it.toFloat() })
        val s = BufferPcmStream(b)
        s.readRange(5, 2)
        val r = s.readRange(1, 2)
        assertContentEquals(floatArrayOf(1f, 2f), r[0])
        assertEquals(3L, s.position)
    }
}
