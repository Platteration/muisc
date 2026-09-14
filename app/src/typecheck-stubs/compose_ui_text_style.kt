@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.text.style

@JvmInline
value class TextAlign private constructor(val value: Int) {
    companion object {
        val Left = TextAlign(1)
        val Right = TextAlign(2)
        val Center = TextAlign(3)
        val Justify = TextAlign(4)
        val Start = TextAlign(5)
        val End = TextAlign(6)
        val Unspecified = TextAlign(Int.MIN_VALUE)
    }
}

@JvmInline
value class TextOverflow private constructor(val value: Int) {
    companion object {
        val Clip = TextOverflow(1)
        val Ellipsis = TextOverflow(2)
        val Visible = TextOverflow(3)
    }
}

@JvmInline
value class TextDecoration private constructor(val mask: Int) {
    companion object {
        val None = TextDecoration(0)
        val Underline = TextDecoration(1)
        val LineThrough = TextDecoration(2)
    }
}
