package dev.muisc.analysis.structure

import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Cues
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.Section
import dev.muisc.audio.AudioBuffer

/**
 * Output of [TrackStructureAnalyzer]: the four structure fields of `TrackAnalysis` plus diagnostics.
 *
 * [activity] and [novelty] (combined Foote novelty per beat, 0..1, see [StructureAnalyzer]) are kept for
 * inspection / the Transition Lab; they are not part of the persisted analysis.
 */
class StructureResult(
    val sections: List<Section>,
    val intro: IntroType,
    val outro: OutroType,
    val cues: Cues,
    val activity: BeatActivity = BeatActivity.empty(),
    val novelty: FloatArray = FloatArray(0),
) {
    companion object {
        val EMPTY = StructureResult(emptyList(), IntroType.UNKNOWN, OutroType.UNKNOWN, Cues())
    }
}

/**
 * Facade of the structure stage: [BeatSyncFeatureExtractor] → [BeatActivity] (from [BarFeatures] when given, else
 * internal) → [StructureAnalyzer] sections → [IntroOutroClassifier] → [CueFinder].
 *
 * Every position in the result is a beat index of [BeatGrid] (whose frames are at the caller's engine rate); the
 * audio is analysed on a 22.05 kHz mono downmix of `[trimStartFrame, trimEndFrame)`. Never throws: an empty
 * grid, empty audio or an empty region yields [StructureResult.EMPTY]-like output (no sections, UNKNOWN types —
 * except the fade / hard-stop flags, which still map to FADE_OUT / HARD_STOP — and -1 cues).
 */
class TrackStructureAnalyzer(
    val extractor: BeatSyncFeatureExtractor = BeatSyncFeatureExtractor(),
    val structure: StructureAnalyzer = StructureAnalyzer(),
    val classifier: IntroOutroClassifier = IntroOutroClassifier(),
    val cueFinder: CueFinder = CueFinder(),
) {
    /**
     * @param trimStartFrame first non-silent engine frame (from silence detection); the region before it is ignored.
     * @param trimEndFrame end (exclusive) of the non-silent region, engine frames.
     * @param barFeatures per-bar features from the features stage, or null to use the internal estimates.
     * @param fadeDetected the silence stage found a fade-out at the end → [OutroType.FADE_OUT].
     * @param hardStop the audio ends abruptly at full level → [OutroType.HARD_STOP] (unless fadeDetected).
     * @param fadeStartFrame engine frame where the fade starts (-1 unknown); only used as a mix-out fallback anchor.
     */
    fun analyze(
        audio: AudioBuffer,
        grid: BeatGrid,
        trimStartFrame: Long = 0L,
        trimEndFrame: Long = audio.frames.toLong(),
        barFeatures: BarFeatures? = null,
        fadeDetected: Boolean = false,
        hardStop: Boolean = false,
        fadeStartFrame: Long = -1L,
    ): StructureResult {
        val outroFlags = classifier.classifyOutro(emptyList(), BeatActivity.empty(), BeatGrid.EMPTY, fadeDetected, hardStop)
        if (grid.isEmpty || audio.isEmpty) return StructureResult(emptyList(), IntroType.UNKNOWN, outroFlags, Cues())
        val features = extractor.extract(audio, grid, trimStartFrame, trimEndFrame)
        val activity = if (barFeatures != null && barFeatures.barCount > 0) BeatActivity.fromBarFeatures(barFeatures, grid, features)
        else BeatActivity.fromFeatures(features, grid)
        if (features.validCount == 0) {
            val cues = cueFinder.find(grid, activity, trimStartFrame, trimEndFrame, fadeStartFrame)
            return StructureResult(emptyList(), IntroType.UNKNOWN, outroFlags, cues, activity)
        }
        val ssm = structure.selfSimilarity(features)
        val novelty = structure.combinedNovelty(ssm)
        val sections = structure.label(structure.pickBoundaries(novelty, grid), activity)
        val intro = classifier.classifyIntro(sections, activity, grid, trimStartFrame, audio.sampleRate)
        val outro = classifier.classifyOutro(sections, activity, grid, fadeDetected, hardStop)
        val cues = cueFinder.find(grid, activity, trimStartFrame, trimEndFrame, fadeStartFrame)
        return StructureResult(sections, intro, outro, cues, activity, novelty)
    }
}
