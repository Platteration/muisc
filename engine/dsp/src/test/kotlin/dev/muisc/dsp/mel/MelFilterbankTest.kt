package dev.muisc.dsp.mel

import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.fft.RealFft
import dev.muisc.dsp.window.Window
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MelFilterbankTest {
    @Test
    fun melScaleRoundTripAndReferenceValues() {
        for (hz in doubleArrayOf(0.0, 100.0, 999.0, 1000.0, 4000.0, 22050.0)) {
            assertEquals(hz, MelFilterbank.melToHz(MelFilterbank.hzToMel(hz)), 1e-6 * (1 + hz))
            assertEquals(hz, MelFilterbank.melToHz(MelFilterbank.hzToMel(hz, htk = true), htk = true), 1e-6 * (1 + hz))
        }
        // Slaney: 1000 Hz = 15 mel, linear below (hz / 66.67); HTK: 1000 Hz = 1000 mel.
        assertEquals(15.0, MelFilterbank.hzToMel(1000.0), 1e-9)
        assertEquals(7.5, MelFilterbank.hzToMel(500.0), 1e-9)
        assertEquals(999.9855, MelFilterbank.hzToMel(1000.0, htk = true), 1e-3) // 2595*log10(1+1000/700)
        // Slaney log region: 15 + ln(4000/1000) / (ln(6.4)/27) = 35.164 (librosa.hz_to_mel(4000))
        assertEquals(35.164, MelFilterbank.hzToMel(4000.0), 0.002)
        // monotone
        var prev = -1.0
        var f = 0.0
        while (f <= 20000.0) { val m = MelFilterbank.hzToMel(f); assertTrue(m > prev); prev = m; f += 50.0 }
    }

    @Test
    fun eachFilterPeaksAtItsCentre() {
        val fb = MelFilterbank(fftSize = 4096, sampleRate = 44100, nMels = 64, fMin = 50.0, fMax = 16000.0)
        val binHz = 44100.0 / 4096
        assertEquals(2049, fb.bins)
        assertEquals(64, fb.weights.size)
        for (m in 0 until fb.nMels) {
            assertFalse(fb.isEmpty(m), "filter $m must cover at least one bin")
            val w = fb.weights[m]
            var best = 0
            for (k in w.indices) if (w[k] > w[best]) best = k
            // The peak bin is the bin nearest the centre frequency (within one bin width).
            assertTrue(abs(best * binHz - fb.centerFrequencies[m]) <= binHz, "filter $m peaks at ${best * binHz} Hz, centre ${fb.centerFrequencies[m]}")
            // Triangular: weights are non-decreasing up to the peak and non-increasing after it.
            for (k in 1..best) assertTrue(w[k] >= w[k - 1])
            for (k in best + 1 until w.size) assertTrue(w[k] <= w[k - 1])
            // Support is exactly (edge[m], edge[m+2]).
            for (k in w.indices) {
                val f = k * binHz
                val inside = f > fb.edgeFrequencies[m] && f < fb.edgeFrequencies[m + 2]
                assertEquals(inside, w[k] > 0f, "filter $m bin $k (${f} Hz)")
            }
        }
        // Centre frequencies are strictly increasing between fMin and fMax.
        for (m in 1 until fb.nMels) assertTrue(fb.centerFrequencies[m] > fb.centerFrequencies[m - 1])
        assertTrue(fb.centerFrequencies[0] > 50.0 && fb.centerFrequencies[63] < 16000.0)
    }

    @Test
    fun unnormalisedFiltersSumToOneAndSlaneyFiltersHaveUnitArea() {
        val sr = 44100; val n = 8192
        val binHz = sr.toDouble() / n
        val plain = MelFilterbank(n, sr, nMels = 40, fMin = 0.0, fMax = 8000.0, normalize = false)
        // Between the first and last centre frequency adjacent triangles are complementary: sum == 1 exactly.
        for (k in 0 until plain.bins) {
            val f = k * binHz
            if (f <= plain.centerFrequencies[0] || f >= plain.centerFrequencies[39]) continue
            var s = 0f
            for (m in 0 until 40) s += plain.weights[m][k]
            assertEquals(1f, s, 1e-4f, "bin $k ($f Hz) filter sum")
        }
        for (m in 0 until 40) {
            var peak = 0f
            for (v in plain.weights[m]) peak = maxOf(peak, v)
            assertTrue(peak > 0.9f && peak <= 1f, "filter $m peak $peak")
        }
        // Slaney normalisation: each triangle has area ~1 Hz^-1 * Hz (Riemann sum over bins * bin width).
        val slaney = MelFilterbank(n, sr, nMels = 40, fMin = 0.0, fMax = 8000.0, normalize = true)
        for (m in 0 until 40) {
            var area = 0.0
            for (v in slaney.weights[m]) area += v
            area *= binHz
            assertEquals(1.0, area, 0.05, "filter $m area")
        }
    }

    @Test
    fun sineEnergyLandsInExpectedBand() {
        val sr = 44100; val n = 4096
        val fb = MelFilterbank(n, sr, nMels = 48, fMin = 30.0, fMax = 12000.0, normalize = false)
        val rfft = RealFft(n)
        val win = Window.hann(n)
        val re = FloatArray(rfft.bins); val im = FloatArray(rfft.bins); val pow = FloatArray(rfft.bins)
        val bands = FloatArray(fb.nMels)
        for (freq in doubleArrayOf(220.0, 1000.0, 3500.0, 9000.0)) {
            val x = Synth.sine(sr, freq, n.toDouble() / sr, amp = 0.5f)
            for (i in 0 until n) x[i] *= win[i]
            rfft.forward(x, re, im)
            rfft.power(re, im, pow)
            fb.apply(pow, bands)
            var best = 0
            for (m in bands.indices) if (bands[m] > bands[best]) best = m
            var nearest = 0
            for (m in 1 until fb.nMels) if (abs(fb.centerFrequencies[m] - freq) < abs(fb.centerFrequencies[nearest] - freq)) nearest = m
            assertTrue(abs(best - nearest) <= 1, "$freq Hz: strongest band $best (${fb.centerFrequencies[best]} Hz), nearest centre $nearest (${fb.centerFrequencies[nearest]} Hz)")
            // The sine's band dominates bands far away by orders of magnitude.
            val far = if (best + 10 < fb.nMels) best + 10 else best - 10
            assertTrue(bands[far] < 1e-4f * bands[best], "$freq Hz: band $far leakage ${bands[far]} vs ${bands[best]}")
            // Energy bookkeeping: total band energy equals sum of power weighted by the filter sum (≈ 1 inside range).
            var total = 0.0
            for (v in bands) total += v
            var powSum = 0.0
            for (k in 0 until rfft.bins) powSum += pow[k]
            assertTrue(total > 0.5 * powSum && total <= 1.001 * powSum, "$freq Hz: total band energy $total vs power $powSum")
        }
        // apply() on frames
        val frames = Array(3) { pow.copyOf() }
        val out = fb.apply(frames)
        assertEquals(3, out.size)
        for (t in 0 until 3) for (m in 0 until fb.nMels) assertEquals(bands[m], out[t][m], 0f)
    }
}
