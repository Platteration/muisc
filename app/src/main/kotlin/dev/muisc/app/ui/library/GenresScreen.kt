package dev.muisc.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel

@Composable
fun GenresScreen(navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val genres by vm.genres.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { LibraryTopBar(title = stringResource(R.string.nav_genres), navigator = navigator, showBack = true) },
    ) { padding ->
        if (genres.isEmpty()) {
            EmptyState(title = stringResource(R.string.empty_generic), modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(genres, key = { it.id }) { genre ->
                ListItem(
                    headlineContent = { Text(genre.name) },
                    leadingContent = { Icon(Icons.Rounded.Tag, contentDescription = null) },
                    modifier = Modifier.clickable { navigator.toGenre(genre.id) },
                )
            }
        }
    }
}
