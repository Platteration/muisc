@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation.lazy.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

sealed class GridCells {
    class Fixed(val count: Int) : GridCells()
    class Adaptive(val minSize: Dp) : GridCells()
    class FixedSize(val size: Dp) : GridCells()
}

@Stable
class LazyGridState internal constructor(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
    val firstVisibleItemIndex: Int = firstVisibleItemIndex
    val firstVisibleItemScrollOffset: Int = firstVisibleItemScrollOffset
    suspend fun scrollToItem(index: Int, scrollOffset: Int = 0) {}
    suspend fun animateScrollToItem(index: Int, scrollOffset: Int = 0) {}
}

@Composable
fun rememberLazyGridState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyGridState = LazyGridState(initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)

interface LazyGridItemScope {
    fun Modifier.animateItem(): Modifier = this
}

interface LazyGridScope {
    fun item(key: Any? = null, contentType: Any? = null, content: @Composable LazyGridItemScope.() -> Unit)
    fun items(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        contentType: (index: Int) -> Any? = { null },
        itemContent: @Composable LazyGridItemScope.(index: Int) -> Unit,
    )
}

inline fun <T> LazyGridScope.items(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    noinline contentType: (item: T) -> Any? = { null },
    crossinline itemContent: @Composable LazyGridItemScope.(item: T) -> Unit,
): Unit = items(items.size, key?.let { k -> { i: Int -> k(items[i]) } }, { i -> contentType(items[i]) }) { i ->
    itemContent(items[i])
}

inline fun <T> LazyGridScope.itemsIndexed(
    items: List<T>,
    noinline key: ((index: Int, item: T) -> Any)? = null,
    crossinline itemContent: @Composable LazyGridItemScope.(index: Int, item: T) -> Unit,
): Unit = items(items.size, key?.let { k -> { i: Int -> k(i, items[i]) } }) { i -> itemContent(i, items[i]) }

@Composable
fun LazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = LazyGridState(0, 0),
    contentPadding: PaddingValues = PaddingValues.Zero,
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit,
) {
}
