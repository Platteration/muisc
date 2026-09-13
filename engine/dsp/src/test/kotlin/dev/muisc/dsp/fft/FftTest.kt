package dev.muisc.dsp.fft

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {
    /** Naive O(N^2) DFT in double precision as the reference. */
    private fun naiveDft(re: FloatArray, im: FloatArray): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val outR = DoubleArray(n); val outI = DoubleArray(n)
        for (k in 0 until n) {
            var sr = 0.0; var si = 0.0
            for (t in 0 until n) {
                val ang = -2.0 * PI * k * t / n
                val c = cos(ang); val s = sin(ang)
                sr += re[t] * c - im[t] * s
                si += re[t] * s + im[t] * c
            }
            outR[k] = sr; outI[k] = si
        }
        return outR to outI
    }

    @Test
    fun matchesNaiveDftForRandomInput() {
        for (n in intArrayOf(16, 64, 1024)) {
            val rnd = Random(42 + n)
            val re = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
            val im = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
            val (refR, refI) = naiveDft(re, im)
            val fft = Fft(n)
            fft.forward(re, im)
            var maxErr = 0.0
            for (k in 0 until n) {
                maxErr = maxOf(maxErr, abs(re[k] - refR[k]), abs(im[k] - refI[k]))
            }
            assertTrue(maxErr < 1e-4, "N=$n max abs error vs naive DFT = $maxErr")
        }
    }

    @Test
    fun inverseOfForwardIsIdentity() {
        for (n in intArrayOf(16, 256, 4096, 65536)) {
            val rnd = Random(7)
            val re = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
            val im = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
            val re0 = re.copyOf(); val im0 = im.copyOf()
            val fft = Fft(n)
            fft.forward(re, im)
            fft.inverse(re, im)
            var maxErr = 0f
            for (i in 0 until n) maxErr = maxOf(maxErr, abs(re[i] - re0[i]), abs(im[i] - im0[i]))
            assertTrue(maxErr < 1e-4f, "N=$n round-trip error $maxErr")
        }
    }

    @Test
    fun impulseGivesFlatSpectrum() {
        val n = 128
        val re = FloatArray(n); val im = FloatArray(n)
        re[0] = 1f
        Fft(n).forward(re, im)
        for (k in 0 until n) {
            assertEquals(1f, re[k], 1e-6f, "re[$k]")
            assertEquals(0f, im[k], 1e-6f, "im[$k]")
        }
        // A delayed impulse has unit magnitude and linear phase.
        val re2 = FloatArray(n); val im2 = FloatArray(n)
        re2[3] = 1f
        Fft(n).forward(re2, im2)
        for (k in 0 until n) {
            val mag = kotlin.math.sqrt(re2[k] * re2[k] + im2[k] * im2[k])
            assertEquals(1f, mag, 1e-5f, "|X[$k]|")
            assertEquals(cos(-2.0 * PI * 3 * k / n).toFloat(), re2[k], 1e-5f)
        }
    }

    @Test
    fun sineAtBinKHasEnergyOnlyInBinK() {
        val n = 512
        val k0 = 37
        val re = FloatArray(n) { sin(2.0 * PI * k0 * it / n).toFloat() }
        val im = FloatArray(n)
        Fft(n).forward(re, im)
        // sin(2πk0 n/N) → X[k0] = -i N/2, X[N-k0] = +i N/2, everything else 0.
        for (k in 0 until n) {
            val expectedRe = 0f
            val expectedIm = when (k) { k0 -> -n / 2f; n - k0 -> n / 2f; else -> 0f }
            assertEquals(expectedRe, re[k], 1e-3f, "re[$k]")
            assertEquals(expectedIm, im[k], 1e-3f, "im[$k]")
        }
    }

    @Test
    fun parsevalHolds() {
        val n = 2048
        val rnd = Random(3)
        val re = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val im = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        var timeE = 0.0
        for (i in 0 until n) timeE += re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
        Fft(n).forward(re, im)
        var freqE = 0.0
        for (i in 0 until n) freqE += re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
        freqE /= n
        assertEquals(timeE, freqE, timeE * 1e-5, "Parseval: time $timeE vs freq $freqE")
    }

    @Test
    fun rejectsInvalidSizes() {
        assertFailsWith<IllegalArgumentException> { Fft(1000) }
        assertFailsWith<IllegalArgumentException> { Fft(131072) }
        assertFailsWith<IllegalArgumentException> { Fft(0) }
    }
}

class RealFftTest {
    @Test
    fun matchesComplexFftOnRealInput() {
        for (n in intArrayOf(16, 32, 1024, 4096)) {
            val rnd = Random(11 + n)
            val x = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
            val cre = x.copyOf(); val cim = FloatArray(n)
            Fft(n).forward(cre, cim)
            val rfft = RealFft(n)
            assertEquals(n / 2 + 1, rfft.bins)
            val re = FloatArray(rfft.bins); val im = FloatArray(rfft.bins)
            rfft.forward(x, re, im)
            var maxErr = 0f
            for (k in 0 until rfft.bins) maxErr = maxOf(maxErr, abs(re[k] - cre[k]), abs(im[k] - cim[k]))
            assertTrue(maxErr < 1e-4f, "N=$n real vs complex FFT max error $maxErr")
        }
    }

    @Test
    fun inverseRoundTrip() {
        for (n in intArrayOf(16, 256, 8192)) {
            val rnd = Random(5)
            val x = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
            val rfft = RealFft(n)
            val re = FloatArray(rfft.bins); val im = FloatArray(rfft.bins)
            rfft.forward(x, re, im)
            val y = FloatArray(n)
            rfft.inverse(re, im, y)
            var maxErr = 0f
            for (i in 0 until n) maxErr = maxOf(maxErr, abs(y[i] - x[i]))
            assertTrue(maxErr < 1e-5f, "N=$n round trip error $maxErr")
        }
    }

    @Test
    fun magnitudeAndPowerOfSine() {
        val n = 1024
        val k0 = 100
        val amp = 0.75f
        val x = FloatArray(n) { (amp * cos(2.0 * PI * k0 * it / n)).toFloat() }
        val rfft = RealFft(n)
        val re = FloatArray(rfft.bins); val im = FloatArray(rfft.bins)
        rfft.forward(x, re, im)
        val mag = FloatArray(rfft.bins); val pow = FloatArray(rfft.bins)
        rfft.magnitude(re, im, mag)
        rfft.power(re, im, pow)
        // cos at bin k0: |X[k0]| = amp*N/2, all others 0.
        for (k in 0 until rfft.bins) {
            val expected = if (k == k0) amp * n / 2f else 0f
            assertEquals(expected, mag[k], 2e-3f, "|X[$k]|")
            assertEquals(expected * expected, pow[k], 2f, "|X[$k]|^2")
        }
        // DC bin: mean * N.
        val dc = FloatArray(n) { 0.25f }
        rfft.forward(dc, re, im)
        assertEquals(0.25f * n, re[0], 1e-3f)
        assertEquals(0f, im[0], 1e-3f)
        assertEquals(0f, re[n / 2], 1e-3f)
    }
}
