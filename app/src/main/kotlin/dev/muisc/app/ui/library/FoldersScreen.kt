package dev.muisc.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.components.SongRow
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel
import dev.muisc.app.ui.viewmodel.find
import dev.muisc.transitions.PlaybackContext

/** Folder tree built from song paths. Navigates within the tree; system back walks up until the root. */
@Composable
fun FoldersScreen(navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val tree by vm.folderTree.collectAsStateWithLifecycle()
    var currentPath by rememberSaveable { mutableStateOf("") }

    // Collapse single-child chains from the root (e.g. /storage/emulated/0) so the first screen is useful.
    val effectiveRoot = remember(tree) {
        var node = tree
        while (node.songs.isEmpty() && node.children.size == 1) node = node.children.first()
        node
    }
    val node = if (currentPath.isEmpty()) effectiveRoot else (tree.find(currentPath) ?: effectiveRoot)
    val atRoot = node === effectiveRoot

    BackHandler(enabled = !atRoot) {
        currentPath = node.path.substringBeforeLast('/', "")
        if (currentPath.length <= effectiveRoot.path.length) currentPath = ""
    }

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = if (atRoot) stringResource(R.string.nav_folders) else node.name,
                navigator = navigator,
                showBack = true,
            )
        },
    ) { padding ->
        if (node.children.isEmpty() && node.songs.isEmpty()) {
            EmptyState(title = stringResource(R.string.empty_generic), modifier = Modifier.padding(padding))
            return@Scaffold
        }
        SongActionHost(vm, navigator) { onAction ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (!atRoot) {
                    item {
                        Text(
                            node.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                if (node.songs.isNotEmpty()) {
                    item {
                        PlayShuffleHeader(
                            songs = node.songs,
                            onPlay = { vm.play(node.songs, 0, PlaybackContext.QUEUE) },
                            onShuffle = { vm.shuffle(node.allSongs()) },
                        )
                    }
                }
                items(node.children, key = { "d:" + it.path }) { child ->
                    ListItem(
                        headlineContent = { Text(child.name) },
                        supportingContent = { Text(stringResource(R.string.songs_count, child.totalSongs)) },
                        leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                        modifier = Modifier.clickable { currentPath = child.path },
                    )
                }
                itemsIndexed(node.songs, key = { _, s -> "s:" + s.id }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { vm.play(node.songs, index, PlaybackContext.QUEUE) },
                        onAction = { onAction(song, it) },
                    )
                }
            }
        }
    }
}
