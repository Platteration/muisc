package dev.muisc.transitions.recipe

import dev.muisc.transitions.recipe.RecipeRenderTestSupport.T
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.assertContract
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.e
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.fadeInB
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.fadeOutA
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.outFrame
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.pt
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.recipe
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What each processing block of a recipe does to the audio: EQ, filters, echo and reverb (with freeze). */
class RecipeRenderEffectsTest {
    private fun fmt(v: Double) = "%.1f".format(v)

    @Test
    fun eqBassHandoverSwapsTheLowBandAtTheSwapBar() {
        val pair = RecipeRenderTestSupport.near
        val rec = recipe(
            "eq-swap", RecipeTempo.MATCH,
            DeckRecipe(level = fadeOutA(), low = listOf(pt(4, 0), pt("4 + beat", "off"))),
            DeckRecipe(level = fadeInB(), low = listOf(pt(4, "off"), pt("4 + beat", 0))),
        )
        val full = render(rec, pair)
        assertContract("eq swap", full)
        T.assertBitIdentical(full.out, RecipeRenderTestSupport.reRender(rec, full, pair), "eq swap")
        val beats = T.masterBeatFrames(full.out)
        assertTrue(full.out.markers.any { it.label == "a.low off" && it.frame == beats[17] }, "marker where A's low band is gone: ${full.out.markers}")
        val aOnly = render(rec, pair, silenceB = true).out
        val bOnly = render(rec, pair, silenceA = true).out
        val cutoff = 120.0
        val aBefore = T.bandRmsDb(aOnly.audio, beats[12], beats[16], cutoff)
        val aAfter = T.bandRmsDb(aOnly.audio, beats[20], beats[24], cutoff)
        val bBefore = T.bandRmsDb(bOnly.audio, beats[12], beats[16], cutoff)
        val bAfter = T.bandRmsDb(bOnly.audio, beats[20], beats[24], cutoff)
        assertTrue(aAfter < aBefore - 20.0, "A's lows drop ${fmt(aBefore - aAfter)} dB after the swap (${fmt(aBefore)} -> ${fmt(aAfter)} dBFS)")
        assertTrue(bAfter > bBefore + 20.0, "B's lows rise ${fmt(bAfter - bBefore)} dB after the swap (${fmt(bBefore)} -> ${fmt(bAfter)} dBFS)")
        // The high band only follows the equal-power fade: no swap there.
        val aHighBefore = T.bandRmsDb(aOnly.audio, beats[12], beats[16], 2000.0, lowPass = false)
        val aHighAfter = T.bandRmsDb(aOnly.audio, beats[20], beats[24], 2000.0, lowPass = false)
        assertTrue(aHighBefore - aHighAfter in 2.5..6.5, "A's highs only follow the crossfade (${fmt(aHighBefore - aHighAfter)} dB)")
    }

    @Test
    fun highPassSweepLiftsTheLowBandOutMonotonically() {
        val pair = RecipeRenderTestSupport.near
        val rec = recipe(
            "hpf-sweep", RecipeTempo.NONE,
            DeckRecipe(hpf = listOf(pt(0, 20), pt(8, 4000)), resonance = e(0.707), level = listOf(pt(7, 1, RecipeCurve.EQUAL_POWER), pt(8, 0))),
            DeckRecipe(level = listOf(pt(6, 0, RecipeCurve.EQUAL_POWER), pt(8, 1))),
            length = 8, hold = 1,
        )
        val full = render(rec, pair)
        assertContract("hpf sweep", full)
        T.assertBitIdentical(full.out, RecipeRenderTestSupport.reRender(rec, full, pair), "hpf sweep")
        assertTrue(full.plan.lanes.any { it.id == "a.hpf" }, "the cutoff lane is published for the Lab")
        val aOnly = render(rec, pair, silenceB = true)
        val aOff = aOnly.plan.aExitOffset.toLong()
        // In `none` mode A is unstretched: output frame f is A's window frame aExitOffset + f.
        val attenuation = (0 until 7).map { bar ->
            val from = outFrame(aOnly, pair, rec, bar + 0.1)
            val to = outFrame(aOnly, pair, rec, bar + 0.9)
            T.bandRmsDb(aOnly.input.aAudio, aOff + from, aOff + to, 150.0) - T.bandRmsDb(aOnly.out.audio, from, to, 150.0)
        }
        assertTrue(attenuation.zipWithNext().all { (x, y) -> y >= x - 0.25 }, "A's low band falls monotonically: attenuation per bar ${attenuation.map { fmt(it) }}")
        assertTrue(attenuation.first() < 3.0, "the sweep starts almost open (${fmt(attenuation.first())} dB)")
        assertTrue(attenuation.last() > 20.0, "and ends with A's lows gone (${fmt(attenuation.last())} dB)")
    }

    @Test
    fun echoSendRingsOnAfterALevelCut() {
        val pair = RecipeRenderTestSupport.near
        fun rec(withEcho: Boolean) = recipe(
            if (withEcho) "echo-out" else "cut-only", RecipeTempo.NONE,
            DeckRecipe(
                level = listOf(pt(3.75, 1), pt(4, 0)),
                echo = if (withEcho) EchoRecipe(send = listOf(pt(3, 0), pt(3.5, 1), pt(4.5, 1), pt(4.75, 0)), beats = e(0.75), feedback = e(0.6)) else null,
            ),
            DeckRecipe(level = listOf(pt(6, 0, RecipeCurve.EQUAL_POWER), pt(8, 1))),
            length = 8, hold = 1,
        )
        val echo = rec(true)
        val full = render(echo, pair)
        assertContract("echo", full)
        T.assertBitIdentical(full.out, RecipeRenderTestSupport.reRender(echo, full, pair), "echo")
        val wet = render(echo, pair, silenceB = true)
        val dry = render(rec(false), pair, silenceB = true)
        fun rms(r: RecipeRenderTestSupport.Rendered, from: Double, to: Double) = T.rmsDb(r.out.audio, outFrame(r, pair, echo, from), outFrame(r, pair, echo, to))
        val before = rms(wet, 3.0, 3.5)
        val justAfter = rms(wet, 4.75, 5.0)
        val later = rms(wet, 5.25, 5.5)
        val muchLater = rms(wet, 6.5, 7.0)
        assertTrue(rms(dry, 4.75, 5.0) < -90.0, "without the echo A is silent after the cut (${fmt(rms(dry, 4.75, 5.0))} dBFS)")
        assertTrue(justAfter > before - 20.0, "the tail is audible after the cut: ${fmt(justAfter)} dBFS vs ${fmt(before)} dBFS before it")
        assertTrue(later < justAfter - 3.0 && muchLater < later - 10.0, "and it decays: ${fmt(justAfter)} -> ${fmt(later)} -> ${fmt(muchLater)} dBFS")
        // Before the send opens the echo adds nothing: bars 1..2 are A untouched.
        val a1 = outFrame(wet, pair, echo, 1.0).toInt()
        var maxDiff = 0f
        for (i in 0 until 8192) maxDiff = maxOf(maxDiff, kotlin.math.abs(wet.out.audio[0][a1 + i] - dry.out.audio[0][a1 + i]))
        assertEquals(0f, maxDiff, "no echo before the send opens")
    }

    @Test
    fun reverbFreezeSustainsTheTailWhileFrozen() {
        val pair = RecipeRenderTestSupport.near
        fun rec(freeze: Boolean) = recipe(
            if (freeze) "freeze" else "no-freeze", RecipeTempo.NONE,
            DeckRecipe(
                level = listOf(pt(3.75, 1), pt(4, 0)),
                reverb = ReverbRecipe(
                    send = listOf(pt(2.5, 0), pt(3, 1), pt(4, 1), pt(4.25, 0)),
                    freeze = if (freeze) listOf(pt(4.3, 0, RecipeCurve.STEP), pt(4.35, 1, RecipeCurve.STEP), pt(7, 0)) else emptyList(),
                    decaySec = e(1.5),
                ),
            ),
            DeckRecipe(level = listOf(pt(6, 0, RecipeCurve.EQUAL_POWER), pt(8, 1))),
            length = 8, hold = 1,
        )
        val frozenRec = rec(true)
        val full = render(frozenRec, pair)
        assertContract("freeze", full)
        T.assertBitIdentical(full.out, RecipeRenderTestSupport.reRender(frozenRec, full, pair), "freeze")
        val frozen = render(frozenRec, pair, silenceB = true)
        val free = render(rec(false), pair, silenceB = true)
        fun rms(r: RecipeRenderTestSupport.Rendered, from: Double, to: Double) = T.rmsDb(r.out.audio, outFrame(r, pair, frozenRec, from), outFrame(r, pair, frozenRec, to))
        val early = rms(frozen, 4.5, 5.0)
        val late = rms(frozen, 6.5, 7.0)
        assertTrue(early > -40.0, "the reverb tail is there after A's cut (${fmt(early)} dBFS)")
        assertTrue(late > early - 3.0, "frozen: the tail sustains from bar 4.5 to bar 7 (${fmt(early)} -> ${fmt(late)} dBFS)")
        val freeEarly = rms(free, 4.5, 5.0)
        val freeLate = rms(free, 6.5, 7.0)
        assertTrue(freeLate < freeEarly - 20.0, "without the freeze the same tail dies (${fmt(freeEarly)} -> ${fmt(freeLate)} dBFS)")
        val released = rms(frozen, 7.75, 8.0)
        assertTrue(released < late - 6.0, "after the freeze is released the tail decays (${fmt(late)} -> ${fmt(released)} dBFS)")
    }
}
