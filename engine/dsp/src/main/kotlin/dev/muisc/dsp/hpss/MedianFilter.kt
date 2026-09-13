package dev.muisc.dsp.hpss

/**
 * A bank of [lanes] independent sliding-window medians that all advance in lock-step, e.g. one lane per
 * STFT bin when median-filtering a spectrogram along the time axis.
 *
 * Each lane keeps its current window as a small sorted array of at most [kernel] values (flat storage
 * `lanes * kernel`). Sliding the window is a single linear pass per lane: the outgoing value is located and the
 * incoming value is bubbled into its sorted slot ("insertion-sort update"), which for the kernel sizes used in
 * HPSS (about 9–41) is faster than heap or histogram medians and allocates nothing after construction.
 *
 * The window may be shorter than [kernel] near the edges of the data ("shrinking window" edge mode): use
 * [push] while filling, [replace] when sliding and [remove] while draining. The median of an even count is the
 * mean of the two middle values.
 *
 * All lanes always hold the same number of values ([count]).
 */
class MedianBank(val lanes: Int, val kernel: Int) {
    init {
        require(lanes > 0) { "lanes must be positive" }
        require(kernel > 0) { "kernel must be positive" }
    }

    private val sorted = FloatArray(lanes * kernel)

    /** Number of values currently in every lane's window, in `[0, kernel]`. */
    var count: Int = 0
        private set

    /** Empties every lane. */
    fun reset() { count = 0 }

    /** Adds `values[lane]` to each lane. Requires `count < kernel`. */
    fun push(values: FloatArray) {
        check(count < kernel) { "bank is full ($kernel values per lane); use replace()" }
        val n = count
        val k = kernel
        for (lane in 0 until lanes) {
            val base = lane * k
            val v = values[lane]
            var i = n
            while (i > 0 && sorted[base + i - 1] > v) { sorted[base + i] = sorted[base + i - 1]; i-- }
            sorted[base + i] = v
        }
        count = n + 1
    }

    /**
     * Slides every lane: removes `outgoing[lane]` (which must currently be in the window) and inserts
     * `incoming[lane]`, keeping [count] unchanged.
     */
    fun replace(outgoing: FloatArray, incoming: FloatArray) {
        val n = count
        check(n > 0) { "bank is empty" }
        val k = kernel
        for (lane in 0 until lanes) {
            val base = lane * k
            val old = outgoing[lane]
            val v = incoming[lane]
            // Locate the outgoing value (linear scan; the window is tiny).
            var i = 0
            while (i < n - 1 && sorted[base + i] != old) i++
            // Bubble the new value from slot i toward its sorted position.
            if (v > old) {
                while (i < n - 1 && sorted[base + i + 1] < v) { sorted[base + i] = sorted[base + i + 1]; i++ }
            } else {
                while (i > 0 && sorted[base + i - 1] > v) { sorted[base + i] = sorted[base + i - 1]; i-- }
            }
            sorted[base + i] = v
        }
    }

    /** Removes `outgoing[lane]` from each lane (the value must currently be in the window). */
    fun remove(outgoing: FloatArray) {
        val n = count
        check(n > 0) { "bank is empty" }
        val k = kernel
        for (lane in 0 until lanes) {
            val base = lane * k
            val old = outgoing[lane]
            var i = 0
            while (i < n - 1 && sorted[base + i] != old) i++
            while (i < n - 1) { sorted[base + i] = sorted[base + i + 1]; i++ }
        }
        count = n - 1
    }

    /** Writes the current median of every lane into [out] (0 when the bank is empty). */
    fun median(out: FloatArray) {
        val n = count
        val k = kernel
        if (n == 0) { java.util.Arrays.fill(out, 0, lanes, 0f); return }
        val mid = n ushr 1
        if (n and 1 == 1) {
            for (lane in 0 until lanes) out[lane] = sorted[lane * k + mid]
        } else {
            for (lane in 0 until lanes) { val b = lane * k; out[lane] = 0.5f * (sorted[b + mid - 1] + sorted[b + mid]) }
        }
    }
}

/**
 * 1-D and 2-D running-median filters with "shrinking window" edge handling (the window is truncated to the
 * valid range at both ends instead of padded), built on the same insertion-sort sliding window as [MedianBank].
 * These are the smoothing kernels of Fitzgerald's median-filtering HPSS, but are generic.
 */
object MedianFilter {

    /**
     * Running median of `x[0 until n]` with an odd [kernel] (`kernel / 2` values on each side) into `out`.
     * `out` may not alias `x`. Edges use the shrinking window (median of the available neighbours only, mean of
     * the two middle values for even counts). Zero allocation when [work] (length ≥ kernel) is supplied.
     */
    fun filter1D(x: FloatArray, out: FloatArray, n: Int = x.size, kernel: Int, work: FloatArray? = null) {
        require(kernel > 0 && kernel % 2 == 1) { "kernel must be a positive odd integer, was $kernel" }
        require(n <= x.size && n <= out.size) { "n exceeds array length" }
        if (n == 0) return
        val half = kernel / 2
        val sorted = work ?: FloatArray(kernel)
        // Prime with x[0 .. half] (as many as exist).
        var count = 0
        val primeEnd = minOf(half, n - 1)
        for (i in 0..primeEnd) { insert(sorted, count, x[i]); count++ }
        for (i in 0 until n) {
            // The window for output i is [i - half, i + half]; incoming = i + half, outgoing = i - half - 1.
            val inIdx = i + half
            val outIdx = i - half - 1
            if (i > 0) {
                if (inIdx < n && outIdx >= 0) {
                    slide(sorted, count, x[outIdx], x[inIdx])
                } else if (inIdx < n) {
                    insert(sorted, count, x[inIdx]); count++
                } else if (outIdx >= 0) {
                    delete(sorted, count, x[outIdx]); count--
                }
            }
            val mid = count ushr 1
            out[i] = if (count and 1 == 1) sorted[mid] else 0.5f * (sorted[mid - 1] + sorted[mid])
        }
    }

    /**
     * Median-filters each row of `s` (shape `[rows][cols]`, e.g. `[frame][bin]`) along its own axis, i.e.
     * across columns/bins, into `out` (same shape, may not alias). This is the *percussive* enhancement of HPSS
     * (smoothing across frequency).
     */
    fun alongRows(s: Array<FloatArray>, out: Array<FloatArray>, kernel: Int) {
        if (s.isEmpty()) return
        val work = FloatArray(kernel)
        for (r in s.indices) filter1D(s[r], out[r], s[r].size, kernel, work)
    }

    /**
     * Median-filters `s` (shape `[rows][cols]`, e.g. `[frame][bin]`) along the row index, i.e. across time for
     * each column/bin, into `out` (same shape, may not alias). This is the *harmonic* enhancement of HPSS.
     * All columns are advanced together through a [MedianBank] so memory access stays row-contiguous.
     */
    fun alongColumns(s: Array<FloatArray>, out: Array<FloatArray>, kernel: Int) {
        require(kernel > 0 && kernel % 2 == 1) { "kernel must be a positive odd integer, was $kernel" }
        val rows = s.size
        if (rows == 0) return
        val cols = s[0].size
        val half = kernel / 2
        val bank = MedianBank(cols, kernel)
        val primeEnd = minOf(half, rows - 1)
        for (r in 0..primeEnd) bank.push(s[r])
        for (r in 0 until rows) {
            val inIdx = r + half
            val outIdx = r - half - 1
            if (r > 0) {
                if (inIdx < rows && outIdx >= 0) bank.replace(s[outIdx], s[inIdx])
                else if (inIdx < rows) bank.push(s[inIdx])
                else if (outIdx >= 0) bank.remove(s[outIdx])
            }
            bank.median(out[r])
        }
    }

    private fun insert(sorted: FloatArray, count: Int, v: Float) {
        var i = count
        while (i > 0 && sorted[i - 1] > v) { sorted[i] = sorted[i - 1]; i-- }
        sorted[i] = v
    }

    private fun delete(sorted: FloatArray, count: Int, old: Float) {
        var i = 0
        while (i < count - 1 && sorted[i] != old) i++
        while (i < count - 1) { sorted[i] = sorted[i + 1]; i++ }
    }

    private fun slide(sorted: FloatArray, count: Int, old: Float, v: Float) {
        var i = 0
        while (i < count - 1 && sorted[i] != old) i++
        if (v > old) {
            while (i < count - 1 && sorted[i + 1] < v) { sorted[i] = sorted[i + 1]; i++ }
        } else {
            while (i > 0 && sorted[i - 1] > v) { sorted[i] = sorted[i - 1]; i-- }
        }
        sorted[i] = v
    }
}
