package dev.muisc.transitions.modifiers

import dev.muisc.analysis.model.Mode
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.core.MasterGrid
import dev.muisc.transitions.core.SpliceCheck
import dev.muisc.transitions.strategies.BassSwapStrategy
import dev.muisc.transitions.strategies.BeatDomain
import dev.muisc.transitions.strategies.BeatMatchedBlendStrategy
import dev.muisc.transitions.strategies.CrossfadeStrategy
import dev.muisc.transitions.strategies.BeatDomainTestSupport as T
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TempoGlideModifierTest {
    private val modifier = TempoGlideModifier()
    private val blend = BeatMatchedBlendStrategy()
    private val a120 = T.song(120.0, 0, Mode.MAJOR)
    private val b132 = T.song(132.0, 9, Mode.MINOR)
    /** 10 % apart: beyond the plain 8 % limit, inside the glide's 16 %. */
    private val pair by lazy { T.pair(a120, b132) }

    @Test
    fun applicabilityOnlyForBeatDomainBasesInTheGlideRange() {
        assertEquals("tempoGlide", modifier.id)
        assertEquals(listOf("glideBars", "curve", "holdBars", "mode"), modifier.params.map { it.id })
        val f = pair.features
        assertEquals(10.0, f.stretchPercent, 1e-9)
        val onBlend = modifier.applicability(f, pair.a.analysis, pair.b.analysis, blend, pair.prefs)
        assertTrue(onBlend > 0.9, "10 % with an 8 % limit: the glide is the way to beat-mix ($onBlend)")
        assertTrue(modifier.applicability(f, pair.a.analysis, pair.b.analysis, BassSwapStrategy(), pair.prefs) > 0.9)
        assertEquals(0.0, modifier.applicability(f, pair.a.analysis, pair.b.analysis, CrossfadeStrategy(), pair.prefs), "not for crossfade")
        assertEquals(0.0, modifier.applicability(f.copy(stretchPercent = 0.3), pair.a.analysis, pair.b.analysis, blend, pair.prefs), "below 0.5 %")
        assertEquals(0.0, modifier.applicability(f.copy(stretchPercent = 17.0), pair.a.analysis, pair.b.analysis, blend, pair.prefs), "above 2 x 8 %")
        val small = modifier.applicability(f.copy(stretchPercent = 2.0), pair.a.analysis, pair.b.analysis, blend, pair.prefs)
        val mid = modifier.applicability(f.copy(stretchPercent = 5.0), pair.a.analysis, pair.b.analysis, blend, pair.prefs)
        assertTrue(small in 0.05..0.4 && mid > small && mid < onBlend, "rises with the stretch: $small < $mid < $onBlend")
        assertEquals(0.0, modifier.applicability(f.copy(gridConfidenceA = 0.2), pair.a.analysis, pair.b.analysis, blend, pair.prefs), "needs beat-matchable grids")
    }

    @Test
    fun adjustPlanRewritesGridAndFrames() {
        val base = blend.plan(pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs, 1)
        val glided = modifier.adjustPlan(base, pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs)
        assertEquals(MasterGrid.MODE_GLIDE, glided.params[MasterGrid.PARAM_MODE])
        assertEquals("0", glided.params[MasterGrid.PARAM_PRE_BARS])
        assertEquals("16", glided.params[MasterGrid.PARAM_GLIDE_BARS])
        assertEquals("2", glided.params[MasterGrid.PARAM_HOLD_BARS])
        assertEquals("S_CURVE", glided.params[MasterGrid.PARAM_CURVE])
        assertEquals("keyLock", glided.params[BeatDomain.PARAM_STRETCH_MODE])
        assertEquals(listOf("tempoGlide"), glided.modifiers)
        val grid = MasterGrid.fromPlan(glided, pair.a.analysis, pair.b.analysis, pair.features, pair.prefs)
        assertEquals(18 * 4, grid.beatCount)
        assertEquals(120.0, grid.bpmStart, 1e-9); assertEquals(132.0, grid.bpmEnd, 1e-9)
        assertEquals((2 * T.G + grid.totalFrames).toInt(), glided.expectedOutputFrames)
        assertTrue(glided.expectedOutputFrames != base.expectedOutputFrames, "the base plan had 16 + 2 + 2 bars, the glide 16 + 2")
        assertEquals(base.aExitFrame, glided.aExitFrame, "A's exit does not move")
        assertEquals(pair.b.analysis.grid.frameOfBeat((pair.b.analysis.cues.mixInBeat + 72).toDouble()) + T.G, glided.bEntryFrame, "B's entry follows the new grid")
        assertTrue(glided.bWindow.end >= glided.bEntryFrame && glided.aExitFrame in glided.aWindow)
        assertTrue(glided.notes.any { it.startsWith("tempo glide 120.0 -> 132.0 BPM over 16 bars") }, glided.notes.toString())
        assertTrue(glided.lanes.any { it.id == BeatDomain.LANE_MASTER_BPM } && glided.lanes.any { it.id == BeatDomain.LANE_MASTER_BEAT })
        // A plan without beat-domain geometry only receives the grid params.
        val cf = CrossfadeStrategy().plan(pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs, 1)
        val cfGlided = modifier.adjustPlan(cf, pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs)
        assertEquals(cf.expectedOutputFrames, cfGlided.expectedOutputFrames)
        assertEquals(MasterGrid.MODE_GLIDE, cfGlided.params[MasterGrid.PARAM_MODE])
        assertTrue(cfGlided.notes.any { it.contains("windows unchanged") })
    }

    @Test
    fun beatMatchedBlendGlidesFrom120To132AndEndsAtRatioOne() {
        val base = blend.plan(pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs, 2)
        val plan = modifier.adjustPlan(base, pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs)
        val input = pair.input(plan)
        val ctx = RenderContext(pair.prefs, 2)
        val rendered = modifier.apply(blend.render(input, ctx), input, Params.EMPTY, ctx)
        T.assertContract("beatMatchedBlend + tempoGlide", plan, input, rendered)
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input), "ends with B dry at ratio 1.0")
        // The master beat period walks monotonically from A's to B's.
        val periods = T.localPeriods(T.masterBeatFrames(rendered))
        assertEquals(18 * 4, periods.size)
        assertEquals(60.0 / 120.0 * T.SR, periods.first().toDouble(), 1.0, "first beat at 120 BPM")
        assertEquals(60.0 / 132.0 * T.SR, periods.last().toDouble(), 1.0, "last beat at 132 BPM")
        for (i in 1 until periods.size) assertTrue(periods[i] <= periods[i - 1] + 1, "period never grows: beat $i ${periods[i]} > ${periods[i - 1]}")
        assertTrue(periods[8 * 4] < periods.first() - 200 && periods[8 * 4] > periods.last() + 200, "half-way through the glide the tempo is between the two")
        // B is slowed by 10 % at entry and at its own tempo at the end; A speeds up as the glide proceeds.
        val trace = rendered.report.ratioTrace
        assertEquals(132.0 / 120.0, trace.first().toDouble(), 0.005)
        assertEquals(1.0, trace.last().toDouble(), 0.002)
        assertEquals(1.0, rendered.report.metrics["bRatioEnd"]!!, 0.002)
        assertTrue(rendered.report.metrics["aMaxStretchPercent"]!! > 8.0, "A reaches ~9 % at the end of the glide: ${rendered.report.metrics["aMaxStretchPercent"]}")
        // The modifier's lane and markers.
        assertTrue(rendered.plan.lanes.any { it.id == BeatDomain.LANE_MASTER_BPM })
        val bpm = rendered.plan.lanes.first { it.id == BeatDomain.LANE_MASTER_BPM }.points
        assertEquals(120.0, bpm.first().value, 1e-9); assertEquals(132.0, bpm.last().value, 1e-9)
        assertTrue(rendered.markers.any { it.label == TempoGlideModifier.MARKER_GLIDE_START && it.frame == T.G.toLong() })
        assertTrue(rendered.markers.any { it.label == TempoGlideModifier.MARKER_GLIDE_END && it.frame == T.masterBeatFrames(rendered)[64] })
        // B's kicks follow the moving grid (B bars 4..20 have drums: master beats 0..64).
        T.assertAligned("B kicks over the glide", T.kickAlignment(rendered, 0 until 64, pair.b.audio, T.beatFrames(pair.b, 16, 80)))
        // Deterministic.
        val again = modifier.apply(blend.render(pair.input(plan), ctx), input, Params.EMPTY, ctx)
        T.assertBitIdentical(rendered, again)
    }

    @Test
    fun aGlideLongerThanBsMusicIsShortened() {
        // B is 24 bars with a 4-bar intro: 20 bars of music after its mix-in cue. A 64-bar glide cannot fit.
        val base = blend.plan(pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY, pair.prefs, 1)
        val plan = modifier.adjustPlan(base, pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY.with("glideBars", 64), pair.prefs)
        assertEquals("18", plan.params[MasterGrid.PARAM_GLIDE_BARS], "64 + 2 hold bars shortened to 18 + 2 = B's 20 bars")
        assertTrue(plan.notes.any { it.contains("glide shortened to 18 bars") }, plan.notes.toString())
        val input = pair.input(plan)
        val ctx = RenderContext(pair.prefs, 1)
        val rendered = modifier.apply(blend.render(input, ctx), input, Params.EMPTY.with("glideBars", 64), ctx)
        T.assertContract("clamped glide", plan, input, rendered)
        // The segment still ends with B at its own tempo, and its tail is real music, not the zero-padded window.
        assertEquals(1.0, rendered.report.ratioTrace.last().toDouble(), 0.002)
        val beats = T.masterBeatFrames(rendered)
        assertTrue(T.rmsDb(rendered.audio, beats[beats.size - 5], beats[beats.size - 1]) > -40.0, "B's last bar carries music")
    }

    @Test
    fun vinylModeResamplesAndShortGlideWorks() {
        val params = Params.EMPTY.with("glideBars", 8).with("holdBars", 1).with("mode", "vinyl").with("curve", "LINEAR")
        val base = blend.plan(pair.a.analysis, pair.b.analysis, pair.features, Params.EMPTY.with("overlapBars", 8), pair.prefs, 1)
        val plan = modifier.adjustPlan(base, pair.a.analysis, pair.b.analysis, pair.features, params, pair.prefs)
        assertEquals("vinyl", plan.params[BeatDomain.PARAM_STRETCH_MODE])
        assertEquals("LINEAR", plan.params[MasterGrid.PARAM_CURVE])
        val input = pair.input(plan)
        val ctx = RenderContext(pair.prefs, 1)
        val rendered = modifier.apply(blend.render(input, ctx), input, params, ctx)
        T.assertContract("vinyl glide", plan, input, rendered)
        assertEquals(0.0, rendered.report.metrics["bWsola"]!!, "vinyl = resampling, no WSOLA")
        assertEquals(0.0, rendered.report.metrics["aWsola"]!!)
        val periods = T.localPeriods(T.masterBeatFrames(rendered))
        assertEquals(9 * 4, periods.size)
        // LINEAR: the BPM rises by the same amount every beat over the 32 glide beats (periods are 1/BPM, so they are not linear).
        val bpm = rendered.plan.lanes.first { it.id == BeatDomain.LANE_MASTER_BPM }.points.map { it.value }
        val steps = (1 until 32).map { bpm[it] - bpm[it - 1] }
        assertTrue(steps.all { abs(it - 12.0 / 32.0) < 1e-6 }, "linear glide steps $steps")
        assertEquals(132.0, bpm[32], 1e-9, "B's tempo reached after 32 beats")
        // Beat 31 is the LAST glide beat (at 120 + 12 * 31/32 = 131.625 BPM); beats 32..35 are the hold at 132.
        assertTrue(periods[0] > periods[31] && periods[31] > periods[32], "the glide is still moving on its last beat")
        // The hold bar runs at B's tempo throughout (the grid carries the fractional period, so a beat is 20045 or
        // 20046 frames: 60 * 44100 / 132 = 20045.45).
        for (k in 32..35) assertEquals(60.0 / 132.0 * T.SR, periods[k].toDouble(), 1.0, "hold beat $k at B's tempo")
        assertEquals(1.0, rendered.report.ratioTrace.last().toDouble(), 0.002)
        T.assertAligned("vinyl B kicks", T.kickAlignment(rendered, 0 until 36, pair.b.audio, T.beatFrames(pair.b, 16, 80), toleranceMs = 4.0), maxMedianMs = 3.0)
    }
}
