package dev.muisc.app.playback

import dev.muisc.app.data.db.Song
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.TransitionPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [EngineController] used before the playback service has installed the real one (see `AppGraph.installPlayback`).
 * Every command is a no-op; [state] is a constant idle [PlayerState] so screens render without null checks.
 * `AppGraph` wraps it in a delegating controller that buffers commands until the real controller arrives, so UI
 * code never needs to know this class exists.
 */
class NoOpEngineController : EngineController {
    private val idle = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> get() = idle

    override fun play() {}
    override fun pause() {}
    override fun togglePlayPause() {}
    override fun next() {}
    override fun previous() {}
    override fun seekTo(positionMs: Long) {}
    override fun setQueue(songs: List<Song>, startIndex: Int, context: PlaybackContext, playNow: Boolean) {}
    override fun playNext(songs: List<Song>) {}
    override fun addToQueue(songs: List<Song>) {}
    override fun moveQueueItem(from: Int, to: Int) {}
    override fun removeQueueItem(index: Int) {}
    override fun skipToQueueItem(index: Int) {}
    override fun clearQueue() {}
    override fun setShuffle(enabled: Boolean) {}
    override fun setRepeat(mode: RepeatMode) {}
    override fun updateTransitionPrefs(prefs: TransitionPrefs) {}
    override fun release() {}
}
