package dev.muisc.app.ui.detail

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.library.LibraryTopBar
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel
import dev.muisc.transitions.PlaybackContext

/** Songs of a genre. Play in order → QUEUE (transitions on); shuffle → SHUFFLE. */
@Composable
fun GenreDetailScreen(genreId: Long, navigator: MuiscNavigator) {
    val vm: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val genre by remember(genreId) { vm.genre(genreId) }.collectAsStateWithLifecycle(initialValue = null)
    val songs by remember(genreId) { vm.songsOfGenre(genreId) }.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = genre?.name ?: songs.firstOrNull()?.genre ?: "",
                navigator = navigator,
                showBack = true,
            )
        },
    ) { padding ->
        SongList(songs = songs, vm = vm, navigator = navigator, context = PlaybackContext.QUEUE, padding = padding)
    }
}
