@file:Suppress("unused", "ClassName")

package android

/** Framework resources actually referenced by the app. */
object R {
    object drawable {
        const val stat_notify_sync: Int = 17301640
    }
}

/** Permission name constants (`android.Manifest.permission.*`). */
object Manifest {
    object permission {
        const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
        const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
        const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
        const val WAKE_LOCK = "android.permission.WAKE_LOCK"
    }
}
