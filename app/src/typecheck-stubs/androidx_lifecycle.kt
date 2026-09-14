@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

abstract class ViewModel {
    protected open fun onCleared() {}
    fun addCloseable(closeable: AutoCloseable) {}
}

val ViewModel.viewModelScope: CoroutineScope
    get() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

class ViewModelStore {
    fun clear() {}
}

interface ViewModelStoreOwner {
    val viewModelStore: ViewModelStore
}

interface CreationExtras

class ViewModelProvider(owner: ViewModelStoreOwner, factory: Factory) {
    operator fun <T : ViewModel> get(modelClass: Class<T>): T = throw UnsupportedOperationException("typecheck stub")

    interface Factory {
        fun <T : ViewModel> create(modelClass: Class<T>): T =
            throw UnsupportedOperationException("typecheck stub")

        fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create(modelClass)
    }

    companion object {
        fun create(owner: ViewModelStoreOwner, factory: Factory): ViewModelProvider = ViewModelProvider(owner, factory)
    }
}

open class LifecycleService : android.app.Service()

interface LifecycleOwner
