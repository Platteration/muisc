package dev.muisc.transitions.live

import dev.muisc.transitions.FadeLaw
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Contract of the [LivePlanBuilders]: structurally valid plans for any arguments, and a transportable plan. */
class LivePlanBuildersTest {
    private val sr = 44100

    @Test
    fun everyBuilderProducesAStructurallyValidPlan() {
        val total = 10L * sr
        val plans = listOf(
            LivePlanBuilders.crossfade(5L * sr, total, 1000L, (1.5 * sr).toInt()),
            LivePlanBuilders.phraseCut(5L * sr, total, 1000L, 706),
            LivePlanBuilders.bassSwap(2L * sr, total, 1000L, 4 * sr, 2 * sr, 22050, 0.99, 3 * sr),
            LivePlanBuilders.filterSweep(2L * sr, total, 1000L, 4 * sr, sr),
            LivePlanBuilders.echoOut(2L * sr, total, 1000L, sr, 16538, 3 * sr),
        )
        assertEquals(LivePlanBuilders.KINDS, plans.map { it.kind }.toSet(), "one builder per kind")
        for (p in plans) {
            assertEquals(emptyList(), LivePlanBuilders.problems(p), p.kind)
            assertTrue(p.outputFrames > 0 && p.aToFrame in p.aFromFrame..total, p.kind)
            assertEquals(p.bFromFrame + p.framesConsumedFromB(), p.bExitFrame(), p.kind)
            val gains = p.nodes.filterIsInstance<LiveNode.Gain>()
            assertEquals(setOf(Deck.A, Deck.B), gains.map { it.deck }.toSet(), "${p.kind} drives both decks")
            assertTrue(gains.all { g -> g.points.all { it.frame in 0..p.outputFrames } }, "${p.kind} lanes inside the segment")
            assertEquals(0f, gains.first { it.deck == Deck.B }.points.first().value, "${p.kind}: B starts silent")
        }
    }

    @Test
    fun absurdArgumentsAreClampedRatherThanThrown() {
        val total = 1000L
        // a segment longer than what is left of A: aToFrame stops at the track end, the nodes stay inside the segment
        val swap = LivePlanBuilders.bassSwap(900L, total, 0L, 44100, swapAtFrame = -50_000, swapFrames = 10_000_000, ratio = 0.99, settleFrame = 99_999_999)
        assertEquals(emptyList(), LivePlanBuilders.problems(swap))
        assertEquals(total, swap.aToFrame)
        val low = swap.nodes.filterIsInstance<LiveNode.LowSwap>().single()
        assertEquals(0, low.atFrame)
        assertEquals(44100, low.atFrame + low.swapFrames)
        assertEquals(44100, swap.nodes.filterIsInstance<LiveNode.Rate>().single().settleFrame)
        // a zero-length fade still yields a playable one-frame segment
        val tiny = LivePlanBuilders.crossfade(0L, total, 0L, 0)
        assertEquals(1, tiny.outputFrames)
        assertEquals(emptyList(), LivePlanBuilders.problems(tiny))
        // the sweep keeps its corners under Nyquist even when asked for more
        val sweep = LivePlanBuilders.filterSweep(0L, total, 0L, 4410, sr, aHpFromHz = 0.0, aHpToHz = 1e9, bLpFromHz = 0.0, bLpToHz = 1e9)
        assertEquals(emptyList(), LivePlanBuilders.problems(sweep))
        assertTrue(sweep.nodes.filterIsInstance<LiveNode.Sweep>().all { it.fromHz >= 1.0 && it.toHz <= 0.45 * sr })
    }

    @Test
    fun echoTailIsLongEnoughForTheFeedbackToDecay() {
        // 0.6^k <= -60 dB for k = 14, so a 3/16 echo needs 14 repeats of room
        assertEquals(14, LivePlanBuilders.echoesToFloor(0.6f))
        assertEquals(14 * 1000, LivePlanBuilders.echoTailFrames(1000, 0.6f))
        assertEquals(1, LivePlanBuilders.echoesToFloor(0f))
        assertEquals(1, LivePlanBuilders.echoesToFloor(1f))
        assertTrue(LivePlanBuilders.echoesToFloor(0.9f) > LivePlanBuilders.echoesToFloor(0.5f), "more feedback rings longer")
    }

    @Test
    fun equalPowerGainsArePowerComplementary() {
        val (a, b) = LivePlanBuilders.equalPowerGains(0, 1000).let { it[0] to it[1] }
        assertEquals(Deck.A, a.deck); assertEquals(Deck.B, b.deck)
        assertTrue(a.points.first().shape == FadeLaw.EQUAL_POWER && b.points.first().shape == FadeLaw.EQUAL_POWER)
        val plan = LivePlan("crossfade", 0L, 1000L, 0L, 1000, listOf(a, b))
        val ga = LiveOffline.gains(plan, Deck.A, 1000)
        val gb = LiveOffline.gains(plan, Deck.B, 1000)
        for (i in 0 until 1000) {
            val power = ga[i] * ga[i] + gb[i] * gb[i]
            assertTrue(abs(power - 1f) < 1e-5f, "constant power at $i: $power")
        }
    }

    @Test
    fun aPlanSurvivesJsonRoundTrip() {
        val plan = LivePlanBuilders.bassSwap(1000L, 10_000L, 500L, 4000, 2000, 500, 1.01, 3000)
        val json = Json.encodeToString(LivePlan.serializer(), plan)
        val back = Json.decodeFromString(LivePlan.serializer(), json)
        assertEquals(plan, back)
        assertEquals(plan.nodes, back.nodes)
        assertEquals(plan.bExitFrame(), back.bExitFrame())
    }
}
