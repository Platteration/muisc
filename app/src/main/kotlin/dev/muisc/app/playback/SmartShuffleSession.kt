package dev.muisc.app.playback

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.Song
import dev.muisc.transitions.TransitionPrefs
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The smart-shuffle state of one controller: when to arrange which part of the queue, and when to stop.
 * [EngineControllerImpl] owns one and forwards the queue events to it; [SmartShuffle] does the ordering.
 *
 * - [start] (a smart-shuffled queue was built, or shuffle was turned on) arranges the queue from the song after the
 *   current one on. A pass arranges [SmartShuffle.horizon] slots off the main thread and swaps them in with
 *   `QueueManager.replaceAfter`, which refuses if the queue changed meanwhile.
 * - **The song after the current one is never moved.** The engine may already be mixing into it (a skip, or the
 *   transition at the end of the song) before the queue index moves at the handover, so a pass always arranges
 *   after it, and `replaceAfter` refuses (under the queue's lock) a swap that would move it.
 * - A pass refused because the listener moved on while it ran is re-anchored at the current position and tried
 *   once more, instead of leaving the rest of the queue in random order.
 * - [onTrackChanged] arranges the next stretch when the current song is [SmartShuffle.EXTEND_MARGIN] slots from the
 *   end of the arranged part, from wherever is later: the end of that part or the song after the current one (the
 *   listener may have jumped ahead).
 * - [end] (any queue edit the user made) stops arranging for good, even while a pass is in flight: the generation
 *   check and the swap happen under the same lock [end] takes, so a pass is either applied before the edit (and
 *   then ended by it) or dropped.
 *
 * Every pass reads the analyses as they are now ([analysisOf] by source), so songs analysed since the shuffle
 * started are placed with their analysis even though the queue holds the `Song` rows of the moment of the tap.
 */
class SmartShuffleSession(
    private val smartShuffle: SmartShuffle,
    private val snapshot: () -> QueueSnapshot,
    private val replaceAfter: (position: Int, anchorId: Long, expected: List<Song>, songs: List<Song>) -> Boolean,
    private val analysisOf: (Song) -> TrackAnalysis?,
    private val prefs: () -> TransitionPrefs,
    private val enabled: () -> Boolean,
    /** Called after a pass changed the queue (the controller re-installs it). Never called under the lock. */
    private val onArranged: () -> Unit,
    private val scope: CoroutineScope,
    private val passContext: CoroutineContext,
    private val seed: () -> Long = System::nanoTime,
) {
    private val lock = Any()

    /** Bumped by [start] and [end]; a pass started under an older value is dropped. Guarded by [lock]. */
    private var generation = 0

    /** True from [start] until [end]: this queue is being smart-shuffled. Guarded by [lock]. */
    private var active = false

    /** Queue position of the last song arranged; -1 before the first pass landed. Guarded by [lock]. */
    private var through = -1

    /** The pass in flight, if any. Guarded by [lock]. */
    private var job: Job? = null

    /** Queue position of the last song arranged; -1 when nothing is (or no longer) arranged. */
    val arrangedThrough: Int get() = synchronized(lock) { if (active) through else -1 }

    /** A smart-shuffled queue starts (or shuffle was turned on): arrange what comes after the next song. */
    fun start() {
        val previous = synchronized(lock) {
            generation++
            active = true
            through = -1
            job.also { job = null }
        }
        previous?.cancel()
        launchPass()
    }

    /** The user changed the queue: stop arranging it (the order they now have stays as it is). */
    fun end() {
        val previous = synchronized(lock) {
            generation++
            active = false
            through = -1
            job.also { job = null }
        }
        previous?.cancel()
    }

    /** The queue index moved to the song now playing (after the controller updated it). */
    fun onTrackChanged() {
        if (!enabled()) return
        val snap = snapshot()
        synchronized(lock) {
            if (!active || job?.isActive == true) return
            if (through >= 0 && snap.index < through - SmartShuffle.EXTEND_MARGIN) return
            if (maxOf(through, snap.index + 1) >= snap.songs.size - 2) return
        }
        launchPass()
    }

    private fun launchPass() {
        val pass = synchronized(lock) {
            if (!active || job?.isActive == true) return
            val passGeneration = generation
            scope.launch(passContext, start = CoroutineStart.LAZY) { runPass(passGeneration) }.also { job = it }
        }
        pass.start()
    }

    /** One pass, re-anchored and tried once more if the listener moved on while it ran. Never throws. */
    private suspend fun runPass(passGeneration: Int) {
        repeat(ATTEMPTS) {
            coroutineContext.ensureActive()
            val snap = snapshot()
            val from = synchronized(lock) {
                if (passGeneration != generation) return
                maxOf(through, snap.index + 1)
            }
            val anchor = snap.songs.getOrNull(from) ?: return
            val tail = snap.songs.subList(from + 1, snap.songs.size).toList()
            if (tail.size < 2) return
            val arranged = try {
                smartShuffle.arrange(anchor, tail, seed(), prefs()) { song -> analysisOf(song) }
            } catch (e: Exception) {
                return // a failure leaves the random order in place: it never stops playback
            }
            val applied = synchronized(lock) {
                if (passGeneration != generation) return
                replaceAfter(from, anchor.id, tail, arranged).also { ok ->
                    if (ok) through = from + minOf(smartShuffle.horizon, tail.size)
                }
            }
            if (applied) {
                onArranged()
                return
            }
        }
    }

    companion object {
        /** A pass and its one re-anchored retry. */
        const val ATTEMPTS = 2
    }
}
