@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.core.app

import android.app.Notification
import android.content.Context

object NotificationCompat {
    const val PRIORITY_LOW = -1
    const val PRIORITY_DEFAULT = 0

    class Builder(context: Context, channelId: String) {
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setSmallIcon(icon: Int): Builder = this
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setPriority(priority: Int): Builder = this
        fun build(): Notification = Notification()
    }
}
