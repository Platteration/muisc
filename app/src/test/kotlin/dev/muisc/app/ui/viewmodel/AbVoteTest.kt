package dev.muisc.app.ui.viewmodel

import dev.muisc.app.data.db.Song
import dev.muisc.app.playback.CustomizationApi
import dev.muisc.app.playback.DjResult
import dev.muisc.app.playback.NoOpCustomization
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.RenderReport
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.Rating
import dev.muisc.transitions.planner.DefaultTransitionPlanner
import dev.muisc.transitions.synthetic.SyntheticTracks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The blind A/B vote of the Android Lab ([castAbVote], which [LabViewModel.voteAb] calls). */
class AbVoteTest {

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", artistId = 1L, album = "Album", albumId = 10L, albumArtist = null,
        track = id.toInt(), disc = 1, year = 2024, durationMs = 180_000L, uri = "content://media/external/audio/media/$id",
        path = "/music/$id.mp3", mimeType = "audio/mpeg", size = 1L, dateAdded = 0L, dateModified = 0L, genre = null,
    )

    private val a = song(1)
    private val b = song(2)

    /** Two real candidates for a synthetic pair, planned once for the whole class. */
    private companion object {
        val candidates: Pair<PlanCandidate, PlanCandidate> by lazy {
            val ta = SyntheticTracks.trackRef(SyntheticSong(bpm = 124.0, tonic = 9, bars = 48, introBars = 8, outroBars = 8)).trackRef
            val tb = SyntheticTracks.trackRef(SyntheticSong(bpm = 124.0, tonic = 4, bars = 48, introBars = 8, outroBars = 8, seed = 11)).trackRef
            val ranked = DefaultTransitionPlanner(DefaultStrategyRegistry.default()).plan(ta, tb, TransitionPrefs(), 0L, null)
            val first = ranked.best
            first to ranked.candidates.first { it.strategy.id != first.strategy.id }
        }
    }

    private fun rendered(c: PlanCandidate) =
        RenderedTransition(c.plan, AudioBuffer.silence(44_100, 2, 16), report = RenderReport(0L, 0f, 0f, 0f))

    /** Both rendered and both heard: ready for the vote. X is the planner's best candidate unless [xIsFirst] is false. */
    private fun heardTest(xIsFirst: Boolean = true): AbTest {
        val (first, second) = candidates
        val x = if (xIsFirst) first else second
        val y = if (xIsFirst) second else first
        return AbTest(first, second, xIsFirst, renderX = rendered(x), renderY = rendered(y), rendering = false, heardX = true, heardY = true)
    }

    /** Records every successful rating; each [rate] waits for [gate], then answers with the next of [results] (default ok). */
    private class FakeRatings(vararg results: DjResult) : CustomizationApi by NoOpCustomization {
        private val answers = ArrayDeque(results.toList())
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val recorded = ArrayList<Pair<String, Rating>>()

        override suspend fun rate(a: Song, b: Song, strategyId: String, rating: Rating): DjResult {
            calls++
            gate.await()
            val result = answers.removeFirstOrNull() ?: DjResult.ok("Rated")
            if (result.ok) recorded += strategyId to rating
            return result
        }
    }

    private fun vote(state: MutableStateFlow<AbTest?>, scope: kotlinx.coroutines.CoroutineScope, fake: FakeRatings, winner: Char?) =
        castAbVote(state, { it }, { _, t -> t }, scope, fake, a, b, winner)

    @Test
    fun aDoubleTapWhileTheVoteIsBeingRecordedRecordsItOnce() = runBlocking {
        val test = heardTest()
        val state = MutableStateFlow<AbTest?>(test)
        val fake = FakeRatings()

        val first = vote(state, this, fake, 'X')
        assertNotNull(first, "the first tap votes")
        repeat(1_000) { if (fake.calls == 0) yield() }
        assertEquals(1, fake.calls, "the first vote must be waiting inside rate()")
        val canVoteWhileRecording = state.value!!.canVote

        // The second tap of a double-tap, and a tap on the other button.
        val second = vote(state, this, fake, 'X')
        val third = vote(state, this, fake, 'Y')
        fake.gate.complete(Unit)
        listOfNotNull(first, second, third).joinAll()

        assertEquals<List<Pair<String, Rating>>>(listOf(test.x.strategy.id to Rating.Up, test.y.strategy.id to Rating.Down), fake.recorded)
        assertFalse(canVoteWhileRecording, "the buttons must be disabled while the vote is recorded")
        val after = state.value!!
        assertNotNull(after.reveal)
        assertFalse(after.canVote)
        assertNull(vote(state, this, fake, 'X'), "a revealed test takes no further vote")
    }

    @Test
    fun whenNothingCouldBeRecordedTheUserCanVoteAgain() = runBlocking {
        val test = heardTest()
        val state = MutableStateFlow<AbTest?>(test)
        val fake = FakeRatings(DjResult.fail("disk full"))
        fake.gate.complete(Unit)

        vote(state, this, fake, 'X')!!.join()
        val failed = state.value!!
        assertEquals<List<Pair<String, Rating>>>(emptyList(), fake.recorded, "the loser must not be rated down when the winner's rating failed")
        assertNull(failed.reveal)
        assertTrue(failed.canVote, "nothing was recorded, so the vote can be cast again")
        assertTrue(failed.error.orEmpty().contains("disk full"), "error: ${failed.error}")

        vote(state, this, fake, 'Y')!!.join()
        assertEquals<List<Pair<String, Rating>>>(listOf(test.y.strategy.id to Rating.Up, test.x.strategy.id to Rating.Down), fake.recorded)
        val done = state.value!!
        assertNotNull(done.reveal)
        assertNull(done.error)
        assertFalse(done.canVote)
    }

    @Test
    fun whenOnlyTheSecondRatingFailedTheVoteIsNotOfferedAgain() = runBlocking {
        val test = heardTest()
        val state = MutableStateFlow<AbTest?>(test)
        val fake = FakeRatings(DjResult.ok("Rated"), DjResult.fail("disk full"))
        fake.gate.complete(Unit)

        vote(state, this, fake, 'X')!!.join()
        // The winner's up is stored: a second vote would count it twice.
        assertEquals<List<Pair<String, Rating>>>(listOf(test.x.strategy.id to Rating.Up), fake.recorded)
        val after = state.value!!
        val reveal = assertNotNull(after.reveal)
        assertTrue("${test.x.strategy.displayName} up" in reveal && "disk full" in reveal, "reveal: $reveal")
        assertFalse(after.canVote)
    }

    @Test
    fun noPreferenceRecordsNothingAndRevealsTheNames() = runBlocking {
        val test = heardTest()
        val state = MutableStateFlow<AbTest?>(test)
        val fake = FakeRatings()
        vote(state, this, fake, null)!!.join()
        assertEquals(0, fake.calls)
        val reveal = assertNotNull(state.value!!.reveal)
        assertTrue(test.x.strategy.displayName in reveal && test.y.strategy.displayName in reveal, "reveal: $reveal")
    }

    @Test
    fun aVoteNeedsBothHeardAndNeverLandsOnANewerTest() = runBlocking {
        val fake = FakeRatings()
        val unheard = MutableStateFlow<AbTest?>(heardTest().copy(heardY = false))
        assertNull(vote(unheard, this, fake, 'X'))

        val state = MutableStateFlow<AbTest?>(heardTest())
        val job = vote(state, this, fake, 'X')!!
        repeat(1_000) { if (fake.calls == 0) yield() }
        // The user closes the test and starts another one before the first vote is stored.
        val newer = heardTest(xIsFirst = false)
        state.value = newer
        fake.gate.complete(Unit)
        job.join()
        assertTrue(state.value == newer, "the old vote must not reveal (and so close) the new test; reveal: ${state.value?.reveal}")
    }
}
