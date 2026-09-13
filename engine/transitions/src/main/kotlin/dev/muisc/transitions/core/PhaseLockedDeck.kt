package dev.muisc.transitions.core

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.resample.VariableRateResampler
import dev.muisc.dsp.stretch.WsolaStretcher
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** How a deck is brought onto the master grid. */
enum class StretchMode {
    /** Variable-rate resampling (vinyl-style: pitch follows tempo). Bit-exact at ratio 1, no phasiness, ~35 cents at 2 %. */
    RESAMPLE,
    /** Pitch-preserving WSOLA with transient protection (key lock). */
    WSOLA,
    /** Reserved for a phase-vocoder stretcher; [PhaseLockedDeck] currently falls back to [WSOLA]. */
    PHASE_VOCODER,
}

/**
 * Chooses the stretcher for a (maximum) stretch ratio `outputDuration / inputDuration`:
 *  - `keyLock = false` → [StretchMode.RESAMPLE] (the user asked for vinyl behaviour; the planner caps the ratio);
 *  - `|ratio - 1| < resampleThresholdPercent` (default 2 %: the pitch drifts by < 35 cents, inaudible as a key change and
 *    free of WSOLA phasiness) → [StretchMode.RESAMPLE];
 *  - otherwise [StretchMode.WSOLA]; [StretchMode.PHASE_VOCODER] only when [select]'s `forcePv` says so.
 */
object StretcherSelector {
    const val DEFAULT_RESAMPLE_THRESHOLD_PERCENT: Double = 2.0

    fun select(ratio: Double, prefs: TransitionPrefs, forcePv: Boolean = false): StretchMode =
        select(ratio, prefs.keyLock, DEFAULT_RESAMPLE_THRESHOLD_PERCENT, forcePv)

    fun select(ratio: Double, keyLock: Boolean, resampleThresholdPercent: Double = DEFAULT_RESAMPLE_THRESHOLD_PERCENT, forcePv: Boolean = false): StretchMode {
        require(ratio > 0.0 && ratio.isFinite()) { "ratio must be positive, was $ratio" }
        if (forcePv) return StretchMode.PHASE_VOCODER
        if (!keyLock) return StretchMode.RESAMPLE
        return if (abs(ratio - 1.0) * 100.0 < resampleThresholdPercent) StretchMode.RESAMPLE else StretchMode.WSOLA
    }
}

/**
 * Piecewise-linear map from output frames (relative to the first rendered master beat) to deck source frames
 * (relative to the deck window): knot `i` maps output `outFrames[i]` to source `srcFrames[i]`, one knot per master
 * beat boundary. Between knots the map is linear (constant playback ratio within a beat); outside it extrapolates
 * with the first / last slope.
 */
class TimeMap(val outFrames: DoubleArray, val srcFrames: DoubleArray) {
    init {
        require(outFrames.size == srcFrames.size && outFrames.size >= 2) { "a time map needs >= 2 knots" }
        require((1 until outFrames.size).all { outFrames[it] > outFrames[it - 1] }) { "output knots must increase" }
        require((1 until srcFrames.size).all { srcFrames[it] > srcFrames[it - 1] }) { "source knots must increase (deck beats must be strictly increasing)" }
    }

    /** Number of beats (segments). */
    val beats: Int get() = outFrames.size - 1

    /** Output length in frames (`outFrames.last() - outFrames.first()`, an integer for grids built by [MasterGrid]). */
    val totalOutFrames: Long get() = Math.round(outFrames[outFrames.size - 1] - outFrames[0])

    /** Stretch ratio `outputDuration / inputDuration` of beat [i]. */
    fun ratioOfBeat(i: Int): Double = (outFrames[i + 1] - outFrames[i]) / (srcFrames[i + 1] - srcFrames[i])

    /** Largest `|ratio - 1| * 100` over all beats. */
    val maxDeviationPercent: Double get() = (0 until beats).maxOf { abs(ratioOfBeat(it) - 1.0) } * 100.0

    /** Source position for an output frame. */
    fun sourceAt(outFrame: Double): Double {
        val n = outFrames.size
        var i = when {
            outFrame <= outFrames[0] -> 0
            outFrame >= outFrames[n - 1] -> n - 2
            else -> {
                var lo = 0; var hi = n - 1
                while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (outFrames[mid] <= outFrame) lo = mid else hi = mid }
                lo
            }
        }
        if (i > n - 2) i = n - 2
        val t = (outFrame - outFrames[i]) / (outFrames[i + 1] - outFrames[i])
        return srcFrames[i] + t * (srcFrames[i + 1] - srcFrames[i])
    }
}

/**
 * Renders one deck's audio onto a [MasterGrid] so that deck beat `deckStartBeat + k` lands exactly on master beat
 * `fromMasterBeat + k` (k in `0..beats`). The output has exactly `master.frameOfBeat(from + beats) - master.frameOfBeat(from)`
 * frames.
 *
 * Inputs are WINDOW-relative: [deck] is the decoded window, [deckGrid] the deck's beat grid with frames relative to
 * the window (see `BeatGrid.relativeTo`), [onsetFrames] the deck's onsets relative to the window. [deckStartBeat]
 * may be fractional (a deck beat position between two tracked beats).
 *
 * Mechanism: a [TimeMap] (output frame → deck frame, linear beat to beat) is derived from the two grids. Then either
 *  - [StretchMode.RESAMPLE]: a `dsp` [VariableRateResampler] reads the deck along the map with a constant ratio per
 *    beat, its read position re-seated on the exact map position at every beat (so there is no accumulated error at
 *    all); ratio 1 with integer positions is bit-exact; or
 *  - [StretchMode.WSOLA]: a streaming `dsp` [WsolaStretcher] whose ratio is set per synthesis hop from the local slope
 *    of the map plus a phase-locked-loop term: the nominal input position the stretcher will reach at the next hop is
 *    mirrored here, and each hop's ratio is chosen so that it lands on the map exactly (`r_m = hop / (S(n_{m+1}) - P_m)`),
 *    so the accumulated source-position error stays at floating-point level (far below 1 ms). Transients
 *    ([Options.transientFrames], defaulting to [onsetFrames] and else the deck's beat frames) are pinned so kicks are
 *    copied once, at their nominal output time. A pre-roll of [Options.preRollHops] hops at ratio 1 warms the
 *    overlap-add up before the first output frame and is discarded.
 *
 * All channels are processed with one shared read position / one shared WSOLA offset, so stereo stays coherent.
 * When [mode] is null it is selected per render by [StretcherSelector] from the map's largest ratio deviation and
 * [Options.keyLock] / [Options.resampleThresholdPercent]. [StretchMode.PHASE_VOCODER] falls back to WSOLA.
 *
 * The object is a per-render helper (create one per deck per render); [ratioTrace] and [modeUsed] describe the last
 * [render] call.
 */
class PhaseLockedDeck(
    val deck: AudioBuffer,
    val deckGrid: BeatGrid,
    val deckStartBeat: Double,
    val onsetFrames: LongArray = LongArray(0),
    val mode: StretchMode? = null,
    val sampleRate: Int = deck.sampleRate,
    val options: Options = Options(),
) {
    /** Tuning options; see [PhaseLockedDeck]. */
    class Options(
        /** Preserve pitch (WSOLA) when the ratio is not tiny; false = always resample (vinyl). */
        val keyLock: Boolean = true,
        /** Ratios within this many percent of 1 are resampled even with key lock (default 2 %). */
        val resampleThresholdPercent: Double = StretcherSelector.DEFAULT_RESAMPLE_THRESHOLD_PERCENT,
        /** Window-relative transient frames to pin in WSOLA; null = [onsetFrames], or the deck beat frames when there are none. */
        val transientFrames: LongArray? = null,
        val wsolaFrameMs: Double = 30.0,
        val wsolaToleranceMs: Double = 10.0,
        /** Anti-alias the resampler when speeding up. */
        val antiAlias: Boolean = true,
        /** WSOLA warm-up hops before the first output frame (discarded). */
        val preRollHops: Int = 8,
        /** Clamp of the per-hop WSOLA ratio (safety against degenerate maps). */
        val minRatio: Double = 0.2,
        val maxRatio: Double = 5.0,
    )

    init {
        require(deckGrid.beatCount >= 2) { "deck grid needs at least two beats" }
        require(sampleRate > 0)
    }

    /** Stretch ratio (output / input) actually used per rendered master beat (filled by [render]). */
    var ratioTrace: DoubleArray = DoubleArray(0)
        private set

    /** Stretch mode used by the last [render] (null before). */
    var modeUsed: StretchMode? = null
        private set

    /** The time map for rendering master beats `[fromMasterBeat, fromMasterBeat + beats]`. */
    fun timeMap(master: MasterGrid, fromMasterBeat: Int, beats: Int): TimeMap {
        require(beats >= 1) { "beats must be >= 1" }
        require(fromMasterBeat >= 0 && fromMasterBeat + beats <= master.beatCount) { "master beats [$fromMasterBeat, ${fromMasterBeat + beats}] outside 0..${master.beatCount}" }
        val f0 = master.beatFrames[fromMasterBeat]
        val out = DoubleArray(beats + 1) { (master.beatFrames[fromMasterBeat + it] - f0).toDouble() }
        val src = DoubleArray(beats + 1) { deckGrid.frameOfBeatExact(deckStartBeat + it, sampleRate) }
        return TimeMap(out, src)
    }

    /** Renders master beats `[fromMasterBeat, fromMasterBeat + beats)`; exactly `master.periodFrames` summed frames. */
    fun render(master: MasterGrid, fromMasterBeat: Int, beats: Int): AudioBuffer {
        require(master.sampleRate == sampleRate) { "master grid rate ${master.sampleRate} != deck rate $sampleRate" }
        val map = timeMap(master, fromMasterBeat, beats)
        val selected = mode ?: StretcherSelector.select(1.0 + map.maxDeviationPercent / 100.0, options.keyLock, options.resampleThresholdPercent)
        val used = if (selected == StretchMode.PHASE_VOCODER) StretchMode.WSOLA else selected
        modeUsed = used
        return when (used) {
            StretchMode.RESAMPLE -> renderResample(map)
            else -> renderWsola(map)
        }
    }

    private fun renderResample(map: TimeMap): AudioBuffer {
        val ch = deck.channelCount
        val total = map.totalOutFrames.toInt()
        val out = Array(ch) { FloatArray(total) }
        val rs = VariableRateResampler(ch, options.antiAlias)
        val trace = DoubleArray(map.beats)
        for (i in 0 until map.beats) {
            val o0 = Math.round(map.outFrames[i]).toInt()
            val o1 = Math.round(map.outFrames[i + 1]).toInt()
            val n = o1 - o0
            val r = map.ratioOfBeat(i)
            trace[i] = r
            if (n <= 0) continue
            rs.reset(map.srcFrames[i])
            rs.setRatio(1.0 / r)
            rs.process(deck.channels, out, n, o0)
        }
        ratioTrace = trace
        return AudioBuffer(sampleRate, out)
    }

    private fun renderWsola(map: TimeMap): AudioBuffer {
        val ch = deck.channelCount
        val total = map.totalOutFrames.toInt()
        val stretcher = WsolaStretcher(options.wsolaFrameMs, options.wsolaToleranceMs)
        val frameSize = stretcher.frameSizeFor(sampleRate)
        val hop = frameSize / 2
        val pre = options.preRollHops.coerceAtLeast(1) * hop
        val base = Math.round(map.srcFrames[0])
        val inStart = base - pre
        // Source position (in pushed-input coordinates) that output frame n of the stream must read.
        fun srcRel(n: Int): Double = if (n <= pre) n.toDouble() else map.sourceAt((n - pre).toDouble()) - base + pre

        // Ratio schedule with PLL: mirror the stream's nominal position and aim every hop at the map.
        val segments = (pre + total) / hop + 3
        val ratios = DoubleArray(segments + 1)
        var p = 0.0
        for (m in 0 until segments) {
            val target = srcRel((m + 1) * hop)
            var r = hop / (target - p)
            if (!(r > 0.0) || !r.isFinite()) r = options.maxRatio
            r = r.coerceIn(options.minRatio, options.maxRatio)
            ratios[m] = r
            p += hop / r
        }
        ratios[segments] = ratios[segments - 1]

        val latency = frameSize + hop + stretcher.toleranceFor(sampleRate) + 2
        val inputLen = ceil(srcRel(pre + total + hop)).toInt() + latency + 2 * frameSize
        val input = deck.slice(inStart.toInt(), inStart.toInt() + inputLen)
        val pins = options.transientFrames ?: onsetFrames.takeIf { it.isNotEmpty() } ?: deckGrid.beatFrames
        val transients = pins.asSequence().map { it - inStart }.filter { it >= 0 && it < inputLen }.map { it.toInt() }.sorted().toList().toIntArray()

        val stream = stretcher.createStream(sampleRate, ch, transients)
        stream.setRatioCurve { n -> ratios[minOf(n / hop, segments)] }
        val needed = pre + total
        val full = Array(ch) { FloatArray(needed) }
        val scratch = Array(ch) { FloatArray(8192) }
        var got = 0
        fun drain() {
            while (true) {
                val avail = stream.available()
                if (avail <= 0) break
                val n = if (got < needed) stream.pull(full, minOf(avail, needed - got), got).also { got += it }
                else stream.pull(scratch, minOf(avail, scratch[0].size), 0)
                if (n == 0) break
            }
        }
        val block = 4096
        var pos = 0
        while (pos < input.frames) {
            val n = minOf(block, input.frames - pos)
            stream.push(input.channels, n, pos)
            pos += n
            drain()
        }
        stream.flush()
        drain()
        val out = Array(ch) { full[it].copyOfRange(pre, pre + total) }

        // Per-beat trace: mean scheduled ratio over the hops that start inside the beat.
        val trace = DoubleArray(map.beats)
        for (i in 0 until map.beats) {
            val o0 = map.outFrames[i]; val o1 = map.outFrames[i + 1]
            var sum = 0.0; var cnt = 0
            val mFirst = floor((o0 + pre) / hop).toInt().coerceAtLeast(0)
            val mLast = ceil((o1 + pre) / hop).toInt().coerceAtMost(segments)
            for (m in mFirst until mLast) { val start = m * hop - pre; if (start >= o0 && start < o1) { sum += ratios[m]; cnt++ } }
            trace[i] = if (cnt > 0) sum / cnt else map.ratioOfBeat(i)
        }
        ratioTrace = trace
        return AudioBuffer(sampleRate, out)
    }
}
