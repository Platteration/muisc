package dev.muisc.dsp.texture

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.dsp.stretch.SigMeasure
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShapedNoiseTest {
    private val sr = 44100
    private val b100 = ThirdOctaveBands.bandOf(100.0)
    private val b10k = ThirdOctaveBands.bandOf(10000.0)

    @Test
    fun measuredEnvelopeMatchesTargetWithin3dB() {
        // Pink-ish slope, 1 dB per band, plus a bump around 1 kHz.
        val env = FloatArray(31) { -20f - it + if (it in 16..18) 6f else 0f }
        val len = 10 * sr
        val out = ShapedNoise(sr, seed = 4).render(len, env)
        assertEquals(len, out.frames)
        val meas = ThirdOctaveBands.measureDb(out)
        for (b in b100..b10k) assertTrue(abs(meas[b] - env[b]) < 3f, "band $b (${ThirdOctaveBands.CENTRES[b]} Hz): target ${env[b]}, measured ${meas[b]}")
        // Absolute level is right too (not only the shape): the 1 kHz band at -20 - 17 + 6 = -31 dB.
        assertEquals(-31f, meas[17], 2f)
        assertTrue(ArtifactDetector(sr).analyze(out).clicks.isEmpty())
    }

    @Test
    fun morphReachesTheEndEnvelopeAndIsDeterministic() {
        val a = FloatArray(31) { -30f }
        val b = FloatArray(31) { -30f - 1.5f * (it - 17) }
        val len = 12 * sr
        val out = ShapedNoise(sr, seed = 8).render(len, a, b, channels = 2)
        assertEquals(len, out.frames)
        assertEquals(2, out.channelCount)
        val first = ThirdOctaveBands.measureDb(out[0].copyOfRange(0, sr), sr)
        val last = ThirdOctaveBands.measureDb(out[0].copyOfRange(len - sr, len), sr)
        val lo = ThirdOctaveBands.bandOf(200.0); val hi = ThirdOctaveBands.bandOf(5000.0)
        val tiltA = 0.0
        val tiltB = (b[hi] - b[lo]).toDouble()
        assertEquals(tiltA, (first[hi] - first[lo]).toDouble(), 3.0, "start tilt")
        assertEquals(tiltB, (last[hi] - last[lo]).toDouble(), 3.0, "end tilt")
        val again = ShapedNoise(sr, seed = 8).render(len, a, b, channels = 2)
        for (i in 0 until len) assertEquals(out[1][i], again[1][i], 0f)
        assertTrue(abs(SigMeasure.correlation(out[0], out[1])) < 0.05, "channels decorrelated")
    }

    @Test
    fun unityGainsReproduceTheNoiseAndBinGainsAreCalibrated() {
        val sn = ShapedNoise(sr, seed = 1)
        // A band level equal to the white-noise level per band gives unity bin gain.
        val counts = ThirdOctaveBands.binCounts(sn.frameSize, sr)
        val env = FloatArray(31) { b -> if (counts[b] > 0) (10.0 * Math.log10(counts[b] / (sn.frameSize / 2.0))).toFloat() else -100f }
        val g = sn.binGains(env)
        for (k in 1 until g.size) assertEquals(1f, g[k], 1e-4f, "bin $k gain")
        val out = sn.render(3 * sr, env)[0]
        assertEquals(1.0, SigMeasure.rms(out, 5000, out.size - 5000), 0.03, "unit RMS reproduced")
    }

    @Test
    fun granulatorHasExactLengthIsClickFreeAndKeepsLevel() {
        val song = SyntheticSong(bpm = 120.0, bars = 2, introBars = 0, outroBars = 0).render()
        val len = 6 * sr + 7
        val g = Granulator(sr, seed = 2)
        val out = g.render(song, len)
        assertEquals(len, out.frames)
        assertEquals(2, out.channelCount)
        val report = ArtifactDetector(sr).analyze(out)
        assertTrue(report.clicks.isEmpty(), report.summary())
        val lvl = SigMeasure.db(out.rms().toDouble() / song.rms())
        assertTrue(abs(lvl) < 4.0, "level difference $lvl dB")
        val again = Granulator(sr, seed = 2).render(song, len)
        for (i in 0 until len) assertEquals(out[0][i], again[0][i], 0f)
        assertTrue(SigMeasure.rms(out[0], 0, 4000) > 0.2 * SigMeasure.rms(out[0]), "full density from the start")
    }

    @Test
    fun granulatorWithoutPitchJitterPreservesPitch() {
        val src = AudioBuffer.mono(sr, Synth.sine(sr, 440.0, 1.0, amp = 0.5f))
        val out = Granulator(sr, seed = 3, pitchJitterSemitones = 0.0, voices = 4).render(src, 3 * sr)
        val f = SigMeasure.peakFrequency(out[0], sr, 32768, 20000, 100.0, 2000.0)
        assertEquals(440.0, f, 4.4, "peak $f Hz")
        val jit = Granulator(sr, seed = 3, pitchJitterSemitones = 2.0, voices = 4).render(src, 3 * sr)
        // With ±2 semitone jitter the energy spreads: the exact 440 Hz bin is weaker relative to the total.
        val sharpCore = SigMeasure.peakNear(SigMeasure.spectrum(out[0], 32768, 20000), 440.0, sr, 32768, 2)
        val jitCore = SigMeasure.peakNear(SigMeasure.spectrum(jit[0], 32768, 20000), 440.0, sr, 32768, 2)
        assertTrue(jitCore < sharpCore, "jitter spreads the peak: $jitCore vs $sharpCore")
    }
}
