package dev.muisc.transitions.recipe

import dev.muisc.transitions.recipe.RecipeRenderTestSupport.assertContract
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.e
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.render
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Twenty seeded random recipes — random lanes, curves, EQ, filters, echo, reverb and freeze on both decks, but
 * respecting the boundary rule — in all three tempo modes: every one renders with finite samples, no clicks, a clean
 * splice and no boundary warning.
 */
class RecipeRenderPropertyTest {
    private class Gen(seed: Long) {
        val rnd = Random(seed)
        fun uni(lo: Double, hi: Double) = lo + (hi - lo) * rnd.nextDouble()
        fun logUni(lo: Double, hi: Double) = exp(uni(ln(lo), ln(hi)))
        fun curve(): RecipeCurve = RecipeCurve.entries[rnd.nextInt(RecipeCurve.entries.size)]
        fun chance(p: Double) = rnd.nextDouble() < p

        /** Sorted random bars strictly inside (from, to), rounded to 1/16 bar. */
        fun bars(count: Int, from: Double, to: Double): List<Double> =
            List(count) { Math.round(uni(from, to) * 16.0) / 16.0 }.filter { it > from && it < to }.sorted()

        /** A lane from [startBar] at [start] through random values to [end] at [endBar] (and held after). */
        fun lane(startBar: Double, start: Double, endBar: Double, end: Double, value: () -> Double): List<RecipePoint> {
            val out = ArrayList<RecipePoint>()
            out += RecipePoint(e(startBar), e(start), curve())
            for (b in bars(rnd.nextInt(4), startBar, endBar)) out += RecipePoint(e(b), e(value()), curve())
            out += RecipePoint(e(endBar), e(end), curve())
            return out
        }

        fun db(): Double = if (chance(0.25)) RecipeResolver.OFF_DB else Math.round(uni(-24.0, 6.0)).toDouble()

        /** Lanes for deck A: neutral at bar 0, level gone by the end of the overlap, sends closed a bar before the end. */
        fun deckA(length: Double, total: Double): DeckRecipe {
            val endA = Math.round(uni(length / 2, length) * 4.0) / 4.0
            val lastSend = total - 1.25
            return DeckRecipe(
                level = lane(0.0, 1.0, endA, 0.0) { uni(0.0, 1.0) },
                low = if (chance(0.4)) lane(0.0, 0.0, uni(1.0, length), db()) { db() } else emptyList(),
                mid = if (chance(0.3)) lane(0.0, 0.0, uni(1.0, length), db()) { db() } else emptyList(),
                high = if (chance(0.3)) lane(0.0, 0.0, uni(1.0, length), db()) { db() } else emptyList(),
                hpf = if (chance(0.3)) lane(0.0, 20.0, uni(1.0, length), logUni(20.0, 8000.0)) { logUni(20.0, 8000.0) } else emptyList(),
                lpf = if (chance(0.3)) lane(0.0, 20000.0, uni(1.0, length), logUni(200.0, 20000.0)) { logUni(200.0, 20000.0) } else emptyList(),
                resonance = e(uni(0.5, 4.0)),
                echo = if (chance(0.35)) echo(0.0, lastSend) else null,
                reverb = if (chance(0.35)) reverb(0.0, lastSend) else null,
            )
        }

        /** Lanes for deck B: anything at bar 0, level at 1 and everything neutral by `total`, sends closed a bar before. */
        fun deckB(length: Double, total: Double): DeckRecipe {
            val startB = Math.round(uni(0.0, length / 2) * 4.0) / 4.0
            val endB = Math.round(uni(length / 2 + 0.5, total) * 4.0) / 4.0
            val lastSend = total - 1.25
            return DeckRecipe(
                level = listOf(RecipePoint(e(0), e(0))) + lane(startB, 0.0, endB, 1.0) { uni(0.0, 1.0) },
                low = if (chance(0.4)) lane(0.0, db(), uni(1.0, total), 0.0) { db() } else emptyList(),
                mid = if (chance(0.3)) lane(0.0, db(), uni(1.0, total), 0.0) { db() } else emptyList(),
                high = if (chance(0.3)) lane(0.0, db(), uni(1.0, total), 0.0) { db() } else emptyList(),
                hpf = if (chance(0.3)) lane(0.0, logUni(20.0, 2000.0), uni(1.0, total), 20.0) { logUni(20.0, 2000.0) } else emptyList(),
                lpf = if (chance(0.3)) lane(0.0, logUni(200.0, 20000.0), uni(1.0, total), 20000.0) { logUni(200.0, 20000.0) } else emptyList(),
                resonance = e(uni(0.5, 4.0)),
                echo = if (chance(0.3)) echo(0.5, lastSend) else null,
                reverb = if (chance(0.3)) reverb(0.5, lastSend) else null,
            )
        }

        fun sendLane(from: Double, lastSend: Double): List<RecipePoint> {
            val close = uni(from + 0.5, lastSend)
            return listOf(RecipePoint(e(from), e(0), curve())) + bars(1 + rnd.nextInt(3), from, close).map { RecipePoint(e(it), e(uni(0.0, 1.0)), curve()) } + RecipePoint(e(close), e(0))
        }

        fun echo(from: Double, lastSend: Double) = EchoRecipe(
            send = sendLane(from, lastSend), beats = e(listOf(0.25, 0.5, 0.75, 1.0)[rnd.nextInt(4)]),
            feedback = e(uni(0.3, 0.85)), dampHz = e(logUni(1000.0, 12000.0)), returnLevel = e(uni(0.4, 1.2)),
        )

        fun reverb(from: Double, lastSend: Double): ReverbRecipe {
            val freeze = if (chance(0.5)) {
                val on = uni(from + 0.5, lastSend - 0.5)
                val off = uni(on + 0.25, lastSend)
                listOf(RecipePoint(e(on), e(0), RecipeCurve.STEP), RecipePoint(e(on + 0.01), e(1), RecipeCurve.STEP), RecipePoint(e(off), e(0)))
            } else emptyList()
            return ReverbRecipe(send = sendLane(from, lastSend), freeze = freeze, decaySec = e(uni(0.5, 4.0)), dampHz = e(logUni(2000.0, 16000.0)), returnLevel = e(uni(0.4, 1.2)))
        }

        fun recipe(i: Int): TransitionRecipe {
            val tempo = RecipeTempo.entries[i % 3]
            val length = (4 + rnd.nextInt(5)).toDouble()
            val settle = rnd.nextInt(3).toDouble()
            val hold = (1 + rnd.nextInt(2)).toDouble()
            val bEntersAt = if (tempo == RecipeTempo.NONE) rnd.nextInt(length.toInt() + 1).toDouble() else 0.0
            val total = if (tempo == RecipeTempo.NONE) maxOf(length, bEntersAt) + hold else length + settle + hold
            return TransitionRecipe(
                id = "random-$i", name = "random $i",
                timing = RecipeTiming(
                    lengthBars = e(length), tempo = tempo, settleBars = e(settle), holdBars = e(hold), bEntersAtBar = e(bEntersAt),
                    align = if (chance(0.5)) RecipeAlign.PHRASE else RecipeAlign.DOWNBEAT,
                ),
                a = deckA(length, total), b = deckB(length, total),
            )
        }
    }

    @Test
    fun randomRecipesRenderCleanlyInEveryTempoMode() {
        val gen = Gen(20260930L)
        val failures = ArrayList<String>()
        for (i in 0 until 20) {
            val rec = gen.recipe(i)
            val pair = when (rec.timing.tempo) {
                RecipeTempo.MATCH -> RecipeRenderTestSupport.near
                RecipeTempo.GLIDE -> RecipeRenderTestSupport.glidePair
                RecipeTempo.NONE -> if (i % 2 == 0) RecipeRenderTestSupport.far else RecipeRenderTestSupport.near
            }
            try {
                assertContract("recipe $i (${rec.timing.tempo})", render(rec, pair))
            } catch (t: Throwable) {
                failures += "recipe $i: ${t.message}\n${RecipeFormat.encode(rec)}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n\n"))
    }
}
