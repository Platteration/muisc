package dev.muisc.dsp.resample

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.filter.Measure
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResamplerTest {

    @Test
    fun roundTrip44to48to44OfA1kHzSineIsBelowMinus50dB() {
        val x = Synth.sine(44100, 1000.0, 1.0)
        val r = Resampler()
        val up = r.resample(AudioBuffer.mono(44100, x), 48000)
        assertEquals(48000, up.frames)
        assertEquals(48000, up.sampleRate)
        val back = r.resample(up, 44100)
        assertEquals(x.size, back.frames)
        val y = back[0]
        val edge = 64
        val err = FloatArray(x.size - 2 * edge) { y[it + edge] - x[it + edge] }
        val rel = Measure.db(Measure.rms(err) / Measure.rms(x, edge, x.size - edge))
        assertTrue(rel < -50.0, "round-trip error $rel dB")
        // amplitude preserved in the passband
        assertEquals(0.5, Measure.amplitude(y, 44100, 1000.0, 1000, 32768), 0.5 * 0.005)
    }

    @Test
    fun outputLengthRounding() {
        val r = Resampler()
        assertEquals(48000, r.outputLength(44100, 44100, 48000))
        assertEquals(24000, r.outputLength(22050, 44100, 48000))
        assertEquals(1, r.outputLength(1, 44100, 48000))
        assertEquals(Math.round(1000 * 44100.0 / 48000).toInt(), r.outputLength(1000, 48000, 44100))
        val stereo = AudioBuffer.stereo(48000, FloatArray(4800), FloatArray(4800))
        val out = r.resample(stereo, 44100)
        assertEquals(4410, out.frames); assertEquals(2, out.channelCount); assertEquals(44100, out.sampleRate)
        assertTrue(r.resample(stereo, 48000) === stereo)
    }

    @Test
    fun impulseStaysAtTheSameTimeWithinOneSample() {
        for ((inRate, outRate) in listOf(44100 to 48000, 48000 to 44100, 44100 to 22050, 22050 to 96000)) {
            val x = FloatArray(4000)
            x[1000] = 1f
            val y = Resampler().resample(x, inRate, outRate)
            var best = 0
            for (i in y.indices) if (abs(y[i]) > abs(y[best])) best = i
            val expected = 1000.0 * outRate / inRate
            assertTrue(abs(best - expected) <= 1.0, "$inRate->$outRate: peak at $best, expected $expected")
            assertTrue(y[best] > 0f)
        }
    }

    @Test
    fun downsamplingRemovesContentAboveNewNyquistInsteadOfAliasing() {
        val x = Synth.sine(44100, 15000.0, 1.0)
        val y = Resampler().resample(x, 44100, 22050)
        assertEquals(22050, y.size)
        val level = Measure.db(Measure.rms(y, 200, y.size - 200) / (0.5 / Math.sqrt(2.0)))
        assertTrue(level < -60.0, "residual after downsampling: $level dB")
        // the aliased image at 7050 Hz specifically
        val alias = Measure.db(Measure.amplitude(y, 22050, 7050.0, 200, 16384) / 0.5)
        assertTrue(alias < -60.0, "alias at 7.05 kHz: $alias dB")
        // but a 5 kHz tone survives unchanged
        val ok = Resampler().resample(Synth.sine(44100, 5000.0, 1.0), 44100, 22050)
        assertEquals(0.0, Measure.db(Measure.amplitude(ok, 22050, 5000.0, 200, 16384) / 0.5), 0.05)
    }

    @Test
    fun nonIntegerRatioPreservesAmplitudeAndFrequency() {
        val x = Synth.sine(48000, 3000.0, 1.0)
        val y = Resampler().resample(x, 48000, 44100)
        assertEquals(44100, y.size)
        assertEquals(0.5, Measure.amplitude(y, 44100, 3000.0, 500, 32768), 0.5 * 0.005)
        // energy is at 3 kHz, not elsewhere: subtract the analytic sine
        val err = FloatArray(40000) { y[it + 100] - (0.5 * Math.sin(2 * Math.PI * 3000.0 * (it + 100) / 44100)).toFloat() }
        assertTrue(Measure.db(Measure.rms(err) / (0.5 / Math.sqrt(2.0))) < -50.0)
    }

    @Test
    fun streamingMatchesOneShotAfterFlushRegardlessOfBlockSizes() {
        for ((inRate, outRate) in listOf(44100 to 48000, 48000 to 44100, 44100 to 22050)) {
            val n = 20000
            val x = arrayOf(Synth.whiteNoise(inRate, n / inRate.toDouble(), seed = 31), Synth.sine(inRate, 440.0, n / inRate.toDouble()))
            val oneShot = Array(2) { Resampler().resample(x[it], inRate, outRate) }
            val s = StreamingResampler(inRate, outRate, 2, maxBlockFrames = 500)
            val out = Array(2) { FloatArray(oneShot[0].size + 64) }
            var produced = 0
            var pos = 0
            val sizes = intArrayOf(3, 1200, 17, 4096, 640)
            var k = 0
            while (pos < n) {
                val len = minOf(sizes[k % sizes.size], n - pos)
                val blk = Array(2) { x[it].copyOfRange(pos, pos + len) }
                produced += s.process(blk, len, out, 0, produced)
                pos += len; k++
            }
            // before the flush the stream is behind by the look-ahead
            assertTrue(produced < oneShot[0].size && produced > oneShot[0].size - s.latencyOutputFrames - 3, "$inRate->$outRate produced $produced")
            produced += s.flush(out, produced)
            val common = minOf(produced, oneShot[0].size)
            assertTrue(abs(produced - oneShot[0].size) <= 1, "$inRate->$outRate: streaming produced $produced vs ${oneShot[0].size}")
            for (ch in 0..1) for (i in 0 until common) assertEquals(oneShot[ch][i], out[ch][i], 1e-5f, "$inRate->$outRate ch $ch sample $i")
        }
    }

    @Test
    fun kernelIsNormalisedAndKaiserWindowed() {
        val k = SincKernel()
        assertEquals(k.rolloff.toFloat(), k.at(0.0), 1e-6f)
        assertEquals(0f, k.at(16.0), 0f)
        assertEquals(0f, k.at(-16.5), 0f)
        assertEquals(k.at(3.3), k.at(-3.3), 0f)
        // DC gain: sum over integer offsets is 1 for any fractional phase
        for (phase in doubleArrayOf(0.0, 0.25, 0.5, 0.9)) {
            var s = 0.0
            for (i in -16..16) s += k.at(i + phase)
            assertEquals(1.0, s, 2e-4, "phase $phase")
        }
        assertEquals(1.0, SincKernel.besselI0(0.0), 1e-15)
        assertEquals(1.2660658777520084, SincKernel.besselI0(1.0), 1e-12)
    }
}
