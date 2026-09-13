package dev.muisc.dsp.loudness

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import kotlin.math.PI
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TruePeakTest {
    /**
     * 20 ms linear fades at both ends. A signal that starts or stops abruptly is a step for the reconstruction
     * filter, and its true peak legitimately overshoots by up to ~1.1 dB (Gibbs); the analytic expectations below
     * are for the steady-state signals.
     */
    private fun faded(x: FloatArray, sr: Int): FloatArray {
        Synth.fadeInInPlace(x, sr / 50); Synth.fadeOutInPlace(x, sr / 50); return x
    }

    @Test
    fun abruptStartOvershootsLikeAReconstructionFilter() {
        val sr = 48000
        val dc = AudioBuffer.mono(sr, FloatArray(sr) { 0.5f })
        val tp = TruePeak.measureDbtp(dc)
        // Step response overshoot of the Kaiser-sinc interpolator: 13.1 % at the half-sample phase.
        assertEquals(-6.02 + 20 * log10(1.131), tp, 0.03)
        assertEquals(-6.02, TruePeak.measureDbtp(AudioBuffer.mono(sr, faded(FloatArray(sr) { 0.5f }, sr))), 0.005)
    }

    @Test
    fun interSampleOverIsDetected() {
        // fs/4 sine with 45 degree phase: every sample is +/- 0.7071 (sample peak -3.01 dBFS), true peak 0 dBTP.
        val sr = 44100
        val x = faded(Synth.sine(sr, 11025.0, 1.0, amp = 1f, phase = PI / 4), sr)
        val buf = AudioBuffer.mono(sr, x)
        val samplePeakDb = 20.0 * log10(buf.peak().toDouble())
        assertEquals(-3.01, samplePeakDb, 0.02)
        val tp = TruePeak.measureDbtp(buf)
        assertEquals(0.0, tp, 0.1)
        assertTrue(tp > samplePeakDb + 2.5)
    }

    @Test
    fun dcAndFullScaleSineReadTheirAnalyticLevels() {
        val sr = 48000
        val dc = AudioBuffer.stereo(sr, faded(FloatArray(sr) { 0.5f }, sr), faded(FloatArray(sr) { -0.5f }, sr))
        assertEquals(-6.02, TruePeak.measureDbtp(dc), 0.01)
        val sine = AudioBuffer.mono(sr, faded(Synth.sine(sr, 997.0, 1.0, amp = 1f), sr))
        assertEquals(0.0, TruePeak.measureDbtp(sine), 0.02)
        // Silence is -inf; the true peak is never below the sample peak.
        assertEquals(Double.NEGATIVE_INFINITY, TruePeak.measureDbtp(AudioBuffer.silence(sr, 1, 1000)))
        val noise = AudioBuffer.mono(sr, faded(Synth.whiteNoise(sr, 1.0, amp = 0.9f, seed = 7), sr))
        assertTrue(TruePeak.measureLinear(noise) >= noise.peak())
    }

    @Test
    fun streamingMatchesOneShotAndTracksChannelsSeparately() {
        val sr = 44100
        val l = faded(Synth.sine(sr, 11025.0, 0.5, amp = 0.5f, phase = PI / 4), sr)
        val r = faded(Synth.sine(sr, 1000.0, 0.5, amp = 0.25f), sr)
        val buf = AudioBuffer.stereo(sr, l, r)
        val oneShot = TruePeak.measureLinear(buf)
        val meter = TruePeak(2, maxBlockFrames = 300)
        var pos = 0
        var step = 7
        while (pos < buf.frames) {
            val n = minOf(step, buf.frames - pos)
            meter.process(buf.channels, n, pos)
            pos += n
            step = step * 7 % 1000 + 1
        }
        meter.flush()
        assertEquals(oneShot, meter.peakLinear, 1e-6f)
        assertEquals(-6.02, meter.channelPeakDbtp(0), 0.1)
        assertEquals(-12.04, meter.channelPeakDbtp(1), 0.05)
        assertEquals(buf.frames.toLong() + meter.latencyFrames, meter.framesProcessed)
        meter.reset()
        assertEquals(0f, meter.peakLinear)
    }

    @Test
    fun envelopeIsDelayedLinkedAndCoversInterSamplePeaks() {
        val sr = 44100
        val frames = 2000
        val l = Synth.sine(sr, 11025.0, frames.toDouble() / sr, amp = 1f, phase = PI / 4)
        val r = FloatArray(frames)
        r[1000] = 0.9f // a lone impulse on the right channel
        val meter = TruePeak(2)
        val env = FloatArray(frames + meter.latencyFrames)
        meter.processEnvelope(arrayOf(l, r), frames, env)
        meter.flush(env, frames)
        val d = meter.latencyFrames
        assertEquals(16, d)
        // The impulse is visible at its own (delayed) position; the interpolated ringing around it stays below it.
        assertTrue(env[1000 + d] >= 0.9f)
        // Steady state of the sine: the envelope is the true peak (~1.0), not the sample peak (0.707).
        for (i in 100 until 900) assertEquals(1.0f, env[i + d], 0.01f, "envelope at $i")
        // Before any input arrived the envelope is (near) zero.
        assertTrue(env[0] < 1e-3f)
    }
}
