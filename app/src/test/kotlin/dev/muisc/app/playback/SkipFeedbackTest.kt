package dev.muisc.app.playback

import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.player.TransitionSkip
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.ContextBucket
import dev.muisc.transitions.custom.FeedbackLearner
import dev.muisc.transitions.custom.RatingTally
import dev.muisc.transitions.synthetic.SyntheticTracks
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Learn from skips": a [TransitionSkip] from the coordinator becomes one weak down-vote, or nothing when switched off. */
class SkipFeedbackTest {

    private val features = run {
        val a = SyntheticTracks.trackRef(SyntheticSong(bpm = 124.0, tonic = 9, bars = 48, introBars = 8, outroBars = 8)).trackRef
        val b = SyntheticTracks.trackRef(SyntheticSong(bpm = 124.0, tonic = 4, bars = 48, introBars = 8, outroBars = 8, seed = 11)).trackRef
        DefaultPairAnalyzer().features(a.analysis, b.analysis, TransitionPrefs())
    }
    private val bucket = ContextBucket.of(features)

    private fun skip(phase: TransitionSkip.Phase = TransitionSkip.Phase.DURING) =
        TransitionSkip("a", "b", "bassSwap", features, phase, if (phase == TransitionSkip.Phase.DURING) 0.0 else 4.0)

    @Test
    fun aSkipIsSavedAsExactlyOneWeakDownVoteThroughTheStore() = runTest {
        val dir = Files.createTempDirectory("muisc-skips").toFile()
        val dj = DjCustomization(dir)
        var recorded = 0
        val feedback = SkipFeedback(dj, this, enabled = { true }, io = StandardTestDispatcher(testScheduler))
        feedback.onRecorded = { recorded++ }

        feedback.onTransitionSkipped(skip())
        advanceUntilIdle()

        val tally = dj.planner.current.customization.learner!!.tally("bassSwap", bucket)
        assertEquals(RatingTally(n = 0, sum = 0.0, implicitN = 1, implicitSum = 0.0), tally)
        val learned = dj.learned().single().second
        assertEquals(0, learned.ratings)
        assertEquals(1, learned.implicit)
        assertEquals(0.5 + 2.0 / 4.25, learned.multiplier, 1e-12, "a quarter of a down-vote (a down-vote alone is ×0.90)")
        assertEquals(1, recorded)
        // On disk, readable by the CLI and the Lab, and behind the same lock as ratings.
        assertEquals(tally, FeedbackLearner.fromJson(File(dir, "feedback.json").readText()).tally("bassSwap", bucket))
        assertTrue(File(dir, ".feedback.json.lock").isFile)
        assertEquals(emptyList(), dj.problems())
    }

    @Test
    fun switchedOffNothingIsWritten() = runTest {
        val dir = Files.createTempDirectory("muisc-skips").toFile()
        val dj = DjCustomization(dir)
        var recorded = 0
        val feedback = SkipFeedback(dj, this, enabled = { false }, io = StandardTestDispatcher(testScheduler))
        feedback.onRecorded = { recorded++ }

        feedback.onTransitionSkipped(skip())
        feedback.onTransitionSkipped(skip(TransitionSkip.Phase.JUST_AFTER))
        advanceUntilIdle()

        assertEquals(emptyList(), dj.learned())
        assertFalse(File(dir, "feedback.json").exists())
        assertEquals(0, recorded)
    }

    @Test
    fun theToggleIsReadWhenTheSkipIsRecorded() = runTest {
        val dir = Files.createTempDirectory("muisc-skips").toFile()
        val dj = DjCustomization(dir)
        var on = true
        val feedback = SkipFeedback(dj, this, enabled = { on }, io = StandardTestDispatcher(testScheduler))
        feedback.onTransitionSkipped(skip()); advanceUntilIdle()
        on = false
        feedback.onTransitionSkipped(skip()); advanceUntilIdle()
        assertEquals(1, dj.learned().single().second.implicit)
    }

    @Test
    fun aSkipThatCannotBeSavedIsAProblemNotACrash() = runTest {
        val dir = Files.createTempDirectory("muisc-skips").toFile()
        val dj = DjCustomization(dir)
        // feedback.json is a directory that is not empty: the store cannot write it.
        File(dir, "feedback.json/keep").mkdirs()
        val feedback = SkipFeedback(dj, this, enabled = { true }, io = StandardTestDispatcher(testScheduler))
        feedback.onTransitionSkipped(skip()); advanceUntilIdle()
        assertTrue(dj.problems().any { "skipped transition could not be saved" in it }, dj.problems().toString())
    }
}
