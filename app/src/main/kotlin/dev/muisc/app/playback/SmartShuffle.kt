package dev.muisc.app.playback

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.Song
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.sequence.SequenceItem
import dev.muisc.transitions.sequence.SequenceOptions
import dev.muisc.transitions.sequence.SetSequencer

/**
 * Smart shuffle on the phone: orders the songs after an anchor (the song playing, or the last song already
 * arranged) with the engine's [SetSequencer] in its online mode, so each song mixes well into the next.
 *
 * Only the first [horizon] slots after the anchor are chosen by cost; the rest keep their random order and are
 * arranged later, as the queue reaches them ([EngineControllerImpl] calls this again from the last arranged song),
 * so a song analysed in the meantime is then placed with its analysis. [analysisOf] is called only for songs with
 * `hasAnalysis` set, at most once per song, and only for the songs the sequencer samples (about
 * `poolSize + horizon` of them), so a 10,000-song shuffle reads a few hundred analyses, not the whole library.
 * A song without an analysis is placed without cost information (see [SetSequencer]). Pure: no Android, no I/O
 * beyond what [analysisOf] does.
 */
class SmartShuffle(
    private val sequencer: SetSequencer = SetSequencer(),
    val horizon: Int = HORIZON,
    val poolSize: Int = SequenceOptions.DEFAULT_POOL_SIZE,
) {
    /** [upcoming] reordered to follow [anchor]; always a permutation of [upcoming]. */
    fun arrange(anchor: Song, upcoming: List<Song>, seed: Long, prefs: TransitionPrefs, analysisOf: (Song) -> TrackAnalysis?): List<Song> {
        if (upcoming.size < 2) return upcoming
        val all = ArrayList<Song>(upcoming.size + 1).apply { add(anchor); addAll(upcoming) }
        val options = SequenceOptions(seed = seed, first = 0, prefs = prefs, poolSize = poolSize, horizon = horizon)
        val result = sequencer.online(all.size, options) { i ->
            val song = all[i]
            val analysis = if (song.hasAnalysis) {
                try {
                    analysisOf(song)
                } catch (e: Exception) {
                    null // an unreadable analysis is the same as none: the song is still placed
                }
            } else {
                null
            }
            SequenceItem(song.id.toString(), analysis, song.artist)
        }
        return result.order.drop(1).map { all[it] }
    }

    companion object {
        /** Slots arranged per pass (about five hours of music). */
        const val HORIZON = 64

        /** The next pass starts when the current song is this many slots from the end of the arranged part. */
        const val EXTEND_MARGIN = 16
    }
}
