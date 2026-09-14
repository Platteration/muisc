@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation.lazy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

interface LazyListItemInfo {
    val index: Int
    val key: Any
    val offset: Int
    val size: Int
    val contentType: Any?
}

interface LazyListLayoutInfo {
    val visibleItemsInfo: List<LazyListItemInfo>
    val viewportStartOffset: Int
    val viewportEndOffset: Int
    val totalItemsCount: Int
}

@Stable
class LazyListState internal constructor(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
    val firstVisibleItemIndex: Int = firstVisibleItemIndex
    val firstVisibleItemScrollOffset: Int = firstVisibleItemScrollOffset
    val isScrollInProgress: Boolean = false
    val layoutInfo: LazyListLayoutInfo
        get() = object : LazyListLayoutInfo {
            override val visibleItemsInfo: List<LazyListItemInfo> = emptyList()
            override val viewportStartOffset: Int = 0
            override val viewportEndOffset: Int = 0
            override val totalItemsCount: Int = 0
        }

    suspend fun scrollToItem(index: Int, scrollOffset: Int = 0) {}
    suspend fun animateScrollToItem(index: Int, scrollOffset: Int = 0) {}
    suspend fun scrollBy(value: Float): Float = 0f
}

@Composable
fun rememberLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState = LazyListState(initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)

interface LazyItemScope {
    fun Modifier.fillParentMaxSize(fraction: Float = 1f): Modifier = this
    fun Modifier.fillParentMaxWidth(fraction: Float = 1f): Modifier = this
    fun Modifier.fillParentMaxHeight(fraction: Float = 1f): Modifier = this
    fun Modifier.animateItem(): Modifier = this
    fun Modifier.animateItemPlacement(): Modifier = this
}

interface LazyListScope {
    fun item(key: Any? = null, contentType: Any? = null, content: @Composable LazyItemScope.() -> Unit)
    fun items(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        contentType: (index: Int) -> Any? = { null },
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    )
    fun stickyHeader(key: Any? = null, contentType: Any? = null, content: @Composable LazyItemScope.() -> Unit)
}

inline fun <T> LazyListScope.items(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    noinline contentType: (item: T) -> Any? = { null },
    crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit,
): Unit = items(items.size, key?.let { k -> { i: Int -> k(items[i]) } }, { i -> contentType(items[i]) }) { i ->
    itemContent(items[i])
}

inline fun <T> LazyListScope.itemsIndexed(
    items: List<T>,
    noinline key: ((index: Int, item: T) -> Any)? = null,
    crossinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    crossinline itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit,
): Unit = items(items.size, key?.let { k -> { i: Int -> k(i, items[i]) } }, { i -> contentType(i, items[i]) }) { i ->
    itemContent(i, items[i])
}

@Composable
fun LazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = LazyListState(0, 0),
    contentPadding: PaddingValues = PaddingValues.Zero,
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
}

@Composable
fun LazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = LazyListState(0, 0),
    contentPadding: PaddingValues = PaddingValues.Zero,
    reverseLayout: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
}
