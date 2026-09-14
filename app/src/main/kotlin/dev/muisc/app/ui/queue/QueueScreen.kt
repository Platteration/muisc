package dev.muisc.app.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.data.db.Song
import dev.muisc.app.playback.EdgeState
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.ArtworkImage
import dev.muisc.app.ui.components.EdgeBadge
import dev.muisc.app.ui.components.EmptyState
import dev.muisc.app.ui.components.artUri
import dev.muisc.app.ui.components.formatDuration
import dev.muisc.app.ui.library.LibraryTopBar
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.PlayerViewModel

/**
 * The play queue: tap to skip, swipe left to remove, long-press + drag (handle or row) to reorder. Under each
 * item the badge for edge `i` (transition from item i to i+1) mirrors the coordinator's state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navigator: MuiscNavigator) {
    val vm: PlayerViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()

    // Local copy so drag moves feel immediate; re-synced from the engine when not dragging.
    var localQueue by remember { mutableStateOf(state.queue) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(listState) { from, to ->
        if (from == to) return@rememberReorderState
        val list = localQueue.toMutableList()
        if (from !in list.indices || to !in list.indices) return@rememberReorderState
        val item = list.removeAt(from)
        list.add(to, item)
        localQueue = list
        vm.moveQueueItem(from, to)
    }
    LaunchedEffect(state.queue, reorder.draggingIndex) {
        if (reorder.draggingIndex == null) localQueue = state.queue
    }

    // Stable keys for duplicates: song id + occurrence number.
    val keys = remember(localQueue) {
        val seen = HashMap<Long, Int>()
        localQueue.map { s ->
            val n = (seen[s.id] ?: 0) + 1
            seen[s.id] = n
            "${s.id}#$n"
        }
    }

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = stringResource(R.string.nav_queue),
                navigator = navigator,
                showBack = true,
                showSearch = false,
                showQueue = false,
                showLab = true,
                extraActions = {
                    if (localQueue.isNotEmpty()) {
                        IconButton(onClick = vm::clearQueue) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.clear_queue))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (localQueue.isEmpty()) {
            EmptyState(title = stringResource(R.string.queue_empty), modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(localQueue, key = { i, _ -> keys.getOrElse(i) { "i$i" } }) { index, song ->
                QueueItem(
                    index = index,
                    song = song,
                    isCurrent = index == state.currentIndex,
                    isPlaying = state.isPlaying && index == state.currentIndex,
                    edge = if (index < localQueue.lastIndex) (state.edges[index] ?: EdgeState.Unknown) else null,
                    inTransition = state.inTransition && index == state.currentIndex,
                    reorder = reorder,
                    onClick = { vm.skipToQueueItem(index) },
                    onRemove = { vm.removeQueueItem(index) },
                    onEdgeClick = { navigator.toLab(song.id, localQueue.getOrNull(index + 1)?.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueItem(
    index: Int,
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    edge: EdgeState?,
    inTransition: Boolean,
    reorder: ReorderState,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onEdgeClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
                true
            } else false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.reorderable(reorder, index),
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.remove), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .reorderHandle(reorder, index)
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    if (isPlaying) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    } else {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ArtworkImage(model = song.artUri(), contentDescription = null, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist + " · " + formatDuration(song.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(12.dp)
                        .reorderHandle(reorder, index),
                )
            }
            if (edge != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp, end = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EdgeBadge(edge = edge, onClick = onEdgeClick)
                    if (inTransition) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.transition_in_progress),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}
