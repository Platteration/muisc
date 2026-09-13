package dev.muisc.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app database: library (songs/albums/artists/genres/playlists/history), the analysis cache and the
 * transition memory (log, per-pair pins, presets). Schema is exported to `app/schemas` (see `room.schemaLocation`).
 *
 * Version 1. Until a migration is written, a schema change wipes the database (the library is re-scanned from
 * MediaStore and analyses are recomputed lazily), which is acceptable for a pre-1.0 app.
 */
@Database(
    entities = [
        Song::class, Album::class, Artist::class, Genre::class,
        Playlist::class, PlaylistEntry::class, PlayHistory::class,
        TrackAnalysisEntity::class, TransitionLog::class, PairOverride::class, StrategyPreset::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MuiscDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun genreDao(): GenreDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun transitionDao(): TransitionDao

    companion object {
        const val NAME = "muisc.db"

        /** Builds the production database. Call once from the application graph. */
        fun build(context: Context): MuiscDatabase =
            Room.databaseBuilder(context.applicationContext, MuiscDatabase::class.java, NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
