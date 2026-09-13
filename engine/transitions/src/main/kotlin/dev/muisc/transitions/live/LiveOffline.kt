package dev.muisc.transitions.live

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.LinkwitzRileyCrossover
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
import dev.muisc.dsp.fx.Delay
import dev.muisc.dsp.resample.VariableRateResampler
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.core.Lane
import kotlin.math.min

/**
 * Offline reference executor of a [LivePlan]: the semantic definition of every [LiveNode] that the real-time
 * `LiveGraph` in `engine:player` must match sample for sample (the CLI uses it to audition live plans). Not
 * real-time safe (it allocates whole-segment work buffers); the per-sample work is the same O(1) as the live graph.
 *
 * **Signal flow per deck** (`n = plan.outputFrames` output frames, planar float32):
 *
 *  1. *Source.* A = `aAudio` (A's frames from `aFromFrame`), zero-padded to `n`. B = `bAudio` (B's frames from
 *     `bFromFrame`), read through the [LiveNode.Rate] node when present (see below), else verbatim; zero beyond
 *     the end of either buffer.
 *  2. *Rate* (B only): [VariableRateResampler] (Catmull-Rom) whose ratio ramps linearly from `ratio` at output frame 0
 *     to 1.0 at `settleFrame`, then stays 1.0. The ramp carries a constant correction of at most `0.5 / settleFrame`
 *     frames per frame so that the read position at `settleFrame` is exactly the integer
 *     `framesConsumedFromB() − (n − settleFrame)`; from there B is read at integer positions, i.e. verbatim, and the
 *     segment's last frame is `bAudio[framesConsumedFromB() − 1]` — the seam at [LivePlan.bExitFrame] is exact.
 *  3. *Sweep* nodes on the deck: a TPT [StateVariableFilter] (high- or low-pass, `q`) at `fromHz` before `fromFrame`,
 *     ramped **exponentially** in Hz to `toHz` over `[fromFrame, toFrame)` and held at `toHz` after; in the order the
 *     nodes appear in the plan.
 *  4. *Echo* (A only): a feedback [Delay] (`delayFrames`, `feedback`, loop low-pass at `dampHz`, loop high-pass at
 *     [ECHO_LOOP_HIGH_PASS_HZ]) fed with A's dry signal up to `cutFrame` and with silence after. The deck output is
 *     the dry signal before the cut and the wet (echo) signal after it, joined by a [CUT_CROSSFADE_MS] equal-power
 *     crossfade that *ends* at `cutFrame` (a hard swap would step between two unrelated samples, and a fade that
 *     started at the cut would let the downbeat transient A is being cut on through for a millisecond). The first
 *     repeat is therefore at full level — `y = line[n - D]` — and every further one is `feedback` times the last.
 *  5. *LowSwap* and *Gain*: without a LowSwap node the deck signal is multiplied by the product of its Gain lanes
 *     (unity when it has none; a lane holds its first value before its first point and its last after its last, and
 *     follows each point's [FadeLaw] to the next — falling equal-power segments are `cos`, rising ones `sin`). With a
 *     LowSwap node each deck is split by an LR4 crossover at `splitHz`: the low band of A goes `1 → 0` and the low
 *     band of B `0 → 1` equal-power over `[atFrame, atFrame + swapFrames)` and the Gain lanes apply to the **high
 *     band only**, so the bass hands over on the swap and nowhere else. Note the LR4 sum is an all-pass, so a deck at
 *     unity in both bands is an all-passed (not bit-identical) copy of its source; the player's 5 ms seam fade covers
 *     the small low-frequency phase difference.
 *  6. The two deck outputs are summed. Output length is exactly `n`.
 *
 * Deterministic; channel count follows [aAudio] (B is converted to it).
 */
object LiveOffline {
    /** Block size of the block-wise stages (filters, resampler). */
    const val BLOCK_FRAMES = 1024
    /** Length of the dry → echo crossfade of an [LiveNode.Echo]; it ENDS at `cutFrame`, so A is silent from there on. */
    const val CUT_CROSSFADE_MS = 5.0
    /** High-pass in the echo feedback loop (keeps the sub-bass out of the tail, DESIGN §4.9). */
    const val ECHO_LOOP_HIGH_PASS_HZ = 120.0

    /**
     * Renders [plan]. [aAudio] holds A's frames `[aFromFrame, aToFrame)`, [bAudio] B's frames from `bFromFrame`
     * (ideally at least [LivePlan.framesConsumedFromB] of them); shorter buffers are zero-padded.
     */
    fun render(plan: LivePlan, aAudio: AudioBuffer, bAudio: AudioBuffer, sampleRate: Int): AudioBuffer {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(aAudio.sampleRate == sampleRate && bAudio.sampleRate == sampleRate) { "buffers must be at $sampleRate Hz" }
        val ch = aAudio.channelCount
        val b = bAudio.withChannels(ch)
        val n = plan.outputFrames
        val deckA = Array(ch) { FloatArray(n) }
        val deckB = Array(ch) { FloatArray(n) }
        for (c in 0 until ch) System.arraycopy(aAudio[c], 0, deckA[c], 0, min(n, aAudio.frames))
        val rate = plan.nodes.filterIsInstance<LiveNode.Rate>().firstOrNull()
        if (rate != null) readRated(b, deckB, n, rate, plan.framesConsumedFromB())
        else for (c in 0 until ch) System.arraycopy(b[c], 0, deckB[c], 0, min(n, b.frames))

        val scratchIn = Array(ch) { FloatArray(BLOCK_FRAMES) }
        val scratchOut = Array(ch) { FloatArray(BLOCK_FRAMES) }
        for (node in plan.nodes) if (node is LiveNode.Sweep) applySweep(if (node.deck == Deck.A) deckA else deckB, n, node, sampleRate, scratchIn, scratchOut)
        plan.nodes.filterIsInstance<LiveNode.Echo>().firstOrNull()?.let { applyEcho(deckA, n, it, sampleRate, scratchIn) }

        val gainA = gains(plan, Deck.A, n)
        val gainB = gains(plan, Deck.B, n)
        val out = Array(ch) { FloatArray(n) }
        val swap = plan.nodes.filterIsInstance<LiveNode.LowSwap>().firstOrNull()
        if (swap == null) {
            for (c in 0 until ch) {
                val a = deckA[c]; val bb = deckB[c]; val o = out[c]
                for (i in 0 until n) o[i] = a[i] * gainA[i] + bb[i] * gainB[i]
            }
        } else {
            val at = swap.atFrame.coerceIn(0, n)
            val end = (at + swap.swapFrames.coerceAtLeast(1)).coerceIn(at + 1, (n).coerceAtLeast(at + 1))
            val lowA = Lane("lowA").add(at.toLong(), 1.0, FadeLaw.EQUAL_POWER).add(end.toLong(), 0.0).fillGains(FloatArray(n), 0L)
            val lowB = Lane("lowB").add(at.toLong(), 0.0, FadeLaw.EQUAL_POWER).add(end.toLong(), 1.0).fillGains(FloatArray(n), 0L)
            val aLow = Array(ch) { FloatArray(n) }; val aHigh = Array(ch) { FloatArray(n) }
            val bLow = Array(ch) { FloatArray(n) }; val bHigh = Array(ch) { FloatArray(n) }
            LinkwitzRileyCrossover(sampleRate, ch, swap.splitHz.coerceIn(10.0, 0.45 * sampleRate)).process(deckA, aLow, aHigh, n)
            LinkwitzRileyCrossover(sampleRate, ch, swap.splitHz.coerceIn(10.0, 0.45 * sampleRate)).process(deckB, bLow, bHigh, n)
            for (c in 0 until ch) {
                val al = aLow[c]; val ah = aHigh[c]; val bl = bLow[c]; val bh = bHigh[c]; val o = out[c]
                for (i in 0 until n) o[i] = ah[i] * gainA[i] + al[i] * lowA[i] + bh[i] * gainB[i] + bl[i] * lowB[i]
            }
        }
        return AudioBuffer(sampleRate, out)
    }

    /**
     * Convenience for tests and the CLI: the two input buffers of [plan] cut from whole tracks (`aFull` frames
     * `[aFromFrame, aToFrame)`, `bFull` frames `[bFromFrame, bExitFrame)`; out-of-range parts are zero).
     */
    fun sliceInputs(plan: LivePlan, aFull: AudioBuffer, bFull: AudioBuffer): Pair<AudioBuffer, AudioBuffer> {
        require(plan.aToFrame <= Int.MAX_VALUE && plan.bExitFrame() <= Int.MAX_VALUE) { "plan positions exceed an in-memory buffer" }
        val a = aFull.slice(plan.aFromFrame.toInt(), plan.aToFrame.toInt())
        val b = bFull.slice(plan.bFromFrame.toInt(), plan.bExitFrame().toInt())
        return a to b
    }

    /** Per-sample product of the deck's Gain lanes (unity when it has none). */
    fun gains(plan: LivePlan, deck: Deck, n: Int): FloatArray {
        val out = FloatArray(n) { 1f }
        val tmp = FloatArray(n)
        for (node in plan.nodes) {
            if (node !is LiveNode.Gain || node.deck != deck || node.points.isEmpty()) continue
            val lane = Lane("gain-$deck")
            for (p in node.points) lane.add(p.frame.toLong(), p.value.toDouble(), p.shape)
            lane.fillGains(tmp, 0L, n)
            for (i in 0 until n) out[i] *= tmp[i]
        }
        return out
    }

    /** Rate node: linear ratio ramp to 1.0 at settleFrame, corrected so the settle position is exactly the plan's. */
    private fun readRated(src: AudioBuffer, dst: Array<FloatArray>, n: Int, rate: LiveNode.Rate, consumedTotal: Long) {
        val settle = rate.settleFrame.coerceIn(0, n)
        val rs = VariableRateResampler(src.channelCount)
        if (settle > 0) {
            val target = (consumedTotal - (n - settle)).toDouble()
            val ratio = rate.ratio
            val nominal = settle * ratio + (1.0 - ratio) * (settle - 1) / 2.0
            val delta = (target - nominal) / settle
            val slope = (1.0 - ratio) / settle
            var pos = 0
            while (pos < settle) {
                val m = min(BLOCK_FRAMES, settle - pos)
                rs.setRatioRamp(ratio + slope * pos + delta, ratio + slope * (pos + m) + delta)
                rs.process(src.channels, dst, m, pos)
                pos += m
            }
            rs.positionInInput = target
        }
        rs.setRatio(1.0)
        if (n > settle) rs.process(src.channels, dst, n - settle, settle)
    }

    private fun applySweep(x: Array<FloatArray>, n: Int, node: LiveNode.Sweep, sampleRate: Int, tin: Array<FloatArray>, tout: Array<FloatArray>) {
        val svf = StateVariableFilter(sampleRate, x.size, node.fromHz, node.q.coerceAtLeast(1e-3))
        svf.mode = if (node.highPass) SvfMode.HIGH_PASS else SvfMode.LOW_PASS
        val from = node.fromFrame.coerceIn(0, n)
        val to = node.toFrame.coerceIn(from, n)
        filterRange(svf, x, 0, from, tin, tout)
        if (to > from) svf.setCutoffRamp(node.fromHz, node.toHz, to - from, exponential = true) else svf.setCutoff(node.toHz)
        filterRange(svf, x, from, to, tin, tout)
        filterRange(svf, x, to, n, tin, tout)
    }

    private fun filterRange(svf: StateVariableFilter, x: Array<FloatArray>, start: Int, end: Int, tin: Array<FloatArray>, tout: Array<FloatArray>) {
        var pos = start
        while (pos < end) {
            val m = min(BLOCK_FRAMES, end - pos)
            for (c in x.indices) System.arraycopy(x[c], pos, tin[c], 0, m)
            svf.process(tin, tout, m)
            for (c in x.indices) System.arraycopy(tout[c], 0, x[c], pos, m)
            pos += m
        }
    }

    private fun applyEcho(a: Array<FloatArray>, n: Int, node: LiveNode.Echo, sampleRate: Int, zeros: Array<FloatArray>) {
        val ch = a.size
        val cut = node.cutFrame.coerceIn(0, n)
        val delayFrames = node.delayFrames.coerceAtLeast(Delay.MIN_DELAY)
        val delay = Delay(sampleRate, ch, maxDelaySeconds = (delayFrames + 8).toDouble() / sampleRate, crossfadeMs = 0.0)
        delay.setDelayFrames(delayFrames.toDouble(), immediate = true)
        delay.feedback = node.feedback.toDouble()
        delay.mix = 1.0
        delay.setLowPass(node.dampHz)
        delay.setHighPass(ECHO_LOOP_HIGH_PASS_HZ)
        val wet = Array(ch) { FloatArray(n) }
        delay.process(a, wet, cut, 0, 0)
        for (c in 0 until ch) zeros[c].fill(0f)
        var pos = cut
        while (pos < n) {
            val m = min(BLOCK_FRAMES, n - pos)
            delay.process(zeros, wet, m, 0, pos)
            pos += m
        }
        // Dry up to the cut, wet from the cut on, joined by a short equal-power crossfade that ENDS at the cut:
        // A must be silent from `cutFrame` on, so the downbeat transient that starts there is never half-played
        // (a 1 ms fragment of a kick followed by the echo tail is exactly the click a DJ's channel cut avoids).
        val xf = Math.round(CUT_CROSSFADE_MS / 1000.0 * sampleRate).toInt().coerceIn(1, cut.coerceAtLeast(1))
        val dryGain = Lane("dry").add((cut - xf).toLong(), 1.0, FadeLaw.EQUAL_POWER).add(cut.toLong(), 0.0)
        val wetGain = Lane("wet").add((cut - xf).toLong(), 0.0, FadeLaw.EQUAL_POWER).add(cut.toLong(), 1.0)
        val dg = dryGain.fillGains(FloatArray(n), 0L)
        val wg = wetGain.fillGains(FloatArray(n), 0L)
        for (c in 0 until ch) {
            val x = a[c]; val w = wet[c]
            for (i in 0 until n) x[i] = x[i] * dg[i] + w[i] * wg[i]
        }
    }
}
