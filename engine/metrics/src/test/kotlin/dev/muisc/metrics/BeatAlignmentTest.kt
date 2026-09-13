package dev.muisc.metrics

import dev.muisc.transitions.AutomationLane
import dev.muisc.transitions.LanePoint
import dev.muisc.transitions.core.MasterGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `beatAlignmentMs` on a click track with a known beat list: the render's own onsets (1 ms ODF with parabolic
 * sub-block interpolation) must land on the master beats to well under a millisecond, and a deliberately
 * dragged render must be graded WARN / FAIL by the 5 ms / 12 ms thresholds.
 */
class BeatAlignmentTest {

    private val sr = MetricsFixtures.SR

    @Test
    fun onsetsOfAClickTrackLandOnItsBeats() {
        val (audio, beats) = MetricsFixtures.clickTrack(bpm = 120.0, beats = 16)
        val rendered = MetricsFixtures.standalone(audio)
        val metrics = ArtifactMetrics.beatAlignment(rendered, beats)
        assertEquals(2, metrics.size)
        val median = metrics[0]
        val worst = metrics[1]
        assertEquals(ArtifactMetrics.BEAT_ALIGNMENT_MS, median.id)
        assertEquals(ArtifactMetrics.BEAT_ALIGNMENT_MAX_MS, worst.id)
        assertTrue(median.value < 2.0, "median error ${median.value} ms")
        assertTrue(worst.value < 5.0, "max error ${worst.value} ms")
        assertEquals(Verdict.PASS, median.verdict)
        assertEquals(Verdict.PASS, worst.verdict)
        // Every beat was matched: the detector finds all 16 onsets.
        assertEquals(beats.size, Signals.onsetTimesSec(audio).size)
    }

    @Test
    fun draggedBeatsAreGradedByTheThresholds() {
        val (audio, beats) = MetricsFixtures.clickTrack(bpm = 120.0, beats = 16)
        val rendered = MetricsFixtures.standalone(audio)
        fun shifted(ms: Double) = DoubleArray(beats.size) { beats[it] + ms / 1000.0 }

        val warn = ArtifactMetrics.beatAlignment(rendered, shifted(8.0))
        assertEquals(Verdict.WARN, warn[0].verdict, "8 ms off: ${warn[0]}")
        assertEquals(Verdict.WARN, warn[1].verdict)
        assertEquals(8.0, warn[0].value, 1.0)

        val fail = ArtifactMetrics.beatAlignment(rendered, shifted(15.0))
        assertEquals(Verdict.FAIL, fail[1].verdict, "15 ms off: ${fail[1]}")
        assertEquals(Verdict.WARN, fail[0].verdict, "the median only ever warns")
    }

    @Test
    fun beatsComeFromThePlanLaneOrTheMasterGrid() {
        val (audio, beats) = MetricsFixtures.clickTrack(bpm = 120.0, beats = 8)
        val lane = AutomationLane(ArtifactMetrics.MASTER_BEAT_LANE, beats.mapIndexed { i, t -> LanePoint(t, i.toDouble()) })
        val rendered = MetricsFixtures.standalone(audio, listOf(lane))
        val fromLane = ArtifactMetrics.evaluate(rendered, null)
        val median = assertNotNull(fromLane.metric(ArtifactMetrics.BEAT_ALIGNMENT_MS), fromLane.summary())
        assertTrue(median.value < 2.0, "$median")
        assertEquals(beats.toList(), ArtifactMetrics.planMasterBeats(rendered)!!.toList())

        // The same beats expressed as a MasterGrid (frames from its own start) give the same answer.
        val grid = MasterGrid.constant(sr, 120.0, beats.size)
        val shifted = MetricsFixtures.standalone(audio.slice(Math.round(beats[0] * sr).toInt(), audio.frames))
        val fromGrid = ArtifactMetrics.evaluate(shifted, null, grid)
        assertTrue(fromGrid.value(ArtifactMetrics.BEAT_ALIGNMENT_MS)!! < 2.0, fromGrid.summary())
        assertEquals(0.0, ArtifactMetrics.beatsOf(grid)[0])
        assertEquals(0.5, ArtifactMetrics.beatsOf(grid)[1], 1e-6)
    }

    @Test
    fun noBeatsOrNoOnsetsMeansNoMetric() {
        val (audio, beats) = MetricsFixtures.clickTrack(beats = 4)
        val rendered = MetricsFixtures.standalone(audio)
        assertEquals(emptyList(), ArtifactMetrics.beatAlignment(rendered, null))
        assertEquals(emptyList(), ArtifactMetrics.beatAlignment(rendered, DoubleArray(0)))
        // Beats far outside the segment match nothing and are not reported as a huge error.
        assertEquals(emptyList(), ArtifactMetrics.beatAlignment(rendered, DoubleArray(beats.size) { 1000.0 + it }))
    }
}
