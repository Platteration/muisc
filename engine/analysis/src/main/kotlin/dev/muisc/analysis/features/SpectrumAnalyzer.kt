package dev.muisc.analysis.features

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.Stft
import dev.muisc.dsp.fft.StftFrameSink
import dev.muisc.dsp.resample.Resampler
import dev.muisc.dsp.texture.ThirdOctaveBands
import dev.muisc.dsp.window.Window
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Spectral descriptors for [dev.muisc.analysis.model.TrackAnalysis]: `ltasDb` / `introLtasDb` /
 * `outroLtasDb`, `brightnessHz` and `textureMagnitude`.
 */
class SpectrumFeatures(
    /** LTAS of the trimmed range: 31 ISO third-octave bands 20 Hz .. 20 kHz, dB (see [ThirdOctaveBands]). */
    val ltasDb: FloatArray,
    /** LTAS of the first [SpectrumAnalyzer.regionSec] after the trim start (same bands). */
    val introLtasDb: FloatArray,
    /** LTAS of the last [SpectrumAnalyzer.regionSec] before the trim end (same bands). */
    val outroLtasDb: FloatArray,
    /** Spectral centroid of the trimmed range in Hz (0 for silence). */
    val brightnessHz: Float,
    /** Per-bin median magnitude spectrum of the outro texture window; see [SpectrumAnalyzer.textureMagnitude]. */
    val textureMagnitude: FloatArray,
)

/**
 * Long-term spectrum, brightness and outro texture.
 *
 * - **LTAS** ([ltasDb]): [ThirdOctaveBands.measureDb] (Welch estimate, Hann frames of [ltasFrame] at 50 %
 *   overlap) of the mono mix **at the engine rate** (so the 20 kHz band exists for 44.1 / 48 kHz input);
 *   per band `10 log10` of the band power, i.e. the dBFS RMS of the band-limited signal, floored at
 *   [ThirdOctaveBands.FLOOR_DB] (-120). Measured over the trimmed range and separately over the intro
 *   (first [regionSec] after the trim start) and outro (last [regionSec] before the trim end) regions.
 * - **Brightness** ([centroidHz]): power-weighted mean frequency `sum f_k P_k / sum P_k` of the Welch power
 *   spectrum (same geometry) over the trimmed range, in Hz; 0 for silence.
 * - **Texture** ([textureMagnitude]): the stationary spectral envelope of the last [textureSec] (15 s) before
 *   the trim end, which the TextureCarry modifier resynthesises. Exact recipe, so it can be inverted:
 *   mono downmix resampled to [TEXTURE_SAMPLE_RATE] = 22 050 Hz ([Resampler], zero-phase), then
 *   `Stft(frameSize = 1024, hop = 256, window = Window.hann(1024) (periodic), center = true)`, unscaled
 *   [dev.muisc.dsp.fft.RealFft] magnitudes `|X[k]|` (a sine of amplitude A on a bin centre reads `A * 256`),
 *   and per bin the **median** over all frames of the window: [TEXTURE_BINS] = 513 linear values for
 *   `f_k = k * 22050 / 1024` Hz. Zeros for silence / empty ranges.
 *
 * Never throws: empty or silent ranges yield floor-valued LTAS, brightness 0 and an all-zero texture.
 */
class SpectrumAnalyzer(
    val regionSec: Double = 30.0,
    val textureSec: Double = 15.0,
    val ltasFrame: Int = ThirdOctaveBands.DEFAULT_FRAME,
    val resampler: Resampler = Resampler(),
) {
    init { require(regionSec > 0 && textureSec > 0 && ltasFrame >= 16) }

    /** All spectral features of engine frames `[trimStartFrame, trimEndFrame)` (clamped). */
    fun analyze(audio: AudioBuffer, trimStartFrame: Long = 0L, trimEndFrame: Long = audio.frames.toLong()): SpectrumFeatures {
        val (start, end) = FeatureRate.clampRange(audio, trimStartFrame, trimEndFrame)
        val mono = audio.mono()
        val sr = audio.sampleRate
        val region = Math.round(regionSec * sr).toInt()
        val introEnd = if (end - start > region) start + region else end
        val outroStart = if (end - start > region) end - region else start
        val whole = ltasOf(mono, sr, start, end)
        val intro = if (introEnd == end) whole else ltasOf(mono, sr, start, introEnd)
        val outro = if (outroStart == start) whole else ltasOf(mono, sr, outroStart, end)
        val texStart = max(start.toLong(), end - Math.round(textureSec * sr))
        return SpectrumFeatures(
            ltasDb = whole,
            introLtasDb = intro,
            outroLtasDb = outro,
            brightnessHz = centroidOf(mono, sr, start, end),
            textureMagnitude = textureMagnitude(audio, texStart, end.toLong()),
        )
    }

    /** LTAS (31 bands, dB) of engine frames `[startFrame, endFrame)`. */
    fun ltasDb(audio: AudioBuffer, startFrame: Long = 0L, endFrame: Long = audio.frames.toLong()): FloatArray {
        val (s, e) = FeatureRate.clampRange(audio, startFrame, endFrame)
        return ltasOf(audio.mono(), audio.sampleRate, s, e)
    }

    /** Spectral centroid (Hz) of engine frames `[startFrame, endFrame)`; 0 for silence. */
    fun centroidHz(audio: AudioBuffer, startFrame: Long = 0L, endFrame: Long = audio.frames.toLong()): Float {
        val (s, e) = FeatureRate.clampRange(audio, startFrame, endFrame)
        return centroidOf(audio.mono(), audio.sampleRate, s, e)
    }

    /** Per-bin median magnitude spectrum (513 bins) of engine frames `[startFrame, endFrame)`; see class doc. */
    fun textureMagnitude(audio: AudioBuffer, startFrame: Long, endFrame: Long): FloatArray {
        val x = FeatureRate.downmix(audio, startFrame, endFrame, resampler, TEXTURE_SAMPLE_RATE)
        val out = FloatArray(TEXTURE_BINS)
        if (x.isEmpty()) return out
        val mags = Stft(TEXTURE_FRAME, TEXTURE_HOP, Window.hann(TEXTURE_FRAME), center = true).magnitudes(x)
        val frames = mags.size
        if (frames == 0) return out
        val column = FloatArray(frames)
        for (k in 0 until TEXTURE_BINS) {
            for (t in 0 until frames) column[t] = mags[t][k]
            java.util.Arrays.sort(column)
            out[k] = if (frames % 2 == 1) column[frames / 2] else 0.5f * (column[frames / 2 - 1] + column[frames / 2])
        }
        return out
    }

    private fun ltasOf(mono: FloatArray, sr: Int, start: Int, end: Int): FloatArray {
        if (end <= start) return FloatArray(ThirdOctaveBands.BANDS) { ThirdOctaveBands.FLOOR_DB }
        val slice = if (start == 0 && end == mono.size) mono else mono.copyOfRange(start, end)
        return ThirdOctaveBands.measureDb(slice, sr, ltasFrame)
    }

    private fun centroidOf(mono: FloatArray, sr: Int, start: Int, end: Int): Float {
        if (end <= start) return 0f
        val n = ltasFrame
        val stft = Stft(n, n / 2, Window.hann(n), center = false)
        val bins = stft.bins
        val acc = DoubleArray(bins)
        val sink = StftFrameSink { _, re, im -> for (k in 0 until bins) acc[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k] }
        val len = end - start
        if (len >= n) {
            stft.process(mono, start, len, sink)
            stft.flush(sink)
        } else {
            val padded = FloatArray(n)
            System.arraycopy(mono, start, padded, 0, len)
            stft.process(padded, 0, n, sink)
            stft.flush(sink)
        }
        var num = 0.0; var den = 0.0
        for (k in 1 until bins) { num += acc[k] * k; den += acc[k] }
        return if (den > 0.0) (num / den * sr / n).toFloat() else 0f
    }

    companion object {
        const val TEXTURE_SAMPLE_RATE = 22050
        const val TEXTURE_FRAME = 1024
        const val TEXTURE_HOP = 256
        const val TEXTURE_BINS = TEXTURE_FRAME / 2 + 1

        /** Frequency in Hz of texture bin [k]. */
        fun textureBinHz(k: Int): Double = k.toDouble() * TEXTURE_SAMPLE_RATE / TEXTURE_FRAME

        /** Convenience RMS (linear) of a magnitude array. */
        fun rms(x: FloatArray): Float { var s = 0.0; for (v in x) s += v.toDouble() * v; return if (x.isEmpty()) 0f else sqrt(s / x.size).toFloat() }
    }
}
