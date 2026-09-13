package dev.muisc.analysis.structure

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.Stft
import dev.muisc.dsp.fft.StftFrameSink
import dev.muisc.dsp.fft.power
import dev.muisc.dsp.mel.MelFilterbank
import dev.muisc.dsp.resample.Resampler
import dev.muisc.dsp.window.Window
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Beat-synchronous features: one row per beat of a [BeatGrid], each aggregating (mean over) the STFT frames whose
 * centre falls inside `[beat_k, beat_k+1)` (the last beat extends by one beat period). Beats that contain no frame
 * centre — outside the analysed region, or the zero-length beat a rigid grid places at the very end — are
 * *invalid* ([frameCounts]`[k] == 0`): their rows hold neutral values (zero chroma, [FLOOR_DB] energies, zero flux)
 * and they are excluded from the track statistics used for normalisation.
 *
 * Stored blocks (raw, interpretable units):
 * - [chroma] `[beat][12]`, pitch class C = 0. Sum of STFT magnitudes over the bins between [CHROMA_MIN_HZ] and
 *   [CHROMA_MAX_HZ] folded to their nearest equal-tempered pitch class (A4 = 440 Hz), then L2-normalised per beat
 *   (unit length, or all zero for silence / invalid beats).
 * - [melDb] `[beat][nMels]`, `10 log10` of the mean mel-band power (Slaney [MelFilterbank], 0 Hz .. Nyquist) in
 *   dB relative to full scale, floored at [FLOOR_DB].
 * - [bandsDb] `[beat][4]`, dB of the mean power in the four DJ bands sub (< 60 Hz), bass (60–250 Hz),
 *   mid (250–4000 Hz) and high (> 4 kHz), split on STFT bins; floored at [FLOOR_DB].
 * - [rms] mean-square amplitude of the beat, as an RMS in the [-1, 1] sample scale (0 for invalid beats), and
 *   [energyDb] `= 20 log10(rms)` floored at [FLOOR_DB]. The RMS is recovered from the spectrum by Parseval's
 *   theorem (window energy compensated), so it is directly comparable to `AudioBuffer.rms()`.
 * - [flux] / [hfFlux]: mean positive spectral flux in dB per frame per band (Böck & Widmer 2013 style,
 *   half-wave rectified difference of consecutive log-mel frames, each band difference capped at [FLUX_CAP_DB]).
 *   Before differencing, the log-mel values are floored at `track maximum − [FLUX_RANGE_DB]` (librosa's `top_db`
 *   convention) so that near-silent bands — where a −120 dB floor would turn faint transient splatter into huge dB
 *   steps — contribute nothing. [flux] averages all mel bands, [hfFlux] only the bands whose centre is above
 *   [HF_MIN_HZ] (hats, snares, claps — the components a tonal bass line or kick does not produce). Typical values:
 *   0 for sustained pads or silence, 2–5 dB for a full drum kit; [hfFlux] is the internal "drum presence" cue
 *   used by [BeatActivity].
 *
 * [vectors] `[beat][12 + nMels + 4 + 1]` is the normalised composite used for the self-similarity matrix: the unit
 * chroma, the z-scored (over valid beats) mel dB scaled by `1/sqrt(nMels)`, the z-scored band dB scaled by `1/2`
 * and the z-scored energy dB — four blocks of comparable norm, so that the cosine similarity between two beats
 * weights harmony, timbre, spectral balance and level about equally.
 */
class BeatSyncFeatures(
    val beatCount: Int,
    val chroma: Array<FloatArray>,
    val melDb: Array<FloatArray>,
    val bandsDb: Array<FloatArray>,
    val rms: FloatArray,
    val energyDb: FloatArray,
    val flux: FloatArray,
    val hfFlux: FloatArray,
    val frameCounts: IntArray,
    val vectors: Array<FloatArray>,
) {
    /** Whether beat [k] aggregated at least one STFT frame. */
    fun isValid(k: Int): Boolean = k in 0 until beatCount && frameCounts[k] > 0

    /** Number of valid beats. */
    val validCount: Int get() = frameCounts.count { it > 0 }

    companion object {
        /** Floor for every dB quantity (digital silence). */
        const val FLOOR_DB = -120f
        const val CHROMA_MIN_HZ = 55.0
        const val CHROMA_MAX_HZ = 5000.0
        /** Mel bands centred above this frequency feed [hfFlux]. */
        const val HF_MIN_HZ = 1500.0
        /** Cap of a single band's positive difference between consecutive frames (bounds the silence→music step). */
        const val FLUX_CAP_DB = 30f
        /** Dynamic range below the track's loudest log-mel value that takes part in the flux (`top_db`). */
        const val FLUX_RANGE_DB = 60f

        fun empty(beatCount: Int = 0, nMels: Int = BeatSyncFeatureExtractor.DEFAULT_MELS): BeatSyncFeatures = BeatSyncFeatures(
            beatCount,
            Array(beatCount) { FloatArray(12) },
            Array(beatCount) { FloatArray(nMels) { FLOOR_DB } },
            Array(beatCount) { FloatArray(4) { FLOOR_DB } },
            FloatArray(beatCount),
            FloatArray(beatCount) { FLOOR_DB },
            FloatArray(beatCount),
            FloatArray(beatCount),
            IntArray(beatCount),
            Array(beatCount) { FloatArray(12 + nMels + 4 + 1) },
        )
    }
}

/**
 * Computes [BeatSyncFeatures] from engine-rate audio: mono downmix of `[trimStartFrame, trimEndFrame)` →
 * [Resampler] to [analysisRate] → streaming [Stft] ([frameSize] / [hop], Hann) → per-frame chroma / mel / band /
 * energy / flux → mean per beat. Frame `t` is centred on analysis sample `t * hop`, i.e. on engine frame
 * `trimStartFrame + t * hop * engineRate / analysisRate`, which decides the beat it belongs to. Nothing is allocated
 * per frame beyond the fixed scratch buffers; the sink runs once per STFT frame, never per sample.
 *
 * Never throws: an empty grid or an empty region returns [BeatSyncFeatures.empty] (all beats invalid).
 */
class BeatSyncFeatureExtractor(
    val analysisRate: Int = DEFAULT_ANALYSIS_RATE,
    val frameSize: Int = 2048,
    val hop: Int = 512,
    val nMels: Int = DEFAULT_MELS,
    val resampler: Resampler = Resampler(),
) {
    private val bins = frameSize / 2 + 1
    private val window = Window.hann(frameSize)
    /** Parseval scale: `Σ|X_k|²` over the half spectrum → mean-square amplitude of the un-windowed input. */
    private val powerScale: Float = run { var s = 0.0; for (w in window) s += w.toDouble() * w; (2.0 / (frameSize * s)).toFloat() }
    private val mel = MelFilterbank(frameSize, analysisRate, nMels, 0.0, analysisRate / 2.0)
    private val hfBands: IntArray = (0 until nMels).filter { mel.centerFrequencies[it] >= BeatSyncFeatures.HF_MIN_HZ }.toIntArray()
    /** Pitch class (0 = C) of each FFT bin in the chroma range, or -1 outside it. */
    private val chromaClass = IntArray(bins) { k ->
        val f = k.toDouble() * analysisRate / frameSize
        if (f < BeatSyncFeatures.CHROMA_MIN_HZ || f > BeatSyncFeatures.CHROMA_MAX_HZ) -1
        else Math.floorMod((12.0 * log2(f / 440.0)).roundToInt() + 9, 12)
    }
    private val bandOfBin = IntArray(bins) { k ->
        val f = k.toDouble() * analysisRate / frameSize
        when { f < 60.0 -> 0; f < 250.0 -> 1; f < 4000.0 -> 2; else -> 3 }
    }

    fun extract(audio: AudioBuffer, grid: BeatGrid, trimStartFrame: Long = 0L, trimEndFrame: Long = audio.frames.toLong()): BeatSyncFeatures {
        val n = grid.beatCount
        if (n == 0) return BeatSyncFeatures.empty(0, nMels)
        val engineRate = audio.sampleRate
        val start = trimStartFrame.coerceIn(0L, audio.frames.toLong()).toInt()
        val end = trimEndFrame.coerceIn(start.toLong(), audio.frames.toLong()).toInt()
        if (end - start < 1) return BeatSyncFeatures.empty(n, nMels)

        val period = grid.periodFrames(engineRate).takeIf { it > 0 } ?: 1.0
        val beatStart = grid.beatFrames
        val beatEnd = LongArray(n) { if (it < n - 1) beatStart[it + 1] else beatStart[it] + Math.round(period) }

        val monoFull = audio.mono()
        val region = if (start == 0 && end == audio.frames) monoFull else monoFull.copyOfRange(start, end)
        val mono = if (engineRate == analysisRate) region else resampler.resample(region, engineRate, analysisRate)

        val chromaAcc = Array(n) { FloatArray(12) }
        val melAcc = Array(n) { FloatArray(nMels) }
        val bandAcc = Array(n) { FloatArray(4) }
        val energyAcc = FloatArray(n)
        val fluxAcc = FloatArray(n)
        val hfFluxAcc = FloatArray(n)
        val counts = IntArray(n)

        val stft = Stft(frameSize, hop, window, center = true)
        val frameCount = stft.frameCount(mono.size)
        val logMel = Array(frameCount) { FloatArray(nMels) }
        val beatOfFrame = IntArray(frameCount) { -1 }
        val pow = FloatArray(bins)
        val melCur = FloatArray(nMels)
        val framePos = hop.toDouble() * engineRate / analysisRate // engine frames per STFT frame
        val cls = chromaClass
        val bob = bandOfBin
        val hf = hfBands
        val scale = powerScale
        var k = 0
        val sink = StftFrameSink { t, re, im ->
            power(re, im, pow, bins)
            for (b in 0 until bins) pow[b] *= scale
            mel.apply(pow, melCur)
            if (t < frameCount) { val lm = logMel[t]; for (m in 0 until nMels) lm[m] = dbFloor(10f * log10(melCur[m] + POWER_EPS)) }

            val pos = start + Math.round(t * framePos)
            while (k < n && pos >= beatEnd[k]) k++
            if (k < n && pos >= beatStart[k]) {
                if (t < frameCount) beatOfFrame[t] = k
                val c = chromaAcc[k]
                val mAcc = melAcc[k]
                val bAcc = bandAcc[k]
                var e = 0f
                for (b in 0 until bins) {
                    val p = pow[b]
                    e += p
                    bAcc[bob[b]] += p
                    val pc = cls[b]
                    if (pc >= 0) c[pc] += sqrt(p)
                }
                for (m in 0 until nMels) mAcc[m] += melCur[m]
                energyAcc[k] += e
                counts[k]++
            }
        }
        stft.process(mono, 0, mono.size, sink)
        stft.flush(sink)

        // Spectral flux post-pass: log-mel floored at (track max − FLUX_RANGE_DB), half-wave rectified, capped.
        var maxDb = BeatSyncFeatures.FLOOR_DB
        for (t in 0 until frameCount) { val lm = logMel[t]; for (m in 0 until nMels) if (lm[m] > maxDb) maxDb = lm[m] }
        val floor = maxDb - BeatSyncFeatures.FLUX_RANGE_DB
        val cap = BeatSyncFeatures.FLUX_CAP_DB
        for (t in 1 until frameCount) {
            val kb = beatOfFrame[t]
            if (kb < 0) continue
            val cur = logMel[t]; val prev = logMel[t - 1]
            var fl = 0f
            for (m in 0 until nMels) {
                val a = if (cur[m] > floor) cur[m] else floor
                val b = if (prev[m] > floor) prev[m] else floor
                val d = a - b
                if (d > 0f) fl += if (d > cap) cap else d
            }
            var hfl = 0f
            for (m in hf) {
                val a = if (cur[m] > floor) cur[m] else floor
                val b = if (prev[m] > floor) prev[m] else floor
                val d = a - b
                if (d > 0f) hfl += if (d > cap) cap else d
            }
            fluxAcc[kb] += fl / nMels
            hfFluxAcc[kb] += if (hf.isEmpty()) 0f else hfl / hf.size
        }

        // Finalise per beat.
        val chroma = Array(n) { FloatArray(12) }
        val melDb = Array(n) { FloatArray(nMels) { BeatSyncFeatures.FLOOR_DB } }
        val bandsDb = Array(n) { FloatArray(4) { BeatSyncFeatures.FLOOR_DB } }
        val rms = FloatArray(n)
        val energyDb = FloatArray(n) { BeatSyncFeatures.FLOOR_DB }
        val flux = FloatArray(n)
        val hfFlux = FloatArray(n)
        for (b in 0 until n) {
            val c = counts[b]
            if (c == 0) continue
            val inv = 1f / c
            var norm = 0f
            for (i in 0 until 12) { val v = chromaAcc[b][i]; norm += v * v }
            norm = sqrt(norm)
            for (i in 0 until 12) chroma[b][i] = if (norm > 1e-12f) chromaAcc[b][i] / norm else 0f
            for (m in 0 until nMels) melDb[b][m] = dbFloor(10f * log10(melAcc[b][m] * inv + POWER_EPS))
            for (i in 0 until 4) bandsDb[b][i] = dbFloor(10f * log10(bandAcc[b][i] * inv + POWER_EPS))
            val ms = energyAcc[b] * inv
            rms[b] = sqrt(ms)
            energyDb[b] = dbFloor(10f * log10(ms + POWER_EPS))
            flux[b] = fluxAcc[b] * inv
            hfFlux[b] = hfFluxAcc[b] * inv
        }
        val vectors = composeVectors(n, counts, chroma, melDb, bandsDb, energyDb, nMels)
        return BeatSyncFeatures(n, chroma, melDb, bandsDb, rms, energyDb, flux, hfFlux, counts, vectors)
    }

    private fun dbFloor(v: Float): Float = if (v < BeatSyncFeatures.FLOOR_DB || v.isNaN()) BeatSyncFeatures.FLOOR_DB else v

    companion object {
        const val DEFAULT_ANALYSIS_RATE = 22050
        const val DEFAULT_MELS = 20
        private const val POWER_EPS = 1e-12f

        /** Builds the normalised composite vectors (see [BeatSyncFeatures.vectors]); invalid beats get zero vectors. */
        internal fun composeVectors(n: Int, counts: IntArray, chroma: Array<FloatArray>, melDb: Array<FloatArray>, bandsDb: Array<FloatArray>, energyDb: FloatArray, nMels: Int): Array<FloatArray> {
            val dim = 12 + nMels + 4 + 1
            val out = Array(n) { FloatArray(dim) }
            val melMean = FloatArray(nMels); val melStd = FloatArray(nMels)
            val bandMean = FloatArray(4); val bandStd = FloatArray(4)
            zStats(n, counts, nMels, { b, m -> melDb[b][m] }, melMean, melStd)
            zStats(n, counts, 4, { b, m -> bandsDb[b][m] }, bandMean, bandStd)
            val eMean = FloatArray(1); val eStd = FloatArray(1)
            zStats(n, counts, 1, { b, _ -> energyDb[b] }, eMean, eStd)
            val melScale = 1f / sqrt(nMels.toFloat())
            for (b in 0 until n) {
                if (counts[b] == 0) continue
                val v = out[b]
                System.arraycopy(chroma[b], 0, v, 0, 12)
                var o = 12
                for (m in 0 until nMels) v[o++] = z(melDb[b][m], melMean[m], melStd[m]) * melScale
                for (i in 0 until 4) v[o++] = z(bandsDb[b][i], bandMean[i], bandStd[i]) * 0.5f
                v[o] = z(energyDb[b], eMean[0], eStd[0])
            }
            return out
        }

        private fun z(x: Float, mean: Float, std: Float): Float = if (std > 1e-6f) (x - mean) / std else 0f

        private inline fun zStats(n: Int, counts: IntArray, dims: Int, get: (Int, Int) -> Float, mean: FloatArray, std: FloatArray) {
            var valid = 0
            for (b in 0 until n) if (counts[b] > 0) valid++
            if (valid == 0) return
            for (m in 0 until dims) {
                var s = 0.0
                for (b in 0 until n) if (counts[b] > 0) s += get(b, m)
                val mu = s / valid
                var q = 0.0
                for (b in 0 until n) if (counts[b] > 0) { val d = get(b, m) - mu; q += d * d }
                mean[m] = mu.toFloat()
                std[m] = sqrt(q / valid).toFloat()
            }
        }
    }
}
