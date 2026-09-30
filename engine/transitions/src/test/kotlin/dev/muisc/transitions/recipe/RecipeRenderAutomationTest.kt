package dev.muisc.transitions.recipe

import dev.muisc.transitions.recipe.RecipeRenderTestSupport.T
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.assertContract
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.outFrame
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.pt
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.recipe
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.render
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** How lanes are turned into audio: where a step lands, and that a lane at its neutral value leaves the deck untouched. */
class RecipeRenderAutomationTest {
    private val sr = RecipeRenderTestSupport.SR
    private fun fmt(v: Double) = "%.1f".format(v)

    /**
     * A `step` cut of A on the downbeat of bar 3 and a `step` entry of B on the downbeat of bar 2: each ramp (~30 ms)
     * ENDS on its bar, so A is already silent when its bar-3 downbeat arrives and B is at full level for its bar-2
     * downbeat. (A ramp centred on the bar would cut A's downbeat transient short — a tick.)
     */
    @Test
    fun stepsAreCompleteOnTheirBar() {
        val pair = RecipeRenderTestSupport.near
        val rec = recipe(
            "steps", RecipeTempo.NONE,
            DeckRecipe(level = listOf(pt(0, 1, RecipeCurve.STEP), pt(3, 0))),
            DeckRecipe(level = listOf(pt(0, 0, RecipeCurve.STEP), pt(2, 1))),
            length = 4, hold = 1,
        )
        val full = render(rec, pair)
        assertContract("steps", full)
        val ms = sr / 1000.0
        val aOnly = render(rec, pair, silenceB = true)
        val cut = outFrame(aOnly, pair, rec, 3.0)
        val aBefore = T.rmsDb(aOnly.out.audio, cut - Math.round(200 * ms), cut - Math.round(40 * ms))
        val aAfter = T.rmsDb(aOnly.out.audio, cut, cut + Math.round(20 * ms))
        assertTrue(aBefore > -40.0 && aAfter < -120.0, "A plays until ~30 ms before bar 3 (${fmt(aBefore)} dBFS) and is silent from bar 3 on (${fmt(aAfter)} dBFS)")
        val bOnly = render(rec, pair, silenceA = true)
        val entry = outFrame(bOnly, pair, rec, 2.0)
        val bOff = bOnly.plan.bEntryOffset.toLong() - bOnly.plan.expectedOutputFrames
        // In `none` mode B is unstretched: output frame f is B's window frame bEntryOffset - expectedOutputFrames + f.
        val bRendered = T.rmsDb(bOnly.out.audio, entry, entry + Math.round(20 * ms))
        val bDry = T.rmsDb(bOnly.input.bAudio, bOff + entry, bOff + entry + Math.round(20 * ms))
        assertTrue(abs(bRendered - bDry) < 0.01, "B is at full level from its bar-2 downbeat on (${fmt(bRendered)} vs dry ${fmt(bDry)} dBFS)")
        assertTrue(T.rmsDb(bOnly.out.audio, entry - Math.round(60 * ms), entry - Math.round(35 * ms)) < -120.0, "and silent until ~30 ms before it")
    }

    /** A filter parked at its neutral cutoff is bypassed exactly, not merely "nearly flat". */
    @Test
    fun parkedFiltersAreTransparent() {
        val pair = RecipeRenderTestSupport.near
        val rec = recipe(
            "open-up", RecipeTempo.NONE,
            DeckRecipe(level = listOf(pt(0, 1, RecipeCurve.EQUAL_POWER), pt(4, 0))),
            // B at exactly half level until bar 7.5 (x0.5 is exact in floating point, and keeps the true-peak limiter,
            // which is not bit-transparent while it releases, away from the bars compared below).
            DeckRecipe(level = listOf(pt(0, 0, RecipeCurve.EQUAL_POWER), pt(2, 0.5), pt(7.5, 0.5), pt(8, 1)), lpf = listOf(pt(0, 400), pt(3, 20000)), hpf = listOf(pt(4, 20), pt(5, 300), pt(6, 20)), resonance = RecipeRenderTestSupport.e(1)),
            length = 4, hold = 4,
        )
        val bOnly = render(rec, pair, silenceA = true)
        assertContract("open-up", bOnly)
        val bOff = bOnly.plan.bEntryOffset.toLong() - bOnly.plan.expectedOutputFrames
        fun maxDiff(fromBar: Double, toBar: Double): Float {
            val from = outFrame(bOnly, pair, rec, fromBar).toInt()
            val to = outFrame(bOnly, pair, rec, toBar).toInt()
            var m = 0f
            for (c in 0 until 2) for (i in from until to) m = maxOf(m, abs(bOnly.out.audio[c][i] - 0.5f * bOnly.input.bAudio[c][(bOff + i).toInt()]))
            return m
        }
        // Bars 3.1..3.9: the low-pass is back at 20 kHz, the high-pass has not moved yet; bars 6.1..7.4: both parked.
        assertTrue(maxDiff(3.1, 3.9) == 0f, "B untouched between the two filter moves (max diff ${maxDiff(3.1, 3.9)})")
        assertTrue(maxDiff(6.1, 7.4) == 0f, "B untouched after the high-pass is parked again (max diff ${maxDiff(6.1, 7.4)})")
        assertTrue(maxDiff(4.9, 5.1) > 1e-3f, "while the high-pass is up B is filtered")
    }
}
