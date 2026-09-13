package dev.muisc.dsp.synth

import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.stretch.SigMeasure
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiserAndPadTest {
    private val sr = 44100

    private fun centroid(x: FloatArray, offset: Int, n: Int): Double {
        val mag = SigMeasure.spectrum(x, n, offset)
        var num = 0.0; var den = 0.0
        for (k in 1 until mag.size) { val p = mag[k].toDouble() * mag[k]; num += p * k * sr / n; den += p }
        return num / den
    }

    @Test
    fun riserNoiseCentreFrequencyRises() {
        val frames = 4 * sr
        val spec = RiserSpec(noiseStartHz = 200.0, noiseEndHz = 6000.0, toneLevel = 0.0, ending = RiserEnding.CUT)
        val r = Riser.render(sr, frames, spec)
        assertEquals(frames, r.frames)
        val x = r[0]
        val n = 16384
        val c = DoubleArray(4) { centroid(x, frames / 8 + it * frames / 4 - n / 2, n) }
        for (i in 1 until 4) assertTrue(c[i] > c[i - 1] * 1.3, "centroid quarter $i (${c[i]}) not above quarter ${i - 1} (${c[i - 1]})")
        assertTrue(c[0] < 900.0 && c[3] > 2500.0, "centroids ${c.toList()}")
        // Exponential build: the last 100 ms are much louder than the first 100 ms and the cut ends loud.
        val early = SigMeasure.rms(x, sr / 2, sr / 2 + sr / 10)
        val late = SigMeasure.rms(x, frames - sr / 10, frames)
        assertTrue(SigMeasure.db(late / early) > 12.0, "build ${SigMeasure.db(late / early)} dB")
        assertTrue(abs(x[frames - 1]) > 1e-4f || abs(x[frames - 2]) > 1e-4f, "cut ending stays loud")
    }

    @Test
    fun riserToneGlidesAndHitEndingDecays() {
        val frames = 3 * sr
        val spec = RiserSpec(noiseLevel = 0.0, toneLevel = 0.5, toneStartHz = 110.0, toneEndHz = 440.0, toneWaveform = Waveform.SINE, ending = RiserEnding.HIT, hitMs = 250.0)
        val r = Riser.render(sr, frames, spec, channels = 2)
        assertEquals(frames, r.frames)
        assertEquals(2, r.channelCount)
        val x = r[0]
        val hit = (0.25 * sr).toInt()
        val rise = frames - hit
        val win = (0.04 * sr).toInt()
        val fLate = SigMeasure.zeroCrossingFrequency(x, sr, rise - 2 * win, win)
        assertEquals(440.0, fLate, 25.0, "tone frequency near the end of the rise")
        val fMid = SigMeasure.zeroCrossingFrequency(x, sr, rise / 2, win)
        assertEquals(220.0, fMid, 15.0, "tone frequency mid rise (geometric)")
        // Hit: loud at its start, decayed to near silence by the end.
        assertTrue(SigMeasure.rms(x, rise, rise + 1000) > 0.2, "hit start level")
        assertTrue(SigMeasure.rms(x, frames - 500, frames) < 0.03, "hit decayed: ${SigMeasure.rms(x, frames - 500, frames)}")
    }

    @Test
    fun padContainsTheRequestedPitches() {
        val notes = intArrayOf(60, 64, 67) // C major
        val frames = 4 * sr
        val pad = Pad.render(sr, frames, notes, PadSpec(attackSec = 0.5, releaseSec = 0.5, cutoffHz = 1500.0), channels = 1)
        assertEquals(frames, pad.frames)
        val x = pad[0]
        val n = 65536
        val mag = SigMeasure.spectrum(x, n, sr)
        for (m in notes) {
            val f = Synth.midiToHz(m.toDouble())
            val peakF = SigMeasure.peakFrequency(x, sr, n, sr, f * 0.97, f * 1.03)
            assertEquals(f, peakF, f * 0.01, "note $m")
            val peak = SigMeasure.peakNear(mag, f, sr, n, 4)
            // 20 dB above the level a quarter-tone below the note (between partials).
            val between = SigMeasure.peakNear(mag, f * 0.972, sr, n, 1)
            assertTrue(SigMeasure.db(peak / between) > 20.0, "note $m peak/floor ${SigMeasure.db(peak / between)} dB")
        }
        assertEquals(0f, x[0], 0f)
        assertEquals(0f, x[frames - 1], 0f)
        assertTrue(SigMeasure.rms(x, sr, 3 * sr) > 0.03, "pad level")
        val stereo = Pad.render(sr, sr, notes, PadSpec(attackSec = 0.1, releaseSec = 0.1), channels = 2)
        assertEquals(2, stereo.channelCount)
        assertTrue(SigMeasure.correlation(stereo[0], stereo[1]) < 0.98, "stereo spread")
    }
}
