package dev.muisc.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.AlbumCard
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.components.SectionHeader
import dev.muisc.app.ui.components.SongRow
import dev.muisc.app.ui.library.LibraryTopBar
import dev.muisc.app.ui.library.PlayShuffleHeader
import dev.muisc.app.ui.library.SongActionHost
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel
import dev.muisc.transitions.PlaybackContext

@Composable
fun ArtistDetailScreen(artistId: Long, navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val artist by remember(artistId) { vm.artist(artistId) }.collectAsStateWithLifecycle(initialValue = null)
    val albums by remember(artistId) { vm.albumsOfArtist(artistId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val songs by remember(artistId) { vm.songsOfArtist(artistId) }.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = artist?.name ?: songs.firstOrNull()?.artist ?: "",
                navigator = navigator,
                showBack = true,
            )
        },
    ) { padding ->
        if (songs.isEmpty() && albums.isEmpty()) {
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
                    PlayShuffleHeader(
                        songs = songs,
                        onPlay = { vm.play(songs, 0, PlaybackContext.QUEUE) },
                        onShuffle = { vm.shuffle(songs) },
                    )
                }
                if (albums.isNotEmpty()) {
                    item { SectionHeader(title = stringResource(R.string.nav_albums)) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(albums, key = { it.id }) { album ->
                                AlbumCard(
                                    album = album,
                                    onClick = { navigator.toAlbum(album.id) },
                                    modifier = Modifier.width(150.dp),
                                )
                            }
                        }
                    }
                }
                item { SectionHeader(title = stringResource(R.string.nav_songs)) }
                itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { vm.play(songs, index, PlaybackContext.QUEUE) },
                        onAction = { onAction(song, it) },
                        subtitle = song.album,
                    )
                }
            }
        }
    }
}
