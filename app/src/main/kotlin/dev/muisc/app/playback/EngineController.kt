package dev.muisc.app.playback

import dev.muisc.app.data.db.Song
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.TransitionPrefs
import kotlinx.coroutines.flow.StateFlow

/** Repeat behaviour of the queue. */
enum class RepeatMode { OFF, ALL, ONE }

/** Planning/rendering state of the transition between queue item `i` and `i + 1`, shown as a badge in the queue. */
sealed interface EdgeState {
    /** Transitions are off for this pair; [reason] is user-facing ("Album playback", "Same album", "Transitions disabled"). */
    data class Gated(val reason: String) : EdgeState
    data object Analysing : EdgeState
    data class Planned(val strategyId: String, val displayName: String, val score: Double) : EdgeState
    data class Rendering(val strategyId: String, val progress: Float) : EdgeState
    data class Ready(val strategyId: String, val displayName: String) : EdgeState
    /** A live DJ move will be used instead of a render (skip, deadline miss). */
    data class Live(val kind: String, val reason: String) : EdgeState
    data class Failed(val reason: String) : EdgeState
    data object Unknown : EdgeState
}

/** Snapshot of everything the UI needs to draw player, mini-player, queue and notification. */
data class PlayerState(
    val isPlaying: Boolean = false,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val context: PlaybackContext = PlaybackContext.SINGLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Edge index → state; edge `i` is the transition from queue[i] to queue[i + 1]. */
    val edges: Map<Int, EdgeState> = emptyMap(),
    /** True while a transition segment is being played (the UI may show "mixing into …"). */
    val inTransition: Boolean = false,
    val transitionPrefs: TransitionPrefs = TransitionPrefs(),
    val error: String? = null,
) {
    val current: Song? get() = queue.getOrNull(currentIndex)
    val next: Song? get() = queue.getOrNull(currentIndex + 1)
}

/**
 * The single entry point the UI, the MediaSession adapter and the notification use to drive playback.
 * Implemented by the playback layer (owns the ProgramPlayer, the AudioTrack sink and the TransitionCoordinator);
 * every method is safe to call from the main thread and returns immediately.
 */
interface EngineController {
    val state: StateFlow<PlayerState>

    fun play()
    fun pause()
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)

    /** Replaces the queue. [context] decides whether transitions apply (ALBUM never transitions by default). */
    fun setQueue(songs: List<Song>, startIndex: Int, context: PlaybackContext, playNow: Boolean = true)
    fun playNext(songs: List<Song>)
    fun addToQueue(songs: List<Song>)
    fun moveQueueItem(from: Int, to: Int)
    fun removeQueueItem(index: Int)
    fun skipToQueueItem(index: Int)
    fun clearQueue()

    fun setShuffle(enabled: Boolean)
    fun setRepeat(mode: RepeatMode)
    fun updateTransitionPrefs(prefs: TransitionPrefs)

    /** Releases audio resources; the controller must not be used afterwards. */
    fun release()
}
