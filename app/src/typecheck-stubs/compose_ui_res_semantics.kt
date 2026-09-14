@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
@ReadOnlyComposable
fun stringResource(id: Int): String = ""

@Composable
@ReadOnlyComposable
fun stringResource(id: Int, vararg formatArgs: Any): String = ""

@Composable
@ReadOnlyComposable
fun pluralStringResource(id: Int, count: Int): String = ""

@Composable
@ReadOnlyComposable
fun pluralStringResource(id: Int, count: Int, vararg formatArgs: Any): String = ""

@Composable
fun painterResource(id: Int): Painter = Painter()

@Composable
fun vectorResource(id: Int): ImageVector = ImageVector("stub")
