package dev.muisc.transitions

import dev.muisc.dsp.stems.PseudoStemSeparator
import dev.muisc.dsp.stems.StemSeparator
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.core.SpliceCheck

/**
 * Where a modifier's parameters live inside a [TransitionPlan]: `plan.params["<modifierId>.<paramId>"]`.
 *
 * The planner resolves every attached modifier's params (defaults ← `prefs.paramOverrides[modifierId]`) and
 * [record]s them in the plan, so a plan is self-describing (the CLI can replay it from JSON and the [RenderKey]
 * covers modifier params too); the renderer reads them back with [resolve]. Strategies' own `ParamSet.resolve`
 * drops the prefixed keys as unknown, so they never collide with strategy params.
 */
object ModifierParams {
    /** The plan-params key of a modifier param. */
    fun key(modifierId: String, paramId: String): String = "$modifierId.$paramId"

    /** Defaults of [modifier] overridden by `prefs.paramOverrides[modifier.id]`. */
    fun defaults(modifier: TransitionModifier, prefs: TransitionPrefs): Params =
        Params.defaults(modifier.params).withAll(prefs.paramOverrides[modifier.id].orEmpty())

    /** [plan] with [params] recorded under the modifier's prefix and the modifier id appended to `plan.modifiers` (once). */
    fun record(plan: TransitionPlan, modifier: TransitionModifier, params: Params): TransitionPlan {
        val prefixed = params.values.mapKeys { (k, _) -> key(modifier.id, k) }
        val ids = if (modifier.id in plan.modifiers) plan.modifiers else plan.modifiers + modifier.id
        return plan.copy(params = plan.params.withAll(prefixed), modifiers = ids)
    }

    /** The modifier's params for [plan]: defaults ← prefs overrides ← values recorded in the plan. */
    fun resolve(modifier: TransitionModifier, plan: TransitionPlan, prefs: TransitionPrefs): Params {
        val prefix = modifier.id + "."
        val recorded = plan.params.values.filterKeys { it.startsWith(prefix) }.mapKeys { (k, _) -> k.removePrefix(prefix) }
        return defaults(modifier, prefs).withAll(recorded).resolve(modifier.params)
    }
}

/**
 * The reference [TransitionRenderer]: turns a [PlanCandidate] into audio.
 *
 * Stages (each followed by a cancellation check — `Thread.interrupted()` → [InterruptedException]):
 *  1. load `plan.aWindow` / `plan.bWindow` through the [loader] (exactly the window lengths, zero-padded);
 *  2. apply [DeckGain.of] (analysis, prefs) to each window **in place** — the loader returns fresh buffers — so
 *     the strategy sees "unity gain" as the splice contract defines it (§2.4);
 *  3. build the [TransitionInput] with a [LazyStemProvider] over the [stemSeparator] and precompute the stems the
 *     plan's `stemNeed` declares;
 *  4. `strategy.render` (progress mapped into 0.1..0.8 of `ctx.progress`);
 *  5. every modifier's `apply` in candidate order (`candidate.modifiers`, else the `plan.modifiers` ids resolved
 *     through the [registry]) with the params [ModifierParams.resolve] reads from the plan;
 *  6. [SpliceCheck.verify] — violations become `RenderReport.warnings` (prefixed "splice: ") — and the
 *     [RenderKey] is stamped into the report; the result is the rendered transition with the updated report.
 *
 * Deterministic for (plan, inputs, seed). Not thread-safe when the default [PseudoStemSeparator] is shared:
 * use one renderer per render thread (the coordinator runs one render at a time, DESIGN.md §7.2).
 */
class DefaultTransitionRenderer(
    val loader: TrackAudioLoader,
    val registry: StrategyRegistry,
    val stemSeparator: StemSeparator = PseudoStemSeparator(),
) : TransitionRenderer {

    override fun render(a: TrackRef, b: TrackRef, candidate: PlanCandidate, features: PairFeatures, ctx: RenderContext): RenderedTransition {
        val plan = candidate.plan
        val strategy = candidate.strategy
        require(plan.strategyId == strategy.id) { "plan is for '${plan.strategyId}' but the candidate's strategy is '${strategy.id}'" }
        val modifiers = resolveModifiers(candidate)
        val prefs = ctx.prefs

        ctx.progress(0.0)
        val aAudio = loader.load(a, plan.aWindow, prefs)
        checkCancelled()
        val bAudio = loader.load(b, plan.bWindow, prefs)
        checkCancelled()
        require(aAudio.frames == plan.aWindow.length) { "loader returned ${aAudio.frames} frames for aWindow ${plan.aWindow}" }
        require(bAudio.frames == plan.bWindow.length) { "loader returned ${bAudio.frames} frames for bWindow ${plan.bWindow}" }

        DeckGain.applyInPlace(aAudio, DeckGain.of(a.analysis, prefs))
        DeckGain.applyInPlace(bAudio, DeckGain.of(b.analysis, prefs))
        ctx.progress(PROGRESS_LOADED)

        val stems = LazyStemProvider(aAudio, bAudio, stemSeparator, plan.stemNeed)
        val input = TransitionInput(plan, a, b, features, aAudio, bAudio, stems)
        stems.precompute()
        checkCancelled()

        val strategyCtx = ctx.copy(progress = { p -> ctx.progress(PROGRESS_LOADED + (PROGRESS_RENDERED - PROGRESS_LOADED) * p.coerceIn(0.0, 1.0)) })
        var rendered = strategy.render(input, strategyCtx)
        ctx.progress(PROGRESS_RENDERED)
        checkCancelled()

        modifiers.forEachIndexed { i, m ->
            val params = ModifierParams.resolve(m, rendered.plan, prefs)
            rendered = m.apply(rendered, input, params, ctx)
            ctx.progress(PROGRESS_RENDERED + (PROGRESS_MODIFIED - PROGRESS_RENDERED) * (i + 1) / modifiers.size)
            checkCancelled()
        }

        val violations = SpliceCheck.verify(rendered, input).map { "splice: $it" }
        val key = RenderKey.compute(a.analysis, b.analysis, rendered.plan, prefs, ctx.seed)
        val report = rendered.report.copy(warnings = rendered.report.warnings + violations, renderKey = key)
        ctx.progress(1.0)
        return RenderedTransition(rendered.plan, rendered.audio, rendered.markers, report)
    }

    /** `candidate.modifiers` when given, else the plan's modifier ids looked up in the registry (unknown id → error). */
    fun resolveModifiers(candidate: PlanCandidate): List<TransitionModifier> {
        if (candidate.modifiers.isNotEmpty()) return candidate.modifiers
        return candidate.plan.modifiers.map { id -> registry.modifier(id) ?: throw IllegalArgumentException("plan uses unknown modifier '$id'") }
    }

    private fun checkCancelled() {
        if (Thread.interrupted()) throw InterruptedException("render cancelled")
    }

    companion object {
        const val PROGRESS_LOADED = 0.1
        const val PROGRESS_RENDERED = 0.8
        const val PROGRESS_MODIFIED = 0.95
    }
}
