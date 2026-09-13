package dev.muisc.app.ui.nowplaying

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.R
import dev.muisc.app.data.db.Song
import dev.muisc.app.data.prefs.NowPlayingLayout
import dev.muisc.app.playback.EdgeState
import dev.muisc.app.playback.PlayerState
import dev.muisc.app.playback.RepeatMode
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.ArtworkImage
import dev.muisc.app.ui.components.EdgeBadge
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.components.artUri
import dev.muisc.app.ui.components.formatDuration
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.PlayerViewModel
import dev.muisc.app.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Two colours pulled from the artwork for the ADAPTIVE layout. */
private data class ArtColors(val background: Color, val accent: Color)

private suspend fun extractArtColors(context: Context, uri: Uri): ArtColors? = withContext(Dispatchers.IO) {
    runCatching {
        val request = ImageRequest.Builder(context).data(uri).allowHardware(false).size(200).build()
        val result = context.imageLoader.execute(request)
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
        val palette = Palette.from(bitmap).generate()
        val bg = palette.getDarkVibrantColor(palette.getDarkMutedColor(0))
        val accent = palette.getVibrantColor(palette.getLightVibrantColor(palette.getLightMutedColor(0)))
        if (bg == 0 && accent == 0) null else ArtColors(Color(bg), Color(accent))
    }.getOrNull()
}

@Composable
fun NowPlayingScreen(navigator: MuiscNavigator) {
    val playerVm: PlayerViewModel = viewModel(factory = AppViewModelFactory)
    val settingsVm: SettingsViewModel = viewModel(factory = AppViewModelFactory)
    val state by playerVm.state.collectAsStateWithLifecycle()
    val analysis by playerVm.currentAnalysis.collectAsStateWithLifecycle()
    val uiPrefs by settingsVm.uiPrefs.collectAsStateWithLifecycle()
    val layout = uiPrefs?.nowPlayingLayout ?: NowPlayingLayout.NORMAL

    val song = state.current
    if (song == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            IconButton(onClick = navigator::back) {
                Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.action_back))
            }
            EmptyState(title = stringResource(R.string.nothing_playing))
        }
        return
    }

    val context = LocalContext.current
    var artColors by remember { mutableStateOf<ArtColors?>(null) }
    LaunchedEffect(song.albumId, layout) {
        artColors = if (layout == NowPlayingLayout.ADAPTIVE) extractArtColors(context, song.artUri()) else null
    }

    val body: @Composable (showArt: Boolean, accent: Color?) -> Unit = { showArt, accent ->
        PlayerBody(
            state = state,
            song = song,
            analysis = analysis,
            showArt = showArt,
            accent = accent,
            onSeek = playerVm::seekTo,
            onTogglePlay = playerVm::togglePlayPause,
            onNext = playerVm::next,
            onPrevious = playerVm::previous,
            onShuffle = playerVm::toggleShuffle,
            onRepeat = playerVm::cycleRepeat,
            onOpenLab = { navigator.toLab(song.id, state.next?.id) },
        )
    }

    when (layout) {
        NowPlayingLayout.NORMAL -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            NowPlayingTopRow(navigator, song, state)
            body(true, null)
        }
        NowPlayingLayout.MINIMAL -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            NowPlayingTopRow(navigator, song, state)
            body(false, null)
        }
        NowPlayingLayout.CARD -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            NowPlayingTopRow(navigator, song, state)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.elevatedCardColors(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
            ) {
                body(true, null)
            }
        }
        NowPlayingLayout.BLUR -> Box(Modifier.fillMaxSize()) {
            ArtworkImage(
                model = song.artUri(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(48.dp),
                shape = androidx.compose.ui.graphics.RectangleShape,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            ),
                        ),
                    ),
            )
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                NowPlayingTopRow(navigator, song, state)
                body(true, null)
            }
        }
        NowPlayingLayout.ADAPTIVE -> {
            val colors = artColors
            val bg = colors?.background ?: MaterialTheme.colorScheme.surfaceContainer
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(bg, MaterialTheme.colorScheme.background))),
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    NowPlayingTopRow(navigator, song, state)
                    body(true, colors?.accent)
                }
            }
        }
    }
}

@Composable
private fun NowPlayingTopRow(navigator: MuiscNavigator, song: Song, state: PlayerState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = navigator::back) {
            Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.action_back))
        }
        Text(
            text = state.context.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { navigator.toLab(song.id, state.next?.id) }) {
            Icon(Icons.Rounded.Science, contentDescription = stringResource(R.string.nav_lab))
        }
        IconButton(onClick = navigator::toQueue) {
            Icon(Icons.Rounded.QueueMusic, contentDescription = stringResource(R.string.nav_queue))
        }
    }
}

@Composable
private fun PlayerBody(
    state: PlayerState,
    song: Song,
    analysis: TrackAnalysis?,
    showArt: Boolean,
    accent: Color?,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenLab: () -> Unit,
) {
    val accentColor = accent ?: MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showArt) {
            ArtworkImage(
                model = song.artUri(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .aspectRatio(1f),
                shape = MaterialTheme.shapes.large,
            )
            Spacer(Modifier.height(20.dp))
        } else {
            Spacer(Modifier.height(48.dp))
        }

        Text(
            song.title,
            style = if (showArt) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))

        // Transition in progress
        if (state.inTransition) {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
                Text(
                    stringResource(R.string.mixing_into, state.next?.title ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        WaveformStrip(
            positionMs = state.positionMs,
            durationMs = if (state.durationMs > 0) state.durationMs else song.durationMs,
            grid = analysis?.grid,
            sampleRate = analysis?.sampleRate ?: 44_100,
            onSeek = onSeek,
        )

        SeekBar(
            positionMs = state.positionMs,
            durationMs = if (state.durationMs > 0) state.durationMs else song.durationMs,
            onSeek = onSeek,
            accent = accentColor,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = onShuffle) {
                Icon(
                    Icons.Rounded.Shuffle,
                    contentDescription = stringResource(R.string.shuffle),
                    tint = if (state.shuffle) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.previous), modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = accentColor),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.next), modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onRepeat) {
                Icon(
                    imageVector = if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = stringResource(R.string.repeat),
                    tint = if (state.repeat != RepeatMode.OFF) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Next transition chip for the current edge
        val next = state.next
        if (next != null) {
            val edge = state.edges[state.currentIndex] ?: EdgeState.Unknown
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.next_transition) + " → " + next.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                EdgeBadge(edge = edge, onClick = onOpenLab)
            }
        }
        state.error?.let { err ->
            Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, accent: Color) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = dragging ?: fraction
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * durationMs).toLong()) }
                dragging = null
            },
            enabled = durationMs > 0,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((shown * durationMs).toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}
