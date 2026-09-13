package dev.muisc.analysis.features

import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.resample.Resampler
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Key-profile sets selectable in [KeyDetector]. */
enum class KeyProfileSet {
    /**
     * Krumhansl & Kessler (1982) probe-tone ratings, as tabulated in Krumhansl, "Cognitive Foundations of
     * Musical Pitch" (1990): major `6.35 2.23 3.48 2.33 4.38 4.09 2.52 5.19 2.39 3.66 2.29 2.88`,
     * minor `6.33 2.68 3.52 5.38 2.60 3.53 2.54 4.75 3.98 2.69 3.34 3.17`.
     */
    KRUMHANSL_KESSLER,
    /** Temperley (1999, "What's key for key?") Kostka–Payne based profiles. */
    TEMPERLEY,
}

/** Full result of a key detection: the [KeyEstimate] plus the tuning used and the raw correlations. */
data class KeyDetection(
    val key: KeyEstimate,
    /** Tuning offset (cents from A4 = 440 Hz) applied to the pitch-class folding. */
    val tuning: TuningEstimate,
    /** Pearson correlation of the chroma with the winning profile, -1..1 (0 for silence). */
    val correlation: Float,
    /** `correlation - secondBestCorrelation`, 0..2: the decisiveness of the estimate. */
    val margin: Float,
    /** Correlation with every key: index `t` = major with tonic t, `12 + t` = minor with tonic t (all 0 for silence). */
    val correlations: FloatArray = FloatArray(24),
) {
    /** Correlation of the analysed chroma with [key]'s profile. */
    fun correlationOf(key: MusicalKey): Float = correlations[if (key.mode == Mode.MAJOR) key.tonic else 12 + key.tonic]
    override fun equals(other: Any?): Boolean = other is KeyDetection && other.key == key && other.tuning == tuning && other.correlation == correlation
    override fun hashCode(): Int = key.hashCode() * 31 + correlation.hashCode()
}

/** Keys of the intro / outro regions; `null` means "same as the whole-track key" (or region not measurable). */
data class SectionKeys(val introKey: KeyEstimate?, val outroKey: KeyEstimate?)

/**
 * Musical key detection by chroma / key-profile correlation.
 *
 * Pipeline (on the 22.05 kHz mono downmix of the requested range):
 *  1. sinusoidal peaks per STFT frame ([SpectralPeakPicker]: frame 8192, hop 2048, 55 Hz .. 5 kHz);
 *  2. tuning estimate ([TuningEstimator]) from the same peaks; the folding below is shifted by it so a
 *     track tuned to A4 = 446 Hz still lands on the right pitch classes;
 *  3. **harmonic pitch-class profile** (Gómez 2006, HPCP): a peak at frequency `f` with magnitude `m` adds
 *     `m * harmonicDecay^h` to the pitch class of `f / (h + 1)` for `h = 0 until harmonics` (default: f, f/2,
 *     f/3, f/4 with weights 1, 0.6, 0.36, 0.216), pitch class = `round(12 log2(f / 440) + 69 - tuning/100)
 *     mod 12` (C = 0). Contributions are summed over all frames (loud frames weigh more);
 *  4. **opening emphasis**: pitch-class content alone cannot separate a key from its relative (the pop
 *     progressions vi–IV–I–V and i–VI–III–VII have identical chroma); listeners resolve this from where the
 *     music *starts*. The chroma of the first [openingSec] seconds that contain tonal peaks is normalised and
 *     added with weight [openingWeight] to the normalised whole-range chroma. This only makes sense for a
 *     range that begins where the music begins, so it is on for [detect] / [detectRange] (whole track,
 *     intro) and off for [keyForRange] (arbitrary sections: a section's first chord is not a cue). Set
 *     `openingWeight = 0` to disable it everywhere;
 *  5. the 12-bin chroma is normalised to sum 1 and Pearson-correlated with the 24 rotations of the major /
 *     minor profiles of [profiles] (Krumhansl & Schmuckler key-finding algorithm). The best key wins.
 *
 * [KeyEstimate.strength] is the correlation mapped linearly to 0..1: `(r + 1) / 2` (0.5 = uncorrelated,
 * 1 = perfect fit); [KeyEstimate.secondStrength] likewise for the runner-up, so `strength - secondStrength`
 * is half the correlation margin. Silence yields C major with strength 0 and an all-zero chroma.
 *
 * Every frame position is an engine-rate frame of the input buffer; nothing here throws on short or silent
 * input.
 */
class KeyDetector(
    val profiles: KeyProfileSet = KeyProfileSet.KRUMHANSL_KESSLER,
    val picker: SpectralPeakPicker = SpectralPeakPicker(),
    val tuningEstimator: TuningEstimator = TuningEstimator(),
    val harmonics: Int = 4,
    val harmonicDecay: Double = 0.6,
    val openingSec: Double = 2.0,
    val openingWeight: Double = 0.75,
    val resampler: Resampler = Resampler(),
) {
    init { require(harmonics >= 1 && harmonicDecay > 0 && openingSec >= 0 && openingWeight >= 0) }

    private val major: DoubleArray
    private val minor: DoubleArray
    init {
        when (profiles) {
            KeyProfileSet.KRUMHANSL_KESSLER -> { major = KK_MAJOR; minor = KK_MINOR }
            KeyProfileSet.TEMPERLEY -> { major = TEMPERLEY_MAJOR; minor = TEMPERLEY_MINOR }
        }
    }

    /** Key of the whole buffer. */
    fun detect(audio: AudioBuffer): KeyDetection = detectRange(audio, 0L, audio.frames.toLong())

    /**
     * Key of engine frames `[startFrame, endFrame)`; the range is clamped to the buffer. [emphasiseOpening]
     * applies the opening emphasis (step 4) — leave it on when the range starts where the music starts.
     */
    fun detectRange(audio: AudioBuffer, startFrame: Long, endFrame: Long, emphasiseOpening: Boolean = true): KeyDetection {
        val mono = FeatureRate.downmix(audio, startFrame, endFrame, resampler, picker.sampleRate)
        val peaks = picker.analyze(mono)
        val tuning = tuningEstimator.estimate(peaks)
        val chroma = chromaFromPeaks(peaks, tuning.cents, emphasiseOpening)
        return fromChroma(chroma, tuning)
    }

    /** Section-level key: [KeyEstimate] of engine frames `[startFrame, endFrame)` (no opening emphasis). */
    fun keyForRange(audio: AudioBuffer, startFrame: Long, endFrame: Long): KeyEstimate =
        detectRange(audio, startFrame, endFrame, emphasiseOpening = false).key

    /**
     * Intro key (first [regionSec] seconds after [trimStartFrame]) and outro key (last [regionSec] seconds
     * before [trimEndFrame]) for [dev.muisc.analysis.model.TrackAnalysis.introKey] / `outroKey`.
     *
     * A region's key is reported only when it is a *different* key that fits the region's chroma clearly
     * better than [wholeKey] does: `r(regionKey) - r(wholeKey) > minMargin` on the region's correlations.
     * Relative-major / minor readings of the same pitch-class content (correlation margins of a few
     * hundredths) are therefore folded into "same as the whole track" instead of producing a spurious key
     * change. `null` also when the trimmed range is not longer than the region (it would be the whole track)
     * or when the region is silent. The intro uses the opening emphasis, the outro does not.
     */
    fun sectionKeys(audio: AudioBuffer, trimStartFrame: Long, trimEndFrame: Long, wholeKey: KeyEstimate, regionSec: Double = 30.0, minMargin: Float = 0.1f): SectionKeys {
        val (start, end) = FeatureRate.clampRange(audio, trimStartFrame, trimEndFrame)
        val region = Math.round(regionSec * audio.sampleRate)
        if (end - start <= region) return SectionKeys(null, null)
        val intro = detectRange(audio, start.toLong(), start + region, emphasiseOpening = true)
        val outro = detectRange(audio, end - region, end.toLong(), emphasiseOpening = false)
        fun differs(d: KeyDetection): KeyEstimate? =
            d.key.takeIf { it.strength > 0f && it.key != wholeKey.key && d.correlation - d.correlationOf(wholeKey.key) > minMargin }
        return SectionKeys(introKey = differs(intro), outroKey = differs(outro))
    }

    /**
     * Harmonic pitch-class profile of [peaks] (see class doc, steps 3–4), normalised to sum 1 (all zeros when
     * there are no peaks). [tuningCents] shifts the folding grid; [emphasiseOpening] enables step 4.
     */
    fun chromaFromPeaks(peaks: SpectralPeaks, tuningCents: Float = 0f, emphasiseOpening: Boolean = true): FloatArray {
        val all = DoubleArray(12)
        val opening = DoubleArray(12)
        if (peaks.isEmpty) return FloatArray(12)
        val openingFrames = if (emphasiseOpening && openingSec > 0 && peaks.hopSec > 0) ceil(openingSec / peaks.hopSec).toInt() else 0
        val openingEnd = peaks.frame[0] + openingFrames
        val shift = 69.0 - tuningCents / 100.0
        val weights = DoubleArray(harmonics) { harmonicDecay.pow(it) }
        for (i in 0 until peaks.size) {
            val f = peaks.freqHz[i].toDouble()
            if (f <= 0.0) continue
            val m = peaks.magnitude[i].toDouble()
            val inOpening = peaks.frame[i] < openingEnd
            val base = 12.0 * ln(f / 440.0) / LN2 + shift
            for (h in 0 until harmonics) {
                // pitch of f / (h + 1) in semitones = base - 12 log2(h + 1)
                val semis = base - SUBHARMONIC_SEMITONES[h]
                val pc = Math.floorMod(Math.round(semis).toInt(), 12)
                val w = m * weights[h]
                all[pc] += w
                if (inOpening) opening[pc] += w
            }
        }
        val out = DoubleArray(12)
        val sumAll = all.sum()
        val sumOpen = opening.sum()
        if (sumAll > 0.0) for (pc in 0 until 12) out[pc] += all[pc] / sumAll
        if (openingWeight > 0.0 && sumOpen > 0.0) for (pc in 0 until 12) out[pc] += openingWeight * opening[pc] / sumOpen
        val s = out.sum()
        return FloatArray(12) { if (s > 0.0) (out[it] / s).toFloat() else 0f }
    }

    /** Key-profile matching of a 12-bin chroma (C = 0) alone; [tuning] is only carried into the result. */
    fun fromChroma(chroma: FloatArray, tuning: TuningEstimate = TuningEstimate.NONE): KeyDetection {
        require(chroma.size == 12) { "chroma must have 12 bins" }
        val c = DoubleArray(12) { chroma[it].toDouble() }
        val sum = c.sum()
        val normChroma = FloatArray(12) { if (sum > 0.0) (c[it] / sum).toFloat() else 0f }
        val cMean = sum / 12.0
        var cVar = 0.0
        for (v in c) cVar += (v - cMean) * (v - cMean)
        if (sum <= 0.0 || cVar <= 0.0) {
            return KeyDetection(KeyEstimate(MusicalKey(0, Mode.MAJOR), 0f, null, 0f, normChroma), tuning, 0f, 0f, FloatArray(24))
        }
        val r = DoubleArray(24)
        for (t in 0 until 12) {
            r[t] = pearson(c, cMean, cVar, major, t)
            r[12 + t] = pearson(c, cMean, cVar, minor, t)
        }
        var best = 0
        for (i in 1 until 24) if (r[i] > r[best]) best = i
        var second = if (best == 0) 1 else 0
        for (i in 0 until 24) if (i != best && r[i] > r[second]) second = i
        val bestKey = MusicalKey(best % 12, if (best < 12) Mode.MAJOR else Mode.MINOR)
        val secondKey = MusicalKey(second % 12, if (second < 12) Mode.MAJOR else Mode.MINOR)
        val estimate = KeyEstimate(
            key = bestKey,
            strength = strength(r[best]),
            secondBest = secondKey,
            secondStrength = strength(r[second]),
            chroma = normChroma,
        )
        return KeyDetection(estimate, tuning, r[best].toFloat(), (r[best] - r[second]).toFloat(), FloatArray(24) { r[it].toFloat() })
    }

    private fun strength(r: Double): Float = ((r + 1.0) / 2.0).coerceIn(0.0, 1.0).toFloat()

    /** Pearson correlation of [c] with [profile] rotated so that its tonic sits on pitch class [tonic]. */
    private fun pearson(c: DoubleArray, cMean: Double, cVar: Double, profile: DoubleArray, tonic: Int): Double {
        var pMean = 0.0
        for (v in profile) pMean += v
        pMean /= 12.0
        var cov = 0.0; var pVar = 0.0
        for (pc in 0 until 12) {
            val p = profile[Math.floorMod(pc - tonic, 12)] - pMean
            cov += (c[pc] - cMean) * p
            pVar += p * p
        }
        return cov / sqrt(cVar * pVar)
    }

    companion object {
        private val LN2 = ln(2.0)
        private val SUBHARMONIC_SEMITONES = DoubleArray(16) { 12.0 * ln((it + 1).toDouble()) / LN2 }

        val KK_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val KK_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val TEMPERLEY_MAJOR = doubleArrayOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0)
        val TEMPERLEY_MINOR = doubleArrayOf(5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0)
    }
}
