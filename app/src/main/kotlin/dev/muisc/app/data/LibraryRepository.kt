package dev.muisc.app.data

import dev.muisc.app.data.db.Album
import dev.muisc.app.data.db.Artist
import dev.muisc.app.data.db.Genre
import dev.muisc.app.data.db.MuiscDatabase
import dev.muisc.app.data.db.PlayHistory
import dev.muisc.app.data.db.Playlist
import dev.muisc.app.data.db.PlaylistEntry
import dev.muisc.app.data.db.Song
import dev.muisc.transitions.PlaybackContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Sort orders for the Songs list. */
enum class SongSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DATE_ADDED("Date added"),
    DURATION("Duration"),
}

/** Result of [LibraryRepository.search]. */
data class SearchResults(
    val query: String,
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

/**
 * Read model of the music library plus playlist and history writes. Every list is a [Flow] that re-emits on
 * change (Room invalidation), so screens simply `collectAsStateWithLifecycle`. Writes are `suspend` and run on
 * Room's own executor; call them from any dispatcher.
 */
class LibraryRepository(private val db: MuiscDatabase) {
    private val songDao get() = db.songDao()
    private val albumDao get() = db.albumDao()
    private val artistDao get() = db.artistDao()
    private val genreDao get() = db.genreDao()
    private val playlistDao get() = db.playlistDao()
    private val historyDao get() = db.historyDao()

    // ---- browsing ----

    fun songs(sort: SongSort = SongSort.TITLE): Flow<List<Song>> = when (sort) {
        SongSort.TITLE -> songDao.byTitle()
        SongSort.ARTIST -> songDao.byArtist()
        SongSort.ALBUM -> songDao.byAlbum()
        SongSort.DATE_ADDED -> songDao.byDateAdded()
        SongSort.DURATION -> songDao.byDuration()
    }

    fun albums(): Flow<List<Album>> = albumDao.all()
    fun artists(): Flow<List<Artist>> = artistDao.all()
    fun genres(): Flow<List<Genre>> = genreDao.all()
    fun playlists(): Flow<List<Playlist>> = playlistDao.all()

    fun album(albumId: Long): Flow<Album?> = albumDao.observe(albumId)
    fun artist(artistId: Long): Flow<Artist?> = artistDao.observe(artistId)
    fun genre(genreId: Long): Flow<Genre?> = genreDao.observe(genreId)
    fun playlist(playlistId: Long): Flow<Playlist?> = playlistDao.observe(playlistId)
    fun song(songId: Long): Flow<Song?> = songDao.observe(songId)

    fun songsOfAlbum(albumId: Long): Flow<List<Song>> = songDao.ofAlbum(albumId)
    fun songsOfArtist(artistId: Long): Flow<List<Song>> = songDao.ofArtist(artistId)
    fun songsOfGenre(genreId: Long): Flow<List<Song>> = songDao.ofGenre(genreId)
    fun songsOfPlaylist(playlistId: Long): Flow<List<Song>> = playlistDao.songsOf(playlistId)
    fun albumsOfArtist(artistId: Long): Flow<List<Album>> = albumDao.ofArtist(artistId)
    fun playlistSongCount(playlistId: Long): Flow<Int> = playlistDao.songCount(playlistId)

    /** Folder tree built from every song's path (collapsed above the first branch). */
    fun folders(): Flow<FolderNode<Song>> = songDao.byTitle().map { list -> FolderTree.build(list, { it.path }) }

    /** Songs directly inside [folderPath] (no sub-folders), title order. */
    fun songsOfFolder(folderPath: String): Flow<List<Song>> =
        songDao.byTitle().map { list -> list.filter { it.folder == folderPath } }

    /** Case-insensitive substring search across songs, albums, artists and playlists; blank query → empty result. */
    fun search(query: String, limitPerKind: Int = 50): Flow<SearchResults> {
        val q = query.trim()
        if (q.isEmpty()) return flowOf(SearchResults(q))
        val pattern = "%$q%"
        return combine(
            songDao.search(pattern, limitPerKind),
            albumDao.search(pattern, limitPerKind),
            artistDao.search(pattern, limitPerKind),
            playlistDao.search(pattern, limitPerKind),
        ) { s, al, ar, pl -> SearchResults(q, s, al, ar, pl) }
    }

    // ---- smart lists ----

    fun recentlyAdded(limit: Int = 100): Flow<List<Song>> = songDao.recentlyAdded(limit)
    fun mostPlayed(limit: Int = 100): Flow<List<Song>> = songDao.mostPlayed(limit)
    /** Recently played songs, most recent first (one entry per song). */
    fun history(limit: Int = 100): Flow<List<Song>> = songDao.recentlyPlayed(limit)
    fun songCount(): Flow<Int> = songDao.count()
    fun analysedCount(): Flow<Int> = songDao.analysedCount()

    // ---- one-shot lookups ----

    suspend fun songById(id: Long): Song? = songDao.byId(id)

    /** Songs for [ids] in the same order as [ids] (missing ids skipped). Chunked to respect SQLite's variable limit. */
    suspend fun songsByIds(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val found = HashMap<Long, Song>(ids.size)
        ids.distinct().chunked(500).forEach { chunk -> songDao.byIds(chunk).forEach { found[it.id] = it } }
        return ids.mapNotNull { found[it] }
    }

    suspend fun albumById(id: Long): Album? = albumDao.byId(id)
    suspend fun artistById(id: Long): Artist? = artistDao.byId(id)

    // ---- playlists ----

    /** Creates a playlist and returns its id. */
    suspend fun createPlaylist(name: String): Long =
        playlistDao.insert(Playlist(name = name.trim().ifEmpty { "New playlist" }, createdAt = System.currentTimeMillis()))

    /** Appends [songIds] (duplicates allowed, like most players) after the current last position. */
    suspend fun addToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        val start = (playlistDao.maxPosition(playlistId) ?: -1) + 1
        playlistDao.insertEntries(
            songIds.mapIndexed { i, songId -> PlaylistEntry(playlistId = playlistId, songId = songId, position = start + i) },
        )
    }

    suspend fun addToPlaylist(playlistId: Long, songId: Long) = addToPlaylist(playlistId, listOf(songId))

    /** Removes every entry of [songId] from the playlist. */
    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) = playlistDao.deleteEntry(playlistId, songId)

    suspend fun renamePlaylist(playlistId: Long, name: String) = playlistDao.rename(playlistId, name.trim())

    suspend fun deletePlaylist(playlistId: Long) = playlistDao.delete(playlistId)

    /** Moves the entry at [from] to [to] (indices in position order) and renumbers positions 0..n-1. */
    suspend fun moveInPlaylist(playlistId: Long, from: Int, to: Int) {
        val entries = playlistDao.entriesOf(playlistId).toMutableList()
        if (from !in entries.indices || to !in entries.indices || from == to) return
        val moved = entries.removeAt(from)
        entries.add(to, moved)
        entries.forEachIndexed { index, e -> if (e.position != index) playlistDao.setPosition(e.id, index) }
    }

    // ---- history ----

    /** Records a play of [songId] in [context]; the playback layer calls this when a track is substantially played. */
    suspend fun recordPlay(songId: Long, context: PlaybackContext) {
        historyDao.insert(PlayHistory(songId = songId, playedAt = System.currentTimeMillis(), context = context.name))
    }

    suspend fun clearHistory() = historyDao.clear()
}
