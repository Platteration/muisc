package dev.muisc.transitions.planner

import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.RankedPlans

/**
 * How one candidate's final score came about (DESIGN.md §5.3):
 * `score = fit × weight × energyPref × variety × modifierBonus × jitter × learned`.
 * [reasons] / [blockers] are the strategy's own `Applicability` texts; [subScores] the planner's §5.2 recomputation.
 * The customization fields ([learned], [pinned], the notes) keep their neutral defaults when the planner has no
 * customization.
 */
data class ScoreBreakdown(
    val strategyId: String,
    val subScores: SubScores,
    /** The strategy's `applicability().score`. */
    val fit: Double,
    /** `prefs.strategyWeights[id]` (1 when absent). */
    val weight: Double,
    /** `1 − |prefs.energy − ambition| · 0.5`. */
    val energyPref: Double,
    /** `1 − varietyPenalty · [id == previous]` (0 for cooldown strategies repeated back to back). */
    val variety: Double,
    /** `1 + Σ bonus(modifier) · applicability(modifier)` over the attached modifiers. */
    val modifierBonus: Double,
    /** `1 ± 0.05`, seeded from the fingerprints, the seed and the strategy id. */
    val jitter: Double,
    val score: Double,
    /** Attached modifier ids with their applicability. */
    val modifiers: Map<String, Double> = emptyMap(),
    val reasons: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    /** The learned multiplier from the user's ratings (0.5..1.5; 1 without ratings). */
    val learned: Double = 1.0,
    /** `"learned ×1.12 from 5 ratings in 'matched, in key, rising'"`; null when nothing was learned for this context. */
    val learnedNote: String? = null,
    /** True when the user pinned this strategy for the pair and the planner ranked it first. */
    val pinned: Boolean = false,
    /** `"pinned by you"` (with the pin's note) when [pinned]. */
    val pinNote: String? = null,
    /** Which preset supplied parameter values, e.g. `"preset 'tight-bass-swap' (Tight 8-bar bass swap) active for bassSwap"`. */
    val presetNote: String? = null,
) {
    /**
     * `"score 0.412 = fit 0.60 × weight 1.00 × energy 0.85 × variety 1.00 × modifiers 1.00 × jitter 1.012"`, followed
     * by `" × learned 1.12"` when ratings contributed and `" · pinned (ranked first)"` for a pinned candidate.
     */
    fun formula(): String =
        "score ${"%.3f".format(score)} = fit ${"%.2f".format(fit)} × weight ${"%.2f".format(weight)} × energy ${"%.2f".format(energyPref)}" +
            " × variety ${"%.2f".format(variety)} × modifiers ${"%.2f".format(modifierBonus)} × jitter ${"%.3f".format(jitter)}" +
            (if (learnedNote != null || learned != 1.0) " × learned ${"%.2f".format(learned)}" else "") +
            (if (pinned) " · pinned (ranked first)" else "")

    /** The lines appended to the candidate's `Applicability.reasons`: sub-scores, formula, modifiers, customization. */
    fun lines(): List<String> {
        val out = ArrayList<String>(6)
        out += "scores: " + subScores.summary()
        out += formula()
        if (modifiers.isNotEmpty()) out += "modifiers: " + modifiers.entries.joinToString(", ") { (id, app) -> "$id (${"%.2f".format(app)})" }
        learnedNote?.let { out += it }
        presetNote?.let { out += it }
        pinNote?.let { out += it }
        return out
    }
}

/** What happened to the user's pin for the pair: used (ranked first) or not, and why. */
data class PinOutcome(val strategyId: String, val used: Boolean, val reason: String, val presetId: String? = null)

/** A registered strategy that produced no candidate, and why (disabled, blocked, or its `plan()` failed). */
data class SkippedStrategy(val strategyId: String, val reason: String, val blockers: List<String> = emptyList())

/**
 * The planner's full account of one `plan()` call: every candidate's [ScoreBreakdown] best-first (parallel to
 * `RankedPlans.candidates`) and the strategies it skipped. Rendered by the CLI `plan` command and the Lab.
 */
data class PlanExplanation(
    val features: PairFeatures,
    /** Best first; index `i` explains `RankedPlans.candidates[i]`. */
    val ranked: List<ScoreBreakdown>,
    val skipped: List<SkippedStrategy> = emptyList(),
    /** The pin for this pair and whether it was used; null when there was none. */
    val pin: PinOutcome? = null,
    /** Customization problems met while planning (a preset that could not be found, ...). */
    val notes: List<String> = emptyList(),
) {
    fun breakdown(strategyId: String): ScoreBreakdown? = ranked.firstOrNull { it.strategyId == strategyId }

    /** Multi-line human-readable report. */
    fun render(): String = buildString {
        appendLine("pair: stretch ${"%.1f".format(features.stretchPercent)} % (${features.tempoRelation}), camelot ${features.camelotDistance} → ${features.camelotDistanceAfterShift} after shift ${features.bestPitchShiftSemitones}, " +
            "loudness Δ ${"%.1f".format(features.loudnessDeltaLu)} LU, ${features.outro} → ${features.intro}, grid ${"%.2f".format(features.gridConfidenceA)}/${"%.2f".format(features.gridConfidenceB)}")
        ranked.forEachIndexed { i, b ->
            appendLine("${i + 1}. ${b.strategyId}  ${b.formula()}")
            appendLine("     " + b.subScores.summary())
            if (b.modifiers.isNotEmpty()) appendLine("     modifiers: " + b.modifiers.entries.joinToString(", ") { (id, app) -> "$id (${"%.2f".format(app)})" })
            for (r in b.reasons) appendLine("     - $r")
            b.learnedNote?.let { appendLine("     $it") }
            b.presetNote?.let { appendLine("     $it") }
            b.pinNote?.let { appendLine("     $it") }
        }
        for (s in skipped) appendLine("skipped ${s.strategyId}: ${s.reason}" + if (s.blockers.isNotEmpty()) " " + s.blockers else "")
        pin?.let { appendLine("pin: ${it.reason}") }
        for (n in notes) appendLine("note: $n")
    }
}

/** [RankedPlans] together with the [PlanExplanation] that produced them. */
data class ExplainedPlans(val ranked: RankedPlans, val explanation: PlanExplanation)
