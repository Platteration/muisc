package dev.muisc.player

import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.filter.LinkwitzRiley
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.live.Deck
import dev.muisc.transitions.live.LiveNode
import dev.muisc.transitions.live.LivePlan
import dev.muisc.transitions.live.LivePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveGraphTest {
    private val sr = 44100

    private fun stereo(freq: Double, seconds: Double, amp: Float) = arrayOf(Synth.sine(sr, freq, seconds, amp), Synth.sine(sr, freq * 1.5, seconds, amp))

    /** Runs the graph block by block with the same feeding logic the player uses. */
    private fun run(graph: LiveGraph, a: Array<FloatArray>, b: Array<FloatArray>, block: Int = 1000): Array<FloatArray> {
        val n = graph.outputFrames
        val out = Array(2) { FloatArray(n) }
        val aBlock = Array(2) { FloatArray(block) }
        val bBlock = Array(2) { FloatArray(block * 2 + 32) }
        var aPos = 0; var bPos = 0; var done = 0
        while (done < n) {
            val m = minOf(block, n - done)
            for (c in 0 until 2) for (i in 0 until m) aBlock[c][i] = if (aPos + i < a[c].size) a[c][aPos + i] else 0f
            aPos += m
            val need = graph.bFramesNeeded(m)
            for (c in 0 until 2) for (i in 0 until need) bBlock[c][i] = if (bPos + i < b[c].size) b[c][bPos + i] else 0f
            bPos += need
            graph.process(aBlock, bBlock, out, m, done, need)
            done += m
        }
        return out
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double { var s = 0.0; for (i in from until to) s += x[i].toDouble() * x[i]; return sqrt(s / (to - from)) }

    @Test
    fun equalPowerCrossfadeMatchesIndependentComputation() {
        val n = 2 * sr
        val a = stereo(440.0, 2.0, 0.5f)
        val b = stereo(660.0, 2.0, 0.5f)
        val plan = LivePlan("crossfade", 0L, n.toLong(), 0L, n, listOf(
            LiveNode.Gain(Deck.A, listOf(LivePoint(0, 1f, FadeLaw.EQUAL_POWER), LivePoint(n, 0f))),
            LiveNode.Gain(Deck.B, listOf(LivePoint(0, 0f, FadeLaw.EQUAL_POWER), LivePoint(n, 1f))),
        ))
        val graph = LiveGraph(plan, sr, 2)
        val out = run(graph, a, b, block = 1024)
        var worst = 0.0
        for (c in 0 until 2) for (i in 0 until n) {
            val t = i.toDouble() / n
            val expected = a[c][i] * cos(PI / 2 * t) + b[c][i] * sin(PI / 2 * t)
            worst = maxOf(worst, abs(expected - out[c][i]))
        }
        assertTrue(worst < 1e-4, "equal-power crossfade deviates by $worst")
        assertEquals(n.toLong(), graph.bFramesConsumed)
        assertTrue(graph.finished)
        // Constant power: the RMS stays within 0.5 dB across the fade (uncorrelated tones).
        val first = rms(out[0], 0, sr / 4); val mid = rms(out[0], n / 2 - sr / 8, n / 2 + sr / 8); val last = rms(out[0], n - sr / 4, n)
        for (v in listOf(first, mid, last)) assertTrue(abs(20 * kotlin.math.log10(v / first)) < 0.5, "power dip: $first $mid $last")
    }

    @Test
    fun lowSwapHandsTheBassOver() {
        val n = 2 * sr
        val a = stereo(50.0, 2.0, 0.5f)   // A: pure bass
        val b = stereo(3000.0, 2.0, 0.5f) // B: no bass
        val plan = LivePlan("bassSwap", 0L, n.toLong(), 0L, n, listOf(LiveNode.LowSwap(atFrame = sr / 2, swapFrames = sr / 2, splitHz = 200.0)))
        val out = run(LiveGraph(plan, sr, 2), a, b)
        val low = LinkwitzRiley.lowPass(200.0, sr.toDouble(), 1)
        val lowOut = FloatArray(n)
        low.process(out[0], lowOut, n)
        val before = rms(lowOut, sr / 8, sr / 2)          // A's bass still there before the swap
        val after = rms(lowOut, n - sr / 2, n)            // gone after it
        assertTrue(before > 0.3, "bass before the swap: $before")
        assertTrue(after < before * 0.02, "bass after the swap: $after vs $before")
        // B's high band is untouched throughout.
        assertTrue(abs(rms(out[0], n - sr / 2, n) - 0.5 / sqrt(2.0)) < 0.02)
    }

    @Test
    fun rateNodeConsumesWhatThePlanSays() {
        val n = 3 * sr
        val a = stereo(440.0, 3.0, 0.3f)
        val b = stereo(100.0, 4.0, 0.3f)
        val plan = LivePlan("bassSwap", 0L, n.toLong(), 0L, n, listOf(LiveNode.Rate(ratio = 1.02, settleFrame = sr)))
        val graph = LiveGraph(plan, sr, 2)
        val out = run(graph, a, b, block = 1000)
        assertTrue(abs(graph.bFramesConsumed - plan.framesConsumedFromB()) <= 4, "consumed ${graph.bFramesConsumed}, plan ${plan.framesConsumedFromB()}")
        assertEquals(1.0, graph.rateAt(sr.toLong()), 0.0)
        assertTrue(graph.rateAt(0) > 1.019 && graph.rateAt(0) <= 1.02)
        for (c in 0 until 2) for (v in out[c]) assertTrue(v.isFinite() && abs(v) < 0.7)
        // After the settle point B passes at ratio 1: the last second equals B's samples at the consumed offset.
        val offset = (plan.framesConsumedFromB() - (n - sr)).toInt()
        var worst = 0f
        for (i in 0 until sr) worst = maxOf(worst, abs(out[0][n - sr + i] - (a[0][n - sr + i] + b[0][offset + i])))
        assertTrue(worst < 0.02f, "post-settle mismatch $worst")
    }

    @Test
    fun echoNodeLeavesADecayingTailAfterTheCut() {
        val n = 2 * sr
        val a = stereo(440.0, 2.0, 0.25f)
        val b = Array(2) { FloatArray(n) }
        val plan = LivePlan("echoOut", 0L, n.toLong(), 0L, n, listOf(LiveNode.Echo(cutFrame = sr / 2, delayFrames = sr / 8, feedback = 0.5f, dampHz = 4000.0)))
        val out = run(LiveGraph(plan, sr, 2), a, b)
        val dry = rms(out[0], sr / 8, sr / 2 - 100)
        val tail1 = rms(out[0], sr / 2 + sr / 8, sr / 2 + sr / 4)
        val tail2 = rms(out[0], n - sr / 4, n)
        assertTrue(dry > 0.15, "dry level $dry")
        assertTrue(tail1 > 0.01 && tail1 < dry, "first echo $tail1 vs dry $dry")
        assertTrue(tail2 < tail1 * 0.3, "tail decays: $tail2 vs $tail1")
        // A sustained tone into a 0.5 feedback loop can build up to 2x; the graph must stay finite and bounded.
        for (v in out[0]) assertTrue(v.isFinite() && abs(v) < 1f)
    }

    @Test
    fun sweepNodeFiltersTheDeck() {
        val n = sr
        val a = stereo(440.0, 1.0, 0.25f)
        val b = Array(2) { FloatArray(n) }
        val plan = LivePlan("filterSweep", 0L, n.toLong(), 0L, n, listOf(LiveNode.Sweep(Deck.A, highPass = true, fromHz = 30.0, toHz = 2000.0, q = 0.7071, fromFrame = sr / 4, toFrame = 3 * sr / 4)))
        val out = run(LiveGraph(plan, sr, 2), a, b)
        val before = rms(out[0], sr / 16, sr / 4)       // 30 Hz high-pass: transparent at 440 Hz
        val after = rms(out[0], n - sr / 8, n)         // 2 kHz high-pass: 440 Hz down by ~ 26 dB
        assertTrue(abs(before - 0.25 / sqrt(2.0)) < 0.01, "before the sweep: $before")
        assertTrue(after < before * 0.1, "after the sweep: $after vs $before")
        for (v in out[0]) assertTrue(v.isFinite())
    }
}
