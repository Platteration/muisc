package dev.muisc.transitions.core

import dev.muisc.analysis.model.Mode
import dev.muisc.dsp.loudness.TruePeak
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderReportsTest {
    private val ctx = RenderContext(TransitionPrefs())
    private val song by lazy { SongFixtures.song("A", 120.0, 0, Mode.MAJOR, bars = 12, introBars = 0, outroBars = 0) }

    /** Adds a raised-cosine-windowed sine burst of [amp] over `[start, start + len)` on both channels (no hard edges). */
    private fun addBurst(audio: dev.muisc.audio.AudioBuffer, start: Int, len: Int, amp: Float, freqHz: Double = 80.0) {
        for (c in 0 until audio.channelCount) for (i in 0 until len) {
            val w = 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / len)
            audio[c][start + i] += (amp * w * kotlin.math.sin(2 * Math.PI * freqHz * i / audio.sampleRate)).toFloat()
        }
    }

    @Test
    fun buildMeasuresAndFlagsArtifacts() {
        val audio = song.audio.slice(44100 * 2, 44100 * 6)
        val r = RenderReports.build(audio, 12, ratioTrace = floatArrayOf(1f, 1.02f), metrics = mapOf("custom" to 3.0), warnings = listOf("note"))
        assertEquals(12, r.renderMillis)
        assertEquals(audio.peak(), r.peak)
        assertTrue(r.truePeakDbtp >= 20 * kotlin.math.log10(r.peak) - 0.01, "true peak >= sample peak")
        assertTrue(r.integratedLufs > -30 && r.integratedLufs < 0, "loudness plausible: ${r.integratedLufs}")
        assertEquals(listOf("note"), r.warnings, "clean synthetic material yields no artifact warnings")
        assertEquals(3.0, r.metrics["custom"]); assertEquals(0.0, r.metrics["clicks"]); assertEquals(audio.frames.toDouble(), r.metrics["frames"])
        assertEquals(2, r.ratioTrace.size)
        // a 0.5 step held for 200 samples on a 16th between hat hits (song time 3.125 s) is a pair of clicks
        val clicky = audio.copy()
        val pos = 44100 + 5512
        for (i in pos until pos + 200) clicky[0][i] += 0.5f
        val r2 = RenderReports.build(clicky, 0)
        val clickFrames = r2.warnings.filter { it.startsWith("click at frame") }.map { it.removePrefix("click at frame ").takeWhile { c -> c.isDigit() }.toInt() }
        assertTrue(clickFrames.any { abs(it - pos) <= 2 || abs(it - (pos + 200)) <= 2 }, r2.warnings.toString())
        assertTrue((r2.metrics["clicks"] ?: 0.0) >= 1.0)
        val empty = RenderReports.build(dev.muisc.audio.AudioBuffer.silence(44100, 2, 0), 0)
        assertEquals(0f, empty.peak)
    }

    @Test
    fun finalizeIsBitTransparentBelowCeilingAndLimitsAbove() {
        val audio = song.audio.slice(44100 * 2, 44100 * 5)
        audio.applyGainInPlace(0.5f)
        val before = audio.copy()
        val f = RenderReports.finalize(audio, ctx)
        assertFalse(f.limited); assertEquals(0.0, f.gainReductionDb); assertTrue(f.warnings.isEmpty())
        for (c in 0 until 2) assertTrue(audio[c].contentEquals(before[c]), "untouched when under the ceiling")

        // Peak in the middle: limited to the ceiling, guard regions bit-identical, no warning.
        val loud = song.audio.slice(44100 * 2, 44100 * 5)
        loud.applyGainInPlace(0.5f)
        val mid = loud.frames / 2
        addBurst(loud, mid - 2000, 4000, 1.4f)
        val ref = loud.copy()
        assertTrue(TruePeak.measureDbtp(loud) > 0.0)
        val f2 = RenderReports.finalize(loud, ctx)
        assertTrue(f2.limited && f2.gainReductionDb > 2.0 && f2.truePeakBeforeDbtp > 0.0)
        assertTrue(f2.warnings.isEmpty(), f2.warnings.toString())
        assertTrue(TruePeak.measureDbtp(loud) <= -1.0 + 0.2, "true peak after: ${TruePeak.measureDbtp(loud)}")
        val g = Splice.GUARD_FRAMES
        for (c in 0 until 2) {
            for (i in 0 until g) assertEquals(ref[c][i], loud[c][i], 1e-3f, "pre-roll frame $i")
            for (i in loud.frames - g until loud.frames) assertEquals(ref[c][i], loud[c][i], 1e-3f, "post-roll frame $i")
        }
        // Time alignment: the limiter's delay is compensated (the loud burst is still centred at mid).
        var maxAt = 0; var maxV = 0f
        for (i in loud[0].indices) if (abs(loud[0][i]) > maxV) { maxV = abs(loud[0][i]); maxAt = i }
        assertTrue(abs(maxAt - mid) < 2200, "peak moved to $maxAt (mid $mid)")

        // Peak straddling the end of the pre-roll: the 5 ms attack ramp reaches into the guard; it is restored and reported.
        val edge = song.audio.slice(44100 * 2, 44100 * 5)
        edge.applyGainInPlace(0.5f)
        addBurst(edge, g - 100, 400, 1.4f, freqHz = 400.0)
        val ref2 = edge.copy()
        val f3 = RenderReports.finalize(edge, ctx)
        assertTrue(f3.limited)
        assertTrue(f3.warnings.any { it.contains("pre-roll") }, f3.warnings.toString())
        for (c in 0 until 2) for (i in 0 until g) assertEquals(ref2[c][i], edge[c][i], 1e-3f, "restored pre-roll frame $i")
        for (c in 0 until 2) for (i in edge.frames - g until edge.frames) assertEquals(ref2[c][i], edge[c][i], 1e-3f)
    }
}
