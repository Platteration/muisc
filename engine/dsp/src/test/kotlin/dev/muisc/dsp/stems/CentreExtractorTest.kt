package dev.muisc.dsp.stems

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CentreExtractorTest {
    private val sr = 44100

    /** Amplitude of the [freqHz] component over [start, start+len) via a single-bin DFT. */
    private fun amplitude(x: FloatArray, freqHz: Double, start: Int, len: Int): Double {
        val w = 2.0 * PI * freqHz / sr
        var re = 0.0; var im = 0.0
        for (i in 0 until len) { val v = x[start + i].toDouble(); re += v * cos(w * (start + i)); im -= v * sin(w * (start + i)) }
        return 2.0 * sqrt(re * re + im * im) / len
    }

    private fun snrDb(reference: FloatArray, estimate: FloatArray): Double {
        var sig = 0.0; var err = 0.0
        for (i in reference.indices) { val d = reference[i] - estimate[i]; sig += reference[i].toDouble() * reference[i]; err += d.toDouble() * d }
        return 10.0 * log10(sig / maxOf(err, 1e-30))
    }

    /** Centred 440 Hz (amp 0.3), hard-left 2 kHz (amp 0.3), hard-right 3.3 kHz (amp 0.2). */
    private fun scene(): AudioBuffer {
        val centre = Synth.sine(sr, 440.0, 2.0, 0.3f)
        val left = Synth.sine(sr, 2000.0, 2.0, 0.3f)
        val right = Synth.sine(sr, 3300.0, 2.0, 0.2f)
        val l = FloatArray(centre.size) { centre[it] + left[it] }
        val r = FloatArray(centre.size) { centre[it] + right[it] }
        return AudioBuffer.stereo(sr, l, r)
    }

    @Test
    fun centredComponentIsKeptAndHardPannedRemoved() {
        val x = scene()
        val y = CentreExtractor().extract(x)
        val start = (0.5 * sr).toInt(); val len = sr // 1 s interior window: 440 Hz has an integer number of cycles
        for (c in 0 until 2) {
            val a440 = amplitude(y[c], 440.0, start, len)
            assertEquals(0.3, a440, 0.015, "ch$c centred amplitude")
        }
        val a2k = amplitude(y[0], 2000.0, start, len)
        val a3k = amplitude(y[1], 3300.0, start, len)
        assertTrue(20 * log10(a2k / 0.3) < -20.0, "hard-left 2 kHz attenuation ${20 * log10(a2k / 0.3)} dB")
        assertTrue(20 * log10(a3k / 0.2) < -20.0, "hard-right 3.3 kHz attenuation ${20 * log10(a3k / 0.2)} dB")
    }

    @Test
    fun strengthZeroIsIdentityAndHalfStrengthHalvesPannedContent() {
        val x = scene()
        val id = CentreExtractor(strength = 0f).extract(x)
        assertTrue(snrDb(x[0], id[0]) > 80.0 && snrDb(x[1], id[1]) > 80.0, "strength 0 must be a perfect round trip")
        val half = CentreExtractor(strength = 0.5f).extract(x)
        val start = (0.5 * sr).toInt(); val len = sr
        // sim = 0 for the hard-panned tone -> mask = 1 - 0.5 = 0.5 -> amplitude halves.
        assertEquals(0.15, amplitude(half[0], 2000.0, start, len), 0.01)
        // sim = 1 for the centred tone -> untouched.
        assertEquals(0.3, amplitude(half[0], 440.0, start, len), 0.01)
    }

    @Test
    fun antiPhaseContentIsRemovedAndChannelApiMatchesBufferApi() {
        val s = Synth.sine(sr, 1000.0, 1.0, 0.4f)
        val l = s.copyOf(); val r = FloatArray(s.size) { -s[it] } // "wide" anti-phase tone: sim = -1 -> 0
        val x = AudioBuffer.stereo(sr, l, r)
        val ce = CentreExtractor()
        val y = ce.extract(x)
        val start = (0.25 * sr).toInt(); val len = sr / 2
        assertTrue(amplitude(y[0], 1000.0, start, len) < 0.004, "anti-phase tone must vanish")
        val ol = FloatArray(s.size); val or = FloatArray(s.size)
        ce.extractChannels(l, r, ol, or)
        for (i in s.indices) { assertEquals(y[0][i], ol[i], 0f); assertEquals(y[1][i], or[i], 0f) }
        // Odd lengths / empty input are handled.
        val e = ce.extract(AudioBuffer.silence(sr, 2, 0))
        assertEquals(0, e.frames)
    }
}
