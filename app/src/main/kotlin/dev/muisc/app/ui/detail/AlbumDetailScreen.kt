package dev.muisc.app.ui.detail

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.ArtworkImage
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.components.SongRow
import dev.muisc.app.ui.components.albumArtUri
import dev.muisc.app.ui.components.formatTotalDuration
import dev.muisc.app.ui.library.LibraryTopBar
import dev.muisc.app.ui.library.SongActionHost
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel
import dev.muisc.transitions.PlaybackContext
import kotlinx.coroutines.launch

/**
 * Album page. "Play in order" builds an ALBUM queue (gapless, no DJ transitions — the tooltip explains why);
 * "Shuffle" builds a SHUFFLE queue where transitions apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(albumId: Long, navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val album by remember(albumId) { vm.album(albumId) }.collectAsStateWithLifecycle(initialValue = null)
    val songs by remember(albumId) { vm.songsOfAlbum(albumId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = album?.title ?: songs.firstOrNull()?.album ?: "",
                navigator = navigator,
                showBack = true,
            )
        },
    ) { padding ->
        if (songs.isEmpty()) {
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
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkImage(
                            model = album?.albumArtUri ?: albumArtUri(albumId),
                            contentDescription = null,
                            modifier = Modifier.size(140.dp),
                            shape = MaterialTheme.shapes.medium,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                album?.title ?: songs.first().album,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                album?.artist ?: songs.first().albumArtist?.takeIf { it.isNotBlank() } ?: songs.first().artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val yearText = album?.year?.takeIf { it > 0 }?.toString()
                                ?: songs.first().year.takeIf { it > 0 }?.toString()
                                ?: ""
                            Text(
                                listOf(
                                    yearText,
                                    stringResource(R.string.songs_count, songs.size),
                                    formatTotalDuration(songs),
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { vm.play(songs, 0, PlaybackContext.ALBUM) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.play_in_order), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalButton(onClick = { vm.shuffle(songs) }) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.shuffle))
                        }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(stringResource(R.string.album_order_tooltip)) } },
                            state = tooltipState,
                        ) {
                            IconButton(onClick = { scope.launch { tooltipState.show() } }) {
                                Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.album_order_tooltip))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
                itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { vm.play(songs, index, PlaybackContext.ALBUM) },
                        onAction = { onAction(song, it) },
                        showArt = false,
                        subtitle = song.artist,
                        leading = {
                            Text(
                                text = if (song.track > 0) (song.track % 1000).toString() else "–",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}
