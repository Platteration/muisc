package dev.muisc.transitions.recipe

import dev.muisc.transitions.StemNeed
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.T
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.assertContract
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.pt
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.recipe
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stem lanes: B's drums replace A's at bar 2, the bass follows at bar 5 (a stem swap written as a recipe). */
class RecipeRenderStemsTest {
    private fun fmt(v: Double) = "%.1f".format(v)

    private val rec = recipe(
        "drums-first", RecipeTempo.MATCH,
        DeckRecipe(
            level = listOf(pt(6, 1, RecipeCurve.EQUAL_POWER), pt(8, 0)),
            stems = StemsRecipe(drums = listOf(pt(2, 0), pt("2 + beat", "off")), bass = listOf(pt(5, 0), pt("5 + beat", "off"))),
        ),
        DeckRecipe(
            level = listOf(pt(0, 0, RecipeCurve.EQUAL_POWER), pt(1, 1)),
            stems = StemsRecipe(drums = listOf(pt(2, "off"), pt("2 + beat", 0)), bass = listOf(pt(5, "off"), pt("5 + beat", 0))),
        ),
    )

    @Test
    fun drumsAreHandedOverFirst() {
        val pair = RecipeRenderTestSupport.near
        val full = render(rec, pair, stems = true)
        assertEquals(StemNeed.BOTH, full.plan.stemNeed)
        assertTrue(full.plan.lanes.map { it.id }.containsAll(listOf("a.stems.drums", "a.stems.bass", "b.stems.drums", "b.stems.bass")))
        assertTrue(RecipeStrategy(rec).needsStems)
        assertContract("stems", full)
        T.assertBitIdentical(full.out, RecipeRenderTestSupport.reRender(rec, full, pair), "stems")

        val aOnly = render(rec, pair, stems = true, silenceB = true).out
        val bOnly = render(rec, pair, stems = true, silenceA = true).out
        val beats = T.masterBeatFrames(full.out)
        // Hats (6 kHz and up) are drums: A's go at bar 2, B's arrive at bar 2.
        val aHatBefore = T.bandRmsDb(aOnly.audio, beats[4], beats[8], 6000.0, lowPass = false)
        val aHatAfter = T.bandRmsDb(aOnly.audio, beats[12], beats[16], 6000.0, lowPass = false)
        val bHatBefore = T.bandRmsDb(bOnly.audio, beats[4], beats[8], 6000.0, lowPass = false)
        val bHatAfter = T.bandRmsDb(bOnly.audio, beats[12], beats[16], 6000.0, lowPass = false)
        assertTrue(aHatAfter < aHatBefore - 10.0, "A's hats leave with its drums: ${fmt(aHatBefore)} -> ${fmt(aHatAfter)} dBFS")
        assertTrue(bHatAfter > bHatBefore + 10.0, "B's hats arrive with its drums: ${fmt(bHatBefore)} -> ${fmt(bHatAfter)} dBFS")
        // The rest of A (its harmonic content) is still playing between the drum swap and the bass swap.
        val aBefore = T.rmsDb(aOnly.audio, beats[4], beats[8])
        val aAfter = T.rmsDb(aOnly.audio, beats[12], beats[16])
        assertTrue(aAfter > aBefore - 10.0 && aBefore - aAfter < aHatBefore - aHatAfter - 3.0, "only the drums left A: full band ${fmt(aBefore)} -> ${fmt(aAfter)} dBFS")
        // Then the bass: A's lows go at bar 5 while B's come in.
        val aLowMid = T.bandRmsDb(aOnly.audio, beats[12], beats[20], 120.0)
        val aLowLate = T.bandRmsDb(aOnly.audio, beats[21], beats[24], 120.0)
        val bLowMid = T.bandRmsDb(bOnly.audio, beats[12], beats[20], 120.0)
        val bLowLate = T.bandRmsDb(bOnly.audio, beats[21], beats[24], 120.0)
        assertTrue(aLowLate < aLowMid - 10.0, "A's bass leaves at bar 5: ${fmt(aLowMid)} -> ${fmt(aLowLate)} dBFS")
        assertTrue(bLowLate > bLowMid + 6.0, "B's bass arrives at bar 5: ${fmt(bLowMid)} -> ${fmt(bLowLate)} dBFS")
    }
}
