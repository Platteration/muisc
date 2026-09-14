@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.core.content

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

object ContextCompat {
    const val RECEIVER_NOT_EXPORTED = 4
    const val RECEIVER_EXPORTED = 2

    @JvmStatic
    fun registerReceiver(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = null

    @JvmStatic
    fun checkSelfPermission(context: Context, permission: String): Int = 0

    @JvmStatic
    fun getMainExecutor(context: Context): java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }

    @JvmStatic
    fun getSystemService(context: Context, serviceClass: Class<*>): Any? = null

    @JvmStatic
    fun startForegroundService(context: Context, intent: Intent) {}
}
