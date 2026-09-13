package dev.muisc.transitions.synthetic

import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.resample.Resampler
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyntheticTrackLoaderTest {
    private val song = SyntheticSong(bpm = 120.0, bars = 8, introBars = 2, outroBars = 2)
    private val prefs = TransitionPrefs()

    @Test
    fun servesExactRangesAndZeroPadsOutside() {
        val loader = SyntheticTrackLoader()
        val t = loader.register(song, id = "s1")
        assertSame(t, loader["s1"])
        val audio = t.audio
        val inside = loader.load(t.trackRef, FrameRange(1000, 5000), prefs)
        assertEquals(4000, inside.frames)
        assertEquals(2, inside.channelCount)
        assertEquals(44100, inside.sampleRate)
        for (c in 0 until 2) assertTrue(inside[c].contentEquals(audio[c].copyOfRange(1000, 5000)))

        val before = loader.load(t.trackRef, FrameRange(-100, 100), prefs)
        assertEquals(200, before.frames)
        for (i in 0 until 100) assertEquals(0f, before[0][i])
        assertTrue(before[0].copyOfRange(100, 200).contentEquals(audio[0].copyOfRange(0, 100)))

        val end = audio.frames.toLong()
        val past = loader.load(t.trackRef, FrameRange(end - 50, end + 150), prefs)
        assertEquals(200, past.frames)
        assertTrue(past[1].copyOfRange(0, 50).contentEquals(audio[1].copyOfRange(audio.frames - 50, audio.frames)))
        for (i in 50 until 200) assertEquals(0f, past[1][i])

        val nothing = loader.load(t.trackRef, FrameRange(end + 1000, end + 1000 + 64), prefs)
        assertEquals(64, nothing.frames)
        assertEquals(0f, nothing.peak())
    }

    @Test
    fun convertsToRequestedFormat() {
        val loader = SyntheticTrackLoader()
        val t = loader.register(song, id = "s1")
        val other = TransitionPrefs(sampleRate = 22050, channels = 1)
        val got = loader.load(t.trackRef, FrameRange(2000, 6000), other)
        assertEquals(22050, got.sampleRate)
        assertEquals(1, got.channelCount)
        assertEquals(4000, got.frames)
        val expected = Resampler.resample(t.audio, 22050).withChannels(1)
        assertTrue(got[0].contentEquals(expected[0].copyOfRange(2000, 6000)))
        // The conversion is cached: the same whole-track buffer backs the next request.
        assertSame(loader.audioAt(t, 22050, 1), loader.audioAt(t, 22050, 1))
        assertSame(t.audio, loader.audioAt(t, 44100, 2))
    }

    @Test
    fun resolvesBySourceAndRejectsUnknownTracks() {
        val t = SyntheticTracks.trackRef(song, id = "registered")
        val loader = SyntheticTrackLoader(listOf(t))
        val alias = TrackRef("someOtherId", t.trackRef.source, t.analysis)
        assertSame(t, loader.resolve(alias))
        val unknown = TrackRef("nope", AudioSourceId("/no/such/file.wav"), t.analysis)
        assertFailsWith<IllegalArgumentException> { loader.load(unknown, FrameRange(0, 10), prefs) }
        assertEquals(1, loader.tracks.size)
    }
}
