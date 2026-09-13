package dev.muisc.dsp.fx

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.stretch.SigMeasure
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VinylBrakeTest {
    private val sr = 44100

    @Test
    fun pitchDecreasesMonotonicallyDuringBrake() {
        val x = Synth.sine(sr, 1000.0, 4.0, amp = 0.5f)
        for (curve in BrakeCurve.values()) {
            val brake = VinylBrake(curve)
            val start = sr / 2
            val len = 2 * sr
            val y = brake.brake(AudioBuffer.mono(sr, x), start, len)
            val out = y[0]
            // Instantaneous frequency in 40 ms windows over the first 80 % of the brake (later the level is too low).
            val win = (0.04 * sr).toInt()
            var prev = Double.MAX_VALUE
            var t = start
            var checked = 0
            while (t + win < start + (len * 0.8).toInt()) {
                val f = SigMeasure.zeroCrossingFrequency(out, sr, t, win)
                if (f == 0.0) break // fewer than two periods in the window: too slow to measure this way
                assertTrue(f <= prev + 5.0, "$curve: frequency rose from $prev to $f at frame $t")
                prev = f
                t += win
                checked++
            }
            assertTrue(checked > 30)
            // First 10 ms of the brake: still within 3 % of the original pitch (the exponential curve drops fastest).
            val f0 = SigMeasure.zeroCrossingFrequency(out, sr, start, win / 4)
            assertEquals(1000.0, f0, 30.0, "$curve: frequency at brake start")
            assertTrue(prev < 400.0, "$curve: frequency at 80 % of the brake is $prev Hz")
        }
    }

    @Test
    fun brakeHasExactLengthPreservesPrefixAndEndsSilent() {
        val x = Synth.sine(sr, 500.0, 2.0, amp = 0.5f)
        val start = 10000
        val len = sr
        val y = VinylBrake().brake(AudioBuffer.mono(sr, x), start, len)
        assertEquals(start + len, y.frames)
        for (i in 0 until start) assertEquals(x[i], y[0][i], 0f, "prefix sample $i")
        for (i in y.frames - 50 until y.frames) assertTrue(abs(y[0][i]) < 1e-3f, "tail sample $i = ${y[0][i]}")
        assertEquals(0f, y[0][y.frames - 1], 0f)
        // Longer requested output: silence after the brake.
        val y2 = VinylBrake().brake(AudioBuffer.mono(sr, x), start, len, start + len + 1000)
        assertEquals(start + len + 1000, y2.frames)
        for (i in start + len until y2.frames) assertEquals(0f, y2[0][i], 0f)
        // Gain follows the speed: half-way through a power-2 brake the speed is 0.25.
        val mid = start + len / 2
        val rmsMid = SigMeasure.rms(y[0], mid - 500, mid + 500)
        assertEquals(0.25 * 0.5 / Math.sqrt(2.0), rmsMid, 0.03, "level at mid brake")
    }

    @Test
    fun spinUpStartsSilentAndReachesNormalSpeed() {
        val x = Synth.sine(sr, 1000.0, 3.0, amp = 0.5f)
        val ramp = sr
        val total = 2 * sr
        val y = VinylBrake().spinUp(AudioBuffer.mono(sr, x), ramp, total)
        assertEquals(total, y.frames)
        for (i in 0 until 20) assertTrue(abs(y[0][i]) < 1e-3f, "start sample $i = ${y[0][i]}")
        val win = (0.05 * sr).toInt()
        val fEarly = SigMeasure.zeroCrossingFrequency(y[0], sr, ramp / 4, win)
        val fLate = SigMeasure.zeroCrossingFrequency(y[0], sr, ramp + sr / 2, win)
        assertTrue(fEarly < 800.0, "early frequency $fEarly")
        assertEquals(1000.0, fLate, 10.0, "frequency after spin-up")
        assertEquals(0.5 / Math.sqrt(2.0), SigMeasure.rms(y[0], ramp + 1000, total - 1000), 0.01)
        // speed curve endpoints
        val b = VinylBrake(BrakeCurve.EXPONENTIAL)
        assertEquals(1.0, b.speedAt(0.0), 1e-12)
        assertEquals(0.0, b.speedAt(1.0), 1e-12)
        assertTrue(b.speedAt(0.5) in 0.05..0.5)
    }
}
