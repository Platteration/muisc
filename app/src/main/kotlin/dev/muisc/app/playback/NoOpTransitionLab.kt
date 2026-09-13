package dev.muisc.app.playback

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.Song
import dev.muisc.transitions.Params
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * [TransitionLabApi] used before the playback service has installed the real one. Engine-backed operations fail
 * with [IllegalStateException] carrying a user-facing message (the Lab shows it as an error card); audition
 * emits nothing; ratings and pins are dropped. `AppGraph` wraps it in a delegating lab that waits for the real
 * implementation, so this is only ever hit when the service cannot start at all.
 */
object NoOpTransitionLab : TransitionLabApi {
    const val NOT_CONNECTED = "Playback engine is not connected yet"

    override suspend fun analysis(song: Song, progress: ((LabProgress) -> Unit)?): TrackAnalysis =
        throw IllegalStateException(NOT_CONNECTED)

    override suspend fun plan(a: Song, b: Song, prefs: TransitionPrefs, seed: Long): RankedPlans =
        throw IllegalStateException(NOT_CONNECTED)

    override suspend fun render(
        a: Song, b: Song, strategyId: String, params: Params, modifiers: List<String>, prefs: TransitionPrefs,
        seed: Long, progress: ((LabProgress) -> Unit)?,
    ): RenderedTransition = throw IllegalStateException(NOT_CONNECTED)

    override fun audition(a: Song, b: Song, rendered: RenderedTransition, contextSec: Double): Flow<Float> = emptyFlow()

    override fun stopAudition() {}

    override suspend fun export(a: Song, b: Song, rendered: RenderedTransition): String =
        throw IllegalStateException(NOT_CONNECTED)

    override suspend fun rate(a: Song, b: Song, strategyId: String, thumbsUp: Boolean) {}

    override suspend fun pinForPair(a: Song, b: Song, strategyId: String?, params: Params?) {}
}
