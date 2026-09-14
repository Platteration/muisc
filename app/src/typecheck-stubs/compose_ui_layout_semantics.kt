@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.layout

import androidx.compose.ui.Modifier

interface ContentScale {
    companion object {
        @JvmField val Crop: ContentScale = object : ContentScale {}
        @JvmField val Fit: ContentScale = object : ContentScale {}
        @JvmField val FillHeight: ContentScale = object : ContentScale {}
        @JvmField val FillWidth: ContentScale = object : ContentScale {}
        @JvmField val FillBounds: ContentScale = object : ContentScale {}
        @JvmField val Inside: ContentScale = object : ContentScale {}
        @JvmField val None: ContentScale = object : ContentScale {}
    }
}

class LayoutCoordinates internal constructor() {
    val size: androidx.compose.ui.unit.IntSize = androidx.compose.ui.unit.IntSize(0, 0)
}

fun Modifier.onGloballyPositioned(onGloballyPositioned: (LayoutCoordinates) -> Unit): Modifier = this
fun Modifier.onSizeChanged(onSizeChanged: (androidx.compose.ui.unit.IntSize) -> Unit): Modifier = this
