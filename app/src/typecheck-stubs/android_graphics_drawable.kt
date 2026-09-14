@file:Suppress("UNUSED_PARAMETER", "unused")

package android.graphics.drawable

import android.content.res.Resources
import android.graphics.Bitmap

abstract class Drawable {
    open val intrinsicWidth: Int get() = -1
    open val intrinsicHeight: Int get() = -1
    open fun setAlpha(alpha: Int) {}
}

open class BitmapDrawable(res: Resources?, val bitmap: Bitmap) : Drawable()

open class ColorDrawable(val color: Int) : Drawable()
