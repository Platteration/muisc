package dev.muisc.dsp.fx

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.dsp.stretch.SigMeasure
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DelayTest {
    private val sr = 44100

    private fun peakIn(x: FloatArray, centre: Int, radius: Int): Pair<Int, Float> {
        var best = centre; var bv = 0f
        for (i in (centre - radius).coerceAtLeast(0)..(centre + radius).coerceAtMost(x.size - 1)) if (abs(x[i]) > bv) { bv = abs(x[i]); best = i }
        return best to bv
    }

    @Test
    fun impulseResponseHasEchoesAtTheDelayWithFeedbackDecay() {
        val d = Delay(sr, 1)
        d.setDelayFrames(4410.0, immediate = true) // 100 ms
        d.feedback = 0.5
        d.mix = 0.5
        d.setLowPass(20000.0); d.setHighPass(5.0)
        val n = 5 * 4410 + 100
        val x = FloatArray(n); x[0] = 1f
        val y = FloatArray(n)
        d.process(arrayOf(x), arrayOf(y), n)
        assertEquals(0.5f, y[0], 1e-6f, "dry impulse")
        var prev = 0f
        for (k in 1..4) {
            val (pos, v) = peakIn(y, k * 4410, 10)
            assertTrue(abs(pos - k * 4410) <= 2, "echo $k at $pos")
            // The first echo is the impulse itself (written unfiltered); later ones pass the loop once more each.
            if (k == 1) assertEquals(0.5f, v, 0.005f, "first echo = wet gain")
            else assertTrue(v / prev in 0.42..0.5, "echo $k decay ratio ${v / prev} (v=$v)")
            // between echoes: silence
            val (_, mid) = peakIn(y, k * 4410 - 2205, 1000)
            assertTrue(mid < 0.02f, "spurious energy between echoes: $mid")
            prev = v
        }
        assertEquals(0.5f, peakIn(y, 4410, 3).second, 0.02f, "first echo = wet gain")
    }

    @Test
    fun fractionalDelayIsInterpolated() {
        val d = Delay(sr, 1)
        d.setDelayFrames(1000.5, immediate = true)
        d.feedback = 0.0; d.mix = 1.0
        d.setLowPass(20000.0)
        val n = 1100
        val x = FloatArray(n); x[0] = 1f
        val y = FloatArray(n)
        d.process(arrayOf(x), arrayOf(y), n)
        // Half-sample delay: the two centre taps of a 3rd-order Lagrange kernel are 9/16 each, outer ones -1/16.
        assertEquals(0.5625f, y[1000], 1e-4f)
        assertEquals(0.5625f, y[1001], 1e-4f)
        assertEquals(-0.0625f, y[999], 1e-4f)
        assertEquals(-0.0625f, y[1002], 1e-4f)
        assertEquals(1000.5, d.delayFrames, 0.0)
    }

    @Test
    fun echoOutTailDecaysBelowMinus60dB() {
        val burst = Synth.whiteNoise(sr, 0.3, amp = 0.5f, seed = 11)
        val d = Delay(sr, 1)
        d.setDelayFrames(0.25 * sr, immediate = true)
        d.feedback = 0.6
        d.mix = 0.5
        val cut = burst.size
        val tail = 5 * sr
        val y = d.echoOut(AudioBuffer.mono(sr, burst), cut, tail)
        assertEquals(cut + tail, y.frames)
        val out = y[0]
        val first = SigMeasure.rms(out, cut, cut + sr / 4)
        val last = SigMeasure.rms(out, y.frames - sr / 4, y.frames)
        val drop = SigMeasure.db(last / first)
        assertTrue(drop < -60.0, "tail decay $drop dB")
        assertTrue(first > 0.01, "tail present: rms $first")
        // Before the cut the dry signal is present at the dry gain.
        assertEquals(0.5f * burst[100], out[100], 1e-6f)
        assertEquals(0.6, d.feedback, 0.0, "feedback restored")
    }

    @Test
    fun delayTimeChangeIsClickFreeAndBeatSettable() {
        val x = Synth.sine(sr, 220.0, 2.0, amp = 0.5f)
        val d = Delay(sr, 1, crossfadeMs = 20.0)
        d.setDelayFrames(0.2 * sr, immediate = true)
        d.feedback = 0.4; d.mix = 0.5
        val y = FloatArray(x.size)
        d.process(arrayOf(x), arrayOf(y), sr)
        d.setDelayFrames(0.3 * sr)
        assertEquals(0.3 * sr, d.delayFrames, 0.0)
        d.process(arrayOf(x), arrayOf(y), x.size - sr, sr, sr)
        val report = ArtifactDetector(sr, clickMinMagnitude = 0.02f).analyze(y)
        assertTrue(report.clicks.isEmpty(), report.summary())
        // Beat-relative time.
        val beatFrames = 60.0 / 128.0 * sr
        d.setDelayBeats(0.1875, beatFrames, immediate = true)
        assertEquals(0.1875 * beatFrames, d.delayFrames, 1e-9)
        // Clamping to the supported range.
        d.setDelayFrames(1.0, immediate = true)
        assertEquals(Delay.MIN_DELAY.toDouble(), d.delayFrames, 0.0)
    }

    @Test
    fun pingPongAlternatesChannels() {
        val d = Delay(sr, 2)
        d.setDelayFrames(1000.0, immediate = true)
        d.feedback = 0.5; d.mix = 1.0; d.pingPong = true
        d.setLowPass(20000.0); d.setHighPass(5.0)
        val n = 3500
        val x = Array(2) { FloatArray(n).also { a -> a[0] = 1f } }
        val y = Array(2) { FloatArray(n) }
        d.process(x, y, n)
        val l1 = peakIn(y[0], 1000, 5).second; val r1 = peakIn(y[1], 1000, 5).second
        val l2 = peakIn(y[0], 2000, 5).second; val r2 = peakIn(y[1], 2000, 5).second
        val l3 = peakIn(y[0], 3000, 5).second; val r3 = peakIn(y[1], 3000, 5).second
        assertTrue(l1 > 0.9f && r1 < 1e-3f, "first echo left only: $l1 / $r1")
        assertTrue(r2 > 0.4f && l2 < 1e-3f, "second echo right only: $l2 / $r2")
        assertTrue(l3 > 0.2f && r3 < 1e-3f, "third echo left only: $l3 / $r3")
    }
}
