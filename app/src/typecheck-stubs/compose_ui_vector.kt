@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.graphics.vector

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

class ImageVector(val name: String)

class VectorPainter internal constructor() : Painter()

@Composable
fun rememberVectorPainter(image: ImageVector): VectorPainter = VectorPainter()
