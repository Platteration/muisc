@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable

fun ComponentActivity.setContent(content: @Composable () -> Unit) {}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I> = object : ActivityResultLauncher<I>() {
    override fun launch(input: I) {}
}
