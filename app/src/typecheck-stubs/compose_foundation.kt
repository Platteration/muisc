@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalFoundationApi

@Composable
fun isSystemInDarkTheme(): Boolean = false

@Composable
fun Canvas(modifier: Modifier, onDraw: DrawScope.() -> Unit) {}

@Composable
fun Canvas(modifier: Modifier, contentDescription: String, onDraw: DrawScope.() -> Unit) {}

fun Modifier.background(color: Color, shape: Shape = RectangleShape): Modifier = this
fun Modifier.background(brush: Brush, shape: Shape = RectangleShape, alpha: Float = 1f): Modifier = this

fun Modifier.border(width: Dp, color: Color, shape: Shape = RectangleShape): Modifier = this
fun Modifier.border(width: Dp, brush: Brush, shape: Shape): Modifier = this

fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: androidx.compose.ui.semantics.Role? = null,
    onClick: () -> Unit,
): Modifier = this

@ExperimentalFoundationApi
fun Modifier.combinedClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: androidx.compose.ui.semantics.Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this

fun Modifier.selectable(selected: Boolean, enabled: Boolean = true, onClick: () -> Unit): Modifier = this

@Stable
class ScrollState internal constructor(initial: Int) {
    val value: Int = initial
    val maxValue: Int = Int.MAX_VALUE
    suspend fun scrollTo(value: Int): Float = 0f
    suspend fun animateScrollTo(value: Int) {}
}

@Composable
fun rememberScrollState(initial: Int = 0): ScrollState = ScrollState(initial)

fun Modifier.verticalScroll(state: ScrollState, enabled: Boolean = true, reverseScrolling: Boolean = false): Modifier = this
fun Modifier.horizontalScroll(state: ScrollState, enabled: Boolean = true, reverseScrolling: Boolean = false): Modifier = this
