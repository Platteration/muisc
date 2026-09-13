package dev.muisc.player

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.BufferPcmStream
import dev.muisc.audio.WavIo
import dev.muisc.audio.synth.Synth
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SinkAndRingTest {
    private fun noise(sr: Int, seconds: Double, seed: Int = 5): AudioBuffer {
        val n = Math.round(seconds * sr).toInt()
        val rnd = Random(seed)
        return AudioBuffer.stereo(sr, FloatArray(n) { 0.5f * (rnd.nextFloat() * 2f - 1f) }, Synth.sine(sr, 220.0, seconds, 0.4f))
    }

    @Test
    fun wavFileSinkWritesIncrementallyAndFinalisesTheHeader() {
        val audio = noise(44100, 0.7)
        for (enc in listOf(WavIo.Encoding.FLOAT32, WavIo.Encoding.PCM16, WavIo.Encoding.PCM24)) {
            val f = File.createTempFile("muisc-sink", ".wav")
            try {
                val sink = WavFileSink(f, 44100, 2, enc)
                val block = 1000
                var pos = 0
                val inter = FloatArray(block * 2)
                while (pos < audio.frames) {
                    val n = minOf(block, audio.frames - pos)
                    val slice = audio.slice(pos, pos + n)
                    SinkPump.interleave(slice.channels, inter, n, 2)
                    sink.write(inter, n)
                    pos += n
                }
                assertEquals(audio.frames.toLong(), sink.framesWritten)
                sink.close()
                val back = WavIo.read(f)
                assertEquals(audio.frames, back.frames)
                assertEquals(2, back.channelCount)
                assertEquals(44100, back.sampleRate)
                val tol = when (enc) { WavIo.Encoding.FLOAT32 -> 0f; WavIo.Encoding.PCM16 -> 1f / 32768f; WavIo.Encoding.PCM24 -> 1f / 8388608f }
                assertTrue(PlayerFixtures.maxDiff(audio, back) <= tol, "$enc round trip")
            } finally { f.delete() }
        }
    }

    @Test
    fun capturingSinkCollectsPlanarAudio() {
        val audio = noise(44100, 0.2)
        val sink = CapturingSink(44100, 2)
        val inter = audio.interleaved()
        sink.write(inter, 3000)
        sink.write(inter.copyOfRange(6000, inter.size), audio.frames - 3000)
        assertEquals(audio.frames, sink.frames)
        assertTrue(PlayerFixtures.exactlyEqual(audio, sink.toBuffer()))
        sink.pause(); assertTrue(sink.paused); sink.resume(); assertFalse(sink.paused)
    }

    @Test
    fun syncRingReadsExactlyTheStream() {
        val audio = noise(44100, 1.0)
        val ring = PcmRing(BufferPcmStream(audio), capacityFrames = 8192, chunkFrames = 1024)
        val out = Array(2) { FloatArray(audio.frames) }
        val rnd = Random(2)
        var pos = 0
        while (true) {
            val want = rnd.nextInt(1, 3000)
            val tmp = Array(2) { FloatArray(want) }
            val n = ring.readBlocking(tmp, 0, want)
            if (n == 0) break
            for (c in 0 until 2) System.arraycopy(tmp[c], 0, out[c], pos, n)
            pos += n
        }
        assertEquals(audio.frames, pos)
        assertTrue(ring.atEnd)
        assertTrue(PlayerFixtures.exactlyEqual(audio, AudioBuffer(44100, out)))
        ring.close()
    }

    @Test
    fun ringSeeksForwardBackwardAndFromHistory() {
        val audio = noise(44100, 1.0)
        val ring = PcmRing(BufferPcmStream(audio), capacityFrames = 16384, chunkFrames = 1024)
        val tmp = Array(2) { FloatArray(4096) }
        ring.readBlocking(tmp, 0, 4096)
        assertEquals(4096L, ring.position)
        ring.seek(4000L) // within history: served from the buffer
        assertEquals(4000L, ring.position)
        ring.readBlocking(tmp, 0, 10)
        for (c in 0 until 2) assertTrue(tmp[c].copyOf(10).contentEquals(audio[c].copyOfRange(4000, 4010)))
        ring.seek(30000L) // far ahead: stream seek
        assertEquals(30000L, ring.position)
        ring.readBlocking(tmp, 0, 100)
        for (c in 0 until 2) assertTrue(tmp[c].copyOf(100).contentEquals(audio[c].copyOfRange(30000, 30100)))
        ring.ensureAvailable(2000)
        ring.seek(31000L) // within the unread data: no stream seek
        ring.readBlocking(tmp, 0, 100)
        for (c in 0 until 2) assertTrue(tmp[c].copyOf(100).contentEquals(audio[c].copyOfRange(31000, 31100)))
        // Peek does not advance, skip does.
        assertEquals(31100L, ring.position)
        ring.peekBlocking(tmp, 0, 50)
        assertEquals(31100L, ring.position)
        assertEquals(50, ring.skip(50))
        assertEquals(31150L, ring.position)
        ring.close()
    }

    @Test
    fun threadedProducerDeliversTheStreamInOrder() {
        val audio = noise(44100, 3.0)
        val ring = PcmRing(BufferPcmStream(audio), capacityFrames = 8192, chunkFrames = 1024).start("test-producer")
        assertTrue(ring.threaded)
        val out = Array(2) { FloatArray(audio.frames) }
        var pos = 0
        val tmp = Array(2) { FloatArray(1024) }
        val deadline = System.nanoTime() + 20_000_000_000L
        while (pos < audio.frames && System.nanoTime() < deadline) {
            val n = ring.readBlocking(tmp, 0, 1024)
            if (n == 0) { if (ring.atEnd) break else continue }
            for (c in 0 until 2) System.arraycopy(tmp[c], 0, out[c], pos, n)
            pos += n
        }
        assertEquals(audio.frames, pos)
        assertTrue(PlayerFixtures.exactlyEqual(audio, AudioBuffer(44100, out)))
        ring.close()
        assertTrue(ring.atEnd)
    }

    @Test
    fun seamFaderIsANoOpOnIdenticalSignalsAndBlendsOtherwise() {
        val fader = SeamFader(240, 2)
        val sig = noise(44100, 0.05)
        val head = sig.copy()
        fader.retain(sig.channels, 0, 240)
        assertTrue(fader.active)
        assertEquals(240, fader.apply(head.channels, 0, 1000))
        assertFalse(fader.active)
        assertTrue(PlayerFixtures.exactlyEqual(sig, head), "identical tail and head must come out bit-identical")
        // Different signals: prev at the first frame, next at the last, linear in between.
        val zeros = AudioBuffer.silence(44100, 2, 240)
        fader.retain(sig.channels, 0, 240)
        fader.apply(zeros.channels, 0, 240)
        assertEquals(sig[0][0] * (1f - 1f / 241f), zeros[0][0], 1e-6f)
        assertEquals(sig[0][239] * (1f - 240f / 241f), zeros[0][239], 1e-6f)
        assertTrue(zeros[0][100] != 0f)
    }
}
