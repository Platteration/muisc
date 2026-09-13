package dev.muisc.audio

import dev.muisc.audio.jvm.JavaSoundDecoder
import dev.muisc.audio.synth.Synth
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaSoundDecoderTest {
    @Test
    fun decodesWavWithSeekAndRange() {
        val sr = 8000
        val l = Synth.sine(sr, 440.0, 1.0, 0.5f)
        val r = Synth.sine(sr, 110.0, 1.0, 0.5f)
        val src = AudioBuffer.stereo(sr, l, r)
        val f = File.createTempFile("muisc", ".wav").also { it.deleteOnExit() }
        WavIo.write(f, src, WavIo.Encoding.PCM16)

        val dec = JavaSoundDecoder()
        val id = AudioSourceId(f.absolutePath)
        assertTrue(dec.canDecode(id))
        val info = dec.probe(id)
        assertEquals(sr, info.sampleRate); assertEquals(2, info.channelCount); assertEquals(src.frames.toLong(), info.totalFrames)

        dec.open(id).use { s ->
            val a = s.readRange(4000, 1000)
            for (i in 0 until 1000) assertTrue(abs(a[0][i] - src[0][4000 + i]) < 2e-4f)
            val b = s.readRange(100, 50) // backwards seek → reopen
            for (i in 0 until 50) assertTrue(abs(b[1][i] - src[1][100 + i]) < 2e-4f)
            val tail = s.readRange(src.frames - 10L, 20)
            assertEquals(20, tail.frames)
            assertEquals(0f, tail[0][15])
        }
    }
}
