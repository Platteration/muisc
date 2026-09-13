package dev.muisc.dsp.gain

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurvesTest {

    @Test
    fun equalPowerGainsHaveUnitSumOfSquares() {
        for (i in 0..100) {
            val x = i / 100.0
            val a = Curves.equalPowerIn(x); val b = Curves.equalPowerOut(x)
            assertEquals(1.0, a * a + b * b, 1e-12)
            assertEquals(a, Curves.fadeIn(FadeShape.EQUAL_POWER, x), 0.0)
            assertEquals(b, Curves.fadeOut(FadeShape.EQUAL_POWER, x), 1e-15)
            val p = Curves.pLaw(x, 0.5); val q = Curves.pLaw(1 - x, 0.5)
            assertEquals(1.0, p * p + q * q, 1e-12)
            assertEquals(1.0, Curves.pLaw(x, 1.0) + Curves.pLaw(1 - x, 1.0), 1e-12)
        }
        assertEquals(sqrt(0.5), Curves.equalPowerIn(0.5), 1e-12)
    }

    @Test
    fun everyShapeIsMonotonicFromZeroToOne() {
        for (shape in FadeShape.entries) {
            assertEquals(0.0, Curves.fadeIn(shape, 0.0), 1e-12, "$shape at 0")
            assertEquals(1.0, Curves.fadeIn(shape, 1.0), 1e-12, "$shape at 1")
            var prev = -1.0
            for (i in 0..200) {
                val v = Curves.fadeIn(shape, i / 200.0)
                assertTrue(v >= prev - 1e-12, "$shape not monotonic at $i")
                prev = v
            }
            assertEquals(0.0, Curves.fadeIn(shape, -0.5), 1e-12)
            assertEquals(1.0, Curves.fadeIn(shape, 1.5), 1e-12)
        }
        assertEquals(0.5, Curves.sCurve(0.5), 1e-12)
        assertTrue(Curves.exponential(0.5) < 0.5 && Curves.logarithmic(0.5) > 0.5)
        assertEquals(Curves.exponential(0.3), 1 - Curves.logarithmic(0.7), 1e-12)
        assertEquals(0.0, Curves.dbLinear(0.0), 0.0)
        assertEquals(Curves.dbToLinear(-30.0), Curves.dbLinear(0.5), 1e-12)
        assertEquals(1.0, Curves.dbLinear(1.0), 1e-12)
    }

    @Test
    fun rampHitsEndpointsAndFollowsShape() {
        val r = Curves.ramp(11, 0.2f, 1.0f, FadeShape.LINEAR)
        assertEquals(0.2f, r[0], 0f); assertEquals(1.0f, r[10], 1e-7f); assertEquals(0.6f, r[5], 1e-6f)
        val s = Curves.ramp(101, 1f, 0f, FadeShape.EQUAL_POWER)
        assertEquals(1f, s[0], 0f); assertEquals(0f, s[100], 1e-7f)
        assertEquals((1 - sqrt(0.5)).toFloat(), s[50], 1e-6f)
        val open = Curves.ramp(10, 0f, 1f, FadeShape.LINEAR, inclusiveEnd = false)
        assertEquals(0.9f, open[9], 1e-6f)
        val reused = FloatArray(16)
        assertTrue(Curves.ramp(4, 0f, 3f, out = reused) === reused)
        assertEquals(3f, reused[3], 1e-7f); assertEquals(0f, reused[4], 0f)
    }

    @Test
    fun dbConversions() {
        assertEquals(0.5, Curves.dbToLinear(-6.020599913279624), 1e-12)
        assertEquals(6.020599913279624, Curves.linearToDb(2.0), 1e-12)
        assertEquals(0.0, Curves.linearToDb(1.0), 0.0)
        assertEquals(-160.0, Curves.linearToDb(0.0), 0.0)
        assertEquals(-160.0, Curves.linearToDb(1e-12), 0.0)
        assertEquals(-20.0, Curves.linearToDb(-0.1), 1e-12)
        assertEquals(0.5f, Curves.dbToLinear(-6.0206f), 1e-5f)
        assertEquals(-40f, Curves.linearToDb(0.01f), 1e-4f)
        for (db in doubleArrayOf(-80.0, -12.0, 0.0, 6.0)) assertEquals(db, Curves.linearToDb(Curves.dbToLinear(db)), 1e-10)
    }

    @Test
    fun laneInterpolatesWithPerSegmentShapes() {
        val lane = Lane(listOf(LanePoint(3.0, 0f), LanePoint(0.0, 0f, FadeShape.EQUAL_POWER), LanePoint(1.0, 1f, FadeShape.LINEAR)))
        assertEquals(0.0, lane.startX, 0.0); assertEquals(3.0, lane.endX, 0.0)
        assertEquals(sqrt(0.5).toFloat(), lane.sample(0.5), 1e-6f)
        assertEquals(1f, lane.sample(1.0), 0f)
        assertEquals(0.5f, lane.sample(2.0), 1e-6f)
        assertEquals(0f, lane.sample(-1.0), 0f)
        assertEquals(0f, lane.sample(10.0), 0f)
        val out = FloatArray(5)
        lane.fill(out, 5, 1.0, 0.5)
        assertEquals(listOf(1f, 0.75f, 0.5f, 0.25f, 0f), out.toList())
        // many points: binary search picks the right segment
        val many = Lane((0..99).map { LanePoint(it.toDouble(), it.toFloat()) })
        for (k in 0 until 990) assertEquals(k / 10f, many.sample(k / 10.0), 1e-5f)
        val seg = Lane.segment(0.0, 1f, 2.0, 0f, FadeShape.S_CURVE)
        assertEquals(0.5f, seg.sample(1.0), 1e-6f)
        assertEquals(1f - Curves.sCurve(0.25).toFloat(), seg.sample(0.5), 1e-6f)
    }

    @Test
    fun applyGainRampAndMixHelpers() {
        val ch = FloatArray(8) { 1f }
        Curves.applyGainRamp(ch, 0f, 1f)
        for (i in 0 until 8) assertEquals(i / 8f, ch[i], 1e-6f)
        val dst = FloatArray(4) { 1f }
        Curves.mixAdd(dst, floatArrayOf(1f, 2f, 3f, 4f), 0.5f)
        assertEquals(listOf(1.5f, 2f, 2.5f, 3f), dst.toList())
        val d2 = FloatArray(4)
        Curves.mixAddRamp(d2, floatArrayOf(1f, 1f, 1f, 1f), 1f, 0f, FadeShape.LINEAR)
        assertEquals(listOf(1f, 0.75f, 0.5f, 0.25f), d2.map { Math.round(it * 1e4f) / 1e4f })
        val d3 = FloatArray(4)
        Curves.mixAddRamp(d3, floatArrayOf(2f, 2f, 2f, 2f), 0f, 1f, FadeShape.EQUAL_POWER)
        assertEquals(2f * Curves.equalPowerIn(0.5).toFloat(), d3[2], 1e-6f)
        val a = FloatArray(4) { 1f }; val b = FloatArray(4) { 1f }; val mix = FloatArray(4)
        Curves.crossfade(mix, a, b, 4, FadeShape.EQUAL_POWER, 0.5, 0.5)
        for (v in mix) assertEquals(sqrt(2.0).toFloat(), v, 1e-6f)
        Curves.crossfade(mix, a, b, 4, FadeShape.LINEAR)
        for (v in mix) assertEquals(1f, v, 1e-6f)
        val g = FloatArray(3) { 2f }
        Curves.applyGain(g, 0.25f, 1, 3)
        assertEquals(listOf(2f, 0.5f, 0.5f), g.toList())
    }
}
