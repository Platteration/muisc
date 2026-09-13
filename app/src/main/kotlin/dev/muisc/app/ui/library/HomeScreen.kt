package dev.muisc.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.data.db.Song
import dev.muisc.app.di.AppGraph
import dev.muisc.app.di.ScanStatus
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.Routes
import dev.muisc.app.ui.components.ArtworkImage
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.components.SectionHeader
import dev.muisc.app.ui.components.SongRow
import dev.muisc.app.ui.components.artUri
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel
import dev.muisc.transitions.PlaybackContext

@Composable
fun HomeScreen(navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val songs by vm.songs.collectAsStateWithLifecycle()
    val recent by vm.recentlyAdded.collectAsStateWithLifecycle()
    val mostPlayed by vm.mostPlayed.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val scanStatus by AppGraph.scanStatus.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = stringResource(R.string.app_name),
                navigator = navigator,
                showLab = true,
                showSettings = true,
            )
        },
    ) { padding ->
        if (songs.isEmpty() && recent.isEmpty()) {
            Column(Modifier.padding(padding)) {
                if (scanStatus is ScanStatus.Running) LinearProgressIndicator(Modifier.fillMaxWidth())
                EmptyState(
                    title = stringResource(R.string.empty_library_title),
                    body = stringResource(R.string.empty_library_body),
                    actionLabel = stringResource(R.string.settings_rescan),
                    onAction = { vm.rescan(full = true) },
                )
            }
            return@Scaffold
        }
        SongActionHost(vm, navigator) { onAction ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (scanStatus is ScanStatus.Running) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(onClick = vm::shuffleAll, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.shuffle_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalButton(onClick = navigator::toGenres) {
                            Icon(Icons.Rounded.Tag, contentDescription = stringResource(R.string.nav_genres))
                        }
                        FilledTonalButton(onClick = navigator::toFolders) {
                            Icon(Icons.Rounded.Folder, contentDescription = stringResource(R.string.nav_folders))
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.recently_added),
                            actionLabel = stringResource(R.string.play_all),
                            onAction = { vm.play(recent, 0, PlaybackContext.QUEUE) },
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(recent, key = { _, s -> s.id }) { index, song ->
                                SongCard(song = song, onClick = { vm.play(recent, index, PlaybackContext.QUEUE) })
                            }
                        }
                    }
                }
                if (mostPlayed.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.most_played),
                            actionLabel = stringResource(R.string.play_all),
                            onAction = { navigator.toSmart(Routes.SMART_MOST_PLAYED) },
                        )
                    }
                    itemsIndexed(mostPlayed.take(5), key = { i, s -> "mp-$i-${s.id}" }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = { vm.play(mostPlayed, index, PlaybackContext.QUEUE) },
                            onAction = { onAction(song, it) },
                        )
                    }
                }
                if (history.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.history),
                            actionLabel = stringResource(R.string.play_all),
                            onAction = { navigator.toSmart(Routes.SMART_HISTORY) },
                        )
                    }
                    itemsIndexed(history.take(5), key = { i, s -> "h-$i-${s.id}" }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = { vm.play(history, index, PlaybackContext.QUEUE) },
                            onAction = { onAction(song, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SongCard(song: Song, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        ArtworkImage(model = song.artUri(), contentDescription = null, modifier = Modifier.size(124.dp), shape = MaterialTheme.shapes.medium)
        Spacer(Modifier.height(6.dp))
        Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
