@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.text.font

import androidx.compose.runtime.Immutable

@Immutable
class FontFamily internal constructor() {
    companion object {
        @JvmField val Default: FontFamily = FontFamily()
        @JvmField val SansSerif: FontFamily = FontFamily()
        @JvmField val Serif: FontFamily = FontFamily()
        @JvmField val Monospace: FontFamily = FontFamily()
        @JvmField val Cursive: FontFamily = FontFamily()
    }
}

@Immutable
class FontWeight(val weight: Int) : Comparable<FontWeight> {
    override fun compareTo(other: FontWeight): Int = weight.compareTo(other.weight)

    companion object {
        @JvmField val W100: FontWeight = FontWeight(100)
        @JvmField val W200: FontWeight = FontWeight(200)
        @JvmField val W300: FontWeight = FontWeight(300)
        @JvmField val W400: FontWeight = FontWeight(400)
        @JvmField val W500: FontWeight = FontWeight(500)
        @JvmField val W600: FontWeight = FontWeight(600)
        @JvmField val W700: FontWeight = FontWeight(700)
        @JvmField val W800: FontWeight = FontWeight(800)
        @JvmField val W900: FontWeight = FontWeight(900)
        @JvmField val Thin: FontWeight = W100
        @JvmField val ExtraLight: FontWeight = W200
        @JvmField val Light: FontWeight = W300
        @JvmField val Normal: FontWeight = W400
        @JvmField val Medium: FontWeight = W500
        @JvmField val SemiBold: FontWeight = W600
        @JvmField val Bold: FontWeight = W700
        @JvmField val ExtraBold: FontWeight = W800
        @JvmField val Black: FontWeight = W900
    }
}

@JvmInline
value class FontStyle private constructor(val value: Int) {
    companion object {
        val Normal = FontStyle(0)
        val Italic = FontStyle(1)
    }
}
