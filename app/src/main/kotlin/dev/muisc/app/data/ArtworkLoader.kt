package dev.muisc.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil.imageLoader
import coil.request.ImageRequest
import dev.muisc.app.R
import dev.muisc.app.data.db.Album
import dev.muisc.app.data.db.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Album-art helpers. Artwork is addressed through MediaStore's album-art content URIs
 * (`content://media/external/audio/albumart/<albumId>`), which Coil loads directly; [request] builds a Coil
 * [ImageRequest] with the placeholder drawable so every screen shows the same fallback. [loadBitmap] is for code
 * that needs pixels (Palette for the Adaptive Now Playing layout, the media-session notification).
 */
object ArtworkLoader {
    private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

    /** Drawable shown while loading, on error and when a song has no art. */
    val placeholderRes: Int = R.drawable.ic_album_placeholder

    fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

    fun artUri(song: Song): Uri = albumArtUri(song.albumId)

    fun artUri(album: Album): Uri = album.albumArtUri?.let { Uri.parse(it) } ?: albumArtUri(album.id)

    /** Coil request for an album's art at roughly [sizePx] pixels (square), with placeholder/error/fallback set. */
    fun request(context: Context, albumId: Long, sizePx: Int = 512, allowHardware: Boolean = true): ImageRequest =
        request(context, albumArtUri(albumId), sizePx, allowHardware, "album:$albumId")

    fun request(context: Context, song: Song, sizePx: Int = 512, allowHardware: Boolean = true): ImageRequest =
        request(context, song.albumId, sizePx, allowHardware)

    fun request(context: Context, album: Album, sizePx: Int = 512, allowHardware: Boolean = true): ImageRequest =
        request(context, artUri(album), sizePx, allowHardware, "album:${album.id}")

    private fun request(context: Context, uri: Uri, sizePx: Int, allowHardware: Boolean, cacheKey: String): ImageRequest =
        ImageRequest.Builder(context)
            .data(uri)
            .size(sizePx)
            .crossfade(true)
            .allowHardware(allowHardware)
            .memoryCacheKey("$cacheKey:$sizePx")
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .fallback(placeholderRes)
            .build()

    /**
     * Loads the art as a software [Bitmap] (so Palette can read pixels), or null when the album has none.
     * Uses Coil (and therefore its caches); falls back to a direct MediaStore read on failure.
     */
    suspend fun loadBitmap(context: Context, song: Song, sizePx: Int = 512): Bitmap? = loadBitmap(context, song.albumId, song.id, sizePx)

    suspend fun loadBitmap(context: Context, albumId: Long, songId: Long? = null, sizePx: Int = 512): Bitmap? {
        val viaCoil = try {
            val req = request(context, albumId, sizePx, allowHardware = false)
            val result = context.imageLoader.execute(req)
            (result.drawable as? BitmapDrawable)?.bitmap
        } catch (e: Exception) {
            null
        }
        if (viaCoil != null) return viaCoil
        return withContext(Dispatchers.IO) { loadDirect(context.contentResolver, albumId, songId, sizePx) }
    }

    /**
     * Direct MediaStore read without Coil. On API 29+ `ContentResolver.loadThumbnail` on the SONG uri is the most
     * reliable route (it extracts embedded art when the album-art file is missing); older APIs decode the album-art
     * content URI. Returns null when there is no art or the file is unreadable.
     */
    fun loadDirect(resolver: ContentResolver, albumId: Long, songId: Long?, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && songId != null) {
            try {
                val songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
                return resolver.loadThumbnail(songUri, Size(sizePx, sizePx), null)
            } catch (e: Exception) {
                // fall through to the album-art uri
            }
        }
        return try {
            resolver.openInputStream(albumArtUri(albumId))?.use { input ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                // Two passes need two streams; the first pass only reads the header.
                BitmapFactory.decodeStream(input, null, bounds)
                val sample = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
                resolver.openInputStream(albumArtUri(albumId))?.use { second ->
                    BitmapFactory.decodeStream(second, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Power-of-two subsampling factor so the decoded image is at least [target] px on its shorter side. */
    internal fun sampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }
}
