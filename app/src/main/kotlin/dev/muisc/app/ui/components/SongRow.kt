package dev.muisc.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.muisc.app.R
import dev.muisc.app.data.db.Song

/** Context actions offered on every song row. Screens decide which ones they support. */
enum class SongAction { PLAY_NEXT, ADD_TO_QUEUE, ADD_TO_PLAYLIST, REMOVE_FROM_PLAYLIST, GO_TO_ALBUM, GO_TO_ARTIST, OPEN_IN_LAB }

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: ((SongAction) -> Unit)? = null,
    actions: List<SongAction> = DEFAULT_SONG_ACTIONS,
    showArt: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    isCurrent: Boolean = false,
    subtitle: String = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        if (showArt) {
            ArtworkImage(model = song.artUri(), contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatDuration(song.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onAction != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(stringResource(actionLabel(action))) },
                            onClick = {
                                menuOpen = false
                                onAction(action)
                            },
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

val DEFAULT_SONG_ACTIONS: List<SongAction> = listOf(
    SongAction.PLAY_NEXT,
    SongAction.ADD_TO_QUEUE,
    SongAction.ADD_TO_PLAYLIST,
    SongAction.GO_TO_ALBUM,
    SongAction.GO_TO_ARTIST,
    SongAction.OPEN_IN_LAB,
)

fun actionLabel(action: SongAction): Int = when (action) {
    SongAction.PLAY_NEXT -> R.string.play_next
    SongAction.ADD_TO_QUEUE -> R.string.add_to_queue
    SongAction.ADD_TO_PLAYLIST -> R.string.add_to_playlist
    SongAction.REMOVE_FROM_PLAYLIST -> R.string.remove_from_playlist
    SongAction.GO_TO_ALBUM -> R.string.go_to_album
    SongAction.GO_TO_ARTIST -> R.string.go_to_artist
    SongAction.OPEN_IN_LAB -> R.string.open_in_lab
}
