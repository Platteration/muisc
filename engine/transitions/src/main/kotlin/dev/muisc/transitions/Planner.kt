package dev.muisc.transitions

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer

/** Derives [PairFeatures] from two analyses. Pure function of the analyses. */
interface PairAnalyzer {
    fun features(a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): PairFeatures
}

/** One ranked option produced by the planner. */
data class PlanCandidate(
    val strategy: TransitionStrategy,
    val applicability: Applicability,
    /** Final score after user weights, variety penalty, energy preference and modifier bonuses. */
    val score: Double,
    val plan: TransitionPlan,
    val modifiers: List<TransitionModifier> = emptyList(),
)

/** Planner output: never empty (the crossfade floor guarantees a candidate for every pair). */
data class RankedPlans(val features: PairFeatures, val candidates: List<PlanCandidate>) {
    init { require(candidates.isNotEmpty()) { "planner must always return at least one candidate" } }
    val best: PlanCandidate get() = candidates.first()
}

/**
 * Chooses how to transition from A into B. Implementations score every registered strategy, apply
 * preferences and the escalation ladder, and return all viable candidates best-first.
 */
interface TransitionPlanner {
    fun plan(a: TrackRef, b: TrackRef, prefs: TransitionPrefs, seed: Long = 0L, previousStrategyId: String? = null): RankedPlans
}

/** Loads decoded audio at the engine format for a frame range of a track (JVM: decoder+resample; Android: MediaCodec). */
interface TrackAudioLoader {
    /** Returns exactly `range.length` frames (zero-padded past the end) at `prefs.sampleRate` / `prefs.channels`. */
    fun load(track: TrackRef, range: FrameRange, prefs: TransitionPrefs): AudioBuffer
}

/** Renders a planned transition: loads the windows, computes stems lazily, runs the strategy and modifiers. */
interface TransitionRenderer {
    fun render(a: TrackRef, b: TrackRef, candidate: PlanCandidate, features: PairFeatures, ctx: RenderContext): RenderedTransition
}

/** All known strategies and modifiers. The single place to register a new technique. */
interface StrategyRegistry {
    val strategies: List<TransitionStrategy>
    val modifiers: List<TransitionModifier>
    fun strategy(id: String): TransitionStrategy? = strategies.firstOrNull { it.id == id }
    fun modifier(id: String): TransitionModifier? = modifiers.firstOrNull { it.id == id }
}
