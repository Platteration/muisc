package dev.muisc.app.data

import dev.muisc.app.data.db.AnalysisDao
import dev.muisc.app.data.db.SongDao
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps `Song.hasAnalysis` true to what the analysis cache can serve now, run once per process start, off the main
 * thread.
 *
 * Every cache lookup asks for `TrackAnalysis.CURRENT_VERSION` at the engine rate, but the flag is only ever set (by
 * the cache's `put`) and never cleared when the analyser version changes. After an update that raises the version,
 * an already analysed library would keep every flag at 1 while no lookup finds a row: the analysis worker (which
 * picks songs by the flag) would never re-analyse it and smart shuffle would order it without analyses. This
 * clears the flag of every song with no row at the current version and rate, so the worker picks them up again
 * ([onReanalysisNeeded] schedules it), and drops the rows of older versions, which no lookup reads any more.
 *
 * Idempotent and cheap on a library whose flags are already right (one indexed lookup per analysed song). A failure
 * is never thrown: startup goes on, and the flags are checked again at the next start.
 */
class AnalysisFlagUpkeep(
    private val deleteOlderThanVersion: suspend (version: Int) -> Unit,
    private val clearStaleFlags: suspend (version: Int, sampleRate: Int) -> Int,
    /** Called with the number of songs that need analysing again, only when there are any. */
    private val onReanalysisNeeded: suspend (songs: Int) -> Unit,
) {
    constructor(songDao: SongDao, analysisDao: AnalysisDao, onReanalysisNeeded: suspend (songs: Int) -> Unit) :
        this(analysisDao::deleteOlderThanVersion, songDao::clearStaleAnalysisFlags, onReanalysisNeeded)

    /** Returns how many songs were marked for analysis again (0 when nothing changed or the database failed). */
    suspend fun run(version: Int, sampleRate: Int): Int {
        try {
            deleteOlderThanVersion(version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Old rows only cost space; the flags still have to be put right.
        }
        val cleared = try {
            clearStaleFlags(version, sampleRate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return 0
        }
        if (cleared > 0) {
            try {
                onReanalysisNeeded(cleared)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The periodic worker picks the songs up on its next run anyway.
            }
        }
        return cleared
    }
}
