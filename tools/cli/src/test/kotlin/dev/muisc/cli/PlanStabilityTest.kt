package dev.muisc.cli

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Same pair, same plan": the planner's ranking must depend on the audio, not on incidental file metadata. Copying a
 * library, restoring a backup or touching a file changes its modification time (and so its cache fingerprint) but not
 * its music, and must not reshuffle the user's transitions or orphan their pins.
 */
class PlanStabilityTest {
    @TempDir
    lateinit var root: File

    private fun ranking(a: File, b: File, cache: File): List<String> =
        Cli.run(cache, "plan", a.absolutePath, b.absolutePath)
            .substringAfter("candidates (").lines().drop(2)
            .takeWhile { it.isNotBlank() }
            .map { it.trim().split(Regex("\\s+")).take(3).joinToString(" ") } // rank, strategy, score

    @Test
    fun `touching or copying a file does not change the plan`() {
        val cache = File(root, "cache")
        val songs = Cli.songs(File(root, "songs"), cache)
        val before = ranking(songs.a, songs.b, cache)
        check(before.size >= 3) { "expected a ranking, got $before" }

        // Same bytes, different modification times: the cache fingerprints change, the music does not.
        val copyDir = File(root, "copies").apply { mkdirs() }
        val a2 = songs.a.copyTo(File(copyDir, songs.a.name)).apply { setLastModified(946_684_800_000L) }  // 2000-01-01
        val b2 = songs.b.copyTo(File(copyDir, songs.b.name)).apply { setLastModified(1_436_961_600_000L) } // 2015-07-15
        assertEquals(before, ranking(a2, b2, cache), "a copy with other modification times was planned differently")

        songs.a.setLastModified(1_672_574_400_000L) // 2023-01-01
        assertEquals(before, ranking(songs.a, songs.b, cache), "touching A changed the plan")
    }
}
