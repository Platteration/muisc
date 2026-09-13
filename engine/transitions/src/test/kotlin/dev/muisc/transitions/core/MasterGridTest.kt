package dev.muisc.transitions.core

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Mode
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.Params
import dev.muisc.transitions.TransitionPlan
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MasterGridTest {
    private val sr = 44100

    @Test
    fun constantGridMatchesRigidBeatGridWithinOneFrame() {
        for (bpm in doubleArrayOf(120.0, 126.0, 128.0, 174.0, 99.7)) {
            val beats = 256
            val g = MasterGrid.constant(sr, bpm, beats)
            val period = 60.0 * sr / bpm
            val rigid = BeatGrid.rigid(bpm, sr, 0L, Math.round(beats * period) + 1)
            assertEquals(beats, g.beatCount)
            assertTrue(rigid.beatCount >= beats + 1)
            for (k in 0..beats) assertTrue(abs(g.beatFrames[k] - rigid.beatFrames[k]) <= 1, "bpm $bpm beat $k: ${g.beatFrames[k]} vs ${rigid.beatFrames[k]}")
            assertTrue(abs(g.totalFrames - beats * period) <= 1.0, "total length drifts at $bpm: ${g.totalFrames} vs ${beats * period}")
        }
    }

    @Test
    fun glideGridEndpointsMonotonicAndTotalLength() {
        val g = MasterGrid.glide(sr, 120.0, 128.0, glideBeats = 16, holdBeats = 4, curve = GlideCurve.LINEAR)
        assertEquals(20, g.beatCount)
        assertEquals(Math.round(60.0 * sr / 120.0), g.periodFrames(0), "first period must be A's")
        assertEquals(Math.round(60.0 * sr / 128.0), g.periodFrames(19), "last period must be B's")
        assertEquals(120.0, g.bpmStart); assertEquals(128.0, g.bpmEnd)
        assertEquals(128.0, g.bpmPerBeat[16], "first hold beat is exactly at B's tempo")
        for (k in 1 until g.beatFrames.size) assertTrue(g.beatFrames[k] > g.beatFrames[k - 1])
        for (k in 1 until g.beatCount) assertTrue(g.bpmPerBeat[k] >= g.bpmPerBeat[k - 1], "tempo must not decrease in an upward glide")
        var sum = 0.0
        for (k in 0 until g.beatCount) sum += 60.0 * sr / g.bpmPerBeat[k]
        assertTrue(abs(g.totalFrames - sum) <= 1.0, "total ${g.totalFrames} vs sum of periods $sum")
        // S-curve and EXP hit the same endpoints
        for (c in listOf(GlideCurve.S_CURVE, GlideCurve.EXP)) {
            val h = MasterGrid.glide(sr, 120.0, 128.0, 16, 4, c)
            assertEquals(120.0, h.bpmPerBeat[0]); assertEquals(128.0, h.bpmPerBeat[16]); assertEquals(128.0, h.bpmEnd)
        }
        // pre-roll + sub-range via build
        val b = MasterGrid.build(sr, 100.0, 110.0, beats = 24, curve = GlideCurve.S_CURVE, glideStartBeat = 4, glideEndBeat = 20)
        assertEquals(100.0, b.bpmPerBeat[3]); assertTrue(b.bpmPerBeat[5] > 100.0); assertEquals(110.0, b.bpmPerBeat[20]); assertEquals(110.0, b.bpmPerBeat[23])
    }

    @Test
    fun frameAndBeatConversionsInvertEachOther() {
        val g = MasterGrid.glide(sr, 120.0, 128.0, 16, 4, GlideCurve.S_CURVE)
        for (k in 0..g.beatCount) {
            assertEquals(g.beatFrames[k], g.frameOfBeat(k.toDouble()))
            assertEquals(k.toDouble(), g.beatAtFrame(g.beatFrames[k]), 1e-9)
        }
        for (beat in doubleArrayOf(0.25, 3.5, 7.9, 15.999, 19.5)) {
            val f = g.frameOfBeat(beat)
            assertEquals(beat, g.beatAtFrame(f), 1e-4)
        }
        assertEquals(g.bpmPerBeat[3], g.bpmAt(3.7))
        assertEquals(g.bpmEnd, g.bpmAt(99.0))
        assertTrue(g.isDownbeat(0) && g.isDownbeat(4) && !g.isDownbeat(5))
        val s = g.slice(4, 8)
        assertEquals(8, s.beatCount); assertEquals(0L, s.startFrame); assertEquals(g.beatFrames[12] - g.beatFrames[4], s.totalFrames)
        assertEquals(g.frameOfBeat(-1.0), -g.periodFrames(0))
    }

    @Test
    fun fromPlanReadsGridParams() {
        val a = SongFixtures.song("A", 120.0, 0, Mode.MAJOR, bars = 8, introBars = 0, outroBars = 0)
        val b = SongFixtures.song("B", 126.0, 9, Mode.MINOR, bars = 8, introBars = 0, outroBars = 0)
        val f = SongFixtures.features(a, b)
        val prefs = SongFixtures.prefs(a, b)
        val base = TransitionPlan("x", Params.EMPTY, 0L, 0L, FrameRange(0, 10), FrameRange(0, 10), expectedOutputFrames = sr * 10)
        val c = MasterGrid.fromPlan(base, a.analysis, b.analysis, f, prefs)
        assertEquals(120.0, c.bpmStart); assertEquals(120.0, c.bpmEnd)
        assertTrue(c.totalFrames >= sr * 10, "constant grid must cover expectedOutputFrames")
        val gp = base.copy(params = Params.EMPTY.with(MasterGrid.PARAM_MODE, MasterGrid.MODE_GLIDE).with(MasterGrid.PARAM_GLIDE_BARS, 4).with(MasterGrid.PARAM_HOLD_BARS, 1).with(MasterGrid.PARAM_CURVE, "linear"))
        val g = MasterGrid.fromPlan(gp, a.analysis, b.analysis, f, prefs)
        assertEquals(20, g.beatCount)
        assertEquals(120.0, g.bpmStart)
        assertEquals(126.0, g.bpmEnd, 1e-9)
    }
}
