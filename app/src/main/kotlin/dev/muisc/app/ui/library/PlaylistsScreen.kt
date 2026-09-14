package dev.muisc.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import dev.muisc.app.data.db.Playlist
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.Routes
import dev.muisc.app.ui.components.ConfirmDialog
import dev.muisc.app.ui.components.PlaylistNameDialog
import dev.muisc.app.ui.components.SectionHeader
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val playlists by vm.playlists.collectAsStateWithLifecycle()

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }

    Scaffold(
        topBar = { LibraryTopBar(title = stringResource(R.string.nav_playlists), navigator = navigator) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.new_playlist))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            item { SectionHeader(title = stringResource(R.string.smart_playlists)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.recently_added)) },
                    leadingContent = { Icon(Icons.Rounded.NewReleases, contentDescription = null) },
                    modifier = Modifier.clickable { navigator.toSmart(Routes.SMART_RECENT) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.most_played)) },
                    leadingContent = { Icon(Icons.Rounded.TrendingUp, contentDescription = null) },
                    modifier = Modifier.clickable { navigator.toSmart(Routes.SMART_MOST_PLAYED) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.history)) },
                    leadingContent = { Icon(Icons.Rounded.History, contentDescription = null) },
                    modifier = Modifier.clickable { navigator.toSmart(Routes.SMART_HISTORY) },
                )
            }
            item { SectionHeader(title = stringResource(R.string.your_playlists)) }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.empty_generic),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                PlaylistItem(
                    playlist = playlist,
                    onClick = { navigator.toPlaylist(playlist.id) },
                    onRename = { renaming = playlist },
                    onDelete = { deleting = playlist },
                )
            }
        }
    }

    if (creating) {
        PlaylistNameDialog(
            title = stringResource(R.string.new_playlist),
            initial = "",
            confirmLabel = stringResource(R.string.action_create),
            onConfirm = { vm.createPlaylist(it); creating = false },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { playlist ->
        PlaylistNameDialog(
            title = stringResource(R.string.action_rename),
            initial = playlist.name,
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { vm.renamePlaylist(playlist.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { playlist ->
        ConfirmDialog(
            text = stringResource(R.string.delete_playlist_confirm, playlist.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { vm.deletePlaylist(playlist.id) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun PlaylistItem(
    playlist: Playlist,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(playlist.name) },
        leadingContent = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
