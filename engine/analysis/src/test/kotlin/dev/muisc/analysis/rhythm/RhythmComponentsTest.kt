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

    // ---- MetricalLevel ---------------------------------------------------------------------------------------

    /**
     * Features with the given raw broadband / low-band / mid-band flux at 64 tracked beats 40 frames apart (even / odd
     * beats); the high band is what the broadband flux has beyond the low and mid bands.
     */
    private fun parityFeatures(
        broadEven: Float, broadOdd: Float, lowEven: Float, lowOdd: Float, midEven: Float = 0f, midOdd: Float = 0f,
    ): Pair<IntArray, OnsetFeatures> {
        val beats = IntArray(64) { 20 + it * 40 }
        val n = beats.last() + 40
        val raw = FloatArray(n); val low = FloatArray(n); val mid = FloatArray(n)
        for ((i, b) in beats.withIndex()) {
            raw[b] = if (i % 2 == 0) broadEven else broadOdd; low[b] = if (i % 2 == 0) lowEven else lowOdd
            mid[b] = if (i % 2 == 0) midEven else midOdd
        }
        return beats to OnsetFeatures(ar, 256, raw, low, raw, low, Array(n) { FloatArray(12) }, FloatArray(n), mid)
    }

    @Test
    fun metricalLevel_halvesOnlyWhenTheLowBandAndTheBroadbandFluxBothAlternate() {
        val m = MetricalLevel()
        // A slow song tracked at its eighths: kick + hat on the beats, a hat alone on the off-beats.
        val (beats, slow) = parityFeatures(broadEven = 1f, broadOdd = 0.4f, lowEven = 0.5f, lowOdd = 0f)
        val c = m.check(beats, slow, bpm = 132.0)
        assertEquals(0.0, c.lowBalance, 1e-9); assertEquals(0.4, c.broadBalance, 1e-6)
        assertTrue(c.halve)
        // ... but not below minBpm
        assertFalse(m.check(beats, slow, bpm = 79.0).halve)
        // A backbeat: the low band alternates (kick / snare), the broadband flux does not.
        val (b2, backbeat) = parityFeatures(broadEven = 1f, broadOdd = 0.9f, lowEven = 0.5f, lowOdd = 0.05f)
        val c2 = m.check(b2, backbeat, bpm = 120.0)
        assertTrue(c2.lowBalance < m.maxLowBalance && c2.broadBalance >= m.maxBroadBalance, "low ${c2.lowBalance} broad ${c2.broadBalance}")
        assertFalse(c2.halve)
        // Every tracked beat alike (four on the floor at the right level).
        assertFalse(m.check(b2, parityFeatures(1f, 0.95f, 0.5f, 0.45f).second, bpm = 128.0).halve)
        // No low band at all (share of flux below OnsetFeatures.LOW_BAND_ZERO_FRACTION): no evidence, no change.
        val (b3, noLow) = parityFeatures(broadEven = 1f, broadOdd = 0.4f, lowEven = 0.03f, lowOdd = 0f)
        assertEquals(0.0, m.check(b3, noLow, bpm = 132.0).lowWeight)
        assertFalse(m.check(b3, noLow, bpm = 132.0).halve)
        // A parity slip halfway (the tracker skipped one eighth, so even and odd swap) does not balance the parities
        // out: the balance is taken per block of 8 beats (summed over the whole track it would read 1).
        val slipped = IntArray(64) { if (it < 32) 20 + it * 40 else 20 + (it + 1) * 40 }
        assertEquals(0.0, m.parityBalance(slipped, slow.rawLowOdf), 1e-9)
        assertEquals(1.0, m.parityBalance(IntArray(4) { it * 40 }, slow.rawLowOdf), 1e-9) // fewer than 8 beats: no evidence
    }

    @Test
    fun metricalLevel_doesNotHalveWhenTheOffBeatsCarryMoreThanHats() {
        val m = MetricalLevel()
        // Double time: the beats carry the kick, a snare body or a chord change (mid) and a hat; the off-beats a hat.
        val (beats, slow) = parityFeatures(broadEven = 1f, broadOdd = 0.4f, lowEven = 0.4f, lowOdd = 0f, midEven = 0.3f, midOdd = 0.05f)
        val c = m.check(beats, slow, bpm = 132.0)
        assertEquals(0.05 / 0.3, c.midRatio, 1e-6); assertEquals(0.35 / 0.3, c.highRatio, 1e-6)
        // high: 0.3 at the beats, 0.35 at the off-beats -> not hat-like
        assertFalse(c.halve)
        val (b1, slow1) = parityFeatures(broadEven = 1f, broadOdd = 0.4f, lowEven = 0.3f, lowOdd = 0f, midEven = 0.3f, midOdd = 0.05f)
        val c1 = m.check(b1, slow1, bpm = 132.0)
        assertEquals(0.35 / 0.4, c1.highRatio, 1e-6)
        assertTrue(c1.halve, "low ${c1.lowBalance} broad ${c1.broadBalance} mid ${c1.midRatio} high ${c1.highRatio}")
        // The same with the low band on the odd beats: the beat parity is the one with the low band, not the even one.
        val swapped = IntArray(b1.size - 1) { b1[it + 1] }
        assertTrue(m.check(swapped, slow1, bpm = 132.0).halve)
        assertEquals(c1.highRatio, m.check(swapped, slow1, bpm = 132.0).highRatio, 1e-6)
        // The halved tempo must be plausible: 110 -> 55 is halved, 108 -> 54 is not.
        assertTrue(m.check(b1, slow1, bpm = 110.0).halve)
        assertFalse(m.check(b1, slow1, bpm = 108.0).halve)

        // A backbeat whose snare or chord puts as much mid-band flux on 2 and 4 as the kick and bass put on 1 and 3:
        // broad balance 0.4 and low balance 0 alone would halve it.
        val (b2, snare) = parityFeatures(broadEven = 1f, broadOdd = 0.4f, lowEven = 0.5f, lowOdd = 0f, midEven = 0.2f, midOdd = 0.2f)
        val c2 = m.check(b2, snare, bpm = 120.0)
        assertTrue(c2.lowBalance < m.maxLowBalance && c2.broadBalance < m.maxBroadBalance && c2.highRatio < m.maxHighRatio, "only the mid band vetoes")
        assertEquals(1.0, c2.midRatio, 1e-6)
        assertFalse(c2.halve)
        // A clap backbeat (high band only) whose clap is weaker than the kick in broadband flux: the off-beats carry
        // more high-band flux than the beats, which have none.
        val (b3, clap) = parityFeatures(broadEven = 1f, broadOdd = 0.5f, lowEven = 0.75f, lowOdd = 0f, midEven = 0.25f, midOdd = 0.05f)
        val c3 = m.check(b3, clap, bpm = 120.0)
        assertTrue(c3.lowBalance < m.maxLowBalance && c3.broadBalance < m.maxBroadBalance && c3.midRatio < m.maxMidRatio, "only the high band vetoes")
        assertTrue(c3.highRatio.isInfinite(), "no high-band flux at the beats: ${c3.highRatio}")
        assertFalse(c3.halve)
        // No mid-band information (features made without it): the mid band never vetoes.
        val (b4, noMid) = parityFeatures(broadEven = 1f, broadOdd = 0.4f, lowEven = 0.5f, lowOdd = 0f)
        assertEquals(0.0, m.check(b4, noMid, bpm = 132.0).midRatio)
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

    /**
     * A sustained saw-ish bass (the tail of the previous bass note under every beat) plus, at 20 known times, a kick
     * (a sine sweeping 155 → 45 Hz, like [dev.muisc.audio.synth.SyntheticSong]'s), optionally with a hi-hat noise
     * burst on the same beat as in those songs.
     */
    private fun kicksOverBass(bassHz: Double, hat: Boolean): Pair<FloatArray, DoubleArray> {
        val x = FloatArray(ar * 8)
        val rnd = kotlin.random.Random(3)
        val w = 2 * Math.PI * bassHz / ar
        for (i in x.indices) { var s = 0.0; for (h in 1..4) s += kotlin.math.sin(h * w * i) / h; x[i] = (0.2 * s).toFloat() }
        val kicks = DoubleArray(20) { 0.3 + it * 0.3717 + rnd.nextDouble() * 0.01 }
        for (k in kicks) {
            val start = Math.round(k * ar).toInt()
            var phase = 0.0
            for (i in 0 until (0.35 * ar).toInt()) {
                val t = i.toDouble() / ar
                phase += 2 * Math.PI * (45.0 + 110.0 * kotlin.math.exp(-t * 28.0)) / ar
                if (start + i < x.size) x[start + i] += (0.75 * kotlin.math.exp(-t * 9.0) * kotlin.math.sin(phase)).toFloat()
            }
            if (hat) {
                var hp = 0f
                for (i in 0 until (0.1 * ar).toInt()) {
                    val white = rnd.nextFloat() * 2f - 1f
                    val out = white - hp; hp += 0.6f * (white - hp)
                    if (start + i < x.size) x[start + i] += 0.2f * kotlin.math.exp(-i / (0.02 * ar)).toFloat() * out
                }
            }
        }
        return x to kicks
    }

    @Test
    fun transientAligner_findsKickOnsetsOverASustainedBassLine() {
        // Measured on single 1.45 ms blocks, the power of these low notes rises and falls with every half cycle, so
        // the largest block-to-block rise can sit anywhere in the bass before the beat or in the kick's first cycles
        // (the old envelope put beats up to 38 ms early). A candidate 8 ms early, as an ODF peak would be:
        for (bass in doubleArrayOf(55.0, 65.0, 82.0)) {
            // kick + hat, as on every beat of the synthetic songs: the aligned time is the beat's start
            val (x, kicks) = kicksOverBass(bass, hat = true)
            val a = TransientAligner(x, ar)
            for (k in kicks) {
                val err = a.align(k - 0.008) - k
                assertTrue(abs(err) <= 0.002, "kick + hat over a $bass Hz bass at ${k * 1000} ms: aligned ${"%.1f".format(err * 1000)} ms off")
            }
            // a bare sine kick: never moved further from the beat than the candidate already was
            val (y, bare) = kicksOverBass(bass, hat = false)
            val b = TransientAligner(y, ar)
            for (k in bare) {
                val err = b.align(k - 0.008) - k
                assertTrue(abs(err) <= 0.008 + 1e-9, "bare kick over a $bass Hz bass at ${k * 1000} ms: aligned ${"%.1f".format(err * 1000)} ms off")
            }
        }
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

        // drifting tempo → FLEX with the tracked beats kept. The onsets are where the drifting beats are (the fitter
        // weighs each beat by the onset under it, and tracked beats follow the onsets); beats drifting away from
        // onsets that lie on a line are the lead-in case of gridFitter_extrapolatesTheConfidentSectionThroughAWanderingLeadIn.
        val drift = DoubleArray(n) { 0.3 + it * period + 0.00002 * it * it }
        val driftOdf = FloatArray((60.0 / hop).toInt())
        for (t in drift) driftOdf[(t / hop).roundToInt()] = 1f
        val fit2 = GridFitter().fit(drift, driftOdf, hop, 0.8f, 60.0)
        assertEquals(GridKind.FLEX, fit2.kind)
        assertEquals(n, fit2.beatCount)
        for (i in 0 until n) assertTrue(abs(fit2.beatTimesSec[i] - drift[i]) < 0.001)
        assertTrue(fit2.bpm in 115.0..121.0)

        // fewer than two beats
        val one = GridFitter().fit(doubleArrayOf(1.0), odf, hop, 0.8f, 60.0)
        assertEquals(1, one.beatCount); assertEquals(0f, one.confidence)
    }

    @Test
    fun gridFitter_extrapolatesTheConfidentSectionThroughAWanderingLeadIn() {
        // A drumless intro: 24 beats (6 bars at 100 BPM) whose only onsets are the chord changes on the downbeats,
        // then 40 beats of drums with an onset on every beat. The tracker's intro beats wandered off the grid by up
        // to ±150 ms and lost one beat (23 tracked for 24); the drum section is tracked exactly.
        val hop = 256.0 / ar
        val period = 0.6
        val first = 0.25
        val odf = FloatArray((42.0 / hop).toInt())
        for (k in 0 until 64) if (k >= 24 || k % 4 == 0) odf[((first + k * period) / hop).roundToInt()] = if (k >= 24) 1f else 0.8f
        val intro = DoubleArray(23) { first + it * (24 * period / 23) + 0.15 * kotlin.math.sin(it * 0.7) }
        val body = DoubleArray(40) { first + (24 + it) * period + (if (it % 2 == 0) 0.001 else -0.001) }
        val fit = GridFitter().fit(intro + body, odf, hop, tempoConfidence = 0.9f, endSec = 42.0)
        assertEquals(GridKind.RIGID, fit.kind, "residual ${fit.residualMs} ms, inliers ${fit.inlierFraction}")
        assertEquals(100.0, fit.bpm, 0.05)
        // the grid is the drum section's line, extended back through the intro to the first tracked beat
        assertEquals(first, fit.beatTimesSec[0], 0.002)
        for ((k, t) in fit.beatTimesSec.withIndex()) assertEquals(first + k * period, t, 0.002, "beat $k")
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
