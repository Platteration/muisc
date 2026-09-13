package dev.muisc.transitions.synthetic

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.AudioDecodeException
import dev.muisc.audio.AudioDecoder
import dev.muisc.audio.AudioFormatInfo
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.BufferPcmStream
import dev.muisc.audio.PcmStream
import dev.muisc.audio.WavIo
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.resample.Resampler
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmTrackAudioLoaderTest {
    /** In-memory decoder that counts how often a source is opened. */
    private class MemoryDecoder(private val sources: Map<String, AudioBuffer>) : AudioDecoder {
        var opens = 0
        var closes = 0
        override fun canDecode(source: AudioSourceId) = source.value in sources
        override fun open(source: AudioSourceId): PcmStream {
            val buf = sources[source.value] ?: throw AudioDecodeException("no such source $source")
            opens++
            val inner = BufferPcmStream(buf)
            return object : PcmStream by inner {
                override fun close() { closes++; inner.close() }
            }
        }
        override fun probe(source: AudioSourceId): AudioFormatInfo {
            val b = sources[source.value] ?: throw AudioDecodeException("no such source $source")
            return AudioFormatInfo(b.sampleRate, b.channelCount, b.frames.toLong(), "PCM_FLOAT")
        }
    }

    private val song44 = SyntheticSong(bpm = 128.0, bars = 4, introBars = 1, outroBars = 1, sampleRate = 44100)
    private val song48 = SyntheticSong(bpm = 128.0, bars = 4, introBars = 1, outroBars = 1, sampleRate = 48000)
    private fun ref(id: String, source: String, song: SyntheticSong) = TrackRef(id, AudioSourceId(source), SyntheticTracks.analysis(song))

    @Test
    fun readsRangesSequentiallyFromOneStream() {
        val audio = song44.render()
        val decoder = MemoryDecoder(mapOf("mem:a" to audio))
        val loader = JvmTrackAudioLoader(decoder)
        val track = ref("a", "mem:a", song44)
        val prefs = TransitionPrefs()

        val r1 = loader.load(track, FrameRange(0, 4096), prefs)
        val r2 = loader.load(track, FrameRange(4096, 8192), prefs)
        val r3 = loader.load(track, FrameRange(1000, 2000), prefs) // backwards: seek, no re-open
        assertEquals(1, decoder.opens)
        assertEquals(1, loader.openStreamCount)
        assertTrue(r1[0].contentEquals(audio[0].copyOfRange(0, 4096)))
        assertTrue(r2[1].contentEquals(audio[1].copyOfRange(4096, 8192)))
        assertTrue(r3[0].contentEquals(audio[0].copyOfRange(1000, 2000)))

        // Zero padding on both sides.
        val end = audio.frames.toLong()
        val tail = loader.load(track, FrameRange(end - 10, end + 20), prefs)
        assertEquals(30, tail.frames)
        assertTrue(tail[0].copyOfRange(0, 10).contentEquals(audio[0].copyOfRange(audio.frames - 10, audio.frames)))
        for (i in 10 until 30) assertEquals(0f, tail[0][i])
        val head = loader.load(track, FrameRange(-5, 5), prefs)
        for (i in 0 until 5) assertEquals(0f, head[0][i])
        assertTrue(head[0].copyOfRange(5, 10).contentEquals(audio[0].copyOfRange(0, 5)))
        val beyond = loader.load(track, FrameRange(end + 100, end + 200), prefs)
        assertEquals(100, beyond.frames)
        assertEquals(0f, beyond.peak())

        loader.release("a")
        assertEquals(1, decoder.closes)
        assertEquals(0, loader.openStreamCount)
        loader.load(track, FrameRange(0, 16), prefs)
        assertEquals(2, decoder.opens)
        loader.close()
        assertEquals(2, decoder.closes)
    }

    @Test
    fun resamplingIsPositionDeterministic() {
        val native = song48.render()
        val decoder = MemoryDecoder(mapOf("mem:b" to native))
        val loader = JvmTrackAudioLoader(decoder)
        val track = ref("b", "mem:b", song48)
        val prefs = TransitionPrefs(sampleRate = 44100, channels = 2)
        val whole = Resampler.resample(native, 44100)

        // Two overlapping windows and one at the very start: every engine frame must match the whole-file conversion.
        for (range in listOf(FrameRange(0, 3000), FrameRange(20000, 30000), FrameRange(25000, 27000))) {
            val got = loader.load(track, range, prefs)
            assertEquals(range.length, got.frames)
            assertEquals(44100, got.sampleRate)
            var maxErr = 0f
            for (c in 0 until 2) for (i in 0 until got.frames) maxErr = maxOf(maxErr, abs(got[c][i] - whole[c][range.start.toInt() + i]))
            assertTrue(maxErr < 1e-5f, "range $range differs from whole-file resample by $maxErr")
        }
        assertEquals(1, decoder.opens)
        // Past the end: exactly range.length frames, silence after the (resampled) end of the file.
        val end = whole.frames.toLong()
        val tail = loader.load(track, FrameRange(end - 100, end + 100), prefs)
        assertEquals(200, tail.frames)
        for (i in 150 until 200) assertEquals(0f, tail[0][i])
        assertTrue(tail.peak() > 0f)
    }

    @Test
    fun loadsRealWavFilesAndAdaptsChannels(@TempDir dir: File) {
        val stereo = song44.render()
        val mono = stereo.withChannels(1)
        val stereoFile = File(dir, "stereo.wav"); WavIo.write(stereoFile, stereo, WavIo.Encoding.PCM16)
        val monoFile = File(dir, "mono.wav"); WavIo.write(monoFile, mono, WavIo.Encoding.PCM16)
        val loader = JvmTrackAudioLoader()
        val prefs = TransitionPrefs()

        val s = loader.load(ref("st", stereoFile.path, song44), FrameRange(10000, 12000), prefs)
        assertEquals(2000, s.frames); assertEquals(2, s.channelCount); assertEquals(44100, s.sampleRate)
        var maxErr = 0f
        for (c in 0 until 2) for (i in 0 until 2000) maxErr = maxOf(maxErr, abs(s[c][i] - stereo[c][10000 + i]))
        assertTrue(maxErr < 2e-4f, "16-bit round trip error $maxErr")

        val m = loader.load(ref("mo", monoFile.path, song44), FrameRange(10000, 12000), prefs)
        assertEquals(2, m.channelCount)
        assertTrue(m[0].contentEquals(m[1]))
        maxErr = 0f
        for (i in 0 until 2000) maxErr = maxOf(maxErr, abs(m[0][i] - mono[0][10000 + i]))
        assertTrue(maxErr < 2e-4f, "mono round trip error $maxErr")

        val m48 = loader.load(ref("mo", monoFile.path, song44), FrameRange(0, 48000), TransitionPrefs(sampleRate = 48000, channels = 1))
        assertEquals(48000, m48.frames); assertEquals(1, m48.channelCount); assertEquals(48000, m48.sampleRate)
        val expected = Resampler.resample(mono, 48000)
        maxErr = 0f
        for (i in 0 until 48000) maxErr = maxOf(maxErr, abs(m48[0][i] - expected[0][i]))
        assertTrue(maxErr < 3e-4f, "resampled file error $maxErr")
        assertEquals(2, loader.openStreamCount)
        loader.close()
        assertEquals(0, loader.openStreamCount)
    }
}
