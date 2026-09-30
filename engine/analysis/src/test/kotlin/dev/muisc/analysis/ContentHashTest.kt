package dev.muisc.analysis

import dev.muisc.audio.synth.SyntheticSong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** [dev.muisc.analysis.model.TrackAnalysis.contentHash] follows the decoded audio, not the file it came from. */
class ContentHashTest {
    private val analyzer = DefaultTrackAnalyzer(AnalysisOptions(computeStructure = false, computeTexture = false))

    @Test
    fun `the same audio under another name and fingerprint hashes the same, other audio does not`() {
        val song = SyntheticSong(bpm = 120.0, bars = 8, introBars = 2, outroBars = 2).render()
        val first = analyzer.analyze(song, "/music/a.flac", "fp-1:javasound")
        val copy = analyzer.analyze(song.copy(), "/backup/a copy.flac", "fp-2:javasound")
        assertTrue(first.contentHash.isNotEmpty())
        assertEquals(first.contentHash, copy.contentHash)
        assertEquals(first.identity, copy.identity)
        assertNotEquals(first.fingerprint, copy.fingerprint)

        val other = analyzer.analyze(SyntheticSong(bpm = 121.0, bars = 8, introBars = 2, outroBars = 2).render(), "/music/b.flac", "fp-3:javasound")
        assertNotEquals(first.contentHash, other.contentHash)
    }
}
