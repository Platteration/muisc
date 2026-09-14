@file:Suppress("UNUSED_PARAMETER", "unused")

package android.database

import android.net.Uri
import android.os.Handler
import java.io.Closeable

interface Cursor : Closeable {
    val count: Int
    val columnCount: Int
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun moveToPosition(position: Int): Boolean
    fun isAfterLast(): Boolean
    fun isNull(column: Int): Boolean
    fun getColumnIndex(name: String): Int
    fun getColumnIndexOrThrow(name: String): Int
    fun getColumnName(index: Int): String
    fun getLong(column: Int): Long
    fun getInt(column: Int): Int
    fun getFloat(column: Int): Float
    fun getDouble(column: Int): Double
    fun getString(column: Int): String?
    override fun close()
}

abstract class ContentObserver(handler: Handler?) {
    open fun onChange(selfChange: Boolean) {}
    open fun onChange(selfChange: Boolean, uri: Uri?) {}
    open fun deliverSelfNotifications(): Boolean = false
}
