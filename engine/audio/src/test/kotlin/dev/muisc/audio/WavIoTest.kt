package dev.muisc.audio

import dev.muisc.audio.synth.Synth
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavIoTest {
    private fun roundTrip(enc: WavIo.Encoding, tol: Float) {
        val sr = 8000
        val l = Synth.sine(sr, 440.0, 0.1, 0.9f)
        val r = Synth.sine(sr, 220.0, 0.1, 0.4f)
        val b = AudioBuffer.stereo(sr, l, r)
        val bytes = ByteArrayOutputStream().also { WavIo.write(it, b, enc) }.toByteArray()
        val back = WavIo.read(ByteArrayInputStream(bytes))
        assertEquals(sr, back.sampleRate); assertEquals(2, back.channelCount); assertEquals(b.frames, back.frames)
        for (c in 0 until 2) for (i in 0 until b.frames) assertTrue(abs(b[c][i] - back[c][i]) <= tol, "enc=$enc c=$c i=$i ${b[c][i]} vs ${back[c][i]}")
    }

    @Test fun pcm16RoundTrip() = roundTrip(WavIo.Encoding.PCM16, 1f / 32767 + 1e-6f)
    @Test fun pcm24RoundTrip() = roundTrip(WavIo.Encoding.PCM24, 1f / 8388607 + 1e-6f)
    @Test fun float32RoundTrip() = roundTrip(WavIo.Encoding.FLOAT32, 0f)
}
