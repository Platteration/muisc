package dev.muisc.analysis.rhythm

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dynamic-programming beat tracker after Ellis, "Beat tracking by dynamic programming" (J. New Music Research
 * 36(1), 2007).
 *
 * With `O(t)` the unit-normalised onset function and `p(t)` the local beat period in frames, the cumulative score
 * `C(t) = O(t) + max_{τ ∈ [p/2, 2p]} [ alpha * F(τ, p(t)) + C(t - τ) ]`, `F(τ, p) = -(ln(τ / p))^2`, is computed
 * left to right (predecessors before the start count as `C = 0`, so the first beats are placed on the period
 * alone); the best chain ends at the maximum of `C` over the last two periods and is recovered by backtracing.
 * With `O ≤ 1` and [alpha] ≈ 500, a 3 % deviation from the period costs ≈ 0.45 and a 10 % deviation ≈ 4.5,
 * so beats follow the onsets within a few percent of the period but never drift by a whole hop.
 *
 * The period curve is supplied by [Tempogram.periodCurve] (smoothed local tempo), so slow drift is followed.
 * Optionally every beat is then snapped to the nearest ODF local maximum within ±[snapFrames] frames
 * (±11.6 ms at the default hop).
 */
class BeatTracker(
    val alpha: Double = 500.0,
    val minRatio: Double = 0.5,
    val maxRatio: Double = 2.0,
    val snapFrames: Int = 1,
) {
    init { require(alpha > 0 && minRatio > 0 && maxRatio > minRatio) }

    /**
     * Tracks beats through [odf] using the per-frame beat [periodFrames]. Returns strictly increasing ODF frame
     * indices (empty when the ODF is empty or every period is non-positive).
     */
    fun track(odf: FloatArray, periodFrames: DoubleArray): IntArray {
        val n = odf.size
        if (n == 0 || periodFrames.size < n) return IntArray(0)
        var maxTau = 0
        for (t in 0 until n) { val hi = (periodFrames[t] * maxRatio).roundToInt(); if (hi > maxTau) maxTau = hi }
        if (maxTau < 1) return IntArray(0)
        val lnTau = DoubleArray(maxTau + 1) { if (it > 0) ln(it.toDouble()) else 0.0 }
        val score = DoubleArray(n)
        val back = IntArray(n) { -1 }
        for (t in 0 until n) {
            val p = periodFrames[t]
            if (p <= 0.0) { score[t] = odf[t].toDouble(); continue }
            val lo = max(1, (p * minRatio).roundToInt())
            val hi = max(lo, (p * maxRatio).roundToInt())
            val lnP = ln(p)
            var best = Double.NEGATIVE_INFINITY
            var bestPrev = -1
            for (tau in lo..hi) {
                val prev = t - tau
                val d = lnTau[tau] - lnP
                val cand = (if (prev >= 0) score[prev] else 0.0) - alpha * d * d
                if (cand > best) { best = cand; bestPrev = prev }
            }
            score[t] = odf[t] + best
            back[t] = bestPrev
        }
        // Best end point: maximum cumulative score within the last two periods.
        val tail = max(1, (2 * periodFrames[n - 1]).roundToInt())
        var end = n - 1
        for (t in max(0, n - tail) until n) if (score[t] > score[end]) end = t
        val beats = ArrayList<Int>()
        var t = end
        while (t >= 0) { beats.add(t); t = back[t] }
        beats.reverse()
        val out = IntArray(beats.size) { beats[it] }
        if (snapFrames > 0) snapToPeaks(odf, out, snapFrames)
        return out
    }

    /** Moves every beat to the ODF maximum within ±[radius] frames, keeping the sequence strictly increasing. */
    private fun snapToPeaks(odf: FloatArray, beats: IntArray, radius: Int) {
        for (i in beats.indices) {
            val b = beats[i]
            var best = b
            val lo = max(if (i > 0) beats[i - 1] + 1 else 0, b - radius)
            val hi = min(odf.size - 1, b + radius)
            for (u in lo..hi) if (odf[u] > odf[best]) best = u
            beats[i] = best
        }
        for (i in 1 until beats.size) if (beats[i] <= beats[i - 1]) beats[i] = beats[i - 1] + 1
    }
}

/**
 * Sub-frame re-alignment of beat / onset times onto waveform transients.
 *
 * The spectral flux of a percussive event peaks while the STFT window slides onto the transient, so ODF frame
 * times lead the waveform onset by up to half a window. This helper computes a log-power envelope in blocks of
 * [blockSize] samples (1.45 ms at 22.05 kHz) and, for a candidate time, finds the block with the largest rise
 * over [riseBlocks] blocks inside `[t - searchBeforeSec, t + searchAfterSec]`. When that rise exceeds
 * [minRiseDb] the transient's start is returned (the block before the rise peak), otherwise the candidate time
 * is returned unchanged (sustained material has no transient to align to).
 */
class TransientAligner(
    mono: FloatArray,
    val sampleRate: Int,
    val blockSize: Int = 32,
    val riseBlocks: Int = 2,
    val searchBeforeSec: Double = 0.03,
    val searchAfterSec: Double = 0.02,
    val minRiseDb: Double = 4.0,
) {
    private val blocks = (mono.size + blockSize - 1) / blockSize
    private val logPower = FloatArray(blocks)
    val blockSeconds: Double = blockSize.toDouble() / sampleRate

    init {
        for (b in 0 until blocks) {
            var acc = 0.0
            val s = b * blockSize
            val e = min(mono.size, s + blockSize)
            for (i in s until e) acc += mono[i].toDouble() * mono[i]
            logPower[b] = (10.0 * log10(acc / blockSize + 1e-10)).toFloat()
        }
    }

    /** Returns the aligned time for [timeSec], or [timeSec] itself when no transient is found nearby. */
    fun align(timeSec: Double): Double {
        if (blocks == 0) return timeSec
        val lo = max(riseBlocks, ((timeSec - searchBeforeSec) / blockSeconds).roundToInt())
        val hi = min(blocks - 1, ((timeSec + searchAfterSec) / blockSeconds).roundToInt())
        if (hi < lo) return timeSec
        var best = -1
        var bestRise = minRiseDb.toFloat()
        for (b in lo..hi) {
            val rise = logPower[b] - logPower[b - riseBlocks]
            if (rise > bestRise) { bestRise = rise; best = b }
        }
        if (best < 0) return timeSec
        return (best - 1) * blockSeconds + 0.5 * blockSeconds
    }

    /** Aligns every time, then enforces a strictly increasing sequence (falls back to the input where it would not be). */
    fun alignAll(times: DoubleArray): DoubleArray {
        val out = DoubleArray(times.size)
        for (i in times.indices) {
            var a = align(times[i])
            if (i > 0 && a <= out[i - 1]) a = max(times[i], out[i - 1] + 1e-4)
            out[i] = a
        }
        return out
    }
}
