package dev.muisc.analysis.rhythm

import dev.muisc.analysis.model.GridKind
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Result of [GridFitter.fit]: the final beat times in seconds (same time base as the input) and grid metadata. */
class GridFit(
    val kind: GridKind,
    /** Beats per minute: from the least-squares line for RIGID, from the median inter-beat interval for FLEX. */
    val bpm: Double,
    val beatTimesSec: DoubleArray,
    /** RMS residual of the inlier beats against the robust least-squares line, in milliseconds. */
    val residualMs: Double,
    /** Least-squares period / first-beat time in seconds (valid for both kinds). */
    val periodSec: Double,
    val offsetSec: Double,
    /** 0..1 support of the beats by the onset function (see [GridFitter]). */
    val beatSupport: Float,
    /** 0..1 combined grid confidence (geometric mean of beat support and tempo confidence). */
    val confidence: Float,
    /**
     * Share of the tracked beats' weight (onset support) that lies on the fitted line (robust fit inliers), among
     * the beats that are not in an off-line run the line explains as well as they do (see [GridFitter]).
     */
    val inlierFraction: Float = 1f,
) {
    val beatCount: Int get() = beatTimesSec.size
}

/**
 * Turns tracked beats into a RIGID or FLEX grid.
 *
 * A weighted least-squares line `t_k = offset + period * k` is fitted through the tracked beat times, robustly.
 * Each beat weighs its onset support (the ODF maximum within ±[supportRadiusFrames] frames, plus [weightFloor]),
 * so beats with nothing under them — the beats the tracker placed through a drumless intro or a breakdown — barely
 * count. The first fit uses only the seed: the heaviest run of at least [seedMinBeats] consecutive beats with
 * support ≥ [seedMinSupport] (all beats when there is no such run), so a long intro whose tracked beats wandered
 * cannot tilt the line the confident section defines. Then every beat whose residual exceeds 3 × 1.4826 × the
 * weighted MAD of all residuals (at least [minInlierMs]) is dropped, the others kept, and the line re-fitted,
 * [robustIterations] times. When the weighted RMS residual of the inliers is below [rigidResidualMs] and at least
 * [minInlierFraction] of the weight is on the line, the tempo is constant for practical purposes and a RIGID grid
 * is produced. For that fraction, a run of consecutive off-line beats does not count when the line's own beats
 * over the same span sit on at least as much onset strength as the tracked beats (the intro's few onsets, its
 * chord changes, fall on the extrapolated grid while the tracked beats wandered or slipped phase); a run whose
 * tracked beats sit on stronger onsets than the line's positions (a real tempo change) does. The RIGID grid is the
 * fitted line from its beat nearest the first tracked beat to [GridFitter.fit]'s `endSec`. Otherwise the grid is
 * FLEX: the tracked beats with a 3-point median smoothing, where each interior beat is replaced by the median of
 * itself and the two positions predicted from its neighbours with the local median inter-beat interval (this
 * removes single-beat outliers without smoothing genuine tempo drift).
 *
 * Confidence: `beatSupport = clamp((meanOdfAtBeats / meanOdf - 1) / (fullSupportRatio - 1), 0, 1)` — beats that
 * sit on onsets [fullSupportRatio] times stronger than the average frame get full support, beats no better than
 * random frames get none — and the grid confidence is `sqrt(beatSupport * tempoConfidence)`.
 */
class GridFitter(
    val rigidResidualMs: Double = 8.0,
    val fullSupportRatio: Double = 4.0,
    val medianWindowBeats: Int = 9,
    /** Re-fit passes of the robust least squares. */
    val robustIterations: Int = 3,
    /** Inlier cut is never tighter than this (ms), so frame-quantised beats are not rejected. */
    val minInlierMs: Double = 6.0,
    /** A RIGID grid needs at least this fraction of the tracked beats' weight on the line. */
    val minInlierFraction: Double = 0.7,
    /** Weight every tracked beat gets on top of its onset support, so a track without onsets still fits. */
    val weightFloor: Double = 0.05,
    /** Onset support of a beat is the ODF maximum within this many frames of it. */
    val supportRadiusFrames: Int = 2,
    /** The seed of the robust fit is a run of beats whose onset support is at least this (unit-scale ODF). */
    val seedMinSupport: Double = 0.25,
    /** ... and at least this long (beats); shorter runs leave the first fit to all beats. */
    val seedMinBeats: Int = 8,
) {
    /**
     * @param beatTimesSec tracked beats (strictly increasing, seconds from the ODF origin)
     * @param odf unit-normalised onset function with [hopSec] seconds per frame (same origin)
     * @param endSec end of the analysed region; RIGID grids extend up to (not beyond) this time
     */
    fun fit(beatTimesSec: DoubleArray, odf: FloatArray, hopSec: Double, tempoConfidence: Float, endSec: Double): GridFit {
        val n = beatTimesSec.size
        if (n < 2) return GridFit(GridKind.FLEX, 0.0, beatTimesSec.copyOf(), 0.0, 0.0, if (n == 1) beatTimesSec[0] else 0.0, 0f, 0f)

        // Robust weighted least squares t = offset + period * k: each beat weighs its onset support (the ODF at the
        // beat, plus a small floor), so beats with nothing under them — a drumless intro, a breakdown — cannot
        // decide the line. The first fit uses only the seed (the heaviest run of well-supported beats, when there is
        // one), so a long intro whose tracked beats wander cannot tilt the line the confident section defines; then
        // re-fit on the inliers (|residual| within max(3 * 1.4826 * weighted MAD, minInlierMs), over all beats) a few
        // times so ambient intro / outro beats without transients do not decide the grid kind or skew the tempo.
        val weight = DoubleArray(n) { weightFloor + supportAt(odf, beatTimesSec[it], hopSec) }
        var totalWeight = 0.0
        for (v in weight) totalWeight += v
        val seed = seedRun(weight)
        val inlier = BooleanArray(n) { seed == null || it in seed }
        var period = 0.0
        var offset = 0.0
        var residualMs = 0.0
        var inlierWeight = totalWeight
        val res = DoubleArray(n)
        for (iter in 0..robustIterations) {
            var sw = 0.0; var sk = 0.0; var st = 0.0; var skk = 0.0; var skt = 0.0; var cnt = 0
            for (k in 0 until n) {
                if (!inlier[k]) continue
                val w = weight[k]; val t = beatTimesSec[k]
                sw += w; sk += w * k; st += w * t; skk += w * k.toDouble() * k; skt += w * k * t; cnt++
            }
            val denom = sw * skk - sk * sk
            if (cnt < 2 || denom <= 0.0) break
            period = (sw * skt - sk * st) / denom
            offset = (st - period * sk) / sw
            var ss = 0.0
            for (k in 0 until n) { res[k] = beatTimesSec[k] - (offset + period * k); if (inlier[k]) ss += weight[k] * res[k] * res[k] }
            residualMs = sqrt(ss / sw) * 1000.0
            inlierWeight = sw
            if (iter == robustIterations) break
            val mad = weightedMedianAbs(res, weight)
            val cut = max(3.0 * 1.4826 * mad, minInlierMs / 1000.0)
            var changed = false
            for (k in 0 until n) { val v = abs(res[k]) <= cut; if (v != inlier[k]) changed = true; inlier[k] = v }
            if (!changed) break
        }
        // Off-line runs the line explains as well as the tracked beats do (the grid's own positions there sit on at
        // least as much onset strength — a drumless intro whose tracked beats wandered or slipped phase while its few
        // onsets fall on the extrapolated grid) do not count against the line; runs whose tracked beats sit on
        // stronger onsets than the grid's positions do (a real tempo change).
        val explained = if (period > 0.0) explainedOutlierWeight(beatTimesSec, weight, inlier, odf, hopSec, period, offset) else 0.0
        val judged = totalWeight - explained
        val inlierFraction = if (judged > 0.0) inlierWeight / judged else 0.0

        val rigid = residualMs < rigidResidualMs && inlierFraction >= minInlierFraction && period > 0
        val times: DoubleArray
        val bpm: Double
        if (rigid) {
            // Start at the line's beat nearest the first tracked beat (the tracked beats before the confident part
            // may have slipped a beat, so index 0 of the line need not be the first tracked beat); the line may
            // extrapolate slightly before the region start; never emit negative times.
            offset += Math.round((beatTimesSec[0] - offset) / period) * period
            while (offset < 0.0) offset += period
            val count = if (endSec <= offset) 1 else (floor((endSec - offset) / period).toInt() + 1)
            times = DoubleArray(count) { offset + it * period }
            bpm = 60.0 / period
        } else {
            times = medianSmooth(beatTimesSec)
            val ibis = DoubleArray(n - 1) { times[it + 1] - times[it] }
            ibis.sort()
            val med = ibis[ibis.size / 2]
            bpm = if (med > 0) 60.0 / med else 0.0
        }

        val support = beatSupport(times, odf, hopSec)
        val conf = sqrt(support.toDouble() * tempoConfidence.coerceIn(0f, 1f)).toFloat()
        return GridFit(if (rigid) GridKind.RIGID else GridKind.FLEX, bpm, times, residualMs, period, offset, support, conf, inlierFraction.toFloat())
    }

    /** `clamp((mean ODF at beats / mean ODF - 1) / (fullSupportRatio - 1), 0, 1)`; ODF at a beat is the max over ±1 frame. */
    fun beatSupport(beatTimesSec: DoubleArray, odf: FloatArray, hopSec: Double): Float {
        if (beatTimesSec.isEmpty() || odf.isEmpty()) return 0f
        var total = 0.0
        for (v in odf) total += v
        val meanAll = total / odf.size
        if (meanAll <= 0.0) return 0f
        var atBeats = 0.0
        var count = 0
        for (t in beatTimesSec) {
            val f = Math.round(t / hopSec).toInt()
            if (f < 0 || f >= odf.size) continue
            var m = odf[f]
            if (f > 0 && odf[f - 1] > m) m = odf[f - 1]
            if (f + 1 < odf.size && odf[f + 1] > m) m = odf[f + 1]
            atBeats += m; count++
        }
        if (count == 0) return 0f
        val ratio = atBeats / count / meanAll
        return ((ratio - 1.0) / (fullSupportRatio - 1.0)).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Total weight of the runs of consecutive outlier beats whose onset support is no better than the line's: for a
     * run of outliers `a..b`, the mean support at the tracked beats is compared with the mean support at the line's
     * beats `offset + j * period` inside `[t_a - period / 2, t_b + period / 2]`.
     */
    private fun explainedOutlierWeight(
        t: DoubleArray, weight: DoubleArray, inlier: BooleanArray, odf: FloatArray, hopSec: Double, period: Double, offset: Double,
    ): Double {
        var explained = 0.0
        var a = 0
        while (a < t.size) {
            if (inlier[a]) { a++; continue }
            var b = a
            while (b + 1 < t.size && !inlier[b + 1]) b++
            var tracked = 0.0
            var runWeight = 0.0
            for (k in a..b) { tracked += weight[k] - weightFloor; runWeight += weight[k] }
            tracked /= (b - a + 1)
            val j0 = kotlin.math.ceil((t[a] - period / 2 - offset) / period).toLong()
            val j1 = floor((t[b] + period / 2 - offset) / period).toLong()
            var line = 0.0
            var count = 0
            for (j in j0..j1) { line += supportAt(odf, offset + j * period, hopSec); count++ }
            if (count > 0 && line / count >= tracked) explained += runWeight
            a = b + 1
        }
        return explained
    }

    /**
     * The heaviest run of at least [seedMinBeats] consecutive beats whose onset support is at least [seedMinSupport]
     * (by total weight), or null when there is none.
     */
    private fun seedRun(weight: DoubleArray): IntRange? {
        var best: IntRange? = null
        var bestWeight = 0.0
        var start = -1
        var acc = 0.0
        for (k in 0..weight.size) {
            val strong = k < weight.size && weight[k] - weightFloor >= seedMinSupport
            if (strong) {
                if (start < 0) { start = k; acc = 0.0 }
                acc += weight[k]
            } else if (start >= 0) {
                if (k - start >= seedMinBeats && acc > bestWeight) { best = start until k; bestWeight = acc }
                start = -1
            }
        }
        return best
    }

    /** ODF at [timeSec]: the maximum over ±[supportRadiusFrames] frames (0 outside the ODF). */
    private fun supportAt(odf: FloatArray, timeSec: Double, hopSec: Double): Double {
        val f = Math.round(timeSec / hopSec).toInt()
        var m = 0f
        for (u in max(0, f - supportRadiusFrames)..min(odf.size - 1, f + supportRadiusFrames)) if (odf[u] > m) m = odf[u]
        return m.toDouble()
    }

    /** Weighted median of `|x|` with weights [w]. */
    private fun weightedMedianAbs(x: DoubleArray, w: DoubleArray): Double {
        val idx = x.indices.sortedBy { abs(x[it]) }
        var total = 0.0
        for (v in w) total += v
        var acc = 0.0
        for (i in idx) { acc += w[i]; if (acc >= total / 2) return abs(x[i]) }
        return if (idx.isEmpty()) 0.0 else abs(x[idx.last()])
    }

    /** 3-point median smoothing of beat positions (see class doc). Keeps the first and last beats. */
    fun medianSmooth(t: DoubleArray): DoubleArray {
        val n = t.size
        if (n < 3) return t.copyOf()
        val out = t.copyOf()
        val half = max(1, medianWindowBeats / 2)
        val buf = DoubleArray(2 * half + 1)
        for (k in 1 until n - 1) {
            val lo = max(1, k - half)
            val hi = min(n - 1, k + half)
            var c = 0
            for (j in lo..hi) buf[c++] = t[j] - t[j - 1]
            java.util.Arrays.sort(buf, 0, c)
            val m = buf[c / 2]
            val a = t[k - 1] + m
            val b = t[k + 1] - m
            out[k] = median3(t[k], a, b)
        }
        for (k in 1 until n) if (out[k] <= out[k - 1]) out[k] = out[k - 1] + 1e-4
        return out
    }

    private fun median3(a: Double, b: Double, c: Double): Double = max(min(a, b), min(max(a, b), c))
}
