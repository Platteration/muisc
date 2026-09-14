package dev.muisc.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.muisc.player.PowerMode

/**
 * Feeds `TransitionCoordinator.setPowerMode` (DESIGN §7.4 "Power"):
 *
 *  - [PowerMode.STRICT_SAVER] — the user's own "no transitions on battery" toggle ([setStrictSaver]).
 *  - [PowerMode.SAVER] — the system power-save mode is on, or the battery is below [SAVER_BATTERY_PERCENT] and the
 *    device is not charging. The coordinator then restricts the ladder to live-capable strategies.
 *  - [PowerMode.NORMAL] — everything else.
 *
 * Battery level is read from the sticky `ACTION_BATTERY_CHANGED` broadcast on demand (no continuous registration:
 * that broadcast fires constantly and would cost more battery than it saves). Changes arrive through the cheap
 * edge broadcasts — power-save toggled, battery low/okay, charger connected/disconnected.
 */
class PowerModeMonitor(
    context: Context,
    private val onMode: (PowerMode) -> Unit,
) {
    private val appContext: Context = context.applicationContext
    private val powerManager: PowerManager? = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    private var strictSaver: Boolean = false

    @Volatile
    var mode: PowerMode = PowerMode.NORMAL
        private set

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    /** Registers for power/battery changes and publishes the current mode immediately. */
    fun start() {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            try {
                ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            } catch (e: RuntimeException) {
                // Without the receiver the mode is still evaluated on every refresh() call.
            }
        }
        refresh(force = true)
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    /** The user toggle "disable transitions to save battery". */
    fun setStrictSaver(enabled: Boolean) {
        if (strictSaver == enabled) return
        strictSaver = enabled
        refresh(force = true)
    }

    /** Re-evaluates the mode and notifies when it changed (or when [force]). */
    fun refresh(force: Boolean = false) {
        val next = evaluate()
        if (next != mode || force) {
            mode = next
            onMode(next)
        }
    }

    private fun evaluate(): PowerMode {
        if (strictSaver) return PowerMode.STRICT_SAVER
        val saving = try {
            powerManager?.isPowerSaveMode == true
        } catch (e: RuntimeException) {
            false
        }
        if (saving) return PowerMode.SAVER
        val (percent, charging) = batteryState()
        if (!charging && percent in 0..SAVER_BATTERY_PERCENT) return PowerMode.SAVER
        return PowerMode.NORMAL
    }

    /** (level 0..100, charging); (-1, false) when the sticky broadcast is unavailable. */
    private fun batteryState(): Pair<Int, Boolean> {
        val intent = try {
            ContextCompat.registerReceiver(appContext, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: RuntimeException) {
            null
        } ?: return -1 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return percent to charging
    }

    companion object {
        /** DESIGN §7.4: below 15 % the ladder is restricted to live-capable strategies. */
        const val SAVER_BATTERY_PERCENT = 15
    }
}
