package dev.muisc.transitions.custom

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TempoRelation
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One profile directory shared by several stores: the Lab keeps one [UserProfile] for its lifetime while
 * `muisc rate` / `muisc pin` (or the app) write the same files from other [UserProfile]s or other processes.
 * Nothing one of them saved may be lost by another's write, and reads see what the others saved.
 */
class SharedProfileTest {

    private val features = PairFeatures(
        tempoRatio = 1.02, tempoRelation = TempoRelation.SAME, stretchPercent = 2.0,
        camelotDistance = 0, bestPitchShiftSemitones = 0, camelotDistanceAfterShift = 0,
        loudnessDeltaLu = 0.0, energyDelta = 0.1, vocalClash = 0.0, spectralSimilarity = 0.8,
        outro = OutroType.BEAT_OUTRO, intro = IntroType.BEAT_INTRO, outroBeatsAvailable = 64, introBeatsAvailable = 64,
        gridConfidenceA = 0.9, gridConfidenceB = 0.9, keyStrengthA = 0.8, keyStrengthB = 0.8, lowEndShareA = 0.3, lowEndShareB = 0.3,
    )

    @Test
    fun ratingsFromTwoProfilesOnOneDirectoryBothSurvive(@TempDir dir: File) {
        val lab = UserProfile(dir)
        lab.feedback.learner // the Lab's first plan loads the (empty) tallies
        val cli = UserProfile(dir)
        repeat(5) { cli.feedback.record("echoOut", features, Rating.Up) }
        lab.feedback.record("crossfade", features, Rating.Down)

        val onDisk = FeedbackLearner.fromJson(File(dir, "feedback.json").readText()).snapshot()
        assertEquals(setOf("crossfade", "echoOut"), onDisk.keys, "the Lab's write must not erase the CLI's ratings: $onDisk")
        assertEquals(5, onDisk.getValue("echoOut").values.single().n)
        assertEquals(1, onDisk.getValue("crossfade").values.single().n)
        // And the CLI sees the Lab's rating without being restarted.
        assertEquals(1, cli.feedback.learner.factor("crossfade", features).ratings)
    }

    @Test
    fun aRunningProfileSeesRatingsMadeElsewhereOnRead(@TempDir dir: File) {
        val lab = UserProfile(dir)
        val customization = lab.customization() // held for the Lab's lifetime (LabContext.customization)
        assertEquals(0, customization.learned("bassSwap", features).ratings)
        UserProfile(dir).feedback.record("bassSwap", features, Rating.Up)
        assertEquals(1, customization.learned("bassSwap", features).ratings, "the planner of a running profile sees the new rating")
        assertEquals(1, lab.feedback.learner.table().size)
        assertEquals(1, lab.feedback.learner.snapshot().getValue("bassSwap").values.single().n)
    }

    @Test
    fun aFileThatBreaksWhileAStoreIsOpenIsStillKeptAside(@TempDir dir: File) {
        val file = File(dir, "feedback.json")
        val store = FileFeedbackStore(file)
        store.record("bassSwap", features, Rating.Up)
        file.writeText("{ hand-edited and broken")
        assertTrue(store.warnings.single().contains("cannot read feedback file"), store.warnings.toString())
        assertEquals(1, store.learner.factor("bassSwap", features).ratings, "the last readable tallies stay in use")
        store.record("echoOut", features, Rating.Down)
        assertEquals("{ hand-edited and broken", File(dir, "feedback.json.corrupt").readText())
        assertTrue(store.warnings.isEmpty(), "the file is readable again: ${store.warnings}")
        val back = FileFeedbackStore(file).learner
        assertEquals(1, back.factor("echoOut", features).ratings)
        assertEquals(1, back.factor("bassSwap", features).ratings)
    }

    @Test
    fun whenTheLockCannotBeTakenTheRatingIsStillSavedWithAWarning(@TempDir dir: File) {
        val file = File(dir, "feedback.json")
        File(dir, ".feedback.json.lock").mkdirs() // a directory where the lock file should be: it cannot be opened
        val store = FileFeedbackStore(file)
        store.record("bassSwap", features, Rating.Up)
        assertEquals(1, FileFeedbackStore(file).learner.factor("bassSwap", features).ratings)
        assertTrue(store.warnings.single().contains("without the inter-process lock"), store.warnings.toString())

        val pins = FilePinStore(File(dir, "pins.json"))
        File(dir, ".pins.json.lock").mkdirs()
        pins.set(PairPin("a", "b", "crossfade"))
        assertEquals(1, FilePinStore(File(dir, "pins.json")).list().size)
        assertTrue(pins.warnings.single().contains("without the inter-process lock"), pins.warnings.toString())
    }

    @Test
    fun pinsSetByTwoProfilesOnOneDirectoryBothSurvive(@TempDir dir: File) {
        val lab = UserProfile(dir)
        assertTrue(lab.pins.list().isEmpty())
        val cli = UserProfile(dir)
        cli.pins.set(PairPin("a", "b", "bassSwap"))
        lab.pins.set(PairPin("c", "d", "echoOut"))
        assertEquals(setOf("bassSwap", "echoOut"), FilePinStore(File(dir, "pins.json")).list().map { it.strategyId }.toSet())
        assertEquals(2, cli.pins.list().size)
    }

    @Test
    fun concurrentProcessesLoseNoRatings(@TempDir dir: File) {
        runWriters(dir, "feedback")
        val tallies = FeedbackLearner.fromJson(File(dir, "feedback.json").readText()).snapshot()
        assertEquals(3 * PER_PROCESS, tallies.getValue("bassSwap").values.sumOf { it.n }, "every rating of every process is on disk")
    }

    @Test
    fun concurrentProcessesLoseNoPins(@TempDir dir: File) {
        runWriters(dir, "pins")
        val pins = FilePinStore(File(dir, "pins.json")).list()
        assertEquals(3 * PER_PROCESS, pins.size, "every pin of every process is on disk")
    }

    /** Three JVMs writing into [dir] at the same time, as the Lab, `muisc rate` and `muisc pin` can. */
    private fun runWriters(dir: File, what: String) {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val tags = listOf("p", "q", "r")
        val procs = tags.map { tag ->
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), SharedProfileWriter::class.java.name, dir.path, what, tag, PER_PROCESS.toString())
                .redirectErrorStream(true)
                .redirectOutput(File(dir, "$tag.log"))
                .start()
        }
        for ((i, p) in procs.withIndex()) {
            assertTrue(p.waitFor(180, TimeUnit.SECONDS), "writer $i timed out")
            assertEquals(0, p.exitValue(), "writer $i failed: " + File(dir, tags[i] + ".log").readText())
        }
    }

    private companion object {
        const val PER_PROCESS = 150
    }
}

/** Child process of [SharedProfileTest]'s concurrency tests: `<dir> feedback|pins <tag> <count>`. */
object SharedProfileWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0])
        val what = args[1]
        val tag = args[2]
        val count = args[3].toInt()
        val profile = UserProfile(dir)
        val f = PairFeatures(
            tempoRatio = 1.0, tempoRelation = TempoRelation.SAME, stretchPercent = 0.0,
            camelotDistance = 0, bestPitchShiftSemitones = 0, camelotDistanceAfterShift = 0,
            loudnessDeltaLu = 0.0, energyDelta = 0.0, vocalClash = 0.0, spectralSimilarity = 0.8,
            outro = OutroType.BEAT_OUTRO, intro = IntroType.BEAT_INTRO, outroBeatsAvailable = 64, introBeatsAvailable = 64,
            gridConfidenceA = 0.9, gridConfidenceB = 0.9, keyStrengthA = 0.8, keyStrengthB = 0.8, lowEndShareA = 0.3, lowEndShareB = 0.3,
        )
        for (i in 0 until count) {
            if (what == "feedback") profile.feedback.record("bassSwap", f, if (i % 2 == 0) Rating.Up else Rating.Down)
            else profile.pins.set(PairPin("$tag-$i", "x", "crossfade"))
        }
    }
}
