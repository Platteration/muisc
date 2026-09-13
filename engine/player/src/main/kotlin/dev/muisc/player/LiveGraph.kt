package dev.muisc.player

import dev.muisc.dsp.filter.LinkwitzRileyCrossover
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
import dev.muisc.dsp.fx.Delay
import dev.muisc.dsp.resample.VariableRateResampler
import dev.muisc.transitions.core.Lane
import dev.muisc.transitions.live.Deck
import dev.muisc.transitions.live.LiveNode
import dev.muisc.transitions.live.LivePlan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time executor of a [LivePlan]'s node set. Built off the audio thread; [process] is allocation-free.
 *
 * Semantics (all frames are output frames of the segment; deck B's frame `bFromFrame` sits at output frame 0):
 *  - `Gain(deck, points)`: gain lane on the deck (first value before the first point, last after the last; between
 *    points the earlier point's shape, with the substrate [Lane] convention: a falling EQUAL_POWER segment is
 *    `cos`, its rising partner `sin`). A deck without a lane plays at unity. Lanes are sampled every
 *    [GAIN_SUBBLOCK] frames and ramped linearly in between (no transcendental calls per sample).
 *  - `Rate(ratio, settleFrame)`: B is read at `ratio` input frames per output frame, ramping linearly to 1.0 by
 *    `settleFrame` (Catmull-Rom interpolation from [VariableRateResampler]); B frames consumed after the segment
 *    ≈ `plan.framesConsumedFromB()` (within a frame; the player positions B's body at `plan.bExitFrame()` exactly).
 *  - `LowSwap(atFrame, swapFrames, splitHz)`: LR4 crossover on both decks; A's low band fades out and B's fades
 *    in linearly over the swap, high bands untouched.
 *  - `Sweep(deck, highPass, fromHz, toHz, q, fromFrame, toFrame)`: SVF on the deck, cutoff geometric from `fromHz`
 *    (held before `fromFrame`) to `toHz` (held after `toFrame`).
 *  - `Echo(cutFrame, delayFrames, feedback, dampHz)`: A's dry signal feeds a feedback delay; at `cutFrame` A is
 *    muted and only the echo tail continues.
 *
 * Order per block: rate (B) → low swap → sweeps → echo (A) → gain lanes → sum. The caller supplies A's next
 * `frames` frames (zeros after `aToFrame`) and B's next [bFramesNeeded] frames.
 */
class LiveGraph(val plan: LivePlan, val sampleRate: Int, val channels: Int, val maxBlockFrames: Int = 4096) {
    init { require(sampleRate > 0 && channels > 0 && maxBlockFrames >= 64) }

    private val laneA: Lane? = lane(Deck.A)
    private val laneB: Lane? = lane(Deck.B)
    private val rate: LiveNode.Rate? = plan.nodes.filterIsInstance<LiveNode.Rate>().firstOrNull()
    private val lowSwap: LiveNode.LowSwap? = plan.nodes.filterIsInstance<LiveNode.LowSwap>().firstOrNull()
    private val sweepA: LiveNode.Sweep? = plan.nodes.filterIsInstance<LiveNode.Sweep>().firstOrNull { it.deck == Deck.A }
    private val sweepB: LiveNode.Sweep? = plan.nodes.filterIsInstance<LiveNode.Sweep>().firstOrNull { it.deck == Deck.B }
    private val echo: LiveNode.Echo? = plan.nodes.filterIsInstance<LiveNode.Echo>().firstOrNull()

    private val aWork = Array(channels) { FloatArray(maxBlockFrames) }
    private val bWork = Array(channels) { FloatArray(maxBlockFrames) }
    private val lowA = if (lowSwap != null) Array(channels) { FloatArray(maxBlockFrames) } else null
    private val highA = if (lowSwap != null) Array(channels) { FloatArray(maxBlockFrames) } else null
    private val lowB = if (lowSwap != null) Array(channels) { FloatArray(maxBlockFrames) } else null
    private val highB = if (lowSwap != null) Array(channels) { FloatArray(maxBlockFrames) } else null
    private val echoBuf = if (echo != null) Array(channels) { FloatArray(maxBlockFrames) } else null
    private val gainScratch = FloatArray(maxBlockFrames)

    private val crossA = lowSwap?.let { LinkwitzRileyCrossover(sampleRate, channels, it.splitHz.coerceIn(20.0, sampleRate * 0.45)) }
    private val crossB = lowSwap?.let { LinkwitzRileyCrossover(sampleRate, channels, it.splitHz.coerceIn(20.0, sampleRate * 0.45)) }
    private val svfA = sweepA?.let { svf(it) }
    private val svfB = sweepB?.let { svf(it) }
    private val delay = echo?.let {
        Delay(sampleRate, channels, maxDelaySeconds = max(0.05, it.delayFrames.toDouble() / sampleRate + 0.05), crossfadeMs = 0.0).apply {
            setDelayFrames(it.delayFrames.toDouble(), immediate = true)
            feedback = it.feedback.toDouble()
            mix = 1.0
            setLowPass(it.dampHz)
        }
    }

    // Variable-rate feed for B: [feed] holds B input frames starting at absolute B frame `feedBase`.
    private val vrr = if (rate != null) VariableRateResampler(channels) else null
    private val feedCapacity = ceil(maxBlockFrames * 1.1).toInt() + 16
    private val feed = if (rate != null) Array(channels) { FloatArray(feedCapacity) } else null
    private var feedLen = 0

    /** Output frames processed so far. */
    var outputFrame: Long = 0L
        private set

    /** B input frames taken from the caller so far (through [process]). */
    var bFramesConsumed: Long = 0L
        private set

    /** Output frames of the segment. */
    val outputFrames: Int get() = plan.outputFrames

    /** True when the whole segment has been produced. */
    val finished: Boolean get() = outputFrame >= plan.outputFrames

    private fun lane(deck: Deck): Lane? {
        val node = plan.nodes.filterIsInstance<LiveNode.Gain>().firstOrNull { it.deck == deck } ?: return null
        if (node.points.isEmpty()) return null
        val lane = Lane("gain" + deck.name)
        for (p in node.points) lane.add(p.frame.toLong(), p.value.toDouble(), p.shape)
        return lane
    }

    private fun svf(s: LiveNode.Sweep): StateVariableFilter =
        StateVariableFilter(sampleRate, channels, cutoffHz = sweepCutoff(s, 0L), q = s.q.coerceIn(0.1, 20.0)).apply {
            mode = if (s.highPass) SvfMode.HIGH_PASS else SvfMode.LOW_PASS
        }

    private fun sweepCutoff(s: LiveNode.Sweep, t: Long): Double {
        if (t <= s.fromFrame || s.toFrame <= s.fromFrame) return s.fromHz
        if (t >= s.toFrame) return s.toHz
        val x = (t - s.fromFrame).toDouble() / (s.toFrame - s.fromFrame)
        return s.fromHz * exp(ln(s.toHz / s.fromHz) * x)
    }

    /** Playback rate of B at output frame [t] (input frames per output frame). */
    fun rateAt(t: Long): Double {
        val r = rate ?: return 1.0
        val settle = r.settleFrame
        if (settle <= 0 || t >= settle) return 1.0
        return r.ratio + (1.0 - r.ratio) * ((t + 0.5) / settle)
    }

    /** B input frames the caller must supply to [process] for the next [frames] output frames. */
    fun bFramesNeeded(frames: Int): Int {
        val v = vrr ?: return frames
        if (frames <= 0) return 0
        val rMax = max(rateAt(outputFrame), rateAt(outputFrame + frames))
        val maxIndex = floor(v.positionInInput + frames * rMax).toInt() + 3
        return max(0, maxIndex + 1 - feedLen)
    }

    /** Resets all DSP state to the start of the segment. */
    fun reset() {
        outputFrame = 0L; bFramesConsumed = 0L; feedLen = 0
        vrr?.reset(0.0)
        crossA?.reset(); crossB?.reset(); svfA?.reset(); svfB?.reset(); delay?.reset()
        sweepA?.let { svfA!!.setCutoff(sweepCutoff(it, 0L)) }
        sweepB?.let { svfB!!.setCutoff(sweepCutoff(it, 0L)) }
    }

    /**
     * Produces [frames] output frames into `out[c][outputOffset ..]` from `aBlock` (A's next [frames] frames) and
     * `bBlock` (B's next [bFrames] frames, [bFramesNeeded] as computed before this call; missing frames read as
     * silence). `out` may alias `aBlock`.
     */
    fun process(aBlock: Array<FloatArray>, bBlock: Array<FloatArray>, out: Array<FloatArray>, frames: Int, outputOffset: Int = 0, bFrames: Int = bBlock[0].size) {
        var done = 0
        var bOff = 0
        val bProvided = bFrames
        while (done < frames) {
            var n = min(maxBlockFrames, frames - done)
            // Split at the rate settle point so each chunk has a single ramp.
            rate?.let { r -> if (outputFrame < r.settleFrame && outputFrame + n > r.settleFrame) n = (r.settleFrame - outputFrame).toInt() }
            val need = bFramesNeeded(n)
            val take = min(need, max(0, bProvided - bOff))
            chunk(aBlock, done, bBlock, bOff, take, need, out, outputOffset + done, n)
            bOff += take
            done += n
        }
    }

    private fun chunk(aBlock: Array<FloatArray>, aOff: Int, bBlock: Array<FloatArray>, bOff: Int, bTake: Int, bNeed: Int, out: Array<FloatArray>, outOff: Int, n: Int) {
        val t0 = outputFrame
        // 1. Decks into the work buffers.
        for (c in 0 until channels) System.arraycopy(aBlock[c], aOff, aWork[c], 0, n)
        val v = vrr
        if (v == null) {
            for (c in 0 until channels) {
                System.arraycopy(bBlock[c], bOff, bWork[c], 0, bTake)
                if (bTake < n) java.util.Arrays.fill(bWork[c], bTake, n, 0f)
            }
            bFramesConsumed += bTake
        } else {
            val f = feed!!
            for (c in 0 until channels) {
                System.arraycopy(bBlock[c], bOff, f[c], feedLen, bTake)
                if (bTake < bNeed) java.util.Arrays.fill(f[c], feedLen + bTake, feedLen + bNeed, 0f)
            }
            feedLen += bNeed
            bFramesConsumed += bTake
            val r0 = rateAt(t0); val r1 = rateAt(t0 + n)
            if (r0 == r1) v.setRatio(r0) else v.setRatioRamp(r0, r1)
            v.process(f, bWork, n)
            // Drop what no future output can read: keep one frame before floor(pos).
            val drop = (floor(v.positionInInput).toInt() - 1).coerceIn(0, feedLen)
            if (drop > 0) {
                val keep = feedLen - drop
                for (c in 0 until channels) System.arraycopy(f[c], drop, f[c], 0, keep)
                feedLen = keep
                v.positionInInput -= drop
            }
        }
        // 2. Low swap.
        lowSwap?.let { ls ->
            crossA!!.process(aWork, lowA!!, highA!!, n)
            crossB!!.process(bWork, lowB!!, highB!!, n)
            val swap = max(1, ls.swapFrames)
            for (i in 0 until n) {
                val t = t0 + i
                val s = if (t < ls.atFrame) 0f else if (t >= ls.atFrame + swap) 1f else ((t - ls.atFrame).toFloat() / swap)
                val sa = 1f - s
                for (c in 0 until channels) {
                    aWork[c][i] = highA[c][i] + lowA[c][i] * sa
                    bWork[c][i] = highB[c][i] + lowB[c][i] * s
                }
            }
        }
        // 3. Sweeps.
        sweepA?.let { s -> svfA!!.setCutoff(sweepCutoff(s, t0 + n)); svfA.process(aWork, aWork, n) }
        sweepB?.let { s -> svfB!!.setCutoff(sweepCutoff(s, t0 + n)); svfB.process(bWork, bWork, n) }
        // 4. Echo on A: dry until the cut, then only the tail.
        echo?.let { e ->
            val cutIdx = (e.cutFrame - t0).coerceIn(0L, n.toLong()).toInt()
            if (cutIdx < n) for (c in 0 until channels) java.util.Arrays.fill(aWork[c], cutIdx, n, 0f)
            delay!!.process(aWork, echoBuf!!, n)
            for (c in 0 until channels) { val a = aWork[c]; val eb = echoBuf[c]; for (i in 0 until n) a[i] += eb[i] }
        }
        // 5. Gain lanes and sum.
        applyLane(laneA, aWork, t0, n)
        applyLane(laneB, bWork, t0, n)
        for (c in 0 until channels) {
            val o = out[c]; val a = aWork[c]; val b = bWork[c]
            for (i in 0 until n) o[outOff + i] = a[i] + b[i]
        }
        outputFrame += n
    }

    private fun applyLane(lane: Lane?, work: Array<FloatArray>, t0: Long, n: Int) {
        if (lane == null) return
        var i = 0
        while (i < n) {
            val m = min(GAIN_SUBBLOCK, n - i)
            val g0 = lane.valueAt(t0 + i).toFloat()
            val g1 = lane.valueAt(t0 + i + m).toFloat()
            if (g0 == 1f && g1 == 1f) { i += m; continue }
            val dg = (g1 - g0) / m
            for (c in 0 until channels) {
                val w = work[c]
                var g = g0
                for (k in 0 until m) { w[i + k] *= g; g += dg }
            }
            i += m
        }
    }

    companion object {
        /** Gain lanes are evaluated every this many frames and ramped linearly in between. */
        const val GAIN_SUBBLOCK = 64
    }
}
