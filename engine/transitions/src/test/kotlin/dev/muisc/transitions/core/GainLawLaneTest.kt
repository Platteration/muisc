package dev.muisc.transitions.core

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.LoudnessInfo
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GainLawLaneTest {
    private fun analysis(lufs: Float) = TrackAnalysis(sourceId = "t", fingerprint = "t", sampleRate = 44100, totalFrames = 44100, trimStartFrame = 0, trimEndFrame = 44100,
        tempo = TempoEstimate(120.0, 1f), grid = BeatGrid.EMPTY, key = KeyEstimate(MusicalKey(0, Mode.MAJOR), 0.5f), loudness = LoudnessInfo(lufs, -1f))

    @Test
    fun deckGainFollowsTargetAndClamps() {
        val prefs = TransitionPrefs(targetLufs = -14.0)
        assertEquals(0f, DeckGain.of(analysis(-14f), prefs))
        assertEquals(-4f, DeckGain.of(analysis(-10f), prefs))
        assertEquals(6f, DeckGain.of(analysis(-30f), prefs), "boost clamps at +6 dB")
        assertEquals(-12f, DeckGain.of(analysis(2f), prefs), "cut clamps at -12 dB")
        assertEquals(0f, DeckGain.of(analysis(-20f), prefs.copy(targetLufs = Double.NaN)), "NaN target = no matching")
        assertEquals(0f, DeckGain.of(analysis(Float.NEGATIVE_INFINITY), prefs), "silent track = 0 dB")
        assertEquals(1f, DeckGain.linear(0f))
        assertEquals(0.5f, DeckGain.linear(-6.0206f), 1e-4f)
        val buf = AudioBuffer.stereo(44100, FloatArray(10) { 0.5f }, FloatArray(10) { -0.25f })
        DeckGain.applyInPlace(buf, -6.0206f)
        assertEquals(0.25f, buf[0][3], 1e-4f); assertEquals(-0.125f, buf[1][7], 1e-4f)
        val same = DeckGain.applyInPlace(buf, 0f)
        assertTrue(same === buf && buf[0][3] == 0.25f)
    }

    @Test
    fun crossfadeLawsAreComplementary() {
        for (x in doubleArrayOf(0.0, 0.1, 0.25, 0.5, 0.75, 1.0)) {
            val (ao, bi) = CrossfadeLaw.gains(x, FadeLaw.EQUAL_POWER)
            assertEquals(1.0, (ao * ao + bi * bi).toDouble(), 1e-6, "equal-power squares sum to 1 at $x")
            val (lo, li) = CrossfadeLaw.gains(x, FadeLaw.LINEAR)
            assertEquals(1.0, (lo + li).toDouble(), 1e-6, "linear gains sum to 1 at $x")
            for (law in FadeLaw.entries) {
                val (o, i) = CrossfadeLaw.gains(x, law)
                assertEquals(CrossfadeLaw.fadeIn(1.0 - x, law), o, 1e-6f, "fadeOut(x) == fadeIn(1 - x) for $law")
                assertTrue(o in 0f..1f && i in 0f..1f)
            }
        }
        assertEquals(1f to 0f, CrossfadeLaw.gains(0.0, FadeLaw.S_CURVE))
        assertEquals(0f to 1f, CrossfadeLaw.gains(1.0, FadeLaw.EXP))
        assertEquals(sqrt(0.5).toFloat(), CrossfadeLaw.gains(0.5, FadeLaw.EQUAL_POWER).first, 1e-6f)
        assertEquals(FadeLaw.EQUAL_POWER, CrossfadeLaw.parse("equal_power"))
        assertEquals(null, CrossfadeLaw.parse("sigmoid"))
    }

    @Test
    fun laneValuesFillAndAutomation() {
        val lane = Lane("g").add(1000, 0.0, FadeLaw.EQUAL_POWER).add(0, 1.0).add(500, 1.0, FadeLaw.EQUAL_POWER)
        assertEquals(listOf(0L, 500L, 1000L), lane.points.map { it.frame }, "points sorted by frame")
        assertEquals(1.0, lane.valueAt(-50))
        assertEquals(1.0, lane.valueAt(250))
        assertEquals(1.0, lane.valueAt(500))
        assertEquals(0.0, lane.valueAt(1000))
        assertEquals(0.0, lane.valueAt(5000))
        assertEquals(sqrt(0.5), lane.valueAt(750), 1e-9, "falling EQUAL_POWER segment is cos(pi/2 t)")
        val partner = Lane("h").add(500, 0.0, FadeLaw.EQUAL_POWER).add(1000, 1.0)
        for (f in 500L..1000L step 25) {
            val a = lane.valueAt(f); val b = partner.valueAt(f)
            assertEquals(1.0, a * a + b * b, 1e-9, "power-complementary at $f")
        }
        // fillGains == valueAt sample by sample, across every region, in blocks
        val n = 1300
        val ref = DoubleArray(n) { lane.valueAt(it - 100L) }
        val out = FloatArray(n)
        lane.fillGains(out, -100L)
        for (i in 0 until n) assertEquals(ref[i], out[i].toDouble(), 1e-6, "fill vs valueAt at ${i - 100}")
        val blocks = FloatArray(n)
        var done = 0
        while (done < n) { val m = minOf(37, n - done); lane.fillGains(blocks, -100L + done, m, done); done += m }
        for (i in 0 until n) assertEquals(out[i], blocks[i], 0f, "block fill at $i")
        // rising EXP vs falling EXP mirror; LINEAR/S_CURVE symmetric
        val up = Lane("u").add(0, 0.0, FadeLaw.EXP).add(100, 1.0)
        val down = Lane("d").add(0, 1.0, FadeLaw.EXP).add(100, 0.0)
        for (f in 0L..100L) assertEquals(up.valueAt(f), down.valueAt(100 - f), 1e-12)
        val sUp = Lane("s").add(0, 0.0, FadeLaw.S_CURVE).add(100, 1.0)
        val sDown = Lane("s2").add(0, 1.0, FadeLaw.S_CURVE).add(100, 0.0)
        for (f in 0L..100L) assertEquals(1.0, sUp.valueAt(f) + sDown.valueAt(f), 1e-12)
        // applyInPlace
        val sig = FloatArray(1300) { 1f }
        lane.applyInPlace(sig, -100L)
        for (i in 0 until n) assertEquals(out[i], sig[i], 1e-6f)
        // empty lane is unity
        val e = Lane("e")
        assertEquals(1.0, e.valueAt(42)); val ef = FloatArray(5); e.fillGains(ef, 0); assertTrue(ef.all { it == 1f })
        // automation lane in seconds with densified curve
        val auto = lane.toAutomationLane(1000, curvePoints = 4)
        assertEquals("g", auto.id)
        assertEquals(0.0, auto.points.first().outputSec); assertEquals(1.0, auto.points.last().outputSec)
        assertEquals(3 + 3, auto.points.size, "the one curved segment (500 -> 1000) is densified with 3 intermediate points")
        assertTrue(auto.points.zipWithNext().all { (p, q) -> q.outputSec >= p.outputSec })
        val mid = auto.points.first { abs(it.outputSec - 0.75) < 1e-9 }
        assertEquals(sqrt(0.5), mid.value, 1e-9)
    }
}
