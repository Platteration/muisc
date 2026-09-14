@file:Suppress("UNUSED_PARAMETER", "unused")

package android.app

import android.content.Context
import android.content.Intent

open class Application : Context() {
    open fun onCreate() {}
    open fun onLowMemory() {}
    open fun onTerminate() {}
}

open class ActivityManager {
    open val isLowRamDevice: Boolean get() = false
    open val memoryClass: Int get() = 256
    open val largeMemoryClass: Int get() = 512
}

open class Notification

open class NotificationChannel(id: String, name: CharSequence, importance: Int) {
    open var description: String? = null
    open fun setShowBadge(value: Boolean) {}
    open fun enableVibration(value: Boolean) {}
    open fun enableLights(value: Boolean) {}
    open fun setSound(sound: android.net.Uri?, attributes: android.media.AudioAttributes?) {}
}

open class NotificationManager {
    open fun createNotificationChannel(channel: NotificationChannel) {}
    open fun notify(id: Int, notification: Notification) {}
    open fun cancel(id: Int) {}

    companion object {
        const val IMPORTANCE_NONE = 0
        const val IMPORTANCE_MIN = 1
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_DEFAULT = 3
        const val IMPORTANCE_HIGH = 4
    }
}

class PendingIntent {
    companion object {
        const val FLAG_IMMUTABLE = 67108864
        const val FLAG_MUTABLE = 33554432
        const val FLAG_UPDATE_CURRENT = 134217728
        const val FLAG_CANCEL_CURRENT = 268435456
        @JvmStatic
        fun getActivity(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent = PendingIntent()
        @JvmStatic
        fun getService(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent = PendingIntent()
        @JvmStatic
        fun getBroadcast(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent = PendingIntent()
    }
}

open class Service : Context() {
    open fun onCreate() {}
    open fun onTrimMemory(level: Int) {}
    open fun onLowMemory() {}
    open fun onDestroy() {}
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = 0
    open fun onTaskRemoved(rootIntent: Intent?) {}
    fun stopSelf() {}
    fun startForeground(id: Int, notification: Notification) {}
    fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {}
    fun stopForeground(flags: Int) {}
}
