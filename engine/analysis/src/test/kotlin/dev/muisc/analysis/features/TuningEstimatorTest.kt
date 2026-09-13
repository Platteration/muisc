package dev.muisc.analysis.features

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuningEstimatorTest {
    private val sr = 22050

    private fun chord(cents: Double, seconds: Double = 4.0): FloatArray {
        val midi = intArrayOf(57, 60, 64, 67, 72, 76) // A3 C4 E4 G4 C5 E5
        val out = FloatArray((seconds * sr).toInt())
        for (m in midi) {
            val f = Synth.midiToHz(m.toDouble()) * 2.0.pow(cents / 1200.0)
            val s = Synth.sine(sr, f, seconds, amp = 0.12f)
            for (i in out.indices) out[i] += s[i]
        }
        return out
    }

    @Test
    fun sharpAndFlatChords_estimatedWithin3Cents() {
        val est = TuningEstimator()
        for (cents in doubleArrayOf(0.0, 20.0, -35.0, 45.0)) {
            val r = est.estimate(AudioBuffer.mono(sr, chord(cents)))
            println("tuning %+.0f cents -> %.1f (conf %.2f)".format(cents, r.cents, r.confidence))
            assertTrue(abs(r.cents - cents) <= 3.0, "expected $cents got ${r.cents}")
            assertTrue(r.confidence > 0.5f, "confidence ${r.confidence}")
        }
    }

    @Test
    fun silenceAndNoise_returnZeroWithLowConfidence() {
        val est = TuningEstimator()
        assertEquals(TuningEstimate.NONE, est.estimate(AudioBuffer.silence(sr, 1, sr)))
        assertEquals(TuningEstimate.NONE, est.estimate(SpectralPeaks.empty(0.1)))
        val noise = est.estimate(AudioBuffer.mono(sr, Synth.whiteNoise(sr, 4.0, seed = 9)))
        assertTrue(noise.confidence < 0.4f, "noise confidence ${noise.confidence}")
        assertTrue(noise.cents in -50f..50f)
    }
}
