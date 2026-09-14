@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.graphics.drawscope

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Density

sealed class DrawStyle

object Fill : DrawStyle()

class Stroke(
    val width: Float = 0f,
    val miter: Float = 4f,
    val cap: StrokeCap = StrokeCap.Butt,
    val join: StrokeJoin = StrokeJoin.Miter,
    val pathEffect: PathEffect? = null,
) : DrawStyle() {
    companion object {
        const val HairlineWidth: Float = 0f
        const val DefaultMiter: Float = 4f
    }
}

interface DrawScope : Density {
    val size: Size
    val center: Offset

    fun drawLine(
        color: Color,
        start: Offset,
        end: Offset,
        strokeWidth: Float = Stroke.HairlineWidth,
        cap: StrokeCap = StrokeCap.Butt,
        pathEffect: PathEffect? = null,
        alpha: Float = 1f,
        blendMode: BlendMode = BlendMode.SrcOver,
    )

    fun drawLine(
        brush: Brush,
        start: Offset,
        end: Offset,
        strokeWidth: Float = Stroke.HairlineWidth,
        cap: StrokeCap = StrokeCap.Butt,
        pathEffect: PathEffect? = null,
        alpha: Float = 1f,
        blendMode: BlendMode = BlendMode.SrcOver,
    )

    fun drawRect(
        color: Color,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        alpha: Float = 1f,
        style: DrawStyle = Fill,
        blendMode: BlendMode = BlendMode.SrcOver,
    )

    fun drawRect(
        brush: Brush,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        alpha: Float = 1f,
        style: DrawStyle = Fill,
        blendMode: BlendMode = BlendMode.SrcOver,
    )

    fun drawRoundRect(
        color: Color,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        cornerRadius: CornerRadius = CornerRadius.Zero,
        alpha: Float = 1f,
        style: DrawStyle = Fill,
        blendMode: BlendMode = BlendMode.SrcOver,
    )

    fun drawRoundRect(
        brush: Brush,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        cornerRadius: CornerRadius = CornerRadius.Zero,
        alpha: Float = 1f,
        style: DrawStyle = Fill,
        blendMode: BlendMode = BlendMode.SrcOver,
    )

    fun drawCircle(
        color: Color,
        radius: Float = size.minDimension / 2f,
        center: Offset = this.center,
        alpha: Float = 1f,
        style: DrawStyle = Fill,
        blendMode: BlendMode = BlendMode.SrcOver,
    )

    fun drawCircle(
        brush: Brush,
        radius: Float = size.minDimension / 2f,
        center: Offset = this.center,
        alpha: Float = 1f,
        style: DrawStyle = Fill,
        blendMode: BlendMode = BlendMode.SrcOver,
    )

    fun drawPath(
        path: Path,
        color: Color,
        alpha: Float = 1f,
        style: DrawStyle = Fill,
        blendMode: BlendMode = BlendMode.SrcOver,
    )

    fun drawPath(
        path: Path,
        brush: Brush,
        alpha: Float = 1f,
        style: DrawStyle = Fill,
        blendMode: BlendMode = BlendMode.SrcOver,
    )

    fun drawArc(
        color: Color,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        topLeft: Offset = Offset.Zero,
        size: Size = this.size,
        alpha: Float = 1f,
        style: DrawStyle = Fill,
        blendMode: BlendMode = BlendMode.SrcOver,
    )
}

interface ContentDrawScope : DrawScope {
    fun drawContent()
}
