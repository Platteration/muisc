package dev.muisc.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Pauses playback when the audio output becomes "noisy" — headphones unplugged, Bluetooth disconnected
 * (`AudioManager.ACTION_AUDIO_BECOMING_NOISY`). Registered while playing and unregistered as soon as playback
 * stops, so the app holds no receiver in the background.
 *
 * [register] / [unregister] are idempotent and must be called from the main thread.
 */
class BecomingNoisyReceiver(
    context: Context,
    private val onNoisy: () -> Unit,
) {
    private val appContext = context.applicationContext
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
        }
    }

    fun register() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        if (!registered) return
        registered = false
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already gone: nothing to do.
        }
    }
}
