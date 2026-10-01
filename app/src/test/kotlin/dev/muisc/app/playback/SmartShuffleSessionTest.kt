package dev.muisc.app.playback

import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Cues
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.LoudnessInfo
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.Song
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.TransitionPrefs
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * The smart-shuffle state machine ([SmartShuffleSession]) against the real [QueueManager] and the real [SmartShuffle]
 * / sequencer, driven the way [EngineControllerImpl] drives it: `shuffle()` = end + new queue + start, a user edit =
 * end + the queue edit, a `TrackChanged` from the engine = re-find the id in the queue + onTrackChanged. Passes run on
 * a test dispatcher, so each scenario controls exactly when a pass lands relative to the listener.
 */
class SmartShuffleSessionTest {

    private fun song(id: Long, analysed: Boolean = true) = Song(
        id = id, title = "Song $id", artist = "Artist $id", artistId = id, album = "Album $id", albumId = 100L + id, albumArtist = null,
        track = 1, disc = 1, year = 2024, durationMs = 180_000L, uri = "content://media/external/audio/media/$id",
        path = "/music/$id.mp3", mimeType = "audio/mpeg", size = 1L, dateAdded = 0L, dateModified = 0L, genre = null,
        hasAnalysis = analysed,
    )

    private fun analysis(id: Long): TrackAnalysis {
        val group = (id % 3).toInt()
        val bpm = doubleArrayOf(90.0, 124.0, 174.0)[group] + (id % 5) * 0.4
        val sr = 44100
        val total = 180L * sr
        val grid = BeatGrid.rigid(bpm, sr, 0L, total - 1, confidence = 0.9f, phraseStartBeat = 0)
        val bars = (grid.beatCount / 4).coerceAtLeast(1)
        fun arr(v: Float) = FloatArray(bars) { v }
        return TrackAnalysis(
            sourceId = "content://media/external/audio/media/$id", fingerprint = "fp-$id", sampleRate = sr, totalFrames = total,
            trimStartFrame = 0L, trimEndFrame = total, tempo = TempoEstimate(bpm, 0.9f), grid = grid,
            key = KeyEstimate(MusicalKey((group * 7) % 12, Mode.MAJOR), 0.9f), loudness = LoudnessInfo(-10f, -1f),
            bars = BarFeatures(arr(0.8f), arr(0.1f), arr(0.2f), arr(0.4f), arr(0.2f), arr(0.6f), arr(0.1f)),
            intro = IntroType.BEAT_INTRO, outro = OutroType.BEAT_OUTRO,
            cues = Cues(mixOutBeat = grid.beatCount - 65, mixInBeat = 64, firstDownbeat = 0, lastDownbeat = (grid.beatCount - 1) / 4 * 4),
        )
    }

    /** A random-looking but fixed starting order (what `songs.shuffled()` hands the controller). */
    private fun shuffled(n: Int, analysed: Boolean = true): List<Song> = (1L..n.toLong()).map { song(it, analysed) }.shuffled(java.util.Random(5))

    private class Harness(scope: TestScope) {
        val queue = QueueManager()
        /** What the analysis cache holds (by song id); the background worker may add to it at any time. */
        val cache = HashMap<Long, TrackAnalysis>()
        val lookups = Collections.synchronizedList(ArrayList<Long>())
        /** Runs at the first analysis lookup of the next pass: "while the pass is reading analyses". */
        var duringNextPass: (() -> Unit)? = null
        /** Runs inside the queue's lock, right before the pass's swap: "while the pass is being applied". */
        var whileApplying: (() -> Unit)? = null
        val accepted = Collections.synchronizedList(ArrayList<Int>())
        val refused = Collections.synchronizedList(ArrayList<Int>())
        var installs = 0

        val session = SmartShuffleSession(
            smartShuffle = SmartShuffle(),
            snapshot = { queue.snapshot() },
            replaceAfter = { position, anchorId, expected, songs ->
                synchronized(queue) {
                    whileApplying?.let { whileApplying = null; it() }
                    queue.replaceAfter(position, anchorId, expected, songs)
                }.also { ok -> if (ok) accepted += position else refused += position }
            },
            analysisOf = { s ->
                duringNextPass?.let { duringNextPass = null; it() }
                lookups += s.id
                synchronized(cache) { cache[s.id] }
            },
            prefs = { TransitionPrefs() },
            enabled = { true },
            onArranged = { installs++ },
            scope = scope,
            passContext = StandardTestDispatcher(scope.testScheduler),
            seed = { 42L },
        )

        /** EngineControllerImpl.shuffle: a new queue, then the first pass. */
        fun shuffle(songs: List<Song>) {
            session.end()
            queue.setQueue(songs, 0, PlaybackContext.SHUFFLE)
            session.start()
        }

        /** EngineControllerImpl.onTrackChanged: the engine reports the song it handed over to. */
        fun trackChanged(id: Long) {
            val index = queue.indexOfEngineId(id.toString())
            if (index >= 0 && index != queue.index) queue.skipTo(index)
            session.onTrackChanged()
        }

        /** Plays on to queue position [to], one handover at a time. */
        fun playTo(to: Int, settle: () -> Unit) {
            while (queue.index < to) {
                trackChanged(queue.snapshot().songs[queue.index + 1].id)
                settle()
            }
        }

        fun ids() = queue.snapshot().songs.map { it.id }
    }

    private fun TestScope.harness(analysed: Boolean = true, n: Int = 200): Pair<Harness, List<Song>> {
        val h = Harness(this)
        val songs = shuffled(n, analysed)
        if (analysed) songs.forEach { h.cache[it.id] = analysis(it.id) }
        return h to songs
    }

    @Test
    fun aSkipBeforeTheFirstPassLandsPlaysTheSongTheEngineMixedInto() = runTest {
        val (h, songs) = harness()
        h.shuffle(songs)
        // The listener presses Next at once: the engine starts mixing into the song at position 1, but the queue only
        // moves at the handover, after the first pass has landed.
        val incoming = h.queue.snapshot().songs[1]
        advanceUntilIdle()
        assertTrue(h.accepted.isNotEmpty(), "the first pass was applied")
        h.trackChanged(incoming.id)
        assertEquals(1, h.queue.index, "the handover lands on the next slot: no song is jumped over")
        assertEquals(incoming.id, h.queue.current()!!.id)
        assertEquals(songs.size, h.ids().toSet().size)
    }

    @Test
    fun aHandoverWhileTheFirstPassRunsReanchorsInsteadOfTurningSmartShuffleOff() = runTest {
        val (h, songs) = harness()
        h.shuffle(songs)
        val start = h.ids()
        h.duringNextPass = { h.trackChanged(start[1]) }
        advanceUntilIdle()
        assertEquals(1, h.queue.index)
        assertEquals(start.take(3), h.ids().take(3), "the playing song and the one after it keep their places")
        assertTrue(h.session.arrangedThrough > 2, "the refused pass re-anchored and was applied (through ${h.session.arrangedThrough})")
        assertNotEquals(start, h.ids(), "the rest of the queue was arranged")
        // And it keeps extending as the queue plays on.
        val firstThrough = h.session.arrangedThrough
        h.playTo(firstThrough - SmartShuffle.EXTEND_MARGIN) { advanceUntilIdle() }
        assertTrue(h.session.arrangedThrough > firstThrough, "the next stretch was arranged")
    }

    @Test
    fun aUserEditWhileAPassIsBeingAppliedEndsArrangement() = runTest {
        val (h, songs) = harness()
        h.shuffle(songs)
        val queued = (1001L..1003L).map { song(it) }
        var edit: Thread? = null
        // The listener taps "Add to queue" on the main thread just as the pass swaps its order in.
        h.whileApplying = {
            edit = thread {
                h.session.end()
                h.queue.addToQueue(queued)
            }
            edit!!.join(300)
        }
        advanceUntilIdle()
        edit!!.join()
        assertEquals(-1, h.session.arrangedThrough, "the edit ended arrangement")
        val passes = h.accepted.size + h.refused.size
        h.playTo(80) { advanceUntilIdle() }
        assertEquals(passes, h.accepted.size + h.refused.size, "no further pass after the edit")
        assertEquals(queued.map { it.id }, h.ids().takeLast(3), "the songs the user queued stay where they put them")
    }

    @Test
    fun jumpingPastTheArrangedPartReanchorsThere() = runTest {
        val (h, songs) = harness()
        h.shuffle(songs)
        advanceUntilIdle()
        val through = h.session.arrangedThrough
        assertTrue(through in 2..80, "first pass applied (through $through)")
        // skipToQueueItem(120): not an edit, the queue only moves.
        h.queue.skipTo(120)
        val target = h.queue.current()!!
        h.trackChanged(target.id)
        advanceUntilIdle()
        assertTrue(h.refused.isEmpty(), "no pass was refused: ${h.refused}")
        assertTrue(h.session.arrangedThrough > 121, "arranged from the new position on (through ${h.session.arrangedThrough})")
        val after = h.accepted.size
        h.playTo(125) { advanceUntilIdle() }
        assertTrue(h.refused.isEmpty(), "no refused pass on later track changes: ${h.refused}")
        assertEquals(after, h.accepted.size, "nothing to do until the end of the new stretch")
    }

    @Test
    fun extensionPassesUseAnalysesMadeAfterTheShuffleStarted() = runTest {
        // A fresh install: nothing is analysed when the listener taps Shuffle, and the queue keeps those Song rows.
        val (h, songs) = harness(analysed = false)
        h.shuffle(songs)
        advanceUntilIdle()
        val through = h.session.arrangedThrough
        assertTrue(through >= 0)
        // The background worker analyses the whole library meanwhile; the queued Song rows still say hasAnalysis=false.
        synchronized(h.cache) { songs.forEach { h.cache[it.id] = analysis(it.id) } }
        h.lookups.clear()
        h.playTo(through - SmartShuffle.EXTEND_MARGIN) { advanceUntilIdle() }
        assertTrue(h.session.arrangedThrough > through, "the next stretch was arranged")
        assertTrue(h.queue.snapshot().songs.none { it.hasAnalysis }, "the queue still holds the old rows")
        assertTrue(h.lookups.size > SmartShuffle.HORIZON, "the extension pass read the new analyses (${h.lookups.size} lookups)")
    }

    @Test
    fun aRepeatedStartOrTrackChangeNeverRunsTwoPassesAtOnce() = runTest {
        val (h, songs) = harness()
        h.shuffle(songs)
        h.session.onTrackChanged()
        h.session.onTrackChanged()
        advanceUntilIdle()
        assertEquals(1, h.accepted.size + h.refused.size, "one pass for one shuffle")
        assertEquals(1, h.installs)
    }
}
