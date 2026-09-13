package dev.muisc.transitions.live

import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.live.LiveFixtures.SR
import dev.muisc.transitions.live.LiveFixtures.a120
import dev.muisc.transitions.live.LiveFixtures.b121
import dev.muisc.transitions.live.LiveFixtures.b140amb
import dev.muisc.transitions.live.LiveFixtures.b140cold
import dev.muisc.transitions.live.LiveFixtures.barFrame
import dev.muisc.transitions.live.LiveFixtures.prefs
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultLivePlanFactoryTest {
    private val factory = DefaultLivePlanFactory()

    /** Frame-level invariants every plan must satisfy against the tracks it was built for. */
    private fun assertValid(plan: LivePlan, aNow: Long, aTotal: Long, bTotal: Long) {
        assertEquals(emptyList(), LivePlanBuilders.problems(plan), plan.toString())
        val now = aNow.coerceIn(0L, maxOf(0L, aTotal))
        assertTrue(plan.aFromFrame >= now, "aFromFrame ${plan.aFromFrame} before now $now")
        assertTrue(plan.aFromFrame <= maxOf(0L, aTotal) && plan.aToFrame <= maxOf(0L, aTotal), "A range ${plan.aFromFrame}..${plan.aToFrame} outside A ($aTotal)")
        assertEquals(minOf(plan.aFromFrame + plan.outputFrames, maxOf(0L, aTotal)).coerceAtLeast(plan.aFromFrame), plan.aToFrame, "aToFrame")
        assertTrue(plan.bFromFrame in 0L..maxOf(0L, bTotal - 1), "bFromFrame ${plan.bFromFrame} outside B ($bTotal)")
        assertTrue(plan.outputFrames > 0)
        assertEquals(plan.bFromFrame + plan.framesConsumedFromB(), plan.bExitFrame(), "bExitFrame consistent")
        // deck A's lane ends at 0, deck B's at 1
        val ga = plan.nodes.filterIsInstance<LiveNode.Gain>().filter { it.deck == Deck.A }
        val gb = plan.nodes.filterIsInstance<LiveNode.Gain>().filter { it.deck == Deck.B }
        assertTrue(ga.isNotEmpty() && gb.isNotEmpty(), "both decks have a gain lane")
        assertEquals(0f, ga.last().points.last().value, "A ends silent")
        assertEquals(1f, gb.last().points.last().value, "B ends at unity")
        assertEquals(0f, gb.first().points.first().value, "B starts silent")
    }

    private fun bMixInFrame(t: dev.muisc.transitions.synthetic.SyntheticTrack): Long = t.analysis.grid.frameOfBeat(t.analysis.cues.mixInBeat.toDouble())

    @Test
    fun bassSwapWhenBeatMatchableWithinTwoPercent() {
        val f = LiveFixtures.features(a120, b121)
        assertTrue(f.beatMatchable && abs(f.tempoRatio - 1.0) <= 0.02, "fixture is bass-swappable: $f")
        val now = barFrame(a120, 12) + 1000
        val plan = factory.plan(a120.trackRef, b121.trackRef, f, now, prefs)
        assertEquals("bassSwap", plan.kind)
        assertValid(plan, now, a120.analysis.totalFrames, b121.analysis.totalFrames)
        val bar = 4 * 22050
        assertEquals(barFrame(a120, 13), plan.aFromFrame, "starts on A's next downbeat")
        assertEquals(4L * bar, plan.outputFrames.toLong(), "4 bars of A (preferredOverlapBars 16 / 4)")
        assertEquals(bMixInFrame(b121), plan.bFromFrame, "B from its mix-in cue")
        val rate = assertNotNull(plan.nodes.filterIsInstance<LiveNode.Rate>().firstOrNull())
        assertEquals(120.0 / 121.0, rate.ratio, 1e-9, "B slowed to A's tempo")
        assertEquals(3 * bar, rate.settleFrame, "settles at the start of the last bar")
        val swap = assertNotNull(plan.nodes.filterIsInstance<LiveNode.LowSwap>().firstOrNull())
        assertEquals(2 * bar, swap.atFrame, "swap on the downbeat in the middle")
        assertTrue(a120.analysis.grid.isDownbeat(Math.round(a120.analysis.grid.beatAtFrame(plan.aFromFrame + swap.atFrame)).toInt()))
        assertEquals(22050, swap.swapFrames, "one beat")
        assertEquals(200.0, swap.splitHz)
        val gains = plan.nodes.filterIsInstance<LiveNode.Gain>()
        assertEquals(2, gains.size)
        assertTrue(gains.all { it.points.first().shape == FadeLaw.EQUAL_POWER && it.points.last().frame == plan.outputFrames })
        // B consumption follows the ramp: 3 bars at mean ratio (r + 1) / 2 plus 1 bar at 1.0
        val expected = Math.round(3.0 * bar * (rate.ratio + 1.0) / 2.0 + bar)
        assertEquals(expected, plan.framesConsumedFromB())
        assertTrue(plan.framesConsumedFromB() < plan.outputFrames, "B is slowed down so it consumes fewer frames")
    }

    @Test
    fun echoOutWhenBIsColdAndTemposDiffer() {
        val f = LiveFixtures.features(a120, b140cold)
        assertTrue(abs(f.tempoRatio - 1.0) > 0.02)
        val now = barFrame(a120, 20) + 5
        val plan = factory.plan(a120.trackRef, b140cold.trackRef, f, now, prefs)
        assertEquals("echoOut", plan.kind)
        assertValid(plan, now, a120.analysis.totalFrames, b140cold.analysis.totalFrames)
        val bar = 4 * 22050
        assertEquals(barFrame(a120, 21), plan.aFromFrame)
        val echo = assertNotNull(plan.nodes.filterIsInstance<LiveNode.Echo>().firstOrNull())
        assertEquals(bar, echo.cutFrame, "A is cut on the downbeat after one dry bar")
        assertEquals(Math.round(0.75 * 22050).toInt(), echo.delayFrames, "dotted-eighth echo")
        assertEquals(DefaultLivePlanFactory.ECHO_FEEDBACK, echo.feedback)
        // 14 echoes to -60 dB * 0.75 beat = 10.5 beats -> 3 bars of tail
        assertEquals(14, LivePlanBuilders.echoesToFloor(echo.feedback))
        assertEquals(4 * bar, plan.outputFrames, "one dry bar + three bars of tail")
        val gb = plan.nodes.filterIsInstance<LiveNode.Gain>().first { it.deck == Deck.B }
        assertEquals(bar, gb.points.last().frame, "B is at unity at the cut")
        assertEquals(bMixInFrame(b140cold), plan.bFromFrame)
        assertTrue(plan.nodes.none { it is LiveNode.Rate }, "no resampling: B keeps its tempo")
    }

    @Test
    fun filterSweepWhenBHasAnIntro() {
        val f = LiveFixtures.features(a120, b140amb)
        val now = barFrame(a120, 8)
        val plan = factory.plan(a120.trackRef, b140amb.trackRef, f, now, prefs)
        assertEquals("filterSweep", plan.kind)
        assertValid(plan, now, a120.analysis.totalFrames, b140amb.analysis.totalFrames)
        assertEquals(now, plan.aFromFrame, "now is a downbeat: no waiting")
        assertEquals(4 * 4 * 22050, plan.outputFrames)
        val sweeps = plan.nodes.filterIsInstance<LiveNode.Sweep>()
        assertEquals(2, sweeps.size)
        val sa = sweeps.first { it.deck == Deck.A }; val sb = sweeps.first { it.deck == Deck.B }
        assertTrue(sa.highPass && sa.fromHz < sa.toHz && sa.toFrame == plan.outputFrames, "A high-pass sweeps up over the segment: $sa")
        assertTrue(!sb.highPass && sb.fromHz < sb.toHz && sb.toHz <= 0.45 * SR, "B low-pass opens: $sb")
        assertEquals(bMixInFrame(b140amb), plan.bFromFrame)
    }

    @Test
    fun phraseCutWhenAEndsWithinABar() {
        val f = LiveFixtures.features(a120, b140cold)
        val now = a120.analysis.trimEndFrame - 2 * 22050 // half a bar before A's last downbeat + 1 bar
        val plan = factory.plan(a120.trackRef, b140cold.trackRef, f, now, prefs)
        assertEquals("phraseCut", plan.kind)
        assertValid(plan, now, a120.analysis.totalFrames, b140cold.analysis.totalFrames)
        assertEquals(a120.analysis.trimEndFrame, plan.aFromFrame, "cut at A's next phrase start (its end)")
        assertEquals(Math.round(0.016 * SR).toInt(), plan.outputFrames, "16 ms")
        assertEquals(2, plan.nodes.size)
        assertTrue(plan.nodes.all { it is LiveNode.Gain && it.points.first().shape == FadeLaw.EQUAL_POWER })
        // an ambient B in the same spot gets no cut: a crossfade from now
        val plan2 = factory.plan(a120.trackRef, b140amb.trackRef, LiveFixtures.features(a120, b140amb), now, prefs)
        assertEquals("crossfade", plan2.kind)
        assertEquals(now, plan2.aFromFrame)
    }

    @Test
    fun crossfadeIsTheFloor() {
        val now = barFrame(a120, 10) + 777
        // The floor is reached when A's grid cannot be trusted: nothing can be scheduled musically, so the plan is a
        // plain fade from "now". (Missing pair features alone only costs the bass swap — the sweep needs no pair.)
        val shaky = a120.trackRef.copy(analysis = a120.analysis.copy(grid = a120.analysis.grid.copy(confidence = 0.3f)))
        val plan = factory.plan(shaky, b121.trackRef, null, now, prefs)
        assertEquals("crossfade", plan.kind, "unusable grid -> crossfade")
        assertValid(plan, now, a120.analysis.totalFrames, b121.analysis.totalFrames)
        assertEquals(now, plan.aFromFrame, "from now")
        assertEquals(Math.round(DefaultLivePlanFactory.DEFAULT_LIVE_FADE_SEC * SR).toInt(), plan.outputFrames)
        assertEquals(66150, plan.outputFrames, "1.5 s default")
        assertEquals(bMixInFrame(b121), plan.bFromFrame)
        assertEquals(plan.bFromFrame + plan.outputFrames, plan.bExitFrame(), "no Rate node: B consumes exactly the segment")
        val over = factory.plan(shaky, b121.trackRef, null, now, prefs, fadeSecOverride = 3.0)
        assertEquals(3 * SR, over.outputFrames)
        val bad = factory.plan(shaky, b121.trackRef, null, now, prefs, fadeSecOverride = Double.NaN)
        assertEquals(66150, bad.outputFrames, "unusable override falls back to the default")
        // an unconfident A grid disables every grid-based rung even for a bass-swappable pair
        val f = LiveFixtures.features(a120, b121)
        val plan3 = factory.plan(shaky, b121.trackRef, f, now, prefs)
        assertEquals("crossfade", plan3.kind)
        // an empty grid on A is the same floor
        val gridless = a120.trackRef.copy(analysis = a120.analysis.copy(grid = dev.muisc.analysis.model.BeatGrid.EMPTY))
        assertEquals("crossfade", factory.plan(gridless, b121.trackRef, f, now, prefs).kind)
        // a feature set that says "not beat-matchable" skips the bass swap but keeps the sweep
        val plan4 = factory.plan(a120.trackRef, b121.trackRef, f.copy(gridConfidenceB = 0.1), now, prefs)
        assertEquals("filterSweep", plan4.kind)
        assertEquals(barFrame(a120, 11), plan4.aFromFrame)
        // ... and so does having no pair features at all
        assertEquals("filterSweep", factory.plan(a120.trackRef, b121.trackRef, null, now, prefs).kind)
    }

    @Test
    fun laddersDownWhenAIsRunningOut() {
        val f = LiveFixtures.features(a120, b121)
        val bar = 4 * 22050L
        // 3 bars left after the next downbeat: the swap shrinks to 3 bars (min 2)
        val now3 = a120.analysis.trimEndFrame - 3 * bar - 100
        val p3 = factory.plan(a120.trackRef, b121.trackRef, f, now3, prefs)
        assertEquals("bassSwap", p3.kind); assertEquals(3 * bar, p3.outputFrames.toLong())
        assertEquals(a120.analysis.trimEndFrame - 3 * bar, p3.aFromFrame)
        // 1 bar left: too short for a swap or a sweep, B is not cold -> crossfade from now
        val now1 = a120.analysis.trimEndFrame - bar - 100
        val p1 = factory.plan(a120.trackRef, b121.trackRef, f, now1, prefs)
        assertEquals("crossfade", p1.kind); assertEquals(now1, p1.aFromFrame)
        assertValid(p1, now1, a120.analysis.totalFrames, b121.analysis.totalFrames)
        // past the end of A: still a valid crossfade, A contributes nothing
        val pEnd = factory.plan(a120.trackRef, b121.trackRef, f, a120.analysis.totalFrames + 5000, prefs)
        assertEquals("crossfade", pEnd.kind)
        assertEquals(a120.analysis.totalFrames, pEnd.aFromFrame); assertEquals(pEnd.aFromFrame, pEnd.aToFrame)
        assertValid(pEnd, a120.analysis.totalFrames, a120.analysis.totalFrames, b121.analysis.totalFrames)
    }

    @Test
    fun neverThrowsForRandomAnalysesAndIsDeterministic() {
        val rnd = Random(20260913)
        val kinds = HashMap<String, Int>()
        repeat(100) { i ->
            val a = LiveFixtures.randomRef(rnd, "a$i")
            val b = LiveFixtures.randomRef(rnd, "b$i")
            val f = LiveFixtures.randomFeatures(rnd, a.analysis, b.analysis)
            val now = rnd.nextLong(-1000L, a.analysis.totalFrames + 1000L)
            val override = when (rnd.nextInt(4)) { 0 -> null; 1 -> Double.NaN; 2 -> -1.0; else -> rnd.nextDouble(0.1, 10.0) }
            val p = prefs.copy(preferredOverlapBars = rnd.nextInt(0, 40))
            val plan = try { factory.plan(a, b, f, now, p, override) } catch (t: Throwable) { throw AssertionError("plan $i threw: $t\nA=${a.analysis.grid} total=${a.analysis.totalFrames} now=$now f=$f", t) }
            assertValid(plan, now, a.analysis.totalFrames, b.analysis.totalFrames)
            assertEquals(plan, factory.plan(a, b, f, now, p, override), "deterministic")
            kinds.merge(plan.kind, 1, Int::plus)
        }
        assertTrue(kinds.getValue("crossfade") > 0 && kinds.size >= 3, "random inputs exercise several rungs: $kinds")
    }

    @Test
    fun deterministicOnRealPairs() {
        val f = LiveFixtures.features(a120, b121)
        val now = barFrame(a120, 9) + 12345
        val p1 = factory.plan(a120.trackRef, b121.trackRef, f, now, prefs)
        val p2 = factory.plan(a120.trackRef, b121.trackRef, f, now, prefs)
        assertEquals(p1, p2)
        assertEquals(p1.nodes, p2.nodes)
    }
}
