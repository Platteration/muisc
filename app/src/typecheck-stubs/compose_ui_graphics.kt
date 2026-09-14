@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.graphics

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density

@Immutable
class Color(val value: ULong) {
    constructor(red: Float, green: Float, blue: Float, alpha: Float = 1f) : this(0uL)

    val red: Float get() = 0f
    val green: Float get() = 0f
    val blue: Float get() = 0f
    val alpha: Float get() = 1f

    fun copy(alpha: Float = this.alpha, red: Float = this.red, green: Float = this.green, blue: Float = this.blue): Color =
        Color(0uL)

    companion object {
        @JvmField val Black: Color = Color(0uL)
        @JvmField val DarkGray: Color = Color(0uL)
        @JvmField val Gray: Color = Color(0uL)
        @JvmField val LightGray: Color = Color(0uL)
        @JvmField val White: Color = Color(0uL)
        @JvmField val Red: Color = Color(0uL)
        @JvmField val Green: Color = Color(0uL)
        @JvmField val Blue: Color = Color(0uL)
        @JvmField val Yellow: Color = Color(0uL)
        @JvmField val Cyan: Color = Color(0uL)
        @JvmField val Magenta: Color = Color(0uL)
        @JvmField val Transparent: Color = Color(0uL)
        @JvmField val Unspecified: Color = Color(0uL)
    }
}

fun Color(color: Int): Color = Color(0uL)
fun Color(color: Long): Color = Color(0uL)

fun lerp(start: Color, stop: Color, fraction: Float): Color = start

@Stable
interface Shape {
    fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Any
}

object RectangleShape : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Any = Unit
}

@Immutable
sealed class Brush {
    companion object {
        fun verticalGradient(colors: List<Color>, startY: Float = 0f, endY: Float = Float.POSITIVE_INFINITY): Brush = SolidColor(Color.Black)
        fun verticalGradient(vararg colorStops: Pair<Float, Color>, startY: Float = 0f, endY: Float = Float.POSITIVE_INFINITY): Brush = SolidColor(Color.Black)
        fun horizontalGradient(colors: List<Color>, startX: Float = 0f, endX: Float = Float.POSITIVE_INFINITY): Brush = SolidColor(Color.Black)
        fun horizontalGradient(vararg colorStops: Pair<Float, Color>, startX: Float = 0f, endX: Float = Float.POSITIVE_INFINITY): Brush = SolidColor(Color.Black)
        fun linearGradient(colors: List<Color>, start: Offset = Offset.Zero, end: Offset = Offset.Infinite): Brush = SolidColor(Color.Black)
        fun linearGradient(vararg colorStops: Pair<Float, Color>, start: Offset = Offset.Zero, end: Offset = Offset.Infinite): Brush = SolidColor(Color.Black)
        fun radialGradient(colors: List<Color>, center: Offset = Offset.Unspecified, radius: Float = Float.POSITIVE_INFINITY): Brush = SolidColor(Color.Black)
        fun sweepGradient(colors: List<Color>, center: Offset = Offset.Unspecified): Brush = SolidColor(Color.Black)
    }
}

@Immutable
class SolidColor(val value: Color) : Brush()

interface Path {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
    fun close()
    fun reset()
    fun addRect(rect: Rect)
}

fun Path(): Path = object : Path {
    override fun moveTo(x: Float, y: Float) {}
    override fun lineTo(x: Float, y: Float) {}
    override fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float) {}
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {}
    override fun close() {}
    override fun reset() {}
    override fun addRect(rect: Rect) {}
}

@Immutable
class PathEffect {
    companion object {
        fun dashPathEffect(intervals: FloatArray, phase: Float = 0f): PathEffect = PathEffect()
        fun cornerPathEffect(radius: Float): PathEffect = PathEffect()
        fun chainPathEffect(outer: PathEffect, inner: PathEffect): PathEffect = PathEffect()
    }
}

@JvmInline
value class StrokeCap private constructor(val value: Int) {
    companion object {
        val Butt = StrokeCap(0)
        val Round = StrokeCap(1)
        val Square = StrokeCap(2)
    }
}

@JvmInline
value class StrokeJoin private constructor(val value: Int) {
    companion object {
        val Miter = StrokeJoin(0)
        val Round = StrokeJoin(1)
        val Bevel = StrokeJoin(2)
    }
}

@JvmInline
value class BlendMode private constructor(val value: Int) {
    companion object {
        val SrcOver = BlendMode(0)
        val Multiply = BlendMode(1)
        val Plus = BlendMode(2)
    }
}

class GraphicsLayerScope {
    var alpha: Float = 1f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var translationX: Float = 0f
    var translationY: Float = 0f
    var rotationZ: Float = 0f
    var shadowElevation: Float = 0f
    var clip: Boolean = false
    var shape: Shape = RectangleShape
}

fun Modifier.graphicsLayer(block: GraphicsLayerScope.() -> Unit): Modifier = this

fun Modifier.graphicsLayer(
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    alpha: Float = 1f,
    translationX: Float = 0f,
    translationY: Float = 0f,
    shadowElevation: Float = 0f,
    rotationX: Float = 0f,
    rotationY: Float = 0f,
    rotationZ: Float = 0f,
    clip: Boolean = false,
    shape: Shape = RectangleShape,
): Modifier = this

fun Color.toArgb(): Int = 0

class ColorFilter private constructor() {
    companion object {
        fun tint(color: Color, blendMode: BlendMode = BlendMode.SrcOver): ColorFilter = ColorFilter()
    }
}
