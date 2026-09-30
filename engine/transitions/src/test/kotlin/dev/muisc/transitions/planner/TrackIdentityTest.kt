package dev.muisc.transitions.planner

import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.custom.InMemoryPinStore
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.custom.PlannerCustomization
import dev.muisc.transitions.synthetic.SyntheticTracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The planner follows the MUSIC, not the file: two analyses of the same audio that differ only in their cache
 * fingerprint (another modification time, another path) must be planned identically, and a pin made for one must
 * apply to the other.
 */
class TrackIdentityTest {
    private val prefs = TransitionPrefs()
    private val a = SyntheticTracks.trackRef(SyntheticSong(bpm = 120.0, tonic = 0, bars = 16, introBars = 4, outroBars = 4), prefs = prefs).trackRef
    private val b = SyntheticTracks.trackRef(SyntheticSong(bpm = 126.0, tonic = 9, mode = dev.muisc.audio.synth.Mode.MINOR, bars = 16, introBars = 4, outroBars = 4), prefs = prefs).trackRef

    private fun sameMusicOtherFile(t: dev.muisc.transitions.TrackRef, content: String) =
        t.copy(analysis = t.analysis.copy(fingerprint = t.analysis.fingerprint + ":touched", contentHash = content))

    @Test
    fun `the ranking depends on the content hash, not the fingerprint`() {
        val planner = DefaultTransitionPlanner(DefaultStrategyRegistry.default())
        val a1 = sameMusicOtherFile(a, "content-a"); val b1 = sameMusicOtherFile(b, "content-b")
        val a2 = a1.copy(analysis = a1.analysis.copy(fingerprint = "another-mtime")); val b2 = b1.copy(analysis = b1.analysis.copy(fingerprint = "and-another"))
        val r1 = planner.plan(a1, b1, prefs, 0L).candidates.map { it.strategy.id to it.score }
        val r2 = planner.plan(a2, b2, prefs, 0L).candidates.map { it.strategy.id to it.score }
        assertEquals(r1, r2)
        // Without a content hash the fingerprint is the identity (analyses cached before the field existed).
        assertEquals("fp", a.analysis.copy(fingerprint = "fp", contentHash = "").identity)
        assertNotEquals(
            DefaultTransitionPlanner.jitter(a1.analysis.identity, b1.analysis.identity, 0, "bassSwap"),
            DefaultTransitionPlanner.jitter(a1.analysis.fingerprint, b1.analysis.fingerprint, 0, "bassSwap"),
        )
    }

    @Test
    fun `a pin made for one copy of the music applies to another`() {
        val a1 = sameMusicOtherFile(a, "content-a"); val b1 = sameMusicOtherFile(b, "content-b")
        val pins = InMemoryPinStore().apply { set(PairPin(a1.analysis.identity, b1.analysis.identity, "crossfade")) }
        val planner = DefaultTransitionPlanner(DefaultStrategyRegistry.default(), customization = PlannerCustomization(pins = pins))
        val copy = a1.copy(analysis = a1.analysis.copy(fingerprint = "copied-elsewhere"))
        assertEquals("crossfade", planner.plan(copy, b1, prefs, 0L).best.strategy.id)
    }
}
