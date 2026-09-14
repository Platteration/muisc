@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.graphics.painter

import androidx.compose.ui.geometry.Size

open class Painter {
    open val intrinsicSize: Size get() = Size.Unspecified
}

class ColorPainter(val color: androidx.compose.ui.graphics.Color) : Painter()
