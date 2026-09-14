@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.unit

import androidx.compose.runtime.Stable

@JvmInline
value class Dp(val value: Float) : Comparable<Dp> {
    override fun compareTo(other: Dp): Int = value.compareTo(other.value)
    operator fun plus(other: Dp): Dp = Dp(value + other.value)
    operator fun minus(other: Dp): Dp = Dp(value - other.value)
    operator fun times(other: Float): Dp = Dp(value * other)
    operator fun div(other: Float): Dp = Dp(value / other)
    operator fun unaryMinus(): Dp = Dp(-value)

    companion object {
        val Hairline: Dp = Dp(0f)
        val Infinity: Dp = Dp(Float.POSITIVE_INFINITY)
        val Unspecified: Dp = Dp(Float.NaN)
    }
}

val Int.dp: Dp get() = Dp(this.toFloat())
val Float.dp: Dp get() = Dp(this)
val Double.dp: Dp get() = Dp(this.toFloat())

@JvmInline
value class TextUnit(val value: Float) {
    operator fun times(other: Float): TextUnit = TextUnit(value * other)

    companion object {
        val Unspecified: TextUnit = TextUnit(Float.NaN)
    }
}

val Int.sp: TextUnit get() = TextUnit(this.toFloat())
val Float.sp: TextUnit get() = TextUnit(this)
val Double.sp: TextUnit get() = TextUnit(this.toFloat())

data class DpSize(val width: Dp, val height: Dp)

data class IntSize(val width: Int, val height: Int)

data class IntOffset(val x: Int, val y: Int)

@Stable
interface Density {
    val density: Float
    val fontScale: Float
    fun Dp.toPx(): Float = value * density
    fun Dp.roundToPx(): Int = (value * density).toInt()
    fun Float.toDp(): Dp = Dp(this / density)
    fun Int.toDp(): Dp = Dp(this / density)
    fun TextUnit.toPx(): Float = value * density * fontScale
}

enum class LayoutDirection { Ltr, Rtl }
