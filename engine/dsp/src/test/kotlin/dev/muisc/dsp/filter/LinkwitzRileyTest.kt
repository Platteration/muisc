package dev.muisc.dsp.filter

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkwitzRileyTest {
    private val fs = 44100
    private val sr = fs.toDouble()

    @Test
    fun lr4SectionsAreMinus6dBAtCornerAndSumToAllPass() {
        val lo = LinkwitzRiley.lowPass(1000.0, sr, 1)
        val hi = LinkwitzRiley.highPass(1000.0, sr, 1)
        assertEquals(-6.0206, lo.magnitudeDb(1000.0, sr), 0.01)
        assertEquals(-6.0206, hi.magnitudeDb(1000.0, sr), 0.01)
        // 24 dB/oct: one octave above the corner the low-pass is about -6 - 24 dB... check the asymptote 2 octaves up.
        assertTrue(lo.magnitudeDb(8000.0, sr) < -60.0)
        val ap = LinkwitzRiley.lr4SumAllPass(1000.0, sr)
        for (k in 0..40) {
            val f = 20.0 * Math.pow(1000.0, k / 40.0)
            val a = lo.response(f, sr); val b = hi.response(f, sr); val r = ap.response(f, sr)
            assertEquals(r[0], a[0] + b[0], 1e-9, "re at $f")
            assertEquals(r[1], a[1] + b[1], 1e-9, "im at $f")
        }
    }

    @Test
    fun twoWayCrossoverSplitSumsToFlatMagnitude() {
        val xo = LinkwitzRileyCrossover(fs, 1, 500.0)
        val win = 16384
        for (f in Measure.binFrequencies(12, 30.0, 18000.0, fs, win)) {
            val x = Synth.sine(fs, f, 1.0)
            val (lo, hi) = xo.split(AudioBuffer.mono(fs, x))
            val sum = FloatArray(x.size) { lo[0][it] + hi[0][it] }
            val start = x.size - win
            val g = Measure.db(Measure.amplitude(sum, fs, f, start, win) / 0.5)
            assertEquals(0.0, g, 0.05, "sum gain at $f Hz")
        }
    }

    @Test
    fun fourBandSumIsMagnitudeFlatWithin0p1dBFrom20HzTo20kHz() {
        val xo = MultibandCrossover(fs, 1, doubleArrayOf(60.0, 250.0, 4000.0))
        assertEquals(4, xo.bands)
        val win = 32768
        val freqs = Measure.binFrequencies(30, 20.0, 20000.0, fs, win)
        for (f in freqs) {
            val x = Synth.sine(fs, f, 1.2)
            val bands = xo.split(AudioBuffer.mono(fs, x))
            val sum = FloatArray(x.size)
            for (b in bands) for (i in sum.indices) sum[i] += b[0][i]
            val start = x.size - win
            val g = Measure.db(Measure.amplitude(sum, fs, f, start, win) / 0.5)
            assertEquals(0.0, g, 0.1, "sum gain at $f Hz")
            assertEquals(0.0, xo.sumMagnitudeDb(f), 1e-6, "analytic sum at $f Hz")
        }
    }

    @Test
    fun bandSumEqualsAllPassReferenceOnWhiteNoise() {
        val xo = MultibandCrossover(fs, 2, doubleArrayOf(60.0, 250.0, 4000.0))
        val n = fs
        val left = Synth.whiteNoise(fs, 1.0, seed = 11)
        val right = Synth.whiteNoise(fs, 1.0, seed = 12)
        val input = AudioBuffer.stereo(fs, left, right)
        val bands = xo.split(input)
        val ref = xo.allPassReference()
        val expected = Array(2) { FloatArray(n) }
        ref.process(input.channels, expected, n)
        for (ch in 0..1) {
            val sum = FloatArray(n)
            for (b in bands) for (i in 0 until n) sum[i] += b[ch][i]
            val err = FloatArray(n) { sum[it] - expected[ch][it] }
            val rel = Measure.db(Measure.rms(err) / Measure.rms(input[ch]))
            assertTrue(rel < -80.0, "sum vs all-pass reference: $rel dB")
            // and the sum is NOT the raw input (phase rotated), so the reference is meaningful
            val raw = FloatArray(n) { sum[it] - input[ch][it] }
            assertTrue(Measure.db(Measure.rms(raw) / Measure.rms(input[ch])) > -20.0)
        }
    }

    @Test
    fun bandsAreSelective() {
        val xo = MultibandCrossover(fs, 1, doubleArrayOf(60.0, 250.0, 4000.0))
        val cases = listOf(25.0 to 0, 120.0 to 1, 1000.0 to 2, 12000.0 to 3)
        val win = 16384
        for ((f, expectBand) in cases) {
            val x = Synth.sine(fs, Math.round(f * win / fs) * sr / win, 1.0)
            val bands = xo.split(AudioBuffer.mono(fs, x))
            val start = x.size - win
            val fSnapped = Math.round(f * win / fs) * sr / win
            val gains = DoubleArray(4) { Measure.db(Measure.amplitude(bands[it][0], fs, fSnapped, start, win) / 0.5) }
            assertEquals(0.0, gains[expectBand], 1.0, "band $expectBand at $f Hz: ${gains.toList()}")
            for (b in 0 until 4) if (b != expectBand) assertTrue(gains[b] < -12.0, "band $b at $f Hz: ${gains.toList()}")
            assertEquals(gains[expectBand], xo.bandMagnitudeDb(expectBand, fSnapped), 0.1)
        }
    }

    @Test
    fun streamingInChunksMatchesOneShot() {
        val x = Synth.whiteNoise(fs, 0.5, seed = 5)
        val n = x.size
        val one = MultibandCrossover(fs, 1, doubleArrayOf(100.0, 1000.0)).split(AudioBuffer.mono(fs, x))
        val stream = MultibandCrossover(fs, 1, doubleArrayOf(100.0, 1000.0), maxBlockFrames = 300)
        val out = Array(3) { arrayOf(FloatArray(n)) }
        var pos = 0
        val sizes = intArrayOf(1, 700, 13, 2048, 999)
        var k = 0
        while (pos < n) {
            val len = minOf(sizes[k % sizes.size], n - pos)
            stream.process(arrayOf(x), out, len, pos)
            pos += len; k++
        }
        for (b in 0 until 3) for (i in 0 until n) assertTrue(abs(one[b][0][i] - out[b][0][i]) < 1e-6f, "band $b sample $i")
    }
}
