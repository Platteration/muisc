package dev.muisc.audio

import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.Synth
import dev.muisc.audio.synth.SyntheticSong
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthTest {
    @Test
    fun clickTrackHasEnergyOnBeats() {
        val sr = 8000
        val c = Synth.clickTrack(sr, 120.0, 2.0)
        // beats at 0, 0.5, 1.0, 1.5 s: energy right after beat >> energy right before the next beat
        for (k in 0 until 4) {
            val s = (k * 0.5 * sr).toInt()
            val on = (0 until 40).sumOf { abs(c[s + it]).toDouble() }
            val off = (0 until 40).sumOf { abs(c[s + 3000 + it]).toDouble() }
            assertTrue(on > 10 * off, "beat $k: on=$on off=$off")
        }
    }

    @Test
    fun syntheticSongHasExpectedDurationAndStructure() {
        val song = SyntheticSong(bpm = 128.0, tonic = 9, mode = Mode.MINOR, bars = 8, introBars = 2, outroBars = 2, sampleRate = 8000)
        val buf = song.render()
        assertEquals(2, buf.channelCount)
        assertEquals(8000, buf.sampleRate)
        assertEquals(Math.round(song.durationSec * 8000).toInt(), buf.frames)
        assertEquals(32, song.beatTimes().size)
        assertEquals(8, song.downbeatTimes().size)
        assertTrue(buf.peak() <= 1f)
        // body is louder than intro (drums + bass present)
        val intro = buf.sliceSeconds(0.0, song.bodyStartSec).rms()
        val body = buf.sliceSeconds(song.bodyStartSec, song.outroStartSec).rms()
        assertTrue(body > intro * 1.5, "body=$body intro=$intro")
    }
}
