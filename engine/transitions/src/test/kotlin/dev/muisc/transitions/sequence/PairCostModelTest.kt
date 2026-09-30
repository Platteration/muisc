package dev.muisc.transitions.sequence

import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.LoudnessInfo
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.planner.TestAnalyses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairCostModelTest {
    private val prefs = TransitionPrefs()
    private val model = PairCostModel()

    @Test
    fun `the cost is the documented weighted sum`() {
        val a = TestAnalyses.simple("a", seconds = 180.0, bpm = 124.0)
        val b = TestAnalyses.simple("b", seconds = 180.0, bpm = 126.0)
        val f = DefaultPairAnalyzer().features(a, b, prefs)
        val br = model.breakdown(a, f, prefs)
        assertFalse(br.beatMatchBlocked)
        assertEquals(br.tempo + br.key + br.energy + br.grid + br.struct + br.room + br.vocal, br.total, 1e-12)
        assertEquals(br.total, model.cost(a, b, prefs), 1e-12)
        val weights = PairCostModel.W_TEMPO + PairCostModel.W_KEY + PairCostModel.W_ENERGY + PairCostModel.W_GRID +
            PairCostModel.W_STRUCT + PairCostModel.W_ROOM + PairCostModel.W_VOCAL
        assertEquals(1.0, weights, 1e-12, "the weights sum to 1, so the range is 0..1 + the gate")
    }

    @Test
    fun `worse tempo, key, grid and room cost more`() {
        val a = TestAnalyses.simple("a", seconds = 180.0, bpm = 124.0)
        val near = TestAnalyses.simple("near", seconds = 180.0, bpm = 125.0)
        val far = TestAnalyses.simple("far", seconds = 180.0, bpm = 100.0)
        assertTrue(model.cost(a, near, prefs) < model.cost(a, far, prefs), "tempo")
        val farKey = near.copy(key = KeyEstimate(MusicalKey(6, Mode.MAJOR), 0.9f)) // F# major: opposite side of the wheel from C
        assertTrue(model.cost(a, near, prefs) < model.cost(a, farKey, prefs), "key")
        val weakGrid = near.copy(grid = near.grid.copy(confidence = 0.3f))
        assertTrue(model.breakdown(a, DefaultPairAnalyzer().features(a, weakGrid, prefs), prefs).beatMatchBlocked, "grid below 0.5 blocks beat-matching")
        assertTrue(model.cost(a, near, prefs) + PairCostModel.GATE_PENALTY <= model.cost(a, weakGrid, prefs), "the gate penalty is added")
        val stretched = TestAnalyses.simple("s", seconds = 180.0, bpm = 124.0 * 1.1)
        assertTrue(model.breakdown(a, DefaultPairAnalyzer().features(a, stretched, prefs), prefs).beatMatchBlocked, "10 % stretch > 8 % limit blocks")
        val noRoom = near.copy(cues = near.cues.copy(mixInBeat = 8)) // 2 bars of intro < half of 8 bars
        assertTrue(model.breakdown(a, DefaultPairAnalyzer().features(a, noRoom, prefs), prefs).beatMatchBlocked, "short intro blocks")
        val louder = near.copy(loudness = LoudnessInfo(-4f, 0f))
        assertTrue(model.cost(a, near, prefs) < model.cost(a, louder, prefs), "loudness jump")
    }

    @Test
    fun `missing analyses cost the neutral value`() {
        val a = TestAnalyses.simple("a")
        assertEquals(PairCostModel.UNKNOWN_PAIR_COST, model.cost(null, a, prefs))
        assertEquals(PairCostModel.UNKNOWN_PAIR_COST, model.cost(a, null, prefs))
        assertEquals(PairCostModel.UNKNOWN_PAIR_COST, model.cost(null, null, prefs))
    }

    @Test
    fun `energy proxy rises with loudness, tempo and percussiveness`() {
        val base = TestAnalyses.simple("e", bpm = 100.0, lufs = -14f)
        val e = PairCostModel.energyProxy(base)
        assertTrue(e in 0.0..1.0)
        assertTrue(PairCostModel.energyProxy(base.copy(loudness = LoudnessInfo(-8f, 0f))) > e)
        assertTrue(PairCostModel.energyProxy(base.copy(tempo = TempoEstimate(150.0, 0.9f))) > e)
        assertTrue(PairCostModel.energyProxy(base.copy(bars = base.bars.copy(percussiveness = FloatArray(base.bars.barCount) { 1f }))) > e)
        assertEquals(0.4 * (6.0 / 14.0) + 0.3 * 0.3 + 0.3 * 0.7, e, 1e-6, "−14 LUFS, 100 BPM, percussiveness 0.7")
    }
}
