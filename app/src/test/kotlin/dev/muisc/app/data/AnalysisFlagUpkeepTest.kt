package dev.muisc.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The startup upkeep that makes a new analyser version re-analyse an already analysed library. Room does not run in
 * this harness, so the two tables are modelled in memory with the semantics of the statements in `Daos.kt`
 * (`AnalysisDao.deleteOlderThanVersion`, `SongDao.clearStaleAnalysisFlags`, and `SongDao.unanalysed`, the worker's
 * pick); the SQL itself is only checked by the first real build (see app/src/typecheck-stubs/README.md).
 */
class AnalysisFlagUpkeepTest {

    private data class SongRow(val id: Long, val uri: String, val path: String, var hasAnalysis: Boolean)
    private data class AnalysisRow(val sourceId: String, val sampleRate: Int, val version: Int)

    private class Library {
        val songs = ArrayList<SongRow>()
        val analyses = ArrayList<AnalysisRow>()
        val requests = ArrayList<Int>()
        var failDelete = false
        var failClear = false

        fun song(id: Long, analysedAt: Int?, rate: Int = RATE, byPath: Boolean = false) {
            val row = SongRow(id, "content://media/external/audio/media/$id", "/music/$id.mp3", analysedAt != null)
            songs += row
            if (analysedAt != null) analyses += AnalysisRow(if (byPath) row.path else row.uri, rate, analysedAt)
        }

        /** `SongDao.unanalysed`: what the worker analyses next. */
        fun unanalysed() = songs.filter { !it.hasAnalysis }.map { it.id }

        val upkeep = AnalysisFlagUpkeep(
            deleteOlderThanVersion = { version ->
                if (failDelete) error("disk I/O error")
                analyses.removeAll { it.version < version }
            },
            clearStaleFlags = { version, rate ->
                if (failClear) error("database is locked")
                val stale = songs.filter { s ->
                    s.hasAnalysis && analyses.none { a -> a.version == version && a.sampleRate == rate && (a.sourceId == s.uri || a.sourceId == s.path) }
                }
                stale.forEach { it.hasAnalysis = false }
                stale.size
            },
            onReanalysisNeeded = { n -> requests += n },
        )
    }

    @Test
    fun anUpgradedLibraryIsAnalysedAgain() = runTest {
        val lib = Library()
        lib.song(1, analysedAt = 2)
        lib.song(2, analysedAt = 2)
        lib.song(3, analysedAt = 2)
        lib.analyses += AnalysisRow(lib.songs[2].uri, RATE, 3) // played since the update: analysed at v3 already
        lib.song(4, analysedAt = 2, byPath = true)
        lib.song(5, analysedAt = null) // never analysed
        lib.song(6, analysedAt = 3, rate = 44_100) // analysed at another engine rate: no lookup at this rate finds it
        assertEquals(listOf(5L), lib.unanalysed(), "before: the worker sees only the never-analysed song")

        val cleared = lib.upkeep.run(version = 3, sampleRate = RATE)

        assertEquals(4, cleared)
        assertEquals(listOf(1L, 2L, 4L, 5L, 6L), lib.unanalysed(), "the worker re-analyses every song without a current row")
        assertTrue(lib.songs.first { it.id == 3L }.hasAnalysis, "a song with a current row keeps its flag")
        assertTrue(lib.analyses.none { it.version < 3 }, "rows of older versions are dropped")
        assertEquals(listOf(4), lib.requests, "the worker is scheduled once")
    }

    @Test
    fun aLibraryWhoseFlagsAreRightIsLeftAlone() = runTest {
        val lib = Library()
        lib.song(1, analysedAt = 3)
        lib.song(2, analysedAt = 3, byPath = true)
        lib.song(3, analysedAt = null)
        assertEquals(0, lib.upkeep.run(version = 3, sampleRate = RATE))
        assertEquals(listOf(3L), lib.unanalysed())
        assertTrue(lib.requests.isEmpty(), "no extra worker run")
        // And a second start after an upgrade changes nothing more.
        val upgraded = Library()
        upgraded.song(1, analysedAt = 2)
        assertEquals(1, upgraded.upkeep.run(3, RATE))
        assertEquals(0, upgraded.upkeep.run(3, RATE))
        assertEquals(listOf(1), upgraded.requests)
    }

    @Test
    fun aDatabaseFailureNeverStopsStartup() = runTest {
        val lib = Library()
        lib.song(1, analysedAt = 2)
        lib.failDelete = true
        assertEquals(1, lib.upkeep.run(3, RATE), "the flags are still put right when old rows cannot be dropped")
        assertEquals(listOf(1L), lib.unanalysed())
        val broken = Library()
        broken.song(1, analysedAt = 2)
        broken.failClear = true
        assertEquals(0, broken.upkeep.run(3, RATE))
        assertTrue(broken.requests.isEmpty())
    }

    private companion object {
        const val RATE = 48_000
    }
}
