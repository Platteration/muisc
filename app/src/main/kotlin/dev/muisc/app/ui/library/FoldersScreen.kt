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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import dev.muisc.transitions.PlaybackContext

/** Folder tree built from song paths. Navigates within the tree; system back walks up until the root. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val tree by vm.folderTree.collectAsStateWithLifecycle()
    var currentPath by rememberSaveable { mutableStateOf("") }

    // `FolderTree.build` already collapses single-child, song-less chains (e.g. /storage/emulated/0), so the tree's
    // own root is the first useful folder and no extra walk-down is needed here.
    val node = if (currentPath.isEmpty()) tree else (tree.find(currentPath) ?: tree)
    val atRoot = node === tree

    BackHandler(enabled = !atRoot) {
        currentPath = node.path.substringBeforeLast('/', "")
        if (currentPath.length <= tree.path.length) currentPath = ""
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
        if (node.children.isEmpty() && node.items.isEmpty()) {
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
                if (node.items.isNotEmpty()) {
                    item {
                        PlayShuffleHeader(
                            songs = node.items,
                            onPlay = { vm.play(node.items, 0, PlaybackContext.QUEUE) },
                            onShuffle = { vm.shuffle(node.allItems) },
                        )
                    }
                }
                items(node.children, key = { "d:" + it.path }) { child ->
                    ListItem(
                        headlineContent = { Text(child.name) },
                        supportingContent = { Text(stringResource(R.string.songs_count, child.totalCount)) },
                        leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                        modifier = Modifier.clickable { currentPath = child.path },
                    )
                }
                itemsIndexed(node.items, key = { _, s -> "s:" + s.id }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { vm.play(node.items, index, PlaybackContext.QUEUE) },
                        onAction = { onAction(song, it) },
                    )
                }
            }
        }
    }
}
