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

class SpectralFreezeTest {
    private val sr = 44100

    @Test
    fun bedHasExactLengthMatchingSpectrumAndIsDeterministic() {
        val song = SyntheticSong(bpm = 120.0, bars = 2, introBars = 0, outroBars = 0).render()
        val freeze = SpectralFreeze(sr, seed = 5)
        val len = 8 * sr + 123
        val bed = freeze.render(song, len)
        assertEquals(len, bed.frames)
        assertEquals(2, bed.channelCount)
        // Compare per channel: the bed's channels are decorrelated, so its mono mix would sit 3 dB lower.
        for (c in 0 until 2) {
            val src = ThirdOctaveBands.measureDb(song[c], sr)
            val out = ThirdOctaveBands.measureDb(bed[c], sr)
            for (b in ThirdOctaveBands.bandOf(100.0)..ThirdOctaveBands.bandOf(10000.0)) {
                assertTrue(abs(src[b] - out[b]) < 3f, "ch$c band $b: source ${src[b]} dB, bed ${out[b]} dB")
            }
        }
        val report = ArtifactDetector(sr).analyze(bed)
        assertTrue(report.clicks.isEmpty() && report.levelJumps.isEmpty(), report.summary())
        // Full level from the first frame (no fade-in) and decorrelated channels.
        assertTrue(SigMeasure.rms(bed[0], 0, 2000) > 0.3 * SigMeasure.rms(bed[0]), "start level")
        assertTrue(abs(SigMeasure.correlation(bed[0], bed[1])) < 0.2, "L/R correlation")
        val again = SpectralFreeze(sr, seed = 5).render(song, len)
        for (c in 0 until 2) for (i in 0 until len) assertEquals(bed[c][i], again[c][i], 0f)
        val other = SpectralFreeze(sr, seed = 6).render(song, len)
        assertTrue(abs(SigMeasure.correlation(bed[0], other[0])) < 0.2, "different seed gives a different bed")
    }

    @Test
    fun tonalEmphasisKeepsPeaksAndSuppressesTheFloor() {
        val a = Synth.sine(sr, 220.0, 3.0, amp = 0.3f)
        val b = Synth.sine(sr, 330.0, 3.0, amp = 0.3f)
        val noise = Synth.whiteNoise(sr, 3.0, amp = 0.02f, seed = 2)
        val x = FloatArray(a.size) { a[it] + b[it] + noise[it] }
        val src = AudioBuffer.mono(sr, x)
        val noisy = SpectralFreeze(sr, seed = 1, tonalEmphasis = false).render(src, 4 * sr)
        val tonal = SpectralFreeze(sr, seed = 1, tonalEmphasis = true).render(src, 4 * sr)
        val n = 65536
        val sNoisy = SigMeasure.spectrum(noisy[0], n, 10000)
        val sTonal = SigMeasure.spectrum(tonal[0], n, 10000)
        fun floorAt(s: FloatArray, hz: Double) = SigMeasure.peakNear(s, hz, sr, n, 20)
        val peakNoisy = SigMeasure.peakNear(sNoisy, 220.0, sr, n)
        val peakTonal = SigMeasure.peakNear(sTonal, 220.0, sr, n)
        assertEquals(0.0, SigMeasure.db(peakTonal / peakNoisy), 3.0, "220 Hz peak preserved")
        val floorNoisy = floorAt(sNoisy, 2000.0)
        val floorTonal = floorAt(sTonal, 2000.0)
        assertTrue(SigMeasure.db(floorTonal / floorNoisy) < -15.0, "noise floor at 2 kHz: ${SigMeasure.db(floorTonal / floorNoisy)} dB")
        // The random-phase bed spreads a tone over the analysis bin width (5.4 Hz at 8192 / 44.1 kHz).
        assertEquals(330.0, SigMeasure.peakFrequency(tonal[0], sr, n, 10000, 250.0, 400.0), 6.0)
    }

    @Test
    fun morphMovesTheSpectrumTowardsTheTargetBands() {
        val src = AudioBuffer.mono(sr, Synth.whiteNoise(sr, 2.0, amp = 0.3f, seed = 9))
        val target = FloatArray(31) { if (ThirdOctaveBands.exactCentre(it) >= 4000.0) 12f else -12f }
        val len = 10 * sr
        val bed = SpectralFreeze(sr, seed = 3).render(src, len, target)
        assertEquals(len, bed.frames)
        val first = ThirdOctaveBands.measureDb(bed[0].copyOfRange(0, 2 * sr), sr)
        val last = ThirdOctaveBands.measureDb(bed[0].copyOfRange(len - 2 * sr, len), sr)
        val lo = ThirdOctaveBands.bandOf(500.0)
        val hi = ThirdOctaveBands.bandOf(8000.0)
        val tiltFirst = first[hi] - first[lo]
        val tiltLast = last[hi] - last[lo]
        // The 2 s windows average the morph weight to ~0.1 and ~0.9: the tilt grows by ~0.8 * 24 dB.
        assertEquals(19.2, (tiltLast - tiltFirst).toDouble(), 5.0, "tilt change from $tiltFirst to $tiltLast dB")
        val report = ArtifactDetector(sr).analyze(bed)
        assertTrue(report.clicks.isEmpty(), report.summary())
    }
}
