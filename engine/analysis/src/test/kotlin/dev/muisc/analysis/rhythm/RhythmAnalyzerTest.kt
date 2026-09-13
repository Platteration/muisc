package dev.muisc.analysis.rhythm

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.GridKind
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.Synth
import dev.muisc.audio.synth.SyntheticSong
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests against the synthetic ground truth (44.1 kHz engine rate, so the 22.05 kHz resample path is
 * exercised). Prints an accuracy table to stdout.
 */
class RhythmAnalyzerTest {
    private val sr = 44100

    private fun beatSeconds(grid: BeatGrid): DoubleArray = DoubleArray(grid.beatCount) { grid.beatFrames[it].toDouble() / sr }

    /** Fraction of [truth] beats that have an estimated beat within [tolSec]. */
    private fun hitRate(est: DoubleArray, truth: List<Double>, tolSec: Double): Double {
        if (truth.isEmpty()) return 0.0
        var hits = 0
        for (t in truth) if (est.any { abs(it - t) <= tolSec }) hits++
        return hits.toDouble() / truth.size
    }

    private fun downbeatsCorrect(grid: BeatGrid, est: DoubleArray, downbeats: List<Double>): Int {
        var ok = 0
        for (d in downbeats) {
            var best = 0
            for (i in est.indices) if (abs(est[i] - d) < abs(est[best] - d)) best = i
            if (abs(est[best] - d) <= 0.025 && grid.isDownbeat(best)) ok++
        }
        return ok
    }

    @Test
    fun clickTrackAt120BpmWithOffset_bpmWithin0_2_everyBeatWithin10ms_phaseCorrect() {
        val offset = 0.35
        val x = Synth.clickTrack(sr, 120.0, 30.0, offsetSec = offset)
        val r = RhythmAnalyzer().analyze(AudioBuffer.mono(sr, x))
        val g = r.grid
        assertEquals(GridKind.RIGID, g.kind)
        assertEquals(120.0, r.tempo.bpm, 0.2, "tempo bpm")
        assertEquals(120.0, g.bpm, 0.2, "grid bpm")
        assertTrue(r.tempo.confidence > 0.5f, "tempo confidence ${r.tempo.confidence}")
        assertTrue(g.confidence > 0.5f, "grid confidence ${g.confidence}")
        val est = beatSeconds(g)
        assertTrue(est.size >= 58, "expected ~60 beats, got ${est.size}")
        var worst = 0.0
        for (t in est) {
            val k = Math.round((t - offset) / 0.5)
            val err = abs(t - (offset + k * 0.5))
            if (err > worst) worst = err
        }
        assertTrue(worst <= 0.010, "worst beat error ${worst * 1000} ms")
        // every click has a beat
        val clicks = generateSequence(0) { it + 1 }.map { offset + it * 0.5 }.takeWhile { it < 30.0 }.toList()
        assertEquals(60, clicks.size)
        assertEquals(1.0, hitRate(est, clicks, 0.010), 1e-9)
        // downbeats: clicks 0, 4, 8 ... are the loud ones
        val downbeats = (0 until 15).map { offset + it * 2.0 }
        assertEquals(downbeats.size, downbeatsCorrect(g, est, downbeats), "downbeat phase")
        // alternates list half and double tempo
        assertTrue(r.tempo.alternates.any { abs(it.bpm - 60.0) < 1.0 })
        assertTrue(r.tempo.alternates.any { abs(it.bpm - 240.0) < 1.0 })
        // onsets: one per click, within 8 ms
        val onsets = r.onsetTimesSec
        assertEquals(clicks.size, onsets.size, "onset count")
        for ((i, c) in clicks.withIndex()) assertTrue(abs(onsets[i] - c) <= 0.008, "onset $i at ${onsets[i]} vs click $c")
        assertEquals(256.0 / 22050, r.odfHopSec, 1e-12)
        println("click 120 BPM: est=%.3f BPM, worst beat error %.2f ms, phase=%d, conf=%.2f".format(g.bpm, worst * 1000, g.downbeatPhase, g.confidence))
    }

    @Test
    fun syntheticSongs_tempoWithin1pct_beatsWithin25ms_downbeatsCorrectFor5of6_gridRigid() {
        val bpms = doubleArrayOf(88.0, 100.0, 120.0, 128.0, 140.0, 172.0)
        val table = StringBuilder()
        table.append(String.format("%-8s %-10s %-8s %-8s %-8s %-9s %-6s %-7s %-6s%n", "true", "est bpm", "err %", "kind", "hits", "meanErr", "phase", "dbConf", "conf"))
        var phaseOkSongs = 0
        for ((i, bpm) in bpms.withIndex()) {
            val song = SyntheticSong(
                bpm = bpm, bars = 32, introBars = 2, outroBars = 2, seed = 11 + i,
                tonic = (i * 5) % 12, mode = if (i % 2 == 0) Mode.MAJOR else Mode.MINOR, sampleRate = sr,
            )
            val audio = song.render()
            val r = RhythmAnalyzer().analyze(audio)
            val g = r.grid
            val est = beatSeconds(g)
            val truth = song.beatTimes().filter { it >= song.bodyStartSec && it < song.outroStartSec }
            val hits = hitRate(est, truth, 0.025)
            val meanErr = truth.sumOf { t -> est.minOf { abs(it - t) } } / truth.size * 1000
            val downbeats = song.downbeatTimes().filter { it >= song.bodyStartSec && it < song.outroStartSec }
            val dbOk = downbeatsCorrect(g, est, downbeats)
            val phaseOk = dbOk == downbeats.size
            if (phaseOk) phaseOkSongs++
            val errPct = abs(r.tempo.bpm - bpm) / bpm * 100
            table.append(String.format("%-8.0f %-10.3f %-8.3f %-8s %-8.3f %-9.2f %-6s %-7.2f %-6.2f%n", bpm, r.tempo.bpm, errPct, g.kind, hits, meanErr, "$dbOk/${downbeats.size}", r.downbeatConfidence, g.confidence))

            // Tempo: within 1 %, or an octave alternate is listed and the chosen bpm is within 1 % of half / double.
            val within1 = errPct <= 1.0
            val octave = (abs(r.tempo.bpm - 2 * bpm) / (2 * bpm) <= 0.01 && r.tempo.alternates.any { abs(it.bpm - bpm) / bpm <= 0.01 }) ||
                (abs(r.tempo.bpm - bpm / 2) / (bpm / 2) <= 0.01 && r.tempo.alternates.any { abs(it.bpm - bpm) / bpm <= 0.01 })
            assertTrue(within1 || octave, "song $bpm: estimated ${r.tempo.bpm} (alternates ${r.tempo.alternates})")
            assertTrue(hits >= 0.9, "song $bpm: beat hit rate $hits")
            assertEquals(GridKind.RIGID, g.kind, "song $bpm: grid kind (residual ${r.gridResidualMs} ms)")
            assertTrue(g.confidence > 0.5f, "song $bpm: confidence ${g.confidence}")
            assertTrue(g.phraseStartBeat == -1 || Math.floorMod(g.phraseStartBeat - g.downbeatPhase, 4) == 0, "phrase start must be a downbeat")
        }
        println("Synthetic songs (32 bars, intro 2, outro 2; body evaluated; hits = beats within 25 ms):")
        println(table)
        println("downbeat phase correct for $phaseOkSongs of 6 songs")
        assertTrue(phaseOkSongs >= 5, "downbeat phase correct for only $phaseOkSongs of 6 songs")
    }

    @Test
    fun silenceAndShortNoise_returnLowConfidenceWithoutThrowing() {
        val silence = RhythmAnalyzer().analyze(AudioBuffer.silence(sr, 2, 10 * sr))
        assertTrue(silence.grid.isEmpty)
        assertEquals(0f, silence.grid.confidence)
        assertEquals(0f, silence.tempo.confidence)
        assertTrue(silence.onsetTimesSec.isEmpty())
        assertTrue(silence.odf.all { it == 0f })

        val noise = RhythmAnalyzer().analyze(AudioBuffer.mono(sr, Synth.whiteNoise(sr, 0.5, seed = 3)))
        assertTrue(noise.grid.confidence < 0.2f, "noise grid confidence ${noise.grid.confidence}")
        assertTrue(noise.tempo.confidence < 0.2f, "noise tempo confidence ${noise.tempo.confidence}")

        // degenerate inputs
        val empty = RhythmAnalyzer().analyze(AudioBuffer.silence(sr, 1, 0))
        assertTrue(empty.grid.isEmpty)
        val tiny = RhythmAnalyzer().analyze(AudioBuffer.mono(sr, FloatArray(100) { 0.5f }))
        assertTrue(tiny.grid.isEmpty)
        val trimmedAway = RhythmAnalyzer().analyze(AudioBuffer.silence(sr, 1, sr), trimStartFrame = 1000, trimEndFrame = 500)
        assertTrue(trimmedAway.grid.isEmpty)

        // longer noise: still a low-confidence grid, and no exception
        val longNoise = RhythmAnalyzer().analyze(AudioBuffer.mono(sr, Synth.whiteNoise(sr, 20.0, seed = 5)))
        println("20 s white noise: tempo conf=%.2f grid conf=%.2f kind=%s".format(longNoise.tempo.confidence, longNoise.grid.confidence, longNoise.grid.kind))
        assertTrue(longNoise.grid.confidence < 0.5f, "long noise confidence ${longNoise.grid.confidence}")
    }

    @Test
    fun determinism_sameInputTwiceGivesIdenticalResult() {
        val song = SyntheticSong(bpm = 124.0, bars = 16, introBars = 1, outroBars = 1, seed = 42, sampleRate = sr)
        val audio = song.render()
        val a = RhythmAnalyzer().analyze(audio)
        val b = RhythmAnalyzer().analyze(audio.copy())
        assertEquals(a.grid, b.grid)
        assertEquals(a.tempo, b.tempo)
        assertTrue(a.odf.contentEquals(b.odf))
        assertTrue(a.onsetTimesSec.contentEquals(b.onsetTimesSec))
    }

    @Test
    fun trimmedRegion_positionsAreInWholeBufferCoordinates() {
        val lead = 3.0
        val clicks = Synth.clickTrack(sr, 100.0, 20.0, offsetSec = 0.2)
        val x = FloatArray(Math.round(lead * sr).toInt()) + clicks
        val trimStart = Math.round(lead * sr)
        val r = RhythmAnalyzer().analyze(AudioBuffer.mono(sr, x), trimStartFrame = trimStart, trimEndFrame = x.size.toLong())
        assertEquals(GridKind.RIGID, r.grid.kind)
        assertEquals(100.0, r.grid.bpm, 0.2)
        val est = beatSeconds(r.grid)
        assertTrue(est.first() >= lead, "first beat ${est.first()} must be after the trim start")
        for (t in est) {
            val k = Math.round((t - lead - 0.2) / 0.6)
            assertTrue(abs(t - (lead + 0.2 + k * 0.6)) <= 0.010, "beat at $t")
        }
        assertTrue(r.grid.beatFrames.last() <= x.size.toLong())
        // ODF timeline starts at track time 0 and the onsets are absolute
        assertTrue(r.odf.size * r.odfHopSec >= x.size.toDouble() / sr - r.odfHopSec)
        assertTrue(r.onsetTimesSec.first() >= lead + 0.19)
        val firstOnsetFrame = Math.round(r.onsetTimesSec.first() / r.odfHopSec).toInt()
        assertTrue(r.odf[firstOnsetFrame] > 0.5f || r.odf[firstOnsetFrame - 1] > 0.5f)
    }

    @Test
    fun slowTempoRamp_isTrackedAsFlexGrid() {
        // Clicks whose period shrinks linearly from 120 to 130 BPM over 40 s (≈ +8 %).
        val seconds = 40.0
        val x = FloatArray(Math.round(seconds * sr).toInt())
        val times = ArrayList<Double>()
        var t = 0.3
        while (t < seconds - 0.1) {
            times.add(t)
            Synth.addBurst(x, sr, Math.round(t * sr).toInt(), 1200.0, 0.02, 0.8f)
            val bpm = 120.0 + 10.0 * (t / seconds)
            t += 60.0 / bpm
        }
        val r = RhythmAnalyzer().analyze(AudioBuffer.mono(sr, x))
        val est = beatSeconds(r.grid)
        val hits = hitRate(est, times, 0.025)
        println("tempo ramp 120→130: kind=%s bpm=%.1f hits=%.3f residual=%.1f ms".format(r.grid.kind, r.grid.bpm, hits, r.gridResidualMs))
        assertEquals(GridKind.FLEX, r.grid.kind)
        assertTrue(hits >= 0.9, "ramp hit rate $hits")
        assertTrue(r.grid.bpm in 118.0..132.0)
        // beat index → frame must be strictly increasing and interpolation sane
        for (i in 1 until r.grid.beatCount) assertTrue(r.grid.beatFrames[i] > r.grid.beatFrames[i - 1])
    }
}
