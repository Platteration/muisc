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

    /**
     * The processed decks are entered and left with the same linear guard blends as the built-in beat-domain
     * strategies: A goes from its dry pre-roll into the processed (here: half-level) signal over
     * [dev.muisc.transitions.strategies.BeatDomain.SEAM_BLEND_FRAMES] frames, and B from its processed signal into
     * the dry post-roll over as many frames before it. In `none` mode both decks are unstretched, so the ratio of
     * the rendered to the dry sample is exactly the blended gain.
     */
    @Test
    fun guardBlendsJoinTheProcessedSignal() {
        val pair = RecipeRenderTestSupport.far
        val rec = recipe(
            "half", RecipeTempo.NONE,
            DeckRecipe(level = listOf(pt(0, 0.5), pt(3, 0.5), pt(4, 0))),
            DeckRecipe(level = listOf(pt(0, 0), pt(2, 0.5))),
            length = 4, hold = 1,
        )
        val n = dev.muisc.transitions.strategies.BeatDomain.SEAM_BLEND_FRAMES
        val g = dev.muisc.transitions.core.Splice.GUARD_FRAMES
        val aOnly = render(rec, pair, silenceB = true)
        assertContract("half A", aOnly, boundaryClean = false)
        assertTrue(aOnly.out.report.warnings.any { it.startsWith("boundary rule: a.level is x0.5 at bar 0") }, aOnly.out.report.warnings.toString())
        val aDry = aOnly.input.aAudio[0]
        val aOff = aOnly.plan.aExitOffset
        var checked = 0
        for (i in 0 until n) {
            val d = aDry[aOff + g + i]
            if (abs(d) < 0.01f) continue
            val w = (i + 1).toDouble() / (n + 1)
            assertEquals(1.0 - 0.5 * w, (aOnly.out.audio[0][g + i] / d).toDouble(), 1e-3, "A's head blend at frame $i")
            checked++
        }
        val bOnly = render(rec, pair, silenceA = true)
        assertContract("half B", bOnly, boundaryClean = false)
        assertTrue(bOnly.out.report.warnings.any { it.startsWith("boundary rule: b.level is x0.5 at the end of the timeline") }, bOnly.out.report.warnings.toString())
        val bDry = bOnly.input.bAudio[0]
        val end = bOnly.plan.expectedOutputFrames - g
        val bOff = bOnly.plan.bEntryOffset - bOnly.plan.expectedOutputFrames
        for (i in 0 until n) {
            val frame = end - n + i
            val d = bDry[bOff + frame]
            if (abs(d) < 0.01f) continue
            val w = (i + 1).toDouble() / (n + 1)
            assertEquals(0.5 + 0.5 * w, (bOnly.out.audio[0][frame] / d).toDouble(), 1e-3, "B's tail blend at frame $i")
            checked++
        }
        assertTrue(checked > 500, "enough samples compared ($checked)")
    }

    /** A deck A still audible when the overlap ends (here at bar 3.6, mid-beat) leaves with a 10 ms linear fade, not a cut. */
    @Test
    fun aStillAudibleAtTheEndOfTheOverlapIsDeclicked() {
        val pair = RecipeRenderTestSupport.far
        val rec = recipe(
            "a-hangs-on", RecipeTempo.NONE,
            DeckRecipe(level = listOf(pt(0, 1), pt(1, 0.5))),
            DeckRecipe(level = listOf(pt(1, 0, RecipeCurve.EQUAL_POWER), pt(3, 1))),
            length = 3.6, hold = 1,
        )
        val aOnly = render(rec, pair, silenceB = true)
        assertContract("a hangs on", aOnly, boundaryClean = false)
        assertTrue(aOnly.out.report.warnings.any { it.startsWith("boundary rule: A's level is 0.5 at the end of the overlap (bar 3.6)") }, aOnly.out.report.warnings.toString())
        val g = dev.muisc.transitions.core.Splice.GUARD_FRAMES
        val aEnd = g + Math.round(3.6 * 2.0 * RecipeRenderTestSupport.SR).toInt()
        val fade = RecipeRenderer.DECLICK_FRAMES
        val dry = aOnly.input.aAudio[0]
        val aOff = aOnly.plan.aExitOffset
        var checked = 0
        for (i in 0 until fade) {
            val frame = aEnd - fade + i
            val d = dry[aOff + frame]
            if (abs(d) < 0.01f) continue
            assertEquals(0.5 * (fade - 1 - i) / fade, (aOnly.out.audio[0][frame] / d).toDouble(), 1e-3, "A's declick at frame $i of $fade")
            checked++
        }
        assertTrue(checked > 100, "enough samples compared ($checked)")
        assertTrue((aEnd until aEnd + 4096).all { aOnly.out.audio[0][it] == 0f }, "nothing of A after the overlap")
    }

    /** A reverb left frozen to the very end is released over the last bar, and nothing of it reaches the post-roll. */
    @Test
    fun effectTailsAreReleasedBeforeThePostRoll() {
        val pair = RecipeRenderTestSupport.near
        val rec = recipe(
            "frozen-forever", RecipeTempo.NONE,
            DeckRecipe(
                level = listOf(pt(2.75, 1), pt(3, 0)),
                reverb = ReverbRecipe(send = listOf(pt(2, 0), pt(2.5, 1), pt(3, 1), pt(3.25, 0)), freeze = listOf(pt(3.3, 0, RecipeCurve.STEP), pt(3.35, 1))),
            ),
            DeckRecipe(level = listOf(pt(3, 0, RecipeCurve.EQUAL_POWER), pt(4, 1))),
            length = 4, hold = 2,
        )
        val aOnly = render(rec, pair, silenceB = true)
        assertContract("frozen forever", aOnly, boundaryClean = false)
        assertTrue(aOnly.out.report.warnings.any { it.startsWith("boundary rule: a.reverb.freeze is still on in the last bar") }, aOnly.out.report.warnings.toString())
        val audio = aOnly.out.audio
        val end = aOnly.plan.expectedOutputFrames - dev.muisc.transitions.core.Splice.GUARD_FRAMES
        val frozen = T.rmsDb(audio, RecipeRenderTestSupport.outFrame(aOnly, pair, rec, 4.0), RecipeRenderTestSupport.outFrame(aOnly, pair, rec, 4.5))
        val lastMs = T.rmsDb(audio, end - 441L, end.toLong())
        assertTrue(frozen > -40.0 && lastMs < frozen - 40.0, "the frozen tail (${"%.1f".format(frozen)} dBFS) is released to ${"%.1f".format(lastMs)} dBFS by the post-roll")
        assertTrue((end until audio.frames).all { audio[0][it] == 0f && audio[1][it] == 0f }, "nothing of A's reverb in the post-roll")
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
