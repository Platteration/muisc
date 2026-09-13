package dev.muisc.dsp.stretch

import dev.muisc.dsp.window.Window
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Waveform-Similarity Overlap-Add time stretcher (Verhelst & Roelands, "An overlap-add technique based on
 * waveform similarity (WSOLA) for high quality time-scale modification of speech", ICASSP 1993).
 *
 * Algorithm (see [WsolaStream] for the streaming engine):
 *  - the output is built from analysis segments of [frameMs] (default 30 ms, `N` frames) that are Hann-windowed
 *    and overlap-added at a fixed synthesis hop `N/2` (15 ms) — periodic Hann at 50 % overlap is exactly
 *    constant-overlap-add, so the output needs no window-sum normalisation and a ratio of 1 is bit-exact;
 *  - segment `k` is nominally taken from input position `k * hop / ratio`; the actual position is searched within
 *    ±[toleranceMs] (10 ms) for the offset whose normalised cross-correlation with the *natural continuation* of
 *    the previous segment (previous position + hop) is maximal, so consecutive segments join phase-coherently;
 *  - the search runs first on a signal decimated by [decimation] (box average; auto = ~11 kHz rate) and the best
 *    three coarse lags are refined at full rate, ~10x cheaper than a brute-force full-rate search;
 *  - for stereo (or more channels) a single offset is computed on the mid (channel average) signal and applied
 *    to every channel, so channels stay phase-coherent;
 *  - transient protection: with a list of transient input positions, the segment whose nominal window first
 *    contains a transient is placed so that the transient lands at exactly its nominal output time
 *    (`frame * ratio` for a constant ratio), the following segments that still contain it are pinned to the
 *    natural continuation of the previous one (so the overlapping copies coincide in time — no doubled kicks),
 *    and segments before it are constrained not to reach into it (no early copy). Segments after a transient
 *    are constrained not to reach back into it either.
 *
 * Quality: pure tones keep their frequency (segments join at multiples of the period), steady material stretches
 * cleanly for ratios 0.8..1.25; ratio 1.0 is the identity. Deterministic; no allocation per hop.
 */
class WsolaStretcher(
    val frameMs: Double = 30.0,
    val toleranceMs: Double = 10.0,
    val decimation: Int = 0,
    val maxPushFrames: Int = 8192,
) : TimeStretcher {
    init {
        require(frameMs > 1.0 && toleranceMs >= 0.0) { "frameMs must be > 1 ms and toleranceMs >= 0" }
        require(decimation >= 0) { "decimation must be >= 0 (0 = auto)" }
    }

    /** Analysis frame length in frames for [sampleRate]: `round(frameMs * sr / 1000)` rounded up to even, at least 64. */
    fun frameSizeFor(sampleRate: Int): Int {
        var n = Math.round(frameMs * sampleRate / 1000.0).toInt().coerceAtLeast(64)
        if (n % 2 == 1) n++
        return n
    }

    /** Search tolerance in frames for [sampleRate]. */
    fun toleranceFor(sampleRate: Int): Int = Math.round(toleranceMs * sampleRate / 1000.0).toInt()

    override fun createStream(sampleRate: Int, channels: Int, transients: IntArray?): StretchStream =
        WsolaStream(sampleRate, channels, frameSizeFor(sampleRate), toleranceFor(sampleRate), decimation, transients, maxPushFrames)
}

/**
 * Streaming WSOLA engine (see [WsolaStretcher] for the algorithm). Output frame `n` maps to input time
 * `n / ratio`; there is no time shift. [latencyFrames] = `frameSize + hop + tolerance + 2` input frames of
 * look-ahead are needed before the output at a given input position can be produced.
 *
 * Buffers: the input window and all search buffers are allocated once (sized for pushes of up to
 * [maxPushFrames]; larger pushes are split). The output queue is pre-sized for `4 * maxPushFrames` and only
 * grows when the consumer does not pull between pushes.
 */
class WsolaStream(
    override val sampleRate: Int,
    override val channels: Int,
    val frameSize: Int,
    val tolerance: Int,
    decimation: Int = 0,
    transients: IntArray? = null,
    val maxPushFrames: Int = 8192,
) : StretchStream {
    init {
        require(sampleRate > 0 && channels > 0) { "sampleRate and channels must be positive" }
        require(frameSize >= 16 && frameSize % 2 == 0) { "frameSize must be even and >= 16, was $frameSize" }
        require(tolerance >= 0) { "tolerance must be >= 0" }
        require(maxPushFrames > 0) { "maxPushFrames must be positive" }
    }

    /** Synthesis hop `frameSize / 2`. */
    val hop: Int = frameSize / 2

    /** Decimation factor used for the coarse search (auto: `sampleRate / 11025`, at least 1). */
    val decimation: Int = if (decimation > 0) decimation else (sampleRate / 11025).coerceAtLeast(1)

    override val latencyFrames: Int = frameSize + hop + tolerance + 2

    private val window = Window.hann(frameSize)
    private val transients: IntArray = transients?.copyOf()?.also { it.sort() } ?: IntArray(0)

    // ---- input window (absolute indexing: inBuf[i] holds input frame inBase + i) ----
    // Enough for the search window plus the template of the previous segment even when the nominal position
    // jumps several hops per segment (ratio down to ~0.2), plus one maximal push.
    private val inCap = 8 * frameSize + 2 * tolerance + maxPushFrames + 64
    private val inBuf = Array(channels) { FloatArray(inCap) }
    private val mid: FloatArray = if (channels == 1) inBuf[0] else FloatArray(inCap)
    private var inBase = 0
    private var inLen = 0
    private var inputEnded = false
    private var inTotal = 0

    // ---- synthesis state ----
    private var segIndex = -1
    private var nominalPos = 0.0
    private var started = false
    private var prevStart = Int.MIN_VALUE
    private var curRatio = 1.0
    private var curve: ((Int) -> Double)? = null
    private var outEnd = -1.0

    private val ola = Array(channels) { FloatArray(frameSize) }
    private val seg = FloatArray(frameSize)
    private val regionLen = 2 * tolerance + frameSize
    private val region = FloatArray(regionLen)
    private val tmpl = FloatArray(frameSize)
    private val regionD = FloatArray(regionLen / this.decimation + 1)
    private val tmplD = FloatArray(frameSize / this.decimation + 1)
    private val coarseScore = DoubleArray(COARSE_CANDIDATES)
    private val coarseLag = IntArray(COARSE_CANDIDATES)

    // ---- output queue ----
    private var outBuf = Array(channels) { FloatArray(4 * maxPushFrames + frameSize) }
    private var outRead = 0
    private var outWrite = 0
    private var outProduced = 0

    override var inputFramesPushed: Int = 0
        private set
    override var outputFramesPulled: Int = 0
        private set
    override var totalOutputFrames: Int = -1
        private set
    override val isFlushed: Boolean get() = inputEnded
    override val ratio: Double get() = curRatio

    override fun setRatio(ratio: Double) {
        require(ratio > 0.0 && ratio.isFinite()) { "ratio must be positive and finite, was $ratio" }
        curRatio = ratio
        curve = null
    }

    override fun setRatioCurve(curve: ((outputFrame: Int) -> Double)?) { this.curve = curve }

    override fun reset() {
        for (b in inBuf) b.fill(0f)
        if (channels > 1) mid.fill(0f)
        inBase = 0; inLen = 0; inputEnded = false; inTotal = 0
        segIndex = -1; nominalPos = 0.0; started = false; prevStart = Int.MIN_VALUE; outEnd = -1.0
        for (o in ola) o.fill(0f)
        outRead = 0; outWrite = 0; outProduced = 0
        inputFramesPushed = 0; outputFramesPulled = 0; totalOutputFrames = -1
    }

    override fun push(input: Array<FloatArray>, frames: Int, offset: Int) {
        check(!inputEnded) { "flush() already called; reset() before pushing more input" }
        require(input.size >= channels) { "input needs $channels channels" }
        var done = 0
        while (done < frames) {
            val n = minOf(maxPushFrames, frames - done)
            if (inLen + n > inCap) compactInput()
            check(inLen + n <= inCap) { "internal: input window overflow" }
            for (c in 0 until channels) System.arraycopy(input[c], offset + done, inBuf[c], inLen, n)
            if (channels > 1) {
                val scale = 1f / channels
                for (i in inLen until inLen + n) {
                    var s = 0f
                    for (c in 0 until channels) s += inBuf[c][i]
                    mid[i] = s * scale
                }
            }
            inLen += n
            inputFramesPushed += n
            done += n
            produce()
        }
    }

    override fun flush() {
        if (inputEnded) return
        inputEnded = true
        inTotal = inBase + inLen
        produce()
        if (totalOutputFrames < 0) totalOutputFrames = 0
    }

    override fun available(): Int {
        val queued = outProduced - outputFramesPulled
        return if (totalOutputFrames >= 0) minOf(queued, totalOutputFrames - outputFramesPulled).coerceAtLeast(0) else queued
    }

    override fun pull(output: Array<FloatArray>, frames: Int, offset: Int): Int {
        val n = minOf(frames, available())
        if (n <= 0) return 0
        for (c in 0 until channels) System.arraycopy(outBuf[c], outRead, output[c], offset, n)
        outRead += n
        outputFramesPulled += n
        if (outRead == outWrite) { outRead = 0; outWrite = 0 }
        return n
    }

    // ---- engine ----

    private fun ratioForSegment(m: Int): Double {
        val c = curve ?: return curRatio
        val r = c((m * hop).coerceAtLeast(0))
        require(r > 0.0 && r.isFinite()) { "ratio curve returned $r at output frame ${m * hop}" }
        curRatio = r
        return r
    }

    private fun canProduce(): Boolean {
        if (outEnd >= 0.0) return false
        if (inputEnded) return true
        val p = Math.round(nominalPos).toInt()
        var need = p + tolerance + frameSize + 1
        if (prevStart != Int.MIN_VALUE) need = maxOf(need, prevStart + hop + frameSize + 1)
        return inBase + inLen >= need
    }

    private fun produce() {
        if (!started) {
            nominalPos = -hop / ratioForSegment(-1)
            started = true
        }
        while (canProduce()) produceOne()
    }

    private fun produceOne() {
        val m = segIndex
        val r = ratioForSegment(m)
        val p = Math.round(nominalPos).toInt()
        val s = chooseStart(p, r)
        for (c in 0 until channels) {
            fetch(inBuf[c], s, frameSize, seg)
            val o = ola[c]
            for (i in 0 until frameSize) o[i] += seg[i] * window[i]
        }
        if (m >= 0) emitHop() else shiftOla()
        prevStart = s
        val next = nominalPos + hop / r
        if (inputEnded && next >= inTotal) {
            outEnd = m * hop + (inTotal - nominalPos) * r
            totalOutputFrames = Math.round(outEnd).toInt().coerceAtLeast(0)
        }
        nominalPos = next
        segIndex = m + 1
        if (!inputEnded) discardInput()
    }

    private fun emitHop() {
        if (outWrite + hop > outBuf[0].size) {
            val live = outWrite - outRead
            if (live + hop <= outBuf[0].size && outRead > 0) {
                for (c in 0 until channels) System.arraycopy(outBuf[c], outRead, outBuf[c], 0, live)
            } else {
                val cap = maxOf(outBuf[0].size * 2, live + hop)
                outBuf = Array(channels) { c ->
                    val a = FloatArray(cap)
                    System.arraycopy(outBuf[c], outRead, a, 0, live)
                    a
                }
            }
            outRead = 0; outWrite = live
        }
        for (c in 0 until channels) System.arraycopy(ola[c], 0, outBuf[c], outWrite, hop)
        outWrite += hop
        outProduced += hop
        shiftOla()
    }

    private fun shiftOla() {
        val keep = frameSize - hop
        for (c in 0 until channels) {
            val o = ola[c]
            System.arraycopy(o, hop, o, 0, keep)
            java.util.Arrays.fill(o, keep, frameSize, 0f)
        }
    }

    private fun discardInput() {
        val pNext = Math.round(nominalPos).toInt()
        var keepFrom = pNext - tolerance - 1
        if (prevStart != Int.MIN_VALUE) keepFrom = minOf(keepFrom, prevStart + hop - 1)
        if (keepFrom - inBase >= maxPushFrames) compactInputTo(keepFrom)
    }

    private fun compactInput() {
        val pNext = Math.round(nominalPos).toInt()
        var keepFrom = pNext - tolerance - 1
        if (prevStart != Int.MIN_VALUE) keepFrom = minOf(keepFrom, prevStart + hop - 1)
        compactInputTo(keepFrom)
    }

    private fun compactInputTo(keepFrom: Int) {
        val shift = keepFrom - inBase
        if (shift <= 0) return
        if (shift >= inLen) { inLen = 0; inBase = keepFrom; return }
        val keep = inLen - shift
        for (c in 0 until channels) System.arraycopy(inBuf[c], shift, inBuf[c], 0, keep)
        if (channels > 1) System.arraycopy(mid, shift, mid, 0, keep)
        inLen = keep
        inBase = keepFrom
    }

    /** Copies input frames `[absStart, absStart + len)` of [src] into [dst], zero-filling outside the window. */
    private fun fetch(src: FloatArray, absStart: Int, len: Int, dst: FloatArray) {
        val lo = inBase
        val hi = inBase + inLen
        val c0 = maxOf(absStart, lo)
        val c1 = minOf(absStart + len, hi)
        if (c1 <= c0) { java.util.Arrays.fill(dst, 0, len, 0f); return }
        val head = c0 - absStart
        if (head > 0) java.util.Arrays.fill(dst, 0, head, 0f)
        System.arraycopy(src, c0 - inBase, dst, head, c1 - c0)
        val tail = head + (c1 - c0)
        if (tail < len) java.util.Arrays.fill(dst, tail, len, 0f)
    }

    // ---- transient bookkeeping ----

    /** Index of the first transient >= x (or size). */
    private fun lowerBound(x: Int): Int {
        var lo = 0; var hi = transients.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (transients[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun hasTransientIn(a: Int, b: Int): Boolean {
        val i = lowerBound(a)
        return i < transients.size && transients[i] < b
    }

    /**
     * Chooses the absolute start of the segment whose nominal start is [p] (ratio [r] for this hop).
     *
     * Transient rules, in order: (1) if the previous segment contained a transient, continue it naturally
     * (`prevStart + hop`) so the overlapping copies coincide; (2) if the nominal window contains a transient `T`
     * at offset `x = T - p`, start at `p + x (1 - r)` so that `T` lands exactly at its nominal output time
     * (a segment is copied verbatim, so the offset inside it is not scaled); (3) otherwise search, but never
     * reach back into the transient before `p` nor forward into the next one.
     */
    private fun chooseStart(p: Int, r: Double): Int {
        var lo = -tolerance
        var hi = tolerance
        if (transients.isNotEmpty()) {
            if (prevStart != Int.MIN_VALUE && hasTransientIn(prevStart, prevStart + frameSize)) {
                return p + (prevStart + hop - p).coerceIn(-tolerance, tolerance)
            }
            val iNext = lowerBound(p)
            if (iNext < transients.size && transients[iNext] < p + frameSize) {
                val x = transients[iNext] - p
                val delta = Math.round(x * (1.0 - r)).toInt()
                if (delta in -tolerance..tolerance && x - delta < frameSize) return p + delta
            }
            val iPrev = iNext - 1
            if (iPrev >= 0) lo = maxOf(lo, transients[iPrev] - p + 1)
            if (iNext < transients.size) hi = minOf(hi, transients[iNext] - frameSize - p)
            if (lo > hi) return p
        }
        return p + search(p, lo, hi)
    }

    /** Best offset in `[lo, hi]` (relative to [p]) by normalised cross-correlation with the natural continuation. */
    private fun search(p: Int, lo: Int, hi: Int): Int {
        if (lo > hi) return 0
        if (prevStart == Int.MIN_VALUE || tolerance == 0 || lo == hi) return 0.coerceIn(lo, hi)
        fetch(mid, p - tolerance, regionLen, region)
        fetch(mid, prevStart + hop, frameSize, tmpl)
        var tE = 0.0
        for (i in 0 until frameSize) tE += tmpl[i].toDouble() * tmpl[i]
        if (tE < 1e-12) return 0.coerceIn(lo, hi)
        val d = decimation
        val n = frameSize
        var bestLag = 0
        var bestScore = -2.0
        if (d > 1 && hi - lo >= 2 * d) {
            // Coarse pass on box-decimated signals.
            val rd = regionLen / d
            val nd = n / d
            for (i in 0 until rd) {
                var s = 0f
                val base = i * d
                for (j in 0 until d) s += region[base + j]
                regionD[i] = s
            }
            for (i in 0 until nd) {
                var s = 0f
                val base = i * d
                for (j in 0 until d) s += tmpl[base + j]
                tmplD[i] = s
            }
            var tdE = 0.0
            for (i in 0 until nd) tdE += tmplD[i].toDouble() * tmplD[i]
            for (k in 0 until COARSE_CANDIDATES) { coarseScore[k] = -2.0; coarseLag[k] = 0 }
            val jLo = ceilDiv(lo + tolerance, d).coerceAtLeast(0)
            val jHi = minOf((hi + tolerance) / d, rd - nd)
            for (j in jLo..jHi) {
                var corr = 0.0
                var e = 0.0
                for (i in 0 until nd) {
                    val v = regionD[j + i]
                    corr += v.toDouble() * tmplD[i]
                    e += v.toDouble() * v
                }
                val score = corr / sqrt(e * tdE + 1e-18)
                // insert into the top-K list
                var slot = -1
                var worst = 2.0
                for (k in 0 until COARSE_CANDIDATES) if (coarseScore[k] < worst) { worst = coarseScore[k]; slot = k }
                if (score > worst) { coarseScore[slot] = score; coarseLag[slot] = j * d - tolerance }
            }
            for (k in 0 until COARSE_CANDIDATES) {
                if (coarseScore[k] <= -2.0) continue
                val centre = coarseLag[k]
                val a = maxOf(lo, centre - d)
                val b = minOf(hi, centre + d)
                for (delta in a..b) {
                    val sc = fullScore(delta, tE)
                    if (sc > bestScore) { bestScore = sc; bestLag = delta }
                }
            }
        } else {
            for (delta in lo..hi) {
                val sc = fullScore(delta, tE)
                if (sc > bestScore) { bestScore = sc; bestLag = delta }
            }
        }
        return bestLag
    }

    /** Full-rate NCC of the region at offset [delta] with the template, with a tiny bias towards small |delta|. */
    private fun fullScore(delta: Int, tE: Double): Double {
        val base = delta + tolerance
        var corr = 0.0
        var e = 0.0
        for (i in 0 until frameSize) {
            val v = region[base + i]
            corr += v.toDouble() * tmpl[i]
            e += v.toDouble() * v
        }
        val ncc = corr / sqrt(e * tE + 1e-18)
        return ncc - SMALL_LAG_BIAS * abs(delta) / (tolerance + 1)
    }

    private fun ceilDiv(a: Int, b: Int): Int = if (a >= 0) (a + b - 1) / b else -((-a) / b)

    private companion object {
        const val COARSE_CANDIDATES = 3
        const val SMALL_LAG_BIAS = 1e-4
    }
}
