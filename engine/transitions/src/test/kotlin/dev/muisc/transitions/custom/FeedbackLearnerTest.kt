package dev.muisc.transitions.custom

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TempoRelation
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Bounds, monotonicity, shrinkage, buckets, persistence and determinism of [FeedbackLearner]. */
class FeedbackLearnerTest {

    private fun features(gridA: Double = 0.9, gridB: Double = 0.9, stretch: Double = 2.0, keyDist: Int = 0, energyDelta: Double = 0.1) = PairFeatures(
        tempoRatio = 1.0 + stretch / 100.0, tempoRelation = TempoRelation.SAME, stretchPercent = stretch,
        camelotDistance = keyDist, bestPitchShiftSemitones = 0, camelotDistanceAfterShift = keyDist,
        loudnessDeltaLu = 0.0, energyDelta = energyDelta, vocalClash = 0.0, spectralSimilarity = 0.8,
        outro = OutroType.BEAT_OUTRO, intro = IntroType.BEAT_INTRO, outroBeatsAvailable = 64, introBeatsAvailable = 64,
        gridConfidenceA = gridA, gridConfidenceB = gridB, keyStrengthA = 0.8, keyStrengthB = 0.8, lowEndShareA = 0.3, lowEndShareB = 0.3,
    )

    private val bucket = ContextBucket(matched = true, inKey = true, rising = true)

    @Test
    fun bucketsFollowTheFeatures() {
        assertEquals(ContextBucket(true, true, true), ContextBucket.of(features()))
        assertEquals(ContextBucket(false, true, true), ContextBucket.of(features(gridB = 0.3)), "grid not confident")
        assertEquals(ContextBucket(false, true, true), ContextBucket.of(features(stretch = 9.0)), "too far apart to match")
        assertEquals(ContextBucket(true, true, true), ContextBucket.of(features(keyDist = 1)))
        assertEquals(ContextBucket(true, false, true), ContextBucket.of(features(keyDist = 2)))
        assertEquals(ContextBucket(true, true, true), ContextBucket.of(features(energyDelta = 0.0)))
        assertEquals(ContextBucket(true, true, false), ContextBucket.of(features(energyDelta = -0.01)))
        assertEquals(8, ContextBucket.ALL.size)
        assertEquals(8, ContextBucket.ALL.map { it.key }.toSet().size)
        assertEquals(8, ContextBucket.ALL.map { it.label }.toSet().size)
        for (b in ContextBucket.ALL) assertEquals(b, ContextBucket.parse(b.key))
        assertEquals("unmatched, key clash, falling", ContextBucket(false, false, false).label)
    }

    @Test
    fun ratingsParse() {
        assertEquals(Rating.Up, Rating.parse("up"))
        assertEquals(Rating.Down, Rating.parse(" DOWN "))
        assertEquals(0.0, Rating.parse("1").value)
        assertEquals(0.5, Rating.parse("3").value)
        assertEquals(1.0, Rating.parse("5").value)
        assertFailsWith<IllegalArgumentException> { Rating.parse("6") }
        assertFailsWith<IllegalArgumentException> { Rating.parse("0") }
        assertFailsWith<IllegalArgumentException> { Rating.parse("meh") }
    }

    @Test
    fun noRatingsIsExactlyNeutralAndTheFormulaIsTheDocumentedOne() {
        val l = FeedbackLearner()
        assertEquals(1.0, l.factor("bassSwap", bucket).multiplier, 0.0)
        assertEquals(0, l.factor("bassSwap", bucket).ratings)
        l.record("bassSwap", bucket, Rating.Up)
        assertEquals(1.10, l.factor("bassSwap", bucket).multiplier, 1e-12)
        repeat(4) { l.record("bassSwap", bucket, Rating.Up) }
        assertEquals(0.5 + 7.0 / 9.0, l.factor("bassSwap", bucket).multiplier, 1e-12)
        val d = FeedbackLearner().apply { record("x", bucket, Rating.Down) }
        assertEquals(0.90, d.factor("x", bucket).multiplier, 1e-12)
        val mid = FeedbackLearner().apply { repeat(7) { record("x", bucket, Rating.Stars(3)) } }
        assertEquals(1.0, mid.factor("x", bucket).multiplier, 1e-12, "3 stars is neutral")
    }

    @Test
    fun weightsStayInsideTheBoundsForAnyRatingSequence() {
        val rnd = Random(17)
        repeat(200) {
            val l = FeedbackLearner()
            val n = rnd.nextInt(0, 400)
            repeat(n) { l.record("s", bucket, if (rnd.nextBoolean()) Rating.Up else Rating.Stars(1 + rnd.nextInt(5))) }
            val m = l.factor("s", bucket).multiplier
            assertTrue(m > FeedbackLearner.MIN && m < FeedbackLearner.MAX, "$m after $n ratings")
        }
        val allUp = FeedbackLearner().apply { repeat(100_000) { record("s", bucket, Rating.Up) } }
        assertTrue(allUp.factor("s", bucket).multiplier in 1.49..1.5)
        val allDown = FeedbackLearner().apply { repeat(100_000) { record("s", bucket, Rating.Down) } }
        assertTrue(allDown.factor("s", bucket).multiplier in 0.5..0.51)
    }

    @Test
    fun anotherUpVoteNeverLowersTheWeightAndADownVoteNeverRaisesIt() {
        val rnd = Random(23)
        repeat(300) {
            val l = FeedbackLearner()
            repeat(rnd.nextInt(0, 60)) { l.record("s", bucket, Rating.Stars(1 + rnd.nextInt(5))) }
            val before = l.factor("s", bucket).multiplier
            val t = l.tally("s", bucket)
            val up = FeedbackLearner.multiplier(t.plus(Rating.Up))
            val down = FeedbackLearner.multiplier(t.plus(Rating.Down))
            assertTrue(up > before, "up: $before → $up after $t")
            assertTrue(down < before, "down: $before → $down after $t")
        }
    }

    @Test
    fun fewRatingsAreShrunkTowardOne() {
        fun ups(n: Int) = FeedbackLearner().apply { repeat(n) { record("s", bucket, Rating.Up) } }.factor("s", bucket).multiplier
        val series = listOf(1, 2, 5, 10, 20, 50).map { ups(it) }
        for (i in 1 until series.size) assertTrue(series[i] > series[i - 1], series.toString())
        assertTrue(ups(1) < 1.15, "one vote moves the weight only a little: ${ups(1)}")
        // Same proportion of up-votes, more evidence → further from 1.
        fun mixed(up: Int, down: Int) = FeedbackLearner().apply {
            repeat(up) { record("s", bucket, Rating.Up) }; repeat(down) { record("s", bucket, Rating.Down) }
        }.factor("s", bucket).multiplier
        assertTrue(abs(mixed(3, 1) - 1.0) < abs(mixed(30, 10) - 1.0))
    }

    @Test
    fun bucketsAndStrategiesAreIndependent() {
        val l = FeedbackLearner()
        repeat(10) { l.record("echoOut", bucket, Rating.Down) }
        assertEquals(1.0, l.factor("echoOut", ContextBucket(false, true, true)).multiplier, 0.0)
        assertEquals(1.0, l.factor("bassSwap", bucket).multiplier, 0.0)
        assertTrue(l.factor("echoOut", bucket).multiplier < 1.0)
        assertEquals("learned ×0.64 from 10 ratings in 'matched, in key, rising'", l.factor("echoOut", bucket).describe())
    }

    @Test
    fun jsonRoundTripIsExactAndDeterministic() {
        val rnd = Random(4)
        val events = List(80) { Triple(listOf("bassSwap", "echoOut", "recipe:dub")[rnd.nextInt(3)], ContextBucket.ALL[rnd.nextInt(8)], Rating.Stars(1 + rnd.nextInt(5))) }
        val l1 = FeedbackLearner().apply { events.forEach { (s, b, r) -> record(s, b, r) } }
        val l2 = FeedbackLearner().apply { events.forEach { (s, b, r) -> record(s, b, r) } }
        assertEquals(l1.toJson(), l2.toJson())
        val back = FeedbackLearner.fromJson(l1.toJson())
        assertEquals(l1.snapshot(), back.snapshot())
        assertEquals(l1.table(), back.table())
        assertEquals(l1.toJson(), back.toJson())
        assertFailsWith<IllegalArgumentException> { FeedbackLearner.fromJson("""{"version": 1, "strategies": {"x": {"sideways": {"n": 1, "sum": 1.0}}}}""") }
        assertFailsWith<IllegalArgumentException> { FeedbackLearner.fromJson("""{"version": 1, "strategies": {"x": {"matched.inkey.rising": {"n": 1, "sum": 3.0}}}}""") }
        assertFailsWith<IllegalArgumentException> { FeedbackLearner.fromJson("""{"version": 9}""") }
    }

    @Test
    fun fileStoreSavesEveryRatingAndKeepsAnUnreadableFile(@TempDir dir: File) {
        val file = File(dir, "feedback.json")
        val store = FileFeedbackStore(file)
        assertTrue(store.warnings.isEmpty())
        val f = store.record("bassSwap", features(), Rating.Up)
        assertEquals(1, f.ratings)
        store.record("bassSwap", features(), Rating.Up)
        assertEquals(2, FileFeedbackStore(file).learner.factor("bassSwap", features()).ratings)

        file.writeText("{ broken")
        val broken = FileFeedbackStore(file)
        assertTrue(broken.warnings.single().contains("cannot read feedback file"))
        assertEquals(0, broken.learner.factor("bassSwap", features()).ratings)
        broken.record("echoOut", features(), Rating.Down)
        assertEquals("{ broken", File(dir, "feedback.json.corrupt").readText(), "the unreadable ratings are kept, not overwritten")
        assertEquals(1, FileFeedbackStore(file).learner.factor("echoOut", features()).ratings)
    }

    // ---- implicit signals (skipped transitions) --------------------------------------------------------------------

    @Test
    fun oneSkipIsAQuarterOfADownVoteAndSkipsAloneAreBounded() {
        val l = FeedbackLearner()
        l.recordImplicit("bassSwap", bucket)
        val one = l.factor("bassSwap", bucket)
        assertEquals(0.5 + 2.0 / 4.25, one.multiplier, 1e-12, "p = K / (2K + 0.25)")
        assertEquals(0, one.ratings)
        assertEquals(1, one.implicit)
        val down = FeedbackLearner().apply { record("bassSwap", bucket, Rating.Down) }.factor("bassSwap", bucket).multiplier
        assertTrue(one.multiplier > down && one.multiplier < 1.0, "a skip is weaker evidence than a down-vote: ${one.multiplier} vs $down")
        // However many skips: never below 0.5 + K / (2K + CAP), and the cap is reached exactly at CAP / WEIGHT skips.
        val floor = 0.5 + FeedbackLearner.PRIOR_STRENGTH / (2 * FeedbackLearner.PRIOR_STRENGTH + FeedbackLearner.IMPLICIT_CAP)
        assertEquals(0.5 + 2.0 / 6.0, floor, 1e-12)
        val atCap = (FeedbackLearner.IMPLICIT_CAP / FeedbackLearner.IMPLICIT_WEIGHT).toInt()
        val many = FeedbackLearner().apply { repeat(100_000) { recordImplicit("s", bucket) } }.factor("s", bucket)
        assertEquals(floor, many.multiplier, 1e-12)
        assertEquals(100_000, many.implicit)
        assertEquals(floor, FeedbackLearner.multiplier(RatingTally(implicitN = atCap)), 1e-12)
        assertTrue(FeedbackLearner.multiplier(RatingTally(implicitN = atCap - 1)) > floor)
        // Skips never outweigh explicit ratings: 1 000 skips against 3 up-votes still leave the strategy favoured.
        val mixed = RatingTally(n = 3, sum = 3.0, implicitN = 1_000)
        assertTrue(FeedbackLearner.multiplier(mixed) > 1.0, "${FeedbackLearner.multiplier(mixed)}")
    }

    @Test
    fun anImplicitDownNeverRaisesTheWeightAndAnyMixStaysInsideTheBounds() {
        val rnd = Random(29)
        repeat(300) {
            var t = RatingTally()
            repeat(rnd.nextInt(0, 60)) {
                t = if (rnd.nextBoolean()) t.plus(Rating.Stars(1 + rnd.nextInt(5))) else t.plusImplicit(if (rnd.nextInt(4) == 0) Rating.Up else Rating.Down)
            }
            val before = FeedbackLearner.multiplier(t)
            assertTrue(before > FeedbackLearner.MIN && before < FeedbackLearner.MAX, "$before for $t")
            val afterSkip = FeedbackLearner.multiplier(t.plusImplicit(Rating.Down))
            assertTrue(afterSkip <= before, "skip: $before → $afterSkip after $t")
            assertTrue(FeedbackLearner.multiplier(t.plus(Rating.Up)) > before, "an up-vote still raises it after $t")
            assertTrue(FeedbackLearner.multiplier(t.plus(Rating.Down)) < before, "a down-vote still lowers it after $t")
        }
    }

    @Test
    fun skipsAreDescribedAndCountedApartFromRatings() {
        val l = FeedbackLearner()
        repeat(2) { l.record("echoOut", bucket, Rating.Up) }
        repeat(3) { l.recordImplicit("echoOut", bucket) }
        l.recordImplicit("bassSwap", bucket)
        val f = l.factor("echoOut", bucket)
        assertEquals(2, f.ratings)
        assertEquals(3, f.implicit)
        val m = "%.2f".format(f.multiplier)
        assertEquals("learned ×$m from 2 ratings and 3 skips in 'matched, in key, rising'", f.describe())
        assertEquals("learned ×0.97 from 1 skip in 'matched, in key, rising'", l.factor("bassSwap", bucket).describe())
        assertEquals(listOf("bassSwap", "echoOut"), l.table().map { it.first }, "a bucket with only skips is listed")
        assertEquals(1, l.table().first().second.implicit)
        // Without skips the text is exactly what it was.
        assertEquals("learned ×1.10 from 1 rating in 'matched, in key, rising'", FeedbackLearner().apply { record("x", bucket, Rating.Up) }.factor("x", bucket).describe())
    }

    @Test
    fun oldFilesStillDecodeAndFilesWithoutSkipsStayVersionOne() {
        // A feedback.json as engines before implicit signals wrote it.
        val old = """{
          "strategies": {
            "bassSwap": { "matched.inkey.rising": { "n": 2, "sum": 1.75 } }
          }
        }"""
        val l = FeedbackLearner.fromJson(old)
        assertEquals(RatingTally(2, 1.75), l.tally("bassSwap", bucket))
        assertEquals(0, l.factor("bassSwap", bucket).implicit)
        assertTrue("implicit" !in l.toJson() && "\"version\": 2" !in l.toJson(), l.toJson())
        assertEquals(FeedbackLearner.fromJson(l.toJson()).snapshot(), l.snapshot())

        l.recordImplicit("bassSwap", bucket)
        val text = l.toJson()
        assertTrue("\"version\": 2" in text && "\"implicitN\": 1" in text, text)
        val back = FeedbackLearner.fromJson(text)
        assertEquals(RatingTally(2, 1.75, 1, 0.0), back.tally("bassSwap", bucket))
        assertEquals(l.table(), back.table())
        assertFailsWith<IllegalArgumentException> { FeedbackLearner.fromJson("""{"version": 2, "strategies": {"x": {"matched.inkey.rising": {"implicitN": 1, "implicitSum": 2.0}}}}""") }
        assertFailsWith<IllegalArgumentException> { FeedbackLearner.fromJson("""{"version": 2, "strategies": {"x": {"matched.inkey.rising": {"implicitN": -1}}}}""") }
        assertFailsWith<IllegalArgumentException> { FeedbackLearner.fromJson("""{"version": 3}""") }
    }

    @Test
    fun fileStoreSavesImplicitSignalsThroughTheSamePathAsRatings(@TempDir dir: File) {
        val file = File(dir, "feedback.json")
        val store = FileFeedbackStore(file)
        store.record("bassSwap", features(), Rating.Up)
        val other = FileFeedbackStore(file) // another program sharing the file
        val f = other.recordImplicit("bassSwap", features())
        assertEquals(1, f.ratings, "the rating saved by the first store is kept")
        assertEquals(1, f.implicit)
        assertEquals(RatingTally(1, 1.0, 1, 0.0), store.learner.tally("bassSwap", bucket), "the first store follows the file")
        assertTrue(File(dir, ".feedback.json.lock").isFile, "saved under the same inter-process lock")
    }

    @Test
    fun resetForgetsOneStrategyOrAllKeepsABackupAndTheLearnerFollows(@TempDir dir: File) {
        val file = File(dir, "feedback.json")
        val store = FileFeedbackStore(file)
        repeat(3) { store.record("bassSwap", features(), Rating.Up) }
        store.record("crossfade", features(), Rating.Down)
        store.recordImplicit("crossfade", features())
        val before = file.readText()

        store.reset("crossfade")
        assertEquals(before, File(dir, "feedback.json.bak").readText(), "the file as it was is kept")
        assertEquals(RatingTally(), store.learner.tally("crossfade", bucket))
        assertEquals(3, store.learner.tally("bassSwap", bucket).n)
        assertEquals(3, FileFeedbackStore(file).learner.tally("bassSwap", bucket).n)
        assertTrue("crossfade" !in file.readText())

        store.reset(null)
        assertTrue(!file.exists(), "nothing left: no file")
        assertEquals(emptyList(), store.learner.table())
        assertEquals(3, FeedbackLearner.fromJson(File(dir, "feedback.json.bak").readText()).tally("bassSwap", bucket).n)
        store.reset(null) // nothing to reset: no error, the backup stays
        assertTrue(File(dir, "feedback.json.bak").isFile)
    }

    /**
     * The reset is a read-modify-write under the file's lock: while another writer holds the lock (here the test
     * thread, standing in for a `record` in another thread or process), the reset waits, and then starts from what
     * that writer saved, so the rating is neither lost nor is the forgotten strategy brought back.
     */
    @Test
    fun resetWaitsForTheLockAndStartsFromWhatIsOnDisk(@TempDir dir: File) {
        val file = File(dir, "feedback.json")
        val store = FileFeedbackStore(file)
        repeat(3) { store.record("bassSwap", features(), Rating.Up) }
        store.record("crossfade", features(), Rating.Down)
        val writer = FileFeedbackStore(file)
        var failure: Throwable? = null
        val resetter = Thread { try { store.reset("crossfade") } catch (t: Throwable) { failure = t } }
        FileLocks.withLock(file, onDegraded = {}) {
            resetter.start()
            // Wait until the reset is parked on the lock (or, if it does not take the lock, has finished).
            val deadline = System.nanoTime() + 10_000_000_000L
            while (resetter.state != Thread.State.WAITING && resetter.state != Thread.State.TERMINATED && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(Thread.State.WAITING, resetter.state, "the reset must wait for the lock")
            assertTrue(!File(dir, "feedback.json.bak").exists(), "nothing touched while the lock is held")
            writer.record("bassSwap", features(), Rating.Up) // re-entrant for this thread: a concurrent rating
            writer.record("crossfade", features(), Rating.Down)
        }
        resetter.join(10_000)
        failure?.let { throw it }
        val onDisk = FileFeedbackStore(file).learner
        assertEquals(4, onDisk.tally("bassSwap", bucket).n, "the concurrent rating is kept")
        assertEquals(0, onDisk.tally("crossfade", bucket).n, "the forgotten strategy stays forgotten")
        assertEquals(4, store.learner.tally("bassSwap", bucket).n)
    }
}
