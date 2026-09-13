package dev.muisc.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.ArtistRow
import dev.muisc.app.ui.components.ArtworkImage
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.components.albumArtUri
import dev.muisc.app.ui.components.SectionHeader
import dev.muisc.app.ui.components.SongRow
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel
import dev.muisc.transitions.PlaybackContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { vm.searchQuery.value = it },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
                actions = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.searchQuery.value = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (results.isEmpty) {
            if (query.length >= 2) {
                EmptyState(title = stringResource(R.string.empty_generic), modifier = Modifier.padding(padding))
            }
            return@Scaffold
        }
        SongActionHost(vm, navigator) { onAction ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (results.artists.isNotEmpty()) {
                    item { SectionHeader(title = stringResource(R.string.nav_artists)) }
                    items(results.artists, key = { "ar-" + it.id }) { artist ->
                        ArtistRow(artist = artist, onClick = { navigator.toArtist(artist.id) })
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item { SectionHeader(title = stringResource(R.string.nav_albums)) }
                    items(results.albums, key = { "al-" + it.id }) { album ->
                        ListItem(
                            headlineContent = { Text(album.title) },
                            supportingContent = { Text(album.artist) },
                            leadingContent = {
                                ArtworkImage(
                                    model = album.albumArtUri ?: albumArtUri(album.id),
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                )
                            },
                            modifier = Modifier.clickable { navigator.toAlbum(album.id) },
                        )
                    }
                }
                if (results.songs.isNotEmpty()) {
                    item { SectionHeader(title = stringResource(R.string.nav_songs)) }
                    itemsIndexed(results.songs, key = { _, s -> "s-" + s.id }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = { vm.play(results.songs, index, PlaybackContext.QUEUE) },
                            onAction = { onAction(song, it) },
                        )
                    }
                }
            }
        }
    }
}
