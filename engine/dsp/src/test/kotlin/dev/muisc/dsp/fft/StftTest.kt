package dev.muisc.dsp.fft

import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.window.Window
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StftTest {
    private fun maxAbsDiff(a: FloatArray, b: FloatArray, n: Int): Float {
        var m = 0f
        for (i in 0 until n) m = maxOf(m, abs(a[i] - b[i]))
        return m
    }

    @Test
    fun perfectReconstructionHannAndSqrtHann() {
        val sr = 22050
        val rnd = Random(9)
        // Noise + sine + a length that is not a multiple of the hop, to exercise the tail handling.
        val x = FloatArray(12345) { rnd.nextFloat() - 0.5f }
        val frameSize = 1024
        for (hop in intArrayOf(frameSize / 4, frameSize / 2)) {
            for (w in listOf(Window.hann(frameSize), Window.sqrtHann(frameSize))) {
                val stft = Stft(frameSize, hop, w)
                val spec = stft.analyze(x, sr)
                assertEquals(1 + x.size / hop, spec.frames, "frame count, hop=$hop")
                val y = Istft(frameSize, hop, w).synthesize(spec, x.size)
                assertEquals(x.size, y.size)
                val err = maxAbsDiff(x, y, x.size)
                assertTrue(err < 1e-4f, "hop=$hop reconstruction error $err")
            }
        }
    }

    @Test
    fun perfectReconstructionNonCenteredAndNonColaHop() {
        val frameSize = 512
        val rnd = Random(2)
        val x = FloatArray(512 + 100 * 37) { rnd.nextFloat() - 0.5f }
        // Non-centred: frame t starts at t*hop; reconstruction is exact where every sample is covered by a
        // window with non-zero weight, i.e. everywhere except the first hop (Hann starts at 0).
        val hop = 37 // not a COLA hop for Hann; window-sum normalisation handles it anyway
        val w = Window.hann(frameSize)
        val spec = Stft(frameSize, hop, w, center = false).analyze(x, 44100)
        assertEquals(1 + (x.size - frameSize) / hop, spec.frames)
        assertEquals(false, spec.centered)
        val y = Istft(frameSize, hop, w, center = false).synthesize(spec)
        assertEquals((spec.frames - 1) * hop + frameSize, y.size)
        // The first/last ~30 samples are covered only by the near-zero tail of one Hann window (w < 0.04), where
        // dividing by w^2 amplifies the float FFT rounding error; everything else must be exact.
        var err = 0f
        for (i in 32 until y.size - 32) err = maxOf(err, abs(x[i] - y[i]))
        assertTrue(err < 1e-4f, "non-centred reconstruction error $err")
        // Sample 0 has zero window weight in every frame and is therefore unrecoverable (stays 0).
        assertEquals(0f, y[0], 0f)
    }

    @Test
    fun sinePeakLandsAtCorrectBin() {
        val sr = 44100
        val frameSize = 2048
        val hop = 512
        val x = Synth.sine(sr, 1000.0, 1.0, amp = 0.5f)
        val stft = Stft(frameSize, hop)
        val spec = stft.analyze(x, sr)
        val mag = spec.magnitude()
        val expectedBin = Math.round(1000.0 * frameSize / sr).toInt() // 46
        assertEquals(46, expectedBin)
        assertEquals(1000.0, spec.binFrequency(expectedBin), sr.toDouble() / frameSize)
        // Check every interior frame (edges see the zero padding).
        for (t in 4 until spec.frames - 4) {
            var best = 0
            for (k in 0 until spec.bins) if (mag[t][k] > mag[t][best]) best = k
            assertEquals(expectedBin, best, "frame $t peak bin")
            // Hann-windowed sine of amplitude A: peak magnitude ≈ A * N/4 (coherent gain 0.5) when on-bin;
            // 1000 Hz sits 0.43 bins off-centre so the peak is somewhat lower. Check the ±20 % envelope.
            val ideal = 0.5f * frameSize / 4f
            assertTrue(mag[t][best] > 0.75f * ideal && mag[t][best] < 1.05f * ideal, "peak ${mag[t][best]} vs ideal $ideal")
            // Energy far away from the peak is negligible (Hann sidelobes < -31 dB, falling fast).
            assertTrue(mag[t][best + 40] < 1e-3f * mag[t][best])
        }
        // magnitudes() one-shot agrees with Spectrogram.magnitude()
        val m2 = stft.magnitudes(x)
        assertEquals(spec.frames, m2.size)
        for (t in 0 until spec.frames) assertEquals(0f, maxAbsDiff(mag[t], m2[t], spec.bins), 0f)
    }

    @Test
    fun frameCountAndTimingFormulas() {
        val frameSize = 1024
        for (hop in intArrayOf(256, 512, 1000)) {
            val stft = Stft(frameSize, hop)
            for (len in intArrayOf(0, 1, 511, 512, 1023, 1024, 1025, 10000)) {
                assertEquals(1 + len / hop, stft.frameCount(len), "centered len=$len hop=$hop")
                val spec = stft.analyze(FloatArray(len), 48000)
                assertEquals(1 + len / hop, spec.frames)
            }
            val plain = Stft(frameSize, hop, center = false)
            assertEquals(0, plain.frameCount(1023))
            assertEquals(1, plain.frameCount(1024))
            assertEquals(1, plain.frameCount(1024 + hop - 1))
            assertEquals(2, plain.frameCount(1024 + hop))
        }
        val spec = Stft(1024, 256).analyze(FloatArray(4096), 44100)
        assertEquals(0.0, spec.frameTime(0), 0.0)
        assertEquals(256.0 / 44100, spec.frameTime(1), 1e-12)
        assertEquals(10, spec.frameAt(10 * 256.0 / 44100))
        assertEquals(513, spec.bins)
        assertEquals(22050.0, spec.binFrequency(512), 0.0)
        val plain = Stft(1024, 256, center = false).analyze(FloatArray(4096), 44100)
        assertEquals(512.0 / 44100, plain.frameTime(0), 1e-12)
    }

    @Test
    fun streamingMatchesOneShotRegardlessOfChunking() {
        val frameSize = 256
        val hop = 64
        val rnd = Random(4)
        val x = FloatArray(5000) { rnd.nextFloat() - 0.5f }
        val ref = Stft(frameSize, hop).analyze(x, 8000)
        val stft = Stft(frameSize, hop)
        for (chunk in intArrayOf(1, 7, 64, 300, 5000)) {
            stft.reset()
            val re = ArrayList<FloatArray>()
            val sink = StftFrameSink { idx, r, i ->
                assertEquals(re.size, idx)
                re += r.copyOf()
            }
            var pos = 0
            while (pos < x.size) {
                val n = minOf(chunk, x.size - pos)
                stft.process(x, pos, n, sink)
                pos += n
            }
            stft.flush(sink)
            assertEquals(ref.frames, re.size, "chunk=$chunk frame count")
            for (t in 0 until ref.frames) assertEquals(0f, maxAbsDiff(ref.re[t], re[t], ref.bins), 0f, "chunk=$chunk frame $t")
        }
        // Streaming ISTFT: push frames one at a time into an output buffer, matching the one-shot synthesis.
        val istft = Istft(frameSize, hop)
        val out = FloatArray(x.size + frameSize)
        var n = 0
        for (t in 0 until ref.frames) n += istft.pushFrame(ref.re[t], ref.im[t], out, n)
        n += istft.flush(out, n)
        assertTrue(n >= x.size - hop)
        assertTrue(maxAbsDiff(x, out, minOf(n, x.size)) < 1e-4f)
    }
}
