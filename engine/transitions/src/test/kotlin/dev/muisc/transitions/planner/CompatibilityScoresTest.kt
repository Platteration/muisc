package dev.muisc.transitions.planner

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.dsp.stems.StemQuality
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TempoRelation
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompatibilityScoresTest {
    private val prefs = TransitionPrefs(maxStretchPercent = 8.0, preferredOverlapBars = 16)
    private val base = PairFeatures(
        tempoRatio = 1.0, tempoRelation = TempoRelation.SAME, stretchPercent = 0.0, camelotDistance = 0, bestPitchShiftSemitones = 0,
        camelotDistanceAfterShift = 0, loudnessDeltaLu = 0.0, energyDelta = 0.0, vocalClash = 0.0, spectralSimilarity = 1.0,
        outro = OutroType.BEAT_OUTRO, intro = IntroType.BEAT_INTRO, outroBeatsAvailable = 64, introBeatsAvailable = 64,
        gridConfidenceA = 1.0, gridConfidenceB = 1.0, keyStrengthA = 1.0, keyStrengthB = 1.0, lowEndShareA = 0.3, lowEndShareB = 0.3,
    )

    @Test
    fun tempoFollowsGaussianOfStretch() {
        assertEquals(1.0, CompatibilityScores.tempo(base, prefs), 1e-12)
        val sigma = 8.0 / 1.5
        for (s in doubleArrayOf(1.0, 4.0, 8.0, 16.0)) {
            assertEquals(exp(-(s / sigma) * (s / sigma)), CompatibilityScores.tempo(base.copy(stretchPercent = s), prefs), 1e-12, "stretch $s")
        }
        assertEquals(0.85, CompatibilityScores.tempo(base.copy(tempoRelation = TempoRelation.HALF), prefs), 1e-12)
        assertEquals(0.85, CompatibilityScores.tempo(base.copy(tempoRelation = TempoRelation.DOUBLE), prefs), 1e-12)
        // No stretch allowed: only an exact match scores.
        val strict = prefs.copy(maxStretchPercent = 0.0)
        assertEquals(1.0, CompatibilityScores.tempo(base, strict), 1e-12)
        assertEquals(0.0, CompatibilityScores.tempo(base.copy(stretchPercent = 0.1), strict), 1e-12)
        // Monotone in stretch.
        var prev = 1.0
        for (s in 0..40) { val v = CompatibilityScores.tempo(base.copy(stretchPercent = s.toDouble()), prefs); assertTrue(v <= prev + 1e-12); prev = v }
    }

    @Test
    fun keyTableAndStrengthBlend() {
        val expected = doubleArrayOf(1.0, 0.85, 0.5, 0.35, 0.1, 0.1, 0.1)
        for (d in 0..6) assertEquals(expected[d], CompatibilityScores.key(base.copy(camelotDistanceAfterShift = d)), 1e-12, "distance $d")
        // Zero strength → neutral 0.6 whatever the distance; half strength → halfway.
        assertEquals(0.6, CompatibilityScores.key(base.copy(camelotDistanceAfterShift = 5, keyStrengthA = 0.0)), 1e-12)
        assertEquals(0.6, CompatibilityScores.key(base.copy(camelotDistanceAfterShift = 0, keyStrengthB = 0.0)), 1e-12)
        assertEquals(0.5 * 0.1 + 0.5 * 0.6, CompatibilityScores.key(base.copy(camelotDistanceAfterShift = 4, keyStrengthA = 0.5, keyStrengthB = 0.9)), 1e-12)
        // The weaker of the two strengths counts.
        assertEquals(CompatibilityScores.key(base.copy(camelotDistanceAfterShift = 2, keyStrengthA = 0.3, keyStrengthB = 1.0)),
            CompatibilityScores.key(base.copy(camelotDistanceAfterShift = 2, keyStrengthA = 1.0, keyStrengthB = 0.3)), 1e-12)
    }

    @Test
    fun energyVocalGridRoomStems() {
        assertEquals(1.0, CompatibilityScores.energy(base), 1e-12)
        assertEquals(exp(-1.0), CompatibilityScores.energy(base.copy(loudnessDeltaLu = 6.0)), 1e-12)
        assertEquals(exp(-1.0), CompatibilityScores.energy(base.copy(loudnessDeltaLu = -6.0)), 1e-12)
        assertEquals(0.5, CompatibilityScores.energy(base.copy(energyDelta = 1.0)), 1e-12)
        assertEquals(0.75, CompatibilityScores.energy(base.copy(energyDelta = -0.5)), 1e-12)
        assertEquals(0.5 * exp(-0.25), CompatibilityScores.energy(base.copy(energyDelta = 1.0, loudnessDeltaLu = 3.0)), 1e-12)

        assertEquals(1.0, CompatibilityScores.vocal(base), 1e-12)
        assertEquals(0.3, CompatibilityScores.vocal(base.copy(vocalClash = 0.7)), 1e-12)

        assertEquals(0.4, CompatibilityScores.grid(base.copy(gridConfidenceA = 0.9, gridConfidenceB = 0.4)), 1e-12)
        assertEquals(0.4, CompatibilityScores.grid(base.copy(gridConfidenceA = 0.4, gridConfidenceB = 0.9)), 1e-12)

        assertEquals(1.0, CompatibilityScores.room(base, 64), 1e-12)
        assertEquals(0.5, CompatibilityScores.room(base.copy(outroBeatsAvailable = 32), 64), 1e-12)
        assertEquals(0.25, CompatibilityScores.room(base.copy(outroBeatsAvailable = 32, introBeatsAvailable = 32), 64), 1e-12)
        assertEquals(1.0, CompatibilityScores.room(base.copy(outroBeatsAvailable = 0), 0), 1e-12, "nothing needed → 1")
        assertEquals(0.0, CompatibilityScores.room(base.copy(outroBeatsAvailable = 0), 8), 1e-12)

        assertEquals(1.0, CompatibilityScores.stems(StemQuality.ML), 1e-12)
        assertEquals(0.8, CompatibilityScores.stems(StemQuality.PSEUDO), 1e-12)
        assertEquals(0.0, CompatibilityScores.stems(null), 1e-12)
    }

    @Test
    fun structTablesAnchorValuesAndFamilies() {
        assertEquals(1.0, StructTables.prior("beatMatchedBlend", OutroType.BEAT_OUTRO, IntroType.BEAT_INTRO), 1e-12)
        for (o in OutroType.entries) assertEquals(1.0, StructTables.prior("phraseCut", o, IntroType.COLD_START), 1e-12, "phraseCut[$o][COLD_START]")
        assertEquals(1.0, StructTables.prior("outroIntroMinimal", OutroType.FADE_OUT, IntroType.AMBIENT_INTRO), 1e-12)
        assertEquals(0.9, StructTables.prior("ambientBridge", OutroType.HARD_STOP, IntroType.AMBIENT_INTRO), 1e-12)
        // Unknown ids: flat generic prior.
        for (o in OutroType.entries) for (i in IntroType.entries) assertEquals(StructTables.GENERIC_PRIOR, StructTables.prior("myExperimentalThing", o, i), 1e-12)
        assertEquals(StrategyFamily.GENERIC, StructTables.family("myExperimentalThing"))
        // Every one of the 14 shipped ids has a family, and families partition the ids.
        val ids = listOf("crossfade", "outroIntroMinimal", "phraseCut", "beatMatchedBlend", "bassSwap", "stemSwap", "drumBreakBridge", "filterSweep", "echoOut", "loopRollRiser", "harmonicBlend", "spectralFreezeBridge", "ambientBridge", "brakeStop")
        for (id in ids) assertTrue(StructTables.family(id) != StrategyFamily.GENERIC, id)
        assertEquals(ids.size, (StructTables.BEAT_DOMAIN_IDS + StructTables.CUT_IDS + StructTables.STRUCTURAL_IDS + StructTables.BRIDGE_IDS + StructTables.CROSSFADE_IDS).size)
        // Beat-domain priors prefer a beat outro into a beat intro over anything into silence.
        val t = StructTables.table(StrategyFamily.BEAT_DOMAIN)
        for (o in OutroType.entries) for (i in IntroType.entries) assertTrue(t[o.ordinal][i.ordinal] <= 1.0 && t[o.ordinal][i.ordinal] >= 0.0)
        assertTrue(t[OutroType.BEAT_OUTRO.ordinal][IntroType.BEAT_INTRO.ordinal] > t[OutroType.HARD_STOP.ordinal][IntroType.SILENCE.ordinal])
        // A structural strategy likes fade-out → ambient far more than beat → cold.
        assertTrue(StructTables.prior("outroIntroMinimal", OutroType.FADE_OUT, IntroType.AMBIENT_INTRO) > 2 * StructTables.prior("outroIntroMinimal", OutroType.BEAT_OUTRO, IntroType.COLD_START))
        // The returned table is a copy.
        t[0][0] = -1.0
        assertEquals(0.5, StructTables.table(StrategyFamily.BEAT_DOMAIN)[0][0], 1e-12)
    }

    @Test
    fun computeAssemblesSubScoresAndNeededBeats() {
        val a = TestAnalyses.simple("a")
        assertEquals(64, CompatibilityScores.neededBeats("beatMatchedBlend", a, prefs))
        assertEquals(32, CompatibilityScores.neededBeats("phraseCut", a, prefs))
        assertEquals(16, CompatibilityScores.neededBeats("outroIntroMinimal", a, prefs))
        assertEquals(8, CompatibilityScores.neededBeats("ambientBridge", a, prefs))
        assertEquals(8, CompatibilityScores.neededBeats("crossfade", null, prefs))
        assertEquals(32, CompatibilityScores.neededBeats("whatever", null, prefs))

        val f = base.copy(stretchPercent = 4.0, camelotDistanceAfterShift = 1, loudnessDeltaLu = 3.0, vocalClash = 0.2, gridConfidenceB = 0.8, outroBeatsAvailable = 32)
        val s = CompatibilityScores.compute("stemSwap", f, prefs, a, StemQuality.PSEUDO)
        assertEquals(CompatibilityScores.tempo(f, prefs), s.tempo, 1e-12)
        assertEquals(0.85, s.key, 1e-12)
        assertEquals(exp(-0.25), s.energy, 1e-12)
        assertEquals(0.8, s.vocal, 1e-12)
        assertEquals(0.8, s.grid, 1e-12)
        assertEquals(StructTables.prior("stemSwap", OutroType.BEAT_OUTRO, IntroType.BEAT_INTRO), s.struct, 1e-12)
        assertEquals(0.5, s.room, 1e-12)
        assertEquals(0.8, s.stems, 1e-12)
        assertEquals(1.0, CompatibilityScores.compute("crossfade", f, prefs, a, null).stems, 1e-12, "non-stem strategies ignore stem quality")
        assertEquals(0.0, CompatibilityScores.compute("drumBreakBridge", f, prefs, a, null).stems, 1e-12)
        // weighted and summary
        assertEquals(0.5 * s.tempo + 0.5 * s.key, s.weighted(wTempo = 0.5, wKey = 0.5), 1e-12)
        assertTrue(s.summary().startsWith("tempo ") && s.summary().contains(" · key 0.85 · ") && s.summary().endsWith("stems 0.80"), s.summary())
        assertEquals(listOf("tempo", "key", "energy", "vocal", "grid", "struct", "room", "stems"), s.asMap().keys.toList())
    }
}
