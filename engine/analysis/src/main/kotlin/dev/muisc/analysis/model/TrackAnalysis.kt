package dev.muisc.analysis.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TempoEstimate(
    val bpm: Double,
    /** 0..1 */
    val confidence: Float,
    /** Alternative interpretations (typically bpm/2 and bpm*2) with their relative scores. */
    val alternates: List<TempoCandidate> = emptyList(),
)

@Serializable
data class TempoCandidate(val bpm: Double, val score: Float)

@Serializable
data class LoudnessInfo(
    /** ITU-R BS.1770 integrated loudness (gated), LUFS. */
    val integratedLufs: Float,
    /** True peak, dBTP. */
    val truePeakDbtp: Float,
    /** Short-term (3 s) loudness on a fixed grid, LUFS; -inf as -120. */
    val shortTermLufs: FloatArray = FloatArray(0),
    /** Seconds between shortTermLufs samples. */
    val shortTermHopSec: Float = 0.1f,
    val loudnessRangeLu: Float = 0f,
) {
    override fun equals(other: Any?): Boolean = other is LoudnessInfo && other.integratedLufs == integratedLufs && other.truePeakDbtp == truePeakDbtp
    override fun hashCode(): Int = integratedLufs.hashCode()
}

enum class SectionLabel { INTRO, VERSE, CHORUS, BREAKDOWN, BUILD, DROP, BRIDGE, OUTRO, UNKNOWN }

/** A structural section in beats of the track's [BeatGrid] (endBeat exclusive). */
@Serializable
data class Section(
    val startBeat: Int,
    val endBeat: Int,
    val label: SectionLabel,
    /** Relative energy 0..1 (normalised to the track's loudest bar). */
    val energy: Float,
    /** Drum presence 0..1. */
    val drums: Float = 0f,
    /** Vocal presence 0..1. */
    val vocals: Float = 0f,
) {
    val lengthBeats: Int get() = endBeat - startBeat
}

enum class OutroType { HARD_STOP, FADE_OUT, BEAT_OUTRO, AMBIENT_OUTRO, VOCAL_OUTRO, UNKNOWN }
enum class IntroType { BEAT_INTRO, AMBIENT_INTRO, VOCAL_INTRO, COLD_START, SILENCE, UNKNOWN }

/** Suggested mix points, as beat indices on the grid (-1 when unknown). */
@Serializable
data class Cues(
    /** Last phrase boundary at which drums are still present: the natural place to start mixing out. */
    val mixOutBeat: Int = -1,
    /** First phrase boundary at which drums are present: the natural place for B to be fully in. */
    val mixInBeat: Int = -1,
    val firstDownbeat: Int = -1,
    val lastDownbeat: Int = -1,
    /** Largest positive energy jump between consecutive phrases ("the drop"), or -1. */
    val dropBeat: Int = -1,
)

/**
 * Per-bar features (index = bar number from the grid's first downbeat). All arrays have the same length.
 * Band split: sub (<60 Hz), bass (60–250), mid (250–4000), high (>4000).
 */
@Serializable
data class BarFeatures(
    val energy: FloatArray = FloatArray(0),
    val sub: FloatArray = FloatArray(0),
    val bass: FloatArray = FloatArray(0),
    val mid: FloatArray = FloatArray(0),
    val high: FloatArray = FloatArray(0),
    /** 0..1 percussive/(harmonic+percussive) ratio. */
    val percussiveness: FloatArray = FloatArray(0),
    /** 0..1 vocal activity (heuristic without ML). */
    val vocalActivity: FloatArray = FloatArray(0),
) {
    val barCount: Int get() = energy.size
    override fun equals(other: Any?): Boolean = other is BarFeatures && other.energy.contentEquals(energy)
    override fun hashCode(): Int = energy.contentHashCode()
}

/**
 * Everything the transition planner knows about a track. Produced once by the analysis pipeline,
 * cached (JSON) and never mutated. Frame positions are at [sampleRate], which is the engine
 * sample rate the analysis ran at — if the engine rate changes the track is re-analysed.
 */
@Serializable
data class TrackAnalysis(
    val version: Int = CURRENT_VERSION,
    /** Identity of the analysed source (path / URI) and a fingerprint (size+mtime+hash) for cache validation. */
    val sourceId: String,
    val fingerprint: String,
    val sampleRate: Int,
    val totalFrames: Long,
    /** First / last non-silent frame (trailing digital silence and fades below -60 dBFS excluded). */
    val trimStartFrame: Long,
    val trimEndFrame: Long,
    val tempo: TempoEstimate,
    val grid: BeatGrid,
    val key: KeyEstimate,
    val loudness: LoudnessInfo,
    val bars: BarFeatures = BarFeatures(),
    /** Long-term average spectrum in dB over 31 third-octave bands (20 Hz .. 20 kHz), for texture matching. */
    val ltasDb: FloatArray = FloatArray(0),
    /** Spectral centroid in Hz over the whole track. */
    val brightnessHz: Float = 0f,
    val sections: List<Section> = emptyList(),
    val intro: IntroType = IntroType.UNKNOWN,
    val outro: OutroType = OutroType.UNKNOWN,
    val cues: Cues = Cues(),
    /** Key of the outro (last ~30 s) and intro (first ~30 s) when they differ from the whole-track key; null = same as [key]. */
    val outroKey: KeyEstimate? = null,
    val introKey: KeyEstimate? = null,
    /** Long-term spectra of the outro / intro regions (31 third-octave bands, dB), used by texture matching. Empty = use [ltasDb]. */
    val outroLtasDb: FloatArray = FloatArray(0),
    val introLtasDb: FloatArray = FloatArray(0),
    /** Estimated tuning deviation from A4 = 440 Hz in cents (0 when not estimated). */
    val tuningCents: Float = 0f,
    /** Onset positions (engine-rate frames) from the onset detection function; used to pin transients during time-stretching. */
    val onsetFrames: LongArray = LongArray(0),
    /** Per-bin median magnitude spectrum of the last ~15 s (513 bins at 22.05 kHz, frame 1024) — the "texture" of the outro for TextureCarry. */
    val textureMagnitude: FloatArray = FloatArray(0),
    /** Free-form extra numbers for experiments (never relied upon by shipped strategies). */
    val extra: Map<String, Double> = emptyMap(),
    /** Wall-clock milliseconds the analysis took (diagnostics). */
    val analysisMillis: Long = 0,
) {
    val durationSec: Double get() = totalFrames.toDouble() / sampleRate
    val trimmedDurationSec: Double get() = (trimEndFrame - trimStartFrame).toDouble() / sampleRate
    fun framesToSec(frames: Long): Double = frames.toDouble() / sampleRate
    fun secToFrames(sec: Double): Long = Math.round(sec * sampleRate)

    fun toJson(): String = json.encodeToString(this)

    override fun equals(other: Any?): Boolean = other is TrackAnalysis && other.sourceId == sourceId && other.fingerprint == fingerprint && other.version == version && other.sampleRate == sampleRate
    override fun hashCode(): Int = (sourceId.hashCode() * 31 + fingerprint.hashCode()) * 31 + version

    companion object {
        /** Bump whenever the analysis output changes in a way that invalidates caches. */
        const val CURRENT_VERSION = 2
        val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
        fun fromJson(s: String): TrackAnalysis = json.decodeFromString(s)
    }
}
