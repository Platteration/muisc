@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.platform

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

private class DensityImpl : Density {
    override val density: Float = 1f
    override val fontScale: Float = 1f
}

val LocalContext: ProvidableCompositionLocal<Context> = staticCompositionLocalOf { Context() }
val LocalConfiguration: ProvidableCompositionLocal<Configuration> = staticCompositionLocalOf { Configuration() }
val LocalDensity: ProvidableCompositionLocal<Density> = staticCompositionLocalOf<Density> { DensityImpl() }
val LocalLayoutDirection: ProvidableCompositionLocal<LayoutDirection> = staticCompositionLocalOf { LayoutDirection.Ltr }
val LocalSoftwareKeyboardController: ProvidableCompositionLocal<SoftwareKeyboardController?> = staticCompositionLocalOf { null }
val LocalFocusManager: ProvidableCompositionLocal<FocusManager> = staticCompositionLocalOf<FocusManager> { object : FocusManager {} }
val LocalUriHandler: ProvidableCompositionLocal<UriHandler> = staticCompositionLocalOf<UriHandler> {
    object : UriHandler { override fun openUri(uri: String) {} }
}

interface SoftwareKeyboardController {
    fun show()
    fun hide()
}

interface FocusManager {
    fun clearFocus(force: Boolean = false) {}
}

interface UriHandler {
    fun openUri(uri: String)
}
