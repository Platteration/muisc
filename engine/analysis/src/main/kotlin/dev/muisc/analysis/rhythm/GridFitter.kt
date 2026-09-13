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
    /** Fraction of tracked beats that lie on the fitted line (robust fit inliers). */
    val inlierFraction: Float = 1f,
) {
    val beatCount: Int get() = beatTimesSec.size
}

/**
 * Turns tracked beats into a RIGID or FLEX grid.
 *
 * A least-squares line `t_k = offset + period * k` is fitted through the tracked beat times, robustly: after a
 * plain fit, beats whose residual exceeds 3 × 1.4826 × MAD (at least [minInlierMs]) are dropped and the line
 * re-fitted, [robustIterations] times. When the RMS residual of the inliers is below [rigidResidualMs] and at
 * least [minInlierFraction] of the beats are inliers, the tempo is constant for practical purposes and a RIGID
 * grid is produced:
 * the fitted line, extended from the first tracked beat to [GridFitter.fit]'s `endSec`. Otherwise the grid is
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
    /** A RIGID grid needs at least this fraction of the tracked beats on the line. */
    val minInlierFraction: Double = 0.7,
) {
    /**
     * @param beatTimesSec tracked beats (strictly increasing, seconds from the ODF origin)
     * @param odf unit-normalised onset function with [hopSec] seconds per frame (same origin)
     * @param endSec end of the analysed region; RIGID grids extend up to (not beyond) this time
     */
    fun fit(beatTimesSec: DoubleArray, odf: FloatArray, hopSec: Double, tempoConfidence: Float, endSec: Double): GridFit {
        val n = beatTimesSec.size
        if (n < 2) return GridFit(GridKind.FLEX, 0.0, beatTimesSec.copyOf(), 0.0, 0.0, if (n == 1) beatTimesSec[0] else 0.0, 0f, 0f)

        // Robust least squares t = offset + period * k: plain fit, then re-fit on the inliers (|residual| within
        // max(3 * 1.4826 * MAD, minInlierMs)) a few times so ambient intro / outro beats without transients do
        // not decide the grid kind or skew the tempo.
        val inlier = BooleanArray(n) { true }
        var period = 0.0
        var offset = 0.0
        var residualMs = 0.0
        var inliers = n
        val res = DoubleArray(n)
        for (iter in 0..robustIterations) {
            var sk = 0.0; var st = 0.0; var skk = 0.0; var skt = 0.0; var cnt = 0
            for (k in 0 until n) {
                if (!inlier[k]) continue
                val t = beatTimesSec[k]; sk += k; st += t; skk += k.toDouble() * k; skt += k * t; cnt++
            }
            val denom = cnt * skk - sk * sk
            if (cnt < 2 || denom <= 0.0) break
            period = (cnt * skt - sk * st) / denom
            offset = (st - period * sk) / cnt
            var ss = 0.0
            for (k in 0 until n) { res[k] = beatTimesSec[k] - (offset + period * k); if (inlier[k]) ss += res[k] * res[k] }
            residualMs = sqrt(ss / cnt) * 1000.0
            inliers = cnt
            if (iter == robustIterations) break
            val absRes = DoubleArray(n) { abs(res[it]) }
            absRes.sort()
            val mad = absRes[n / 2]
            val cut = max(3.0 * 1.4826 * mad, minInlierMs / 1000.0)
            var changed = false
            for (k in 0 until n) { val v = abs(res[k]) <= cut; if (v != inlier[k]) changed = true; inlier[k] = v }
            if (!changed) break
        }
        val inlierFraction = inliers.toDouble() / n

        val rigid = residualMs < rigidResidualMs && inlierFraction >= minInlierFraction && period > 0
        val times: DoubleArray
        val bpm: Double
        if (rigid) {
            // The line may extrapolate the first tracked beat slightly before the region start; never emit negative times.
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
