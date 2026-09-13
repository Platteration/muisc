package dev.muisc.transitions.live

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.transitions.live.LiveFixtures.SR
import dev.muisc.transitions.live.LiveFixtures.a120
import dev.muisc.transitions.live.LiveFixtures.b121
import dev.muisc.transitions.live.LiveFixtures.b140amb
import dev.muisc.transitions.live.LiveFixtures.b140cold
import dev.muisc.transitions.live.LiveFixtures.barFrame
import dev.muisc.transitions.live.LiveFixtures.db
import dev.muisc.transitions.live.LiveFixtures.prefs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveOfflineTest {
    private val factory = DefaultLivePlanFactory()

    @Test
    fun crossfadeEqualsIndependentEqualPowerMix() {
        val n = SR
        val aFrom = barFrame(a120, 12); val bFrom = barFrame(b121, 4)
        val plan = LivePlanBuilders.crossfade(aFrom, a120.analysis.totalFrames, bFrom, n)
        val (aIn, bIn) = LiveFixtures.inputs(plan, a120, b121)
        val out = LiveOffline.render(plan, aIn, bIn, SR)
        assertEquals(n, out.frames)
        var maxErr = 0.0
        for (c in 0 until 2) for (i in 0 until n) {
            val t = i.toDouble() / n
            val expected = aIn[c][i] * cos(PI / 2 * t) + bIn[c][i] * sin(PI / 2 * t)
            maxErr = maxOf(maxErr, abs(expected - out[c][i]))
        }
        assertTrue(maxErr <= 1e-4, "max abs error $maxErr")
        assertEquals(aIn[0][0], out[0][0], 1e-7f, "starts as A")
        assertTrue(abs(out[0][n - 1] - bIn[0][n - 1]) < 1e-4f, "ends as B")
    }

    @Test
    fun rateRampEndsOnIntegerPositionSoTheTailIsVerbatimB() {
        val n = 4 * SR
        for ((ratio, settle) in listOf(0.99 to n / 2, 1.02 to n - 1000, 0.985 to 12345, 1.0 to n, 0.995 to n)) {
            val plan = LivePlan("bassSwap", 0L, n.toLong(), 0L, n, listOf(
                LiveNode.Rate(ratio, settle), LiveNode.Gain(Deck.A, listOf(LivePoint(0, 0f))), LiveNode.Gain(Deck.B, listOf(LivePoint(0, 1f))),
            ))
            val bFull = b121.audio.slice(barFrame(b121, 4).toInt(), barFrame(b121, 4).toInt() + n + 8000)
            val out = LiveOffline.render(plan, LiveFixtures.silence(n), bFull, SR)
            val consumed = plan.framesConsumedFromB().toInt()
            assertEquals(Math.round(settle * (ratio + 1.0) / 2.0 + (n - settle)).toInt(), consumed)
            // every output frame after the settle point is B verbatim at consumed - (n - i)
            for (i in settle until n) for (c in 0 until 2) {
                val expected = bFull[c][consumed - (n - i)]
                assertEquals(expected, out[c][i], 1e-6f, "ratio $ratio settle $settle frame $i")
            }
            // ... and before it the mean ratio is what the plan promised (position error < 1 frame over the ramp)
            if (settle in 1 until n && ratio != 1.0) {
                assertTrue(abs(out[0][settle] - bFull[0][consumed - (n - settle)]) < 1e-6f)
                val stretched = out[0][settle / 2]
                assertTrue(abs(stretched - bFull[0][settle / 2]) > 0f || ratio == 1.0, "the ramp region is actually resampled")
            }
        }
    }

    @Test
    fun bassSwapHandsOverTheLowBandOnTheDownbeat() {
        val f = LiveFixtures.features(a120, b121)
        val plan = factory.plan(a120.trackRef, b121.trackRef, f, barFrame(a120, 12) + 1000, prefs)
        assertEquals("bassSwap", plan.kind)
        val swap = plan.nodes.filterIsInstance<LiveNode.LowSwap>().first()
        val bar = plan.outputFrames / 4
        val (aIn, bIn) = LiveFixtures.inputs(plan, a120, b121)
        val before = swap.atFrame - bar until swap.atFrame
        val after = swap.atFrame + swap.swapFrames until swap.atFrame + swap.swapFrames + bar
        val hz = swap.splitHz

        val aOnly = LiveOffline.render(plan, aIn, LiveFixtures.silence(bIn.frames), SR)
        val aBefore = db(LiveFixtures.lowBandPower(aOnly, hz, before.first, before.last + 1))
        val aAfter = db(LiveFixtures.lowBandPower(aOnly, hz, after.first, after.last + 1))
        assertTrue(aAfter < aBefore - 20.0, "A's low band after the swap: $aAfter dB vs $aBefore dB before")
        // A's mids/highs are still there after the swap: only the equal-power lane (~ -4 dB across one bar) took them
        // down, so the swap really is an EQ move and not a fade-out.
        val aHighBefore = db(LiveFixtures.highBandPower(aOnly, hz, before.first, before.last + 1))
        val aHighAfter = db(LiveFixtures.highBandPower(aOnly, hz, after.first, after.last + 1))
        assertTrue(aHighAfter > aHighBefore - 8.0, "A's highs remain after the swap ($aHighBefore dB -> $aHighAfter dB)")

        val bOnly = LiveOffline.render(plan, LiveFixtures.silence(aIn.frames), bIn, SR)
        val bBefore = db(LiveFixtures.lowBandPower(bOnly, hz, before.first, before.last + 1))
        val bAfter = db(LiveFixtures.lowBandPower(bOnly, hz, after.first, after.last + 1))
        assertTrue(bAfter > bBefore + 20.0, "B's low band rises: $bBefore dB -> $bAfter dB")
        val bDry = db(LiveFixtures.lowBandPower(bIn, hz, after.first, after.last + 1))
        assertTrue(abs(bAfter - bDry) < 2.0, "after the swap B's low band is its own ($bAfter vs dry $bDry dB)")

        val both = LiveOffline.render(plan, aIn, bIn, SR)
        assertEquals(plan.outputFrames, both.frames)
        assertTrue(ArtifactDetector(SR).analyze(both).clicks.isEmpty())
        // the seam: the last frames are B at unity through the crossover (all-pass), same level as dry B
        val tail = db(LiveFixtures.power(both.mono(), both.frames - bar, both.frames))
        val dryTail = db(LiveFixtures.power(bIn.mono(), plan.framesConsumedFromB().toInt() - bar, plan.framesConsumedFromB().toInt()))
        assertTrue(abs(tail - dryTail) < 1.0, "tail level $tail dB vs dry B $dryTail dB")
    }

    @Test
    fun echoOutRepeatsAtDelayFramesWithFeedbackDecay() {
        val cut = 20000; val delay = 3000; val tail = 30000
        val plan = LivePlanBuilders.echoOut(0L, (cut + tail).toLong(), 0L, cut, delay, tail, feedback = 0.6f, dampHz = 4000.0)
        assertEquals(emptyList(), LivePlanBuilders.problems(plan))
        val n = plan.outputFrames
        // one 500 Hz Hann burst of 200 frames just before the cut
        val burstAt = cut - 1000; val burstLen = 200
        val a = AudioBuffer.silence(SR, 2, n)
        for (i in 0 until burstLen) {
            val w = 0.5 * (1 - cos(2 * PI * i / burstLen))
            val v = (0.8 * w * sin(2 * PI * 500.0 * i / SR)).toFloat()
            a[0][burstAt + i] = v; a[1][burstAt + i] = v
        }
        val out = LiveOffline.render(plan, a, LiveFixtures.silence(n), SR)
        assertEquals(n, out.frames)
        // A is dry until the short declick fade that ENDS at the cut, and silent (dry) from the cut on.
        val xf = Math.round(LiveOffline.CUT_CROSSFADE_MS / 1000.0 * SR).toInt()
        for (i in 0 until cut - xf) assertEquals(a[0][i], out[0][i], 0f, "dry before the cut is verbatim (frame $i)")
        val x = out[0]
        fun peak(from: Int, to: Int): Pair<Int, Float> { var p = from; var m = 0f; for (i in from until to) if (abs(x[i]) > m) { m = abs(x[i]); p = i }; return p to m }
        val (p0, m0) = peak(burstAt, burstAt + burstLen)
        var prevPos = p0; var prevMag = m0
        for (k in 1..5) {
            val expected = burstAt + k * delay
            val (pos, mag) = peak(expected - 50, expected + burstLen + 50)
            assertTrue(abs((pos - prevPos) - delay) <= 4, "echo $k at $pos, previous at $prevPos: spacing ${pos - prevPos} != $delay")
            val ratio = mag / prevMag
            // y[n] = line[n - D] with line[n] = x[n] + fb * loopFilter(y[n]): the FIRST repeat is the input itself,
            // every later one is `feedback` times the previous (minus the loop filters' small loss).
            val range = if (k == 1) 0.95f..1.02f else 0.45f..0.72f
            assertTrue(ratio in range, "echo $k decays by feedback: ratio $ratio not in $range")
            val (_, gapMag) = peak(expected + burstLen + 300, expected + delay - 300)
            assertTrue(gapMag < 0.05f * mag, "silence between echoes ($gapMag vs $mag)")
            prevPos = pos; prevMag = mag
        }
        assertEquals(0f, x[n - 1], 1e-6f, "the tail is contained: A's lane reaches 0 at the seam")
        assertTrue(ArtifactDetector(SR).analyze(out).clicks.isEmpty())
    }

    @Test
    fun filterSweepLowBandDecreasesMonotonically() {
        val n = 4 * SR
        val plan = LivePlanBuilders.filterSweep(0L, n.toLong(), 0L, n, SR, resonance = LivePlanBuilders.SWEEP_LP_Q)
        // a steady low-end signal: bass fundamentals at 45/70/110/160 Hz plus a 1.2 kHz partial
        val a = AudioBuffer.silence(SR, 2, n)
        for (i in 0 until n) {
            var v = 0.0
            for (hz in doubleArrayOf(45.0, 70.0, 110.0, 160.0)) v += 0.15 * sin(2 * PI * hz * i / SR)
            v += 0.1 * sin(2 * PI * 1200.0 * i / SR)
            a[0][i] = v.toFloat(); a[1][i] = v.toFloat()
        }
        val out = LiveOffline.render(plan, a, LiveFixtures.silence(n), SR)
        val win = SR / 4
        val dry = a.mono(); val wet = out.mono()
        val ratios = DoubleArray(n / win) { k -> LiveFixtures.lowBandPower(wet, SR, 200.0, k * win, (k + 1) * win) / LiveFixtures.lowBandPower(dry, SR, 200.0, k * win, (k + 1) * win) }
        for (k in 1 until ratios.size) assertTrue(ratios[k] <= ratios[k - 1] + 1e-9, "low band ratio rises at window $k: ${ratios.toList()}")
        assertTrue(ratios.first() > 0.5 && ratios.last() < 1e-4, "from nearly full to gone: ${ratios.first()} -> ${ratios.last()}")
        // the factory's resonant default on real material: the last bar's low band is far below the first bar's
        val f = LiveFixtures.features(a120, b140amb)
        val real = factory.plan(a120.trackRef, b140amb.trackRef, f, barFrame(a120, 8), prefs)
        assertEquals("filterSweep", real.kind)
        val (aIn, bIn) = LiveFixtures.inputs(real, a120, b140amb)
        val aOnly = LiveOffline.render(real, aIn, LiveFixtures.silence(bIn.frames), SR)
        val bar = real.outputFrames / 4
        val first = db(LiveFixtures.lowBandPower(aOnly, 200.0, 0, bar)); val last = db(LiveFixtures.lowBandPower(aOnly, 200.0, 3 * bar, 4 * bar))
        assertTrue(last < first - 30.0, "A's low band: $first dB -> $last dB")
        val bOnly = LiveOffline.render(real, LiveFixtures.silence(aIn.frames), bIn, SR)
        val bFirst = db(LiveFixtures.power(bOnly.mono(), 0, bar)); val bLast = db(LiveFixtures.power(bOnly.mono(), 3 * bar, 4 * bar))
        assertTrue(bLast > bFirst + 6.0, "B opens up: $bFirst dB -> $bLast dB")
    }

    @Test
    fun everyKindRendersCleanDeterministicAndExactLength() {
        val cases = listOf(
            Triple("bassSwap", b121, barFrame(a120, 12) + 1000),
            Triple("echoOut", b140cold, barFrame(a120, 20) + 5),
            Triple("filterSweep", b140amb, barFrame(a120, 8)),
            Triple("phraseCut", b140cold, a120.analysis.trimEndFrame - 2 * 22050),
            Triple("crossfade", b121, barFrame(a120, 10) + 777),
        )
        // the crossfade rung is only reached when A's grid is unusable
        val shakyA = a120.trackRef.copy(analysis = a120.analysis.copy(grid = a120.analysis.grid.copy(confidence = 0.3f)))
        for ((kind, b, now) in cases) {
            val a = if (kind == "crossfade") shakyA else a120.trackRef
            val features = if (kind == "crossfade") null else LiveFixtures.features(a120, b)
            val plan = factory.plan(a, b.trackRef, features, now, prefs)
            assertEquals(kind, plan.kind)
            val (aIn, bIn) = LiveFixtures.inputs(plan, a120, b)
            assertEquals((plan.aToFrame - plan.aFromFrame).toInt(), aIn.frames)
            assertEquals(plan.framesConsumedFromB().toInt(), bIn.frames)
            val out = LiveOffline.render(plan, aIn, bIn, SR)
            assertEquals(plan.outputFrames, out.frames, kind)
            val report = ArtifactDetector(SR).analyze(out)
            assertTrue(report.clicks.isEmpty(), "$kind: ${report.summary()}")
            assertTrue(out.peak() < 1.2f, "$kind peak ${out.peak()}")
            val again = LiveOffline.render(plan, aIn, bIn, SR)
            for (c in 0 until 2) assertTrue(out[c].contentEquals(again[c]), "$kind deterministic")
            // B at unity at the seam: the last 20 ms (less for a 16 ms cut) carry B's level
            val win = minOf(882, out.frames / 4, bIn.frames).coerceAtLeast(1)
            val seam = db(LiveFixtures.power(out.mono(), out.frames - win, out.frames))
            val dryB = db(LiveFixtures.power(bIn.mono(), bIn.frames - win, bIn.frames))
            assertTrue(abs(seam - dryB) < 1.5, "$kind seam level $seam dB vs B $dryB dB")
        }
        // an interior phrase cut: 16 ms equal-power gains, no click
        val cut = LivePlanBuilders.phraseCut(barFrame(a120, 16), a120.analysis.totalFrames, barFrame(b140cold, 0), 706)
        val (aIn, bIn) = LiveFixtures.inputs(cut, a120, b140cold)
        val out = LiveOffline.render(cut, aIn, bIn, SR)
        assertEquals(706, out.frames)
        assertTrue(ArtifactDetector(SR).analyze(out).clicks.isEmpty())
    }

    @Test
    fun shortInputsAreZeroPadded() {
        val n = 10000
        val plan = LivePlanBuilders.crossfade(0L, 4000L, 0L, n)
        assertEquals(4000L, plan.aToFrame)
        val a = AudioBuffer(SR, arrayOf(FloatArray(4000) { 0.5f }, FloatArray(4000) { 0.5f }))
        val out = LiveOffline.render(plan, a, LiveFixtures.silence(3000), SR)
        assertEquals(n, out.frames)
        assertTrue(out[0][3999] > 0f && out[0][4000] == 0f && out[0][n - 1] == 0f)
        // mono B is converted to A's channel count
        val outMono = LiveOffline.render(plan, a, AudioBuffer.mono(SR, FloatArray(n) { 0.25f }), SR)
        assertEquals(2, outMono.channelCount)
        assertEquals(0.25f, outMono[1][n - 1], 1e-3f)
    }
}
