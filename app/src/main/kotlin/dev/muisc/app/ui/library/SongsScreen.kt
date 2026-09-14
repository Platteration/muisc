package dev.muisc.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.data.db.Song
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.components.SongRow
import dev.muisc.app.ui.components.SortMenu
import dev.muisc.app.ui.components.formatTotalDuration
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel
import dev.muisc.transitions.PlaybackContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen(navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val songs by vm.songs.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = stringResource(R.string.nav_songs),
                navigator = navigator,
                extraActions = { SortMenu(current = sort, onSelect = { vm.sort.value = it }) },
            )
        },
    ) { padding ->
        if (songs.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.empty_library_title),
                body = stringResource(R.string.empty_library_body),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        SongActionHost(vm, navigator) { onAction ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    PlayShuffleHeader(
                        songs = songs,
                        onPlay = { vm.play(songs, 0, PlaybackContext.QUEUE) },
                        onShuffle = { vm.shuffle(songs) },
                    )
                }
                itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { vm.play(songs, index, PlaybackContext.QUEUE) },
                        onAction = { onAction(song, it) },
                    )
                }
            }
        }
    }
}

/** "Play · Shuffle" buttons with the count/duration summary, reused by list screens. */
@Composable
fun PlayShuffleHeader(
    songs: List<Song>,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    playLabel: String = stringResource(R.string.play_all),
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.songs_count, songs.size) + " · " + formatTotalDuration(songs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onPlay) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(playLabel)
        }
        FilledTonalButton(onClick = onShuffle) {
            Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.shuffle))
        }
    }
}
