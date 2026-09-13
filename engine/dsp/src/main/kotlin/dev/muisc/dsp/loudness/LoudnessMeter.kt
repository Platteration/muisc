package dev.muisc.dsp.loudness

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.BiquadCascade
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * Result of a BS.1770-4 / EBU R128 loudness measurement.
 *
 * Loudness values are in LUFS (= LKFS); [Double.NEGATIVE_INFINITY] means "no signal" (digital silence, or a
 * signal shorter than the measurement window). Curves are on a 100 ms grid: `momentaryLufs[k]` is the loudness
 * of the 400 ms window ending at `(k + 4) * 0.1 s`, `shortTermLufs[k]` that of the 3 s window ending at
 * `(k + 30) * 0.1 s` (see [momentaryTimeSec] / [shortTermTimeSec]).
 */
class LoudnessResult(
    /** Integrated (programme) loudness with the absolute (-70 LUFS) and relative (-10 LU) gates applied. */
    val integratedLufs: Double,
    /** Loudness range (EBU Tech 3342) in LU. 0 when fewer than two short-term blocks survive the gates. */
    val loudnessRangeLu: Double,
    /** Momentary loudness (400 ms windows) every 100 ms, first value at 0.4 s. */
    val momentaryLufs: DoubleArray,
    /** Short-term loudness (3 s windows) every 100 ms, first value at 3 s. */
    val shortTermLufs: DoubleArray,
    /** The relative gate threshold that was applied to the integrated measurement (integrated of the absolute-gated blocks minus 10 LU). */
    val relativeThresholdLufs: Double,
    /** Number of 400 ms blocks that passed both gates, and the total number of blocks. */
    val gatedBlockCount: Int,
    val totalBlockCount: Int,
) {
    /** Grid spacing of the curves in seconds. */
    val gridSec: Double get() = 0.1

    /** Loudest momentary value (max of [momentaryLufs]), -inf when empty. */
    val maxMomentaryLufs: Double get() = momentaryLufs.maxOrNull() ?: Double.NEGATIVE_INFINITY

    /** Loudest short-term value (max of [shortTermLufs]), -inf when empty. */
    val maxShortTermLufs: Double get() = shortTermLufs.maxOrNull() ?: Double.NEGATIVE_INFINITY

    /** End time (s) of the window behind `momentaryLufs[index]`. */
    fun momentaryTimeSec(index: Int): Double = (index + 4) * 0.1

    /** End time (s) of the window behind `shortTermLufs[index]`. */
    fun shortTermTimeSec(index: Int): Double = (index + 30) * 0.1

    override fun toString(): String =
        "LoudnessResult(I=%.2f LUFS, LRA=%.2f LU, maxM=%.2f, maxS=%.2f, blocks=%d/%d)".format(
            integratedLufs, loudnessRangeLu, maxMomentaryLufs, maxShortTermLufs, gatedBlockCount, totalBlockCount,
        )
}

/**
 * Streaming ITU-R BS.1770-4 / EBU R128 loudness meter.
 *
 * Algorithm (BS.1770-4 §2 and EBU Tech 3341 / 3342):
 *  1. Every channel is filtered with the K-weighting ([KWeighting]: +4 dB high shelf, RLB high-pass).
 *  2. The mean square of each weighted channel is accumulated over consecutive 100 ms sub-blocks; the
 *     channel-weighted sum `sum_i G_i z_i` of a sub-block is stored (G = 1 for mono/stereo/centre, 1.41 for the
 *     surround channels of a 5.0 layout, see [defaultChannelWeights]).
 *  3. A *gating block* is 400 ms = 4 sub-blocks with a 100 ms hop (75 % overlap); its loudness is
 *     `-0.691 + 10 log10(mean power)`. The same sub-blocks give the short-term loudness (30 sub-blocks = 3 s).
 *  4. Integrated loudness: blocks below the absolute gate (-70 LUFS) are dropped; the relative threshold is the
 *     power-mean loudness of the remaining blocks minus 10 LU; the integrated loudness is the power-mean of the
 *     blocks above that threshold.
 *  5. Loudness range (EBU Tech 3342): short-term values below -70 LUFS are dropped, a relative gate 20 LU below
 *     their power mean is applied, and LRA is the difference between the 95th and 10th percentile of the
 *     remaining values (percentile index rounded, as in libebur128).
 *
 * Feed audio with [process] (any block size; blocks are internally chunked into a fixed work buffer, so
 * processing allocates nothing except the growth of the sub-block history, which is doubled geometrically —
 * pass [initialCapacitySec] to pre-size it). Query [momentaryLufs] / [shortTermLufs] at any time for live
 * metering and [result] for the full measurement. [reset] clears everything for the next signal.
 *
 * Accuracy: the EBU Tech 3341 conformance cases (stereo 1 kHz sines at -23 / -33 dBFS, gated silence, mono)
 * read within 0.01 LU of their nominal values at 44.1 and 48 kHz.
 */
class LoudnessMeter(
    val sampleRate: Int,
    val channels: Int,
    channelWeights: FloatArray = defaultChannelWeights(channels),
    initialCapacitySec: Double = 300.0,
    private val chunkFrames: Int = 4096,
) {
    init {
        require(sampleRate > 0 && channels > 0) { "sampleRate and channels must be positive" }
        require(channelWeights.size == channels) { "channelWeights must have one entry per channel" }
        require(chunkFrames > 0)
    }

    /** Per-channel weights G_i (copied). */
    val channelWeights: FloatArray = channelWeights.copyOf()

    /** Length of one 100 ms sub-block in frames. */
    val subBlockFrames: Int = Math.round(0.1 * sampleRate).toInt().coerceAtLeast(1)

    private val filter: BiquadCascade = KWeighting.newFilter(channels, sampleRate)
    private val work = Array(channels) { FloatArray(chunkFrames) }
    private val acc = DoubleArray(channels)
    private var filled = 0

    /** Channel-weighted mean-square power of every completed 100 ms sub-block. */
    private var sub = DoubleArray(((initialCapacitySec * 10).toInt() + 64).coerceAtLeast(64))
    private var subCount = 0

    /** Total frames fed since the last [reset]. */
    var framesProcessed: Long = 0L
        private set

    /** Number of completed 100 ms sub-blocks. */
    val subBlockCount: Int get() = subCount

    /** Clears filter state, accumulators and the block history. */
    fun reset() {
        filter.reset()
        acc.fill(0.0)
        filled = 0
        subCount = 0
        framesProcessed = 0L
    }

    /** Feeds [frames] frames of every channel of [input], starting at [offset]. */
    fun process(input: Array<FloatArray>, frames: Int, offset: Int = 0) {
        require(input.size >= channels) { "input has ${input.size} channels, meter expects $channels" }
        var done = 0
        while (done < frames) {
            val n = min(chunkFrames, frames - done)
            for (ch in 0 until channels) System.arraycopy(input[ch], offset + done, work[ch], 0, n)
            filter.processInPlace(work, n)
            accumulate(n)
            done += n
        }
        framesProcessed += frames
    }

    /** Feeds a whole [AudioBuffer] (must match the meter's sample rate and channel count). */
    fun process(buffer: AudioBuffer) {
        require(buffer.sampleRate == sampleRate) { "buffer sample rate ${buffer.sampleRate} != $sampleRate" }
        require(buffer.channelCount == channels) { "buffer has ${buffer.channelCount} channels, meter expects $channels" }
        process(buffer.channels, buffer.frames)
    }

    private fun accumulate(n: Int) {
        var i = 0
        while (i < n) {
            val take = min(n - i, subBlockFrames - filled)
            for (ch in 0 until channels) {
                val w = work[ch]
                var s = 0.0
                for (k in i until i + take) { val v = w[k].toDouble(); s += v * v }
                acc[ch] += s
            }
            filled += take
            i += take
            if (filled == subBlockFrames) {
                var p = 0.0
                for (ch in 0 until channels) p += channelWeights[ch] * acc[ch]
                pushSubBlock(p / subBlockFrames)
                acc.fill(0.0)
                filled = 0
            }
        }
    }

    private fun pushSubBlock(power: Double) {
        if (subCount == sub.size) sub = sub.copyOf(sub.size * 2)
        sub[subCount++] = power
    }

    /** Mean power of the last [count] completed sub-blocks, or NaN when fewer are available. */
    private fun tailPower(count: Int): Double {
        if (subCount < count) return Double.NaN
        var s = 0.0
        for (k in subCount - count until subCount) s += sub[k]
        return s / count
    }

    /** Live momentary loudness: the last completed 400 ms (-inf until 400 ms have been processed). */
    fun momentaryLufs(): Double = powerToLufs(tailPower(MOMENTARY_SUB_BLOCKS))

    /** Live short-term loudness: the last completed 3 s (-inf until 3 s have been processed). */
    fun shortTermLufs(): Double = powerToLufs(tailPower(SHORT_TERM_SUB_BLOCKS))

    /** Builds the full measurement from everything fed so far (does not modify the meter's state). */
    fun result(): LoudnessResult {
        val momentaryPower = windowPowers(MOMENTARY_SUB_BLOCKS)
        val shortTermPower = windowPowers(SHORT_TERM_SUB_BLOCKS)
        val momentary = DoubleArray(momentaryPower.size) { powerToLufs(momentaryPower[it]) }
        val shortTerm = DoubleArray(shortTermPower.size) { powerToLufs(shortTermPower[it]) }

        // Integrated loudness, BS.1770-4 gating.
        val absPower = ABSOLUTE_GATE_POWER
        var sum = 0.0; var count = 0
        for (p in momentaryPower) if (p > absPower) { sum += p; count++ }
        val relativeThreshold = if (count == 0) Double.NEGATIVE_INFINITY else powerToLufs(sum / count) - RELATIVE_GATE_LU
        val relPower = lufsToPower(relativeThreshold)
        var gSum = 0.0; var gCount = 0
        for (p in momentaryPower) if (p > absPower && p > relPower) { gSum += p; gCount++ }
        val integrated = if (gCount == 0) Double.NEGATIVE_INFINITY else powerToLufs(gSum / gCount)

        return LoudnessResult(
            integratedLufs = integrated,
            loudnessRangeLu = loudnessRange(shortTermPower),
            momentaryLufs = momentary,
            shortTermLufs = shortTerm,
            relativeThresholdLufs = relativeThreshold,
            gatedBlockCount = gCount,
            totalBlockCount = momentaryPower.size,
        )
    }

    /** Mean power over every window of [len] consecutive sub-blocks, hop 1 sub-block. */
    private fun windowPowers(len: Int): DoubleArray {
        val n = subCount - len + 1
        if (n <= 0) return DoubleArray(0)
        val out = DoubleArray(n)
        var s = 0.0
        for (k in 0 until len) s += sub[k]
        out[0] = s / len
        for (j in 1 until n) {
            s += sub[j + len - 1] - sub[j - 1]
            out[j] = s / len
        }
        // Guard against negative drift of the running sum on (near-)silent input.
        for (j in 0 until n) if (out[j] < 0.0) out[j] = 0.0
        return out
    }

    /** EBU Tech 3342 loudness range from the short-term window powers. */
    private fun loudnessRange(shortTermPower: DoubleArray): Double {
        var sum = 0.0; var count = 0
        for (p in shortTermPower) if (p > ABSOLUTE_GATE_POWER) { sum += p; count++ }
        if (count == 0) return 0.0
        val relPower = lufsToPower(powerToLufs(sum / count) - LRA_RELATIVE_GATE_LU)
        val kept = DoubleArray(count)
        var m = 0
        for (p in shortTermPower) if (p > ABSOLUTE_GATE_POWER && p > relPower) kept[m++] = p
        if (m < 2) return 0.0
        java.util.Arrays.sort(kept, 0, m)
        val lo = kept[Math.round(0.10 * (m - 1)).toInt()]
        val hi = kept[Math.round(0.95 * (m - 1)).toInt()]
        return powerToLufs(hi) - powerToLufs(lo)
    }

    companion object {
        /** Blocks per 400 ms gating window. */
        const val MOMENTARY_SUB_BLOCKS: Int = 4
        /** Blocks per 3 s short-term window. */
        const val SHORT_TERM_SUB_BLOCKS: Int = 30
        /** BS.1770 absolute gate. */
        const val ABSOLUTE_GATE_LUFS: Double = -70.0
        /** BS.1770 relative gate (below the ungated loudness). */
        const val RELATIVE_GATE_LU: Double = 10.0
        /** EBU Tech 3342 relative gate for the loudness range. */
        const val LRA_RELATIVE_GATE_LU: Double = 20.0
        /** The BS.1770 loudness formula offset: `L = -0.691 + 10 log10(sum G_i z_i)`. */
        const val LOUDNESS_OFFSET_DB: Double = -0.691

        private val ABSOLUTE_GATE_POWER: Double = lufsToPower(ABSOLUTE_GATE_LUFS)

        /** Weighted mean-square power to LUFS (-inf for power <= 0). */
        fun powerToLufs(power: Double): Double =
            if (power.isNaN() || power <= 0.0) Double.NEGATIVE_INFINITY else LOUDNESS_OFFSET_DB + 10.0 * log10(power)

        /** Inverse of [powerToLufs] (0 for -inf). */
        fun lufsToPower(lufs: Double): Double =
            if (lufs == Double.NEGATIVE_INFINITY) 0.0 else 10.0.pow((lufs - LOUDNESS_OFFSET_DB) / 10.0)

        /**
         * BS.1770-4 channel weights for [channels] channels in the L, R, C, Ls, Rs order: 1.0 for the first three
         * (so mono and stereo are unweighted) and 1.41 for the two surround channels. Layouts with an LFE channel
         * are not modelled (an LFE should be excluded: pass explicit weights with 0 for it).
         */
        fun defaultChannelWeights(channels: Int): FloatArray = FloatArray(channels) { if (it == 3 || it == 4) 1.41f else 1f }

        /** One-shot measurement of a whole buffer. */
        fun measure(buffer: AudioBuffer): LoudnessResult {
            val meter = LoudnessMeter(buffer.sampleRate, buffer.channelCount, initialCapacitySec = buffer.durationSec)
            meter.process(buffer)
            return meter.result()
        }

        /** One-shot integrated loudness of a whole buffer (LUFS, -inf for silence). */
        fun integratedLufs(buffer: AudioBuffer): Double = measure(buffer).integratedLufs
    }
}
