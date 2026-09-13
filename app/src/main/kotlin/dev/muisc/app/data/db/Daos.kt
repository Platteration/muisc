package dev.muisc.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Songs. List queries return [Flow]s that re-emit when the `songs` table (or a joined table) changes; one-shot
 * lookups and writes are `suspend`. Sorting variants are separate queries so Room validates every statement at
 * compile time (no `@RawQuery`).
 */
@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun byTitle(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY artist COLLATE NOCASE ASC, album COLLATE NOCASE ASC, disc ASC, track ASC")
    fun byArtist(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY album COLLATE NOCASE ASC, disc ASC, track ASC")
    fun byAlbum(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY dateAdded DESC, title COLLATE NOCASE ASC")
    fun byDateAdded(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY durationMs DESC, title COLLATE NOCASE ASC")
    fun byDuration(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun byId(id: Long): Song?

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observe(id: Long): Flow<Song?>

    /** Unordered; callers re-order by their id list. Keep [ids] under ~900 entries (SQLite variable limit). */
    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Song>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY disc ASC, track ASC, title COLLATE NOCASE ASC")
    fun ofAlbum(albumId: Long): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE artistId = :artistId ORDER BY album COLLATE NOCASE ASC, disc ASC, track ASC")
    fun ofArtist(artistId: Long): Flow<List<Song>>

    @Query(
        "SELECT songs.* FROM songs INNER JOIN genres ON songs.genre = genres.name " +
            "WHERE genres.id = :genreId ORDER BY songs.artist COLLATE NOCASE ASC, songs.album COLLATE NOCASE ASC, songs.disc ASC, songs.track ASC",
    )
    fun ofGenre(genreId: Long): Flow<List<Song>>

    /** [pattern] is a ready-made LIKE pattern such as `%term%`. */
    @Query(
        "SELECT * FROM songs WHERE title LIKE :pattern OR artist LIKE :pattern OR album LIKE :pattern " +
            "ORDER BY title COLLATE NOCASE ASC LIMIT :limit",
    )
    fun search(pattern: String, limit: Int): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY dateAdded DESC, id DESC LIMIT :limit")
    fun recentlyAdded(limit: Int): Flow<List<Song>>

    @Query(
        "SELECT songs.* FROM songs INNER JOIN " +
            "(SELECT songId, COUNT(*) AS plays FROM play_history GROUP BY songId) AS h ON songs.id = h.songId " +
            "ORDER BY h.plays DESC, songs.title COLLATE NOCASE ASC LIMIT :limit",
    )
    fun mostPlayed(limit: Int): Flow<List<Song>>

    @Query(
        "SELECT songs.* FROM songs INNER JOIN " +
            "(SELECT songId, MAX(playedAt) AS lastPlayed FROM play_history GROUP BY songId) AS h ON songs.id = h.songId " +
            "ORDER BY h.lastPlayed DESC LIMIT :limit",
    )
    fun recentlyPlayed(limit: Int): Flow<List<Song>>

    @Query("SELECT COUNT(*) FROM songs")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs WHERE hasAnalysis = 1")
    fun analysedCount(): Flow<Int>

    @Query("SELECT id FROM songs")
    suspend fun allIds(): List<Long>

    /** Every song, one shot (used by the scanner to rebuild the album/artist/genre aggregates). */
    @Query("SELECT * FROM songs")
    suspend fun allOnce(): List<Song>

    @Query("SELECT * FROM songs WHERE hasAnalysis = 0 ORDER BY dateAdded DESC LIMIT :limit")
    suspend fun unanalysed(limit: Int): List<Song>

    @Upsert
    suspend fun upsertAll(songs: List<Song>)

    /** Keep [ids] under ~900 entries per call. */
    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()

    @Query("UPDATE songs SET hasAnalysis = :value WHERE id = :id")
    suspend fun setHasAnalysis(id: Long, value: Boolean)

    /**
     * Blocking variant used by the analysis cache (which implements a non-suspending engine interface and is only
     * ever called from engine worker threads, never from the main thread).
     */
    @Query("UPDATE songs SET hasAnalysis = 1 WHERE uri = :sourceId OR path = :sourceId")
    fun markAnalysedBySourceBlocking(sourceId: String): Int
}

/** Albums (aggregates rebuilt by the scanner). */
@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY title COLLATE NOCASE ASC")
    fun all(): Flow<List<Album>>

    @Query("SELECT * FROM albums ORDER BY year DESC, title COLLATE NOCASE ASC")
    fun byYear(): Flow<List<Album>>

    @Query("SELECT * FROM albums WHERE id = :id")
    fun observe(id: Long): Flow<Album?>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun byId(id: Long): Album?

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year ASC, title COLLATE NOCASE ASC")
    fun ofArtist(artistId: Long): Flow<List<Album>>

    @Query("SELECT * FROM albums WHERE title LIKE :pattern OR artist LIKE :pattern ORDER BY title COLLATE NOCASE ASC LIMIT :limit")
    fun search(pattern: String, limit: Int): Flow<List<Album>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(albums: List<Album>)

    @Query("DELETE FROM albums")
    suspend fun deleteAll()
}

/** Artists (aggregates rebuilt by the scanner). */
@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    fun all(): Flow<List<Artist>>

    @Query("SELECT * FROM artists WHERE id = :id")
    fun observe(id: Long): Flow<Artist?>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun byId(id: Long): Artist?

    @Query("SELECT * FROM artists WHERE name LIKE :pattern ORDER BY name COLLATE NOCASE ASC LIMIT :limit")
    fun search(pattern: String, limit: Int): Flow<List<Artist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artists: List<Artist>)

    @Query("DELETE FROM artists")
    suspend fun deleteAll()
}

/** Genres (aggregates rebuilt by the scanner). */
@Dao
interface GenreDao {
    @Query("SELECT * FROM genres ORDER BY name COLLATE NOCASE ASC")
    fun all(): Flow<List<Genre>>

    @Query("SELECT * FROM genres WHERE id = :id")
    fun observe(id: Long): Flow<Genre?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(genres: List<Genre>)

    @Query("DELETE FROM genres")
    suspend fun deleteAll()
}

/** Playlists and their entries. */
@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun all(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<Playlist?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): Playlist?

    @Query("SELECT * FROM playlists WHERE name LIKE :pattern ORDER BY name COLLATE NOCASE ASC LIMIT :limit")
    fun search(pattern: String, limit: Int): Flow<List<Playlist>>

    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "SELECT songs.* FROM songs INNER JOIN playlist_entries ON songs.id = playlist_entries.songId " +
            "WHERE playlist_entries.playlistId = :playlistId ORDER BY playlist_entries.position ASC, playlist_entries.id ASC",
    )
    fun songsOf(playlistId: Long): Flow<List<Song>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position ASC, id ASC")
    suspend fun entriesOf(playlistId: Long): List<PlaylistEntry>

    @Query("SELECT COUNT(*) FROM playlist_entries WHERE playlistId = :playlistId")
    fun songCount(playlistId: Long): Flow<Int>

    @Query("SELECT MAX(position) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntry>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun deleteEntry(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_entries WHERE id = :entryId")
    suspend fun deleteEntryById(entryId: Long)

    @Query("UPDATE playlist_entries SET position = :position WHERE id = :entryId")
    suspend fun setPosition(entryId: Long, position: Int)
}

/** Play history. */
@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: PlayHistory)

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<PlayHistory>>

    @Query("SELECT COUNT(*) FROM play_history WHERE songId = :songId")
    suspend fun playCount(songId: Long): Int

    @Query("DELETE FROM play_history WHERE playedAt < :olderThanMs")
    suspend fun prune(olderThanMs: Long)

    @Query("DELETE FROM play_history")
    suspend fun clear()
}

/**
 * Cached track analyses. Methods are BLOCKING (not `suspend`) because `dev.muisc.analysis.AnalysisCache` is a
 * plain synchronous interface called from engine worker threads; never call these on the main thread.
 */
@Dao
interface AnalysisDao {
    @Query("SELECT * FROM track_analysis WHERE fingerprint = :fingerprint AND sampleRate = :sampleRate AND version = :version LIMIT 1")
    fun getBlocking(fingerprint: String, sampleRate: Int, version: Int): TrackAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(entity: TrackAnalysisEntity)

    @Query("DELETE FROM track_analysis")
    fun clearBlocking()

    @Query("SELECT COUNT(*) FROM track_analysis")
    fun count(): Flow<Int>

    @Query("DELETE FROM track_analysis WHERE version < :version")
    suspend fun deleteOlderThanVersion(version: Int)

    @Query("SELECT fingerprint FROM track_analysis WHERE sourceId = :sourceId AND version = :version LIMIT 1")
    suspend fun fingerprintForSource(sourceId: String, version: Int): String?
}

/** Transition log, per-pair pins and strategy presets. */
@Dao
interface TransitionDao {
    @Insert
    suspend fun insertLog(log: TransitionLog): Long

    @Query("SELECT * FROM transition_log WHERE aSongId = :aSongId AND bSongId = :bSongId ORDER BY createdAt DESC")
    fun logsFor(aSongId: Long, bSongId: Long): Flow<List<TransitionLog>>

    @Query("SELECT * FROM transition_log ORDER BY createdAt DESC LIMIT :limit")
    fun recentLogs(limit: Int): Flow<List<TransitionLog>>

    /** The strategy used for the most recent transition (any pair), for the planner's variety penalty. */
    @Query("SELECT strategyId FROM transition_log ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastStrategyId(): String?

    @Query("DELETE FROM transition_log WHERE createdAt < :olderThanMs")
    suspend fun pruneLogs(olderThanMs: Long)

    @Query("SELECT * FROM pair_overrides WHERE aSongId = :aSongId AND bSongId = :bSongId")
    suspend fun overrideFor(aSongId: Long, bSongId: Long): PairOverride?

    @Query("SELECT * FROM pair_overrides WHERE aSongId = :aSongId AND bSongId = :bSongId")
    fun observeOverride(aSongId: Long, bSongId: Long): Flow<PairOverride?>

    @Upsert
    suspend fun upsertOverride(pin: PairOverride)

    @Query("DELETE FROM pair_overrides WHERE aSongId = :aSongId AND bSongId = :bSongId")
    suspend fun deleteOverride(aSongId: Long, bSongId: Long)

    @Query("SELECT * FROM strategy_presets WHERE strategyId = :strategyId ORDER BY name COLLATE NOCASE ASC")
    fun presets(strategyId: String): Flow<List<StrategyPreset>>

    @Query("SELECT * FROM strategy_presets ORDER BY strategyId ASC, name COLLATE NOCASE ASC")
    fun allPresets(): Flow<List<StrategyPreset>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: StrategyPreset): Long

    @Query("DELETE FROM strategy_presets WHERE id = :id")
    suspend fun deletePreset(id: Long)
}
