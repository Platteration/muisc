package dev.muisc.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioBufferTest {
    @Test
    fun sliceZeroFillsOutOfRange() {
        val b = AudioBuffer.mono(100, floatArrayOf(1f, 2f, 3f, 4f))
        val s = b.slice(-2, 6)
        assertEquals(8, s.frames)
        assertContentEquals(floatArrayOf(0f, 0f, 1f, 2f, 3f, 4f, 0f, 0f), s[0])
    }

    @Test
    fun monoAveragesChannels() {
        val b = AudioBuffer.stereo(10, floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        assertContentEquals(floatArrayOf(0.5f, 0.5f), b.mono())
    }

    @Test
    fun interleavedRoundTrip() {
        val b = AudioBuffer.stereo(10, floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f))
        val i = b.interleaved()
        assertContentEquals(floatArrayOf(1f, 4f, 2f, 5f, 3f, 6f), i)
        val back = AudioBuffer.fromInterleaved(10, 2, i)
        assertContentEquals(b[0], back[0]); assertContentEquals(b[1], back[1])
    }

    @Test
    fun concatAndWithChannels() {
        val a = AudioBuffer.mono(10, floatArrayOf(1f, 2f))
        val b = AudioBuffer.mono(10, floatArrayOf(3f))
        val c = a.concat(b)
        assertContentEquals(floatArrayOf(1f, 2f, 3f), c[0])
        val st = c.withChannels(2)
        assertEquals(2, st.channelCount)
        assertContentEquals(c[0], st[1])
        assertFailsWith<IllegalArgumentException> { AudioBuffer(10, arrayOf(FloatArray(2), FloatArray(3))) }
    }

    @Test
    fun peakAndRms() {
        val b = AudioBuffer.mono(10, floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f))
        assertEquals(0.5f, b.peak())
        assertEquals(0.5f, b.rms(), 1e-6f)
    }
}
