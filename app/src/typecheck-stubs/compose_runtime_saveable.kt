@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.runtime.saveable

import androidx.compose.runtime.Composable

interface SaverScope {
    fun canBeSaved(value: Any): Boolean
}

interface Saver<Original, Saveable : Any> {
    fun SaverScope.save(value: Original): Saveable?
    fun restore(value: Saveable): Original?
}

fun <Original, Saveable : Any> listSaver(
    save: SaverScope.(value: Original) -> List<Saveable>,
    restore: (list: List<Saveable>) -> Original?,
): Saver<Original, Any> = throw UnsupportedOperationException("typecheck stub")

fun <T> autoSaver(): Saver<T, Any> = throw UnsupportedOperationException("typecheck stub")

@Composable
fun <T : Any> rememberSaveable(vararg inputs: Any?, key: String? = null, init: () -> T): T = init()

@Composable
fun <T> rememberSaveable(
    vararg inputs: Any?,
    saver: Saver<T, out Any>,
    key: String? = null,
    init: () -> T,
): T = init()
