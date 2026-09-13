package dev.muisc.player

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.BufferPcmStream
import dev.muisc.audio.WavIo
import dev.muisc.audio.readAll
import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.resample.Resampler
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResamplingPcmStreamTest {
    private fun material(sr: Int, seconds: Double, seed: Int = 3): AudioBuffer {
        val n = Math.round(seconds * sr).toInt()
        val rnd = Random(seed)
        val l = Synth.sine(sr, 441.0, seconds, 0.4f)
        val r = FloatArray(n) { 0.3f * (rnd.nextFloat() * 2f - 1f) + l[it] * 0.5f }
        return AudioBuffer.stereo(sr, l, r)
    }

    private fun readAllFrom(s: ResamplingPcmStream, chunk: Int = 1000): AudioBuffer {
        val chunks = ArrayList<Array<FloatArray>>()
        val tmp = Array(s.channelCount) { FloatArray(chunk) }
        var total = 0
        while (true) {
            val n = s.read(tmp, 0, chunk)
            if (n <= 0) break
            chunks += Array(s.channelCount) { tmp[it].copyOf(n) }
            total += n
        }
        val out = Array(s.channelCount) { FloatArray(total) }
        var pos = 0
        for (c in chunks) { for (ch in 0 until s.channelCount) System.arraycopy(c[ch], 0, out[ch], pos, c[0].size); pos += c[0].size }
        return AudioBuffer(s.sampleRate, out)
    }

    @Test
    fun seekingAnywhereReproducesTheSequentialRead() {
        val src = material(48000, 2.0)
        val sequential = readAllFrom(ResamplingPcmStream(BufferPcmStream(src), 44100), chunk = 777)
        assertEquals(Math.round(src.frames * (44100 / 48000.0)).toInt(), sequential.frames)
        val rnd = Random(11)
        val s2 = ResamplingPcmStream(BufferPcmStream(src), 44100)
        val tmp = Array(2) { FloatArray(5000) }
        repeat(60) {
            val pos = rnd.nextInt(0, sequential.frames)
            val n = rnd.nextInt(1, 5000)
            s2.seek(pos.toLong())
            assertEquals(pos.toLong(), s2.position)
            val got = s2.read(tmp, 0, n)
            assertEquals(minOf(n, sequential.frames - pos), got)
            for (c in 0 until 2) for (i in 0 until got) {
                assertEquals(sequential[c][pos + i], tmp[c][i], 0f, "frame ${pos + i} ch $c after seek($pos) differs from the sequential read")
            }
        }
        // A fresh instance positioned directly at an arbitrary frame produces the same samples too.
        val s3 = ResamplingPcmStream(BufferPcmStream(src), 44100)
        s3.seek(31_337L)
        val got = s3.read(tmp, 0, 4000)
        assertEquals(4000, got)
        for (c in 0 until 2) assertTrue(tmp[c].copyOf(4000).contentEquals(sequential[c].copyOfRange(31_337, 35_337)))
    }

    @Test
    fun matchesTheOneShotResamplerAndScalesTotalFrames() {
        val src = material(22050, 1.5)
        val s = ResamplingPcmStream(BufferPcmStream(src), 44100)
        assertEquals(Math.round(src.frames * 2.0), s.totalFrames)
        val got = readAllFrom(s)
        val ref = Resampler(SincKernelSpec.DEFAULT.kernel()).resample(src, 44100)
        assertEquals(ref.frames, got.frames)
        var worst = 0f
        for (c in 0 until 2) for (i in 100 until got.frames - 100) worst = maxOf(worst, abs(got[c][i] - ref[c][i]))
        assertTrue(worst < 1e-6f, "streaming vs one-shot: max diff $worst")
        // Down-sampling as well.
        val down = readAllFrom(ResamplingPcmStream(BufferPcmStream(material(48000, 1.0)), 32000))
        assertEquals(32000, down.frames)
        assertTrue(down.peak() > 0.3f && down.peak() < 1f)
    }

    @Test
    fun identityWhenRatesMatch() {
        val src = material(44100, 0.5)
        val s = ResamplingPcmStream(BufferPcmStream(src), 44100)
        assertTrue(s.identity)
        assertEquals(src.frames.toLong(), s.totalFrames)
        val got = readAllFrom(s)
        assertTrue(PlayerFixtures.exactlyEqual(src, got))
        s.seek(100L)
        assertEquals(100L, s.position)
    }

    @Test
    fun channelAdapterDuplicatesAndAverages() {
        val mono = AudioBuffer.mono(44100, Synth.sine(44100, 200.0, 0.2, 0.5f))
        val stereo = ChannelAdaptingPcmStream(BufferPcmStream(mono), 2).readAll()
        assertEquals(2, stereo.channelCount)
        assertTrue(stereo[0].contentEquals(mono[0]) && stereo[1].contentEquals(mono[0]))
        val src = material(44100, 0.2)
        val down = ChannelAdaptingPcmStream(BufferPcmStream(src), 1).readAll()
        assertEquals(1, down.channelCount)
        for (i in 0 until src.frames) assertEquals(src[0][i] * 0.5f + src[1][i] * 0.5f, down[0][i], 1e-7f)
    }

    @Test
    fun jvmFactoryOpensAtTheEngineFormat() {
        val tmp = File.createTempFile("muisc-stream", ".wav")
        try {
            val mono = AudioBuffer.mono(22050, Synth.sine(22050, 300.0, 0.25, 0.5f))
            WavIo.write(tmp, mono, WavIo.Encoding.FLOAT32)
            val s = JvmEngineStreamFactory().open(AudioSourceId(tmp.path), 44100, 2)
            assertEquals(44100, s.sampleRate)
            assertEquals(2, s.channelCount)
            assertEquals(mono.frames * 2L, s.totalFrames)
            val all = s.readAll()
            assertEquals(mono.frames * 2, all.frames)
            assertTrue(all[0].contentEquals(all[1]))
            assertTrue(all.peak() > 0.45f && all.peak() < 0.55f)
            s.close()
        } finally { tmp.delete() }
    }

    @Test
    fun memoryFactoryAdaptsAndIsFreshPerOpen() {
        val src = material(44100, 0.3)
        val f = MemoryEngineStreamFactory().register(AudioSourceId("x"), src)
        val a = f.open(AudioSourceId("x"), 44100, 2)
        val b = f.open(AudioSourceId("x"), 44100, 2)
        val tmp = Array(2) { FloatArray(100) }
        a.read(tmp, 0, 100)
        assertEquals(100L, a.position)
        assertEquals(0L, b.position)
        val m = f.open(AudioSourceId("x"), 22050, 1)
        assertEquals(1, m.channelCount); assertEquals(22050, m.sampleRate)
        assertEquals(Math.round(src.frames / 2.0), m.totalFrames)
    }
}
