package dev.muisc.transitions

import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
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
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.transitions.synthetic.SyntheticTracks
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultPairAnalyzerTest {
    private val analyzer = DefaultPairAnalyzer()
    private val prefs = TransitionPrefs()

    // ------------------------------------------------------------------------------------------ helpers

    /** Hand-built analysis: [bars] bars of 4 beats at [bpm], every field explicit so expectations are exact. */
    private fun analysis(
        bpm: Double = 120.0,
        key: MusicalKey = MusicalKey(0, Mode.MAJOR),
        keyStrength: Float = 0.8f,
        bars: Int = 32,
        energy: FloatArray = FloatArray(bars) { 0.5f },
        vocal: FloatArray = FloatArray(bars),
        sub: FloatArray = FloatArray(bars) { 0.1f },
        bass: FloatArray = FloatArray(bars) { 0.2f },
        cues: Cues = Cues(mixOutBeat = (bars - 8) * 4, mixInBeat = 16, firstDownbeat = 0, lastDownbeat = (bars - 1) * 4),
        lufs: Float = -14f,
        ltas: FloatArray = FloatArray(0),
        introLtas: FloatArray = FloatArray(0),
        outroLtas: FloatArray = FloatArray(0),
        gridConfidence: Float = 0.9f,
        sections: List<Section> = emptyList(),
        outroKey: KeyEstimate? = null,
        introKey: KeyEstimate? = null,
        grid: BeatGrid? = null,
    ): TrackAnalysis {
        val sr = 44100
        val beats = bars * 4
        val g = grid ?: BeatGrid.rigid(bpm, sr, 0, Math.round((beats - 0.5) * 60.0 * sr / bpm), confidence = gridConfidence, phraseStartBeat = 0)
        return TrackAnalysis(
            sourceId = "t", fingerprint = "f", sampleRate = sr, totalFrames = Math.round(beats * 60.0 * sr / bpm),
            trimStartFrame = 0, trimEndFrame = Math.round(beats * 60.0 * sr / bpm),
            tempo = TempoEstimate(bpm, 0.9f), grid = g, key = KeyEstimate(key, keyStrength), loudness = LoudnessInfo(lufs, -1f),
            bars = BarFeatures(energy, sub, bass, FloatArray(bars) { 0.5f }, FloatArray(bars) { 0.2f }, FloatArray(bars) { 0.5f }, vocal),
            ltasDb = ltas, introLtasDb = introLtas, outroLtasDb = outroLtas, cues = cues, sections = sections,
            intro = IntroType.BEAT_INTRO, outro = OutroType.BEAT_OUTRO, outroKey = outroKey, introKey = introKey,
        )
    }

    private fun synth(bpm: Double = 120.0, tonic: Int = 0, mode: dev.muisc.audio.synth.Mode = dev.muisc.audio.synth.Mode.MAJOR, bars: Int = 32, introBars: Int = 4, outroBars: Int = 4): TrackAnalysis =
        SyntheticTracks.analysis(SyntheticSong(bpm = bpm, tonic = tonic, mode = mode, bars = bars, introBars = introBars, outroBars = outroBars))

    private fun allKeys(): List<MusicalKey> = (0 until 12).flatMap { t -> listOf(MusicalKey(t, Mode.MAJOR), MusicalKey(t, Mode.MINOR)) }

    /** Brute-force reference: min distance over [-s, s], ties → smallest |shift|, then the positive shift. */
    private fun bruteForceShift(a: MusicalKey, b: MusicalKey, s: Int): Pair<Int, Int> {
        val base = a.camelot.distanceTo(b.camelot)
        if (base <= 1) return 0 to base
        var best = 0; var bestD = base
        val order = (0..s).flatMap { m -> if (m == 0) listOf(0) else listOf(m, -m) }
        for (shift in order) {
            val d = a.camelot.distanceTo(MusicalKey(Math.floorMod(b.tonic + shift, 12), b.mode).camelot)
            if (d < bestD) { bestD = d; best = shift }
        }
        return best to bestD
    }

    // ------------------------------------------------------------------------------------------ tempo

    @Test
    fun sameRelationWithFivePercentStretch() {
        val f = analyzer.features(synth(120.0), synth(126.0), prefs)
        assertEquals(TempoRelation.SAME, f.tempoRelation)
        assertEquals(1.05, f.tempoRatio, 1e-9)
        assertEquals(5.0, f.stretchPercent, 0.01)
        assertTrue(f.beatMatchable)
        assertEquals(0.95, f.gridConfidenceA, 1e-6)
        assertEquals(0.95, f.gridConfidenceB, 1e-6)
    }

    @Test
    fun halfAndDoubleTimeRelations() {
        val slow = analysis(bpm = 70.0)
        val fast = analysis(bpm = 140.0)
        val up = analyzer.features(slow, fast, prefs)
        assertEquals(TempoRelation.HALF, up.tempoRelation)
        assertEquals(1.0, up.tempoRatio, 1e-9)
        assertEquals(0.0, up.stretchPercent, 1e-9)
        val down = analyzer.features(fast, slow, prefs)
        assertEquals(TempoRelation.DOUBLE, down.tempoRelation)
        assertEquals(1.0, down.tempoRatio, 1e-9)

        // 128 -> 68: 68/128 = 0.53 -> double time 1.0625 (6.25 %) beats 0.53 (47 %).
        val f = analyzer.features(analysis(bpm = 128.0), analysis(bpm = 68.0), prefs)
        assertEquals(TempoRelation.DOUBLE, f.tempoRelation)
        assertEquals(6.25, f.stretchPercent, 1e-9)
    }

    @Test
    fun relationChoiceIsSymmetric() {
        for ((a, b) in listOf(100.0 to 140.0, 140.0 to 100.0, 90.0 to 170.0, 120.0 to 121.0, 60.0 to 200.0)) {
            val ab = analyzer.features(analysis(bpm = a), analysis(bpm = b), prefs)
            val ba = analyzer.features(analysis(bpm = b), analysis(bpm = a), prefs)
            assertEquals(1.0, ab.tempoRatio * ba.tempoRatio, 1e-9, "$a -> $b")
            val mirrored = when (ab.tempoRelation) { TempoRelation.SAME -> TempoRelation.SAME; TempoRelation.HALF -> TempoRelation.DOUBLE; TempoRelation.DOUBLE -> TempoRelation.HALF }
            assertEquals(mirrored, ba.tempoRelation, "$a -> $b")
        }
    }

    // ------------------------------------------------------------------------------------------ key

    @Test
    fun relativeKeysAreCompatibleWithoutShift() {
        val f = analyzer.features(synth(tonic = 0, mode = dev.muisc.audio.synth.Mode.MAJOR), synth(tonic = 9, mode = dev.muisc.audio.synth.Mode.MINOR), prefs)
        assertEquals(1, f.camelotDistance)
        assertEquals(0, f.bestPitchShiftSemitones)
        assertEquals(1, f.camelotDistanceAfterShift)
        assertEquals(0.9, f.keyStrengthA, 1e-6)
        assertEquals(0.9, f.keyStrengthB, 1e-6)
        val same = analyzer.features(synth(tonic = 0), synth(tonic = 0), prefs)
        assertEquals(0, same.camelotDistance)
        assertEquals(0, same.bestPitchShiftSemitones)
    }

    @Test
    fun tritoneNeedsAPitchShift() {
        val c = MusicalKey(0, Mode.MAJOR); val fSharp = MusicalKey(6, Mode.MAJOR)
        val f1 = analyzer.features(analysis(key = c), analysis(key = fSharp), prefs) // maxPitchShiftSemitones = 1
        assertEquals(6, f1.camelotDistance)
        val (expShift1, expDist1) = bruteForceShift(c, fSharp, 1)
        assertEquals(expShift1, f1.bestPitchShiftSemitones)
        assertEquals(expDist1, f1.camelotDistanceAfterShift)
        assertEquals(1, abs(f1.bestPitchShiftSemitones))
        assertEquals(1, f1.camelotDistanceAfterShift)

        val f6 = analyzer.features(analysis(key = c), analysis(key = fSharp), prefs.copy(maxPitchShiftSemitones = 6.0))
        val (expShift6, expDist6) = bruteForceShift(c, fSharp, 6)
        assertEquals(expShift6, f6.bestPitchShiftSemitones)
        assertEquals(expDist6, f6.camelotDistanceAfterShift)
        assertEquals(0, f6.camelotDistanceAfterShift)
    }

    @Test
    fun pitchShiftMatchesBruteForceForEveryKeyPair() {
        for (s in listOf(1, 2, 6)) {
            val p = prefs.copy(maxPitchShiftSemitones = s.toDouble())
            for (a in allKeys()) for (b in allKeys()) {
                val f = analyzer.features(analysis(key = a), analysis(key = b), p)
                val (expShift, expDist) = bruteForceShift(a, b, s)
                assertEquals(a.camelot.distanceTo(b.camelot), f.camelotDistance, "$a -> $b")
                assertEquals(expShift, f.bestPitchShiftSemitones, "$a -> $b, S=$s")
                assertEquals(expDist, f.camelotDistanceAfterShift, "$a -> $b, S=$s")
                assertTrue(abs(f.bestPitchShiftSemitones) <= s)
                assertTrue(f.camelotDistanceAfterShift <= f.camelotDistance)
                if (f.camelotDistance <= 1) assertEquals(0, f.bestPitchShiftSemitones)
            }
        }
    }

    @Test
    fun sectionKeysTakePrecedence() {
        val a = analysis(key = MusicalKey(0, Mode.MAJOR), outroKey = KeyEstimate(MusicalKey(7, Mode.MAJOR), 0.6f))
        val b = analysis(key = MusicalKey(6, Mode.MAJOR), introKey = KeyEstimate(MusicalKey(7, Mode.MAJOR), 0.7f))
        val f = analyzer.features(a, b, prefs)
        assertEquals(0, f.camelotDistance)
        assertEquals(0.6, f.keyStrengthA, 1e-6)
        assertEquals(0.7, f.keyStrengthB, 1e-6)
    }

    // ------------------------------------------------------------------------------------------ level / energy

    @Test
    fun loudnessDeltaIsBMinusA() {
        assertEquals(4.0, analyzer.features(analysis(lufs = -14f), analysis(lufs = -10f), prefs).loudnessDeltaLu, 1e-6)
        assertEquals(-6.0, analyzer.features(analysis(lufs = -8f), analysis(lufs = -14f), prefs).loudnessDeltaLu, 1e-6)
        assertEquals(0.0, analyzer.features(analysis(lufs = -120f), analysis(lufs = -14f), prefs).loudnessDeltaLu, 1e-6)
    }

    @Test
    fun energyDeltaUsesTailBeforeMixOutAndHeadFromMixIn() {
        // A: 32 bars, mixOutBeat = 96 (bar 24) -> tail = bars 16..23 (energy 0.2); everything else 1.0.
        val aEnergy = FloatArray(32) { 1f }
        for (bar in 16 until 24) aEnergy[bar] = 0.2f
        val a = analysis(energy = aEnergy, cues = Cues(mixOutBeat = 96, mixInBeat = 0, firstDownbeat = 0, lastDownbeat = 124))
        // B: mixInBeat = 16 (bar 4) -> head = bars 4..11 (energy 0.9); everything else 0.1.
        val bEnergy = FloatArray(32) { 0.1f }
        for (bar in 4 until 12) bEnergy[bar] = 0.9f
        val b = analysis(energy = bEnergy, cues = Cues(mixOutBeat = 96, mixInBeat = 16, firstDownbeat = 0, lastDownbeat = 124))
        assertEquals(0.7, analyzer.features(a, b, prefs).energyDelta, 1e-6)
        // Reverse direction: B's tail (bars 16..23, 0.1) into A's head (mixIn 0 -> bars 0..7, 1.0).
        assertEquals(0.9, analyzer.features(b, a, prefs).energyDelta, 1e-6)
        // Loud into quiet is negative.
        val loud = analysis(energy = FloatArray(32) { 0.95f })
        val quiet = analysis(energy = FloatArray(32) { 0.15f })
        assertEquals(-0.8, analyzer.features(loud, quiet, prefs).energyDelta, 1e-6)
    }

    @Test
    fun quietOutroIntoLoudBodyIsPositive() {
        // A's mix-out cue sits right after the intro (long outro), so its tail window is the pad-only intro.
        val a = synth(bars = 32, introBars = 4, outroBars = 20)
        assertEquals(16, a.cues.mixOutBeat)
        val b = synth(bars = 32, introBars = 0, outroBars = 4)
        val f = analyzer.features(a, b, prefs)
        assertTrue(f.energyDelta > 0.3, "energyDelta ${f.energyDelta}")
        assertTrue(f.lowEndShareB > f.lowEndShareA + 0.2, "low end A ${f.lowEndShareA} B ${f.lowEndShareB}")
        assertEquals(0.0, f.vocalClash, 1e-9)
        assertEquals(OutroType.AMBIENT_OUTRO, f.outro)
        assertEquals(IntroType.COLD_START, f.intro)
        // Synthetic full-body pairs are close to energy-neutral.
        val neutral = analyzer.features(synth(), synth(bpm = 124.0, tonic = 7), prefs)
        assertTrue(abs(neutral.energyDelta) < 0.15, "energyDelta ${neutral.energyDelta}")
        assertTrue(neutral.spectralSimilarity in 0.0..1.0, "similarity ${neutral.spectralSimilarity}")
    }

    @Test
    fun vocalClashAndLowEndShare() {
        val aVocal = FloatArray(32); for (bar in 16 until 24) aVocal[bar] = 0.5f
        val bVocal = FloatArray(32); for (bar in 4 until 12) bVocal[bar] = 0.8f
        val aSub = FloatArray(32) { 0.3f }; val aBass = FloatArray(32) { 0.4f }
        val bSub = FloatArray(32) { 0.05f }; val bBass = FloatArray(32) { 0.1f }
        val a = analysis(vocal = aVocal, sub = aSub, bass = aBass, cues = Cues(mixOutBeat = 96, mixInBeat = 0, firstDownbeat = 0, lastDownbeat = 124))
        val b = analysis(vocal = bVocal, sub = bSub, bass = bBass, cues = Cues(mixOutBeat = 96, mixInBeat = 16, firstDownbeat = 0, lastDownbeat = 124))
        val f = analyzer.features(a, b, prefs)
        assertEquals(0.4, f.vocalClash, 1e-6)
        assertEquals(0.7, f.lowEndShareA, 1e-6)
        assertEquals(0.15, f.lowEndShareB, 1e-6)
        // No vocals in B's head -> no clash even though A's tail sings.
        assertEquals(0.0, analyzer.features(a, analysis(), prefs).vocalClash, 1e-9)
    }

    @Test
    fun spectralSimilarityInLinearPower() {
        val flat = FloatArray(31) { -30f }
        val bassy = FloatArray(31) { if (it < 10) -20f else -60f }
        val bright = FloatArray(31) { if (it >= 20) -20f else -60f }
        assertEquals(1.0, analyzer.features(analysis(ltas = flat), analysis(ltas = flat), prefs).spectralSimilarity, 1e-9)
        assertEquals(1.0, analyzer.features(analysis(ltas = flat), analysis(ltas = FloatArray(31) { -50f }), prefs).spectralSimilarity, 1e-9) // same shape, different level
        val opposite = analyzer.features(analysis(ltas = bassy), analysis(ltas = bright), prefs).spectralSimilarity
        assertTrue(opposite < 0.01, "opposite spectra: $opposite")
        // Section spectra win over whole-track spectra when present.
        val sec = analyzer.features(analysis(ltas = bassy, outroLtas = flat), analysis(ltas = bright, introLtas = flat), prefs).spectralSimilarity
        assertEquals(1.0, sec, 1e-9)
        // Missing spectra are neutral.
        assertEquals(1.0, analyzer.features(analysis(), analysis(ltas = bright), prefs).spectralSimilarity, 1e-9)
    }

    // ------------------------------------------------------------------------------------------ room / edges

    @Test
    fun beatsAvailableComeFromCues() {
        val a = synth(bars = 32, introBars = 4, outroBars = 4) // lastDownbeat 124, mixOut 80
        val b = synth(bars = 32, introBars = 4, outroBars = 4) // mixIn 16, firstDownbeat 0
        val f = analyzer.features(a, b, prefs)
        assertEquals(44, f.outroBeatsAvailable)
        assertEquals(16, f.introBeatsAvailable)
        assertEquals(OutroType.AMBIENT_OUTRO, f.outro)
        assertEquals(IntroType.AMBIENT_INTRO, f.intro)
        val cold = analyzer.features(a, synth(bars = 32, introBars = 0), prefs)
        assertEquals(0, cold.introBeatsAvailable)
        assertEquals(IntroType.COLD_START, cold.intro)

        // Unknown cues fall back: 16 bars before the last beat / end of the first section / 8 bars.
        val noCues = analysis(cues = Cues())
        val fb = analyzer.features(noCues, noCues, prefs)
        assertEquals(64, fb.outroBeatsAvailable)
        assertEquals(32, fb.introBeatsAvailable)
        val withSection = analysis(cues = Cues(), sections = listOf(Section(0, 48, SectionLabel.INTRO, 0.3f)))
        assertEquals(48, analyzer.features(noCues, withSection, prefs).introBeatsAvailable)
        // Never negative.
        val weird = analysis(cues = Cues(mixOutBeat = 200, mixInBeat = 2, firstDownbeat = 10, lastDownbeat = 124))
        val w = analyzer.features(weird, weird, prefs)
        assertEquals(0, w.outroBeatsAvailable)
        assertEquals(0, w.introBeatsAvailable)
    }

    @Test
    fun emptyAnalysesAreNeutral() {
        val empty = TrackAnalysis(
            sourceId = "e", fingerprint = "e", sampleRate = 44100, totalFrames = 44100L * 30, trimStartFrame = 0, trimEndFrame = 44100L * 30,
            tempo = TempoEstimate(0.0, 0f), grid = BeatGrid.EMPTY, key = KeyEstimate(MusicalKey(0, Mode.MAJOR), 0.2f), loudness = LoudnessInfo(-120f, -120f),
        )
        val f = analyzer.features(empty, empty, prefs)
        assertEquals(TempoRelation.SAME, f.tempoRelation)
        assertEquals(1.0, f.tempoRatio, 1e-9)
        assertEquals(0.0, f.stretchPercent, 1e-9)
        assertEquals(0.0, f.gridConfidenceA, 1e-9)
        assertEquals(0.0, f.gridConfidenceB, 1e-9)
        assertTrue(!f.beatMatchable)
        assertEquals(0.0, f.energyDelta, 1e-9)
        assertEquals(0.0, f.vocalClash, 1e-9)
        assertEquals(0.0, f.lowEndShareA, 1e-9)
        assertEquals(0.0, f.loudnessDeltaLu, 1e-9)
        assertEquals(1.0, f.spectralSimilarity, 1e-9)
        assertEquals(0, f.outroBeatsAvailable)
        assertEquals(0, f.introBeatsAvailable)
        assertEquals(IntroType.UNKNOWN, f.intro)
        assertEquals(OutroType.UNKNOWN, f.outro)
        // A real track against an empty one still gets a tempo ratio from the known bpm only when both are known.
        val mixed = analyzer.features(synth(), empty, prefs)
        assertEquals(0.0, mixed.stretchPercent, 1e-9)
        assertTrue(!mixed.beatMatchable)
    }

    @Test
    fun deterministicAndPure() {
        val a = synth(); val b = synth(bpm = 126.0, tonic = 7)
        val f1 = analyzer.features(a, b, prefs)
        val f2 = DefaultPairAnalyzer().features(a, b, prefs)
        assertEquals(f1, f2)
        val json = PairFeatures.serializer()
        assertEquals(f1, kotlinx.serialization.json.Json.decodeFromString(json, kotlinx.serialization.json.Json.encodeToString(json, f1)))
    }
}
