package dev.muisc.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.muisc.app.R
import dev.muisc.app.data.db.Song

/** Sort orders offered by the song list. Sorting is applied in the ViewModel so it works on any song source. */
enum class SongSort(val labelRes: Int) {
    TITLE(R.string.sort_title),
    ARTIST(R.string.sort_artist),
    ALBUM(R.string.sort_album),
    DATE_ADDED(R.string.sort_date_added),
    DURATION(R.string.sort_duration),
    YEAR(R.string.sort_year),
}

fun List<Song>.applySort(sort: SongSort): List<Song> = when (sort) {
    SongSort.TITLE -> sortedBy { it.title.lowercase() }
    SongSort.ARTIST -> sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.track }))
    SongSort.ALBUM -> sortedWith(compareBy({ it.album.lowercase() }, { it.disc }, { it.track }))
    SongSort.DATE_ADDED -> sortedByDescending { it.dateAdded }
    SongSort.DURATION -> sortedByDescending { it.durationMs }
    SongSort.YEAR -> sortedWith(compareByDescending<Song> { it.year }.thenBy { it.album.lowercase() }.thenBy { it.track })
}

@Composable
fun SortMenu(
    current: SongSort,
    onSelect: (SongSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Sort, contentDescription = stringResource(R.string.sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SongSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { androidx.compose.material3.Text(stringResource(sort.labelRes)) },
                    trailingIcon = if (sort == current) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        open = false
                        onSelect(sort)
                    },
                )
            }
        }
    }
}
