package dev.muisc.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One audio file from MediaStore. [id] is the MediaStore `_ID`, so it is stable across rescans and can be turned
 * back into a content URI with `ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)`
 * (already done in [uri]). Timestamps ([dateAdded], [dateModified]) are epoch SECONDS as MediaStore reports them.
 */
@Entity(
    tableName = "songs",
    indices = [
        Index("albumId"), Index("artistId"), Index("title"), Index("dateAdded"), Index("path"), Index("genre"),
    ],
)
data class Song(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val albumArtist: String?,
    /** 1-based track number within the disc (0 when unknown). */
    val track: Int,
    /** 1-based disc number (1 when unknown). */
    val disc: Int,
    val year: Int,
    val durationMs: Long,
    /** `content://` URI of the file, the identity the decoder opens. */
    val uri: String,
    /** Absolute file path (from `DATA`), or `relativePath + displayName` when DATA is unavailable. */
    val path: String,
    val mimeType: String,
    val size: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val genre: String?,
    /** True once a [TrackAnalysisEntity] exists for this file (maintained by the analysis cache). */
    val hasAnalysis: Boolean = false,
) {
    /** Parent folder of [path] ("" when the path has no separator). */
    val folder: String get() = path.substringBeforeLast('/', "")
}

/** Aggregate of the songs sharing a MediaStore album id; rebuilt by the scanner after every scan. */
@Entity(tableName = "albums", indices = [Index("artistId"), Index("title")])
data class Album(
    @PrimaryKey val id: Long,
    val title: String,
    val artistId: Long,
    /** Album artist when the files carry one, else the most common track artist. */
    val artist: String,
    val year: Int,
    val songCount: Int,
    /** `content://media/external/audio/albumart/<id>` (null when the album id is unknown). */
    val albumArtUri: String?,
)

/** Aggregate of the songs sharing a MediaStore artist id; rebuilt by the scanner. */
@Entity(tableName = "artists", indices = [Index("name")])
data class Artist(
    @PrimaryKey val id: Long,
    val name: String,
    val albumCount: Int,
    val songCount: Int,
)

/** A genre name seen on at least one song; [id] is a stable hash of the normalised name (see `MediaStoreScanner`). */
@Entity(tableName = "genres", indices = [Index(value = ["name"], unique = true)])
data class Genre(
    @PrimaryKey val id: Long,
    val name: String,
)

/** User playlist. Membership lives in [PlaylistEntry]. */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Epoch milliseconds. */
    val createdAt: Long,
)

/** One song inside a playlist at [position] (0-based, gaps allowed; readers order by it). */
@Entity(
    tableName = "playlist_entries",
    indices = [Index("playlistId"), Index("songId"), Index(value = ["playlistId", "position"])],
    foreignKeys = [
        ForeignKey(entity = Playlist::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Song::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaylistEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)

/** A completed (or substantially played) song, for "Recently played" / "Most played". [context] is the `PlaybackContext` name. */
@Entity(
    tableName = "play_history",
    indices = [Index("songId"), Index("playedAt")],
    foreignKeys = [
        ForeignKey(entity = Song::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlayHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    /** Epoch milliseconds. */
    val playedAt: Long,
    val context: String,
)

/**
 * Cached `TrackAnalysis` as JSON, keyed the way `AnalysisCache.get(fingerprint, sampleRate, version)` looks it up.
 * The composite key means a re-analysis at another engine rate or a newer analyser version does not clobber the old one.
 */
@Entity(
    tableName = "track_analysis",
    primaryKeys = ["fingerprint", "sampleRate", "version"],
    indices = [Index("sourceId"), Index("updatedAt")],
)
data class TrackAnalysisEntity(
    val fingerprint: String,
    val sampleRate: Int,
    val version: Int,
    /** Path / URI the analysis was made from (`TrackAnalysis.sourceId`). */
    val sourceId: String,
    val json: String,
    /** Epoch milliseconds. */
    val updatedAt: Long,
)

/** What the coordinator decided for one A→B pair and how it went; consulted by the planner's variety penalty and the Lab. */
@Entity(tableName = "transition_log", indices = [Index(value = ["aSongId", "bSongId"]), Index("createdAt")])
data class TransitionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val aSongId: Long,
    val bSongId: Long,
    val strategyId: String,
    /** `Params` serialised with kotlinx.serialization. */
    val paramsJson: String,
    val score: Double,
    /** Free-form outcome: "planned", "rendered", "installed", "live:<kind>", "deadline-miss", "thumbs-up", "thumbs-down", ... */
    val outcome: String,
    /** Epoch milliseconds. */
    val createdAt: Long,
)

/** "Pin for this pair" from the Transition Lab: always use [strategyId] with [paramsJson] when A is followed by B. */
@Entity(
    tableName = "pair_overrides",
    primaryKeys = ["aSongId", "bSongId"],
    indices = [Index("bSongId")],
    foreignKeys = [
        ForeignKey(entity = Song::class, parentColumns = ["id"], childColumns = ["aSongId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Song::class, parentColumns = ["id"], childColumns = ["bSongId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PairOverride(
    val aSongId: Long,
    val bSongId: Long,
    val strategyId: String,
    val paramsJson: String,
)

/** A named set of parameter values for one strategy ("Save as default" / presets in the Lab). */
@Entity(tableName = "strategy_presets", indices = [Index("strategyId")])
data class StrategyPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val strategyId: String,
    val name: String,
    val paramsJson: String,
)
