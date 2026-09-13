package dev.muisc.analysis

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.GridKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeatGridTest {
    private val sr = 44100

    @Test
    fun rigidGridRoundTrips() {
        val g = BeatGrid.rigid(120.0, sr, firstBeatFrame = 1000, endFrame = 10 * sr.toLong(), downbeatPhase = 1)
        assertEquals(GridKind.RIGID, g.kind)
        val period = 60.0 * sr / 120.0
        assertEquals(1000L + Math.round(4 * period), g.frameOfBeat(4.0))
        assertEquals(4.5, g.beatAtFrame(g.frameOfBeat(4.5)), 1e-3)
        // extrapolation
        assertEquals(1000L - Math.round(2 * period), g.frameOfBeat(-2.0))
        assertTrue(g.frameOfBeat(g.beatCount + 3.0) > g.beatFrames.last())
        // downbeats: phase 1 → beats 1,5,9...
        assertTrue(g.isDownbeat(1) && g.isDownbeat(5) && !g.isDownbeat(0) && !g.isDownbeat(4))
        assertEquals(5, g.nextDownbeat(1.5))
        assertEquals(1, g.previousDownbeat(4.9))
        assertEquals(5, g.nearestDownbeat(4.9))
        assertEquals(0, g.barOfBeat(2)); assertEquals(-1, g.barOfBeat(0)); assertEquals(9, g.beatOfBar(2))
    }

    @Test
    fun phrases() {
        val g = BeatGrid.rigid(100.0, sr, 0, 60L * sr, phraseStartBeat = 4, phraseBars = 8)
        assertTrue(g.isPhraseStart(4) && g.isPhraseStart(36) && !g.isPhraseStart(8))
        assertEquals(36, g.nextPhraseStart(5.0))
        assertEquals(4, g.previousPhraseStart(35.0))
    }

    @Test
    fun flexGridInterpolates() {
        val g = BeatGrid(GridKind.FLEX, 0.0, longArrayOf(0, 100, 250, 300), confidence = 0.5f)
        assertEquals(175L, g.frameOfBeat(1.5))
        assertEquals(1.5, g.beatAtFrame(175), 1e-9)
        assertEquals(3.0 + 1.0, g.beatAtFrame(350), 1e-9) // extrapolate with last period 50
    }
}
