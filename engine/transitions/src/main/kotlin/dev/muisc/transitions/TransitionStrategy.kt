package dev.muisc.transitions

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.stems.Stems
import kotlinx.serialization.Serializable

/** A half-open frame range [start, end) in a track. */
@Serializable
data class FrameRange(val start: Long, val end: Long) {
    init { require(end >= start) { "end ($end) < start ($start)" } }
    val length: Int get() = (end - start).toInt()
    operator fun contains(frame: Long): Boolean = frame in start until end
}

/** Result of asking a strategy whether it fits a pair. `score` is 0..1; any blocker makes it inapplicable. */
data class Applicability(
    val score: Double,
    val reasons: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
) {
    val applicable: Boolean get() = blockers.isEmpty() && score > 0.0
    companion object {
        fun blocked(vararg why: String) = Applicability(0.0, blockers = why.toList())
        fun of(score: Double, vararg why: String) = Applicability(score.coerceIn(0.0, 1.0), reasons = why.toList())
    }
}

/** Where the strategy needs stems for (both is fine); the renderer computes them lazily. */
enum class StemNeed { NONE, A_TAIL, B_HEAD, BOTH }

/** A named automation curve (beat position → value) for visualisation in the Transition Lab. */
@Serializable
data class AutomationLane(val id: String, val points: List<LanePoint>)

@Serializable
data class LanePoint(val outputSec: Double, val value: Double)

/**
 * A fully-decided transition, produced by [TransitionStrategy.plan] BEFORE any audio is loaded.
 * Pure data, serialisable, shown and editable in the Transition Lab.
 *
 * Splice contract (the program sequencer relies on it):
 *  - A's body plays up to (excluding) [aExitFrame]; the rendered segment's first frames ARE A's audio from
 *    [aExitFrame] onward at unity gain and stretch ratio 1.0.
 *  - The rendered segment's last frames ARE B's audio up to (excluding) [bEntryFrame] at unity gain and
 *    ratio 1.0; B's body resumes at [bEntryFrame].
 *  - [aWindow] / [bWindow] are the frame ranges the renderer must decode and hand to [TransitionStrategy.render].
 *    aWindow must contain aExitFrame; bWindow must end at or after bEntryFrame.
 */
@Serializable
data class TransitionPlan(
    val strategyId: String,
    val params: Params,
    val aExitFrame: Long,
    val bEntryFrame: Long,
    val aWindow: FrameRange,
    val bWindow: FrameRange,
    /** Estimated length of the rendered segment in frames (the actual render may differ slightly). */
    val expectedOutputFrames: Int,
    /** Modifier ids applied on top of the base strategy (e.g. "tempoGlide", "textureCarry"). */
    val modifiers: List<String> = emptyList(),
    val stemNeed: StemNeed = StemNeed.NONE,
    val lanes: List<AutomationLane> = emptyList(),
    /** Human-readable explanation of the choices made (bar counts, cue points, stretch ratio...). */
    val notes: List<String> = emptyList(),
) {
    init {
        require(aExitFrame in aWindow) { "aExitFrame $aExitFrame not inside aWindow $aWindow" }
        require(bWindow.end >= bEntryFrame) { "bWindow $bWindow ends before bEntryFrame $bEntryFrame" }
    }
    /** Offset of aExitFrame inside the decoded A window. */
    val aExitOffset: Int get() = (aExitFrame - aWindow.start).toInt()
    /** Offset of bEntryFrame inside the decoded B window. */
    val bEntryOffset: Int get() = (bEntryFrame - bWindow.start).toInt()
}

/** Lazily computed stems for the decoded windows. */
interface StemProvider {
    fun aTail(): Stems
    fun bHead(): Stems
}

/** Decoded audio handed to [TransitionStrategy.render]; both buffers are at the engine format. */
class TransitionInput(
    val plan: TransitionPlan,
    val a: TrackRef,
    val b: TrackRef,
    val features: PairFeatures,
    /** Exactly plan.aWindow frames of A. */
    val aAudio: AudioBuffer,
    /** Exactly plan.bWindow frames of B. */
    val bAudio: AudioBuffer,
    val stems: StemProvider,
) {
    init {
        require(aAudio.frames == plan.aWindow.length) { "aAudio has ${aAudio.frames} frames, plan.aWindow needs ${plan.aWindow.length}" }
        require(bAudio.frames == plan.bWindow.length) { "bAudio has ${bAudio.frames} frames, plan.bWindow needs ${plan.bWindow.length}" }
    }
    val aAnalysis: TrackAnalysis get() = a.analysis
    val bAnalysis: TrackAnalysis get() = b.analysis
}

/** Rendering environment. */
data class RenderContext(
    val prefs: TransitionPrefs,
    val seed: Long = 0L,
    /** Progress callback 0..1 (may be called from the render thread). */
    val progress: (Double) -> Unit = {},
) {
    val sampleRate: Int get() = prefs.sampleRate
    val channels: Int get() = prefs.channels
}

/** Marker in the rendered segment (e.g. "B enters", "bass swap") for the Lab's waveform view. */
@Serializable
data class Marker(val frame: Long, val label: String)

@Serializable
data class RenderReport(
    val renderMillis: Long,
    val peak: Float,
    val truePeakDbtp: Float,
    val integratedLufs: Float,
    /** Detected artifacts (clicks, level jumps, clipping). Empty when clean. */
    val warnings: List<String> = emptyList(),
    /** Cache key of this render (strategy id + params + fingerprints + prefs), empty when not cached. */
    val renderKey: String = "",
    /** Numeric metrics (metric id -> value) for the Lab / regression tools. */
    val metrics: Map<String, Double> = emptyMap(),
    /** Stretch ratio actually used per master beat (empty for non-beat-domain strategies). */
    val ratioTrace: FloatArray = FloatArray(0),
)

/** The rendered transition segment plus its plan and diagnostics. */
class RenderedTransition(
    val plan: TransitionPlan,
    val audio: AudioBuffer,
    val markers: List<Marker> = emptyList(),
    val report: RenderReport,
)

/**
 * A transition technique. Implementations are stateless and thread-safe: every call receives all state.
 *
 * Adding a technique = one class implementing this + registering it in [StrategyRegistry].
 */
interface TransitionStrategy {
    /** Stable identifier (used in prefs, presets, JSON). */
    val id: String
    val displayName: String
    val description: String
    val params: List<ParamSpec>

    /** Cheap suitability check from analyses only (no audio). Returns a 0..1 score and reasons/blockers. */
    fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability

    /** Decides exact frames and parameters. Must be deterministic for (inputs, seed). No audio access. */
    fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan

    /** Produces the segment honouring the splice contract of [TransitionPlan]. */
    fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition
}

/**
 * Modifiers decorate a base plan (e.g. tempo glide changes the master grid; texture carry adds a bed).
 * They are applied by the renderer after the base strategy renders.
 */
interface TransitionModifier {
    val id: String
    val displayName: String
    val params: List<ParamSpec>
    /** 0..1 how much this modifier would improve the pair; 0 = don't apply. */
    fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, base: TransitionStrategy, prefs: TransitionPrefs): Double
    /** May adjust the plan (windows, expected length) before audio is loaded. */
    fun adjustPlan(plan: TransitionPlan, a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs): TransitionPlan
    /** Post-processes the rendered segment (has access to the same input the base strategy had). */
    fun apply(rendered: RenderedTransition, input: TransitionInput, params: Params, ctx: RenderContext): RenderedTransition
}
