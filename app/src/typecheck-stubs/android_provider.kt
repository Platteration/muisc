@file:Suppress("UNUSED_PARAMETER", "unused", "ClassName")

package android.provider

import android.content.Context
import android.net.Uri

/**
 * `android.provider.MediaStore`. The real class exposes these column names through a chain of
 * interfaces (`BaseColumns` -> `MediaColumns` -> `AudioColumns`); here they are flattened onto
 * the objects the app reads them from, which is enough to check spelling and usage.
 */
object MediaStore {
    const val VOLUME_EXTERNAL = "external"
    const val VOLUME_EXTERNAL_PRIMARY = "external_primary"
    const val EXTRA_MEDIA_ID = "android.provider.extra.MEDIA_ID"
    const val UNKNOWN_STRING = "<unknown>"

    @JvmStatic fun getGeneration(context: Context, volumeName: String): Long = 0L
    @JvmStatic fun getVolumeName(uri: Uri): String = VOLUME_EXTERNAL_PRIMARY
    @JvmStatic fun getExternalVolumeNames(context: Context): Set<String> = setOf(VOLUME_EXTERNAL_PRIMARY)

    object MediaColumns {
        const val _ID = "_id"
        const val DISPLAY_NAME = "_display_name"
        const val MIME_TYPE = "mime_type"
        const val RELATIVE_PATH = "relative_path"
        const val IS_PENDING = "is_pending"
        const val SIZE = "_size"
        const val DATA = "_data"
        const val DATE_ADDED = "date_added"
        const val DATE_MODIFIED = "date_modified"
        const val TITLE = "title"
        const val DURATION = "duration"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val ALBUM_ARTIST = "album_artist"
        const val YEAR = "year"
        const val GENRE = "genre"
        const val BITRATE = "bitrate"
        const val VOLUME_NAME = "volume_name"
        const val IS_FAVORITE = "is_favorite"
    }

    object Audio {
        object Media {
            // BaseColumns
            const val _ID = "_id"
            const val _COUNT = "_count"

            // MediaColumns
            const val DATA = "_data"
            const val SIZE = "_size"
            const val DISPLAY_NAME = "_display_name"
            const val TITLE = "title"
            const val MIME_TYPE = "mime_type"
            const val DATE_ADDED = "date_added"
            const val DATE_MODIFIED = "date_modified"
            const val RELATIVE_PATH = "relative_path"
            const val VOLUME_NAME = "volume_name"
            const val IS_PENDING = "is_pending"
            const val IS_FAVORITE = "is_favorite"
            const val BITRATE = "bitrate"
            const val GENERATION_MODIFIED = "generation_modified"

            // AudioColumns
            const val DURATION = "duration"
            const val ARTIST = "artist"
            const val ARTIST_ID = "artist_id"
            const val ALBUM = "album"
            const val ALBUM_ID = "album_id"
            const val ALBUM_ARTIST = "album_artist"
            const val COMPOSER = "composer"
            const val TRACK = "track"
            const val YEAR = "year"
            const val IS_MUSIC = "is_music"
            const val IS_PODCAST = "is_podcast"
            const val IS_AUDIOBOOK = "is_audiobook"
            const val GENRE = "genre"
            const val GENRE_ID = "genre_id"
            const val DISC_NUMBER = "disc_number"
            const val CD_TRACK_NUMBER = "cd_track_number"
            const val NUM_TRACKS = "num_tracks"

            @JvmField val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/audio/media")
            @JvmField val INTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/internal/audio/media")

            @JvmStatic fun getContentUri(volumeName: String): Uri = Uri.parse("content://media/$volumeName/audio/media")
            @JvmStatic fun getContentUriForPath(path: String): Uri? = EXTERNAL_CONTENT_URI
        }

        object Albums {
            const val _ID = "_id"
            const val ALBUM = "album"
            const val ALBUM_ID = "album_id"
            const val ARTIST = "artist"
            const val ARTIST_ID = "artist_id"
            const val NUMBER_OF_SONGS = "numsongs"
            const val FIRST_YEAR = "minyear"
            const val LAST_YEAR = "maxyear"

            @JvmField val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/audio/albums")

            @JvmStatic fun getContentUri(volumeName: String): Uri = Uri.parse("content://media/$volumeName/audio/albums")
        }

        object Artists {
            const val _ID = "_id"
            const val ARTIST = "artist"
            const val NUMBER_OF_ALBUMS = "number_of_albums"
            const val NUMBER_OF_TRACKS = "number_of_tracks"

            @JvmField val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/audio/artists")

            @JvmStatic fun getContentUri(volumeName: String): Uri = Uri.parse("content://media/$volumeName/audio/artists")
        }

        object Genres {
            const val _ID = "_id"
            const val NAME = "name"

            object Members {
                const val _ID = "_id"
                const val AUDIO_ID = "audio_id"
                const val GENRE_ID = "genre_id"

                @JvmStatic
                fun getContentUri(volumeName: String, genreId: Long): Uri =
                    Uri.parse("content://media/$volumeName/audio/genres/$genreId/members")
            }

            @JvmField val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/audio/genres")

            @JvmStatic fun getContentUri(volumeName: String): Uri = Uri.parse("content://media/$volumeName/audio/genres")
        }
    }

    object Downloads {
        @JvmField val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/downloads")

        @JvmStatic fun getContentUri(volumeName: String): Uri = Uri.parse("content://media/$volumeName/downloads")
    }
}

object Settings {
    object Global {
        const val ZEN_MODE = "zen_mode"
    }
}
