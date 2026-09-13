package dev.muisc.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.Song
import dev.muisc.app.di.AppGraph
import dev.muisc.app.playback.EngineController
import dev.muisc.app.playback.PlayerState
import dev.muisc.app.playback.RepeatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Wraps [EngineController.state]. `AppGraph.engineController` is a process-stable delegating controller (the real
 * engine is installed into it by the playback service), so it is safe to hold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel : ViewModel() {

    private val controller: EngineController get() = AppGraph.engineController

    val state: StateFlow<PlayerState> get() = controller.state

    /** Analysis of the current song when it is already cached (never triggers a fresh analysis from the UI). */
    val currentAnalysis: StateFlow<TrackAnalysis?> = controller.state
        .map { it.current }
        .distinctUntilChanged { old, new -> old?.id == new?.id && old?.hasAnalysis == new?.hasAnalysis }
        .mapLatest { song -> loadCachedAnalysis(song) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun loadCachedAnalysis(song: Song?): TrackAnalysis? {
        if (song == null || !song.hasAnalysis) return null
        return withContext(Dispatchers.IO) {
            runCatching { AppGraph.lab.analysis(song) }.getOrNull()
        }
    }

    fun play() = controller.play()
    fun pause() = controller.pause()
    fun togglePlayPause() = controller.togglePlayPause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun toggleShuffle() = controller.setShuffle(!state.value.shuffle)

    fun cycleRepeat() {
        val next = when (state.value.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        controller.setRepeat(next)
    }

    fun skipToQueueItem(index: Int) = controller.skipToQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = controller.moveQueueItem(from, to)
    fun removeQueueItem(index: Int) = controller.removeQueueItem(index)
    fun clearQueue() = controller.clearQueue()
}
