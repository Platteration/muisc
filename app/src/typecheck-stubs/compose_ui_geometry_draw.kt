@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.geometry

import androidx.compose.runtime.Immutable

@Immutable
data class Offset(val x: Float, val y: Float) {
    operator fun plus(other: Offset): Offset = Offset(x + other.x, y + other.y)
    operator fun minus(other: Offset): Offset = Offset(x - other.x, y - other.y)
    operator fun times(operand: Float): Offset = Offset(x * operand, y * operand)
    operator fun div(operand: Float): Offset = Offset(x / operand, y / operand)

    companion object {
        @JvmField val Zero: Offset = Offset(0f, 0f)
        @JvmField val Infinite: Offset = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        @JvmField val Unspecified: Offset = Offset(Float.NaN, Float.NaN)
    }
}

@Immutable
data class Size(val width: Float, val height: Float) {
    val minDimension: Float get() = minOf(width, height)
    val maxDimension: Float get() = maxOf(width, height)
    val center: Offset get() = Offset(width / 2f, height / 2f)

    companion object {
        @JvmField val Zero: Size = Size(0f, 0f)
        @JvmField val Unspecified: Size = Size(Float.NaN, Float.NaN)
    }
}

@Immutable
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Immutable
data class CornerRadius(val x: Float, val y: Float = x) {
    companion object {
        @JvmField val Zero: CornerRadius = CornerRadius(0f, 0f)
    }
}
