package dev.muisc.dsp.loudness

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** EBU Tech 3341 / 3342 conformance cases (nominal values within +/-0.1 LU). */
class LoudnessMeterTest {

    private fun dbfs(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

    private fun stereoSine(sr: Int, seconds: Double, levelDbfs: Double, freq: Double = 1000.0): AudioBuffer {
        val l = Synth.sine(sr, freq, seconds, amp = dbfs(levelDbfs))
        return AudioBuffer.stereo(sr, l, l.copyOf())
    }

    @Test
    fun stereoSineAtMinus23dBfsReadsMinus23Lufs() {
        for (sr in intArrayOf(48000, 44100)) {
            val r = LoudnessMeter.measure(stereoSine(sr, 20.0, -23.0))
            assertEquals(-23.0, r.integratedLufs, 0.1, "integrated at $sr")
            assertEquals(-23.0, r.maxMomentaryLufs, 0.1, "momentary at $sr")
            assertEquals(-23.0, r.maxShortTermLufs, 0.1, "short-term at $sr")
            // Steady signal: LRA ~ 0, every block passes the gates.
            assertEquals(0.0, r.loudnessRangeLu, 0.1)
            assertEquals(r.totalBlockCount, r.gatedBlockCount)
            assertEquals(200 - 3, r.totalBlockCount)
            assertEquals(200 - 29, r.shortTermLufs.size)
        }
    }

    @Test
    fun stereoSineAtMinus33dBfsReadsMinus33Lufs() {
        val r = LoudnessMeter.measure(stereoSine(48000, 20.0, -33.0))
        assertEquals(-33.0, r.integratedLufs, 0.1)
    }

    @Test
    fun monoSineReadsMinus26Lufs() {
        val x = Synth.sine(44100, 1000.0, 20.0, amp = dbfs(-23.0))
        val r = LoudnessMeter.measure(AudioBuffer.mono(44100, x))
        assertEquals(-26.0, r.integratedLufs, 0.1)
    }

    @Test
    fun gatingIgnoresTrailingNearSilence() {
        val sr = 48000
        val loud = stereoSine(sr, 20.0, -23.0)
        val quiet = stereoSine(sr, 20.0, -75.0)
        val r = LoudnessMeter.measure(loud.concat(quiet))
        assertEquals(-23.0, r.integratedLufs, 0.1)
        assertTrue(r.gatedBlockCount < r.totalBlockCount)
        // The momentary curve shows the drop: ~ -23 in the first half, ~ -75 in the second.
        assertEquals(-23.0, r.momentaryLufs[50], 0.1)
        assertEquals(-75.0, r.momentaryLufs[350], 0.1)
        assertEquals(35.4, r.momentaryTimeSec(350), 1e-9)
        // Digital silence alone reads -inf.
        assertEquals(Double.NEGATIVE_INFINITY, LoudnessMeter.measure(AudioBuffer.silence(sr, 2, sr * 5)).integratedLufs)
    }

    @Test
    fun relativeGateDropsQuietButAudibleSection() {
        // -23 LUFS for 20 s, then -43 LUFS (20 LU quieter, well above the absolute gate) for 20 s. The ungated
        // loudness is the *power* mean: 10 log10((10^-2.3 + 10^-4.3) / 2) = -25.96 LUFS, so the relative gate sits at
        // -35.96 LUFS, drops the quiet half, and the integrated loudness stays -23.
        val sr = 44100
        val r = LoudnessMeter.measure(stereoSine(sr, 20.0, -23.0).concat(stereoSine(sr, 20.0, -43.0)))
        assertEquals(-23.0, r.integratedLufs, 0.1)
        assertEquals(-35.96, r.relativeThresholdLufs, 0.1)
        assertEquals(r.totalBlockCount / 2.0, r.gatedBlockCount.toDouble(), 3.0)
    }

    @Test
    fun loudnessRangeOfAlternatingBlocksIsTenLu() {
        val sr = 48000
        var buf = stereoSine(sr, 20.0, -20.0)
        buf = buf.concat(stereoSine(sr, 20.0, -30.0)).concat(stereoSine(sr, 20.0, -20.0)).concat(stereoSine(sr, 20.0, -30.0))
        val r = LoudnessMeter.measure(buf)
        assertEquals(10.0, r.loudnessRangeLu, 1.0)
        assertEquals(-20.0, r.maxShortTermLufs, 0.1)
    }

    @Test
    fun streamingInOddBlocksMatchesOneShot() {
        val sr = 44100
        val buf = stereoSine(sr, 6.0, -23.0).concat(stereoSine(sr, 4.0, -33.0, freq = 3000.0))
        val oneShot = LoudnessMeter.measure(buf)
        val meter = LoudnessMeter(sr, 2, initialCapacitySec = 1.0)
        var pos = 0
        var step = 1
        while (pos < buf.frames) {
            val n = minOf(step, buf.frames - pos)
            meter.process(buf.channels, n, pos)
            pos += n
            step = step * 3 % 5000 + 1
        }
        val streamed = meter.result()
        assertEquals(oneShot.integratedLufs, streamed.integratedLufs, 1e-9)
        assertEquals(oneShot.loudnessRangeLu, streamed.loudnessRangeLu, 1e-9)
        assertEquals(oneShot.momentaryLufs.size, streamed.momentaryLufs.size)
        for (i in oneShot.momentaryLufs.indices) assertEquals(oneShot.momentaryLufs[i], streamed.momentaryLufs[i], 1e-9)
        assertEquals(streamed.momentaryLufs.last(), meter.momentaryLufs(), 1e-12)
        assertEquals(streamed.shortTermLufs.last(), meter.shortTermLufs(), 1e-12)
        meter.reset()
        assertEquals(Double.NEGATIVE_INFINITY, meter.momentaryLufs())
        assertEquals(0, meter.result().totalBlockCount)
    }
}
