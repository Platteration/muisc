@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.material3

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Modal bottom sheet (material3 1.3.x). It is experimental there, so the marker is kept: a caller without
// @OptIn(ExperimentalMaterial3Api::class) fails here exactly as on a real build. Only the parameters the app can
// reasonably use are declared; their names, order and defaults follow the 1.3 signatures.

@ExperimentalMaterial3Api
enum class SheetValue { Hidden, Expanded, PartiallyExpanded }

@Stable
@ExperimentalMaterial3Api
class SheetState internal constructor(
    val skipPartiallyExpanded: Boolean,
) {
    val currentValue: SheetValue get() = SheetValue.Hidden
    val targetValue: SheetValue get() = SheetValue.Hidden
    val isVisible: Boolean get() = false
    suspend fun expand() {}
    suspend fun partialExpand() {}
    suspend fun show() {}
    suspend fun hide() {}
}

@ExperimentalMaterial3Api
object BottomSheetDefaults {
    val SheetPeekHeight: Dp = 56.dp
    val SheetMaxWidth: Dp = 640.dp

    @Composable
    fun DragHandle(modifier: Modifier = Modifier, width: Dp = 32.dp, height: Dp = 4.dp, color: Color = Color.Unspecified) {}
}

@Composable
@ExperimentalMaterial3Api
fun rememberModalBottomSheetState(
    skipPartiallyExpanded: Boolean = false,
    confirmValueChange: (SheetValue) -> Boolean = { true },
): SheetState = SheetState(skipPartiallyExpanded)

@Composable
@ExperimentalMaterial3Api
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape? = null,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = Color.Unspecified,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
}
