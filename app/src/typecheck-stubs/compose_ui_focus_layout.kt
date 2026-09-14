@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.focus

import androidx.compose.ui.Modifier

class FocusRequester {
    fun requestFocus() {}
    fun freeFocus(): Boolean = true

    companion object {
        @JvmField val Default: FocusRequester = FocusRequester()
    }
}

class FocusState internal constructor() {
    val isFocused: Boolean = false
    val hasFocus: Boolean = false
}

fun Modifier.focusRequester(focusRequester: FocusRequester): Modifier = this
fun Modifier.onFocusChanged(onFocusChanged: (FocusState) -> Unit): Modifier = this
fun Modifier.focusable(enabled: Boolean = true): Modifier = this
