package dev.muisc.analysis.features

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.SyntheticSong
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilenceTrimmerTest {
    private val sr = 44100

    @Test
    fun leadingAndTrailingSilence_trimmedWithin30ms_hardStopDetected() {
        val song = SyntheticSong(bpm = 120.0, bars = 12, introBars = 2, outroBars = 2, outroFade = false, sampleRate = sr, leadingSilenceSec = 1.5, trailingSilenceSec = 2.0)
        val audio = song.render()
        val info = SilenceTrimmer().analyze(audio)
        val startSec = info.trimStartFrame.toDouble() / sr
        val endSec = info.trimEndFrame.toDouble() / sr
        println("hard stop song: trim %.3f..%.3f s (truth %.3f..%.3f), hardStop=%s (delta %.1f dB), fade=%s (slope %.2f dB/s, range %.1f dB)".format(
            startSec, endSec, song.leadingSilenceSec, song.musicEndSec, info.hardStop, info.endLevelDeltaDb, info.fadeOut, info.fadeSlopeDbPerSec, info.fadeRangeDb))
        assertFalse(info.silent)
        assertTrue(abs(startSec - song.leadingSilenceSec) <= 0.030, "trim start $startSec")
        assertTrue(abs(endSec - song.musicEndSec) <= 0.030, "trim end $endSec")
        assertTrue(info.hardStop, "hard stop expected (delta ${info.endLevelDeltaDb} dB)")
        assertFalse(info.fadeOut, "no fade expected")
        assertEquals(-1L, info.fadeStartFrame)

        // A true cut (no release at all) reads within ~2 dB of the running level.
        val cut = audio.slice(0, (song.outroStartSec * sr).toInt() + sr) // cut 1 s into the outro, mid-bar
        val c = SilenceTrimmer().analyze(cut)
        println("mid-bar cut: hardStop=%s delta %.1f dB".format(c.hardStop, c.endLevelDeltaDb))
        assertTrue(c.hardStop && c.endLevelDeltaDb > -3f, "mid-bar cut delta ${c.endLevelDeltaDb}")
        assertTrue(abs(c.trimEndFrame - cut.frames) <= 0.011 * sr)
    }

    @Test
    fun fadedOutro_fadeDetectedWithStartNearOutro() {
        val song = SyntheticSong(bpm = 120.0, bars = 16, introBars = 2, outroBars = 4, outroFade = true, sampleRate = sr, leadingSilenceSec = 0.5, trailingSilenceSec = 1.0)
        val audio = song.render()
        val info = SilenceTrimmer().analyze(audio)
        val endSec = info.trimEndFrame.toDouble() / sr
        val fadeStartSec = info.fadeStartFrame.toDouble() / sr
        println("fade song: trim end %.3f s (music end %.3f), fade=%s start %.2f s (outro starts %.2f), slope %.2f dB/s range %.1f dB, hardStop=%s (delta %.1f dB)".format(
            endSec, song.musicEndSec, info.fadeOut, fadeStartSec, song.outroStartSec, info.fadeSlopeDbPerSec, info.fadeRangeDb, info.hardStop, info.endLevelDeltaDb))
        assertTrue(info.fadeOut, "fade expected (slope ${info.fadeSlopeDbPerSec}, range ${info.fadeRangeDb})")
        assertFalse(info.hardStop, "no hard stop expected (delta ${info.endLevelDeltaDb} dB)")
        assertTrue(endSec <= song.musicEndSec + 0.011 && endSec >= song.musicEndSec - 0.3, "trim end $endSec")
        assertTrue(fadeStartSec >= song.outroStartSec - 1.0 && fadeStartSec <= song.outroStartSec + 4.0, "fade start $fadeStartSec")
        assertTrue(abs(info.trimStartFrame.toDouble() / sr - 0.5) <= 0.030)
    }

    @Test
    fun silenceBlipsAndDegenerateInput_neverThrow() {
        val trimmer = SilenceTrimmer()
        val silent = trimmer.analyze(AudioBuffer.silence(sr, 2, sr * 3))
        assertTrue(silent.silent)
        assertEquals(0L, silent.trimStartFrame); assertEquals(0L, silent.trimEndFrame)
        assertFalse(silent.hardStop); assertFalse(silent.fadeOut)

        val empty = trimmer.analyze(AudioBuffer.silence(sr, 1, 0))
        assertTrue(empty.silent)

        // A 20 ms blip inside 3 s of silence is ignored.
        val blip = FloatArray(sr * 3)
        for (i in sr until sr + (0.02 * sr).toInt()) blip[i] = if (i % 2 == 0) 0.5f else -0.5f
        val b = trimmer.analyze(AudioBuffer.mono(sr, blip))
        assertTrue(b.silent, "blip should be ignored")

        // A 200 ms tone is kept, positions within one window.
        val tone = FloatArray(sr * 3)
        for (i in sr until sr + (0.2 * sr).toInt()) tone[i] = if (i % 2 == 0) 0.5f else -0.5f
        val t = trimmer.analyze(AudioBuffer.mono(sr, tone))
        assertFalse(t.silent)
        assertTrue(abs(t.trimStartFrame - sr) <= 0.011 * sr, "start ${t.trimStartFrame}")
        assertTrue(abs(t.trimEndFrame - (sr + 0.2 * sr)) <= 0.011 * sr, "end ${t.trimEndFrame}")

        // Very quiet content below -60 dBFS is silence.
        val quiet = FloatArray(sr) { if (it % 2 == 0) 0.0005f else -0.0005f }
        assertTrue(trimmer.analyze(AudioBuffer.mono(sr, quiet)).silent)
    }
}
