package dev.muisc.transitions.recipe

import dev.muisc.transitions.recipe.RecipeRenderTestSupport.T
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.assertContract
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.fadeInB
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.pt
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.recipe
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.render
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recipes that break the boundary rule render with warnings — never a crash, never a broken splice. */
class RecipeRenderBoundaryTest {
    /** A cut on its EQ at bar 0, A still at half level when the overlap ends, B filtered at the seam, B's echo never closed. */
    private fun rude(tempo: RecipeTempo) = recipe(
        "rude-${tempo.name.lowercase()}", tempo,
        DeckRecipe(low = listOf(pt(0, -12), pt(4, 0)), level = listOf(pt(0, 1), pt(8, 0.5))),
        DeckRecipe(level = fadeInB(), lpf = listOf(pt(0, 20000), pt("total", 2000)), echo = EchoRecipe(send = listOf(pt(0, 0.3)))),
    )

    private fun assertWarned(name: String, r: RecipeRenderTestSupport.Rendered) {
        val w = r.out.report.warnings
        for (what in listOf("a.low is -12 dB at bar 0", "A's level is 0.5 at the end of the overlap", "b.lpf is 2000 Hz at the end of the timeline", "b.echo.send is still open in the last bar")) {
            assertTrue(w.any { it.startsWith("boundary rule: $what") }, "$name: warning '$what' in $w")
            assertTrue(r.plan.notes.any { it.startsWith("warning: boundary rule: $what") }, "$name: the plan already says '$what': ${r.plan.notes}")
        }
        assertEquals(4.0, r.out.report.metrics["boundaryWarnings"])
    }

    @Test
    fun matchRecipeBreakingTheRuleStillSplicesCleanly() {
        val pair = RecipeRenderTestSupport.near
        val rec = rude(RecipeTempo.MATCH)
        val r = render(rec, pair)
        assertContract("rude match", r, boundaryClean = false)
        assertWarned("rude match", r)
        // A is cut at the end of the overlap with the 10 ms declick: the last A frames of the A-only render fall to 0.
        val aOnly = render(rec, pair, silenceB = true)
        val beats = T.masterBeatFrames(aOnly.out)
        val end = beats[32].toInt()
        val x = aOnly.out.audio[0]
        var tailPeak = 0f
        for (i in end until end + 4096) tailPeak = maxOf(tailPeak, abs(x[i]))
        var bodyPeak = 0f
        for (i in end - 20000 until end - 1000) bodyPeak = maxOf(bodyPeak, abs(x[i]))
        assertTrue(bodyPeak > 0.05f && tailPeak == 0f, "A plays at half level until the overlap ends ($bodyPeak) and nothing of it after ($tailPeak)")
    }

    @Test
    fun noneRecipeBreakingTheRuleStillSplicesCleanly() {
        val pair = RecipeRenderTestSupport.far
        val r = render(rude(RecipeTempo.NONE), pair)
        assertContract("rude none", r, boundaryClean = false)
        assertWarned("rude none", r)
    }

    @Test
    fun cleanRecipeHasNoWarnings() {
        val rec = recipe("polite", RecipeTempo.MATCH, DeckRecipe(level = RecipeRenderTestSupport.fadeOutA()), DeckRecipe(level = fadeInB()))
        val resolved = RecipeResolver.resolve(rec)
        val r = render(rec, RecipeRenderTestSupport.near)
        assertTrue(r.plan.notes.none { it.startsWith("warning") }, r.plan.notes.toString())
        assertEquals(0.0, r.out.report.metrics["boundaryWarnings"])
        assertTrue(resolved.a.level.valueAt(0.0) == 1.0 && resolved.b.level.valueAt(resolved.totalBars) == 1.0)
    }
}
