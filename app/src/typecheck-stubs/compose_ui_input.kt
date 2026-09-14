@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.input.pointer

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

class PointerInputChange internal constructor() {
    val position: Offset = Offset.Zero
    val previousPosition: Offset = Offset.Zero
    val pressed: Boolean = false
    fun consume() {}
}

interface AwaitPointerEventScope : Density {
    val size: IntSize
}

interface PointerInputScope : Density {
    val size: IntSize
    suspend fun <R> awaitPointerEventScope(block: suspend AwaitPointerEventScope.() -> R): R
}

fun Modifier.pointerInput(key1: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this
fun Modifier.pointerInput(key1: Any?, key2: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this
fun Modifier.pointerInput(vararg keys: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this
