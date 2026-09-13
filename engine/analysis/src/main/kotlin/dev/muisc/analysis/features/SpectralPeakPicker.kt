package dev.muisc.analysis.features

import dev.muisc.dsp.fft.Stft
import dev.muisc.dsp.fft.StftFrameSink
import dev.muisc.dsp.hpss.MedianFilter
import dev.muisc.dsp.window.Window
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Sparse list of spectral peaks (sinusoidal partials) of a signal, as parallel arrays sorted by frame.
 *
 * `frame[i]` is the STFT frame index (centred convention: frame `t` is centred on sample `t * hop`),
 * `freqHz[i]` the parabolically interpolated peak frequency and `magnitude[i]` the interpolated linear
 * magnitude (unscaled [Stft] convention: a sine of amplitude A on a bin centre reads `A * N / 4` with the
 * Hann window of length N).
 */
class SpectralPeaks(
    val frameCount: Int,
    val hopSec: Double,
    val frame: IntArray,
    val freqHz: FloatArray,
    val magnitude: FloatArray,
) {
    val size: Int get() = frame.size
    val isEmpty: Boolean get() = frame.isEmpty()

    companion object {
        fun empty(hopSec: Double): SpectralPeaks = SpectralPeaks(0, hopSec, IntArray(0), FloatArray(0), FloatArray(0))
    }
}

/**
 * Picks sinusoidal peaks from a Hann-windowed STFT (streaming, one frame in memory at a time).
 *
 * Per frame:
 *  1. magnitude spectrum `|X[k]|`;
 *  2. a **local noise floor** = running median of the magnitude over [floorKernelBins] bins
 *     ([MedianFilter.filter1D]; a sparse line spectrum occupies well under half of the bins, so the median
 *     tracks the noise / sidelobe level rather than the partials);
 *  3. a bin `k` in `[minFreqHz, maxFreqHz]` is a peak when it is a strict local maximum
 *     (`|X[k]| > |X[k-1]|`, `|X[k]| >= |X[k+1]|`), exceeds `floorRatio * floor[k]` and lies within
 *     [dynamicRangeDb] of the frame's largest bin;
 *  4. frequency and magnitude are refined by **parabolic interpolation in the log-magnitude domain**
 *     (`delta = 0.5 (a - c) / (a - 2b + c)` with `a, b, c = ln|X[k-1..k+1]|`; J. O. Smith, "Spectral Audio
 *     Signal Processing", quadratic peak interpolation), accurate to a few cents even at 55 Hz.
 *
 * Defaults are for the 22.05 kHz analysis rate: frame 8192 (2.69 Hz bins, 372 ms) and hop 2048 (93 ms).
 * 8192 rather than 4096 because adjacent semitones at 55 Hz are only 3.3 Hz apart: a 5.4 Hz bin (4096)
 * cannot separate the bass register, and parabolic interpolation needs clear bins between partials.
 * Silence yields an empty peak list; the input may be arbitrarily short.
 */
class SpectralPeakPicker(
    val sampleRate: Int = FeatureRate.SAMPLE_RATE,
    val frameSize: Int = 8192,
    val hop: Int = 2048,
    val minFreqHz: Double = 55.0,
    val maxFreqHz: Double = 5000.0,
    val floorKernelBins: Int = 51,
    val floorRatio: Float = 4f,
    val dynamicRangeDb: Float = 60f,
) {
    init {
        require(sampleRate > 0 && frameSize >= 16 && hop in 1..frameSize)
        require(minFreqHz > 0 && maxFreqHz > minFreqHz && maxFreqHz <= sampleRate / 2.0)
        require(floorKernelBins > 0 && floorKernelBins % 2 == 1) { "floorKernelBins must be odd" }
    }

    /** Seconds per STFT hop. */
    val hopSec: Double = hop.toDouble() / sampleRate

    private val bins = frameSize / 2 + 1
    private val kMin = ceil(minFreqHz * frameSize / sampleRate).toInt().coerceAtLeast(1)
    private val kMax = floor(maxFreqHz * frameSize / sampleRate).toInt().coerceAtMost(bins - 2)
    private val rangeFloor = 10.0.pow(-dynamicRangeDb / 20.0).toFloat()

    /** Peaks of the mono signal [x] (at [sampleRate]). */
    fun analyze(x: FloatArray): SpectralPeaks {
        if (x.isEmpty()) return SpectralPeaks.empty(hopSec)
        val stft = Stft(frameSize, hop, Window.hann(frameSize), center = true)
        val frames = stft.frameCount(x.size)
        val mag = FloatArray(bins)
        val floorArr = FloatArray(bins)
        val work = FloatArray(floorKernelBins)
        var frameOut = IntArray(1024)
        var freqOut = FloatArray(1024)
        var magOut = FloatArray(1024)
        var n = 0
        val sink = StftFrameSink { t, re, im ->
            var frameMax = 0f
            for (k in 0 until bins) {
                val r = re[k]; val i = im[k]
                val m = sqrt(r * r + i * i)
                mag[k] = m
                if (m > frameMax) frameMax = m
            }
            if (frameMax > 0f) {
                MedianFilter.filter1D(mag, floorArr, bins, floorKernelBins, work)
                val absFloor = frameMax * rangeFloor
                for (k in kMin..kMax) {
                    val b = mag[k]
                    if (b <= mag[k - 1] || b < mag[k + 1]) continue
                    if (b < absFloor || b <= floorRatio * floorArr[k]) continue
                    val a = mag[k - 1]; val c = mag[k + 1]
                    val la = ln(a.coerceAtLeast(1e-12f).toDouble()); val lb = ln(b.toDouble()); val lc = ln(c.coerceAtLeast(1e-12f).toDouble())
                    val denom = la - 2 * lb + lc
                    val delta = if (denom < 0.0) (0.5 * (la - lc) / denom).coerceIn(-0.5, 0.5) else 0.0
                    val peakMag = exp(lb - 0.25 * (la - lc) * delta)
                    if (n == frameOut.size) {
                        frameOut = frameOut.copyOf(n * 2); freqOut = freqOut.copyOf(n * 2); magOut = magOut.copyOf(n * 2)
                    }
                    frameOut[n] = t
                    freqOut[n] = ((k + delta) * sampleRate / frameSize).toFloat()
                    magOut[n] = peakMag.toFloat()
                    n++
                }
            }
        }
        stft.process(x, 0, x.size, sink)
        stft.flush(sink)
        return SpectralPeaks(frames, hopSec, frameOut.copyOf(n), freqOut.copyOf(n), magOut.copyOf(n))
    }
}
