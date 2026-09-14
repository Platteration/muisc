@file:Suppress("UNUSED_PARAMETER", "unused")

package android.os

import java.io.Closeable
import java.io.File
import java.io.FileDescriptor

object Build {
    object VERSION {
        @JvmField val SDK_INT: Int = 34
    }

    object VERSION_CODES {
        const val O = 26
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
    }
}

class Bundle {
    fun putBoolean(key: String, value: Boolean) {}
    fun putInt(key: String, value: Int) {}
    fun putLong(key: String, value: Long) {}
    fun putFloat(key: String, value: Float) {}
    fun putString(key: String, value: String?) {}
    fun putStringArray(key: String, value: Array<String>?) {}
    fun getBoolean(key: String, default: Boolean = false): Boolean = default
    fun getInt(key: String, default: Int = 0): Int = default
    fun getLong(key: String, default: Long = 0L): Long = default
    fun getFloat(key: String, default: Float = 0f): Float = default
    fun getString(key: String): String? = null

    companion object {
        @JvmField val EMPTY: Bundle = Bundle()
    }
}

open class Looper {
    companion object {
        @JvmStatic fun getMainLooper(): Looper = Looper()
        @JvmStatic fun myLooper(): Looper? = null
    }
}

open class Handler(looper: Looper) {
    open fun post(r: Runnable): Boolean = true
    open fun postDelayed(r: Runnable, delayMillis: Long): Boolean = true
    open fun removeCallbacksAndMessages(token: Any?) {}
}

open class CancellationSignal {
    open fun cancel() {}
    open val isCanceled: Boolean get() = false
}

class ParcelFileDescriptor : Closeable {
    val fileDescriptor: FileDescriptor = FileDescriptor()
    val statSize: Long = 0
    override fun close() {}
}

object SystemClock {
    @JvmStatic fun elapsedRealtime(): Long = 0
    @JvmStatic fun uptimeMillis(): Long = 0
}

object Process {
    const val THREAD_PRIORITY_URGENT_AUDIO = -19
    const val THREAD_PRIORITY_AUDIO = -16
    const val THREAD_PRIORITY_DEFAULT = 0
    const val THREAD_PRIORITY_BACKGROUND = 10
    const val THREAD_PRIORITY_LESS_FAVORABLE = 1

    @JvmStatic fun setThreadPriority(priority: Int) {}
}

open class PowerManager {
    open val isPowerSaveMode: Boolean get() = false
    open val isInteractive: Boolean get() = true
    open fun newWakeLock(levelAndFlags: Int, tag: String): WakeLock = WakeLock()

    open class WakeLock {
        open val isHeld: Boolean get() = false
        open fun acquire() {}
        open fun acquire(timeoutMs: Long) {}
        open fun release() {}
        open fun setReferenceCounted(value: Boolean) {}
    }

    companion object {
        const val PARTIAL_WAKE_LOCK = 1
        const val ACTION_POWER_SAVE_MODE_CHANGED = "android.os.action.POWER_SAVE_MODE_CHANGED"
    }
}

open class BatteryManager {
    open fun getIntProperty(id: Int): Int = 0

    companion object {
        const val EXTRA_LEVEL = "level"
        const val EXTRA_SCALE = "scale"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PLUGGED = "plugged"
        const val BATTERY_STATUS_UNKNOWN = 1
        const val BATTERY_STATUS_CHARGING = 2
        const val BATTERY_STATUS_DISCHARGING = 3
        const val BATTERY_STATUS_NOT_CHARGING = 4
        const val BATTERY_STATUS_FULL = 5
        const val BATTERY_PROPERTY_CAPACITY = 4
    }
}

object Environment {
    const val DIRECTORY_MUSIC = "Music"
    const val DIRECTORY_DOWNLOADS = "Download"

    @JvmStatic fun getExternalStoragePublicDirectory(type: String): File = File("/sdcard/$type")
    @JvmStatic fun getExternalStorageDirectory(): File = File("/sdcard")
}
