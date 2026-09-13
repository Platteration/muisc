package dev.muisc.app.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.ArtistRow
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel

@Composable
fun ArtistsScreen(navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val artists by vm.artists.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { LibraryTopBar(title = stringResource(R.string.nav_artists), navigator = navigator) },
    ) { padding ->
        if (artists.isEmpty()) {
            EmptyState(title = stringResource(R.string.empty_generic), modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(artists, key = { it.id }) { artist ->
                ArtistRow(artist = artist, onClick = { navigator.toArtist(artist.id) })
            }
        }
    }
}
