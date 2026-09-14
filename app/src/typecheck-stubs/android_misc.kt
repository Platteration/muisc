@file:Suppress("UNUSED_PARAMETER", "unused")

package android.content.pm

class PackageManager {
    fun getLaunchIntentForPackage(pkg: String): android.content.Intent? = null

    companion object {
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
    }
}
