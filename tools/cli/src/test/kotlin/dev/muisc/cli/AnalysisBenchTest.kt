package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The analysis accuracy floor: [AnalysisBench] on a fixed set of 12 songs (seed [SEED]), asserting what the
 * analyzer achieves TODAY as a floor. The numbers below were measured on this code, then written down; they are
 * not targets. If an analyzer change raises them, raise the floor with it; if a change lowers them, that is a
 * regression to explain, not a floor to lower (AGENTS.md §4).
 */
class AnalysisBenchTest {

    companion object {
        const val SEED = 1L
        const val SONGS = 12

        private val scores: List<AnalysisBench.Score> by lazy {
            AnalysisBench.run(AnalysisBench.generate(SONGS, SEED)).also { println(AnalysisBench.report(it, SEED, showAll = true)) }
        }
    }

    @TempDir
    lateinit var tmp: File

    @Test
    fun `the generator covers the ranges it promises and is deterministic`() {
        val cases = AnalysisBench.generate(24, 5L)
        assertEquals(24, cases.map { it.trueKey }.toSet().size, "24 cases must cover all 24 keys")
        assertTrue(cases.all { it.song.bpm in 60.0..180.0 })
        assertTrue(cases.any { it.song.leadingSilenceSec > 0 } && cases.any { it.song.introBars == 0 } && cases.any { it.song.outroFade })
        assertTrue(cases.any { it.detuneCents > 5 } && cases.any { it.detuneCents < -5 } && cases.all { kotlin.math.abs(it.detuneCents) < 41 })
        assertEquals(0.0, cases[0].detuneCents)
        val again = AnalysisBench.generate(24, 5L)
        assertEquals(cases.map { it.song }, again.map { it.song })
        assertTrue(AnalysisBench.generate(24, 6L).map { it.song } != cases.map { it.song }, "a different seed must give different songs")
    }

    @Test
    fun `detune scales the ground truth tempo and times together`() {
        val c = AnalysisBench.generate(8, 3L).first { kotlin.math.abs(it.detuneCents) > 10 }
        val audio = c.render()
        assertEquals(AnalysisBench.SAMPLE_RATE, audio.sampleRate)
        // Rendered at renderRate and read at 44.1 kHz: the file is shorter (or longer) by exactly the speed factor.
        assertEquals(c.song.durationSec / c.speed, audio.durationSec, 0.001)
        assertEquals(c.song.bpm * c.speed, c.trueBpm, 1e-9)
        assertEquals(c.song.beatTimes()[5] / c.speed, c.trueBeats[5], 1e-9)
    }

    @Test
    fun `beat matching is one to one within the tolerance`() {
        val truth = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val est = doubleArrayOf(1.05, 1.06, 2.2, 3.0, 3.01, 4.069)
        val m = AnalysisBench.matchBeats(truth, est, 0.07)
        assertEquals(listOf(0, -1, 3, 5), m.toList())
    }

    @Test
    fun `accuracy on the fixed set is at least what the analyzer achieved when this floor was set`() {
        val s = AnalysisBench.Summary(scores)
        assertEquals(SONGS, s.songs)
        val report = AnalysisBench.report(scores, SEED, showAll = true)
        assertTrue(s.tempoOk >= FLOOR_TEMPO_OK, "tempo within 1 %: ${s.tempoOk} < floor $FLOOR_TEMPO_OK\n$report")
        assertTrue(s.octaveErrors <= CEILING_OCTAVE_ERRORS, "octave errors: ${s.octaveErrors} > ceiling $CEILING_OCTAVE_ERRORS\n$report")
        assertTrue(s.beatFOk >= FLOOR_BEAT_F_OK, "beat F ≥ 0.9: ${s.beatFOk} < floor $FLOOR_BEAT_F_OK\n$report")
        assertTrue(s.meanF >= FLOOR_MEAN_F, "mean beat F ${s.meanF} < floor $FLOOR_MEAN_F\n$report")
        assertTrue(s.phaseOk >= FLOOR_PHASE_OK, "downbeat phase: ${s.phaseOk} < floor $FLOOR_PHASE_OK\n$report")
        assertTrue(s.keyExact >= FLOOR_KEY_EXACT, "key exact: ${s.keyExact} < floor $FLOOR_KEY_EXACT\n$report")
        assertTrue(s.keyExact + s.keyRelative >= FLOOR_KEY_EXACT_OR_RELATIVE, "key exact or relative: ${s.keyExact + s.keyRelative} < floor $FLOOR_KEY_EXACT_OR_RELATIVE\n$report")
        assertTrue(s.trimOk >= FLOOR_TRIM_OK, "trim within 50 ms: ${s.trimOk} < floor $FLOOR_TRIM_OK\n$report")
    }

    @Test
    fun `muisc bench analysis prints the summary and the case table`() {
        val out = runBench(tmp, "analysis", "--songs", "2", "--seed", "4", "--all")
        assertContains(out, "analysis bench: 2 synthetic songs, seed 4")
        assertContains(out, "tempo within 1 %")
        assertContains(out, "beat F-measure")
        assertContains(out, "all cases:")
        assertContains(out, "#1 ")
        assertFailsWith<CliktError> { runBench(tmp, "analysis", "--songs", "0") }
    }
}

// Floors measured on the fixed set (seed 1, 12 songs) on 2026-09-30, on the analyzer at commit 596a577, and written
// down as measured (the mean F-measure rounded down to two decimals: measured 0.896). Measured, per song:
// tempo 11/12 (#7, 66 BPM, is read as 132: an octave error), beat F >= 0.9 in 9/12 (#0 and #1 have 8-bar pad-only
// intros; #7 is the octave error), downbeat phase 10/12 (#0, #5), key 12/12 exact, trim 12/12 within 50 ms (max 8.6).
private const val FLOOR_TEMPO_OK = 11
private const val CEILING_OCTAVE_ERRORS = 1
private const val FLOOR_BEAT_F_OK = 9
private const val FLOOR_MEAN_F = 0.89
private const val FLOOR_PHASE_OK = 10
private const val FLOOR_KEY_EXACT = 12
private const val FLOOR_KEY_EXACT_OR_RELATIVE = 12
private const val FLOOR_TRIM_OK = 12

/** Runs `muisc bench <args>` in-process (see [runStandalone]). */
internal fun runBench(root: File, vararg args: String): String = runStandalone(BenchCommand.build(), root, *args)
