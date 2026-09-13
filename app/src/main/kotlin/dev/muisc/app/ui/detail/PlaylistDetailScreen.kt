package dev.muisc.app.ui.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.data.db.Song
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.Routes
import dev.muisc.app.ui.components.ConfirmDialog
import dev.muisc.app.ui.components.DEFAULT_SONG_ACTIONS
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.components.PlaylistNameDialog
import dev.muisc.app.ui.components.SongAction
import dev.muisc.app.ui.components.SongRow
import dev.muisc.app.ui.library.LibraryTopBar
import dev.muisc.app.ui.library.PlayShuffleHeader
import dev.muisc.app.ui.library.SongActionHost
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel
import dev.muisc.transitions.PlaybackContext

/** A user playlist: play → PLAYLIST context; per-row "remove from playlist"; rename/delete in the overflow menu. */
@Composable
fun PlaylistDetailScreen(playlistId: Long, navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val playlist by remember(playlistId) { vm.playlist(playlistId) }.collectAsStateWithLifecycle(initialValue = null)
    val songs by remember(playlistId) { vm.songsOfPlaylist(playlistId) }.collectAsStateWithLifecycle(initialValue = emptyList())

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = playlist?.name ?: "",
                navigator = navigator,
                showBack = true,
                extraActions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menuOpen = false; deleting = true })
                    }
                },
            )
        },
    ) { padding ->
        SongList(
            songs = songs,
            vm = vm,
            navigator = navigator,
            context = PlaybackContext.PLAYLIST,
            padding = padding,
            onRemoveFromPlaylist = { song -> vm.removeFromPlaylist(playlistId, song) },
        )
    }

    if (renaming) {
        PlaylistNameDialog(
            title = stringResource(R.string.action_rename),
            initial = playlist?.name ?: "",
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { vm.renamePlaylist(playlistId, it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        ConfirmDialog(
            text = stringResource(R.string.delete_playlist_confirm, playlist?.name ?: ""),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                vm.deletePlaylist(playlistId)
                navigator.back()
            },
            onDismiss = { deleting = false },
        )
    }
}

/** Smart playlists: recently added / most played / history. Play → PLAYLIST context. */
@Composable
fun SmartPlaylistScreen(kind: String, navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val songs by when (kind) {
        Routes.SMART_MOST_PLAYED -> vm.mostPlayed
        Routes.SMART_HISTORY -> vm.history
        else -> vm.recentlyAdded
    }.collectAsStateWithLifecycle()
    val title = stringResource(
        when (kind) {
            Routes.SMART_MOST_PLAYED -> R.string.most_played
            Routes.SMART_HISTORY -> R.string.history
            else -> R.string.recently_added
        },
    )
    Scaffold(
        topBar = { LibraryTopBar(title = title, navigator = navigator, showBack = true) },
    ) { padding ->
        SongList(songs = songs, vm = vm, navigator = navigator, context = PlaybackContext.PLAYLIST, padding = padding)
    }
}

@Composable
internal fun SongList(
    songs: List<Song>,
    vm: LibraryViewModel,
    navigator: MuiscNavigator,
    context: PlaybackContext,
    padding: PaddingValues,
    onRemoveFromPlaylist: ((Song) -> Unit)? = null,
) {
    if (songs.isEmpty()) {
        EmptyState(title = stringResource(R.string.empty_generic), modifier = Modifier.padding(padding))
        return
    }
    val actions = if (onRemoveFromPlaylist != null) DEFAULT_SONG_ACTIONS + SongAction.REMOVE_FROM_PLAYLIST else DEFAULT_SONG_ACTIONS
    SongActionHost(vm, navigator, onRemoveFromPlaylist = onRemoveFromPlaylist) { onAction ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                PlayShuffleHeader(
                    songs = songs,
                    onPlay = { vm.play(songs, 0, context) },
                    onShuffle = { vm.shuffle(songs) },
                )
            }
            itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { vm.play(songs, index, context) },
                    onAction = { onAction(song, it) },
                    actions = actions,
                )
            }
        }
    }
}
