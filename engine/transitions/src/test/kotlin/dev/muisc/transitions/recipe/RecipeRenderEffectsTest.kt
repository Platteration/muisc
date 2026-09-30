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
        fun rec(withEcho: Boolean, sendClosesAt: Double = 4.5) = recipe(
            if (withEcho) "echo-out" else "cut-only", RecipeTempo.NONE,
            DeckRecipe(
                level = listOf(pt(3.75, 1), pt(4, 0)),
                echo = if (withEcho) EchoRecipe(send = listOf(pt(3, 0), pt(3.5, 1), pt(sendClosesAt, 1), pt(sendClosesAt + 0.25, 0)), beats = e(0.75), feedback = e(0.6)) else null,
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
        // The send is taken before the fader: while A's fader is down (bar 4 on) the open send keeps feeding the
        // echo, so a send that stays open half a bar longer leaves a clearly louder tail.
        val early = render(rec(true, sendClosesAt = 3.75), pair, silenceB = true)
        val earlyTail = rms(early, 5.0, 5.5)
        val lateTail = rms(wet, 5.0, 5.5)
        assertTrue(lateTail > earlyTail + 3.0, "pre-fader send: ${fmt(lateTail)} dBFS with the send open after the cut vs ${fmt(earlyTail)} dBFS closed at it")
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

    /**
     * The hard case for a freeze: it engages while the send is wide open and A (pre-fader) is still feeding it, and it
     * is released on a loud, dark (500 Hz damping) tail at double return level, with nothing else playing. Neither
     * moment may click.
     */
    @Test
    fun freezeEngagesAndReleasesWithoutClicks() {
        val pair = RecipeRenderTestSupport.near
        val rec = recipe(
            "hard-freeze", RecipeTempo.NONE,
            DeckRecipe(
                level = listOf(pt(4, 1), pt(4.25, 0)),
                reverb = ReverbRecipe(
                    send = listOf(pt(3, 0), pt(3.25, 1), pt(5, 1), pt(5.25, 0)),
                    freeze = listOf(pt(4.3, 0, RecipeCurve.STEP), pt(4.3, 1), pt(6, 1, RecipeCurve.STEP), pt(6, 0)),
                    decaySec = e(3), dampHz = e(500), returnLevel = e(2),
                ),
            ),
            DeckRecipe(level = listOf(pt(7, 0, RecipeCurve.EQUAL_POWER), pt(8, 1))),
            length = 8, hold = 1,
        )
        val aOnly = render(rec, pair, silenceB = true)
        assertContract("hard freeze", aOnly)
        fun rms(from: Double, to: Double) = T.rmsDb(aOnly.out.audio, outFrame(aOnly, pair, rec, from), outFrame(aOnly, pair, rec, to))
        assertTrue(rms(5.0, 5.9) > -30.0, "the frozen tail is loud (${fmt(rms(5.0, 5.9))} dBFS)")
        assertContract("hard freeze, both decks", render(rec, pair))
    }

    /**
     * Found by the random-recipe property test (seed 1, recipe 10; values rounded to 4 decimals): B's reverb, fed
     * pre-fader while B's fader is still down, is frozen from bar 1.34 and released at bar 2.89 while almost nothing
     * else plays. Un-freezing an FdnReverb in place restarts its damping filters from the state they had when the
     * freeze began, and that step comes out of the delay lines as clicks; the renderer instead lets the frozen reverb
     * fade at its RT60 and starts a fresh one for the send.
     */
    @Test
    fun freezeReleasedOnAQuietMixDoesNotClick() {
        val rec = RecipeFormat.decode(
            """
            {"id": "quiet-release", "name": "quiet release", "timing": {"lengthBars": 7, "tempo": "glide"},
            "a": {"level": [{"at": 0, "v": 1, "curve": "sCurve"}, {"at": 3.875, "v": 0.8741, "curve": "sCurve"}, {"at": 5.75, "v": 0}], "resonance": 2.4672,
              "echo": {"send": [{"at": 0, "v": 0, "curve": "equalPower"}, {"at": 1.9375, "v": 0.2673, "curve": "equalPower"}, {"at": 2.0625, "v": 0.9216}, {"at": 2.6403, "v": 0}], "beats": 0.5, "feedback": 0.848, "dampHz": 1019.1204, "returnLevel": 0.9366},
              "reverb": {"send": [{"at": 0, "v": 0, "curve": "sCurve"}, {"at": 3.625, "v": 0.0885, "curve": "step"}, {"at": 5.0321, "v": 0}], "decaySec": 1.4107, "dampHz": 3942.875, "returnLevel": 0.4182}},
            "b": {"level": [{"at": 0, "v": 0}, {"at": 2.25, "v": 0, "curve": "sCurve"}, {"at": 7.875, "v": 0.6777}, {"at": 8.25, "v": 1, "curve": "exp"}],
              "low": [{"at": 0, "v": 3, "curve": "exp"}, {"at": 9.0386, "v": 0, "curve": "equalPower"}],
              "mid": [{"at": 0, "v": -17, "curve": "equalPower"}, {"at": 2.4375, "v": -6, "curve": "sCurve"}, {"at": 7.1875, "v": -120, "curve": "exp"}, {"at": 9.5625, "v": 2, "curve": "step"}, {"at": 10.6713, "v": 0, "curve": "exp"}], "resonance": 2.9422,
              "reverb": {"send": [{"at": 0.5, "v": 0, "curve": "sCurve"}, {"at": 0.875, "v": 0.6178, "curve": "equalPower"}, {"at": 1.75, "v": 0.322, "curve": "sCurve"}, {"at": 1.9875, "v": 0}], "freeze": [{"at": 1.3308, "v": 0, "curve": "step"}, {"at": 1.3408, "v": 1, "curve": "step"}, {"at": 2.8896, "v": 0}], "decaySec": 3.6898, "dampHz": 3330.4769, "returnLevel": 0.6963}}}
            """.trimIndent(),
        )
        assertContract("quiet release", render(rec, RecipeRenderTestSupport.glidePair))
    }
}
