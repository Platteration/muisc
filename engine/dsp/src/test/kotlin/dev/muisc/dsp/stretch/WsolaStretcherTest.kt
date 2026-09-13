package dev.muisc.dsp.stretch

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.qa.ArtifactDetector
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WsolaStretcherTest {
    private val sr = 44100

    @Test
    fun stableSineKeepsFrequencyAndLevelWithoutClicks() {
        val x = Synth.sine(sr, 440.0, 2.0, amp = 0.5f)
        val stretcher = WsolaStretcher()
        val y = stretcher.stretch(AudioBuffer.mono(sr, x), 1.10)
        assertEquals(Math.round(x.size * 1.10).toInt(), y.frames)
        val out = y[0]
        val f = SigMeasure.peakFrequency(out, sr, 32768, offset = 20000, minHz = 100.0, maxHz = 2000.0)
        assertEquals(440.0, f, 440.0 * 0.005, "frequency after stretch: $f Hz")
        val levelDb = SigMeasure.db(SigMeasure.rms(out, 5000, out.size - 5000) / SigMeasure.rms(x))
        assertTrue(abs(levelDb) < 1.0, "level change $levelDb dB")
        val report = ArtifactDetector(sr, clickMinMagnitude = 0.05f).analyze(y)
        assertTrue(report.clicks.isEmpty() && report.levelJumps.isEmpty(), report.summary())
    }

    @Test
    fun ratioOneIsIdentity() {
        val song = SyntheticSong(bpm = 128.0, bars = 4, introBars = 0, outroBars = 0).render()
        val y = WsolaStretcher().stretch(song, 1.0)
        assertEquals(song.frames, y.frames)
        for (c in 0 until 2) {
            val snr = SigMeasure.snrDb(song[c], y[c])
            assertTrue(snr > 60.0, "channel $c SNR $snr dB")
        }
        // ...and with transient protection enabled it is still the identity.
        val beats = IntArray(16) { Math.round(it * 60.0 / 128.0 * sr).toInt() }
        val yt = WsolaStretcher().stretch(song, 1.0, beats)
        assertTrue(SigMeasure.snrDb(song[0], yt[0]) > 60.0)
    }

    @Test
    fun stretchToLengthIsExactAndKeepsBeatSpacing() {
        val spec = SyntheticSong(bpm = 120.0, bars = 8, introBars = 0, outroBars = 0, stereo = false)
        val song = spec.render()
        val beatFrames = IntArray(spec.bars * spec.beatsPerBar) { Math.round(spec.beatTimes()[it] * sr).toInt() }
        val beatGap = (spec.beatSec * sr).toInt()
        val refOnsets = SigMeasure.kickOnsets(song[0], sr, beatGap / 2)
        assertTrue(refOnsets.size >= 28, "reference onsets ${refOnsets.size}")
        val stretcher = WsolaStretcher()
        for (ratio in doubleArrayOf(0.95, 1.08)) {
            val target = Math.round(song.frames * ratio).toInt()
            val y = stretcher.stretchToLength(song, target, beatFrames)
            assertEquals(target, y.frames, "exact length for ratio $ratio")
            val onsets = SigMeasure.kickOnsets(y[0], sr, (beatGap * ratio / 2).toInt())
            assertEquals(refOnsets.size, onsets.size, "onset count for ratio $ratio (no doubled / dropped kicks)")
            val tol = 0.005 * sr
            for (i in refOnsets.indices) {
                val expected = refOnsets[i] * ratio
                assertTrue(abs(onsets[i] - expected) <= tol, "ratio $ratio kick $i at ${onsets[i]}, expected $expected (±$tol)")
            }
            val report = ArtifactDetector(sr).analyze(y)
            assertTrue(report.clicks.isEmpty(), report.summary())
        }
    }

    @Test
    fun ratioGlideProducesMonotonicallyIncreasingPeriod() {
        val seconds = 8.0
        val clicks = Synth.clickTrack(sr, 240.0, seconds) // one click every 250 ms
        val nClicks = (seconds * 4).toInt()
        val transients = IntArray(nClicks) { Math.round(it * 0.25 * sr).toInt() }
        val approxOut = (clicks.size * 1.05).toInt()
        val y = WsolaStretcher().stretch(AudioBuffer.mono(sr, clicks), { o -> 1.0 + 0.1 * (o.toDouble() / approxOut).coerceIn(0.0, 1.0) }, transients)
        val onsets = SigMeasure.kickOnsets(y[0], sr, (0.15 * sr).toInt(), lowPassHz = 3000.0)
        assertTrue(onsets.size >= nClicks - 1, "onsets found: ${onsets.size}")
        val intervals = IntArray(onsets.size - 1) { onsets[it + 1] - onsets[it] }
        for (i in 1 until intervals.size) {
            assertTrue(intervals[i] >= intervals[i - 1] - 2, "interval $i (${intervals[i]}) < previous (${intervals[i - 1]})")
        }
        val first = intervals[0] / (0.25 * sr)
        val last = intervals[intervals.size - 1] / (0.25 * sr)
        assertEquals(1.0, first, 0.02, "first interval ratio")
        assertTrue(last > 1.08 && last < 1.12, "last interval ratio $last")
        // Output length is the integral of the ratio: between 1.0x and 1.1x, near 1.05x.
        assertTrue(y.frames > clicks.size * 1.03 && y.frames < clicks.size * 1.08, "output frames ${y.frames}")
    }

    @Test
    fun streamingInChunksMatchesOneShotAndReportsLength() {
        val song = SyntheticSong(bpm = 100.0, bars = 3, introBars = 0, outroBars = 0).render()
        val stretcher = WsolaStretcher()
        val ratio = 1.07
        val oneShot = stretcher.stretch(song, ratio)
        val stream = stretcher.createStream(sr, 2)
        stream.setRatio(ratio)
        assertTrue(stream.latencyFrames in 1000..4000, "latency ${stream.latencyFrames}")
        val out = Array(2) { FloatArray(oneShot.frames + 4096) }
        var produced = 0
        var pos = 0
        var chunk = 37
        while (pos < song.frames) {
            val n = minOf(chunk, song.frames - pos)
            stream.push(song.channels, n, pos)
            pos += n
            chunk = (chunk * 7 + 13) % 3000 + 1
            produced += stream.pull(out, stream.available(), produced)
        }
        assertEquals(song.frames, stream.inputFramesPushed)
        stream.flush()
        assertEquals(Math.round(song.frames * ratio).toInt(), stream.totalOutputFrames)
        produced += stream.pull(out, stream.available(), produced)
        assertEquals(stream.totalOutputFrames, produced)
        assertEquals(oneShot.frames, produced)
        for (c in 0 until 2) for (i in 0 until produced) assertEquals(oneShot[c][i], out[c][i], 0f, "ch$c sample $i")
    }

    @Test
    fun stereoChannelsShareTheOffset() {
        val left = Synth.sine(sr, 330.0, 1.5, amp = 0.6f)
        val right = FloatArray(left.size) { left[it] * 0.5f }
        val y = WsolaStretcher().stretch(AudioBuffer.stereo(sr, left, right), 0.93)
        for (i in 0 until y.frames) assertEquals(y[0][i] * 0.5f, y[1][i], 1e-6f, "sample $i")
    }

    @Test
    fun transientProtectionCopiesEachClickOnceAtNominalTime() {
        val clicks = Synth.clickTrack(sr, 120.0, 4.0)
        val transients = IntArray(8) { Math.round(it * 0.5 * sr).toInt() }
        val ratio = 1.1
        val y = WsolaStretcher().stretch(AudioBuffer.mono(sr, clicks), ratio, transients)
        val onsets = SigMeasure.kickOnsets(y[0], sr, (0.2 * sr).toInt(), lowPassHz = 3000.0)
        val ref = SigMeasure.kickOnsets(clicks, sr, (0.2 * sr).toInt(), lowPassHz = 3000.0)
        assertEquals(ref.size, onsets.size, "click count")
        for (i in ref.indices) assertTrue(abs(onsets[i] - ref[i] * ratio) <= 0.002 * sr, "click $i at ${onsets[i]} vs ${ref[i] * ratio}")
    }
}
