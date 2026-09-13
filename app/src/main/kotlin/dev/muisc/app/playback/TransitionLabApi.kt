package dev.muisc.app.playback

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.Song
import dev.muisc.transitions.Params
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionPrefs
import kotlinx.coroutines.flow.Flow

/** Progress of a Lab render 0..1. */
data class LabProgress(val stage: String, val fraction: Float)

/**
 * What the Transition Lab screen needs from the playback layer: analyse two songs, rank strategies, render one with
 * edited parameters, audition the result through the real engine, and export it. All functions suspend and run off
 * the main thread; results are plain engine objects so the same code path is used by the CLI.
 */
interface TransitionLabApi {
    suspend fun analysis(song: Song, progress: ((LabProgress) -> Unit)? = null): TrackAnalysis
    suspend fun plan(a: Song, b: Song, prefs: TransitionPrefs, seed: Long = 0L): RankedPlans
    suspend fun render(a: Song, b: Song, strategyId: String, params: Params, modifiers: List<String>, prefs: TransitionPrefs, seed: Long = 0L, progress: ((LabProgress) -> Unit)? = null): RenderedTransition

    /** Plays A's tail (contextSec) + the rendered segment + B's head through the normal engine; emits position 0..1. */
    fun audition(a: Song, b: Song, rendered: RenderedTransition, contextSec: Double = 15.0): Flow<Float>
    fun stopAudition()

    /** Writes WAV + plan JSON + metrics JSON to the public Music/Muisc/Renders folder; returns the base path. */
    suspend fun export(a: Song, b: Song, rendered: RenderedTransition): String

    /** Thumbs up/down nudges the strategy weight in prefs by ±0.1 and records it in the transition log. */
    suspend fun rate(a: Song, b: Song, strategyId: String, thumbsUp: Boolean)

    /** Pins a strategy (+ params) for this specific pair; null clears the pin. */
    suspend fun pinForPair(a: Song, b: Song, strategyId: String?, params: Params?)
}
