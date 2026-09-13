package dev.muisc.dsp.window

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowTest {
    @Test
    fun hannIsColaAt50And75PercentOverlap() {
        val n = 1024
        val w = Window.hann(n)
        val s50 = Window.overlapAddSum(w, n / 2)
        val s75 = Window.overlapAddSum(w, n / 4)
        for (v in s50) assertEquals(1f, v, 1e-5f, "Hann OLA at hop N/2 must be 1")
        for (v in s75) assertEquals(2f, v, 1e-5f, "Hann OLA at hop N/4 must be 2")
        assertTrue(Window.isCola(w, n / 2, 1e-5f))
        assertTrue(Window.isCola(w, n / 4, 1e-5f))
        assertTrue(Window.isCola(w, n / 8, 1e-5f))
        // A non-COLA hop (not a divisor of N) must be detected.
        assertFalse(Window.isCola(w, 700, 1e-3f))
        // The symmetric Hann is *not* exactly COLA at N/2.
        assertTrue(Window.colaDeviation(Window.hann(n, periodic = false), n / 2) > 1e-3f)
    }

    @Test
    fun sqrtHannSquaredIsColaAt75Percent() {
        val n = 2048
        val w = Window.sqrtHann(n)
        val sq = FloatArray(n) { w[it] * w[it] }
        val s = Window.overlapAddSum(sq, n / 4)
        for (v in s) assertEquals(2f, v, 1e-5f)
        assertTrue(Window.isCola(sq, n / 2, 1e-5f))
        // and the window itself equals sqrt of Hann pointwise
        val h = Window.hann(n)
        for (i in 0 until n) assertEquals(h[i], w[i] * w[i], 1e-6f)
    }

    @Test
    fun hammingAndBlackmanHarrisShapes() {
        val n = 512
        val hm = Window.hamming(n, periodic = false)
        assertEquals(0.08f, hm[0], 1e-6f)
        assertEquals(0.08f, hm[n - 1], 1e-6f)
        assertEquals(1f, hm[(n - 1) / 2 + 1], 1e-4f)
        val bh = Window.blackmanHarris(n, periodic = false)
        // a0 - a1 + a2 - a3 = 6e-5 at the ends, a0 + a1 + a2 + a3 = 1 at the centre
        assertEquals(6e-5f, bh[0], 1e-6f)
        assertEquals(1f, bh[n / 2], 1e-3f)
        // Blackman-Harris is COLA-ish at hop N/8 within 1e-3? Not exactly; just check hop N/4 sums are smooth and positive.
        for (v in Window.overlapAddSum(Window.blackmanHarris(n), n / 4)) assertTrue(v > 1.3f && v < 1.6f)
    }

    @Test
    fun kaiserEndpointsAndCentre() {
        val beta = 8.6
        val n = 257
        val w = Window.kaiser(n, beta)
        val expectedEnd = (1.0 / Window.besselI0(beta)).toFloat()
        assertEquals(expectedEnd, w[0], 1e-7f)
        assertEquals(expectedEnd, w[n - 1], 1e-7f)
        assertEquals(1f, w[n / 2], 1e-7f)
        // symmetric and monotone towards the centre
        for (i in 0 until n / 2) {
            assertEquals(w[i], w[n - 1 - i], 1e-7f)
            assertTrue(w[i] <= w[i + 1])
        }
        // I0 sanity: I0(0)=1, I0(1)=1.2660658777520084, I0(5)=27.239871823604442
        assertEquals(1.0, Window.besselI0(0.0), 1e-15)
        assertEquals(1.2660658777520084, Window.besselI0(1.0), 1e-12)
        assertEquals(27.239871823604442, Window.besselI0(5.0), 1e-9)
        // beta = 0 is rectangular
        for (v in Window.kaiser(64, 0.0)) assertEquals(1f, v, 0f)
    }

    @Test
    fun tukeyLimits() {
        val n = 300
        val rect = Window.rectangular(n)
        val t0 = Window.tukey(n, 0.0)
        val t1 = Window.tukey(n, 1.0)
        val hann = Window.hann(n)
        for (i in 0 until n) {
            assertEquals(rect[i], t0[i], 0f)
            assertEquals(hann[i], t1[i], 1e-6f, "tukey(1) vs hann at $i")
        }
        val t1s = Window.tukey(n, 1.0, periodic = false)
        val hannS = Window.hann(n, periodic = false)
        for (i in 0 until n) assertEquals(hannS[i], t1s[i], 1e-6f)
        // alpha = 0.5: flat middle half, tapered quarters
        val t5 = Window.tukey(n, 0.5)
        assertEquals(0f, t5[0], 1e-7f)
        assertEquals(1f, t5[n / 2], 1e-7f)
        assertEquals(1f, t5[n / 4], 1e-6f)
        assertTrue(t5[n / 8] > 0.4f && t5[n / 8] < 0.6f)
        // Tukey is COLA when the hop makes the cosine tapers overlap exactly: hop = N - taper = N (1 - alpha/2).
        val s = Window.overlapAddSum(t5, n - n / 4)
        for (v in s) assertEquals(1f, v, 1e-6f)
    }

    @Test
    fun windowOfEnumMatchesGenerators() {
        val n = 128
        for (t in WindowType.entries) {
            val a = Window.of(t, n)
            val b = when (t) {
                WindowType.RECTANGULAR -> Window.rectangular(n)
                WindowType.HANN -> Window.hann(n)
                WindowType.SQRT_HANN -> Window.sqrtHann(n)
                WindowType.HAMMING -> Window.hamming(n)
                WindowType.BLACKMAN_HARRIS -> Window.blackmanHarris(n)
            }
            assertEquals(n, a.size)
            for (i in 0 until n) assertTrue(abs(a[i] - b[i]) == 0f)
        }
    }
}
