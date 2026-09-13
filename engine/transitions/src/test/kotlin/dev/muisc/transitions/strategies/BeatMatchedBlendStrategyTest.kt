package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.Mode
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.TempoRelation
import dev.muisc.transitions.core.MasterGrid
import dev.muisc.transitions.strategies.BeatDomainTestSupport as T
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeatMatchedBlendStrategyTest {
    private val strategy = BeatMatchedBlendStrategy()
    private val a120 = T.song(120.0, 0, Mode.MAJOR)
    private val b126 = T.song(126.0, 9, Mode.MINOR)
    private val pair by lazy { T.pair(a120, b126) }
    /** 8-bar overlap keeps the renders short; bass in at bar 4 so the EQ move happens while A still plays. */
    private val short = Params.EMPTY.with("overlapBars", 8).with("bassInBar", 4)

    private fun render(p: T.Pair, params: Params, seed: Long = 1L) = run {
        val plan = strategy.plan(p.a.analysis, p.b.analysis, p.features, params, p.prefs, seed)
        val input = p.input(plan)
        Triple(plan, input, strategy.render(input, RenderContext(p.prefs, seed)))
    }

    @Test
    fun applicabilityGatesAndScores() {
        assertEquals("beatMatchedBlend", strategy.id)
        assertEquals(listOf("overlapBars", "bassInBar", "bassInBeats", "eqDepthDb", "aHighsOutDb", "lowHz", "highHz", "vocalDuckDb", "law", "entryOffsetBars", "settleBars", "holdBars", "detectOnsets"), strategy.params.map { it.id })
        val ok = strategy.applicability(pair.features, pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(ok.applicable, "120 -> 126 is applicable: ${ok.blockers}")
        assertTrue(ok.score > 0.6, "score ${ok.score} for a 5 % stretch, relative keys, matching loudness")
        assertTrue(ok.reasons.any { it.startsWith("tempo 5.0 % stretch") }, ok.reasons.toString())
        assertTrue(ok.reasons.any { it.startsWith("keys 8B→8A") }, "reasons name the two Camelot codes: ${ok.reasons}")
        // 25 % apart: blocked by the stretch gate.
        val far = T.pair(a120, T.song(150.0, 2, Mode.MAJOR))
        val blocked = strategy.applicability(far.features, far.a.analysis, far.b.analysis, far.prefs)
        assertTrue(!blocked.applicable && blocked.blockers.any { it.contains("exceeds") }, blocked.toString())
        // unconfident grid: blocked
        val shaky = strategy.applicability(pair.features.copy(gridConfidenceB = 0.3), pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(!shaky.applicable && shaky.blockers.any { it.contains("beat-matchable") }, shaky.toString())
        // no room: blocked
        val cramped = strategy.applicability(pair.features.copy(introBeatsAvailable = 4), pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(!cramped.applicable && cramped.blockers.any { it.startsWith("room") }, cramped.toString())
        // key distance lowers the score but does not block
        val clash = strategy.applicability(pair.features.copy(camelotDistance = 5, camelotDistanceAfterShift = 5), pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(clash.applicable && clash.score < ok.score)
    }

    @Test
    fun planGeometryFollowsCuesAndContract() {
        val (plan, _, _) = render(pair, short).let { Triple(it.first, it.second, it.third) }
        val a = pair.a.analysis; val b = pair.b.analysis
        assertEquals("beatMatchedBlend", plan.strategyId)
        // The synthetic mix-out cue (bar 12) is not on the 8-bar phrase grid: the overlap starts at the phrase start before it (bar 8).
        assertEquals(a.grid.previousPhraseStart(a.cues.mixOutBeat.toDouble()), plan.params[BeatDomain.PARAM_A_START_BEAT]!!.toInt(), "A's overlap starts at the phrase start at/before its mix-out cue")
        assertEquals(32, plan.params[BeatDomain.PARAM_A_START_BEAT]!!.toInt())
        assertEquals(b.cues.mixInBeat, plan.params[BeatDomain.PARAM_B_START_BEAT]!!.toInt(), "B's mixInBeat sits on master beat 0")
        assertEquals(32, plan.params[BeatDomain.PARAM_OVERLAP_BEATS]!!.toInt())
        assertEquals(MasterGrid.MODE_GLIDE, plan.params[MasterGrid.PARAM_MODE], "base layout: overlap at A's tempo, settle, hold")
        val grid = MasterGrid.fromPlan(plan, a, b, pair.features, pair.prefs)
        assertEquals((8 + 2 + 2) * 4, grid.beatCount)
        assertEquals(120.0, grid.bpmStart, 1e-9); assertEquals(126.0, grid.bpmEnd, 1e-9)
        assertEquals((2 * T.G + grid.totalFrames).toInt(), plan.expectedOutputFrames)
        assertEquals(a.grid.frameOfBeat(32.0) - T.G, plan.aExitFrame)
        assertEquals(b.grid.frameOfBeat((b.cues.mixInBeat + grid.beatCount).toDouble()) + T.G, plan.bEntryFrame)
        assertTrue(plan.aWindow.start <= plan.aExitFrame - BeatDomain.WINDOW_PAD + 1 && plan.bWindow.end >= plan.bEntryFrame + BeatDomain.WINDOW_PAD, "windows padded")
        assertTrue(plan.lanes.map { it.id }.containsAll(listOf("masterBeat", "masterBpm", "gainA", "gainB", "duckA", "eqLowB", "eqHighA")), plan.lanes.map { it.id }.toString())
        assertTrue(plan.notes.any { it.contains("bass in at bar 4") }, plan.notes.toString())
        // Cues unknown (-1): falls back to phrase starts / downbeats and still plans.
        val noCues = pair.a.analysis.copy(cues = dev.muisc.analysis.model.Cues())
        val p2 = strategy.plan(noCues, b.copy(cues = dev.muisc.analysis.model.Cues()), pair.features, short, pair.prefs, 0)
        assertTrue(p2.notes.any { it.contains("no mixOutBeat") } && p2.notes.any { it.contains("no cues") || it.contains("first downbeat") }, p2.notes.toString())
        assertTrue(p2.aExitFrame in p2.aWindow)
    }

    @Test
    fun renderIsCleanDeterministicAndBeatLocked() {
        val (plan, input, rendered) = render(pair, short, seed = 3)
        T.assertContract("beatMatchedBlend", plan, input, rendered)
        val (_, _, again) = render(pair, short, seed = 3)
        T.assertBitIdentical(rendered, again)
        assertEquals(listOf(BeatMatchedBlendStrategy.MARKER_B_ENTERS, BeatMatchedBlendStrategy.MARKER_BASS_IN, BeatMatchedBlendStrategy.MARKER_A_HIGHS_OUT, BeatMatchedBlendStrategy.MARKER_A_GONE, BeatMatchedBlendStrategy.MARKER_HOLD), rendered.markers.map { it.label })
        assertEquals(T.G.toLong(), rendered.markers[0].frame)
        // B ends at ratio 1.0: the last master beats are at B's tempo and the trace says so.
        val trace = rendered.report.ratioTrace
        assertEquals(48, trace.size)
        assertEquals(1.0, trace.last().toDouble(), 0.002, "B at its own tempo before the seam")
        assertEquals(126.0 / 120.0, trace.first().toDouble(), 0.005, "B stretched to A's tempo at entry")
        // Beat alignment: B's kicks land on the master beats over the whole grid (B bars 4..16 have drums).
        val ref = T.beatFrames(pair.b, 16, 80)
        val al = T.kickAlignment(rendered, 0 until 48, pair.b.audio, ref)
        T.assertAligned("B kicks vs masterBeat", al)
        // A's kicks too, over the overlap (A plays at ratio 1: resample path, tight).
        val alA = T.kickAlignment(rendered, 0 until 32, pair.a.audio, T.beatFrames(pair.a, 16, 80), toleranceMs = 3.0)
        T.assertAligned("A kicks vs masterBeat", alA, maxMedianMs = 2.0)
    }

    @Test
    fun bassInOpensBsLowShelf() {
        val (plan, input, _) = render(pair, short)
        val bOnly = strategy.render(T.silenced(input, silenceA = true, silenceB = false), RenderContext(pair.prefs, 1))
        val beats = T.masterBeatFrames(bOnly)
        val before = T.bandRmsDb(bOnly.audio, beats[4], beats[12], 150.0)   // bars 1-2, lows cut
        val after = T.bandRmsDb(bOnly.audio, beats[20], beats[28], 150.0)   // bars 5-6, lows in
        assertTrue(after - before > 7.0, "B lows rise by ${"%.1f".format(after - before)} dB at bass-in (${"%.1f".format(before)} -> ${"%.1f".format(after)} dBFS)")
        // The high band of B is not affected by the low shelf: same fade-in law only (equal-power over 8 bars).
        val hBefore = T.bandRmsDb(bOnly.audio, beats[4], beats[12], 3000.0, lowPass = false)
        val hAfter = T.bandRmsDb(bOnly.audio, beats[20], beats[28], 3000.0, lowPass = false)
        assertTrue(abs((hAfter - hBefore) - (after - before)) > 3.0, "the low band moves more than the high band (${"%.1f".format(hAfter - hBefore)} dB)")
        // Without EQ the low band only follows the crossfade.
        val flat = strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, short.with("eqDepthDb", 0.0), pair.prefs, 1)
        val flatOnly = strategy.render(T.silenced(pair.input(flat), silenceA = true, silenceB = false), RenderContext(pair.prefs, 1))
        val fb = T.masterBeatFrames(flatOnly)
        val flatDelta = T.bandRmsDb(flatOnly.audio, fb[20], fb[28], 150.0) - T.bandRmsDb(flatOnly.audio, fb[4], fb[12], 150.0)
        assertTrue(after - before - flatDelta > 6.0, "EQ contributes ${"%.1f".format(after - before - flatDelta)} dB beyond the fade (${"%.1f".format(flatDelta)} dB)")
        assertEquals(plan.expectedOutputFrames, bOnly.audio.frames)
    }

    @Test
    fun vocalDuckLowersAWhenBSings() {
        // Give B vocals in its bars 6 and 7 (master bars 2 and 3 of the overlap, B starts at bar 4).
        val bars = pair.b.analysis.bars
        val vocal = FloatArray(bars.barCount) { if (it == 6 || it == 7) 1f else 0f }
        val singing = pair.b.analysis.copy(bars = BarFeatures(bars.energy, bars.sub, bars.bass, bars.mid, bars.high, bars.percussiveness, vocal))
        val plan = strategy.plan(pair.a.analysis, singing, pair.features, short, pair.prefs, 1)
        val duckLane = plan.lanes.first { it.id == "duckA" }
        assertTrue(duckLane.points.any { abs(it.value + 6.0) < 1e-9 }, "duck lane reaches -6 dB: ${duckLane.points}")
        val input = T.silenced(pair.input(plan, bAnalysis = singing), silenceA = false, silenceB = true)
        val ducked = strategy.render(input, RenderContext(pair.prefs, 1))
        val plain = strategy.plan(pair.a.analysis, singing, pair.features, short.with("vocalDuckDb", 0.0), pair.prefs, 1)
        val undecked = strategy.render(T.silenced(pair.input(plain, bAnalysis = singing), silenceA = false, silenceB = true), RenderContext(pair.prefs, 1))
        val beats = T.masterBeatFrames(ducked)
        val inDuck = T.rmsDb(ducked.audio, beats[9], beats[15]) - T.rmsDb(undecked.audio, beats[9], beats[15])
        val outside = T.rmsDb(ducked.audio, beats[1], beats[7]) - T.rmsDb(undecked.audio, beats[1], beats[7])
        assertEquals(-6.0, inDuck, 1.0, "A is 6 dB lower while B sings")
        assertEquals(0.0, outside, 0.3, "A untouched where B does not sing")
    }

    @Test
    fun halfTimePairIsReadInHalfTime() {
        val fast = T.song(240.0, 9, Mode.MINOR, bars = 48, introBars = 8, outroBars = 8)
        val p = T.pair(a120, fast)
        assertEquals(TempoRelation.HALF, p.features.tempoRelation)
        assertEquals(0.0, p.features.stretchPercent, 1e-9)
        val app = strategy.applicability(p.features, p.a.analysis, p.b.analysis, p.prefs)
        assertTrue(app.applicable, app.blockers.toString())
        val (plan, input, rendered) = render(p, short)
        assertEquals(16, plan.params[BeatDomain.PARAM_B_START_BEAT]!!.toInt(), "B's mixInBeat 32 is matched beat 16")
        assertTrue(plan.notes.any { it.contains("half time") }, plan.notes.toString())
        T.assertContract("beatMatchedBlend half-time", plan, input, rendered)
        // Every master beat carries a B kick (B's beats 32 + 2k), at ratio 1.0 throughout.
        for (r in rendered.report.ratioTrace) assertEquals(1.0, r.toDouble(), 0.002)
        val ref = LongArray(48) { p.b.analysis.grid.beatFrames[32 + 2 * it] }
        val al = T.kickAlignment(rendered, 0 until 48, p.b.audio, ref, toleranceMs = 3.0)
        T.assertAligned("half-time B kicks", al, maxMedianMs = 2.0)
    }
}
