@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.draw

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp

fun Modifier.clip(shape: Shape): Modifier = this
fun Modifier.clipToBounds(): Modifier = this
fun Modifier.alpha(alpha: Float): Modifier = this
fun Modifier.blur(radius: Dp): Modifier = this
fun Modifier.blur(radiusX: Dp, radiusY: Dp): Modifier = this
fun Modifier.shadow(elevation: Dp, shape: Shape? = null, clip: Boolean = elevation > Dp(0f)): Modifier = this
fun Modifier.drawBehind(onDraw: DrawScope.() -> Unit): Modifier = this
fun Modifier.drawWithContent(onDraw: ContentDrawScope.() -> Unit): Modifier = this
