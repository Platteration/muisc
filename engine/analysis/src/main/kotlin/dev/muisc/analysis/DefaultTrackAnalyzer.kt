package dev.muisc.analysis

import dev.muisc.analysis.features.BarFeatureExtractor
import dev.muisc.analysis.features.FeatureRate
import dev.muisc.analysis.features.KeyDetector
import dev.muisc.analysis.features.LoudnessAnalyzer
import dev.muisc.analysis.features.SilenceInfo
import dev.muisc.analysis.features.SilenceTrimmer
import dev.muisc.analysis.features.SpectrumAnalyzer
import dev.muisc.analysis.features.SpectrumFeatures
import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Cues
import dev.muisc.analysis.model.GridKind
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.analysis.rhythm.RhythmAnalyzer
import dev.muisc.analysis.rhythm.RhythmResult
import dev.muisc.analysis.structure.BeatActivity
import dev.muisc.analysis.structure.StructureResult
import dev.muisc.analysis.structure.TrackStructureAnalyzer
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.PcmStream
import dev.muisc.dsp.hpss.Hpss
import dev.muisc.dsp.hpss.HpssResult
import dev.muisc.dsp.resample.Resampler
import kotlin.math.min

/**
 * Tunables of [DefaultTrackAnalyzer].
 *
 * @param analysisRate sample rate of the shared mono downmix every rhythmic / tonal stage runs on. The
 *   sub-packages are calibrated for (and partly hard-wired to) [FeatureRate.SAMPLE_RATE] = 22 050 Hz, so
 *   this must currently equal that value; it is a parameter so the constraint is visible, not hidden.
 * @param computeStructure run the structure stage (sections, intro / outro types, cues). When off, the
 *   silence-trimmer flags still yield [OutroType.FADE_OUT] / [OutroType.HARD_STOP] and everything else is UNKNOWN.
 * @param computeTexture compute [TrackAnalysis.textureMagnitude] (the outro's median magnitude spectrum for
 *   TextureCarry). LTAS and brightness are always computed.
 * @param maxSeconds analyse at most this many seconds from the start of the track (null = the whole track). A
 *   truncated analysis reports the analysed length as `totalFrames` and sets `extra["truncated"] = 1.0`.
 * @param regionSec length of the intro / outro regions for `introKey` / `outroKey` / `introLtasDb` / `outroLtasDb`.
 */
data class AnalysisOptions(
    val analysisRate: Int = FeatureRate.SAMPLE_RATE,
    val computeStructure: Boolean = true,
    val computeTexture: Boolean = true,
    val maxSeconds: Double? = null,
    val regionSec: Double = 30.0,
) {
    init {
        require(analysisRate > 0) { "analysisRate must be positive" }
        require(maxSeconds == null || maxSeconds > 0) { "maxSeconds must be positive or null" }
        require(regionSec > 0) { "regionSec must be positive" }
    }
}

/**
 * The production [TrackAnalyzer]: assembles the rhythm, feature and structure packages into one pipeline and
 * builds the [TrackAnalysis].
 *
 * Pipeline (progress stages in brackets; all frame positions that leave this class are engine-rate frames of
 * the input buffer, i.e. `audio.sampleRate`):
 *  1. [`trim`] [SilenceTrimmer] on the engine-rate buffer: `trimStartFrame` / `trimEndFrame`, fade-out and
 *     hard-stop flags.
 *  2. [`loudness`] [LoudnessAnalyzer] on the engine-rate buffer, all channels, from frame 0 (so
 *     `shortTermLufs[k]` is the window starting at `k * 0.1` s of track time).
 *  3. [`downmix`] **one** resample of the whole buffer to [AnalysisOptions.analysisRate] with the dsp
 *     [Resampler] (zero-phase: time `t` in the input is time `t` in the downmix): L/R for stereo input (the
 *     bar-feature vocal heuristic needs the stereo image), the single channel for mono. The mono downmix used
 *     by every rhythmic / tonal stage is the average of those resampled channels (the resampler is linear, so
 *     this is the resampled mono mix). Nothing below resamples again; outputs are converted back with
 *     `engineFrame = analysisFrame * engineRate / analysisRate`.
 *  4. [`rhythm`] [RhythmAnalyzer] on the trimmed range → tempo, beat grid (converted to engine frames, keeping
 *     the beat count so bar / section indices stay valid), onsets (→ `onsetFrames`, clamped to the trim range).
 *  5. [`hpss`] one [Hpss] separation of the analysis-rate signal (frame 1024 / hop 256, the geometry
 *     [BarFeatureExtractor] expects), handed to the bar features so they do not separate again. Skipped when
 *     there is no grid (nothing needs it then). Stereo input is separated as L/R so the vocal heuristic can use
 *     centre dominance (a pad-only intro otherwise reads as "vocal" on the mono path).
 *  6. [`key`] [KeyDetector] (with its [dev.muisc.analysis.features.TuningEstimator]) over the trimmed range,
 *     plus `introKey` / `outroKey` over the first / last [AnalysisOptions.regionSec] when they differ.
 *  7. [`bars`] [BarFeatureExtractor] with the shared HPSS result → [BarFeatures].
 *  8. [`spectrum`] [SpectrumAnalyzer] on the engine-rate buffer (LTAS needs the 20 kHz band): `ltasDb`,
 *     `introLtasDb`, `outroLtasDb`, `brightnessHz`, and `textureMagnitude` (last 15 s, optional).
 *  9. [`structure`] [TrackStructureAnalyzer] facade with the bar features and the silence flags → sections,
 *     intro / outro types, cues.
 * 10. [`assemble`] → [TrackAnalysis] (`version = CURRENT_VERSION`, `analysisMillis` = wall clock).
 *
 * Robustness: digital silence, input shorter than a few seconds, mono input and any sample rate (44.1 / 48 kHz)
 * all produce a [TrackAnalysis] without throwing — with an EMPTY grid, confidence 0 and UNKNOWN types where
 * nothing could be measured. The analysis is deterministic for a given input.
 *
 * **Memory (streaming overload).** [analyze] with a [PcmStream] reads the stream once into a planar float
 * buffer — sized exactly from `totalFrames` when the stream knows it (capped by [AnalysisOptions.maxSeconds]),
 * otherwise grown geometrically — and then runs the buffer pipeline. Peak memory is therefore bounded by the
 * decoded float buffer plus the fixed working set of the stages (the 22.05 kHz signal is 1/2 of a 44.1 kHz
 * buffer, the mono mix 1/4, the HPSS components another 1×); no second copy of the track is ever made,
 * unlike [dev.muisc.audio.readAll], which holds chunks and the final buffer at the same time.
 *
 * `extra` carries diagnostics that shipped strategies must not rely on: `tuningConfidence`, `keyCorrelation`,
 * `keyMargin`, `downbeatConfidence`, `gridResidualMs`, `trackedBeats`, `tempoConfidence`, `fadeOut`,
 * `hardStop`, `fadeStartFrame`, `endLevelDeltaDb`, `leadingSilenceMs`, `trailingSilenceMs`, `analysisRate`,
 * `truncated`.
 */
class DefaultTrackAnalyzer(
    val options: AnalysisOptions = AnalysisOptions(),
    val silenceTrimmer: SilenceTrimmer = SilenceTrimmer(),
    val loudnessAnalyzer: LoudnessAnalyzer = LoudnessAnalyzer(),
    val rhythmAnalyzer: RhythmAnalyzer = RhythmAnalyzer(),
    val keyDetector: KeyDetector = KeyDetector(),
    val barFeatureExtractor: BarFeatureExtractor = BarFeatureExtractor(),
    val spectrumAnalyzer: SpectrumAnalyzer = SpectrumAnalyzer(regionSec = options.regionSec),
    val structureAnalyzer: TrackStructureAnalyzer = TrackStructureAnalyzer(),
    val resampler: Resampler = Resampler(),
) : TrackAnalyzer {
    init {
        require(options.analysisRate == FeatureRate.SAMPLE_RATE) {
            "analysisRate must be ${FeatureRate.SAMPLE_RATE} Hz (the rhythm / feature packages are calibrated for it), was ${options.analysisRate}"
        }
    }

    /** The HPSS geometry [BarFeatureExtractor] uses by default; shared so the separation runs once. */
    private val hpss: Hpss get() = barFeatureExtractor.hpss

    override fun analyze(audio: AudioBuffer, sourceId: String, fingerprint: String, progress: AnalysisProgress): TrackAnalysis {
        val t0 = System.nanoTime()
        val engineRate = audio.sampleRate
        val (input, truncated) = truncate(audio)
        val totalFrames = input.frames.toLong()
        val extra = LinkedHashMap<String, Double>()

        // 1. silence trim + ending flags (engine rate)
        progress.onProgress(STAGE_TRIM, 0.02)
        val silence = silenceTrimmer.analyze(input)
        val trimStart = silence.trimStartFrame
        val trimEnd = silence.trimEndFrame

        // 2. loudness (engine rate, all channels, from frame 0)
        progress.onProgress(STAGE_LOUDNESS, 0.08)
        val loudness = loudnessAnalyzer.analyze(input, 0L, totalFrames)

        // 3. one resample to the analysis rate (L/R for stereo input, mono otherwise), shared by everything below;
        //    the mono downmix is derived from it (the resampler is linear, so this equals resampling the mono mix)
        progress.onProgress(STAGE_DOWNMIX, 0.18)
        val analysisRate = options.analysisRate
        val work = toAnalysisRate(input, analysisRate)
        val mono = if (work.channelCount == 1) work else AudioBuffer.mono(analysisRate, work.mono())
        val aStart = toAnalysisFrame(trimStart, engineRate, analysisRate, mono.frames)
        val aEnd = toAnalysisFrame(trimEnd, engineRate, analysisRate, mono.frames).coerceAtLeast(aStart)

        // 4. rhythm
        progress.onProgress(STAGE_RHYTHM, 0.28)
        val rhythm = rhythmAnalyzer.analyze(mono, aStart, aEnd)
        val analysisGrid = rhythm.grid
        val grid = toEngineGrid(analysisGrid, analysisRate, engineRate)
        val onsetFrames = onsetFrames(rhythm, engineRate, trimStart, trimEnd)

        // 5. HPSS once on the analysis-rate signal (only needed by the bar features, which need a grid)
        progress.onProgress(STAGE_HPSS, 0.48)
        val hpssResult: HpssResult? = if (!analysisGrid.isEmpty && work.frames > 0) hpss.separate(work) else null

        // 6. key + tuning (+ intro / outro keys)
        progress.onProgress(STAGE_KEY, 0.62)
        val keyDetection = keyDetector.detectRange(mono, aStart, aEnd, emphasiseOpening = true)
        val sectionKeys = keyDetector.sectionKeys(mono, aStart, aEnd, keyDetection.key, options.regionSec)

        // 7. bar features
        progress.onProgress(STAGE_BARS, 0.72)
        val bars: BarFeatures = if (hpssResult != null) barFeatureExtractor.extract(work, analysisGrid, hpssResult) else BarFeatures()

        // 8. spectrum (engine rate for the LTAS; texture on the last 15 s)
        progress.onProgress(STAGE_SPECTRUM, 0.80)
        val spectrum = spectrum(input, trimStart, trimEnd)

        // 9. structure
        progress.onProgress(STAGE_STRUCTURE, 0.88)
        val aFadeStart = if (silence.fadeStartFrame >= 0) toAnalysisFrame(silence.fadeStartFrame, engineRate, analysisRate, mono.frames) else -1L
        val structure = structure(mono, analysisGrid, aStart, aEnd, bars, silence, aFadeStart)

        // 10. assemble
        progress.onProgress(STAGE_ASSEMBLE, 0.98)
        extra["tuningConfidence"] = keyDetection.tuning.confidence.toDouble()
        extra["keyCorrelation"] = keyDetection.correlation.toDouble()
        extra["keyMargin"] = keyDetection.margin.toDouble()
        extra["tempoConfidence"] = rhythm.tempo.confidence.toDouble()
        extra["downbeatConfidence"] = rhythm.downbeatConfidence.toDouble()
        extra["gridResidualMs"] = rhythm.gridResidualMs
        extra["trackedBeats"] = rhythm.trackedBeats.toDouble()
        extra["fadeOut"] = if (silence.fadeOut) 1.0 else 0.0
        extra["hardStop"] = if (silence.hardStop) 1.0 else 0.0
        extra["fadeStartFrame"] = silence.fadeStartFrame.toDouble()
        extra["endLevelDeltaDb"] = silence.endLevelDeltaDb.toDouble()
        extra["leadingSilenceMs"] = trimStart * 1000.0 / engineRate
        extra["trailingSilenceMs"] = (totalFrames - trimEnd) * 1000.0 / engineRate
        extra["analysisRate"] = analysisRate.toDouble()
        if (truncated) extra["truncated"] = 1.0

        val millis = (System.nanoTime() - t0) / 1_000_000L
        val result = TrackAnalysis(
            version = TrackAnalysis.CURRENT_VERSION,
            sourceId = sourceId,
            fingerprint = fingerprint,
            sampleRate = engineRate,
            totalFrames = totalFrames,
            trimStartFrame = trimStart,
            trimEndFrame = trimEnd,
            tempo = rhythm.tempo,
            grid = grid,
            key = keyDetection.key,
            loudness = loudness,
            bars = bars,
            ltasDb = spectrum.ltasDb,
            brightnessHz = spectrum.brightnessHz,
            sections = structure.sections,
            intro = structure.intro,
            outro = structure.outro,
            cues = structure.cues,
            outroKey = sectionKeys.outroKey,
            introKey = sectionKeys.introKey,
            outroLtasDb = spectrum.outroLtasDb,
            introLtasDb = spectrum.introLtasDb,
            tuningCents = keyDetection.tuning.cents,
            onsetFrames = onsetFrames,
            textureMagnitude = spectrum.textureMagnitude,
            extra = extra,
            analysisMillis = millis,
        )
        progress.onProgress(STAGE_DONE, 1.0)
        return result
    }

    /**
     * Streaming entry point: reads [stream] once into one planar float buffer (see the class KDoc for the
     * memory bound) and runs the buffer pipeline. Honours [AnalysisOptions.maxSeconds] while reading, so a
     * capped analysis never decodes more than it analyses.
     */
    override fun analyze(stream: PcmStream, sourceId: String, fingerprint: String, progress: AnalysisProgress): TrackAnalysis {
        progress.onProgress(STAGE_READ, 0.0)
        val audio = readStream(stream)
        return analyze(audio, sourceId, fingerprint, progress)
    }

    // ---- stages -------------------------------------------------------------------------------------------

    private fun truncate(audio: AudioBuffer): Pair<AudioBuffer, Boolean> {
        val max = options.maxSeconds ?: return audio to false
        val limit = Math.round(max * audio.sampleRate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return if (audio.frames > limit) audio.slice(0, limit) to true else audio to false
    }

    /**
     * The analysis-rate signal: the first two channels of stereo (or wider) input, the single channel of mono
     * input, resampled to [rate] once. Arrays are shared with the input when the rate already matches.
     */
    private fun toAnalysisRate(audio: AudioBuffer, rate: Int): AudioBuffer {
        val channels = if (audio.channelCount >= 2) 2 else 1
        return AudioBuffer(rate, Array(channels) { c -> if (audio.sampleRate == rate) audio[c] else resampler.resample(audio[c], audio.sampleRate, rate) })
    }

    private fun spectrum(audio: AudioBuffer, trimStart: Long, trimEnd: Long): SpectrumFeatures {
        if (options.computeTexture) return spectrumAnalyzer.analyze(audio, trimStart, trimEnd)
        val (start, end) = FeatureRate.clampRange(audio, trimStart, trimEnd)
        val region = Math.round(options.regionSec * audio.sampleRate).toInt()
        val introEnd = if (end - start > region) start + region else end
        val outroStart = if (end - start > region) end - region else start
        val whole = spectrumAnalyzer.ltasDb(audio, start.toLong(), end.toLong())
        return SpectrumFeatures(
            ltasDb = whole,
            introLtasDb = if (introEnd == end) whole else spectrumAnalyzer.ltasDb(audio, start.toLong(), introEnd.toLong()),
            outroLtasDb = if (outroStart == start) whole else spectrumAnalyzer.ltasDb(audio, outroStart.toLong(), end.toLong()),
            brightnessHz = spectrumAnalyzer.centroidHz(audio, start.toLong(), end.toLong()),
            textureMagnitude = FloatArray(0),
        )
    }

    private fun structure(mono: AudioBuffer, grid: BeatGrid, aStart: Long, aEnd: Long, bars: BarFeatures, silence: SilenceInfo, aFadeStart: Long): StructureResult {
        if (!options.computeStructure) {
            val outro = structureAnalyzer.classifier.classifyOutro(emptyList(), BeatActivity.empty(), BeatGrid.EMPTY, silence.fadeOut, silence.hardStop)
            return StructureResult(emptyList(), IntroType.UNKNOWN, outro, Cues())
        }
        return structureAnalyzer.analyze(
            audio = mono,
            grid = grid,
            trimStartFrame = aStart,
            trimEndFrame = aEnd,
            barFeatures = bars.takeIf { it.barCount > 0 }?.let { forStructure(it) },
            fadeDetected = silence.fadeOut,
            hardStop = silence.hardStop,
            fadeStartFrame = aFadeStart,
        )
    }

    /**
     * Bar features as handed to the structure stage: identical to the persisted [BarFeatures] except that
     * `percussiveness` is re-anchored for [BeatActivity]'s absolute drum ramp (`PERC_ABSENT` = 0.1 →
     * `PERC_FULL` = 0.4). [BarFeatureExtractor]'s log-compressed ratio is calibrated differently (pad-only bars
     * ≈ 0.25–0.35 from HPSS leakage of note attacks, bass + pad ≈ 0.4, a full kit ≈ 0.55–0.6, see its KDoc), so
     * on that scale a drum-less pad intro reads as "drums undecided / present" and never becomes an
     * AMBIENT_INTRO. The remap `raw ∈ [PERC_RAW_ABSENT, PERC_RAW_PRESENT] → [0.1, 0.4]` (clamped) makes the
     * activity's drum estimate `ramp(raw, 0.30, 0.55)`: pad-only → 0, bass + pad → ~0.4, full kit → ≥ 0.8.
     * The persisted analysis keeps the raw values (contract: `percussive / (harmonic + percussive)`).
     */
    internal fun forStructure(bars: BarFeatures): BarFeatures {
        val p = bars.percussiveness
        if (p.isEmpty()) return bars
        val remapped = FloatArray(p.size) { i ->
            val t = ((p[i] - PERC_RAW_ABSENT) / (PERC_RAW_PRESENT - PERC_RAW_ABSENT)).coerceIn(0f, 1f)
            BeatActivity.PERC_ABSENT + t * (BeatActivity.PERC_FULL - BeatActivity.PERC_ABSENT)
        }
        return bars.copy(percussiveness = remapped)
    }

    /** Onset times (seconds, whole-buffer time base) → engine frames inside `[trimStart, trimEnd]`, ascending. */
    private fun onsetFrames(rhythm: RhythmResult, engineRate: Int, trimStart: Long, trimEnd: Long): LongArray {
        val times = rhythm.onsetTimesSec
        if (times.isEmpty() || trimEnd <= trimStart) return LongArray(0)
        val out = ArrayList<Long>(times.size)
        var last = Long.MIN_VALUE
        for (t in times) {
            val f = Math.round(t * engineRate).coerceIn(trimStart, trimEnd)
            if (f > last) { out.add(f); last = f }
        }
        return out.toLongArray()
    }

    private fun readStream(stream: PcmStream): AudioBuffer {
        val channels = stream.channelCount
        val rate = stream.sampleRate
        val cap = options.maxSeconds?.let { Math.round(it * rate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: Int.MAX_VALUE
        val known = stream.totalFrames
        var capacity = if (known >= 0) min(known, cap.toLong()).toInt() else min(cap, rate * 30)
        if (capacity <= 0) capacity = min(cap, 1 shl 16).coerceAtLeast(1)
        var data = Array(channels) { FloatArray(capacity) }
        var total = 0
        while (total < cap) {
            if (total == capacity) {
                val grown = min(cap.toLong(), capacity.toLong() * 2).toInt()
                if (grown <= capacity) break
                data = Array(channels) { data[it].copyOf(grown) }
                capacity = grown
            }
            val want = min(READ_BLOCK, capacity - total)
            val n = stream.read(data, total, want)
            if (n <= 0) break
            total += n
        }
        if (total != capacity) data = Array(channels) { data[it].copyOf(total) }
        return AudioBuffer(rate, data)
    }

    companion object {
        const val STAGE_READ = "read"
        const val STAGE_TRIM = "trim"
        const val STAGE_LOUDNESS = "loudness"
        const val STAGE_DOWNMIX = "downmix"
        const val STAGE_RHYTHM = "rhythm"
        const val STAGE_HPSS = "hpss"
        const val STAGE_KEY = "key"
        const val STAGE_BARS = "bars"
        const val STAGE_SPECTRUM = "spectrum"
        const val STAGE_STRUCTURE = "structure"
        const val STAGE_ASSEMBLE = "assemble"
        const val STAGE_DONE = "done"

        /** Stage labels in pipeline order (for progress UIs). */
        val STAGES: List<String> = listOf(STAGE_READ, STAGE_TRIM, STAGE_LOUDNESS, STAGE_DOWNMIX, STAGE_RHYTHM, STAGE_HPSS, STAGE_KEY, STAGE_BARS, STAGE_SPECTRUM, STAGE_STRUCTURE, STAGE_ASSEMBLE, STAGE_DONE)

        private const val READ_BLOCK = 1 shl 16

        /** Raw [BarFeatures.percussiveness] at / below which a bar is drum-less for the structure stage (see [forStructure]). */
        const val PERC_RAW_ABSENT = 0.30f
        /** Raw [BarFeatures.percussiveness] at / above which a bar has a full drum kit for the structure stage. */
        const val PERC_RAW_PRESENT = 0.55f

        /** Engine frame → analysis-rate frame (rounded, clamped to `[0, limit]`). */
        internal fun toAnalysisFrame(frame: Long, engineRate: Int, analysisRate: Int, limit: Int): Long =
            Math.round(frame.toDouble() * analysisRate / engineRate).coerceIn(0L, limit.toLong())

        /**
         * Converts a grid whose frames are at [fromRate] to [toRate], keeping the beat count, kind, phase and
         * phrase information. RIGID grids are rebuilt from the scaled first beat and the exact period at the
         * target rate; FLEX grids scale every beat (kept strictly increasing).
         */
        internal fun toEngineGrid(grid: BeatGrid, fromRate: Int, toRate: Int): BeatGrid {
            if (grid.isEmpty || fromRate == toRate) return grid
            val n = grid.beatCount
            val scale = toRate.toDouble() / fromRate
            val frames = LongArray(n)
            if (grid.kind == GridKind.RIGID && grid.bpm > 0) {
                val first = grid.beatFrames[0] * scale
                val period = 60.0 * toRate / grid.bpm
                for (i in 0 until n) frames[i] = Math.round(first + i * period)
            } else {
                for (i in 0 until n) frames[i] = Math.round(grid.beatFrames[i] * scale)
            }
            for (i in 1 until n) if (frames[i] <= frames[i - 1]) frames[i] = frames[i - 1] + 1
            return BeatGrid(grid.kind, grid.bpm, frames, grid.downbeatPhase, grid.beatsPerBar, grid.confidence, grid.phraseStartBeat, grid.phraseBars)
        }
    }
}
