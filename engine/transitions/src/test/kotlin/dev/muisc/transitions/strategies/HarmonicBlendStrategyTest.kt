package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.Mode
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.strategies.BeatDomainTestSupport as T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarmonicBlendStrategyTest {
    private val strategy = HarmonicBlendStrategy()
    private val a120 = T.song(120.0, 0, Mode.MAJOR)          // C major = 8B
    private val bE = T.song(126.0, 4, Mode.MAJOR)            // E major = 12B: distance 4, +1 st -> F major = 7B (distance 1)
    private val pair by lazy { T.pair(a120, bE) }
    /** Shortest overlap (12 bars), 4-bar settle, 2-bar hold: an 18-bar grid. */
    private val short = Params.EMPTY.with("overlapBars", 12)

    private fun render(p: T.Pair, params: Params, seed: Long = 1L) = run {
        val plan = strategy.plan(p.a.analysis, p.b.analysis, p.features, params, p.prefs, seed)
        val input = p.input(plan)
        Triple(plan, input, strategy.render(input, RenderContext(p.prefs, seed)))
    }

    @Test
    fun applicabilityNeedsAKeyRelationWithinTheShiftLimit() {
        assertEquals("harmonicBlend", strategy.id)
        assertEquals(listOf("overlapBars", "maxShift", "settleBars", "vocalDuckDb", "eqDepthDb", "bassInBar", "lowHz", "law", "entryOffsetBars", "holdBars", "detectOnsets"), strategy.params.map { it.id })
        assertEquals(4, pair.features.camelotDistance); assertEquals(1, pair.features.bestPitchShiftSemitones); assertEquals(1, pair.features.camelotDistanceAfterShift)
        val ok = strategy.applicability(pair.features, pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(ok.applicable && ok.score > 0.5, ok.toString())
        assertTrue(ok.reasons.any { it.startsWith("pitch shift +1 st") }, ok.reasons.toString())
        // Shifting not allowed: the pair stays 4 apart -> blocked.
        val noShift = T.pair(a120, bE, maxPitchShiftSemitones = 0.0)
        val blocked = strategy.applicability(noShift.features, noShift.a.analysis, noShift.b.analysis, noShift.prefs)
        assertTrue(!blocked.applicable && blocked.blockers.any { it.contains("Camelot distance 4") }, blocked.toString())
        // Weak key estimates: blocked (the shift would be a guess).
        val weak = strategy.applicability(pair.features.copy(keyStrengthB = 0.4), pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(!weak.applicable && weak.blockers.any { it.contains("key strength") }, weak.toString())
        // Tempo far apart: blocked like every beat-domain strategy.
        val far = T.pair(a120, T.song(150.0, 4, Mode.MAJOR))
        assertTrue(!strategy.applicability(far.features, far.a.analysis, far.b.analysis, far.prefs).applicable)
        // Already compatible keys: applicable without a shift and scores higher than the shifted pair.
        val relative = T.pair(a120, T.song(126.0, 9, Mode.MINOR))
        val rel = strategy.applicability(relative.features, relative.a.analysis, relative.b.analysis, relative.prefs)
        assertTrue(rel.applicable && rel.score > ok.score && rel.reasons.any { it.contains("no shift") }, rel.toString())
    }

    @Test
    fun renderIsCleanDeterministicAndBeatLocked() {
        val (plan, input, rendered) = render(pair, short, seed = 9)
        assertEquals("1", plan.params[HarmonicBlendStrategy.PARAM_SHIFT])
        assertTrue(plan.notes.any { it.contains("shifted +1 st") }, plan.notes.toString())
        T.assertContract("harmonicBlend", plan, input, rendered)
        T.assertBitIdentical(rendered, render(pair, short, seed = 9).third)
        assertEquals(listOf(HarmonicBlendStrategy.MARKER_B_ENTERS, HarmonicBlendStrategy.MARKER_A_GONE, HarmonicBlendStrategy.MARKER_SHIFT_BACK, HarmonicBlendStrategy.MARKER_UNSHIFTED), rendered.markers.map { it.label })
        val beats = T.masterBeatFrames(rendered)
        assertEquals(18 * 4 + 1, beats.size)
        assertEquals(beats[48], rendered.markers[1].frame, "A gone after 12 bars")
        assertEquals(beats[48], rendered.markers[2].frame, "shift ramps back as soon as A is gone")
        assertEquals(beats[64], rendered.markers[3].frame, "unshifted at the hold")
        val pitch = rendered.plan.lanes.first { it.id == HarmonicBlendStrategy.LANE_PITCH }
        assertEquals(1.0, pitch.points.first().value); assertEquals(0.0, pitch.points.last().value)
        assertEquals(1.0, rendered.report.ratioTrace.last().toDouble(), 0.002, "B at ratio 1.0 before the seam")
        // B's kicks stay on the master beats through the warp (B bars 4..20 have drums: master beats 0..64).
        T.assertAligned("B kicks (shifted)", T.kickAlignment(rendered, 0 until 64, pair.b.audio, T.beatFrames(pair.b, 16, 80)))
    }

    @Test
    fun bIsShiftedByOneSemitoneInTheOverlapAndUnshiftedAtTheEnd() {
        val (_, input, _) = render(pair, short)
        val bOnly = strategy.render(T.silenced(input, silenceA = true, silenceB = false), RenderContext(pair.prefs, 1))
        val beats = T.masterBeatFrames(bOnly)
        // Master bar 2 = B bar 6 (chord vi of E major, bass C#2 = 69.3 Hz); +1 st -> 73.4 Hz. The kick's chirp sweeps
        // through the fundamental's band, so the bass is identified by its 3rd harmonic (207.9 -> 220.3 Hz), clear of
        // the kick, the 190 Hz snare and the pad (>= 277 Hz).
        val overlapHz = T.peakHz(bOnly.audio, beats[8], beats[12], 200.0, 235.0)
        assertEquals(220.3, overlapHz, 2.5, "bass 3rd harmonic in the overlap is C#2 shifted up a semitone")
        // Master bar 17 (hold, B bar 21: chord V, bass B1 = 61.7 Hz) is unshifted.
        val holdHz = T.peakHz(bOnly.audio, beats[68], beats[72], 50.0, 80.0)
        assertEquals(61.7, holdHz, 1.0, "bass fundamental in the hold is unshifted")
        // With shifting disabled the same bar is at its native pitch.
        val plain = strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, short.with("maxShift", 0), pair.prefs, 1)
        assertEquals("0", plain.params[HarmonicBlendStrategy.PARAM_SHIFT])
        assertTrue(plain.notes.any { it.contains("blending unshifted") }, plain.notes.toString())
        val plainOnly = strategy.render(T.silenced(pair.input(plain), silenceA = true, silenceB = false), RenderContext(pair.prefs, 1))
        val pb = T.masterBeatFrames(plainOnly)
        assertEquals(207.9, T.peakHz(plainOnly.audio, pb[8], pb[12], 200.0, 235.0), 2.5, "unshifted C#2 (3rd harmonic)")
        assertEquals(emptyList(), dev.muisc.transitions.core.SpliceCheck.verify(plainOnly, T.silenced(pair.input(plain), true, false)))
    }
}
