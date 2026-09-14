@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.material.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * `androidx.compose.material.icons.Icons`. In the real artifact every icon is an extension property
 * on one of these objects, declared in the matching `...icons.<style>` package -- the layout is
 * mirrored here so the app's imports and `Icons.Rounded.X` spellings are checked.
 * The stub cannot verify that an icon of that name actually ships in material-icons-extended.
 */
object Icons {
    object Rounded
    object Filled
    object Outlined
    object Sharp
    object TwoTone
    val Default = Filled

    object AutoMirrored {
        object Rounded
        object Filled
        object Outlined
        val Default = Filled
    }
}
