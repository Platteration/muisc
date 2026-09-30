package dev.muisc.transitions.planner

import dev.muisc.dsp.stems.StemQuality
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.ModifierParams
import dev.muisc.transitions.PairAnalyzer
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.StrategyRegistry
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionModifier
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPlanner
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.custom.PlannerCustomization
import dev.muisc.transitions.custom.PresetResolution
import dev.muisc.transitions.custom.StrategyPreset
import dev.muisc.transitions.sdk.StrategyTraits
import java.security.MessageDigest
import kotlin.math.abs

/**
 * The reference [TransitionPlanner] (DESIGN.md §5.3): scores every registered strategy and returns all viable
 * candidates best first, each with its `plan()` already computed and its modifiers attached.
 *
 * For every strategy in [registry]:
 *  1. `applicability()` — skipped when blocked (or scored 0) and when listed in `prefs.disabledStrategies`
 *     (`crossfade` can never be disabled: it is the floor of the ladder);
 *  2. `plan()` with `Params.defaults(specs).withAll(prefs.paramOverrides[id])` — a strategy whose `plan()` throws
 *     is skipped and reported in the [PlanExplanation] (the ladder moves on, the pair still plans). With an active
 *     preset or a pin the params are layered as documented in [PresetResolution]: defaults ← active preset
 *     (`prefs.activePresets[id]`) ← `prefs.paramOverrides[id]` ← the pin's preset ← the pin's params;
 *  3. every registered modifier with `applicability > `[MODIFIER_ATTACH_THRESHOLD] (and not disabled) adjusts
 *     the plan in registry order; its resolved params are written into `plan.params` under
 *     `"<modifierId>.<paramId>"` ([ModifierParams]) and its id appended to `plan.modifiers`;
 *  4. `score = fit × weight × energyPref × variety × modifierBonus × jitter × learned` where
 *     `weight = prefs.strategyWeights[id] ?: 1`, `energyPref = 1 − |prefs.energy − ambition| · 0.5` (ambition from
 *     the strategy itself when it implements [StrategyTraits], else [AMBITION]), `variety = 1 − prefs.varietyPenalty · [id == previousStrategyId]` (0 for the
 *     [COOLDOWN_IDS] repeated back to back), `modifierBonus = 1 + Σ bonus[m] · applicability(m)` over the
 *     attached modifiers ([MODIFIER_BONUS]) and `jitter = 1 + `[JITTER]` · uniform(−1, 1)` seeded from
 *     `(a.identity, b.identity, seed, id)` — the same pair, seed and strategy always jitter identically, and
 *     `learned` is the [customization]'s learned multiplier for the strategy in the pair's context bucket
 *     (`custom.FeedbackLearner`, 0.5..1.5; exactly 1 without ratings or without a learner).
 *
 * **Pins.** When [customization] holds a [PairPin] for `(a.identity, b.identity)` and the pinned strategy
 * produced a candidate, that candidate is ranked first whatever its score (the others stay best-first by score).
 * When the pinned strategy is disabled, blocked, unknown or fails to plan, the normal ranking applies and
 * [PlanExplanation.pin] says why the pin was not used.
 *
 * No minimum-score cut-off exists: the ranking *is* the escalation ladder. The result is never empty as long
 * as a never-blocked strategy (`crossfade`) is registered; otherwise [plan] throws [IllegalStateException].
 * Every candidate's `applicability.reasons` carries the strategy's own reasons followed by the §5.2 sub-scores
 * and the score formula, so the Lab / CLI can show them without re-deriving anything.
 *
 * Pure and deterministic: no state, same inputs → same output (sorting is stable; ties keep registry order).
 *
 * @param stemQuality the best stem separator available to the renderer, used for the `s_stems` sub-score in the
 *   explanation (strategies apply their own gate); null = none.
 * @param customization the user's presets, pins and learned weights; [PlannerCustomization.NONE] (the default)
 *   leaves every score and ranking exactly as without customization.
 */
class DefaultTransitionPlanner(
    val registry: StrategyRegistry,
    val pairAnalyzer: PairAnalyzer = DefaultPairAnalyzer(),
    val stemQuality: StemQuality? = StemQuality.PSEUDO,
    val customization: PlannerCustomization = PlannerCustomization.NONE,
) : TransitionPlanner {

    override fun plan(a: TrackRef, b: TrackRef, prefs: TransitionPrefs, seed: Long, previousStrategyId: String?): RankedPlans =
        planExplained(a, b, prefs, seed, previousStrategyId).ranked

    /** [plan] plus the [PlanExplanation] for every candidate and skipped strategy. */
    fun planExplained(a: TrackRef, b: TrackRef, prefs: TransitionPrefs, seed: Long = 0L, previousStrategyId: String? = null): ExplainedPlans {
        val features = pairAnalyzer.features(a.analysis, b.analysis, prefs)
        return planExplained(a, b, features, prefs, seed, previousStrategyId)
    }

    /** As [planExplained] with precomputed [features] (the Lab re-plans with edited features). */
    fun planExplained(a: TrackRef, b: TrackRef, features: PairFeatures, prefs: TransitionPrefs, seed: Long = 0L, previousStrategyId: String? = null): ExplainedPlans {
        val scored = ArrayList<Pair<PlanCandidate, ScoreBreakdown>>(registry.strategies.size)
        val skipped = ArrayList<SkippedStrategy>()
        val notes = ArrayList<String>()
        val pin: PairPin? = customization.pins.pin(a.analysis.identity, b.analysis.identity)
        val pinPreset: StrategyPreset? = pin?.presetId?.let { pid ->
            val p = customization.presets.preset(pid)
            when {
                p == null -> { notes += "pin: preset '$pid' not found — the pinned ${pin.strategyId} uses its other params"; null }
                p.strategyId != pin.strategyId -> { notes += "pin: preset '$pid' is for ${p.strategyId}, not ${pin.strategyId} — ignored"; null }
                else -> p
            }
        }
        for (strategy in registry.strategies) {
            val id = strategy.id
            val pinned = pin != null && pin.strategyId == id
            if (id in prefs.disabledStrategies && id != CROSSFADE_ID) { skipped += SkippedStrategy(id, "disabled in prefs"); continue }
            val app = try {
                strategy.applicability(features, a.analysis, b.analysis, prefs)
            } catch (e: RuntimeException) {
                skipped += SkippedStrategy(id, "applicability() failed: ${e.message ?: e.javaClass.simpleName}"); continue
            }
            if (!app.applicable) {
                skipped += SkippedStrategy(id, if (app.blockers.isNotEmpty()) "blocked" else "score 0", app.blockers); continue
            }
            val active = PresetResolution.active(id, prefs, customization.presets)
            active.problem?.let { notes += it }
            var overrides = active.preset?.params?.values.orEmpty() + prefs.paramOverrides[id].orEmpty()
            if (pinned) overrides = overrides + pinPreset?.params?.values.orEmpty() + pin!!.params?.values.orEmpty()
            val params = Params.defaults(strategy.params).withAll(overrides)
            val allowedModifiers: List<String>? = if (pinned && pinPreset?.modifiers != null) pinPreset.modifiers else active.preset?.modifiers
            var plan = try {
                strategy.plan(a.analysis, b.analysis, features, params, prefs, seed)
            } catch (e: RuntimeException) {
                skipped += SkippedStrategy(id, "plan() failed: ${e.message ?: e.javaClass.simpleName}"); continue
            }
            check(plan.strategyId == id) { "strategy '$id' produced a plan claiming strategy '${plan.strategyId}'" }

            // --- modifiers ---
            val attached = LinkedHashMap<TransitionModifier, Double>()
            for (m in registry.modifiers) {
                if (m.id in prefs.disabledStrategies) continue
                if (allowedModifiers != null && m.id !in allowedModifiers) continue
                val mApp = m.applicability(features, a.analysis, b.analysis, strategy, prefs)
                if (!(mApp > MODIFIER_ATTACH_THRESHOLD)) continue
                val mParams = ModifierParams.defaults(m, prefs)
                plan = try {
                    m.adjustPlan(plan, a.analysis, b.analysis, features, mParams, prefs)
                } catch (e: RuntimeException) {
                    skipped += SkippedStrategy("$id+${m.id}", "adjustPlan() failed: ${e.message ?: e.javaClass.simpleName}"); continue
                }
                plan = ModifierParams.record(plan, m, mParams)
                attached[m] = mApp.coerceIn(0.0, 1.0)
            }

            // --- score ---
            val fit = app.score.coerceIn(0.0, 1.0)
            val weight = (prefs.strategyWeights[id] ?: 1.0).coerceAtLeast(0.0)
            val energyPref = energyPreference(strategy, prefs.energy)
            val variety = variety(id, previousStrategyId, prefs.varietyPenalty)
            val modifierBonus = 1.0 + attached.entries.sumOf { (m, mApp) -> (MODIFIER_BONUS[m.id] ?: DEFAULT_MODIFIER_BONUS) * mApp }
            val jitter = jitter(a.analysis.identity, b.analysis.identity, seed, id)
            val learned = customization.learned(id, features)
            val score = fit * weight * energyPref * variety * modifierBonus * jitter * learned.multiplier
            val presetNote = when {
                pinned && pinPreset != null -> "preset '${pinPreset.id}' (${pinPreset.name}) from your pin"
                active.preset != null -> "preset '${active.preset.id}' (${active.preset.name}) active for $id"
                else -> null
            }
            val sub = CompatibilityScores.compute(id, features, prefs, a.analysis, stemQuality)
            val breakdown = ScoreBreakdown(
                strategyId = id, subScores = sub, fit = fit, weight = weight, energyPref = energyPref, variety = variety,
                modifierBonus = modifierBonus, jitter = jitter, score = score,
                modifiers = attached.entries.associate { (m, v) -> m.id to v }, reasons = app.reasons, blockers = app.blockers,
                learned = learned.multiplier, learnedNote = if (learned.ratings > 0) learned.describe() else null,
                pinned = pinned, pinNote = if (pinned) pinText(pin!!) else null, presetNote = presetNote,
            )
            val explained = Applicability(app.score, reasons = app.reasons + breakdown.lines(), blockers = app.blockers)
            scored += PlanCandidate(strategy, explained, score, plan, attached.keys.toList()) to breakdown
        }
        if (scored.isEmpty()) {
            throw IllegalStateException(
                "no applicable strategy for ${a.id} → ${b.id}: the registry must contain a never-blocked strategy (crossfade); skipped: " +
                    skipped.joinToString { "${it.strategyId} (${it.reason})" },
            )
        }
        val byScore = scored.sortedByDescending { it.second.score } // stable: ties keep registry order
        var pinOutcome: PinOutcome? = null
        var ordered = byScore
        if (pin != null) {
            val at = byScore.indexOfFirst { it.second.pinned }
            if (at >= 0) {
                ordered = listOf(byScore[at]) + byScore.filterIndexed { i, _ -> i != at }
                pinOutcome = PinOutcome(pin.strategyId, used = true, reason = pinText(pin) + if (at > 0) " — ranked first (its score alone ranks it #${at + 1})" else "", presetId = pinPreset?.id)
            } else {
                val why = skipped.firstOrNull { it.strategyId == pin.strategyId }?.let { sk ->
                    sk.reason + if (sk.blockers.isNotEmpty()) ": " + sk.blockers.joinToString("; ") else ""
                } ?: "no strategy '${pin.strategyId}' is registered"
                pinOutcome = PinOutcome(pin.strategyId, used = false, reason = "pinned ${pin.strategyId} not used ($why) — normal ranking applies", presetId = pinPreset?.id)
            }
        }
        val ranked = RankedPlans(features, ordered.map { it.first })
        return ExplainedPlans(ranked, PlanExplanation(features, ordered.map { it.second }, skipped, pinOutcome, notes.distinct()))
    }

    private fun pinText(pin: PairPin): String =
        "pinned by you" + if (pin.note.isNotBlank()) " (${pin.note})" else ""

    companion object {
        const val CROSSFADE_ID = "crossfade"

        /** Modifiers with applicability strictly above this are attached. */
        const val MODIFIER_ATTACH_THRESHOLD = 0.3

        /** Half-width of the multiplicative jitter. */
        const val JITTER = 0.05

        const val DEFAULT_AMBITION = 0.5
        const val DEFAULT_MODIFIER_BONUS = 0.10

        /** How "showy" each strategy is, 0 (safest) .. 1 (showpiece); matched against `prefs.energy` (§5.3). */
        val AMBITION: Map<String, Double> = mapOf(
            "crossfade" to 0.1, "outroIntroMinimal" to 0.2, "ambientBridge" to 0.3, "beatMatchedBlend" to 0.4,
            "phraseCut" to 0.5, "bassSwap" to 0.5, "harmonicBlend" to 0.5,
            "stemSwap" to 0.6, "filterSweep" to 0.6, "echoOut" to 0.6, "spectralFreezeBridge" to 0.6,
            "drumBreakBridge" to 0.7, "loopRollRiser" to 0.9, "brakeStop" to 0.9,
        )

        /** Strategies whose variety factor drops to 0 when they were used for the previous transition. */
        val COOLDOWN_IDS: Set<String> = setOf("brakeStop", "loopRollRiser")

        /** Score bonus per unit of modifier applicability. */
        val MODIFIER_BONUS: Map<String, Double> = mapOf("tempoGlide" to 0.15, "textureCarry" to 0.10)

        /** `1 − |energy − ambition| · 0.5`. */
        fun energyPreference(strategyId: String, energy: Double): Double =
            (1.0 - abs(energy.coerceIn(0.0, 1.0) - (AMBITION[strategyId] ?: DEFAULT_AMBITION)) * 0.5).coerceIn(0.0, 1.0)

        /** The strategy's ambition: its own [StrategyTraits.ambition] (clamped to 0..1) when it has one, else [AMBITION] / [DEFAULT_AMBITION]. */
        fun ambitionOf(strategy: TransitionStrategy): Double =
            (strategy as? StrategyTraits)?.ambition?.takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0) ?: (AMBITION[strategy.id] ?: DEFAULT_AMBITION)

        /** [energyPreference] with the ambition taken from [ambitionOf]. */
        fun energyPreference(strategy: TransitionStrategy, energy: Double): Double =
            (1.0 - abs(energy.coerceIn(0.0, 1.0) - ambitionOf(strategy)) * 0.5).coerceIn(0.0, 1.0)

        /** `1 − varietyPenalty · [id == previous]`, 0 for a [COOLDOWN_IDS] strategy repeated back to back. */
        fun variety(strategyId: String, previousStrategyId: String?, varietyPenalty: Double): Double {
            if (strategyId != previousStrategyId) return 1.0
            if (strategyId in COOLDOWN_IDS) return 0.0
            return (1.0 - varietyPenalty.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        }

        /** `1 + JITTER · u`, `u ∈ [−1, 1)` derived from SHA-256 of the track identities, the seed and the strategy id. */
        fun jitter(fingerprintA: String, fingerprintB: String, seed: Long, strategyId: String): Double {
            val digest = MessageDigest.getInstance("SHA-256").digest("$fingerprintA\u0000$fingerprintB\u0000$seed\u0000$strategyId".toByteArray(Charsets.UTF_8))
            var bits = 0L
            for (i in 0 until 8) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
            val unit = (bits ushr 11).toDouble() / (1L shl 53).toDouble() // [0, 1)
            return 1.0 + JITTER * (unit * 2.0 - 1.0)
        }
    }
}
