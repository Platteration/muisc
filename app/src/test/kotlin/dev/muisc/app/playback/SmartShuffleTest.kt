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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Smart shuffle on the phone: the arrangement helper and the queue swap it relies on. */
class SmartShuffleTest {

    private fun song(id: Long, analysed: Boolean = true, artist: String = "Artist $id") = Song(
        id = id, title = "Song $id", artist = artist, artistId = id, album = "Album $id", albumId = 100L + id, albumArtist = null,
        track = 1, disc = 1, year = 2024, durationMs = 180_000L, uri = "content://media/external/audio/media/$id",
        path = "/music/$id.mp3", mimeType = "audio/mpeg", size = 1L, dateAdded = 0L, dateModified = 0L, genre = null,
        hasAnalysis = analysed,
    )

    private fun analysis(id: Long, bpm: Double, tonic: Int): TrackAnalysis {
        val sr = 44100
        val total = 180L * sr
        val grid = BeatGrid.rigid(bpm, sr, 0L, total - 1, confidence = 0.9f, phraseStartBeat = 0)
        val bars = (grid.beatCount / 4).coerceAtLeast(1)
        fun arr(v: Float) = FloatArray(bars) { v }
        return TrackAnalysis(
            sourceId = "content://media/external/audio/media/$id", fingerprint = "fp-$id", sampleRate = sr, totalFrames = total,
            trimStartFrame = 0L, trimEndFrame = total, tempo = TempoEstimate(bpm, 0.9f), grid = grid,
            key = KeyEstimate(MusicalKey(tonic, Mode.MAJOR), 0.9f), loudness = LoudnessInfo(-10f, -1f),
            bars = BarFeatures(arr(0.8f), arr(0.1f), arr(0.2f), arr(0.4f), arr(0.2f), arr(0.6f), arr(0.1f)),
            intro = IntroType.BEAT_INTRO, outro = OutroType.BEAT_OUTRO,
            cues = Cues(mixOutBeat = grid.beatCount - 65, mixInBeat = 64, firstDownbeat = 0, lastDownbeat = (grid.beatCount - 1) / 4 * 4),
        )
    }

    /** Three tempo groups (90 / 124 / 174 BPM) interleaved, so the input order jumps tempo at every step. */
    private val songs = (1L..30L).map { song(it) }
    private val analyses = songs.associate { s ->
        val group = (s.id % 3).toInt()
        s.id to analysis(s.id, doubleArrayOf(90.0, 124.0, 174.0)[group] + (s.id % 5) * 0.4, tonic = (group * 7) % 12)
    }

    private fun tempoJumps(order: List<Song>) = (0 until order.size - 1).count { analyses.getValue(order[it].id).tempo.bpm.let { a -> kotlin.math.abs(a - analyses.getValue(order[it + 1].id).tempo.bpm) > 10.0 } }

    @Test
    fun arrangeIsAPermutationThatGroupsCompatibleSongs() {
        val lookups = HashMap<Long, Int>()
        val anchor = songs.first()
        val upcoming = songs.drop(1)
        val arranged = SmartShuffle().arrange(anchor, upcoming, seed = 7, prefs = TransitionPrefs()) { s ->
            lookups[s.id] = (lookups[s.id] ?: 0) + 1
            analyses[s.id]
        }
        assertEquals(upcoming.map { it.id }.sorted(), arranged.map { it.id }.sorted(), "every upcoming song, once")
        assertTrue(lookups.values.all { it == 1 }, "each analysis read at most once")
        val before = tempoJumps(listOf(anchor) + upcoming)
        val after = tempoJumps(listOf(anchor) + arranged)
        println("tempo jumps: input $before, smart $after")
        assertTrue(after < before / 3, "smart order groups tempos ($after jumps vs $before)")
    }

    @Test
    fun sameSeedSameOrderAndAStaleFlagNeverHidesAnAnalysis() {
        // The queue keeps the Song rows of the moment it was built, so `hasAnalysis` can be stale (the worker analysed
        // the song since): the flag must not decide whether the analysis is read.
        val mixed = songs.mapIndexed { i, s -> if (i % 4 == 0) s.copy(hasAnalysis = false) else s }
        val looked = HashMap<Long, Int>()
        val shuffle = SmartShuffle()
        val a = shuffle.arrange(mixed.first(), mixed.drop(1), 3, TransitionPrefs()) { s -> looked[s.id] = (looked[s.id] ?: 0) + 1; analyses[s.id] }
        val b = shuffle.arrange(mixed.first(), mixed.drop(1), 3, TransitionPrefs()) { s -> analyses[s.id] }
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(mixed.map { it.id }.toSet(), looked.keys, "every song of a short queue is looked up, whatever its flag says")
        assertTrue(looked.values.all { it == 1 }, "each analysis read at most once")
        // A song the cache has no analysis for is placed without one.
        val none = shuffle.arrange(mixed.first(), mixed.drop(1), 3, TransitionPrefs()) { s -> if (s.hasAnalysis) analyses[s.id] else null }
        assertEquals(mixed.drop(1).map { it.id }.sorted(), none.map { it.id }.sorted(), "unanalysed songs are placed too")
        // A lookup that throws is treated as "no analysis": the song is still placed.
        val c = shuffle.arrange(mixed.first(), mixed.drop(1), 3, TransitionPrefs()) { s -> if (s.id == 2L) error("corrupt row") else analyses[s.id] }
        assertEquals(mixed.drop(1).map { it.id }.sorted(), c.map { it.id }.sorted())
    }

    @Test
    fun onlyTheHorizonIsReadForALargeQueue() {
        val many = (1L..2_000L).map { song(it) }
        var reads = 0
        val shuffle = SmartShuffle(horizon = 20, poolSize = 8)
        val arranged = shuffle.arrange(many.first(), many.drop(1), 1, TransitionPrefs()) { s -> reads++; analysis(s.id, 120.0, 0) }
        assertEquals(many.size - 1, arranged.size)
        assertEquals(1 + 8 + 20, reads, "the anchor, the pool and one refill per arranged slot")
    }

    @Test
    fun replaceAfterSwapsOnlyAnUnchangedTail() {
        val queue = QueueManager()
        val list = songs.take(8)
        queue.setQueue(list, 0, PlaybackContext.SHUFFLE)
        // Arranged after the song that follows the current one: the engine may already be mixing into that one.
        val tail = list.drop(2)
        val reversed = tail.reversed()
        assertFalse(queue.replaceAfter(1, anchorId = 99L, expected = tail, songs = reversed), "wrong anchor")
        assertFalse(queue.replaceAfter(1, list[1].id, expected = tail.drop(1), songs = reversed.drop(1)), "tail changed")
        assertFalse(queue.replaceAfter(1, list[1].id, expected = tail.drop(1), songs = tail.drop(1).reversed()), "tail changed (a song was added since)")
        assertFalse(queue.replaceAfter(1, list[1].id, expected = tail, songs = reversed.drop(1) + song(77)), "not a permutation")
        assertFalse(queue.replaceAfter(0, list[0].id, expected = list.drop(1), songs = list.drop(1).reversed()), "the next song would move")
        assertEquals(list.map { it.id }, queue.snapshot().songs.map { it.id }, "refusals leave the queue alone")
        assertTrue(queue.replaceAfter(1, list[1].id, tail, reversed))
        assertEquals((list.take(2) + reversed).map { it.id }, queue.snapshot().songs.map { it.id })
        assertEquals(queue.snapshot().songs.map { it.id }, queue.naturalOrder().map { it.id }, "shuffle off: the natural order follows")
        // Never at or before the current song.
        queue.skipTo(3)
        val now = queue.snapshot().songs
        assertFalse(queue.replaceAfter(1, now[1].id, now.drop(2), now.drop(2).reversed()))
        assertFalse(queue.replaceAfter(3, now[3].id, now.drop(4), now.drop(4).reversed()), "the next song would move")
        assertTrue(queue.replaceAfter(4, now[4].id, now.drop(5), now.drop(5).reversed()))
        assertEquals((now.take(5) + now.drop(5).reversed()).map { it.id }, queue.snapshot().songs.map { it.id })
    }

    @Test
    fun withShuffleOnOnlyThePlayOrderChanges() {
        val queue = QueueManager()
        val list = songs.take(6)
        queue.setQueue(list, 0, PlaybackContext.PLAYLIST)
        queue.setShuffle(true)
        val snap = queue.snapshot()
        val tail = snap.songs.drop(snap.index + 2)
        assertTrue(queue.replaceAfter(snap.index + 1, snap.songs[snap.index + 1].id, tail, tail.reversed()))
        assertEquals(list.map { it.id }, queue.naturalOrder().map { it.id }, "the playlist's own order is kept for shuffle off")
        queue.setShuffle(false)
        assertEquals(list.map { it.id }, queue.snapshot().songs.map { it.id })
    }
}
