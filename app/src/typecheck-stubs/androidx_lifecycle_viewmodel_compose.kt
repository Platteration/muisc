@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.lifecycle.viewmodel.compose

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner

@Composable
inline fun <reified VM : ViewModel> viewModel(
    viewModelStoreOwner: ViewModelStoreOwner? = null,
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
): VM = factory?.create(VM::class.java) ?: throw UnsupportedOperationException("typecheck stub")

object LocalViewModelStoreOwner {
    val current: ViewModelStoreOwner? @Composable get() = null
}
