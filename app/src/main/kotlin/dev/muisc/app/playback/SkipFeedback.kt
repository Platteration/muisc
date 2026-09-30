package dev.muisc.app.playback

import dev.muisc.player.TransitionSkip
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Learn from skips": turns the skips the [dev.muisc.player.TransitionCoordinator] attributes to a rendered
 * transition ([TransitionSkip]; the coordinator already leaves out album playback, live fallbacks, skips later in B
 * and repeats) into implicit feedback in the DJ's learned preferences, through the same locked store path as a
 * thumbs up/down ([DjCustomization.recordSkip]).
 *
 * [enabled] is the Settings toggle, read when the skip is recorded (off = nothing is written). The file is written
 * on [io], never on the coordinator's thread. Pure JVM, so it is unit-tested on the desktop.
 */
class SkipFeedback(
    private val dj: DjCustomization,
    private val scope: CoroutineScope,
    private val enabled: suspend () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Called after a skip was saved (the DJ screens re-read the learned preferences). */
    @Volatile
    var onRecorded: (() -> Unit)? = null

    /** The coordinator's `onTransitionSkipped`. Returns at once. */
    fun onTransitionSkipped(skip: TransitionSkip) {
        scope.launch(io) {
            val on = try {
                enabled()
            } catch (t: Throwable) {
                false // settings unreadable: record nothing rather than guess
            }
            if (!on) return@launch
            if (dj.recordSkip(skip.strategyId, skip.features) != null) onRecorded?.invoke()
        }
    }
}
