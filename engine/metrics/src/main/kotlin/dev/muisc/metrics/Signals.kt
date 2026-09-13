package dev.muisc.metrics

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.BiquadCascade
import dev.muisc.dsp.filter.LinkwitzRiley
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Small deterministic signal helpers shared by [ArtifactMetrics] and [GoldenFingerprint].
 *
 * Everything here is pure and allocation-light (one array per call, no hidden state); all of it works on the
 * mono mix of a buffer unless the metric is explicitly per-channel. Block grids are always non-overlapping and
 * anchored at frame 0, so two runs on the same audio give identical numbers.
 */
internal object Signals {

    const val DB_FLOOR: Double = -120.0

    /** Linear RMS → dBFS with a [DB_FLOOR] floor (0 → [DB_FLOOR], not -inf, so differences stay finite). */
    fun db(rms: Double): Double = if (rms <= 1e-12) DB_FLOOR else max(DB_FLOOR, 20.0 * log10(rms))

    /** Ratio → dB; 0 → -inf (used where the caller wants a real -inf, e.g. residual energies). */
    fun ratioDb(ratio: Double): Double = if (ratio <= 0.0) Double.NEGATIVE_INFINITY else 10.0 * log10(ratio)

    /** Mono mix (never aliases the buffer's own arrays). */
    fun mono(buffer: AudioBuffer): FloatArray =
        if (buffer.channelCount == 1) buffer[0].copyOf() else buffer.mono()

    /** Number of whole [block]-frame blocks in [frames]. */
    fun blockCount(frames: Int, block: Int): Int = if (block <= 0) 0 else frames / block

    /** Frames of a duration in milliseconds, at least 1. */
    fun msFrames(ms: Double, sampleRate: Int): Int = max(1, Math.round(ms / 1000.0 * sampleRate).toInt())

    /** Prefix sums of `x[i]^2` (length `x.size + 1`) — the basis of every windowed RMS below. */
    fun energyPrefix(x: FloatArray): DoubleArray {
        val p = DoubleArray(x.size + 1)
        for (i in x.indices) p[i + 1] = p[i] + x[i].toDouble() * x[i]
        return p
    }

    /** RMS of `x[from, to)` from a [energyPrefix] table (0 for an empty range). */
    fun rmsOf(prefix: DoubleArray, from: Int, to: Int): Double {
        val a = from.coerceIn(0, prefix.size - 1)
        val b = to.coerceIn(a, prefix.size - 1)
        if (b <= a) return 0.0
        val e = prefix[b] - prefix[a]
        return sqrt(max(0.0, e) / (b - a))
    }

    /** RMS per non-overlapping block of [block] frames (partial tail dropped). */
    fun blockRms(x: FloatArray, block: Int): DoubleArray {
        val n = blockCount(x.size, block)
        val prefix = energyPrefix(x)
        return DoubleArray(n) { rmsOf(prefix, it * block, (it + 1) * block) }
    }

    /** [blockRms] in dBFS. */
    fun blockRmsDb(x: FloatArray, block: Int): DoubleArray {
        val r = blockRms(x, block)
        return DoubleArray(r.size) { db(r[it]) }
    }

    /** Linkwitz-Riley 4th-order high-pass of a mono signal (zero allocation beyond the output). */
    fun highPass(x: FloatArray, sampleRate: Int, cutoffHz: Double): FloatArray {
        val fc = min(cutoffHz, sampleRate * 0.45)
        val cascade = BiquadCascade(1, 2)
        cascade.setAll(LinkwitzRiley.lr4HighPassSection(fc, sampleRate.toDouble()), immediate = true)
        val out = FloatArray(x.size)
        cascade.process(x, out, x.size)
        return out
    }

    /** First difference `y[i] = x[i] - x[i-1]` (`y[0] = 0`). */
    fun firstDifference(x: FloatArray): FloatArray {
        val out = FloatArray(x.size)
        for (i in 1 until x.size) out[i] = x[i] - x[i - 1]
        return out
    }

    /** Zero-lag normalised cross-correlation of two equal-length spans; 1.0 when both spans are silent. */
    fun correlation(x: FloatArray, xOff: Int, y: FloatArray, yOff: Int, n: Int): Double {
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        for (i in 0 until n) {
            val a = x[xOff + i].toDouble(); val b = y[yOff + i].toDouble()
            xy += a * b; xx += a * a; yy += b * b
        }
        if (xx <= 0.0 && yy <= 0.0) return 1.0
        if (xx <= 0.0 || yy <= 0.0) return 0.0
        return xy / sqrt(xx * yy)
    }

    /** Maximum absolute sample difference of two equal-length spans, over every channel. */
    fun maxAbsDiff(x: AudioBuffer, xOff: Int, y: AudioBuffer, yOff: Int, n: Int): Double {
        var m = 0.0
        val channels = min(x.channelCount, y.channelCount)
        for (c in 0 until channels) {
            val a = x[c]; val b = y[c]
            for (i in 0 until n) { val d = abs(a[xOff + i] - b[yOff + i]).toDouble(); if (d > m) m = d }
        }
        return m
    }

    /** Median of [values] (sorts a copy); 0.0 when empty. */
    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val s = values.copyOf(); s.sort()
        return if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2])
    }

    /**
     * Energy-rise onset detector used to decide whether a discontinuity in the render is "explained by a source
     * onset" (DESIGN.md §9). Deliberately cheap and slightly over-eager: every real attack must be found, a few
     * extra positions only make the click/level checks more forgiving inside 5 ms windows.
     *
     * 3 ms non-overlapping blocks; block k is an onset when its RMS is [riseDb] above the loudest of the
     * [lookBack] preceding blocks and above an absolute floor; the reported frame is the block start. Onsets
     * closer than 20 ms are merged.
     */
    fun onsetFrames(
        buffer: AudioBuffer,
        blockMs: Double = 3.0,
        riseDb: Double = 6.0,
        lookBack: Int = 3,
        floorDb: Double = -60.0,
        minSpacingMs: Double = 20.0,
    ): IntArray {
        val sr = buffer.sampleRate
        val x = mono(buffer)
        val block = msFrames(blockMs, sr)
        val dbs = blockRmsDb(x, block)
        if (dbs.size < lookBack + 1) return IntArray(0)
        val spacing = max(1, Math.round(minSpacingMs / blockMs).toInt())
        val out = ArrayList<Int>()
        var lastBlock = -spacing - 1
        for (k in lookBack until dbs.size) {
            val v = dbs[k]
            if (v < floorDb) continue
            var pre = DB_FLOOR
            for (j in k - lookBack until k) if (dbs[j] > pre) pre = dbs[j]
            if (v - pre < riseDb) continue
            if (k - lastBlock < spacing) continue
            out += k * block
            lastBlock = k
        }
        val res = IntArray(out.size)
        for (i in out.indices) res[i] = out[i]
        return res
    }

    /**
     * Onset times in seconds of a rendered segment, with parabolic sub-block interpolation (DESIGN.md §9:
     * "ODF of the render (parabolic sub-frame peaks)").
     *
     * The detection function is the half-wave-rectified difference of a short RMS envelope (1 ms blocks by
     * default, i.e. 1 ms resolution before interpolation); peaks are local maxima of that function above
     * `peakFraction * max(odf)` and at least [minSpacingMs] apart. The time of a peak at block k is
     * `(k + 0.5 + delta) * blockMs`: the rise from block k-1 to block k means the attack lies inside block k,
     * whose centre is half a block after its start, and `delta in [-0.5, 0.5]` is the vertex of the parabola
     * through `(odf[k-1], odf[k], odf[k+1])`.
     */
    fun onsetTimesSec(
        buffer: AudioBuffer,
        blockMs: Double = 1.0,
        peakFraction: Double = 0.1,
        minSpacingMs: Double = 50.0,
        floorDb: Double = -70.0,
    ): DoubleArray {
        val sr = buffer.sampleRate
        val x = mono(buffer)
        val block = msFrames(blockMs, sr)
        val rms = blockRms(x, block)
        if (rms.size < 3) return DoubleArray(0)
        val odf = DoubleArray(rms.size)
        for (k in 1 until rms.size) odf[k] = max(0.0, rms[k] - rms[k - 1])
        var peak = 0.0
        for (v in odf) if (v > peak) peak = v
        if (peak <= 0.0) return DoubleArray(0)
        val threshold = peak * peakFraction
        val floorRms = Math.pow(10.0, floorDb / 20.0)
        val spacing = max(1, Math.round(minSpacingMs / blockMs).toInt())
        val times = ArrayList<Double>()
        var lastK = -spacing - 1
        var k = 1
        while (k < odf.size - 1) {
            val v = odf[k]
            if (v >= threshold && v >= odf[k - 1] && v > odf[k + 1] && rms[k] > floorRms) {
                if (k - lastK >= spacing) {
                    val d = parabolicOffset(odf[k - 1], v, odf[k + 1])
                    times += (k + 0.5 + d) * block / sr.toDouble()
                    lastK = k
                } else if (lastK >= 0 && times.isNotEmpty() && v > odf[lastK]) {
                    // A stronger peak inside the guard window replaces the previous one.
                    val d = parabolicOffset(odf[k - 1], v, odf[k + 1])
                    times[times.size - 1] = (k + 0.5 + d) * block / sr.toDouble()
                    lastK = k
                }
            }
            k++
        }
        val out = DoubleArray(times.size)
        for (i in times.indices) out[i] = times[i]
        return out
    }

    /** Vertex offset in `[-0.5, 0.5]` of the parabola through `(-1, yPrev), (0, y), (1, yNext)`. */
    fun parabolicOffset(yPrev: Double, y: Double, yNext: Double): Double {
        val denom = yPrev - 2.0 * y + yNext
        if (denom == 0.0 || !denom.isFinite()) return 0.0
        val d = 0.5 * (yPrev - yNext) / denom
        return if (!d.isFinite()) 0.0 else d.coerceIn(-0.5, 0.5)
    }
}
