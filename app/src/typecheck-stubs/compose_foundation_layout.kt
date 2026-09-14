@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
class PaddingValues internal constructor() {
    companion object {
        @JvmField val Zero: PaddingValues = PaddingValues()
    }
}

fun PaddingValues(all: Dp = 0.dp): PaddingValues = PaddingValues.Zero
fun PaddingValues(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): PaddingValues = PaddingValues.Zero
fun PaddingValues(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues = PaddingValues.Zero

object Arrangement {
    interface Horizontal
    interface Vertical
    interface HorizontalOrVertical : Horizontal, Vertical

    private object Impl : HorizontalOrVertical

    @JvmField val Start: Horizontal = Impl
    @JvmField val End: Horizontal = Impl
    @JvmField val Top: Vertical = Impl
    @JvmField val Bottom: Vertical = Impl
    @JvmField val Center: HorizontalOrVertical = Impl
    @JvmField val SpaceBetween: HorizontalOrVertical = Impl
    @JvmField val SpaceAround: HorizontalOrVertical = Impl
    @JvmField val SpaceEvenly: HorizontalOrVertical = Impl

    fun spacedBy(space: Dp): HorizontalOrVertical = Impl
    fun spacedBy(space: Dp, alignment: Alignment.Horizontal): Horizontal = Impl
    fun spacedBy(space: Dp, alignment: Alignment.Vertical): Vertical = Impl
}

@Stable
interface BoxScope {
    fun Modifier.align(alignment: Alignment): Modifier = this
    fun Modifier.matchParentSize(): Modifier = this
}

@Stable
interface ColumnScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
    fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
}

@Stable
interface RowScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
    fun Modifier.align(alignment: Alignment.Vertical): Modifier = this
}

private object BoxScopeImpl : BoxScope
private object ColumnScopeImpl : ColumnScope
private object RowScopeImpl : RowScope

@Composable
fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxScopeImpl.content()
}

@Composable
fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    ColumnScopeImpl.content()
}

@Composable
fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    RowScopeImpl.content()
}

@Composable
fun Spacer(modifier: Modifier) {}

/** The content-less `Box(modifier)` overload. */
@Composable
fun Box(modifier: Modifier) {}

fun Modifier.padding(all: Dp): Modifier = this
fun Modifier.padding(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier = this
fun Modifier.padding(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp): Modifier = this
fun Modifier.padding(paddingValues: PaddingValues): Modifier = this

fun Modifier.fillMaxSize(fraction: Float = 1f): Modifier = this
fun Modifier.fillMaxWidth(fraction: Float = 1f): Modifier = this
fun Modifier.fillMaxHeight(fraction: Float = 1f): Modifier = this
fun Modifier.wrapContentSize(): Modifier = this
fun Modifier.wrapContentWidth(): Modifier = this
fun Modifier.wrapContentHeight(): Modifier = this

fun Modifier.size(size: Dp): Modifier = this
fun Modifier.size(width: Dp, height: Dp): Modifier = this
fun Modifier.width(width: Dp): Modifier = this
fun Modifier.height(height: Dp): Modifier = this
fun Modifier.sizeIn(minWidth: Dp = Dp.Unspecified, minHeight: Dp = Dp.Unspecified, maxWidth: Dp = Dp.Unspecified, maxHeight: Dp = Dp.Unspecified): Modifier = this
fun Modifier.widthIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.heightIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.aspectRatio(ratio: Float, matchHeightConstraintsFirst: Boolean = false): Modifier = this
fun Modifier.offset(x: Dp = 0.dp, y: Dp = 0.dp): Modifier = this

// --- window insets ---------------------------------------------------------

@Stable
interface WindowInsets {
    fun getLeft(density: androidx.compose.ui.unit.Density, layoutDirection: androidx.compose.ui.unit.LayoutDirection): Int
    fun getTop(density: androidx.compose.ui.unit.Density): Int
    fun getRight(density: androidx.compose.ui.unit.Density, layoutDirection: androidx.compose.ui.unit.LayoutDirection): Int
    fun getBottom(density: androidx.compose.ui.unit.Density): Int

    companion object
}

private object EmptyWindowInsets : WindowInsets {
    override fun getLeft(density: androidx.compose.ui.unit.Density, layoutDirection: androidx.compose.ui.unit.LayoutDirection): Int = 0
    override fun getTop(density: androidx.compose.ui.unit.Density): Int = 0
    override fun getRight(density: androidx.compose.ui.unit.Density, layoutDirection: androidx.compose.ui.unit.LayoutDirection): Int = 0
    override fun getBottom(density: androidx.compose.ui.unit.Density): Int = 0
}

/** Top-level extensions, exactly as in `androidx.compose.foundation.layout.WindowInsets.kt`. */
fun WindowInsets.only(sides: WindowInsetsSides): WindowInsets = this
fun WindowInsets.add(insets: WindowInsets): WindowInsets = this
fun WindowInsets.exclude(insets: WindowInsets): WindowInsets = this
fun WindowInsets.union(insets: WindowInsets): WindowInsets = this

@JvmInline
value class WindowInsetsSides private constructor(private val value: Int) {
    operator fun plus(other: WindowInsetsSides): WindowInsetsSides = this

    companion object {
        val Left = WindowInsetsSides(1)
        val Top = WindowInsetsSides(2)
        val Right = WindowInsetsSides(4)
        val Bottom = WindowInsetsSides(8)
        val Start = WindowInsetsSides(16)
        val End = WindowInsetsSides(32)
        val Horizontal = WindowInsetsSides(64)
        val Vertical = WindowInsetsSides(128)
    }
}

val WindowInsets.Companion.systemBars: WindowInsets get() = EmptyWindowInsets
val WindowInsets.Companion.statusBars: WindowInsets get() = EmptyWindowInsets
val WindowInsets.Companion.navigationBars: WindowInsets get() = EmptyWindowInsets
val WindowInsets.Companion.safeDrawing: WindowInsets get() = EmptyWindowInsets
val WindowInsets.Companion.ime: WindowInsets get() = EmptyWindowInsets

fun Modifier.windowInsetsPadding(insets: WindowInsets): Modifier = this
fun Modifier.statusBarsPadding(): Modifier = this
fun Modifier.navigationBarsPadding(): Modifier = this
fun Modifier.systemBarsPadding(): Modifier = this
fun Modifier.safeDrawingPadding(): Modifier = this
fun Modifier.imePadding(): Modifier = this
fun Modifier.consumeWindowInsets(insets: WindowInsets): Modifier = this
fun Modifier.consumeWindowInsets(paddingValues: PaddingValues): Modifier = this

@Composable
fun WindowInsets.asPaddingValues(): PaddingValues = PaddingValues.Zero
