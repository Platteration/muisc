package dev.muisc.app.ui.queue

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * Minimal long-press drag-to-reorder for a LazyColumn whose items map 1:1 to list indices (no headers).
 * The dragged item is translated visually; when its centre crosses a neighbour, [onMove] is called and the
 * offset is rebased so the item keeps following the finger.
 */
class ReorderState(
    private val listState: LazyListState,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    var draggingIndex: Int? by mutableStateOf(null)
        private set
    var dragOffset: Float by mutableFloatStateOf(0f)
        private set

    private fun itemInfo(index: Int): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

    fun onDragStart(index: Int) {
        draggingIndex = index
        dragOffset = 0f
    }

    fun onDrag(deltaY: Float) {
        val from = draggingIndex ?: return
        dragOffset += deltaY
        val item = itemInfo(from) ?: return
        val centre = item.offset + dragOffset + item.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index != from && centre >= it.offset && centre < it.offset + it.size
        } ?: return
        onMove(from, target.index)
        dragOffset -= (target.offset - item.offset)
        draggingIndex = target.index
    }

    fun onDragEnd() {
        draggingIndex = null
        dragOffset = 0f
    }

    fun isDragging(index: Int): Boolean = draggingIndex == index
    fun offsetFor(index: Int): Float = if (draggingIndex == index) dragOffset else 0f
}

@Composable
fun rememberReorderState(listState: LazyListState, onMove: (from: Int, to: Int) -> Unit): ReorderState {
    val currentOnMove by rememberUpdatedState(onMove)
    return remember(listState) { ReorderState(listState) { from, to -> currentOnMove(from, to) } }
}

/** Visual translation + elevation for the item at [index]. */
fun Modifier.reorderable(state: ReorderState, index: Int): Modifier =
    this
        .zIndex(if (state.isDragging(index)) 1f else 0f)
        .graphicsLayer {
            translationY = state.offsetFor(index)
            shadowElevation = if (state.isDragging(index)) 16f else 0f
        }

/** Long-press-then-drag handle for the item at [index]. Reads the latest index so it survives moves. */
@Composable
fun Modifier.reorderHandle(state: ReorderState, index: Int): Modifier {
    val currentIndex by rememberUpdatedState(index)
    return this.pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.onDragStart(currentIndex) },
            onDrag = { change, dragAmount ->
                change.consume()
                state.onDrag(dragAmount.y)
            },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.onDragEnd() },
        )
    }
}
