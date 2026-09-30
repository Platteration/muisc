package dev.muisc.transitions.recipe

import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.assertContract
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.fadeInB
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.fadeOutA
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.pt
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.recipe
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.render
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lanes land on the rendered seam. In `match` / `glide` the geometry renders whole bars (settle and hold rounded,
 * the overlap rounded and shortened when a song is too short), and the lanes are evaluated with the `bars`,
 * `settle`, `hold` and `total` that are actually rendered, so a lane written to be neutral at `total` is neutral where
 * the segment really ends. The boundary check reads B at the rendered end.
 */
class RecipeRenderSeamTest {
    private val pair = RecipeRenderTestSupport.near

    /** b.hpf parked at 1 kHz until `total - 1`, open (20 Hz) at `total`. */
    private fun hpfToTotal(settle: Any, length: Any = 8) = recipe(
        "seam-hpf", RecipeTempo.MATCH, DeckRecipe(level = fadeOutA()),
        DeckRecipe(level = fadeInB(), hpf = listOf(pt(0, 1000), pt("total - 1", 1000), pt("total", 20))),
        length = length, settle = settle, hold = 2,
    )

    /** The value of the plan lane [id] at output second [sec] (linear between its points; the lane is densified). */
    private fun laneAt(r: RecipeRenderTestSupport.Rendered, id: String, sec: Double): Double {
        val pts = r.out.plan.lanes.single { it.id == id }.points
        if (sec <= pts.first().outputSec) return pts.first().value
        if (sec >= pts.last().outputSec) return pts.last().value
        val i = pts.indexOfLast { it.outputSec <= sec }
        val p = pts[i]; val q = pts[i + 1]
        if (q.outputSec == p.outputSec) return q.value
        return p.value + (q.value - p.value) * (sec - p.outputSec) / (q.outputSec - p.outputSec)
    }

    /** RMS of (B-only render - dry B) over RMS of dry B, 5000..1100 frames before the seam (B runs at ratio 1 there). */
    private fun residualBeforeSeam(r: RecipeRenderTestSupport.Rendered): Double {
        val out = r.out.audio[0]
        val dry = r.input.bAudio[0]
        val end = out.size - Splice.GUARD_FRAMES
        val bAtEnd = r.plan.bEntryOffset - Splice.GUARD_FRAMES
        var diff = 0.0
        var ref = 0.0
        for (i in end - 5000 until end - 1100) {
            val d = dry[bAtEnd - (end - i)].toDouble()
            diff += (out[i] - d) * (out[i] - d)
            ref += d * d
        }
        return sqrt(diff / ref)
    }

    @Test
    fun aFractionalSettleStillEndsOnTheRenderedSeam() {
        val rec = hpfToTotal(settle = 2.4)
        val report = RecipeValidator().validate(rec)
        assertTrue(report.valid, report.toString())
        val r = render(rec, pair, silenceA = true)
        assertContract("settle 2.4", r)
        assertEquals(12.0, r.out.report.metrics["timelineBars"]!!, 1e-6)
        // The processed B is its dry self before the seam blend, as with a whole-bar settle.
        val whole = render(hpfToTotal(settle = 2), pair, silenceA = true)
        val fractional = residualBeforeSeam(r)
        assertTrue(fractional < 0.1, "B-only residual before the seam: $fractional (whole-bar settle: ${residualBeforeSeam(whole)})")
        val endSec = (r.out.audio.frames - Splice.GUARD_FRAMES).toDouble() / RecipeRenderTestSupport.SR
        // b.hpf is open (20 Hz) at the rendered end, not ~126 Hz as when it was placed against total = 12.4.
        assertEquals(20.0, laneAt(r, "b.hpf", endSec), 1e-3)
        assertTrue(r.plan.notes.any { it.startsWith("lanes are placed with bars = 8, settle = 2, hold = 2, total = 12,") }, r.plan.notes.toString())
    }

    @Test
    fun aShortenedOverlapMovesTheLanesWithIt() {
        // 64 bars asked for; the 24-bar B leaves room for far fewer, so the overlap is shortened.
        val rec = recipe(
            "seam-short", RecipeTempo.MATCH, DeckRecipe(level = fadeOutA()),
            DeckRecipe(level = fadeInB(), hpf = listOf(pt(0, 1000), pt("bars", 20))),
            length = 64, settle = 2, hold = 1,
        )
        val r = render(rec, pair, silenceA = true)
        assertContract("shortened", r)
        val rendered = r.out.report.metrics["timelineBars"]!!
        assertTrue(rendered < 30.0, "the overlap was shortened: $rendered bars")
        val endSec = (r.out.audio.frames - Splice.GUARD_FRAMES).toDouble() / RecipeRenderTestSupport.SR
        assertEquals(1.0, laneAt(r, "b.level", endSec), 1e-9)
        assertEquals(20.0, laneAt(r, "b.hpf", endSec), 1e-9)
        assertTrue(r.plan.notes.any { it.startsWith("lanes are placed with bars = ") }, r.plan.notes.toString())
        val residual = residualBeforeSeam(r)
        assertTrue(residual < 0.1, "B is at full level and unfiltered before the seam: residual $residual")
    }

    @Test
    fun aLaneWrittenAgainstAFixedBarWarnsWhenTheRenderEndsEarlier() {
        // Neutral at the recipe's 12.4 bars (so the validator is satisfied), but the render ends at bar 12.
        val rec = recipe(
            "seam-fixed", RecipeTempo.MATCH, DeckRecipe(level = fadeOutA()),
            DeckRecipe(level = fadeInB(), hpf = listOf(pt(0, 1000), pt(11.4, 1000), pt(12.4, 20))),
            length = 8, settle = 2.4, hold = 2,
        )
        val r = render(rec, pair)
        assertContract("fixed bar", r, boundaryClean = false)
        assertTrue(r.out.report.warnings.any { it.startsWith("boundary rule: b.hpf is") && it.contains("(bar 12)") }, r.out.report.warnings.toString())
    }

    /** A recipe that only resolves with its own values (here: a division by `total - 12`) keeps them, and the plan says why. */
    @Test
    fun aRecipeThatDoesNotResolveWithTheRenderedBarsKeepsItsOwn() {
        val rec = recipe(
            "seam-keep", RecipeTempo.MATCH, DeckRecipe(level = fadeOutA()),
            DeckRecipe(level = fadeInB(), mid = listOf(pt(0, 0), pt(1, "-1 / (total - 12)"), pt(2, 0))),
            length = 8, settle = 2.4, hold = 2,
        )
        val plan = RecipeStrategy(rec).plan(pair.a.analysis, pair.b.analysis, pair.features, dev.muisc.transitions.Params.EMPTY, pair.prefs, 1L)
        val note = plan.notes.single { it.startsWith("lanes are placed with") }
        assertTrue(note.startsWith("lanes are placed with the recipe's own values") && note.contains("division by zero"), note)
    }

    /** RECIPES.md: sends and freezes back to 0 by `total - 1`. A vertical step at `total - 1` is 0 there (the later point wins). */
    @Test
    fun aFreezeReleasedByAStepAtTotalMinusOneIsNotABoundaryProblem() {
        val far = RecipeRenderTestSupport.far
        fun freezeUntil(release: String) = recipe(
            "freeze-$release".replace(Regex("[^a-z0-9]+"), "-"), RecipeTempo.NONE,
            DeckRecipe(level = fadeOutA(), reverb = ReverbRecipe(freeze = listOf(pt(0, 0), pt(3, 0), pt(3, 1), pt(release, 1), pt(release, 0)))),
            DeckRecipe(level = fadeInB()),
            length = 4, hold = 2,
        )
        val onTime = freezeUntil("total - 1")
        val report = RecipeValidator().validate(onTime)
        assertTrue(report.problems.isEmpty(), report.toString())
        val r = render(onTime, far)
        assertContract("released at total - 1", r)
        assertEquals(0.0, r.out.report.metrics["boundaryWarnings"])
        // Still frozen inside the last bar: the validator and the renderer both warn.
        val late = freezeUntil("total - 0.5")
        assertTrue(RecipeValidator().validate(late).warnings.any { it.path == "a.reverb.freeze" })
        val lr = render(late, far)
        assertTrue(lr.out.report.warnings.any { it.startsWith("boundary rule: a.reverb.freeze is still on") }, lr.out.report.warnings.toString())
    }

    @Test
    fun maxOverFollowsTheLaterPointAtAVerticalStep() {
        val lane = ResolvedLane(LaneKind.FREEZE, listOf(ResolvedPoint(0.0, 0.0, RecipeCurve.LINEAR), ResolvedPoint(3.0, 1.0, RecipeCurve.LINEAR), ResolvedPoint(5.0, 1.0, RecipeCurve.LINEAR), ResolvedPoint(5.0, 0.0, RecipeCurve.LINEAR)))
        assertEquals(0.0, RecipeGeometry.maxOver(lane, 5.0, 6.0))
        assertEquals(1.0, RecipeGeometry.maxOver(lane, 4.5, 6.0))
        // A step up and down again inside the window counts.
        val blip = ResolvedLane(LaneKind.SEND, listOf(ResolvedPoint(0.0, 0.0, RecipeCurve.LINEAR), ResolvedPoint(5.5, 0.0, RecipeCurve.LINEAR), ResolvedPoint(5.5, 0.4, RecipeCurve.LINEAR), ResolvedPoint(5.5, 0.0, RecipeCurve.LINEAR)))
        assertEquals(0.4, RecipeGeometry.maxOver(blip, 5.0, 6.0))
        // A step at the window's end: the lane is 1 just before it.
        val atEnd = ResolvedLane(LaneKind.FREEZE, listOf(ResolvedPoint(0.0, 1.0, RecipeCurve.LINEAR), ResolvedPoint(6.0, 1.0, RecipeCurve.LINEAR), ResolvedPoint(6.0, 0.0, RecipeCurve.LINEAR)))
        assertEquals(1.0, RecipeGeometry.maxOver(atEnd, 5.0, 6.0))
        assertTrue(abs(RecipeGeometry.maxOver(ResolvedLane(LaneKind.SEND, emptyList()), 0.0, 1.0)) == 0.0)
    }
}
