package dev.muisc.app.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.muisc.app.R
import dev.muisc.app.data.db.Song
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.AddToPlaylistDialog
import dev.muisc.app.ui.components.SongAction
import dev.muisc.app.ui.viewmodel.LibraryViewModel

/**
 * Hosts the "add to playlist" dialog and routes every [SongAction] to the ViewModel / navigator, so list screens
 * only need `SongActionHost(vm, navigator) { onAction -> ... SongRow(onAction = { onAction(song, it) }) }`.
 */
@Composable
fun SongActionHost(
    vm: LibraryViewModel,
    navigator: MuiscNavigator,
    onRemoveFromPlaylist: ((Song) -> Unit)? = null,
    content: @Composable (onAction: (Song, SongAction) -> Unit) -> Unit,
) {
    var pendingForPlaylist by remember { mutableStateOf<Song?>(null) }
    val playlists by vm.playlists.collectAsStateWithLifecycle()

    val handler: (Song, SongAction) -> Unit = { song, action ->
        when (action) {
            SongAction.PLAY_NEXT -> vm.playNext(listOf(song))
            SongAction.ADD_TO_QUEUE -> vm.addToQueue(listOf(song))
            SongAction.ADD_TO_PLAYLIST -> pendingForPlaylist = song
            SongAction.REMOVE_FROM_PLAYLIST -> onRemoveFromPlaylist?.invoke(song)
            SongAction.GO_TO_ALBUM -> navigator.toAlbum(song.albumId)
            SongAction.GO_TO_ARTIST -> navigator.toArtist(song.artistId)
            SongAction.OPEN_IN_LAB -> navigator.toLab(a = song.id)
        }
    }

    content(handler)

    val pending = pendingForPlaylist
    if (pending != null) {
        AddToPlaylistDialog(
            playlists = playlists,
            onPick = { playlist -> vm.addToPlaylist(playlist.id, listOf(pending)) },
            onCreate = { name -> vm.createPlaylist(name, listOf(pending)) },
            onDismiss = { pendingForPlaylist = null },
        )
    }
}

/** Standard top app bar for library screens: optional back arrow, title and the global shortcuts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTopBar(
    title: String,
    navigator: MuiscNavigator,
    showBack: Boolean = false,
    showSearch: Boolean = true,
    showQueue: Boolean = true,
    showLab: Boolean = false,
    showSettings: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    extraActions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = navigator::back) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            extraActions()
            if (showSearch) {
                IconButton(onClick = navigator::toSearch) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.nav_search))
                }
            }
            if (showQueue) {
                IconButton(onClick = navigator::toQueue) {
                    Icon(Icons.Rounded.QueueMusic, contentDescription = stringResource(R.string.nav_queue))
                }
            }
            if (showLab) {
                IconButton(onClick = { navigator.toLab() }) {
                    Icon(Icons.Rounded.Science, contentDescription = stringResource(R.string.nav_lab))
                }
            }
            if (showSettings) {
                IconButton(onClick = navigator::toSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.nav_settings))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(),
        scrollBehavior = scrollBehavior,
    )
}
