package dev.muisc.dsp.hpss

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import kotlin.math.log10
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HpssTest {
    private val sr = 44100

    private fun energy(x: FloatArray, start: Int = 0, end: Int = x.size): Double {
        var e = 0.0
        for (i in start until end) e += x[i].toDouble() * x[i]
        return e
    }

    /** SNR in dB of [estimate] against [reference]. */
    private fun snrDb(reference: FloatArray, estimate: FloatArray): Double {
        var sig = 0.0; var err = 0.0
        for (i in reference.indices) { val d = reference[i] - estimate[i]; sig += reference[i].toDouble() * reference[i]; err += d.toDouble() * d }
        return 10.0 * log10(sig / maxOf(err, 1e-30))
    }

    /** Impulsive click train: 1 ms noise bursts every [periodSec] seconds. */
    private fun clicks(seconds: Double, periodSec: Double = 0.25, amp: Float = 0.6f): FloatArray {
        val n = Math.round(seconds * sr).toInt()
        val out = FloatArray(n)
        val rnd = Random(21)
        val burst = (0.001 * sr).toInt()
        var t = 0.05
        while (t < seconds) {
            val s = Math.round(t * sr).toInt()
            for (i in 0 until burst) if (s + i < n) out[s + i] += amp * (rnd.nextFloat() * 2f - 1f) * (1f - i.toFloat() / burst)
            t += periodSec
        }
        return out
    }

    @Test
    fun sustainedSineGoesToHarmonic() {
        val x = Synth.sine(sr, 440.0, 3.0, amp = 0.5f)
        val r = Hpss().separate(AudioBuffer.mono(sr, x))
        val eh = energy(r.harmonic[0]); val ep = energy(r.percussive[0]); val ex = energy(x)
        assertTrue(eh / ex > 0.95, "harmonic fraction ${eh / ex}")
        assertTrue(ep / ex < 0.05, "percussive fraction ${ep / ex}")
        // The percussiveness curve is near zero away from the (padded) edges.
        val edge = r.frameAt(0.25)
        for (t in edge until r.frames - edge) assertTrue(r.percussiveness[t] < 0.05, "frame $t percussiveness ${r.percussiveness[t]}")
    }

    @Test
    fun clickTrainGoesToPercussive() {
        val x = clicks(3.0)
        val r = Hpss().separate(AudioBuffer.mono(sr, x))
        val eh = energy(r.harmonic[0]); val ep = energy(r.percussive[0]); val ex = energy(x)
        assertTrue(ep / ex > 0.90, "percussive fraction ${ep / ex}")
        assertTrue(eh / ex < 0.10, "harmonic fraction ${eh / ex}")
        // Percussiveness peaks (> 0.9) on the click frames.
        var t = 0.05
        while (t < 3.0) { val f = r.frameAt(t); assertTrue(r.percussiveness[f] > 0.9, "click at ${t}s: ${r.percussiveness[f]}"); t += 0.25 }
        // Synth.clickTrack (20 ms decaying *tone* bursts, ~120 ms long) is the ambiguous case: with the 17-frame
        // (200 ms) time median about half of it counts as harmonic; the 31-frame median (librosa's default,
        // used by PseudoStemSeparator) classifies it as percussive.
        val ct = Synth.clickTrack(sr, 120.0, 3.0)
        val frac17 = energy(Hpss().separate(AudioBuffer.mono(sr, ct)).percussive[0]) / energy(ct)
        val frac31 = energy(Hpss(harmonicKernel = 31).separate(AudioBuffer.mono(sr, ct)).percussive[0]) / energy(ct)
        assertTrue(frac17 > 0.4, "clickTrack percussive fraction (17 frames) $frac17")
        assertTrue(frac31 > 0.85, "clickTrack percussive fraction (31 frames) $frac31")
    }

    @Test
    fun mixSeparatesWithSnrAbove10dB() {
        // Fades: an abrupt tone onset is itself a (legitimate) transient and would dominate the error budget.
        val sine = Synth.sine(sr, 220.0, 3.0, amp = 0.4f)
        val sine2 = Synth.sine(sr, 1320.0, 3.0, amp = 0.2f)
        for (s in listOf(sine, sine2)) { Synth.fadeInInPlace(s, sr / 5); Synth.fadeOutInPlace(s, sr / 5) }
        val cl = clicks(3.0, amp = 0.5f)
        val harmonicRef = FloatArray(sine.size) { sine[it] + sine2[it] }
        val x = FloatArray(sine.size) { harmonicRef[it] + cl[it] }
        val r = Hpss().separate(AudioBuffer.mono(sr, x))
        val snrH = snrDb(harmonicRef, r.harmonic[0])
        val snrP = snrDb(cl, r.percussive[0])
        assertTrue(snrH > 10.0, "harmonic SNR $snrH dB")
        assertTrue(snrP > 10.0, "percussive SNR $snrP dB")
        // Percussiveness is energy-weighted: the quiet clicks over a loud tone give a small ratio on the click
        // frame, but still orders of magnitude above the frames between clicks.
        val onClick = r.percussiveness[r.frameAt(1.05)]
        val between = r.percussiveness[r.frameAt(1.05 + 0.125)]
        assertTrue(onClick > 100 * between && between < 1e-3, "on=$onClick between=$between")
    }

    @Test
    fun harmonicPlusPercussiveReconstructsInput() {
        val rnd = Random(4)
        val n = 44100 + 777 // not a hop multiple: exercises the tail handling
        val x = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 0.8f }
        Synth.addBurst(x, sr, 10000, 800.0, 0.05, 0.5f)
        for (mask in doubleArrayOf(1.0, 2.0, 3.5, Double.POSITIVE_INFINITY)) {
            val r = Hpss(maskPower = mask).separate(AudioBuffer.mono(sr, x))
            val sum = r.reconstruct()[0]
            val snr = snrDb(x, sum)
            assertTrue(snr > 40.0, "p=$mask reconstruction SNR $snr dB")
            assertTrue(snr > 80.0, "p=$mask reconstruction should be at float precision, was $snr dB")
        }
    }

    @Test
    fun channelsAreIndependentAndStreamingMatchesOneShot() {
        val sine = Synth.sine(sr, 330.0, 2.0, amp = 0.5f)
        val cl = clicks(2.0)
        val stereo = AudioBuffer.stereo(sr, sine, cl)
        val hpss = Hpss()
        val r = hpss.separate(stereo)
        assertTrue(energy(r.harmonic[0]) / energy(sine) > 0.95)
        assertTrue(energy(r.percussive[1]) / energy(cl) > 0.90)
        // Per-channel API gives the same samples and the same frame count.
        val h = FloatArray(sine.size); val p = FloatArray(sine.size); val perc = FloatArray(hpss.frameCount(sine.size))
        val frames = hpss.separateChannel(sine, h, p, perc)
        assertEquals(r.frames, frames)
        assertEquals(1 + sine.size / 512, frames)
        for (i in sine.indices) { assertEquals(r.harmonic[0][i], h[i], 0f); assertEquals(r.percussive[0][i], p[i], 0f) }
        // Percussiveness-only path equals the full path.
        val po = hpss.percussiveness(AudioBuffer.mono(sr, cl))
        val full = hpss.separate(AudioBuffer.mono(sr, cl)).percussiveness
        for (t in po.indices) assertEquals(full[t], po[t], 1e-6f)
        // Combined stereo curve is energy-weighted across channels: between clicks only the sine is present -> ~0.
        assertTrue(r.percussiveness[r.frameAt(1.05 + 0.125)] < 0.1)
    }

    @Test
    fun edgeCasesShortAndEmptyInput() {
        val hpss = Hpss()
        val empty = hpss.separate(AudioBuffer.silence(sr, 1, 0))
        assertEquals(0, empty.harmonic.frames)
        val short = Synth.sine(sr, 440.0, 0.01) // 441 samples < frameSize
        val r = hpss.separate(AudioBuffer.mono(sr, short))
        assertEquals(short.size, r.harmonic.frames)
        assertEquals(hpss.frameCount(short.size), r.frames)
        val sum = r.reconstruct()[0]
        for (i in short.indices) assertEquals(short[i], sum[i], 1e-4f)
    }

    @Test
    fun speedOnLongStereoTrack() {
        // 30 s of stereo "music": sines + clicks + noise. Keeps the suite fast; the 3-minute figure is reported
        // in the work-package notes (scales linearly).
        val seconds = 30.0
        val rnd = Random(8)
        val l = Synth.sine(sr, 110.0, seconds, 0.3f); val r = Synth.sine(sr, 165.0, seconds, 0.3f)
        val cl = clicks(seconds, 0.5)
        for (i in l.indices) { val nz = (rnd.nextFloat() - 0.5f) * 0.05f; l[i] += cl[i] + nz; r[i] += cl[i] - nz }
        val buf = AudioBuffer.stereo(sr, l, r)
        val hpss = Hpss()
        hpss.separate(buf) // warm-up (JIT)
        val t0 = System.nanoTime()
        val res = hpss.separate(buf)
        val ms = (System.nanoTime() - t0) / 1e6
        println("HPSS: ${seconds}s stereo @ $sr Hz in %.0f ms (%.1fx realtime)".format(ms, seconds * 1000 / ms))
        assertTrue(res.frames > 0)
        assertTrue(ms < 20_000, "HPSS too slow: $ms ms for $seconds s stereo")
    }
}
