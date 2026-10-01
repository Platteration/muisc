package dev.muisc.analysis.rhythm

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.GridKind
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.Synth
import dev.muisc.audio.synth.SyntheticSong
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
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

    companion object {
        /**
         * Two-beat grooves at 85–130 BPM ([Grooves]). The ones marked "halved" were read at half their tempo by the
         * metrical-level check of analysis version 3; the others are kept as guards.
         */
        @JvmStatic
        fun twoBeatGrooves(): List<Grooves.Groove> {
            val kb = Grooves.Low.KICK_BASS
            return listOf(
                // clap backbeat (kick 0.8), no hats: halved at every clap level
                Grooves.Groove("clap 0.3, no hats", 92.0, kb, Grooves.Back.CLAP, 0.3f, 0f),
                Grooves.Groove("clap 0.6, no hats", 120.0, kb, Grooves.Back.CLAP, 0.6f, 0f),
                Grooves.Groove("clap 1.2, no hats", 100.0, kb, Grooves.Back.CLAP, 1.2f, 0f),
                Grooves.Groove("clap 0.3, no hats", 130.0, kb, Grooves.Back.CLAP, 0.3f, 0f),
                // clap backbeat with quiet and with normal eighth-note hats: halved
                Grooves.Groove("clap 0.6, quiet hats", 100.0, kb, Grooves.Back.CLAP, 0.6f, 0.05f),
                Grooves.Groove("clap 1.2, quiet hats", 120.0, kb, Grooves.Back.CLAP, 1.2f, 0.05f),
                Grooves.Groove("clap 1.2, hats", 100.0, kb, Grooves.Back.CLAP, 1.2f, 0.18f),
                Grooves.Groove("clap 0.08, kick 1.0, hats", 110.0, kb, Grooves.Back.CLAP, 0.08f, 0.18f, kickAmp = 1.0f),
                Grooves.Groove("clap 0.08, kick 1.0, hats", 126.0, kb, Grooves.Back.CLAP, 0.08f, 0.18f, kickAmp = 1.0f),
                // snare backbeat with a 280 Hz body (little of it in the low band), no hats: halved
                Grooves.Groove("snare 280 Hz 0.3, no hats", 92.0, kb, Grooves.Back.SNARE, 0.3f, 0f, snareHz = 280.0),
                Grooves.Groove("snare 280 Hz 0.3, no hats", 130.0, kb, Grooves.Back.SNARE, 0.3f, 0f, snareHz = 280.0),
                // snare backbeats the first check already kept (guards)
                Grooves.Groove("snare 0.5, no hats", 88.0, kb, Grooves.Back.SNARE, 0.5f, 0f),
                Grooves.Groove("snare 230 Hz 0.5, hats", 120.0, kb, Grooves.Back.SNARE, 0.5f, 0.18f, snareHz = 230.0),
                // boom-chick (kick + bass / chord + brush): halved with a quiet chord, kept with a louder one
                Grooves.Groove("boom-chick chord 0.02", 88.0, kb, Grooves.Back.CHORD_BRUSH, 0.02f, 0f),
                Grooves.Groove("boom-chick chord 0.03", 110.0, kb, Grooves.Back.CHORD_BRUSH, 0.03f, 0f),
                Grooves.Groove("boom-chick chord 0.02", 126.0, kb, Grooves.Back.CHORD_BRUSH, 0.02f, 0f),
                Grooves.Groove("boom-chick chord 0.12", 120.0, kb, Grooves.Back.CHORD_BRUSH, 0.12f, 0f),
                // oom-pah (bass / chord): halved with the chord at 0.06, kept at 0.12
                Grooves.Groove("oom-pah chord 0.06", 85.0, Grooves.Low.BASS, Grooves.Back.CHORD, 0.06f, 0f),
                Grooves.Groove("oom-pah chord 0.06", 100.0, Grooves.Low.BASS, Grooves.Back.CHORD, 0.06f, 0f),
                Grooves.Groove("oom-pah chord 0.06", 120.0, Grooves.Low.BASS, Grooves.Back.CHORD, 0.06f, 0f),
                Grooves.Groove("oom-pah chord 0.12", 110.0, Grooves.Low.BASS, Grooves.Back.CHORD, 0.12f, 0f),
            )
        }
    }

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

    /** Worst distance (s) from a true beat to the grid's nearest beat, the grid extrapolated before its first beat. */
    private fun worstBeatError(grid: BeatGrid, truth: DoubleArray): Double = truth.maxOf { t ->
        val k = Math.round(grid.beatAtFrame(Math.round(t * sr))).toDouble()
        abs(grid.frameOfBeat(k).toDouble() / sr - t)
    }

    @Test
    fun slowSongsWithEighthNoteHats_areReadAtTheirTempoNotAtDoubleTime() {
        // Kick and bass on every beat, hi-hats on the eighths: at 61–66 BPM the eighth-note pulse (122–132 BPM) is as
        // periodic as the beat and inside the preferred range, and the analyzer used to read these songs at double
        // time (`muisc bench analysis`, seed 1: 60.8 → 123.0, 66.4 → 132.2 twice).
        for ((i, bpm) in doubleArrayOf(61.0, 66.4).withIndex()) {
            val song = SyntheticSong(bpm = bpm, bars = 12, introBars = 0, outroBars = 0, seed = 21 + i, tonic = 3 + i, sampleRate = sr)
            val r = RhythmAnalyzer().analyze(song.render())
            assertEquals(bpm, r.tempo.bpm, bpm * 0.01, "song $bpm: tempo (alternates ${r.tempo.alternates})")
            assertEquals(bpm, r.grid.bpm, bpm * 0.01, "song $bpm: grid tempo")
            assertEquals(GridKind.RIGID, r.grid.kind, "song $bpm: grid kind (residual ${r.gridResidualMs} ms)")
            assertTrue(worstBeatError(r.grid, song.beatTimes()) <= 0.025, "song $bpm: worst beat error ${worstBeatError(r.grid, song.beatTimes())} s")
            // the double-time reading stays available as an alternate
            assertTrue(r.tempo.alternates.any { abs(it.bpm - 2 * bpm) < 0.02 * bpm }, "song $bpm: alternates ${r.tempo.alternates}")
        }
    }

    @Test
    fun backbeatWithEighthNoteHats_keepsItsTempo() {
        // Kick on 1 and 3, snare on 2 and 4, hi-hats on the eighths, bass on 1 and 3: the low band alternates beat to
        // beat just as a slow song's tracked eighths do, but the snare is as strong a broadband event as the kick, so
        // the metrical-level check must not halve it (120 BPM, not 60).
        backbeat(hats = true)
    }

    @Test
    fun backbeatWithoutHats_keepsItsTempo() {
        // The same backbeat with no hi-hats: the clap is then the louder broadband event and the kick's beats carry
        // nothing above the low band. The symmetric broadband balance of the first metrical-level check read that as
        // alternation and halved it (120 → 60).
        backbeat(hats = false)
    }

    private fun backbeat(hats: Boolean) {
        val bpm = 120.0
        val beat = 60.0 / bpm
        val x = FloatArray(Math.round(40.0 * sr).toInt())
        val rnd = kotlin.random.Random(9)
        var k = 0
        while (k * beat < 39.5) {
            val start = Math.round(k * beat * sr).toInt()
            if (k % 2 == 0) {
                var phase = 0.0
                for (i in 0 until (0.35 * sr).toInt()) {
                    val t = i.toDouble() / sr
                    phase += 2 * Math.PI * (45.0 + 110.0 * kotlin.math.exp(-t * 28.0)) / sr
                    val bass = 0.2 * kotlin.math.exp(-t * 4.0) * kotlin.math.sin(2 * Math.PI * 55.0 * t)
                    if (start + i < x.size) x[start + i] += (0.8 * kotlin.math.exp(-t * 9.0) * kotlin.math.sin(phase) + bass).toFloat()
                }
            } else {
                // a clap-like snare: high-passed noise, no body in the low band
                var hp = 0f
                for (i in 0 until (0.18 * sr).toInt()) {
                    val white = rnd.nextFloat() * 2f - 1f
                    val out = white - hp; hp += 0.6f * (white - hp)
                    if (start + i < x.size) x[start + i] += 0.6f * kotlin.math.exp(-i * 22.0 / sr).toFloat() * out
                }
            }
            for (h in 0 until if (hats) 2 else 0) {
                val hs = Math.round((k * beat + h * beat / 2) * sr).toInt()
                var hp = 0f
                for (i in 0 until (0.1 * sr).toInt()) {
                    val white = rnd.nextFloat() * 2f - 1f
                    val out = white - hp; hp += 0.6f * (white - hp)
                    if (hs + i < x.size) x[hs + i] += 0.2f * kotlin.math.exp(-i / (0.02 * sr)).toFloat() * out
                }
            }
            k++
        }
        val r = RhythmAnalyzer().analyze(AudioBuffer.mono(sr, x))
        assertEquals(bpm, r.tempo.bpm, bpm * 0.01, "backbeat tempo (alternates ${r.tempo.alternates})")
        assertEquals(bpm, r.grid.bpm, bpm * 0.01, "backbeat grid tempo")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("twoBeatGrooves")
    fun backbeatBoomChickAndOomPah_keepTheirTempo(g: Grooves.Groove) {
        // Something low on 1 and 3 (kick and bass, or bass alone) and something else on 2 and 4 (snare, clap, chord
        // and brush, chord alone): the low band alternates beat to beat, but the beat between two low notes is a real
        // beat, not a hi-hat off-beat, so the tempo must not be halved. The first metrical-level check halved most of
        // these (92 → 46, 120 → 60, ...), see the comments in [twoBeatGrooves].
        val r = RhythmAnalyzer().analyze(Grooves.render(g, sr))
        assertEquals(g.bpm, r.tempo.bpm, g.bpm * 0.01, "$g: tempo (alternates ${r.tempo.alternates})")
        assertEquals(g.bpm, r.grid.bpm, g.bpm * 0.01, "$g: grid tempo")
        val truth = (0 until (g.seconds / (60.0 / g.bpm)).toInt()).map { it * 60.0 / g.bpm }.filter { it in 2.0..(g.seconds - 2.0) }
        val hits = hitRate(beatSeconds(r.grid), truth, 0.025)
        assertTrue(hits >= 0.9, "$g: beat hit rate $hits")
    }

    @Test
    fun longDrumlessIntro_theGridIsTheDrumSectionExtendedBackThroughTheIntro() {
        // Eight bars of pad only (one chord change per bar, nothing on the other beats) before the drums. The tracked
        // beats wander through such an intro, and the analyzer used to hand them on as a FLEX grid (`muisc bench
        // analysis`, seed 1: 76.8 BPM i8 had beat F 0.59, 86.6 BPM i8 0.59). The grid must be the drum section's,
        // extended back through the intro, with the intro's downbeats on the chord changes.
        for ((i, bpm) in doubleArrayOf(76.8, 88.0).withIndex()) {
            val song = SyntheticSong(bpm = bpm, bars = 14, introBars = 8, outroBars = 0, seed = 31 + i, tonic = 7 + i, sampleRate = sr)
            val r = RhythmAnalyzer().analyze(song.render())
            val g = r.grid
            assertEquals(GridKind.RIGID, g.kind, "song $bpm: grid kind (residual ${r.gridResidualMs} ms)")
            assertEquals(bpm, r.tempo.bpm, bpm * 0.005, "song $bpm: tempo")
            val worst = worstBeatError(g, song.beatTimes())
            assertTrue(worst <= 0.025, "song $bpm: worst beat error ${worst * 1000} ms (intro included)")
            for (d in song.downbeatTimes()) {
                val b = Math.round(g.beatAtFrame(Math.round(d * sr))).toInt()
                assertTrue(g.isDownbeat(b), "song $bpm: the downbeat at $d s is beat $b, not a grid downbeat (phase ${g.downbeatPhase})")
            }
        }
    }
}
