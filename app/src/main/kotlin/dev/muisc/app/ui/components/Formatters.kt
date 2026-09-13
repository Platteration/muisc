package dev.muisc.app.ui.components

import android.content.ContentUris
import android.net.Uri
import dev.muisc.app.data.db.Song
import java.util.Locale

private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

/** MediaStore album art content URI for an album id. */
fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

/** Album art for a song (its album's artwork). */
fun Song.artUri(): Uri = albumArtUri(albumId)

/** "m:ss" or "h:mm:ss". */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** Total duration of a list of songs, e.g. "1 h 12 min" / "34 min". */
fun formatTotalDuration(songs: List<Song>): String {
    val totalMin = songs.sumOf { it.durationMs } / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "$h h $m min" else "$m min"
}

fun formatScore(score: Double): String = String.format(Locale.US, "%.2f", score)

fun formatBpm(bpm: Double): String = String.format(Locale.US, "%.1f BPM", bpm)

fun formatLufs(lufs: Float): String = String.format(Locale.US, "%.1f LUFS", lufs)

fun formatDouble(value: Double, decimals: Int = 2): String = String.format(Locale.US, "%.${decimals}f", value)
