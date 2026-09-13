package dev.muisc.analysis.rhythm

import dev.muisc.analysis.model.TempoCandidate
import dev.muisc.analysis.model.TempoEstimate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Per-window ("local") tempo information produced by [TempoEstimator]: a coarse tempogram plus the refined local
 * period of every window, restricted to the neighbourhood of the global tempo so slow drift is followed without
 * octave jumps.
 *
 * `windowScores[w][lag - 1]` is the combined, prior-weighted autocorrelation of window `w` at `lag` ODF frames
 * (`lag` in `1..maxLag`); [globalScore] is the same for the whole track after harmonic summation (see
 * [TempoEstimator]).
 */
class Tempogram(
    val hopSeconds: Double,
    val windowCentreFrames: IntArray,
    /** Refined local period in ODF frames per window (0 when the window carries no tempo information). */
    val localLagFrames: DoubleArray,
    val localBpm: DoubleArray,
    /** 0..1 strength of the local period (normalised autocorrelation value at the local peak). */
    val localConfidence: FloatArray,
    val minLag: Int,
    val maxLag: Int,
    val windowScores: Array<FloatArray>,
    val globalScore: FloatArray,
) {
    val windows: Int get() = windowCentreFrames.size

    /**
     * Local beat period (ODF frames) for every ODF frame `0 until frames`: confidence-weighted average of the
     * window periods within ±[smoothSec] (triangular weights), falling back to [fallbackLagFrames] where no
     * window has confidence. Feeds the [BeatTracker].
     */
    fun periodCurve(frames: Int, fallbackLagFrames: Double, smoothSec: Double = 6.0): DoubleArray {
        val out = DoubleArray(frames)
        val halfFrames = max(1.0, smoothSec / 2 / hopSeconds)
        for (t in 0 until frames) {
            var num = 0.0
            var den = 0.0
            for (w in 0 until windows) {
                val d = abs(t - windowCentreFrames[w]) / halfFrames
                if (d >= 1.0 || localLagFrames[w] <= 0.0) continue
                val k = (1.0 - d) * localConfidence[w]
                num += k * localLagFrames[w]
                den += k
            }
            out[t] = if (den > 1e-3) num / den else fallbackLagFrames
        }
        return out
    }
}

/** Result of [TempoEstimator.estimate]. */
class TempoResult(
    val estimate: TempoEstimate,
    /** Refined global beat period in ODF frames (0 when no tempo could be estimated). */
    val periodFrames: Double,
    val tempogram: Tempogram,
) {
    val bpm: Double get() = estimate.bpm
    val confidence: Float get() = estimate.confidence
}

/**
 * Autocorrelation tempo estimation with a log-Gaussian tempo prior (Ellis, "Beat tracking by dynamic
 * programming", J. New Music Research 2007) and a harmonic (comb) summation for octave resolution.
 *
 * For every [windowSec] window of the onset function (hopped [windowHopSec]) the mean-removed, unbiased,
 * unit-normalised autocorrelation of the broadband ODF is computed for lags `1..maxLag` and combined with the
 * autocorrelation of the low-band (kick / bass) ODF, weighted by [lowBandWeight] times the window's low-band
 * presence ([OnsetFeatures.lowBandWeightFor]) — the low band carries no hi-hat subdivisions, so it anchors the
 * beat level.
 * The windows are averaged (weighted by their raw flux, so eventful windows dominate) and multiplied by the prior
 * `exp(-0.5 * (log2(bpm / priorBpm) / priorSigmaOctaves)^2)`.
 *
 * Octave decision: the score of a candidate lag `τ` is the harmonic sum `W(τ) + W(2τ)/2 + W(3τ)/3 + W(4τ)/4`
 * (each harmonic taken as the maximum over ±1 frame). For a pulse train the score is highest at the fundamental
 * period, because the half-period lag only correlates the weaker subdivisions (hi-hats) and the double period
 * loses the higher harmonics — the low-band ODF, which has no subdivision energy, makes this robust for music
 * with kick / bass. Finally a best lag outside [preferredMinBpm]..[preferredMaxBpm] is replaced by its
 * half / double when that alternative is inside the range and scores at least [octaveSwitchRatio] of the best.
 * The chosen peak is refined by parabolic interpolation to sub-BPM precision.
 *
 * Confidence is the prominence of the winning peak: `1 - H2 / H1`, where `H2` is the best local maximum that is
 * not the winner or one of its simple ratios (×2, ÷2, ×3, ÷3, ×3/2, ×2/3 within ±5 %), clamped to 0..1.
 * Alternates are bpm/2, bpm·2 and the 2nd / 3rd distinct peaks with `score = H / H1`.
 */
class TempoEstimator(
    val minBpm: Double = 40.0,
    val maxBpm: Double = 240.0,
    val windowSec: Double = 8.0,
    val windowHopSec: Double = 2.0,
    val priorBpm: Double = 120.0,
    val priorSigmaOctaves: Double = 0.9,
    val preferredMinBpm: Double = 70.0,
    val preferredMaxBpm: Double = 180.0,
    val octaveSwitchRatio: Double = 0.6,
    /** Weight of the low-band ACF relative to the broadband ACF when the low band is fully present ([OnsetFeatures.lowBandWeightFor]). */
    val lowBandWeight: Double = 2.0,
    /** Local periods are searched within `[1 - localSearch, 1 + localSearch]` × the global period. */
    val localSearch: Double = 0.15,
    /** Minimum ODF length (seconds) for any estimate at all. */
    val minDurationSec: Double = 3.0,
) {
    init {
        require(minBpm > 0 && maxBpm > minBpm)
        require(windowSec > 0 && windowHopSec > 0)
    }

    fun estimate(features: OnsetFeatures): TempoResult {
        val hopSec = features.hopSeconds
        val odf = features.odf
        val n = odf.size
        val minLag = max(1, (60.0 / maxBpm / hopSec).roundToInt())
        val maxLag = max(minLag + 1, (60.0 / minBpm / hopSec).roundToInt())
        val empty = { Tempogram(hopSec, IntArray(0), DoubleArray(0), DoubleArray(0), FloatArray(0), minLag, maxLag, emptyArray(), FloatArray(maxLag)) }
        if (n * hopSec < minDurationSec || n <= maxLag + 2) {
            return TempoResult(TempoEstimate(0.0, 0f), 0.0, empty())
        }

        // ---- windows --------------------------------------------------------------------------------------
        val win = min(n, max(maxLag + 2, (windowSec / hopSec).roundToInt()))
        val hopW = max(1, (windowHopSec / hopSec).roundToInt())
        val starts = ArrayList<Int>()
        var s = 0
        while (s + win <= n) { starts.add(s); s += hopW }
        if (starts.isEmpty()) starts.add(0)
        val nw = starts.size
        val acf = Array(nw) { FloatArray(maxLag) }        // combined ACF per window, index lag-1
        val lowWeight = DoubleArray(nw)
        val weight = DoubleArray(nw)
        val xb = FloatArray(win)
        val xl = FloatArray(win)
        val rb = DoubleArray(maxLag + 1)
        val rl = DoubleArray(maxLag + 1)
        for (w in 0 until nw) {
            val st = starts[w]
            var rawB = 0.0; var rawL = 0.0
            for (i in 0 until win) { rawB += features.rawOdf[st + i]; rawL += features.rawLowOdf[st + i] }
            weight[w] = rawB
            val g = if (rawB > 0) OnsetFeatures.lowBandWeightFor(rawL / rawB) * lowBandWeight else 0.0
            lowWeight[w] = g
            val okB = autocorrelation(odf, st, win, maxLag, xb, rb)
            val okL = g > 0 && autocorrelation(features.lowOdf, st, win, maxLag, xl, rl)
            val a = acf[w]
            if (okB) for (lag in 1..maxLag) a[lag - 1] = rb[lag].toFloat()
            if (okL) for (lag in 1..maxLag) a[lag - 1] += (g * rl[lag]).toFloat()
            if (!okB) weight[w] = 0.0
        }

        // ---- global weighted ACF and prior -------------------------------------------------------------------
        val prior = FloatArray(maxLag) { i -> priorWeight(60.0 / ((i + 1) * hopSec)) }
        val wp = FloatArray(maxLag)
        var totalW = 0.0
        for (w in 0 until nw) totalW += weight[w]
        if (totalW <= 0.0) {
            return TempoResult(TempoEstimate(0.0, 0f), 0.0, empty())
        }
        for (i in 0 until maxLag) {
            var acc = 0.0
            for (w in 0 until nw) acc += weight[w] * acf[w][i]
            val a = (acc / totalW).toFloat()
            wp[i] = if (a > 0f) a * prior[i] else 0f
        }
        val h = harmonicSum(wp, minLag, maxLag)

        // ---- peak picking --------------------------------------------------------------------------------------
        val peaks = localMaxima(h, minLag, maxLag) // lags, sorted by score desc
        if (peaks.isEmpty()) {
            return TempoResult(TempoEstimate(0.0, 0f), 0.0, empty())
        }
        var best = peaks[0]
        val bestBpm0 = 60.0 / (best * hopSec)
        if (bestBpm0 < preferredMinBpm || bestBpm0 > preferredMaxBpm) {
            val altLag = if (bestBpm0 < preferredMinBpm) best / 2.0 else best * 2.0
            val altBpm = 60.0 / (altLag * hopSec)
            if (altBpm in preferredMinBpm..preferredMaxBpm) {
                val cand = peaks.firstOrNull { abs(it - altLag) <= max(1.0, 0.04 * altLag) }
                if (cand != null && h[cand - 1] >= octaveSwitchRatio * h[best - 1]) best = cand
            }
        }
        val refined = refinePeak(wp, best, 1, maxLag)
        val bpm = 60.0 / (refined * hopSec)
        val h1 = h[best - 1]

        // ---- confidence and alternates ---------------------------------------------------------------------------
        val ratios = doubleArrayOf(2.0, 0.5, 3.0, 1.0 / 3.0, 1.5, 2.0 / 3.0)
        fun isRelated(lag: Int): Boolean {
            if (abs(lag - best) <= max(1.0, 0.05 * best)) return true
            for (r in ratios) if (abs(lag - best * r) <= max(1.0, 0.05 * best * r)) return true
            return false
        }
        val h2 = peaks.firstOrNull { !isRelated(it) }?.let { h[it - 1] } ?: 0f
        val confidence = if (h1 > 0f) (1f - h2 / h1).coerceIn(0f, 1f) else 0f
        val alternates = ArrayList<TempoCandidate>()
        fun scoreAtLag(lag: Double): Float {
            val i = lag.roundToInt()
            if (i < 1 || i > maxLag) return 0f
            var m = 0f
            for (j in max(1, i - 1)..min(maxLag, i + 1)) if (h[j - 1] > m) m = h[j - 1]
            return if (h1 > 0f) (m / h1).coerceIn(0f, 1f) else 0f
        }
        alternates.add(TempoCandidate(bpm / 2, scoreAtLag(refined * 2)))
        alternates.add(TempoCandidate(bpm * 2, scoreAtLag(refined / 2)))
        var added = 0
        for (p in peaks) {
            if (added >= 2) break
            if (abs(p - best) <= max(1.0, 0.05 * best)) continue
            if (abs(p - 2.0 * best) <= max(1.0, 0.1 * best) || abs(p - best / 2.0) <= max(1.0, 0.025 * best)) continue
            val pb = 60.0 / (refinePeak(wp, p, 1, maxLag) * hopSec)
            alternates.add(TempoCandidate(pb, if (h1 > 0f) (h[p - 1] / h1).coerceIn(0f, 1f) else 0f))
            added++
        }

        // ---- local tempo per window ----------------------------------------------------------------------------
        val centres = IntArray(nw) { starts[it] + win / 2 }
        val localLag = DoubleArray(nw)
        val localBpm = DoubleArray(nw)
        val localConf = FloatArray(nw)
        val scores = Array(nw) { w -> FloatArray(maxLag) { i -> val a = acf[w][i]; if (a > 0f) a * prior[i] else 0f } }
        val lo = max(1, (refined * (1 - localSearch)).roundToInt())
        val hi = min(maxLag, (refined * (1 + localSearch)).roundToInt())
        for (w in 0 until nw) {
            if (weight[w] <= 0.0) continue
            val a = acf[w]
            var bi = -1; var bv = 0f
            for (lag in lo..hi) {
                val v = a[lag - 1]
                val isMax = (lag == lo || v >= a[lag - 2]) && (lag == hi || v > a[lag])
                if (isMax && v > bv) { bv = v; bi = lag }
            }
            if (bi < 0) continue
            localLag[w] = refinePeak(a, bi, 1, maxLag)
            localBpm[w] = 60.0 / (localLag[w] * hopSec)
            localConf[w] = (bv / (1f + lowWeight[w].toFloat())).coerceIn(0f, 1f)
        }
        val tg = Tempogram(hopSec, centres, localLag, localBpm, localConf, minLag, maxLag, scores, h)
        return TempoResult(TempoEstimate(bpm, confidence, alternates), refined, tg)
    }

    /** Prior weight of a tempo: log-Gaussian around [priorBpm] with [priorSigmaOctaves]. */
    fun priorWeight(bpm: Double): Float {
        val z = log2(bpm / priorBpm) / priorSigmaOctaves
        return exp(-0.5 * z * z).toFloat()
    }

    companion object {
        /**
         * Mean-removed, unbiased, unit-normalised autocorrelation of `x[start until start+len]` for lags
         * `0..maxLag` into [out] (`out[0] == 1`). [tmp] must hold `len` floats. Returns false (and zeros) when the
         * window has no variance.
         */
        fun autocorrelation(x: FloatArray, start: Int, len: Int, maxLag: Int, tmp: FloatArray, out: DoubleArray): Boolean {
            var mean = 0.0
            for (i in 0 until len) mean += x[start + i]
            mean /= len
            val m = mean.toFloat()
            for (i in 0 until len) tmp[i] = x[start + i] - m
            var r0 = 0.0
            for (i in 0 until len) r0 += tmp[i].toDouble() * tmp[i]
            if (r0 <= 1e-12) { java.util.Arrays.fill(out, 0.0); return false }
            out[0] = 1.0
            for (lag in 1..maxLag) {
                val cnt = len - lag
                if (cnt <= 0) { out[lag] = 0.0; continue }
                var acc = 0.0
                for (i in 0 until cnt) acc += tmp[i].toDouble() * tmp[i + lag]
                out[lag] = (acc / cnt) / (r0 / len)
            }
            return true
        }

        /** `H(τ) = W(τ) + Σ_{k=2..4} max(W(kτ-1..kτ+1)) / k` for τ in `minLag..maxLag` (index lag-1; zero elsewhere). */
        fun harmonicSum(w: FloatArray, minLag: Int, maxLag: Int): FloatArray {
            val h = FloatArray(w.size)
            for (lag in minLag..maxLag) {
                var acc = w[lag - 1]
                for (k in 2..4) {
                    val c = k * lag
                    if (c > maxLag) break
                    var m = 0f
                    for (j in max(1, c - 1)..min(maxLag, c + 1)) if (w[j - 1] > m) m = w[j - 1]
                    acc += m / k
                }
                h[lag - 1] = acc
            }
            return h
        }

        /** Strictly positive local maxima of [h] (index lag-1) with lag in `minLag..maxLag`, sorted by value descending. */
        fun localMaxima(h: FloatArray, minLag: Int, maxLag: Int): List<Int> {
            val out = ArrayList<Int>()
            for (lag in minLag..maxLag) {
                val v = h[lag - 1]
                if (v <= 0f) continue
                val left = if (lag > 1) h[lag - 2] else 0f
                val right = if (lag < h.size) h[lag] else 0f
                if (v >= left && v > right) out.add(lag)
            }
            out.sortByDescending { h[it - 1] }
            return out
        }

        /** Parabolic interpolation of the peak of [y] (index lag-1) around integer [lag]; result in `lag ± 0.5`. */
        fun refinePeak(y: FloatArray, lag: Int, minLag: Int, maxLag: Int): Double {
            if (lag - 1 < minLag || lag + 1 > maxLag) return lag.toDouble()
            val y0 = y[lag - 2].toDouble(); val y1 = y[lag - 1].toDouble(); val y2 = y[lag].toDouble()
            val denom = y0 - 2 * y1 + y2
            if (denom >= 0.0) return lag.toDouble()
            val d = (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5)
            return lag + d
        }
    }
}
