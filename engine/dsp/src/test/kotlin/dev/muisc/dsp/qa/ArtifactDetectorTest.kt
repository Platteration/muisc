package dev.muisc.dsp.qa

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.Synth
import dev.muisc.audio.synth.SyntheticSong
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactDetectorTest {
    private val sr = 44100

    private fun song(bpm: Double = 120.0, bars: Int = 8) =
        SyntheticSong(bpm = bpm, tonic = 9, mode = Mode.MINOR, bars = bars, introBars = 2, outroBars = 2, sampleRate = sr)

    /** Adds a step of [height] from sample [at] onwards (a hard crossfade discontinuity). */
    private fun addStep(x: FloatArray, at: Int, height: Float) { for (i in at until x.size) x[i] += height }

    @Test
    fun stepInSmoothSignalIsAClickAndCleanSineIsNot() {
        val det = ArtifactDetector(sr)
        val clean = Synth.sine(sr, 440.0, 2.0, amp = 0.5f)
        assertTrue(det.detectClicks(clean).isEmpty(), "pure sine flagged: ${det.detectClicks(clean)}")

        val x = clean.copyOf()
        val at = 30000
        addStep(x, at, 0.5f)
        val clicks = det.detectClicks(x)
        assertEquals(1, clicks.size, "expected exactly one click, got $clicks")
        assertTrue(abs(clicks[0].frame - at) <= 1, "click at ${clicks[0].frame}, step at $at")
        assertEquals(0.5f, clicks[0].magnitude, 0.01f)
        assertTrue(clicks[0].ratio > 20f, "ratio ${clicks[0].ratio}")

        // Two steps far apart -> two clicks; a step back down (a dropout of 1 ms) -> two clicks 44 samples apart.
        val y = clean.copyOf()
        addStep(y, 10000, 0.5f)
        addStep(y, 60000, -0.5f)
        assertEquals(2, det.detectClicks(y).size)

        // Smaller steps below the magnitude floor are ignored on purpose (SyntheticSong note truncations).
        val z = clean.copyOf()
        addStep(z, 30000, 0.05f)
        assertTrue(det.detectClicks(z).isEmpty())
        // ...but a stricter detector finds them.
        assertEquals(1, ArtifactDetector(sr, clickMinMagnitude = 0.02f).detectClicks(z).size)
    }

    @Test
    fun syntheticSongIsNotFlaggedButAStepInsideItIs() {
        val det = ArtifactDetector(sr)
        val s = song()
        val buf = s.render()
        val report = det.analyze(buf)
        assertTrue(report.clicks.isEmpty(), report.summary())
        assertTrue(report.isClean(), report.summary())

        // A 0.5 step in the pad-only intro.
        val left = buf[0].copyOf()
        val introAt = buf.secondsToFrames(s.bodyStartSec * 0.6) // mid-bar, pad fully sustained
        addStep(left, introAt, 0.5f)
        val c1 = det.detectClicks(left)
        assertEquals(1, c1.size, "intro step: $c1")
        assertTrue(abs(c1[0].frame - introAt) <= 1)

        // A 0.5 step in the body between two hat bursts (the drums are playing, kicks/hats must not mask it).
        val right = buf[1].copyOf()
        val beat = s.bodyStartSec + 4 * s.beatSec
        val bodyAt = buf.secondsToFrames(beat + 0.175)
        addStep(right, bodyAt, 0.5f)
        val c2 = det.detectClicks(right)
        assertEquals(1, c2.size, "body step: $c2")
        assertTrue(abs(c2[0].frame - bodyAt) <= 1)

        // The full-buffer report attributes each click to its channel.
        val both = det.analyze(AudioBuffer.stereo(sr, left, right))
        assertEquals(2, both.clicks.size)
        assertEquals(setOf(0, 1), both.clicks.map { it.channel }.toSet())
        assertFalse(both.isClean())
        assertTrue(both.summary().contains("clicks: 2"))
    }

    @Test
    fun otherTemposAndSongVariantsStayClean() {
        val det = ArtifactDetector(sr)
        for (bpm in doubleArrayOf(90.0, 128.0, 174.0)) {
            val buf = SyntheticSong(bpm = bpm, bars = 6, introBars = 1, outroBars = 1, outroFade = true, sampleRate = sr, leadingSilenceSec = 0.5, trailingSilenceSec = 0.5).render()
            val r = det.analyze(buf)
            assertTrue(r.isClean(), "bpm $bpm: ${r.summary()}")
        }
    }

    @Test
    fun levelJumpsDetectSustainedStepsNotOnsetsOrFades() {
        val det = ArtifactDetector(sr)
        // Sine at -6 dBFS dropping to -26 dBFS half way: one drop of ~20 dB.
        val x = Synth.sine(sr, 300.0, 3.0, amp = 0.5f)
        val half = x.size / 2
        for (i in half until x.size) x[i] *= 0.1f
        val jumps = det.detectLevelJumps(x)
        assertEquals(1, jumps.size, "$jumps")
        assertEquals(-20f, jumps[0].deltaDb, 0.5f)
        assertTrue(abs(jumps[0].frame - half) <= det.levelWindowMs / 1000.0 * sr + 1, "jump at ${jumps[0].frame} expected near $half")
        assertEquals(-9.0f, jumps[0].fromDb, 0.2f) // 0.5/sqrt(2) = -9.03 dBFS

        // The reverse (a rise) is found too.
        val up = Synth.sine(sr, 300.0, 3.0, amp = 0.5f)
        for (i in 0 until half) up[i] *= 0.1f
        val rises = det.detectLevelJumps(up)
        assertEquals(1, rises.size)
        assertEquals(20f, rises[0].deltaDb, 0.5f)

        // Negatives: a smooth 2 s linear fade, an isolated drum-like transient, and steps from/to silence.
        val fade = Synth.sine(sr, 300.0, 3.0, amp = 0.5f)
        Synth.fadeOutInPlace(fade, 2 * sr)
        assertTrue(det.detectLevelJumps(fade).isEmpty())
        val burst = Synth.sine(sr, 300.0, 3.0, amp = 0.05f)
        Synth.addBurst(burst, sr, sr, 200.0, 0.03, 0.8f)
        assertTrue(det.detectLevelJumps(burst).isEmpty(), "transient flagged: ${det.detectLevelJumps(burst)}")
        val gated = FloatArray(3 * sr)
        val tone = Synth.sine(sr, 300.0, 1.0, amp = 0.5f)
        System.arraycopy(tone, 0, gated, sr, sr)
        assertTrue(det.detectLevelJumps(gated).isEmpty())
        // A 6 dB step is below the default 10 dB threshold but above a stricter 3 dB one.
        val small = Synth.sine(sr, 300.0, 3.0, amp = 0.5f)
        for (i in half until x.size) small[i] *= 0.5f
        assertTrue(det.detectLevelJumps(small).isEmpty())
        assertEquals(1, ArtifactDetector(sr, levelJumpDb = 3f).detectLevelJumps(small).size)
    }

    @Test
    fun clippingRuns() {
        val det = ArtifactDetector(sr)
        val x = Synth.sine(sr, 100.0, 0.5, amp = 0.9f)
        assertTrue(det.detectClipping(x).isEmpty())
        // Hard-clip a loud sine: every half cycle produces a run of clipped samples.
        val loud = Synth.sine(sr, 100.0, 0.1, amp = 1.5f)
        for (i in loud.indices) loud[i] = loud[i].coerceIn(-1f, 1f)
        val runs = det.detectClipping(loud)
        assertEquals(20, runs.size) // 0.1 s at 100 Hz = 10 cycles x 2 half-cycles
        for (r in runs) assertTrue(r.length >= 3)
        // Analytic run length: |1.5 sin| >= 0.999 for the fraction of the half cycle where sin >= 0.666.
        val expectedLen = (sr / 100.0) * (1 - 2 * Math.asin(0.999 / 1.5) / Math.PI) / 2
        for (r in runs) assertEquals(expectedLen, r.length.toDouble(), 2.0)
        // Exactly 3 consecutive full-scale samples count; 2 do not.
        val y = FloatArray(1000)
        y[100] = 1f; y[101] = -1f; y[102] = 1f
        y[500] = 1f; y[501] = 1f
        val r = det.detectClipping(y)
        assertEquals(listOf(ClipRun(100, 3)), r)
        // A run touching the end of the buffer is reported too.
        val z = FloatArray(100); for (i in 95 until 100) z[i] = -0.9995f
        assertEquals(listOf(ClipRun(95, 5)), det.detectClipping(z))
    }

    @Test
    fun dcOffsetAndSilenceGaps() {
        val det = ArtifactDetector(sr)
        // phase != 0 so the tone does not start with an exact zero sample (which would join the silent run).
        val x = Synth.sine(sr, 440.0, 1.0, amp = 0.5f, phase = 0.1)
        assertEquals(0f, det.dcOffset(x), 1e-3f)
        val off = FloatArray(x.size) { x[it] + 0.1f }
        assertEquals(0.1f, det.dcOffset(off), 1e-4f)
        assertEquals(listOf(0), det.analyze(off).dcOffsetExceeded)
        assertTrue(det.analyze(x).dcOffsetExceeded.isEmpty())

        // Silence: 0.5 s lead-in and 0.5 s tail are fine; a 150 ms hole in the middle is a gap, a 50 ms one is not.
        val g = FloatArray(3 * sr)
        System.arraycopy(x, 0, g, sr / 2, sr)
        System.arraycopy(x, 0, g, sr / 2 + sr + 150 * sr / 1000, sr) // hole of 150 ms
        val gaps = det.detectSilenceGaps(g)
        assertEquals(1, gaps.size, "$gaps")
        assertEquals(sr / 2 + sr, gaps[0].start)
        assertEquals(150 * sr / 1000, gaps[0].length)
        val h = FloatArray(3 * sr)
        System.arraycopy(x, 0, h, sr / 2, sr)
        System.arraycopy(x, 0, h, sr / 2 + sr + 50 * sr / 1000, sr)
        assertTrue(det.detectSilenceGaps(h).isEmpty())
        // Sample-exact zero crossings of a sine are never a gap.
        assertTrue(det.detectSilenceGaps(x).isEmpty())
        val report = det.analyze(g)
        assertFalse(report.isClean())
        assertTrue(report.summary().contains("silence gaps: 1"))
        assertTrue(report.levelJumps.isEmpty(), "to/from silence is not a level jump: ${report.levelJumps}")
    }
}
