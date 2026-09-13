package dev.muisc.dsp.stretch

import dev.muisc.dsp.fft.RealFft
import dev.muisc.dsp.filter.Biquad
import dev.muisc.dsp.filter.BiquadFilter
import dev.muisc.dsp.window.Window
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Numeric measurement helpers shared by the stretch / fx / texture / synth tests. */
internal object SigMeasure {
    /** Hann-windowed magnitude spectrum of `x[offset, offset + n)` (n a power of two); zero-padded past the end. */
    fun spectrum(x: FloatArray, n: Int, offset: Int = 0): FloatArray {
        val w = Window.hann(n)
        val frame = FloatArray(n) { val i = offset + it; if (i < x.size) x[i] * w[it] else 0f }
        val fft = RealFft(n)
        val re = FloatArray(fft.bins); val im = FloatArray(fft.bins)
        fft.forward(frame, re, im)
        return FloatArray(fft.bins) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }

    /** Frequency of the strongest spectral peak in [minHz, maxHz] with parabolic interpolation. */
    fun peakFrequency(x: FloatArray, sampleRate: Int, n: Int = 32768, offset: Int = 0, minHz: Double = 20.0, maxHz: Double = sampleRate / 2.0): Double {
        val mag = spectrum(x, n, offset)
        val k0 = (minHz * n / sampleRate).toInt().coerceAtLeast(1)
        val k1 = (maxHz * n / sampleRate).toInt().coerceAtMost(mag.size - 2)
        var best = k0
        for (k in k0..k1) if (mag[k] > mag[best]) best = k
        val a = mag[best - 1].toDouble(); val b = mag[best].toDouble(); val c = mag[best + 1].toDouble()
        val denom = a - 2 * b + c
        val delta = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
        return (best + delta) * sampleRate / n
    }

    /** Largest magnitude within ±[bins] of [freqHz] in a spectrum of size [n]. */
    fun peakNear(mag: FloatArray, freqHz: Double, sampleRate: Int, n: Int, bins: Int = 3): Double {
        val k = Math.round(freqHz * n / sampleRate).toInt()
        var m = 0.0
        for (j in (k - bins).coerceAtLeast(0)..(k + bins).coerceAtMost(mag.size - 1)) m = maxOf(m, mag[j].toDouble())
        return m
    }

    fun rms(x: FloatArray, start: Int = 0, end: Int = x.size): Double {
        if (end <= start) return 0.0
        var acc = 0.0
        for (i in start until end) acc += x[i].toDouble() * x[i]
        return sqrt(acc / (end - start))
    }

    fun db(v: Double): Double = 20.0 * log10(maxOf(v, 1e-12))

    /** Hann-windowed single-bin DFT amplitude of the [freqHz] component over `[start, start + len)`. */
    fun amplitudeAt(x: FloatArray, sampleRate: Int, freqHz: Double, start: Int, len: Int): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        var re = 0.0; var im = 0.0; var wsum = 0.0
        for (i in 0 until len) {
            val win = 0.5 - 0.5 * cos(2.0 * PI * i / len)
            val v = x[start + i].toDouble() * win
            re += v * cos(w * i); im -= v * sin(w * i); wsum += win
        }
        return 2.0 * sqrt(re * re + im * im) / wsum
    }

    /** Signal-to-noise ratio in dB of [y] against reference [x] over the common length. */
    fun snrDb(x: FloatArray, y: FloatArray, start: Int = 0, end: Int = minOf(x.size, y.size)): Double {
        var sig = 0.0; var err = 0.0
        for (i in start until end) { val d = (y[i] - x[i]).toDouble(); err += d * d; sig += x[i].toDouble() * x[i] }
        return 10.0 * log10(sig / maxOf(err, 1e-30))
    }

    /** Frequency estimate from zero crossings over `[start, start + len)`. */
    fun zeroCrossingFrequency(x: FloatArray, sampleRate: Int, start: Int, len: Int): Double {
        var first = -1; var last = -1; var count = 0
        for (i in start + 1 until start + len) {
            if (x[i - 1] < 0f && x[i] >= 0f) { if (first < 0) first = i; last = i; count++ }
        }
        if (count < 2) return 0.0
        return (count - 1).toDouble() * sampleRate / (last - first)
    }

    /**
     * Kick onset positions (frames): the signal is low-passed at [lowPassHz] (2nd-order Butterworth), its RMS
     * envelope taken over 5 ms windows every 1 ms, and peaks of the positive envelope difference above
     * [threshold] times the global maximum, separated by at least [minGapFrames], are reported.
     */
    fun kickOnsets(x: FloatArray, sampleRate: Int, minGapFrames: Int, lowPassHz: Double = 150.0, threshold: Double = 0.3): IntArray {
        val lp = BiquadFilter(1, Biquad.lowPass(lowPassHz, sampleRate.toDouble()))
        val y = FloatArray(x.size)
        lp.process(x, y, x.size)
        val win = Math.round(0.005 * sampleRate).toInt()
        val hop = Math.round(0.001 * sampleRate).toInt()
        val m = (x.size - win) / hop
        if (m < 2) return IntArray(0)
        val env = DoubleArray(m)
        for (t in 0 until m) env[t] = rms(y, t * hop, t * hop + win)
        val d = DoubleArray(m)
        var mx = 0.0
        for (t in 1 until m) { d[t] = maxOf(0.0, env[t] - env[t - 1]); if (d[t] > mx) mx = d[t] }
        val out = ArrayList<Int>()
        var lastPeak = -minGapFrames
        var t = 1
        while (t < m - 1) {
            if (d[t] >= threshold * mx && d[t] >= d[t - 1] && d[t] >= d[t + 1] && t * hop - lastPeak >= minGapFrames) {
                out += t * hop
                lastPeak = t * hop
            }
            t++
        }
        return out.toIntArray()
    }

    /** Pearson correlation of two arrays over the common length. */
    fun correlation(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var sa = 0.0; var sb = 0.0; var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in 0 until n) { sa += a[i]; sb += b[i]; sab += a[i].toDouble() * b[i]; saa += a[i].toDouble() * a[i]; sbb += b[i].toDouble() * b[i] }
        val cov = sab - sa * sb / n
        val va = saa - sa * sa / n
        val vb = sbb - sb * sb / n
        return if (va <= 0 || vb <= 0) 0.0 else cov / sqrt(va * vb)
    }
}
