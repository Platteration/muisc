package dev.muisc.dsp.hpss

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MedianFilterTest {

    /** Brute-force shrinking-window median (sort the valid neighbourhood). */
    private fun bruteMedian1D(x: FloatArray, kernel: Int): FloatArray {
        val half = kernel / 2
        return FloatArray(x.size) { i ->
            val lo = maxOf(0, i - half); val hi = minOf(x.size - 1, i + half)
            val w = x.copyOfRange(lo, hi + 1).also { it.sort() }
            val n = w.size
            if (n % 2 == 1) w[n / 2] else 0.5f * (w[n / 2 - 1] + w[n / 2])
        }
    }

    @Test
    fun filter1DMatchesBruteForceIncludingEdgesAndDuplicates() {
        val rnd = Random(11)
        for (kernel in intArrayOf(1, 3, 5, 17, 31)) {
            for (n in intArrayOf(1, 2, 7, 16, 17, 100, 333)) {
                // Small integer alphabet so that duplicate values are frequent (exercises the equal-value paths).
                val x = FloatArray(n) { rnd.nextInt(0, 6).toFloat() }
                val out = FloatArray(n)
                MedianFilter.filter1D(x, out, n, kernel)
                val ref = bruteMedian1D(x, kernel)
                for (i in 0 until n) assertEquals(ref[i], out[i], 0f, "kernel=$kernel n=$n i=$i")
            }
        }
    }

    @Test
    fun filter1DRemovesImpulsesAndKeepsSteps() {
        val n = 200
        val x = FloatArray(n) { if (it < 100) 1f else 3f }
        x[50] = 100f; x[51] = -100f; x[150] = 40f
        val out = FloatArray(n)
        MedianFilter.filter1D(x, out, n, kernel = 9)
        // Isolated outliers (up to (kernel-1)/2 = 4 consecutive) vanish; the step edge stays sharp.
        for (i in 0 until 100) assertEquals(1f, out[i], 0f, "i=$i")
        for (i in 100 until n) assertEquals(3f, out[i], 0f, "i=$i")
    }

    @Test
    fun alongColumnsAndAlongRowsMatchBruteForce() {
        val rnd = Random(5)
        val rows = 40; val cols = 23; val kernel = 7
        val s = Array(rows) { FloatArray(cols) { rnd.nextInt(0, 9).toFloat() } }
        val byRows = Array(rows) { FloatArray(cols) }
        val byCols = Array(rows) { FloatArray(cols) }
        MedianFilter.alongRows(s, byRows, kernel)
        MedianFilter.alongColumns(s, byCols, kernel)
        for (r in 0 until rows) {
            val ref = bruteMedian1D(s[r], kernel)
            for (c in 0 until cols) assertEquals(ref[c], byRows[r][c], 0f, "rows r=$r c=$c")
        }
        for (c in 0 until cols) {
            val column = FloatArray(rows) { s[it][c] }
            val ref = bruteMedian1D(column, kernel)
            for (r in 0 until rows) assertEquals(ref[r], byCols[r][c], 0f, "cols r=$r c=$c")
        }
    }

    @Test
    fun medianBankPushReplaceRemoveTrackSortedWindows() {
        val rnd = Random(3)
        val lanes = 5; val kernel = 9
        val bank = MedianBank(lanes, kernel)
        val stream = Array(60) { FloatArray(lanes) { rnd.nextInt(-3, 4).toFloat() } }
        val out = FloatArray(lanes)
        // Fill, slide, drain — after every step the median must equal the brute-force median of the window.
        var lo = 0; var hi = 0 // window = stream[lo until hi]
        fun check(step: String) {
            bank.median(out)
            assertEquals(hi - lo, bank.count, step)
            if (hi == lo) return
            for (l in 0 until lanes) {
                val w = FloatArray(hi - lo) { stream[lo + it][l] }.also { it.sort() }
                val n = w.size
                val ref = if (n % 2 == 1) w[n / 2] else 0.5f * (w[n / 2 - 1] + w[n / 2])
                assertEquals(ref, out[l], 0f, "$step lane $l")
            }
        }
        for (i in 0 until kernel) { bank.push(stream[hi]); hi++; check("push $i") }
        for (i in kernel until stream.size) { bank.replace(stream[lo], stream[hi]); lo++; hi++; check("replace $i") }
        while (lo < hi) { bank.remove(stream[lo]); lo++; check("remove $lo") }
        assertEquals(0, bank.count)
        bank.median(out)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun constantAndMonotonicInputsAreFixedPoints() {
        val n = 64
        val c = FloatArray(n) { 0.25f }
        val out = FloatArray(n)
        MedianFilter.filter1D(c, out, n, 15)
        for (i in 0 until n) assertEquals(0.25f, out[i], 0f)
        // A monotonic ramp is its own median in the interior; at the edges the shrinking window keeps it exact too
        // (median of a contiguous ramp segment is its middle element = the centre sample).
        val ramp = FloatArray(n) { it.toFloat() }
        MedianFilter.filter1D(ramp, out, n, 5)
        for (i in 2 until n - 2) assertEquals(ramp[i], out[i], 0f, "i=$i")
        assertEquals(1f, out[0], 0f)      // window [0,1,2] -> 1
        assertEquals(1.5f, out[1], 0f)    // window [0,1,2,3] -> mean(1,2)
    }
}
