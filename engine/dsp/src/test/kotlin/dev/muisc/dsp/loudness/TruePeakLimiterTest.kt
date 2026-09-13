package dev.muisc.dsp.loudness

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.qa.ArtifactDetector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TruePeakLimiterTest {
    private val sr = 44100

    /** Raised-cosine window weight: 0 before [start], 1 between the ramps, 0 after [end]; ramps of [ramp] frames. */
    private fun window(i: Int, start: Int, end: Int, ramp: Int): Float = when {
        i < start || i >= end -> 0f
        i < start + ramp -> (0.5 - 0.5 * cos(PI * (i - start) / ramp)).toFloat()
        i >= end - ramp -> (0.5 - 0.5 * cos(PI * (end - i) / ramp)).toFloat()
        else -> 1f
    }

    /**
     * A 1 kHz stereo sine at 0.5 with a 2 s section boosted to +6 dBFS (amplitude 2.0) and a 100 ms fs/4 burst
     * with an inter-sample over (sample peak 0.85, true peak 1.2). Section edges use 10 ms raised-cosine ramps
     * so that the *input* is click-free and any click in the output is the limiter's.
     */
    private fun overs(): AudioBuffer {
        val l = Synth.sine(sr, 1000.0, 6.0, amp = 0.5f)
        val r = Synth.sine(sr, 1000.0, 6.0, amp = 0.5f, phase = 0.3)
        val ramp = sr / 100
        for (i in 2 * sr - ramp until 4 * sr + ramp) {
            val g = 1f + 3f * window(i, 2 * sr - ramp, 4 * sr + ramp, ramp)
            l[i] *= g; r[i] *= g
        }
        val burst = Synth.sine(sr, sr / 4.0, 0.1 + 0.02, amp = 1.2f, phase = PI / 4)
        for (i in burst.indices) {
            val w = window(i, 0, burst.size, ramp)
            val k = 5 * sr - ramp + i
            l[k] = l[k] * (1f - w) + burst[i] * w
            r[k] = r[k] * (1f - w) + burst[i] * w
        }
        return AudioBuffer.stereo(sr, l, r)
    }

    @Test
    fun outputTruePeakNeverExceedsCeiling() {
        val buf = overs()
        assertTrue(TruePeak.measureDbtp(buf) > 5.9)
        val reduction = TruePeakLimiter.processInPlace(buf, ceilingDbtp = -1.0)
        val tp = TruePeak.measureDbtp(buf)
        assertTrue(tp <= -1.0 + 0.05, "true peak after limiting: $tp dBTP")
        assertTrue(tp > -1.3, "limiter should not over-reduce: $tp dBTP")
        assertEquals(7.0 + 0.05, reduction, 0.1)
        // The quiet section (-6 dBFS) is untouched: 0.5 amplitude sine survives bit-for-bit at 1 s.
        val ref = Synth.sine(sr, 1000.0, 6.0, amp = 0.5f)
        for (i in sr until sr + 1000) assertEquals(ref[i], buf[0][i], "sample $i")
        // Stereo link: both channels get the same gain, so their ratio in the limited part is preserved.
        val orig = overs()
        for (i in 3 * sr until 3 * sr + 200) {
            if (kotlin.math.abs(orig[1][i]) > 0.5f) assertEquals(orig[0][i] / orig[1][i], buf[0][i] / buf[1][i], 1e-3f)
        }
    }

    @Test
    fun compliantSignalIsBitIdenticalAfterDelayCompensation() {
        val l = Synth.sine(sr, 440.0, 3.0, amp = 0.5f)
        val r = Synth.whiteNoise(sr, 3.0, amp = 0.3f, seed = 3)
        val buf = AudioBuffer.stereo(sr, l, r)
        val original = buf.copy()
        val reduction = TruePeakLimiter.processInPlace(buf, ceilingDbtp = -1.0)
        assertEquals(0.0, reduction, 0.0)
        assertContentEquals(original[0], buf[0])
        assertContentEquals(original[1], buf[1])
        // Streaming: output is the input delayed by exactly latencyFrames.
        val lim = TruePeakLimiter(sr, 2, maxBlockFrames = 500)
        assertEquals(16 + 221, lim.latencyFrames)
        val out = Array(2) { FloatArray(buf.frames + lim.latencyFrames) }
        var pos = 0
        var step = 3
        while (pos < buf.frames) {
            val n = minOf(step, buf.frames - pos)
            lim.process(buf.channels, out, n, pos, pos)
            pos += n
            step = step * 3 % 1100 + 1
        }
        lim.flush(out, buf.frames)
        for (ch in 0..1) for (i in 0 until buf.frames) assertEquals(original[ch][i], out[ch][i + lim.latencyFrames])
        for (ch in 0..1) for (i in 0 until lim.latencyFrames) assertEquals(0f, out[ch][i])
    }

    @Test
    fun limitingProducesNoClicksAndStreamingMatchesOneShot() {
        val buf = overs()
        val oneShot = buf.copy()
        TruePeakLimiter.processInPlace(oneShot)
        val report = ArtifactDetector(sr).analyze(oneShot)
        assertTrue(report.clicks.isEmpty(), report.summary())
        assertTrue(report.clipRuns.isEmpty(), report.summary())

        val lim = TruePeakLimiter(sr, 2, maxBlockFrames = 777)
        val out = Array(2) { FloatArray(buf.frames + lim.latencyFrames) }
        var pos = 0
        var step = 5
        while (pos < buf.frames) {
            val n = minOf(step, buf.frames - pos)
            lim.process(buf.channels, out, n, pos, pos)
            pos += n
            step = step * 5 % 3000 + 1
        }
        lim.flush(out, buf.frames)
        for (ch in 0..1) for (i in 0 until buf.frames) assertEquals(oneShot[ch][i], out[ch][i + lim.latencyFrames], 1e-6f)
        assertEquals(lim.maxGainReductionDb, TruePeakLimiter.processInPlace(buf), 1e-9)
    }

    @Test
    fun releaseFollowsTheConfiguredTimeConstant() {
        // Gain must be back to ~unity 5 time constants after the loud section, and still well down after one.
        val n = 4 * sr
        val x = Synth.sine(sr, 1000.0, 4.0, amp = 0.25f)
        for (i in sr until 2 * sr) x[i] *= 8f // +6 dBFS for 1 s (section edges fall on zero crossings)
        val buf = AudioBuffer.mono(sr, x)
        val ref = x.copyOf()
        TruePeakLimiter.processInPlace(buf, ceilingDbtp = -1.0, releaseMs = 80.0)
        /** Least-squares gain of the output against the input over 200 frames from [t] seconds. */
        fun gainAt(t: Double): Double {
            val c = (t * sr).toInt()
            var num = 0.0; var den = 0.0
            for (i in c until c + 200) { num += buf[0][i] * ref[i].toDouble(); den += ref[i] * ref[i].toDouble() }
            return num / den
        }
        val floor = TruePeak.dbtpToLinear(-1.05) / 2.0 // required gain in the loud part
        assertEquals(floor, gainAt(1.5), 0.01)
        // 80 ms after the end: (1 - floor) * e^-1 still missing (box smoothing shifts it by ~2.5 ms: loose bound).
        val g80 = gainAt(2.0 + 0.080)
        assertTrue(g80 > floor + 0.5 * (1 - floor) && g80 < floor + 0.75 * (1 - floor), "gain after one tau: $g80")
        assertEquals(1.0, gainAt(2.0 + 0.6), 0.002)
        assertEquals(n, buf.frames)
    }
}
