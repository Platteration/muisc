package dev.muisc.analysis.rhythm

import dev.muisc.analysis.model.GridKind
import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.resample.Resampler
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests of the individual rhythm components at the 22.05 kHz analysis rate. */
class RhythmComponentsTest {
    private val ar = RhythmAnalyzer.ANALYSIS_SAMPLE_RATE

    // ---- OnsetDetector -------------------------------------------------------------------------------------

    @Test
    fun onsetDetector_geometryAndClickOnsets() {
        val det = OnsetDetector()
        assertEquals(256.0 / 22050, det.hopSeconds, 1e-12)
        assertTrue(det.lowBandCount in 1..3, "low band count ${det.lowBandCount}")
        val x = Resampler().resample(Synth.clickTrack(44100, 120.0, 10.0, offsetSec = 0.25), 44100, ar)
        val f = det.analyze(x)
        assertEquals(det.hopSeconds, f.hopSeconds, 1e-12)
        assertEquals(1 + x.size / 256, f.frames)
        assertEquals(0f, f.odf[0])
        assertTrue(f.odf.all { it in 0f..1f + 1e-6f })
        assertTrue(f.odf.max() > 0.99f)
        // one onset per click, within 8 ms
        val onsets = f.onsetTimes()
        val clicks = (0 until 20).map { 0.25 + it * 0.5 }
        assertEquals(clicks.size, onsets.size)
        for (i in clicks.indices) assertTrue(abs(onsets[i] - clicks[i]) <= 0.008, "onset $i: ${onsets[i]} vs ${clicks[i]}")
        // 1000–1500 Hz clicks leave only transient splatter in the low band → the low-band ODF gets ~no weight
        assertTrue(f.lowBandFraction < 0.06, "low band fraction ${f.lowBandFraction}")
        assertTrue(f.lowBandWeight() < 0.1, "low band weight ${f.lowBandWeight()}")
        assertEquals(0.0, OnsetFeatures.lowBandWeightFor(0.03)); assertEquals(1.0, OnsetFeatures.lowBandWeightFor(0.2)); assertEquals(0.5, OnsetFeatures.lowBandWeightFor(0.08), 1e-9)
        // ODF is quiet between clicks: the mean is far below the peaks
        assertTrue(f.odf.average() < 0.1)
    }

    @Test
    fun onsetDetector_silenceGivesZeroOdfAndNoOnsets() {
        val f = OnsetDetector().analyze(FloatArray(ar * 3))
        assertTrue(f.odf.all { it == 0f })
        assertTrue(f.lowOdf.all { it == 0f })
        assertTrue(f.onsetTimes().isEmpty())
        val empty = OnsetDetector().analyze(FloatArray(0))
        assertEquals(1, empty.frames)
        assertTrue(empty.onsetTimes().isEmpty())
    }

    @Test
    fun onsetDetector_vibratoProducesLittleFluxComparedToNoteOnsets() {
        // A 440 Hz tone with ±3 % vibrato at 6 Hz versus the same tone restarted every 0.5 s.
        val n = ar * 4
        val vib = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val f = 440.0 * (1 + 0.03 * kotlin.math.sin(2 * Math.PI * 6 * i / ar))
            ph += 2 * Math.PI * f / ar
            vib[i] = (0.5 * kotlin.math.sin(ph)).toFloat()
        }
        val notes = FloatArray(n)
        for (k in 0 until 8) {
            val s = k * ar / 2
            for (i in 0 until ar / 2 - 2000) notes[s + i] = (0.5 * kotlin.math.sin(2 * Math.PI * 440.0 * i / ar)).toFloat()
        }
        val det = OnsetDetector()
        val rawVib = det.analyze(vib).rawOdf.drop(5).max()
        val rawNotes = det.analyze(notes).rawOdf.drop(5).max()
        assertTrue(rawNotes > 2 * rawVib, "notes $rawNotes vs vibrato $rawVib")
    }

    @Test
    fun runningMaxNormalisation() {
        val x = FloatArray(1000) { if (it % 100 == 0) (if (it < 500) 4f else 1f) else 0.1f }
        val y = OnsetDetector.normaliseByRunningMax(x, windowFrames = 50, floorFraction = 0.1f)
        assertEquals(1f, y[0], 1e-6f)      // 4 / 4
        assertEquals(1f, y[900], 1e-6f)    // 1 / 1 (running max)
        assertEquals(0.1f, y[905], 1e-6f)  // 0.1 / 1 (the pulse at 900 is inside the ±25-frame window)
        assertEquals(0.25f, y[950], 1e-6f) // 0.1 / max(running max 0.1, floor 0.1 * 4)
        assertEquals(0.025f, y[10], 1e-6f) // 0.1 / 4 (pulse at 0 inside the window)
        assertEquals(0.25f, y[50], 1e-6f)  // no pulse within ±25 frames → floor 0.4
        // floor: a region of tiny values is not amplified above floor * global
        val z = FloatArray(200) { 0.01f }
        z[0] = 1f
        val zn = OnsetDetector.normaliseByRunningMax(z, 10, 0.1f)
        assertEquals(0.1f, zn[150], 1e-6f) // 0.01 / max(0.01, 0.1 * 1)
        assertTrue(OnsetDetector.normaliseByRunningMax(FloatArray(10), 5, 0.1f).all { it == 0f })
    }

    // ---- TempoEstimator ------------------------------------------------------------------------------------

    /** Pulse train with sub-frame positions (each pulse split linearly between its two neighbouring frames, like a real ODF peak). */
    private fun pulseOdf(periodFrames: Double, frames: Int, sub: Float = 0f): FloatArray {
        val odf = FloatArray(frames)
        fun put(t: Double, amp: Float) {
            val i = kotlin.math.floor(t).toInt(); val frac = (t - i).toFloat()
            if (i in 0 until frames) odf[i] += amp * (1 - frac)
            if (i + 1 in 0 until frames) odf[i + 1] += amp * frac
        }
        var t = 0.0
        while (t < frames) {
            put(t, 1f)
            if (sub > 0) put(t + periodFrames / 2, sub)
            t += periodFrames
        }
        return odf
    }

    private fun featuresOf(odf: FloatArray, low: FloatArray = odf): OnsetFeatures =
        OnsetFeatures(ar, 256, odf, low, odf, FloatArray(odf.size) { low[it] * 0.2f }, Array(odf.size) { FloatArray(12) }, FloatArray(odf.size))

    @Test
    fun tempoEstimator_findsPeriodWithSubBpmPrecisionAndListsOctaves() {
        val hop = 256.0 / ar
        for (bpm in doubleArrayOf(75.0, 100.0, 133.3, 160.0)) {
            val period = 60.0 / bpm / hop
            val odf = pulseOdf(period, (40.0 / hop).toInt())
            val r = TempoEstimator().estimate(featuresOf(odf))
            assertEquals(bpm, r.bpm, bpm * 0.005, "bpm $bpm")
            assertTrue(r.confidence > 0.5f, "confidence ${r.confidence} at $bpm")
            assertTrue(r.estimate.alternates.any { abs(it.bpm - bpm / 2) < 0.005 * bpm }, "half alternate at $bpm: ${r.estimate.alternates}")
            assertTrue(r.estimate.alternates.any { abs(it.bpm - bpm * 2) < 0.01 * bpm }, "double alternate at $bpm: ${r.estimate.alternates}")
            assertEquals(period, r.periodFrames, period * 0.005)
            // local tempo curve is flat and equal to the global one
            val curve = r.tempogram.periodCurve(odf.size, r.periodFrames)
            assertTrue(curve.all { abs(it - period) < period * 0.01 })
            assertTrue(r.tempogram.windows > 5)
        }
    }

    @Test
    fun tempoEstimator_hiHatSubdivisionsDoNotDoubleTheTempoWhenTheLowBandIsClean() {
        val hop = 256.0 / ar
        val bpm = 88.0
        val period = 60.0 / bpm / hop
        val frames = (40.0 / hop).toInt()
        val broadband = pulseOdf(period, frames, sub = 0.8f)  // strong 8th notes
        val low = pulseOdf(period, frames)                     // kick on every beat only
        val r = TempoEstimator().estimate(featuresOf(broadband, low))
        assertEquals(bpm, r.bpm, 1.0, "with low band: ${r.bpm}")
        // The double tempo is present as an alternate with a substantial score.
        assertTrue(r.estimate.alternates.any { abs(it.bpm - 176.0) < 2.0 && it.score > 0.3 })
    }

    @Test
    fun tempoEstimator_prefersTheMusicalRangeAndHandlesShortOrEmptyInput() {
        val hop = 256.0 / ar
        // 55 BPM pulse train: 110 BPM (its double) is inside the preferred range and scores well → chosen.
        val odf = pulseOdf(60.0 / 55.0 / hop, (40.0 / hop).toInt())
        val r = TempoEstimator().estimate(featuresOf(odf))
        assertTrue(abs(r.bpm - 110.0) < 1.0 || abs(r.bpm - 55.0) < 0.5, "bpm ${r.bpm}")
        // too short → no estimate, no exception
        val short = TempoEstimator().estimate(featuresOf(FloatArray(50) { 1f }))
        assertEquals(0.0, short.bpm)
        assertEquals(0f, short.confidence)
        val flat = TempoEstimator().estimate(featuresOf(FloatArray(4000)))
        assertEquals(0.0, flat.bpm)
        assertEquals(0f, flat.confidence)
    }

    @Test
    fun tempoEstimator_helpers() {
        val x = FloatArray(64) { if (it % 8 == 0) 1f else 0f }
        val tmp = FloatArray(64); val out = DoubleArray(17)
        assertTrue(TempoEstimator.autocorrelation(x, 0, 64, 16, tmp, out))
        assertEquals(1.0, out[0], 1e-9)
        assertTrue(out[8] > 0.9 && out[16] > 0.9, "acf at period ${out[8]} ${out[16]}")
        assertTrue(out[4] < 0.0, "acf at half period is negative after mean removal: ${out[4]}")
        assertFalse(TempoEstimator.autocorrelation(FloatArray(64) { 0.5f }, 0, 64, 16, tmp, out))
        // parabolic refinement of a symmetric peak lands in between
        val y = floatArrayOf(0f, 0.5f, 1f, 1f, 0.5f, 0f)
        assertEquals(3.5, TempoEstimator.refinePeak(y, 3, 1, 6), 1e-9)
        assertEquals(3.5, TempoEstimator.refinePeak(y, 4, 1, 6), 1e-9)
        assertEquals(1.0, TempoEstimator.refinePeak(y, 1, 1, 6), 1e-9) // edge: no refinement
        val h = TempoEstimator.harmonicSum(floatArrayOf(0f, 1f, 0f, 1f, 0f, 1f, 0f, 1f), 1, 8)
        assertEquals(1f + 0.5f + 1f / 3 + 0.25f, h[1], 1e-6f)  // lag 2 sees 4, 6, 8
        assertEquals(1f + 0.5f, h[3], 1e-6f)                  // lag 4 sees 8
        assertEquals(listOf(2, 4), TempoEstimator.localMaxima(floatArrayOf(0f, 1f, 0f, 0.9f, 0f), 1, 5))
    }

    // ---- BeatTracker -----------------------------------------------------------------------------------------

    @Test
    fun beatTracker_followsPulsesAndToleratesGapsAndDrift() {
        val n = 3000
        val odf = FloatArray(n)
        val truth = ArrayList<Int>()
        // period drifts from 40 to 44 frames; a gap of 8 missing pulses in the middle
        var t = 7.0
        var k = 0
        while (t < n) {
            val i = t.roundToInt()
            truth.add(i)
            if (k !in 30..37) odf[i] = 1f
            t += 40.0 + 4.0 * (t / n)
            k++
        }
        for (i in 0 until n) odf[i] += 0.05f * ((i * 7919) % 13) / 13f  // deterministic clutter
        val periods = DoubleArray(n) { 40.0 + 4.0 * (it.toDouble() / n) }
        val beats = BeatTracker().track(odf, periods)
        assertTrue(beats.size >= truth.size - 1 && beats.size <= truth.size + 1, "beats ${beats.size} vs ${truth.size}")
        var hits = 0
        for (b in truth) if (beats.any { abs(it - b) <= 1 }) hits++
        assertTrue(hits >= truth.size - 2, "hits $hits of ${truth.size}")
        for (i in 1 until beats.size) assertTrue(beats[i] > beats[i - 1])
        // empty / degenerate
        assertTrue(BeatTracker().track(FloatArray(0), DoubleArray(0)).isEmpty())
        assertTrue(BeatTracker().track(FloatArray(10), DoubleArray(10)).isEmpty())
    }

    @Test
    fun transientAligner_findsClickStartsAndLeavesSustainedMaterialAlone() {
        val x = FloatArray(ar * 2)
        val clicks = doubleArrayOf(0.25, 0.7, 1.2, 1.73)
        for (c in clicks) Synth.addBurst(x, ar, Math.round(c * ar).toInt(), 1000.0, 0.02, 0.8f)
        val a = TransientAligner(x, ar)
        for (c in clicks) {
            val aligned = a.align(c - 0.008)   // candidate 8 ms early, as an ODF peak would be
            assertTrue(abs(aligned - c) <= 0.002, "aligned $aligned vs click $c")
        }
        val tone = Synth.sine(ar, 220.0, 2.0)
        val b = TransientAligner(tone, ar)
        assertEquals(1.0, b.align(1.0), 1e-12)
        val seq = b.alignAll(doubleArrayOf(0.5, 1.0, 1.5))
        assertTrue(seq.contentEquals(doubleArrayOf(0.5, 1.0, 1.5)))
    }

    // ---- GridFitter ------------------------------------------------------------------------------------------

    @Test
    fun gridFitter_rigidWhenBeatsAreOnALineEvenWithOutliers_flexWhenTempoDrifts() {
        val hop = 256.0 / ar
        val period = 0.5
        val n = 100
        val beats = DoubleArray(n) { 0.3 + it * period + (if (it % 3 == 0) 0.002 else -0.002) }
        beats[0] += 0.04; beats[1] -= 0.03; beats[n - 1] += 0.05  // ambient intro / outro beats off the line
        val odf = FloatArray((60.0 / hop).toInt())
        for (t in beats) odf[(t / hop).roundToInt()] = 1f
        val fit = GridFitter().fit(beats, odf, hop, tempoConfidence = 0.8f, endSec = 60.0)
        assertEquals(GridKind.RIGID, fit.kind)
        assertEquals(120.0, fit.bpm, 0.05)
        assertTrue(fit.residualMs < 3.0, "residual ${fit.residualMs}")
        assertTrue(fit.inlierFraction >= 0.95f)
        assertEquals(0.3, fit.beatTimesSec[0], 0.003)
        assertTrue(fit.beatTimesSec.last() <= 60.0 && fit.beatTimesSec.last() > 59.4)
        assertTrue(fit.beatSupport > 0.9f, "support ${fit.beatSupport}")
        assertEquals(kotlin.math.sqrt(fit.beatSupport * 0.8), fit.confidence.toDouble(), 1e-5)

        // drifting tempo → FLEX with the tracked beats kept
        val drift = DoubleArray(n) { 0.3 + it * period + 0.00002 * it * it }
        val fit2 = GridFitter().fit(drift, odf, hop, 0.8f, 60.0)
        assertEquals(GridKind.FLEX, fit2.kind)
        assertEquals(n, fit2.beatCount)
        for (i in 0 until n) assertTrue(abs(fit2.beatTimesSec[i] - drift[i]) < 0.001)
        assertTrue(fit2.bpm in 115.0..121.0)

        // fewer than two beats
        val one = GridFitter().fit(doubleArrayOf(1.0), odf, hop, 0.8f, 60.0)
        assertEquals(1, one.beatCount); assertEquals(0f, one.confidence)
    }

    @Test
    fun gridFitter_medianSmoothingRemovesSingleOutliersOnly() {
        val t = DoubleArray(20) { it * 0.5 }
        t[7] += 0.03
        val s = GridFitter().medianSmooth(t)
        assertEquals(3.5, s[7], 1e-9)
        for (i in t.indices) if (i != 7) assertEquals(t[i], s[i], 1e-9)
        // a genuine tempo change is kept
        val ramp = DoubleArray(20) { if (it < 10) it * 0.5 else 4.5 + (it - 9) * 0.45 }
        val r = GridFitter().medianSmooth(ramp)
        for (i in 2 until 18) assertTrue(abs(r[i] - ramp[i]) < 0.03, "beat $i moved to ${r[i]} from ${ramp[i]}")
    }

    // ---- DownbeatEstimator -----------------------------------------------------------------------------------

    @Test
    fun downbeatEstimator_usesLowBandAccentsAndReportsMargin() {
        val hop = 256.0 / ar
        val n = 64
        val frames = (n * 0.5 / hop).toInt() + 10
        val odf = FloatArray(frames)
        val low = FloatArray(frames)
        val beats = DoubleArray(n) { 0.1 + it * 0.5 }
        for ((i, t) in beats.withIndex()) {
            val f = (t / hop).roundToInt()
            odf[f] = 1f
            low[f] = if (i % 4 == 2) 1f else 0.4f   // accent on the third beat of each bar
        }
        val feats = OnsetFeatures(ar, 256, odf, low, odf, low, Array(frames) { FloatArray(12) }, FloatArray(frames))
        val r = DownbeatEstimator().estimate(beats, feats)
        assertEquals(2, r.phase)
        assertTrue(r.confidence > 0.5f, "confidence ${r.confidence}")
        // no information at all → some phase, zero confidence, no phrase
        val flat = DownbeatEstimator().estimate(beats, OnsetFeatures(ar, 256, odf, odf, odf, odf, Array(frames) { FloatArray(12) }, FloatArray(frames)))
        assertEquals(0f, flat.confidence)
        assertEquals(-1, flat.phraseStartBeat)
        // too few beats
        val few = DownbeatEstimator().estimate(doubleArrayOf(0.0, 0.5, 1.0), feats)
        assertEquals(0f, few.confidence)
    }

    @Test
    fun downbeatEstimator_chordChangesMarkDownbeats() {
        val hop = 256.0 / ar
        val n = 64
        val frames = (n * 0.5 / hop).toInt() + 10
        val odf = FloatArray(frames)
        val chroma = Array(frames) { FloatArray(12) }
        val beats = DoubleArray(n) { 0.1 + it * 0.5 }
        // chord changes on beats 1, 5, 9 ... (phase 1): a new root every bar
        for (f in 0 until frames) {
            val t = f * hop
            val beat = ((t - 0.1) / 0.5).toInt().coerceAtLeast(0)
            val bar = (beat - 1 + 4) / 4
            chroma[f][(bar * 5) % 12] = 1f
            chroma[f][(bar * 5 + 4) % 12] = 0.7f
        }
        for (t in beats) odf[(t / hop).roundToInt()] = 1f
        val feats = OnsetFeatures(ar, 256, odf, FloatArray(frames), odf, FloatArray(frames), chroma, FloatArray(frames))
        val r = DownbeatEstimator().estimate(beats, feats)
        assertEquals(1, r.phase)
        assertTrue(r.confidence > 0.3f, "confidence ${r.confidence}")
    }
}
