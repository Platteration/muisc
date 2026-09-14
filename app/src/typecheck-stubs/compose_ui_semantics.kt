@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.semantics

import androidx.compose.ui.Modifier

@JvmInline
value class Role private constructor(val value: Int) {
    companion object {
        val Button = Role(0)
        val Checkbox = Role(1)
        val Switch = Role(2)
        val RadioButton = Role(3)
        val Tab = Role(4)
        val Image = Role(5)
        val DropdownList = Role(6)
    }
}

interface SemanticsPropertyReceiver {
    var contentDescription: String
    var stateDescription: String
}

fun Modifier.semantics(mergeDescendants: Boolean = false, properties: SemanticsPropertyReceiver.() -> Unit): Modifier = this
fun Modifier.clearAndSetSemantics(properties: SemanticsPropertyReceiver.() -> Unit): Modifier = this
