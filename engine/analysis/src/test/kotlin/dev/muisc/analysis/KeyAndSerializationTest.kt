package dev.muisc.analysis

import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Camelot
import dev.muisc.analysis.model.Cues
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.LoudnessInfo
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.Section
import dev.muisc.analysis.model.SectionLabel
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.analysis.model.TrackAnalysis
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KeyAndSerializationTest {
    @Test
    fun camelotWheel() {
        assertEquals("8B", MusicalKey(0, Mode.MAJOR).camelot.code)   // C major
        assertEquals("8A", MusicalKey(9, Mode.MINOR).camelot.code)   // A minor
        assertEquals("9B", MusicalKey(7, Mode.MAJOR).camelot.code)   // G major
        assertEquals("1A", MusicalKey(8, Mode.MINOR).camelot.code)   // G# minor
        assertEquals("12A", MusicalKey(1, Mode.MINOR).camelot.code)  // C# minor (Db minor)
        val c = MusicalKey(0, Mode.MAJOR).camelot
        assertEquals(0, c.distanceTo(Camelot.parse("8B")))
        assertEquals(1, c.distanceTo(Camelot.parse("8A")))
        assertEquals(1, c.distanceTo(Camelot.parse("9B")))
        assertEquals(1, c.distanceTo(Camelot.parse("7B")))
        assertEquals(2, c.distanceTo(Camelot.parse("9A")))
        assertEquals(6, c.distanceTo(Camelot.parse("2B")))
        assertEquals(1, Camelot.parse("12B").distanceTo(Camelot.parse("1B")))
        assertEquals(MusicalKey(10, Mode.MINOR), MusicalKey.parse("Bbm"))
        assertEquals(MusicalKey(6, Mode.MAJOR), MusicalKey.parse("F# major"))
    }

    @Test
    fun analysisJsonRoundTrip() {
        val sr = 44100
        val grid = BeatGrid.rigid(124.0, sr, 2000, 30L * sr, downbeatPhase = 2, confidence = 0.8f, phraseStartBeat = 2)
        val a = TrackAnalysis(
            sourceId = "/music/x.flac", fingerprint = "abc", sampleRate = sr, totalFrames = 30L * sr,
            trimStartFrame = 100, trimEndFrame = 30L * sr - 500,
            tempo = TempoEstimate(124.0, 0.9f), grid = grid,
            key = KeyEstimate(MusicalKey(9, Mode.MINOR), 0.7f, MusicalKey(0, Mode.MAJOR), 0.6f, FloatArray(12) { it / 12f }),
            loudness = LoudnessInfo(-12.5f, -0.3f, floatArrayOf(-20f, -15f), 0.1f, 6f),
            bars = BarFeatures(energy = floatArrayOf(0.5f, 1f), sub = floatArrayOf(0.1f, 0.2f), bass = floatArrayOf(0.2f, 0.3f), mid = floatArrayOf(0.5f, 0.4f), high = floatArrayOf(0.2f, 0.1f), percussiveness = floatArrayOf(0.5f, 0.6f), vocalActivity = floatArrayOf(0f, 0.5f)),
            ltasDb = FloatArray(31) { -it.toFloat() }, brightnessHz = 2500f,
            sections = listOf(Section(0, 32, SectionLabel.INTRO, 0.3f), Section(32, 64, SectionLabel.DROP, 1f, 1f, 0.2f)),
            intro = IntroType.BEAT_INTRO, outro = OutroType.FADE_OUT, cues = Cues(mixOutBeat = 40, mixInBeat = 8),
            extra = mapOf("foo" to 1.5),
        )
        val json = a.toJson()
        val back = TrackAnalysis.fromJson(json)
        assertEquals(a, back)
        assertEquals(a.grid, back.grid)
        assertContentEquals(a.grid.beatFrames, back.grid.beatFrames)
        assertEquals(a.key.key, back.key.key)
        assertEquals(a.sections, back.sections)
        assertEquals(a.cues, back.cues)
        assertEquals(a.loudness.integratedLufs, back.loudness.integratedLufs)
        assertEquals(1.5, back.extra["foo"])
        assertContentEquals(a.ltasDb, back.ltasDb)
    }
}
