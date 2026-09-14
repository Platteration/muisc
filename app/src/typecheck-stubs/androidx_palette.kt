@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.palette.graphics

import android.graphics.Bitmap

class Palette private constructor() {
    val dominantSwatch: Swatch? get() = null
    val vibrantSwatch: Swatch? get() = null
    val darkVibrantSwatch: Swatch? get() = null
    val lightVibrantSwatch: Swatch? get() = null
    val mutedSwatch: Swatch? get() = null
    val darkMutedSwatch: Swatch? get() = null
    val lightMutedSwatch: Swatch? get() = null
    val swatches: List<Swatch> get() = emptyList()

    fun getDominantColor(defaultColor: Int): Int = defaultColor
    fun getVibrantColor(defaultColor: Int): Int = defaultColor
    fun getMutedColor(defaultColor: Int): Int = defaultColor
    fun getDarkVibrantColor(defaultColor: Int): Int = defaultColor
    fun getLightVibrantColor(defaultColor: Int): Int = defaultColor
    fun getDarkMutedColor(defaultColor: Int): Int = defaultColor
    fun getLightMutedColor(defaultColor: Int): Int = defaultColor

    class Swatch(val rgb: Int, val population: Int) {
        val bodyTextColor: Int get() = 0
        val titleTextColor: Int get() = 0
        val hsl: FloatArray get() = FloatArray(3)
    }

    class Builder(bitmap: Bitmap) {
        fun maximumColorCount(count: Int): Builder = this
        fun resizeBitmapArea(area: Int): Builder = this
        fun clearFilters(): Builder = this
        fun generate(): Palette = Palette()
        fun generate(listener: (Palette?) -> Unit) {}
    }

    companion object {
        @JvmStatic fun from(bitmap: Bitmap): Builder = Builder(bitmap)
    }
}
