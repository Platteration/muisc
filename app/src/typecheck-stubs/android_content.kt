@file:Suppress("UNUSED_PARAMETER", "unused")

package android.content

import android.content.res.AssetFileDescriptor
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream
import java.io.OutputStream

open class Context {
    open val applicationContext: Context get() = this
    open val contentResolver: ContentResolver get() = ContentResolver()
    open val packageManager: android.content.pm.PackageManager get() = android.content.pm.PackageManager()
    open val packageName: String get() = "dev.muisc.app"
    open val cacheDir: File get() = File("/data/cache")
    open val filesDir: File get() = File("/data/files")
    open fun getSystemService(name: String): Any? = null
    open fun getExternalFilesDir(type: String?): File? = null
    open fun startService(intent: Intent): ComponentName? = null
    open fun startActivity(intent: Intent) {}
    open fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? = null
    open fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = null
    open fun unregisterReceiver(receiver: BroadcastReceiver) {}
    open fun getString(resId: Int): String = ""
    open fun getString(resId: Int, vararg args: Any?): String = ""
    open fun checkSelfPermission(permission: String): Int = 0

    companion object {
        const val POWER_SERVICE = "power"
        const val AUDIO_SERVICE = "audio"
        const val ACTIVITY_SERVICE = "activity"
        const val NOTIFICATION_SERVICE = "notification"
        const val BATTERY_SERVICE = "batterymanager"
    }
}

open class ContentResolver {
    open fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    open fun query(
        uri: Uri,
        projection: Array<String>?,
        queryArgs: Bundle?,
        cancellationSignal: android.os.CancellationSignal?,
    ): Cursor? = null

    open fun openInputStream(uri: Uri): InputStream? = null
    open fun openOutputStream(uri: Uri): OutputStream? = null
    open fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor? = null
    open fun openAssetFileDescriptor(uri: Uri, mode: String): AssetFileDescriptor? = null
    open fun loadThumbnail(uri: Uri, size: android.util.Size, signal: android.os.CancellationSignal?): android.graphics.Bitmap =
        android.graphics.Bitmap()
    open fun getType(uri: Uri): String? = null
    open fun insert(uri: Uri, values: ContentValues?): Uri? = null
    open fun update(uri: Uri, values: ContentValues?, where: String?, args: Array<String>?): Int = 0
    open fun delete(uri: Uri, where: String?, args: Array<String>?): Int = 0
    open fun registerContentObserver(uri: Uri, notifyForDescendants: Boolean, observer: ContentObserver) {}
    open fun unregisterContentObserver(observer: ContentObserver) {}
}

open class ContentValues {
    open fun put(key: String, value: String?) {}
    open fun put(key: String, value: Int?) {}
    open fun put(key: String, value: Long?) {}
    open fun clear() {}
}

object ContentUris {
    @JvmStatic fun withAppendedId(contentUri: Uri, id: Long): Uri = contentUri
    @JvmStatic fun parseId(contentUri: Uri): Long = 0L
}

open class ComponentName(packageName: String, className: String) {
    constructor(context: Context, className: String) : this(context.packageName, className)
    constructor(context: Context, cls: Class<*>) : this(context.packageName, cls.name)
}

open class Intent() {
    constructor(action: String) : this()
    constructor(context: Context, cls: Class<*>) : this()

    open val action: String? = null
    open fun setClass(context: Context, cls: Class<*>): Intent = this
    open fun setClassName(context: Context, className: String): Intent = this
    open fun setPackage(packageName: String?): Intent = this
    open fun setAction(action: String?): Intent = this
    open fun setType(type: String?): Intent = this
    open fun addFlags(flags: Int): Intent = this
    open fun setFlags(flags: Int): Intent = this
    open fun putExtra(name: String, value: String?): Intent = this
    open fun putExtra(name: String, value: Long): Intent = this
    open fun putExtra(name: String, value: Int): Intent = this
    open fun putExtra(name: String, value: Boolean): Intent = this
    open fun getIntExtra(name: String, default: Int): Int = default
    open fun getStringExtra(name: String): String? = null

    companion object {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED"
        const val ACTION_BATTERY_LOW = "android.intent.action.BATTERY_LOW"
        const val ACTION_BATTERY_OKAY = "android.intent.action.BATTERY_OKAY"
        const val ACTION_POWER_CONNECTED = "android.intent.action.ACTION_POWER_CONNECTED"
        const val ACTION_POWER_DISCONNECTED = "android.intent.action.ACTION_POWER_DISCONNECTED"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
        const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
        @JvmStatic fun createChooser(target: Intent, title: CharSequence?): Intent = target
    }
}

open class IntentFilter() {
    constructor(action: String) : this()

    open fun addAction(action: String) {}
}

abstract class BroadcastReceiver {
    abstract fun onReceive(context: Context?, intent: Intent?)
}
