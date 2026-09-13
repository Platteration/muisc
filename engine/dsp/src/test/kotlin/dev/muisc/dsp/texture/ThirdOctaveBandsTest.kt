package dev.muisc.dsp.texture

import dev.muisc.audio.synth.Synth
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThirdOctaveBandsTest {
    private val sr = 44100

    @Test
    fun bandIndexingFollowsIso266() {
        assertEquals(31, ThirdOctaveBands.BANDS)
        assertEquals(17, ThirdOctaveBands.bandOf(1000.0))
        assertEquals(0, ThirdOctaveBands.bandOf(20.0))
        assertEquals(30, ThirdOctaveBands.bandOf(20000.0))
        assertEquals(17, ThirdOctaveBands.bandOf(1100.0))
        assertEquals(18, ThirdOctaveBands.bandOf(1150.0))
        assertEquals(-1, ThirdOctaveBands.bandOf(0.0))
        assertEquals(30, ThirdOctaveBands.bandOf(22050.0))
        for (b in 0 until 31) {
            assertEquals(ThirdOctaveBands.CENTRES[b], ThirdOctaveBands.exactCentre(b), ThirdOctaveBands.CENTRES[b] * 0.012, "centre $b")
            assertTrue(ThirdOctaveBands.lowerEdge(b) < ThirdOctaveBands.exactCentre(b) && ThirdOctaveBands.upperEdge(b) > ThirdOctaveBands.exactCentre(b))
        }
        // Interpolation to bins: exact at band centres, monotone between.
        val env = FloatArray(31) { it.toFloat() }
        val bins = ThirdOctaveBands.interpolateToBins(env, 8192, sr)
        val k1k = Math.round(1000.0 * 8192 / sr).toInt()
        assertEquals(17f, bins[k1k], 0.15f)
        assertEquals(0f, bins[0], 0f)
        assertEquals(30f, bins[bins.size - 1], 0f)
    }

    @Test
    fun fullScaleSineReadsMinus3dBInItsBand() {
        val x = Synth.sine(sr, 1000.0, 2.0, amp = 1.0f)
        val db = ThirdOctaveBands.measureDb(x, sr)
        assertEquals(-3.01f, db[17], 0.3f, "1 kHz band level ${db[17]}")
        for (b in 0 until 31) if (b != 17) assertTrue(db[b] < -40f, "band $b leakage ${db[b]}")
        // Level scales with amplitude.
        val half = ThirdOctaveBands.measureDb(Synth.sine(sr, 1000.0, 2.0, amp = 0.5f), sr)
        assertEquals(db[17] - 6.02f, half[17], 0.1f)
    }

    @Test
    fun unitRmsWhiteNoiseFollowsBandwidth() {
        val rnd = kotlin.random.Random(21)
        val amp = Math.sqrt(3.0).toFloat()
        val x = FloatArray(10 * sr) { amp * (rnd.nextFloat() * 2f - 1f) }
        val db = ThirdOctaveBands.measureDb(x, sr)
        for (b in ThirdOctaveBands.bandOf(200.0)..ThirdOctaveBands.bandOf(10000.0)) {
            val bw = ThirdOctaveBands.upperEdge(b) - ThirdOctaveBands.lowerEdge(b)
            val expected = 10.0 * log10(bw / (sr / 2.0))
            assertEquals(expected, db[b].toDouble(), 1.0, "band $b (${ThirdOctaveBands.CENTRES[b]} Hz)")
        }
        // Sum of band powers is the total power (0 dB for unit RMS).
        var p = 0.0
        for (b in 0 until 31) p += Math.pow(10.0, db[b] / 10.0)
        assertEquals(0.0, 10.0 * log10(p), 0.3)
    }
}
