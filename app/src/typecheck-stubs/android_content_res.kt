@file:Suppress("UNUSED_PARAMETER", "unused")

package android.content.res

import java.io.Closeable

open class AssetFileDescriptor : Closeable {
    open val length: Long get() = -1L
    open val parcelFileDescriptor: android.os.ParcelFileDescriptor get() = android.os.ParcelFileDescriptor()
    override fun close() {}
}

open class Resources {
    open fun getString(id: Int): String = ""
    open fun getQuantityString(id: Int, quantity: Int, vararg args: Any?): String = ""
}

open class Configuration {
    @JvmField var uiMode: Int = 0
}
