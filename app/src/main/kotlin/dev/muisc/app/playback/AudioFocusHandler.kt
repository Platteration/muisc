package dev.muisc.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Audio focus for the playback service (DESIGN.md §8): one `AUDIOFOCUS_GAIN` request with `USAGE_MEDIA` /
 * `CONTENT_TYPE_MUSIC` attributes.
 *
 *  - `AUDIOFOCUS_LOSS` — pause and abandon focus (another app took over for good).
 *  - `AUDIOFOCUS_LOSS_TRANSIENT` — pause, keep the request, resume automatically on the following gain
 *    (only when we were actually playing, which [playing] tells us).
 *  - `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` — duck by [DUCK_DB] through [onDuck]; the engine ramps the master gain
 *    over 50 ms, so no pause is needed. `setWillPauseWhenDucked(false)` tells the system we duck ourselves.
 *  - `AUDIOFOCUS_GAIN` — un-duck and, if we paused transiently, resume.
 *
 * The listener is delivered on the main thread. Every callback must return immediately.
 */
class AudioFocusHandler(
    context: Context,
    /** Pause playback; [transient] is true when an automatic resume may follow. */
    private val onPause: (transient: Boolean) -> Unit,
    /** Resume after a transient loss. */
    private val onResume: () -> Unit,
    /** Attenuation in dB (0 = none, [DUCK_DB] while ducked). */
    private val onDuck: (Float) -> Unit = {},
) {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val handler = Handler(Looper.getMainLooper())

    /** Kept in sync by the owner: a transient loss only arms an auto-resume when playback was running. */
    @Volatile
    var playing: Boolean = false

    /** True while this app holds audio focus. */
    var hasFocus: Boolean = false
        private set

    /** True while a transient loss is in force and playback should resume when focus comes back. */
    private var resumeOnGain: Boolean = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnGain = false
                onDuck(0f)
                onPause(false)
                abandon()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = playing
                onPause(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onDuck(DUCK_DB)
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                onDuck(0f)
                if (resumeOnGain) {
                    resumeOnGain = false
                    onResume()
                }
            }
        }
    }

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(listener, handler)
        .build()

    /** Requests focus (idempotent while held). Returns false when the request was denied — do not start playing. */
    fun request(): Boolean {
        if (hasFocus) return true
        val manager = audioManager ?: return true // no AudioManager: do not block playback
        val result = try {
            manager.requestAudioFocus(request)
        } catch (e: Exception) {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    /** Gives focus back (on stop, on a permanent loss and when the service is destroyed). */
    fun abandon() {
        val manager = audioManager ?: return
        resumeOnGain = false
        if (!hasFocus) return
        hasFocus = false
        try { manager.abandonAudioFocusRequest(request) } catch (e: Exception) { }
    }

    companion object {
        /** Attenuation applied while another app has a duckable transient focus. */
        const val DUCK_DB = -12f
    }
}

/**
 * Implemented by the real `EngineController` so audio focus can duck the engine's master gain without widening the
 * frozen [EngineController] contract. Ducking is simply skipped when the controller does not implement it.
 */
interface DuckableEngine {
    /** Sets the master attenuation in dB (≤ 0; 0 = no attenuation). The engine ramps over ~50 ms. */
    fun setDuckDb(db: Float)
}
