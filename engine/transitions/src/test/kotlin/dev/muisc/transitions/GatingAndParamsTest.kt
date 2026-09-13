package dev.muisc.transitions

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.LoudnessInfo
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioSourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatingAndParamsTest {
    private fun track(id: String, album: String?): TrackRef {
        val a = TrackAnalysis(sourceId = id, fingerprint = id, sampleRate = 44100, totalFrames = 44100L * 60, trimStartFrame = 0, trimEndFrame = 44100L * 60,
            tempo = TempoEstimate(120.0, 0.9f), grid = BeatGrid.EMPTY, key = KeyEstimate(MusicalKey(0, Mode.MAJOR), 0.5f), loudness = LoudnessInfo(-14f, -1f))
        return TrackRef(id, AudioSourceId(id), a, albumId = album)
    }

    @Test
    fun albumGating() {
        val prefs = TransitionPrefs()
        val a1 = track("a1", "album1"); val a2 = track("a2", "album1"); val b1 = track("b1", "album2")
        assertFalse(TransitionGating.transitionsEnabled(PlaybackContext.ALBUM, a1, a2, prefs))
        assertTrue(TransitionGating.transitionsEnabled(PlaybackContext.ALBUM, a1, b1, prefs))
        assertTrue(TransitionGating.transitionsEnabled(PlaybackContext.ALBUM, a1, a2, prefs.copy(allowInAlbums = true)))
        assertTrue(TransitionGating.transitionsEnabled(PlaybackContext.PLAYLIST, a1, a2, prefs))
        assertTrue(TransitionGating.transitionsEnabled(PlaybackContext.SHUFFLE, a1, a2, prefs))
        assertFalse(TransitionGating.transitionsEnabled(PlaybackContext.SHUFFLE, a1, a2, prefs.copy(keepAlbumFlowInShuffle = true)))
        assertTrue(TransitionGating.transitionsEnabled(PlaybackContext.SHUFFLE, a1, b1, prefs.copy(keepAlbumFlowInShuffle = true)))
        assertFalse(TransitionGating.transitionsEnabled(PlaybackContext.SINGLE, a1, b1, prefs))
        assertFalse(TransitionGating.transitionsEnabled(PlaybackContext.PLAYLIST, a1, b1, prefs.copy(enabled = false)))
    }

    @Test
    fun paramsParseAndClamp() {
        val d = ParamSpec.DoubleSpec("overlapBars", "Overlap", 16.0, 1.0, 64.0, "bars")
        val i = ParamSpec.IntSpec("loops", "Loops", 4, 1, 8)
        val b = ParamSpec.BoolSpec("keyLock", "Key lock", true)
        val c = ParamSpec.ChoiceSpec("curve", "Curve", "equalPower", listOf("equalPower", "linear"))
        val p = Params.parse(listOf("overlapBars=200", "loops=2.6", "keyLock=off", "curve=bogus"))
        assertEquals(64.0, p.double(d)); assertEquals(3, p.int(i)); assertFalse(p.bool(b)); assertEquals("equalPower", p.choice(c))
        val defaults = Params.defaults(listOf(d, i, b, c))
        assertEquals("16.0", defaults["overlapBars"]); assertEquals("equalPower", defaults["curve"])
        val resolved = p.resolve(listOf(d, i, b, c))
        assertEquals(setOf("overlapBars", "loops", "keyLock", "curve"), resolved.values.keys)
    }

    @Test
    fun planContractIsValidated() {
        val plan = TransitionPlan("x", Params.EMPTY, aExitFrame = 100, bEntryFrame = 50, aWindow = FrameRange(0, 200), bWindow = FrameRange(0, 60), expectedOutputFrames = 100)
        assertEquals(100, plan.aExitOffset); assertEquals(50, plan.bEntryOffset)
        assertFalse(runCatching { TransitionPlan("x", Params.EMPTY, 300, 50, FrameRange(0, 200), FrameRange(0, 60), 1) }.isSuccess)
        assertFalse(runCatching { TransitionPlan("x", Params.EMPTY, 100, 80, FrameRange(0, 200), FrameRange(0, 60), 1) }.isSuccess)
    }
}
