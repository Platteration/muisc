package dev.muisc.app.playback

import dev.muisc.app.data.db.Song
import dev.muisc.transitions.PlaybackContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** The engine-id generations that let the controller re-plan one edge ("use this technique for the next transition"). */
class QueueReplanIdsTest {

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", artistId = 1L, album = "Album", albumId = 10L, albumArtist = null,
        track = id.toInt(), disc = 1, year = 2024, durationMs = 180_000L, uri = "content://media/external/audio/media/$id",
        path = "/music/$id.mp3", mimeType = "audio/mpeg", size = 1L, dateAdded = 0L, dateModified = 0L, genre = null,
    )

    private val songs = listOf(song(1), song(2), song(3))

    @Test
    fun withoutGenerationsTheIdsAreThePlainSongIds() {
        assertEquals(QueueManager.queueItems(songs), QueueManager.queueItems(songs, emptyMap()))
        assertEquals(listOf("1", "2", "3"), QueueManager.queueItems(songs, emptyMap()).map { it.id })
    }

    @Test
    fun aGenerationChangesOnlyThatSongsEngineId() {
        val plain = QueueManager.queueItems(songs)
        val bumped = QueueManager.queueItems(songs, mapOf(2L to 1))
        assertEquals(listOf("1", "2~1", "3"), bumped.map { it.id })
        // Same file, album and tags: only the identity the coordinator keys its edges by changes.
        assertEquals(plain.map { it.sourceId }, bumped.map { it.sourceId })
        assertEquals(plain.map { it.albumId }, bumped.map { it.albumId })
        assertEquals(listOf("1", "2~2", "3"), QueueManager.queueItems(songs, mapOf(2L to 2)).map { it.id })
    }

    @Test
    fun trackChangesReportedWithAGenerationFindTheSong() {
        assertEquals("2", QueueManager.baseEngineId("2~3"))
        assertEquals("2", QueueManager.baseEngineId("2"))
        val queue = QueueManager()
        queue.setQueue(songs, 0, PlaybackContext.PLAYLIST)
        assertEquals(1, queue.indexOfEngineId("2~1"))
        assertEquals(1, queue.indexOfEngineId("2"))
        assertEquals(-1, queue.indexOfEngineId("4~1"))
    }
}
