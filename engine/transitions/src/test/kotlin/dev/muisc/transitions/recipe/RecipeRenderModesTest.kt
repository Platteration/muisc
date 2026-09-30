package dev.muisc.transitions.recipe

import dev.muisc.transitions.StemNeed
import dev.muisc.transitions.core.SongFixtures
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.strategies.BeatDomain
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.T
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.assertContract
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.fadeInB
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.fadeOutA
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.pt
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.recipe
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.render
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One render per tempo mode: geometry, lanes, splice contract, determinism and where the beats land. */
class RecipeRenderModesTest {
    private val g = Splice.GUARD_FRAMES

    private val blend = DeckRecipe(level = fadeOutA()) to DeckRecipe(level = fadeInB())

    @Test
    fun matchRecipeIsBeatLockedAndLandsAtRatioOne() {
        val pair = RecipeRenderTestSupport.near
        val rec = recipe("match-blend", RecipeTempo.MATCH, blend.first, blend.second)
        val r = render(rec, pair)
        assertContract("match", r)
        T.assertBitIdentical(r.out, RecipeRenderTestSupport.reRender(rec, r, pair), "match")
        val plan = r.plan
        assertEquals(StemNeed.NONE, plan.stemNeed)
        assertEquals(listOf(BeatDomain.LANE_MASTER_BEAT, BeatDomain.LANE_MASTER_BPM, "a.level", "b.level"), plan.lanes.map { it.id })
        assertTrue(BeatDomain.hasGeometry(plan), "the plan carries the beat-domain geometry")
        // 8-bar overlap + 2 settle + 1 hold = 11 bars = 44 master beats.
        val beats = T.masterBeatFrames(r.out)
        assertEquals(45, beats.size)
        assertEquals(g.toLong(), beats[0])
        assertEquals((beats[44] + g).toInt(), plan.expectedOutputFrames)
        assertEquals(RecipeGeometry.MARKER_B_ENTERS, r.out.markers[0].label)
        assertEquals(beats[0], r.out.markers[0].frame, "B enters on master beat 0")
        assertEquals(RecipeGeometry.MARKER_A_GONE, r.out.markers[1].label)
        assertEquals(beats[32], r.out.markers[1].frame, "A gone after the 8-bar overlap")
        assertEquals(1.0, r.out.report.ratioTrace.last().toDouble(), 0.002, "B at ratio 1.0 before the seam")
        assertTrue(plan.notes.any { it.startsWith("match:") }, plan.notes.toString())
        // B's kicks (B alone) sit on the master beats, bars 4..10.
        val bOnly = render(rec, pair, silenceA = true)
        T.assertAligned("B kicks", T.kickAlignment(bOnly.out, 16 until 40, pair.b.audio, T.beatFrames(pair.b, 16, 40)))
        // A's kicks too, while A is still loud (bars 0..4).
        val aOnly = render(rec, pair, silenceB = true)
        T.assertAligned("A kicks", T.kickAlignment(aOnly.out, 0 until 16, pair.a.audio, T.beatFrames(pair.a, 16, 32)))
    }

    @Test
    fun glideRecipeMovesTheMasterTempoAcrossTheOverlap() {
        val pair = RecipeRenderTestSupport.glidePair
        val rec = recipe("glide-blend", RecipeTempo.GLIDE, blend.first, blend.second)
        val r = render(rec, pair)
        assertContract("glide", r)
        T.assertBitIdentical(r.out, RecipeRenderTestSupport.reRender(rec, r, pair), "glide")
        val bpm = r.plan.lanes.first { it.id == BeatDomain.LANE_MASTER_BPM }.points
        assertEquals(120.0, bpm.first().value, 1e-6, "the glide starts at A's tempo")
        assertEquals(124.0, bpm[32].value, 1e-6, "B's tempo is reached at the end of the overlap")
        assertTrue((1 until 32).all { bpm[it].value >= bpm[it - 1].value }, "the tempo only rises during the glide")
        assertTrue((32 until bpm.size).all { abs(bpm[it].value - 124.0) < 1e-6 }, "then holds B's tempo for settle + hold")
        assertEquals(1.0, r.out.report.ratioTrace.last().toDouble(), 0.002, "B at ratio 1.0 before the seam")
        assertTrue(r.plan.notes.any { it.startsWith("glide:") }, r.plan.notes.toString())
        val bOnly = render(rec, pair, silenceA = true)
        T.assertAligned("B kicks during and after the glide", T.kickAlignment(bOnly.out, 16 until 40, pair.b.audio, T.beatFrames(pair.b, 16, 40)))
    }

    @Test
    fun noneRecipeFollowsAsBarsAndLandsBUnstretched() {
        val pair = RecipeRenderTestSupport.far
        val rec = recipe(
            "none-cut", RecipeTempo.NONE,
            DeckRecipe(level = listOf(pt(1, 1, RecipeCurve.EQUAL_POWER), pt(4, 0))),
            DeckRecipe(level = listOf(pt(2, 0, RecipeCurve.EQUAL_POWER), pt(4, 1))),
            length = 4, hold = 1, bEntersAt = 4,
        )
        assertTrue(RecipeStrategy(rec).applicability(pair.features, pair.a.analysis, pair.b.analysis, pair.prefs).applicable, "a none recipe does not care about the tempo gap")
        val r = render(rec, pair)
        assertContract("none", r)
        T.assertBitIdentical(r.out, RecipeRenderTestSupport.reRender(rec, r, pair), "none")
        assertTrue(r.plan.lanes.none { it.id == BeatDomain.LANE_MASTER_BEAT }, "a tempo-agnostic recipe makes no beat-alignment claim")
        assertTrue(r.plan.lanes.any { it.id == RecipeGeometry.LANE_BEATS_A })
        assertTrue(!BeatDomain.hasGeometry(r.plan))
        // 5 bars of A at 120 BPM: 10 s of timeline between the guards.
        val barFrames = 2.0 * RecipeRenderTestSupport.SR
        assertEquals((2 * g + Math.round(5 * barFrames)).toInt(), r.plan.expectedOutputFrames)
        assertEquals(g + Math.round(2 * barFrames), r.out.markers[0].frame, "B enters when its level starts rising (bar 2)")
        assertEquals(g + Math.round(4 * barFrames), r.out.markers[1].frame, "A gone at bar 4")
        assertTrue(r.out.report.ratioTrace.isEmpty(), "nothing is stretched")
        // B's entry downbeat (its mixInBeat 16) lands on timeline bar 4 and B keeps its own 140 BPM from there.
        val bOnly = render(rec, pair, silenceA = true)
        val grid = pair.b.analysis.grid
        val land = g + Math.round(4 * barFrames)
        val expected = LongArray(4) { land + (grid.beatFrames[16 + it] - grid.beatFrames[16]) }
        val offsets = SongFixtures.kickOffsets(bOnly.out.audio, expected)
        val bias = SongFixtures.median(SongFixtures.kickOffsets(pair.b.audio, T.beatFrames(pair.b, 16, 20)))
        assertTrue(offsets.all { abs(it - bias) <= 0.002 * RecipeRenderTestSupport.SR }, "B's kicks at its own tempo from bar 4: offsets ${offsets.toList()} bias $bias")
        // Before the crossfade A is untouched: the first bar after the pre-roll is A verbatim.
        val aOff = r.plan.aExitOffset
        var maxDiff = 0f
        for (i in 0 until 4096) maxDiff = maxOf(maxDiff, abs(r.out.audio[0][g + 2048 + i] - r.input.aAudio[0][aOff + g + 2048 + i]))
        assertTrue(maxDiff < 1e-6f, "A is unprocessed while its lanes are neutral (max diff $maxDiff)")
    }
}
