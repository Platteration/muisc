package dev.muisc.dsp.loudness

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GainNormaliserTest {
    private val sr = 44100

    private fun stereoSine(levelDbfs: Double, seconds: Double = 10.0): AudioBuffer {
        val l = Synth.sine(sr, 1000.0, seconds, amp = 10.0.pow(levelDbfs / 20.0).toFloat())
        return AudioBuffer.stereo(sr, l, l.copyOf())
    }

    @Test
    fun quietSineIsBroughtToTarget() {
        val buf = stereoSine(-33.0)
        val plan = GainNormaliser.normaliseInPlace(buf, targetLufs = -23.0, ceilingDbtp = -1.0)
        assertEquals(-33.0, plan.inputLufs, 0.1)
        assertEquals(10.0, plan.gainDb, 0.1)
        assertFalse(plan.peakLimited)
        assertEquals(-23.0, plan.outputLufs, 0.1)
        assertEquals(-23.0, LoudnessMeter.integratedLufs(buf), 0.1)
        assertEquals(-23.0, TruePeak.measureDbtp(buf), 0.1)
        assertEquals(plan.gainLinear.toDouble(), 10.0.pow(plan.gainDb / 20.0), 1e-6)
    }

    @Test
    fun peakyQuietSignalIsCappedByTruePeakCeiling() {
        // Sparse clicks: low integrated loudness (~ -30 LUFS) but a ~ -12 dBTP peak.
        val x = Synth.clickTrack(sr, 60.0, 10.0, amp = 0.25f)
        val buf = AudioBuffer.mono(sr, x)
        val before = GainNormaliser.plan(buf, targetLufs = -14.0, ceilingDbtp = -1.0)
        assertTrue(before.inputLufs < -20.0, "clicks should be quiet: ${before.inputLufs}")
        assertEquals(-12.1, before.inputTruePeakDbtp, 0.5)
        assertTrue(before.peakLimited)
        assertEquals(-1.0 - before.inputTruePeakDbtp, before.gainDb, 1e-9)
        assertTrue(before.gainDb < -14.0 - before.inputLufs)
        GainNormaliser.applyInPlace(buf, before)
        assertEquals(-1.0, TruePeak.measureDbtp(buf), 0.05)
        assertTrue(LoudnessMeter.integratedLufs(buf) < -14.0)
        assertEquals(before.outputLufs, LoudnessMeter.integratedLufs(buf), 0.05)
    }

    @Test
    fun loudSignalIsTurnedDownAndSilenceIsLeftAlone() {
        val loud = stereoSine(-6.0, seconds = 5.0)
        val p = GainNormaliser.normaliseInPlace(loud, targetLufs = -14.0)
        assertEquals(-8.0, p.gainDb, 0.1)
        assertFalse(p.peakLimited)
        assertEquals(-14.0, LoudnessMeter.integratedLufs(loud), 0.1)

        val silence = AudioBuffer.silence(sr, 2, sr * 2)
        val s = GainNormaliser.normaliseInPlace(silence, targetLufs = -14.0)
        assertEquals(0.0, s.gainDb)
        assertFalse(s.peakLimited)
        assertEquals(Double.NEGATIVE_INFINITY, s.inputLufs)

        // Pure arithmetic variant.
        val cap = GainNormaliser.plan(inputLufs = -20.0, inputTruePeakDbtp = -2.0, targetLufs = -14.0, ceilingDbtp = -1.0)
        assertEquals(1.0, cap.gainDb, 1e-12)
        assertTrue(cap.peakLimited)
        assertEquals(-19.0, cap.outputLufs, 1e-12)
        assertEquals(-1.0, cap.outputTruePeakDbtp, 1e-12)
    }
}
