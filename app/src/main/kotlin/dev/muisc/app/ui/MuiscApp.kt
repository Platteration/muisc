package dev.muisc.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.muisc.app.R
import dev.muisc.app.data.prefs.ThemeMode
import dev.muisc.app.ui.components.MiniPlayer
import dev.muisc.app.ui.components.PermissionGate
import dev.muisc.app.ui.theme.MuiscTheme
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LibraryViewModel
import dev.muisc.app.ui.viewmodel.PlayerViewModel
import dev.muisc.app.ui.viewmodel.SettingsViewModel

private data class BottomTab(val route: String, val labelRes: Int, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab(Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
    BottomTab(Routes.SONGS, R.string.nav_songs, Icons.Rounded.MusicNote),
    BottomTab(Routes.ALBUMS, R.string.nav_albums, Icons.Rounded.Album),
    BottomTab(Routes.ARTISTS, R.string.nav_artists, Icons.Rounded.Person),
    BottomTab(Routes.PLAYLISTS, R.string.nav_playlists, Icons.Rounded.QueueMusic),
)

/** Root composable: theme from prefs, permission gate, then the scaffold with bottom navigation + mini-player. */
@Composable
fun MuiscApp() {
    val settingsVm: SettingsViewModel = viewModel(factory = AppViewModelFactory)
    val uiPrefs by settingsVm.uiPrefs.collectAsStateWithLifecycle()
    val libraryVm: LibraryViewModel = viewModel(factory = AppViewModelFactory)

    MuiscTheme(
        themeMode = uiPrefs?.theme ?: ThemeMode.SYSTEM,
        dynamicColor = uiPrefs?.dynamicColor ?: true,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PermissionGate(onGranted = { libraryVm.rescan(full = false) }) {
                MuiscScaffold()
            }
        }
    }
}

@Composable
private fun MuiscScaffold() {
    val navController = rememberNavController()
    val navigator = remember(navController) { MuiscNavigator(navController) }
    val playerVm: PlayerViewModel = viewModel(factory = AppViewModelFactory)
    val playerState by playerVm.state.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in Routes.topLevel
    val showMiniPlayer = playerState.current != null && currentRoute != Routes.NOW_PLAYING

    Scaffold(
        // Top insets are handled by each screen's own top app bar; the scaffold only reserves the bottom/sides.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        bottomBar = {
            Column {
                if (showMiniPlayer) {
                    MiniPlayer(
                        state = playerState,
                        onTogglePlay = playerVm::togglePlayPause,
                        onNext = playerVm::next,
                        onOpen = navigator::toNowPlaying,
                        modifier = if (showBottomBar) Modifier else Modifier.navigationBarsPadding(),
                    )
                }
                if (showBottomBar) {
                    NavigationBar {
                        bottomTabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = { navigator.toTopLevel(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.labelRes)) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        MuiscNavHost(
            navController = navController,
            navigator = navigator,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }
}
