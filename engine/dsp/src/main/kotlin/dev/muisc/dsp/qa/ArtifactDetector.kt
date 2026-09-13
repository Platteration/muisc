package dev.muisc.dsp.qa

import dev.muisc.audio.AudioBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A detected click / discontinuity at sample [frame]. [magnitude] is the absolute second difference, [ratio] its ratio to the local robust noise scale. */
data class Click(val frame: Int, val magnitude: Float, val ratio: Float, val channel: Int = 0)

/** A sustained short-term level step at sample [frame] from [fromDb] to [toDb] (dBFS RMS). */
data class LevelJump(val frame: Int, val fromDb: Float, val toDb: Float, val channel: Int = 0) {
    val deltaDb: Float get() = toDb - fromDb
}

/** A run of [length] consecutive samples with `|x| >= clipThreshold` starting at [start]. */
data class ClipRun(val start: Int, val length: Int, val channel: Int = 0)

/** A run of [length] consecutive (near-)zero samples starting at [start], strictly inside the signal. */
data class SilenceGap(val start: Int, val length: Int, val channel: Int = 0)

/**
 * Result of [ArtifactDetector.analyze]. [isClean] is true when no list has entries and the DC offset is within
 * limits; [summary] is a human-readable multi-line report suitable for CLI output and test failure messages.
 */
data class ArtifactReport(
    val sampleRate: Int,
    val frames: Int,
    val channels: Int,
    val clicks: List<Click>,
    val levelJumps: List<LevelJump>,
    val clipRuns: List<ClipRun>,
    val silenceGaps: List<SilenceGap>,
    /** Mean sample value per channel. */
    val dcOffset: FloatArray,
    val dcOffsetMax: Float,
) {
    /** Channels whose |DC offset| exceeds the configured maximum. */
    val dcOffsetExceeded: List<Int> get() = dcOffset.indices.filter { abs(dcOffset[it]) > dcOffsetMax }

    fun isClean(): Boolean =
        clicks.isEmpty() && levelJumps.isEmpty() && clipRuns.isEmpty() && silenceGaps.isEmpty() && dcOffsetExceeded.isEmpty()

    fun summary(): String {
        val sb = StringBuilder()
        val secs = frames.toDouble() / sampleRate
        sb.append("ArtifactReport: ").append(if (isClean()) "CLEAN" else "ISSUES")
            .append(" (%d ch, %d frames, %.2f s @ %d Hz)\n".format(channels, frames, secs, sampleRate))
        sb.append("  clicks: ${clicks.size}")
        clicks.take(MAX_LISTED).forEach { sb.append("\n    ch${it.channel} @ %.3fs (#%d) mag=%.3f ratio=%.0f".format(it.frame.toDouble() / sampleRate, it.frame, it.magnitude, it.ratio)) }
        if (clicks.size > MAX_LISTED) sb.append("\n    ... ${clicks.size - MAX_LISTED} more")
        sb.append("\n  level jumps: ${levelJumps.size}")
        levelJumps.take(MAX_LISTED).forEach { sb.append("\n    ch${it.channel} @ %.3fs %.1f dB -> %.1f dB (%+.1f dB)".format(it.frame.toDouble() / sampleRate, it.fromDb, it.toDb, it.deltaDb)) }
        if (levelJumps.size > MAX_LISTED) sb.append("\n    ... ${levelJumps.size - MAX_LISTED} more")
        sb.append("\n  clipping runs: ${clipRuns.size}")
        clipRuns.take(MAX_LISTED).forEach { sb.append("\n    ch${it.channel} @ %.3fs len=%d".format(it.start.toDouble() / sampleRate, it.length)) }
        if (clipRuns.size > MAX_LISTED) sb.append("\n    ... ${clipRuns.size - MAX_LISTED} more")
        sb.append("\n  silence gaps: ${silenceGaps.size}")
        silenceGaps.take(MAX_LISTED).forEach { sb.append("\n    ch${it.channel} @ %.3fs len=%.1f ms".format(it.start.toDouble() / sampleRate, it.length * 1000.0 / sampleRate)) }
        if (silenceGaps.size > MAX_LISTED) sb.append("\n    ... ${silenceGaps.size - MAX_LISTED} more")
        sb.append("\n  dc offset: ").append(dcOffset.joinToString(", ") { "%.4f".format(it) })
        if (dcOffsetExceeded.isNotEmpty()) sb.append(" EXCEEDS ${"%.4f".format(dcOffsetMax)} on ch ${dcOffsetExceeded.joinToString()}")
        return sb.toString()
    }

    override fun toString(): String = summary()

    private companion object { const val MAX_LISTED = 10 }
}

/**
 * Numeric audio-quality checks for rendered transitions and tests. One-shot: every detector takes a whole
 * channel array (work buffers are allocated per call, sized to the input).
 *
 * **Clicks / discontinuities** ([detectClicks]). Uses the second difference `d2[n] = x[n+1] - 2x[n] + x[n-1]`,
 * which is ~0 for smooth signals and equals the step height (twice, with opposite sign) for a step. A sample is
 * a click candidate when all three hold:
 *  1. `|d2[n]| >= clickMinMagnitude` (absolute floor, see below);
 *  2. `|d2[n]| >= clickMadFactor * s(n)`, where `s(n)` is a robust local scale: `1.4826 * MAD(d2)` computed on
 *     non-overlapping blocks of [clickBlockMs], taking the maximum over the block containing `n` and its two
 *     neighbours (so the onset of a noise burst, whose *following* block is noisy, is not a click);
 *  3. the RMS of `d2` over the 5 samples around `n` exceeds [clickRmsRatio] times the RMS of `d2` over the
 *     surrounding ±[clickBlockMs] (excluding those 5 samples): a click is energy concentrated in a couple of
 *     samples, whereas drum hits and noise bursts spread it over many milliseconds.
 * Candidates closer than 2 ms are merged into the one with the largest magnitude.
 *
 * The magnitude floor default (0.15) is tuned with `SyntheticSong` from `engine/audio`: its bass/kick notes are
 * truncated without release and produce genuine steps of 0.02–0.09 depending on tempo (0.087 at 174 bpm), which
 * must not be reported, while a crossfade discontinuity of 0.5 (the spec's artificial step) is flagged with a
 * 3x margin. Lower it for stricter QA of material without such truncations.
 *
 * **Level jumps** ([detectLevelJumps]). Short-term RMS in dBFS over consecutive [levelWindowMs] windows. A rise
 * is reported at a window boundary when the *loudest* of the [levelHoldWindows] windows before it is more than
 * [levelJumpDb] below the *quietest* of the [levelHoldWindows] windows after it (and symmetrically for a drop),
 * i.e. only sustained steps count; drum onsets that decay within the hold period do not. Boundaries within the
 * first/last hold period and steps from/to silence (quiet side below [silenceDb]) are excluded (fade-ins/outs
 * and leading silence are normal; gaps are covered by the silence detector), as are steps whose loud side is
 * below [levelFloorDb]: the tail of a linear fade drops by an unbounded number of dB per window, but nothing
 * below -30 dBFS is a level problem.
 *
 * **Clipping** ([detectClipping]): runs of at least [clipMinRun] consecutive samples with `|x| >= clipThreshold`.
 *
 * **DC offset** ([dcOffset]): mean sample value; flagged in the report when `|mean| > dcOffsetMax`.
 *
 * **Silence gaps** ([detectSilenceGaps]): runs of samples with `|x| <= silenceThreshold` longer than
 * [silenceMinMs] that do not touch the start or end of the signal.
 */
class ArtifactDetector(
    val sampleRate: Int,
    val clickMinMagnitude: Float = 0.15f,
    val clickMadFactor: Float = 8f,
    val clickRmsRatio: Float = 4f,
    val clickBlockMs: Double = 10.0,
    val levelWindowMs: Double = 50.0,
    val levelJumpDb: Float = 10f,
    val levelHoldWindows: Int = 4,
    val clipThreshold: Float = 0.999f,
    val clipMinRun: Int = 3,
    val dcOffsetMax: Float = 0.01f,
    val silenceMinMs: Double = 100.0,
    val silenceThreshold: Float = 1e-5f,
    val silenceDb: Float = -70f,
    val levelFloorDb: Float = -30f,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(levelHoldWindows >= 1 && clipMinRun >= 1) { "levelHoldWindows and clipMinRun must be >= 1" }
    }

    /** Runs every detector on every channel of [buffer] and merges the results (sorted by frame). */
    fun analyze(buffer: AudioBuffer): ArtifactReport {
        require(buffer.sampleRate == sampleRate) { "buffer sample rate ${buffer.sampleRate} != detector sample rate $sampleRate" }
        val clicks = ArrayList<Click>()
        val jumps = ArrayList<LevelJump>()
        val clips = ArrayList<ClipRun>()
        val gaps = ArrayList<SilenceGap>()
        val dc = FloatArray(buffer.channelCount)
        for (c in 0 until buffer.channelCount) {
            val x = buffer[c]
            clicks += detectClicks(x, c)
            jumps += detectLevelJumps(x, c)
            clips += detectClipping(x, c)
            gaps += detectSilenceGaps(x, c)
            dc[c] = dcOffset(x)
        }
        clicks.sortBy { it.frame }; jumps.sortBy { it.frame }; clips.sortBy { it.start }; gaps.sortBy { it.start }
        return ArtifactReport(sampleRate, buffer.frames, buffer.channelCount, clicks, jumps, clips, gaps, dc, dcOffsetMax)
    }

    /** Runs every detector on a single mono channel. */
    fun analyze(x: FloatArray): ArtifactReport = analyze(AudioBuffer.mono(sampleRate, x))

    /** Click / discontinuity detector (see class doc). */
    fun detectClicks(x: FloatArray, channel: Int = 0): List<Click> {
        val n = x.size
        if (n < 3) return emptyList()
        val d2 = FloatArray(n)
        for (i in 1 until n - 1) d2[i] = x[i + 1] - 2f * x[i] + x[i - 1]

        // Block-wise robust scale: 1.4826 * MAD of d2 per block.
        val block = max(16, Math.round(clickBlockMs / 1000.0 * sampleRate).toInt())
        val nBlocks = (n + block - 1) / block
        val scale = FloatArray(nBlocks)
        val work = FloatArray(block)
        for (b in 0 until nBlocks) {
            val s = b * block
            val len = min(block, n - s)
            System.arraycopy(d2, s, work, 0, len)
            val med = medianInPlace(work, len)
            for (i in 0 until len) work[i] = abs(d2[s + i] - med)
            scale[b] = 1.4826f * medianInPlace(work, len)
        }

        // Prefix sum of d2^2 for the RMS-ratio criterion.
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + d2[i].toDouble() * d2[i]

        val ctx = block
        val minRatio2 = clickRmsRatio.toDouble() * clickRmsRatio
        val raw = ArrayList<Click>()
        for (i in 1 until n - 1) {
            val a = abs(d2[i])
            if (a < clickMinMagnitude) continue
            val b = i / block
            var s = scale[b]
            if (b > 0 && scale[b - 1] > s) s = scale[b - 1]
            if (b + 1 < nBlocks && scale[b + 1] > s) s = scale[b + 1]
            if (a < clickMadFactor * s) continue
            val lo = max(0, i - 2); val hi = min(n - 1, i + 2)
            val spikeE = prefix[hi + 1] - prefix[lo]
            val spikeN = hi - lo + 1
            val clo = max(0, i - ctx); val chi = min(n - 1, i + ctx)
            val ctxE = prefix[chi + 1] - prefix[clo] - spikeE
            val ctxN = chi - clo + 1 - spikeN
            val spikeMs = spikeE / spikeN
            val ctxMs = if (ctxN > 0) ctxE / ctxN else 0.0
            if (spikeMs < minRatio2 * ctxMs) continue
            val ratio = if (s > 0f) a / s else Float.POSITIVE_INFINITY
            raw += Click(i, a, ratio, channel)
        }
        if (raw.isEmpty()) return raw
        // Merge candidates closer than 2 ms, keeping the strongest.
        val mergeDist = max(2, Math.round(0.002 * sampleRate).toInt())
        val out = ArrayList<Click>()
        var best = raw[0]
        var lastFrame = raw[0].frame
        for (k in 1 until raw.size) {
            val c = raw[k]
            if (c.frame - lastFrame <= mergeDist) {
                if (c.magnitude > best.magnitude) best = c
            } else {
                out += best
                best = c
            }
            lastFrame = c.frame
        }
        out += best
        return out
    }

    /** Sustained short-term level step detector (see class doc). Returns jumps at window boundaries. */
    fun detectLevelJumps(x: FloatArray, channel: Int = 0): List<LevelJump> {
        val win = max(1, Math.round(levelWindowMs / 1000.0 * sampleRate).toInt())
        val nWin = x.size / win
        val hold = levelHoldWindows
        if (nWin < 2 * hold + 1) return emptyList()
        val db = FloatArray(nWin)
        for (w in 0 until nWin) {
            var acc = 0.0
            val s = w * win
            for (i in s until s + win) acc += x[i].toDouble() * x[i]
            db[w] = rmsToDb(sqrt(acc / win))
        }
        val out = ArrayList<LevelJump>()
        var w = hold
        while (w + hold <= nWin) {
            var preMin = Float.MAX_VALUE; var preMax = -Float.MAX_VALUE
            for (k in w - hold until w) { val v = db[k]; if (v < preMin) preMin = v; if (v > preMax) preMax = v }
            var postMin = Float.MAX_VALUE; var postMax = -Float.MAX_VALUE
            for (k in w until w + hold) { val v = db[k]; if (v < postMin) postMin = v; if (v > postMax) postMax = v }
            // The quiet side must be above the silence floor (steps from/to silence are not level jumps) and the
            // loud side above levelFloorDb (a linear fade's tail is an unbounded dB drop per window but inaudible).
            val rise = postMin - preMax > levelJumpDb && preMax > silenceDb && postMin > levelFloorDb
            val drop = preMin - postMax > levelJumpDb && postMax > silenceDb && preMin > levelFloorDb
            if (rise) {
                out += LevelJump(w * win, preMax, postMin, channel)
                w += hold // don't report the same step at every boundary inside the hold period
            } else if (drop) {
                out += LevelJump(w * win, preMin, postMax, channel)
                w += hold
            } else {
                w++
            }
        }
        return out
    }

    /** Runs of at least [clipMinRun] consecutive samples with `|x| >= clipThreshold`. */
    fun detectClipping(x: FloatArray, channel: Int = 0): List<ClipRun> {
        val out = ArrayList<ClipRun>()
        var runStart = -1
        for (i in x.indices) {
            val v = x[i]
            val clipped = v >= clipThreshold || v <= -clipThreshold
            if (clipped) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                if (i - runStart >= clipMinRun) out += ClipRun(runStart, i - runStart, channel)
                runStart = -1
            }
        }
        if (runStart >= 0 && x.size - runStart >= clipMinRun) out += ClipRun(runStart, x.size - runStart, channel)
        return out
    }

    /** Mean sample value. */
    fun dcOffset(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var acc = 0.0
        for (v in x) acc += v
        return (acc / x.size).toFloat()
    }

    /** Runs of `|x| <= silenceThreshold` longer than [silenceMinMs] that neither start at sample 0 nor end at the last sample. */
    fun detectSilenceGaps(x: FloatArray, channel: Int = 0): List<SilenceGap> {
        val minLen = max(1, Math.round(silenceMinMs / 1000.0 * sampleRate).toInt())
        val out = ArrayList<SilenceGap>()
        var runStart = -1
        for (i in x.indices) {
            val v = x[i]
            val silent = v <= silenceThreshold && v >= -silenceThreshold
            if (silent) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                if (runStart > 0 && i - runStart > minLen) out += SilenceGap(runStart, i - runStart, channel)
                runStart = -1
            }
        }
        // A trailing run touches the end: not an inner gap.
        return out
    }

    /** Selects the median of `a[0 until len]` (reorders the array). Uses full sort: blocks are small. */
    private fun medianInPlace(a: FloatArray, len: Int): Float {
        if (len == 0) return 0f
        java.util.Arrays.sort(a, 0, len)
        return if (len % 2 == 1) a[len / 2] else 0.5f * (a[len / 2 - 1] + a[len / 2])
    }

    companion object {
        /** RMS (linear, ≥ 0) to dBFS with a -120 dB floor. */
        fun rmsToDb(rms: Double): Float = if (rms <= 1e-6) -120f else (20.0 * log10(rms)).toFloat()
    }
}
