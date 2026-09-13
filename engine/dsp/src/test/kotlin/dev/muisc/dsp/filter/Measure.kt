package dev.muisc.dsp.filter

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Numeric helpers shared by the filter tests. */
internal object Measure {
    /** Amplitude of the [freqHz] component of x over [start, start+len) via a single-bin DFT ("lock-in"). */
    fun amplitude(x: FloatArray, sampleRate: Int, freqHz: Double, start: Int, len: Int): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        var re = 0.0; var im = 0.0
        for (i in 0 until len) {
            val v = x[start + i].toDouble()
            re += v * cos(w * (start + i)); im -= v * sin(w * (start + i))
        }
        return 2.0 * sqrt(re * re + im * im) / len
    }

    fun rms(x: FloatArray, start: Int = 0, end: Int = x.size): Double {
        var acc = 0.0
        for (i in start until end) acc += x[i].toDouble() * x[i]
        return sqrt(acc / (end - start))
    }

    fun db(v: Double): Double = 20.0 * log10(v)

    /** Log-spaced frequencies from [lo] to [hi], snapped to integer DFT bins of a window of [windowLen] samples. */
    fun binFrequencies(count: Int, lo: Double, hi: Double, sampleRate: Int, windowLen: Int): DoubleArray = DoubleArray(count) {
        val f = lo * Math.pow(hi / lo, it / (count - 1.0))
        val k = Math.round(f * windowLen / sampleRate).toInt().coerceAtLeast(1)
        k.toDouble() * sampleRate / windowLen
    }
}
