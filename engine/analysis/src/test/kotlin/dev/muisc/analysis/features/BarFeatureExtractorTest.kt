package dev.muisc.analysis.features

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.hpss.Hpss
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BarFeatureExtractorTest {
    private val sr = 44100

    @Test
    fun syntheticSong_introLessPercussiveAndQuieter_bodyHasBass_barCountMatches() {
        val song = SyntheticSong(bpm = 120.0, bars = 16, introBars = 4, outroBars = 4, sampleRate = sr, seed = 21)
        val audio = song.render()
        val grid = BeatGrid.rigid(song.bpm, sr, Math.round(song.leadingSilenceSec * sr), audio.frames.toLong())
        val f = BarFeatureExtractor().extract(audio, grid)
        assertEquals(song.bars, f.barCount)
        assertEquals(song.bars, f.percussiveness.size)
        assertEquals(song.bars, f.vocalActivity.size)
        assertEquals(song.bars, f.sub.size)

        println(String.format("%-4s %-7s %-6s %-6s %-6s %-6s %-6s %-6s", "bar", "energy", "sub", "bass", "mid", "high", "perc", "vocal"))
        for (b in 0 until f.barCount) {
            println(String.format("%-4d %-7.3f %-6.3f %-6.3f %-6.3f %-6.3f %-6.3f %-6.3f", b, f.energy[b], f.sub[b], f.bass[b], f.mid[b], f.high[b], f.percussiveness[b], f.vocalActivity[b]))
            val sum = f.sub[b] + f.bass[b] + f.mid[b] + f.high[b]
            assertTrue(abs(sum - 1f) < 1e-3f, "bar $b shares sum to $sum")
            assertTrue(f.energy[b] in 0f..1f && f.percussiveness[b] in 0f..1f && f.vocalActivity[b] in 0f..1f)
        }
        assertEquals(1f, f.energy.max(), 1e-6f)
        val intro = 0 until song.introBars
        val body = song.introBars until song.bars - song.outroBars
        val meanIntroPerc = intro.map { f.percussiveness[it] }.average()
        val meanBodyPerc = body.map { f.percussiveness[it] }.average()
        val meanIntroEnergy = intro.map { f.energy[it] }.average()
        val meanBodyEnergy = body.map { f.energy[it] }.average()
        val bodyLow = body.map { f.sub[it] + f.bass[it] }.average()
        println("percussiveness intro %.3f body %.3f; energy intro %.3f body %.3f; body sub+bass share %.3f".format(meanIntroPerc, meanBodyPerc, meanIntroEnergy, meanBodyEnergy, bodyLow))
        assertTrue(meanBodyPerc - meanIntroPerc > 0.2, "percussiveness body $meanBodyPerc vs intro $meanIntroPerc")
        assertTrue(meanIntroEnergy < meanBodyEnergy, "energy intro $meanIntroEnergy vs body $meanBodyEnergy")
        assertTrue(bodyLow > 0.25, "body sub+bass share $bodyLow")
    }

    @Test
    fun precomputedHpss_gridPartialBars_andDegenerateInput() {
        val song = SyntheticSong(bpm = 100.0, bars = 8, introBars = 2, outroBars = 2, sampleRate = sr, stereo = false, seed = 4)
        val audio = song.render()
        // Grid whose last bar is cut short by the end of the audio (drop the last 0.7 s).
        val cut = audio.slice(0, audio.frames - (0.7 * sr).toInt())
        val grid = BeatGrid.rigid(song.bpm, sr, 0L, cut.frames.toLong())
        val extractor = BarFeatureExtractor()
        val bounds = extractor.barBoundaries(grid, cut.frames.toLong())
        assertEquals(song.bars + 1, bounds.size)
        assertEquals(cut.frames.toLong(), bounds.last())
        val plain = extractor.extract(cut, grid)
        assertEquals(song.bars, plain.barCount)

        // Supplying an HpssResult computed at the engine rate gives the same percussiveness profile.
        val pre = Hpss(2048, 512).separate(cut)
        val withPre = extractor.extract(cut, grid, pre)
        assertEquals(plain.barCount, withPre.barCount)
        for (b in 0 until plain.barCount) assertTrue(abs(plain.percussiveness[b] - withPre.percussiveness[b]) < 0.15f, "bar $b: ${plain.percussiveness[b]} vs ${withPre.percussiveness[b]}")

        // Bars before the first downbeat are ignored: downbeatPhase 2 starts bar 0 on beat 2.
        val phased = BeatGrid.rigid(song.bpm, sr, 0L, cut.frames.toLong(), downbeatPhase = 2)
        val pb = extractor.barBoundaries(phased, cut.frames.toLong())
        assertEquals(phased.beatFrames[2], pb[0])

        assertEquals(0, extractor.extract(cut, BeatGrid.EMPTY).barCount)
        assertEquals(0, extractor.extract(AudioBuffer.silence(sr, 2, 0), grid).barCount)
        val silent = extractor.extract(AudioBuffer.silence(sr, 2, sr * 10), BeatGrid.rigid(120.0, sr, 0L, (sr * 10).toLong()))
        assertEquals(5, silent.barCount)
        assertTrue(silent.energy.all { it == 0f } && silent.sub.all { it == 0f } && silent.percussiveness.all { it == 0f })
    }
}
