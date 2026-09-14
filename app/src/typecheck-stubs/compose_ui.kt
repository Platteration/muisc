@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui

import androidx.compose.runtime.Stable

@Stable
interface Modifier {
    infix fun then(other: Modifier): Modifier = other

    interface Element : Modifier

    companion object : Modifier
}

@Stable
class Alignment private constructor() {
    class Horizontal internal constructor()
    class Vertical internal constructor()

    companion object {
        @JvmField val TopStart: Alignment = Alignment()
        @JvmField val TopCenter: Alignment = Alignment()
        @JvmField val TopEnd: Alignment = Alignment()
        @JvmField val CenterStart: Alignment = Alignment()
        @JvmField val Center: Alignment = Alignment()
        @JvmField val CenterEnd: Alignment = Alignment()
        @JvmField val BottomStart: Alignment = Alignment()
        @JvmField val BottomCenter: Alignment = Alignment()
        @JvmField val BottomEnd: Alignment = Alignment()

        @JvmField val Start: Horizontal = Horizontal()
        @JvmField val CenterHorizontally: Horizontal = Horizontal()
        @JvmField val End: Horizontal = Horizontal()

        @JvmField val Top: Vertical = Vertical()
        @JvmField val CenterVertically: Vertical = Vertical()
        @JvmField val Bottom: Vertical = Vertical()
    }
}

fun Modifier.zIndex(zIndex: Float): Modifier = this
fun Modifier.alpha(alpha: Float): Modifier = this
fun Modifier.rotate(degrees: Float): Modifier = this
fun Modifier.scale(scale: Float): Modifier = this
