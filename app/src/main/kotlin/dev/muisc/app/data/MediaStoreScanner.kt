package dev.muisc.app.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.room.withTransaction
import dev.muisc.app.data.db.Album
import dev.muisc.app.data.db.Artist
import dev.muisc.app.data.db.Genre
import dev.muisc.app.data.db.MuiscDatabase
import dev.muisc.app.data.db.Song
import dev.muisc.app.data.prefs.ScanState
import dev.muisc.app.data.prefs.SettingsRepository
import dev.muisc.app.data.prefs.UiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Outcome of one [MediaStoreScanner.scan]. */
data class ScanResult(
    /** Rows read from MediaStore (after the min-duration / blacklist filters). */
    val scanned: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    /** True when the scan was skipped because MediaStore reported no change (API 30+ generation) or permission is missing. */
    val skipped: Boolean = false,
    /** Non-null when the scan could not run (no permission, provider error). */
    val error: String? = null,
) {
    val changed: Boolean get() = added > 0 || updated > 0 || removed > 0
}

/**
 * Reads `MediaStore.Audio.Media` into the Room library. `scan(full = false)` is incremental: it re-reads only rows
 * whose `DATE_MODIFIED` is newer than the last scan (and, on API 30+, returns immediately when
 * `MediaStore.getGeneration` is unchanged), then reconciles deletions with a cheap id-only query. After every
 * scan the album/artist/genre aggregates are rebuilt from the `songs` table.
 *
 * Permissions are the UI's job (READ_MEDIA_AUDIO on 33+, READ_EXTERNAL_STORAGE below); without them the
 * provider throws `SecurityException` and the scan returns an empty, skipped result. Only one scan runs at a time.
 */
class MediaStoreScanner(
    private val context: Context,
    private val db: MuiscDatabase,
    private val settings: SettingsRepository,
) {
    private val resolver = context.applicationContext.contentResolver
    private val scanMutex = Mutex()

    /** Emits whenever MediaStore reports a change under the audio collection. Debounce before rescanning. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    /**
     * Runs a scan on [Dispatchers.IO]. [full] re-reads every row and ignores the saved scan state (use after the
     * user changes the min-duration or blacklist settings, or from the "Rescan library" action).
     */
    suspend fun scan(full: Boolean): ScanResult = scanMutex.withLock {
        withContext(Dispatchers.IO) { scanLocked(full) }
    }

    private suspend fun scanLocked(full: Boolean): ScanResult {
        val prefs = settings.currentUiPrefs()
        val previous = if (full) ScanState() else settings.currentScanState()

        val generation = currentGeneration()
        if (!full && previous.hasScanned && generation >= 0 && generation == previous.lastGeneration) {
            return ScanResult(skipped = true)
        }

        val minDurationMs = prefs.minDurationSec.coerceAtLeast(0) * 1000L
        val baseSelection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val baseArgs = arrayOf(minDurationMs.toString())

        // 1. Rows to (re)read: everything on a full scan, else only rows modified since the last scan.
        val selection: String
        val args: Array<String>
        if (!full && previous.hasScanned) {
            selection = "$baseSelection AND ${MediaStore.Audio.Media.DATE_MODIFIED} > ?"
            args = baseArgs + previous.lastDateModifiedSec.toString()
        } else {
            selection = baseSelection
            args = baseArgs
        }

        val read: Pair<List<Song>, Set<Long>> = try {
            val genreBySong = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) emptyMap() else legacyGenres()
            Pair(querySongs(selection, args, prefs, genreBySong), queryIds(baseSelection, baseArgs))
        } catch (e: SecurityException) {
            return ScanResult(skipped = true, error = "Missing storage permission")
        } catch (e: Exception) {
            return ScanResult(skipped = true, error = e.message ?: e.javaClass.simpleName)
        }
        val changedRows: List<Song> = read.first
        val presentIds: Set<Long> = read.second

        // 2. Reconcile: songs that vanished from MediaStore (or fell under the min-duration filter) are removed; on a
        //    full scan every existing song that was not re-read is gone too (covers files newly blacklisted).
        val songDao = db.songDao()
        val existingIds = songDao.allIds().toHashSet()
        val toRemove = HashSet<Long>()
        existingIds.filterTo(toRemove) { it !in presentIds }
        if (full) {
            val keep = changedRows.mapTo(HashSet()) { it.id }
            existingIds.filterTo(toRemove) { it !in keep }
        }
        val added = changedRows.count { it.id !in existingIds }
        val updated = changedRows.size - added
        val blacklist = prefs.blacklist.map { it.trimEnd('/') }.filter { it.isNotEmpty() }

        var removed = 0
        db.withTransaction {
            if (toRemove.isNotEmpty()) toRemove.toList().chunked(500).forEach { songDao.deleteByIds(it) }
            changedRows.chunked(500).forEach { songDao.upsertAll(it) }
            // Incremental scans do not re-read unchanged rows, so apply the blacklist to what is stored as well.
            val stored = songDao.allOnce()
            val (banned, kept) = stored.partition { song -> blacklist.any { isUnder(song.path, it) } }
            if (banned.isNotEmpty()) banned.map { it.id }.chunked(500).forEach { songDao.deleteByIds(it) }
            removed = toRemove.size + banned.size
            // Rebuilding touches three tables (and wakes every library screen), so skip it when nothing moved.
            if (full || removed > 0 || changedRows.isNotEmpty()) rebuildAggregates(kept)
        }

        val maxModified = changedRows.maxOfOrNull { it.dateModified } ?: previous.lastDateModifiedSec
        settings.updateScanState(
            ScanState(
                lastGeneration = generation,
                lastDateModifiedSec = maxOf(maxModified, previous.lastDateModifiedSec),
                lastScanAtMs = System.currentTimeMillis(),
            ),
        )
        return ScanResult(scanned = changedRows.size, added = added, updated = updated, removed = removed)
    }

    /** `MediaStore.getGeneration` on API 30+, -1 otherwise or on failure. */
    private fun currentGeneration(): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1L
        return try {
            MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } catch (e: Exception) {
            -1L
        }
    }

    private fun queryIds(selection: String, args: Array<String>): Set<Long> {
        val ids = HashSet<Long>()
        resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID), selection, args, null)?.use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        return ids
    }

    @Suppress("DEPRECATION")
    private fun querySongs(selection: String, args: Array<String>, prefs: UiPrefs, legacyGenreBySong: Map<Long, String>): List<Song> {
        val projection = ArrayList<String>().apply {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ARTIST_ID)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.GENRE)
            }
        }.toTypedArray()

        val blacklist = prefs.blacklist.map { it.trimEnd('/') }.filter { it.isNotEmpty() }
        val out = ArrayList<Song>()
        resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iArtistId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val iDisplayName = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val iMime = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val iSize = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val iDateAdded = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val iDateModified = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val iRelativePath = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val iAlbumArtist = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val iGenre = c.getColumnIndex(MediaStore.Audio.Media.GENRE)

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val data = c.optString(iData)
                val displayName = c.optString(iDisplayName)
                val path = if (data.isNotEmpty()) data else (c.optString(iRelativePath) + displayName)
                if (blacklist.any { isUnder(path, it) }) continue

                val rawTrack = c.getInt(iTrack)
                val genre = if (iGenre >= 0) c.optString(iGenre).ifEmpty { null } else legacyGenreBySong[id]
                out += Song(
                    id = id,
                    title = c.optString(iTitle).ifEmpty { displayName.substringBeforeLast('.').ifEmpty { "Unknown title" } },
                    artist = c.optString(iArtist).ifEmpty { UNKNOWN_ARTIST },
                    artistId = c.getLong(iArtistId),
                    album = c.optString(iAlbum).ifEmpty { UNKNOWN_ALBUM },
                    albumId = c.getLong(iAlbumId),
                    albumArtist = if (iAlbumArtist >= 0) c.optString(iAlbumArtist).ifEmpty { null } else null,
                    track = if (rawTrack > 0) rawTrack % 1000 else 0,
                    disc = if (rawTrack >= 1000) rawTrack / 1000 else 1,
                    year = c.getInt(iYear).coerceAtLeast(0),
                    durationMs = c.getLong(iDuration),
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    path = path,
                    mimeType = c.optString(iMime),
                    size = if (iSize >= 0) c.getLong(iSize) else 0L,
                    dateAdded = if (iDateAdded >= 0) c.getLong(iDateAdded) else 0L,
                    dateModified = if (iDateModified >= 0) c.getLong(iDateModified) else 0L,
                    genre = genre?.trim()?.ifEmpty { null },
                    hasAnalysis = false,
                )
            }
        }
        return out
    }

    /** API < 30: genre membership lives in a separate table; returns song id → genre name. */
    private fun legacyGenres(): Map<Long, String> {
        val result = HashMap<Long, String>()
        val genres = ArrayList<Pair<Long, String>>()
        resolver.query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.optString(1).trim()
                if (name.isNotEmpty()) genres += c.getLong(0) to name
            }
        }
        for ((genreId, name) in genres) {
            val membersUri: Uri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
            try {
                resolver.query(membersUri, arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null)?.use { c ->
                    while (c.moveToNext()) result[c.getLong(0)] = name
                }
            } catch (e: Exception) {
                // A single broken genre must not abort the scan.
            }
        }
        return result
    }

    /** Recomputes `albums`, `artists` and `genres` from [all] (the stored songs). Must run inside a transaction. */
    private suspend fun rebuildAggregates(all: List<Song>) {
        val albums = all.groupBy { it.albumId }.map { (albumId, songs) ->
            val first = songs.first()
            val albumArtist = songs.firstNotNullOfOrNull { it.albumArtist }
                ?: songs.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key
                ?: first.artist
            Album(
                id = albumId,
                title = first.album,
                artistId = first.artistId,
                artist = albumArtist,
                year = songs.maxOf { it.year },
                songCount = songs.size,
                albumArtUri = ArtworkLoader.albumArtUri(albumId).toString(),
            )
        }
        val artists = all.groupBy { it.artistId }.map { (artistId, songs) ->
            Artist(
                id = artistId,
                name = songs.first().artist,
                albumCount = songs.distinctBy { it.albumId }.size,
                songCount = songs.size,
            )
        }
        val genres = all.mapNotNull { it.genre }.distinct().map { Genre(id = genreId(it), name = it) }

        db.albumDao().deleteAll()
        db.albumDao().insertAll(albums)
        db.artistDao().deleteAll()
        db.artistDao().insertAll(artists)
        db.genreDao().deleteAll()
        db.genreDao().insertAll(genres)
    }

    private fun isUnder(path: String, folder: String): Boolean =
        path == folder || path.startsWith("$folder/")

    private fun Cursor.optString(index: Int): String = if (index < 0 || isNull(index)) "" else (getString(index) ?: "")

    companion object {
        const val UNKNOWN_ARTIST = "<unknown>"
        const val UNKNOWN_ALBUM = "<unknown>"

        /** Stable, name-derived genre id (MediaStore genre ids differ between API levels and are not stored on songs). */
        fun genreId(name: String): Long {
            var h = 1125899906842597L
            for (ch in name.trim().lowercase()) h = 31 * h + ch.code
            return h and Long.MAX_VALUE
        }
    }
}
