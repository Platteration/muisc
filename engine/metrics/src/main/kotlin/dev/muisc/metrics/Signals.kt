package dev.muisc.metrics

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.Fft
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
     *
     * The first blocks have no history, and "no history" means silence, not "cannot decide": a buffer that
     * starts above [floorDb] starts with an attack. That matters because the buffers this is run on are cut
     * exactly at musical events - a strategy's B window begins on B's entry downbeat, its A window on A's exit
     * beat - so refusing to look at block 0 hid the single most important onset of every render and let the
     * level check report the incoming track's own downbeat as an artifact.
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
        if (dbs.isEmpty()) return IntArray(0)
        val spacing = max(1, Math.round(minSpacingMs / blockMs).toInt())
        val out = ArrayList<Int>()
        var lastBlock = -spacing - 1
        for (k in dbs.indices) {
            val v = dbs[k]
            if (v < floorDb) continue
            var pre = DB_FLOOR
            for (j in max(0, k - lookBack) until k) if (dbs[j] > pre) pre = dbs[j]
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
     * Attack function of a buffer for beat alignment (DESIGN.md §9, "ODF of the render (parabolic sub-frame
     * peaks)"): `odf[k]` is how far the mono mix's **analytic envelope** ([analyticEnvelope], averaged over 1 ms
     * blocks) rises in block k above its maximum over the [RISE_LOOKBACK_MS] before it. Create with [attacks].
     *
     * Both halves of that definition replace what the metric used before, a half-wave-rectified difference of a
     * 1 ms block RMS, which measured the waveform rather than its attacks on bass-heavy material:
     *  - a 1 ms RMS of a 45-155 Hz kick or bass note rises and falls with every half-cycle, and the analytic
     *    envelope of a sinusoid does not;
     *  - kick, bass and pads beat against each other (within one deck as well as across two), so even the analytic
     *    envelope dips and recovers within a beat - by 6 dB and more in the fixtures' own sources - and a recovery
     *    is a rise. A recovery only regains a level the envelope already had; an attack exceeds it.
     */
    class Attacks(val odf: DoubleArray, val blockFrames: Int, val sampleRate: Int) {
        /** The strongest attack of the whole buffer; [strongestNear] reports only attacks above a fraction of it. */
        val peak: Double = odf.maxOrNull() ?: 0.0

        /** Times in seconds of every local maximum of [odf] within `t ± windowSec` that reaches `peakFraction * peak`. */
        fun allNear(t: Double, windowSec: Double, peakFraction: Double = ATTACK_PEAK_FRACTION): DoubleArray {
            val found = peaksNear(t, windowSec, peakFraction)
            return DoubleArray(found.size) { found[it].time }
        }

        /** One local maximum of [odf]: its time in seconds (sub-block interpolated) and its height. */
        class Peak(val time: Double, val strength: Double)

        /** [allNear] with each attack's strength (its [odf] value), in time order. */
        fun peaksNear(t: Double, windowSec: Double, peakFraction: Double = ATTACK_PEAK_FRACTION): List<Peak> {
            if (peak <= 0.0) return emptyList()
            val blockSec = blockFrames / sampleRate.toDouble()
            val lo = max(1, Math.floor((t - windowSec) / blockSec).toInt() - 1)
            val hi = min(odf.size - 2, Math.ceil((t + windowSec) / blockSec).toInt() + 1)
            val out = ArrayList<Peak>()
            for (k in lo..hi) {
                val v = odf[k]
                if (v < peakFraction * peak || v < odf[k - 1] || v <= odf[k + 1]) continue
                val time = (k + 0.5 + parabolicOffset(odf[k - 1], v, odf[k + 1])) * blockSec
                if (abs(time - t) <= windowSec) out += Peak(time, v)
            }
            return out
        }

        /**
         * Time in seconds of the strongest local maximum of [odf] whose time lies within `t ± windowSec`, with
         * parabolic sub-block interpolation (a peak at block k is at `(k + 0.5 + delta)` blocks: the rise into
         * block k puts the attack inside it). NaN when there is none, or when it is weaker than
         * `peakFraction * peak`: a stretch without a real attack has no time to report.
         */
        fun strongestNear(t: Double, windowSec: Double, peakFraction: Double = ATTACK_PEAK_FRACTION): Double {
            if (peak <= 0.0) return Double.NaN
            val blockSec = blockFrames / sampleRate.toDouble()
            val lo = max(1, Math.floor((t - windowSec) / blockSec).toInt() - 1)
            val hi = min(odf.size - 2, Math.ceil((t + windowSec) / blockSec).toInt() + 1)
            var bestTime = Double.NaN
            var bestValue = 0.0
            for (k in lo..hi) {
                val v = odf[k]
                if (v <= bestValue || v < odf[k - 1] || v <= odf[k + 1]) continue
                val time = (k + 0.5 + parabolicOffset(odf[k - 1], v, odf[k + 1])) * blockSec
                if (abs(time - t) > windowSec) continue
                bestTime = time; bestValue = v
            }
            return if (bestValue >= peakFraction * peak) bestTime else Double.NaN
        }
    }

    /**
     * [Attacks] of [buffer]'s mono mix on 1 ms blocks. With [highPassHz] > 0 the mix is first high-passed ([highPass],
     * LR4) and the attacks are those of what lies above that frequency; [lookbackMs] is how far back the level an
     * attack must exceed is taken from.
     */
    fun attacks(buffer: AudioBuffer, highPassHz: Double = 0.0, lookbackMs: Double = RISE_LOOKBACK_MS): Attacks {
        val sr = buffer.sampleRate
        val block = msFrames(1.0, sr)
        val x = mono(buffer)
        val envelope = analyticEnvelope(if (highPassHz > 0.0) highPass(x, sr, highPassHz) else x)
        val n = blockCount(envelope.size, block)
        val env = DoubleArray(n) { b -> var acc = 0.0; for (i in b * block until (b + 1) * block) acc += envelope[i]; acc / block }
        val lookBack = max(1, Math.round(lookbackMs).toInt())
        val odf = DoubleArray(n)
        for (k in 1 until n) {
            var before = 0.0
            for (j in max(0, k - lookBack) until k) if (env[j] > before) before = env[j]
            odf[k] = max(0.0, env[k] - before)
        }
        return Attacks(odf, block, sr)
    }

    /** How far back (1 ms blocks) [attacks] looks for the level an attack must exceed. */
    const val RISE_LOOKBACK_MS = 20.0
    /** An attack weaker than this fraction of the buffer's strongest does not count (the old onset detector's threshold). */
    const val ATTACK_PEAK_FRACTION = 0.1

    /**
     * Magnitude of the analytic signal `x + i·H(x)` (H = Hilbert transform): the envelope of [x] without the
     * waveform's own ripple. Computed with the `dsp` [Fft] in overlapping [HILBERT_FFT]-point segments; each
     * segment contributes only its middle, [HILBERT_MARGIN] frames away from its edges. The truncated Hilbert kernel
     * (it decays as `1 / (pi n)`) costs most at low frequencies: on a 55 Hz sine of amplitude 0.5 the envelope stays
     * within 0.0014 of 0.5 more than a second from the buffer's ends (`AttacksTest`). Frames outside [x] count as
     * silence, so the envelope sags near the ends of a buffer that does not start or end in silence.
     */
    fun analyticEnvelope(x: FloatArray): FloatArray {
        val n = x.size
        val out = FloatArray(n)
        if (n == 0) return out
        val size = HILBERT_FFT
        val margin = HILBERT_MARGIN
        val hop = size - 2 * margin
        val fft = Fft(size)
        val re = FloatArray(size); val im = FloatArray(size)
        var start = 0
        while (start < n) {
            val from = start - margin
            for (i in 0 until size) { val j = from + i; re[i] = if (j in 0 until n) x[j] else 0f; im[i] = 0f }
            fft.forward(re, im)
            // Analytic spectrum: keep DC and Nyquist, double the positive frequencies, drop the negative ones.
            for (k in 1 until size / 2) { re[k] *= 2f; im[k] *= 2f }
            for (k in size / 2 + 1 until size) { re[k] = 0f; im[k] = 0f }
            fft.inverse(re, im)
            val m = min(hop, n - start)
            for (i in 0 until m) { val a = re[margin + i]; val b = im[margin + i]; out[start + i] = sqrt(a * a + b * b) }
            start += hop
        }
        return out
    }

    /** FFT size of [analyticEnvelope] (the largest the `dsp` [Fft] supports). */
    const val HILBERT_FFT = 65536
    /** Frames discarded at each end of an [analyticEnvelope] segment (372 ms at 44.1 kHz). */
    const val HILBERT_MARGIN = 16384

    /** Vertex offset in `[-0.5, 0.5]` of the parabola through `(-1, yPrev), (0, y), (1, yNext)`. */
    fun parabolicOffset(yPrev: Double, y: Double, yNext: Double): Double {
        val denom = yPrev - 2.0 * y + yNext
        if (denom == 0.0 || !denom.isFinite()) return 0.0
        val d = 0.5 * (yPrev - yNext) / denom
        return if (!d.isFinite()) 0.0 else d.coerceIn(-0.5, 0.5)
    }
}
