package dev.muisc.dsp.stems

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.gain.FadeShape
import dev.muisc.dsp.gain.Lane
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StemUtilsTest {
    private val sr = 48000

    /** Stems whose four buffers are constant-valued (stereo, [frames] frames): drums = d, bass = b, ... */
    private fun constantStems(frames: Int, d: Float, b: Float, v: Float, o: Float): Stems {
        fun buf(value: Float) = AudioBuffer(sr, Array(2) { FloatArray(frames) { value } })
        return Stems(sr, buf(d), buf(b), buf(v), buf(o), StemQuality.PSEUDO)
    }

    @Test
    fun mixWithGainsScalesAndSelectsStems() {
        val n = 1000
        val s = constantStems(n, 1f, 2f, 4f, 8f)
        // Unity everywhere == sum().
        val unity = StemUtils.mixWithGains(s)
        val sum = s.sum()
        for (c in 0 until 2) for (i in 0 until n) { assertEquals(15f, unity[c][i], 0f); assertEquals(sum[c][i], unity[c][i], 0f) }
        // Named form: cut the bass.
        val noBass = StemUtils.mixWithGains(s, bass = 0f)
        assertEquals(13f, noBass[1][n / 2], 0f)
        // Map form: absent kinds default to 0 (solo) ...
        val solo = StemUtils.mixWithGains(s, mapOf(StemKind.DRUMS to 0.5f))
        assertEquals(0.5f, solo[0][7], 0f)
        // ... unless defaultGain says otherwise.
        val cut = StemUtils.mixWithGains(s, mapOf(StemKind.VOCALS to 0f), defaultGain = 1f)
        assertEquals(11f, cut[0][7], 0f)
        // Into-variant accumulates over a range with offsets and allocates the output only once.
        val dst = AudioBuffer.silence(sr, 2, 50)
        StemUtils.mixWithGainsInto(dst, s, floatArrayOf(1f, 1f, 0f, 0f), frames = 10, dstOffset = 5, srcOffset = 100)
        StemUtils.mixWithGainsInto(dst, s, floatArrayOf(0f, 0f, 1f, 0f), frames = 10, dstOffset = 5, srcOffset = 100)
        assertEquals(0f, dst[0][4], 0f); assertEquals(7f, dst[0][5], 0f); assertEquals(7f, dst[1][14], 0f); assertEquals(0f, dst[0][15], 0f)
    }

    @Test
    fun crossfadeFollowsPerStemLanesAnalytically() {
        val n = 3000
        val a = constantStems(n, 1f, 2f, 4f, 8f)
        val b = constantStems(n, -1f, -2f, -4f, -8f)
        // Staggered LINEAR lanes: drums swap during x in [0, 1/3], bass during [1/3, 2/3], vocals+other during [2/3, 1].
        val lanes = StemCrossfadeLanes.drumsThenBassThenRest(FadeShape.LINEAR)
        val out = StemUtils.crossfade(a, b, lanes)
        assertEquals(n, out.frames)
        // x = 0: everything from A -> 15. x = 1: everything from B -> -15.
        assertEquals(15f, out[0][0], 1e-4f)
        assertEquals(-15f, out[1][n - 1], 1e-4f)
        // x = 1/6 (middle of the drums slot): drums = 0.5*1 + 0.5*(-1) = 0, others from A -> 14.
        val i16 = Math.round((n - 1) / 6.0).toInt()
        assertEquals(14f, out[0][i16], 0.02f)
        // x = 1/3: drums fully B (-1), rest A (14) -> 13.
        val i13 = Math.round((n - 1) / 3.0).toInt()
        assertEquals(13f, out[0][i13], 0.02f)
        // x = 1/2: drums B (-1), bass halfway (0), vocals+other A (12) -> 11.
        val i12 = (n - 1) / 2
        assertEquals(11f, out[0][i12], 0.02f)
        // x = 2/3: drums B (-1), bass B (-2), rest A (12) -> 9.
        val i23 = Math.round(2.0 * (n - 1) / 3.0).toInt()
        assertEquals(9f, out[0][i23], 0.02f)
        // Equal-power uniform lanes on identical stems: gains cos/sin -> amplitude cos(x)+sin(x), peak sqrt 2 at x = 1/2.
        val ep = StemUtils.crossfade(a, a, StemCrossfadeLanes.uniform(FadeShape.EQUAL_POWER))
        assertEquals(15f * sqrt(2f), ep[0][i12], 0.02f)
        assertEquals(15f, ep[0][0], 1e-4f)
        val x = 0.25
        val i14 = Math.round((n - 1) * x).toInt()
        assertEquals((15.0 * (cos(PI / 2 * x) + sin(PI / 2 * x))).toFloat(), ep[0][i14], 0.02f)
    }

    @Test
    fun crossfadeIntoRendersInBlocksWithOffsetsAndCustomLanes() {
        val n = 10_000
        val a = constantStems(n, 1f, 0f, 0f, 0f)
        val b = constantStems(n, 0f, 0f, 0f, 3f)
        // Custom lanes: drums of A stay at 1 throughout, other of B ramps 0 -> 1 with an S-curve.
        val lanes = StemCrossfadeLanes(
            outLanes = mapOf(StemKind.DRUMS to Lane.segment(0.0, 1f, 1.0, 1f)),
            inLanes = mapOf(StemKind.OTHER to Lane.segment(0.0, 0f, 1.0, 1f, FadeShape.S_CURVE)),
        )
        val whole = StemUtils.crossfade(a, b, lanes, frames = 9000, aOffset = 500, bOffset = 1000)
        // Rendered in two pieces with the x range split accordingly must give identical samples.
        val dst = AudioBuffer.silence(sr, 2, 9000)
        StemUtils.crossfadeInto(dst, a, b, lanes, frames = 4000, dstOffset = 0, aOffset = 500, bOffset = 1000, xStart = 0.0, xEnd = 3999.0 / 8999.0)
        StemUtils.crossfadeInto(dst, a, b, lanes, frames = 5000, dstOffset = 4000, aOffset = 4500, bOffset = 5000, xStart = 4000.0 / 8999.0, xEnd = 1.0)
        for (c in 0 until 2) for (i in 0 until 9000) assertEquals(whole[c][i], dst[c][i], 1e-5f, "c=$c i=$i")
        // Analytic: at x = 0.5 the S-curve is 0.5 -> 1 + 3 * 0.5 = 2.5; at x = 0 -> 1; at x = 1 -> 4.
        assertEquals(1f, whole[0][0], 1e-5f)
        assertEquals(2.5f, whole[0][4499], 0.002f)
        assertEquals(4f, whole[0][8999], 1e-5f)
        // A StemCrossfadeLanes must cover every kind exactly once in staggered().
        assertTrue(runCatching { StemCrossfadeLanes.staggered(listOf(listOf(StemKind.DRUMS))) }.isFailure)
    }

    @Test
    fun rmsAndEnergyFractionsAreExact() {
        val n = 4096
        val s = constantStems(n, 1f, 2f, 3f, 0f)
        assertEquals(1f, StemUtils.rms(s, StemKind.DRUMS), 1e-6f)
        assertEquals(2f, StemUtils.rms(s, StemKind.BASS), 1e-6f)
        val all = StemUtils.rms(s)
        assertEquals(3f, all[StemKind.VOCALS.ordinal], 1e-6f)
        assertEquals(0f, all[StemKind.OTHER.ordinal], 0f)
        assertEquals(2f, StemUtils.rms(s, StemKind.BASS, 100, 200), 1e-6f)
        assertEquals(0f, StemUtils.rms(s, StemKind.BASS, 100, 100), 0f)
        // Energy fractions 1 : 4 : 9 : 0 over 14.
        val f = StemUtils.energyFractions(s)
        assertEquals(1f / 14f, f[0], 1e-6f); assertEquals(4f / 14f, f[1], 1e-6f); assertEquals(9f / 14f, f[2], 1e-6f); assertEquals(0f, f[3], 0f)
        assertEquals(1f, f.sum(), 1e-6f)
        val silent = StemUtils.energyFractions(constantStems(10, 0f, 0f, 0f, 0f))
        assertTrue(silent.all { it == 0f })
    }
}
