package dev.muisc.dsp.loudness

import dev.muisc.audio.synth.Synth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KWeightingTest {

    @Test
    fun shelfCoefficientsAt48kMatchBs1770Table() {
        val c = KWeighting.highShelf(48000.0)
        assertEquals(1.53512485958697, c.b0, 1e-6)
        assertEquals(-2.69169618940638, c.b1, 1e-6)
        assertEquals(1.19839281085285, c.b2, 1e-6)
        assertEquals(-1.69065929318241, c.a1, 1e-6)
        assertEquals(0.73248077421585, c.a2, 1e-6)
        assertTrue(c.isStable)
    }

    @Test
    fun highPassCoefficientsAt48kMatchBs1770Table() {
        val c = KWeighting.highPass(48000.0)
        assertEquals(1.0, c.b0, 1e-12)
        assertEquals(-2.0, c.b1, 1e-12)
        assertEquals(1.0, c.b2, 1e-12)
        assertEquals(-1.99004745483398, c.a1, 1e-6)
        assertEquals(0.99007225036621, c.a2, 1e-6)
        assertTrue(c.isStable)
    }

    @Test
    fun responseShapeIsSampleRateIndependent() {
        // The -0.691 dB loudness offset compensates the weighting's gain at 1 kHz: ~ +0.69..0.70 dB at every rate.
        for (sr in doubleArrayOf(44100.0, 48000.0, 96000.0)) {
            assertEquals(0.691, KWeighting.magnitudeDb(1000.0, sr), 0.02, "1 kHz gain at $sr")
            // Shelf: +4 dB well above the corner; RLB high-pass (Q = 0.5, two coincident real poles): -6 dB at its
            // 38 Hz corner, deep cut at 10 Hz.
            assertEquals(4.0, KWeighting.magnitudeDb(10000.0, sr), 0.15, "10 kHz gain at $sr")
            assertEquals(-6.0, KWeighting.magnitudeDb(38.1, sr), 0.3, "38 Hz gain at $sr")
            assertTrue(KWeighting.magnitudeDb(10.0, sr) < -20.0, "10 Hz gain at $sr")
            // 44.1 and 48 kHz agree with each other closely at 1 kHz (the loudness tests rely on this).
        }
        assertEquals(KWeighting.magnitudeDb(1000.0, 48000.0), KWeighting.magnitudeDb(1000.0, 44100.0), 0.01)
    }

    @Test
    fun newFilterProducesTheAnalyticGainOnASine() {
        val sr = 44100
        val f = KWeighting.newFilter(2, sr)
        for (freq in doubleArrayOf(100.0, 1000.0, 5000.0)) {
            f.reset()
            val x = Synth.sine(sr, freq, 2.0, amp = 0.5f)
            val y = FloatArray(x.size)
            f.process(arrayOf(x, x.copyOf()), arrayOf(y, FloatArray(x.size)), x.size)
            // Lock-in amplitude over the second half (filter settled), compared to the analytic magnitude.
            val start = x.size / 2
            val len = x.size - start
            val w = 2.0 * PI * freq / sr
            var re = 0.0; var im = 0.0
            for (i in 0 until len) { val v = y[start + i].toDouble(); re += v * cos(w * (start + i)); im -= v * sin(w * (start + i)) }
            val amp = 2.0 * sqrt(re * re + im * im) / len
            val measuredDb = 20.0 * log10(amp / 0.5)
            assertEquals(KWeighting.magnitudeDb(freq, sr.toDouble()), measuredDb, 0.02, "gain at $freq Hz")
        }
    }
}
