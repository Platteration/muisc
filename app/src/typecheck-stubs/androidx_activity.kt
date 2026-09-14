@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.activity

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

open class ComponentActivity : Context(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
    open val application: Application get() = Application()
    open val onBackPressedDispatcher: OnBackPressedDispatcher get() = OnBackPressedDispatcher()
    open val intent: android.content.Intent get() = android.content.Intent()

    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onStart() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStop() {}
    open fun onDestroy() {}
    open fun onNewIntent(intent: android.content.Intent?) {}
    open fun finish() {}
}

class OnBackPressedDispatcher {
    fun onBackPressed() {}
}

class SystemBarStyle {
    companion object {
        fun auto(lightScrim: Int, darkScrim: Int): SystemBarStyle = SystemBarStyle()
        fun dark(scrim: Int): SystemBarStyle = SystemBarStyle()
        fun light(scrim: Int, darkScrim: Int): SystemBarStyle = SystemBarStyle()
    }
}

fun ComponentActivity.enableEdgeToEdge(
    statusBarStyle: SystemBarStyle = SystemBarStyle.auto(0, 0),
    navigationBarStyle: SystemBarStyle = SystemBarStyle.auto(0, 0),
) {
}
