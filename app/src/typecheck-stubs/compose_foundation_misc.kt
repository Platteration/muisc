@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.foundation.shape

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

@Immutable
open class CornerBasedShape internal constructor() : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Any = Unit
}

@Immutable
class RoundedCornerShape internal constructor() : CornerBasedShape()

fun RoundedCornerShape(size: Dp): RoundedCornerShape = RoundedCornerShape()
fun RoundedCornerShape(percent: Int): RoundedCornerShape = RoundedCornerShape()
fun RoundedCornerShape(
    topStart: Dp = androidx.compose.ui.unit.Dp(0f),
    topEnd: Dp = androidx.compose.ui.unit.Dp(0f),
    bottomEnd: Dp = androidx.compose.ui.unit.Dp(0f),
    bottomStart: Dp = androidx.compose.ui.unit.Dp(0f),
): RoundedCornerShape = RoundedCornerShape()

val CircleShape: RoundedCornerShape = RoundedCornerShape()
