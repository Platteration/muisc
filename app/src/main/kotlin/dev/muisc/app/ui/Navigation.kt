package dev.muisc.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.muisc.app.ui.detail.AlbumDetailScreen
import dev.muisc.app.ui.detail.ArtistDetailScreen
import dev.muisc.app.ui.detail.GenreDetailScreen
import dev.muisc.app.ui.detail.PlaylistDetailScreen
import dev.muisc.app.ui.detail.SmartPlaylistScreen
import dev.muisc.app.ui.lab.TransitionLabScreen
import dev.muisc.app.ui.library.AlbumsScreen
import dev.muisc.app.ui.library.ArtistsScreen
import dev.muisc.app.ui.library.FoldersScreen
import dev.muisc.app.ui.library.GenresScreen
import dev.muisc.app.ui.library.HomeScreen
import dev.muisc.app.ui.library.PlaylistsScreen
import dev.muisc.app.ui.library.SearchScreen
import dev.muisc.app.ui.library.SongsScreen
import dev.muisc.app.ui.nowplaying.NowPlayingScreen
import dev.muisc.app.ui.queue.QueueScreen
import dev.muisc.app.ui.settings.SettingsScreen

/** Route templates and builders. Keep every navigate() call going through these helpers. */
object Routes {
    const val HOME = "home"
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ALBUM = "album/{id}"
    const val ARTISTS = "artists"
    const val ARTIST = "artist/{id}"
    const val PLAYLISTS = "playlists"
    const val PLAYLIST = "playlist/{id}"
    const val SMART = "smart/{kind}"
    const val GENRES = "genres"
    const val GENRE = "genre/{id}"
    const val FOLDERS = "folders"
    const val SEARCH = "search"
    const val NOW_PLAYING = "nowPlaying"
    const val QUEUE = "queue"
    const val LAB = "lab?a={a}&b={b}"
    const val SETTINGS = "settings"
    const val SETTINGS_SECTION = "settings/{section}"

    fun album(id: Long) = "album/$id"
    fun artist(id: Long) = "artist/$id"
    fun playlist(id: Long) = "playlist/$id"
    fun smart(kind: String) = "smart/$kind"
    fun genre(id: Long) = "genre/$id"
    fun lab(a: Long? = null, b: Long? = null) = "lab?a=${a ?: -1L}&b=${b ?: -1L}"
    fun settings(section: String) = "settings/$section"

    /** Destinations that show the bottom navigation bar. */
    val topLevel: List<String> = listOf(HOME, SONGS, ALBUMS, ARTISTS, PLAYLISTS)

    const val SMART_RECENT = "recent"
    const val SMART_MOST_PLAYED = "mostPlayed"
    const val SMART_HISTORY = "history"
}

/** Navigation callbacks handed to screens so they never touch the NavController directly. */
class MuiscNavigator(private val navController: NavHostController) {
    fun back() { navController.popBackStack() }
    fun toAlbum(id: Long) = navController.navigate(Routes.album(id))
    fun toArtist(id: Long) = navController.navigate(Routes.artist(id))
    fun toPlaylist(id: Long) = navController.navigate(Routes.playlist(id))
    fun toSmart(kind: String) = navController.navigate(Routes.smart(kind))
    fun toGenre(id: Long) = navController.navigate(Routes.genre(id))
    fun toGenres() = navController.navigate(Routes.GENRES)
    fun toFolders() = navController.navigate(Routes.FOLDERS)
    fun toSearch() = navController.navigate(Routes.SEARCH)
    fun toQueue() = navController.navigate(Routes.QUEUE)
    fun toLab(a: Long? = null, b: Long? = null) = navController.navigate(Routes.lab(a, b))
    fun toSettings() = navController.navigate(Routes.SETTINGS)
    fun toSettingsSection(section: String) = navController.navigate(Routes.settings(section))

    fun toNowPlaying() {
        navController.navigate(Routes.NOW_PLAYING) { launchSingleTop = true }
    }

    /** Bottom-bar navigation: single top, restore state, pop to start. */
    fun toTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

private fun NavBackStackEntry.longArg(name: String): Long = arguments?.getLong(name) ?: -1L

@Composable
fun MuiscNavHost(
    navController: NavHostController,
    navigator: MuiscNavigator,
    modifier: Modifier = Modifier,
) {
    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) { HomeScreen(navigator) }
        composable(Routes.SONGS) { SongsScreen(navigator) }
        composable(Routes.ALBUMS) { AlbumsScreen(navigator) }
        composable(Routes.ARTISTS) { ArtistsScreen(navigator) }
        composable(Routes.PLAYLISTS) { PlaylistsScreen(navigator) }
        composable(Routes.GENRES) { GenresScreen(navigator) }
        composable(Routes.FOLDERS) { FoldersScreen(navigator) }
        composable(Routes.SEARCH) { SearchScreen(navigator) }

        composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            AlbumDetailScreen(albumId = it.longArg("id"), navigator = navigator)
        }
        composable(Routes.ARTIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            ArtistDetailScreen(artistId = it.longArg("id"), navigator = navigator)
        }
        composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            PlaylistDetailScreen(playlistId = it.longArg("id"), navigator = navigator)
        }
        composable(Routes.SMART, arguments = listOf(navArgument("kind") { type = NavType.StringType })) {
            SmartPlaylistScreen(kind = it.arguments?.getString("kind") ?: Routes.SMART_RECENT, navigator = navigator)
        }
        composable(Routes.GENRE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            GenreDetailScreen(genreId = it.longArg("id"), navigator = navigator)
        }

        composable(Routes.NOW_PLAYING) { NowPlayingScreen(navigator) }
        composable(Routes.QUEUE) { QueueScreen(navigator) }

        composable(
            Routes.LAB,
            arguments = listOf(
                navArgument("a") { type = NavType.LongType; defaultValue = -1L },
                navArgument("b") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            TransitionLabScreen(initialA = it.longArg("a"), initialB = it.longArg("b"), navigator = navigator)
        }

        composable(Routes.SETTINGS) { SettingsScreen(section = null, navigator = navigator) }
        composable(Routes.SETTINGS_SECTION, arguments = listOf(navArgument("section") { type = NavType.StringType })) {
            SettingsScreen(section = it.arguments?.getString("section"), navigator = navigator)
        }
    }
}
