package dev.muisc.dsp.fx

import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.qa.ArtifactDetector
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopRollTest {
    private val sr = 44100

    @Test
    fun lengthIsSumOfLoopsAndSegmentsMatchSource() {
        val spec = SyntheticSong(bpm = 124.0, bars = 4, introBars = 0, outroBars = 0)
        val song = spec.render()
        val beat = spec.beatSec * sr
        val lengths = LoopRoll.sequenceFromBeat(beat, spec.beatsPerBar, steps = 5)
        val loopStart = Math.round(spec.barSec * sr).toInt() // downbeat of bar 2
        val roll = LoopRoll(crossfadeMs = 3.0).render(song, loopStart, lengths)
        assertEquals(lengths.sum(), roll.frames)
        assertEquals(2, roll.channelCount)
        val xf = (0.003 * sr).toInt()
        var o = 0
        for ((i, len) in lengths.withIndex()) {
            // Interior of every segment (outside the crossfade zones) is the source from loopStart.
            val from = if (i == 0) 0 else xf
            val to = if (i == lengths.size - 1) len else len - xf
            for (c in 0 until 2) for (j in from until to) {
                assertEquals(song[c][loopStart + j], roll[c][o + j], 0f, "segment $i ch$c sample $j")
            }
            o += len
        }
    }

    @Test
    fun rollIsClickFree() {
        val spec = SyntheticSong(bpm = 128.0, bars = 4, introBars = 0, outroBars = 0, stereo = false)
        val song = spec.render()
        val beat = spec.beatSec * sr
        val lengths = LoopRoll.sequenceFromBeat(beat, 4, steps = 5, fillBar = true)
        val loopStart = Math.round(spec.barSec * sr).toInt()
        val roll = LoopRoll(crossfadeMs = 4.0).render(song, loopStart, lengths)
        assertEquals(lengths.sum(), roll.frames)
        val report = ArtifactDetector(sr).analyze(roll)
        assertTrue(report.clicks.isEmpty(), report.summary())
        // The crossfade must not move the grid: the downbeat kick of every loop sits right at the loop start.
        var o = 0
        for (len in lengths) {
            var e = 0.0
            for (j in 0 until 400) e += roll[0][o + j] * roll[0][o + j]
            assertTrue(e > 1.0, "energy at loop start $o: $e")
            o += len
        }
        // Zero-length crossfade degenerates to plain concatenation of the same length.
        val raw = LoopRoll(crossfadeMs = 0.0).render(song, loopStart, lengths)
        assertEquals(roll.frames, raw.frames)
    }

    @Test
    fun sequenceFromBeatHalvesAndFills() {
        val beat = 22050.0 // 120 bpm
        val plain = LoopRoll.sequenceFromBeat(beat, 4, steps = 5)
        assertContentEquals(intArrayOf(88200, 44100, 22050, 11025, 5513), plain)
        val filled = LoopRoll.sequenceFromBeat(beat, 4, steps = 3, fillBar = true)
        assertContentEquals(intArrayOf(88200, 44100, 44100, 22050, 22050, 22050, 22050), filled)
        val fractions = LoopRoll.lengthsFromBeatFractions(beat, doubleArrayOf(1.0, 0.5, 0.25))
        assertContentEquals(intArrayOf(22050, 11025, 5513), fractions)
    }
}
