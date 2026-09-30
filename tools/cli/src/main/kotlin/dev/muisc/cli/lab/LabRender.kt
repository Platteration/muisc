package dev.muisc.cli.lab

import dev.muisc.cli.PresetValidation
import dev.muisc.cli.RenderSupport
import dev.muisc.metrics.ArtifactMetrics
import dev.muisc.metrics.MetricsReport
import dev.muisc.metrics.Verdict
import dev.muisc.player.EngineLimits
import dev.muisc.player.ProgramRenderer
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.DefaultTransitionRenderer
import dev.muisc.transitions.LazyStemProvider
import dev.muisc.transitions.ModifierParams
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.custom.StrategyPreset
import dev.muisc.transitions.recipe.RecipeCodec
import dev.muisc.transitions.recipe.RecipeStrategy
import dev.muisc.transitions.recipe.RecipeValidator
import dev.muisc.transitions.recipe.TransitionRecipe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import java.io.File

/**
 * What the page asks to hear: A → B with a strategy (or the planner's best), parameter values, modifiers, a style,
 * a preset, or an unsaved recipe, and how many seconds of each song to play around the transition.
 *
 * [params] are layered over the strategy's defaults, the active preset / `paramOverrides` of the prefs and the
 * [preset] (in that order), the same layering `muisc render --set` uses. [modifiers] null means "what the planner
 * (or the preset) chose"; an empty list means none.
 */
class RenderSpec(
    val a: String,
    val b: String,
    val strategy: String? = null,
    val params: Map<String, String> = emptyMap(),
    val modifiers: List<String>? = null,
    val style: String? = null,
    val preset: String? = null,
    val recipeText: String? = null,
    val contextSec: Double = DEFAULT_CONTEXT_SEC,
    val seed: Long = 0L,
    val limiter: Boolean = true,
) {
    companion object {
        const val DEFAULT_CONTEXT_SEC = 8.0
        const val MAX_CONTEXT_SEC = 60.0

        fun parse(o: JsonObject): RenderSpec {
            val ctx = o.dbl("contextSec") ?: DEFAULT_CONTEXT_SEC
            if (ctx < 0 || ctx > MAX_CONTEXT_SEC) throw LabError(400, "contextSec must be within 0..${MAX_CONTEXT_SEC.toInt()} seconds")
            return RenderSpec(
                a = o.requireStr("a"),
                b = o.requireStr("b"),
                strategy = o.str("strategy"),
                params = o.stringMap("params"),
                modifiers = o.strList("modifiers"),
                style = o.str("style"),
                preset = o.str("preset"),
                recipeText = o.str("recipe"),
                contextSec = ctx,
                seed = o.lng("seed") ?: 0L,
                limiter = o.bool("limiter") ?: true,
            )
        }
    }
}

/** One finished render: the files written and everything the page shows about it. */
class LabRenderResult(
    val id: String,
    val strategyId: String,
    val presetId: String?,
    val features: PairFeatures,
    val contextFile: File,
    val segmentFile: File,
    val contextFrames: Int,
    val segmentFrames: Int,
    val json: JsonObject,
    /** Metrics of the segment alone. */
    val metrics: MetricsReport,
    /** Metrics of the context render (the seams the player produced). */
    val contextMetrics: MetricsReport,
)

/**
 * The Lab's render pipeline: plan (or rebuild the requested candidate), render the segment with the real renderer,
 * measure it with [ArtifactMetrics], then build A-tail + segment + B-head as a playback program and run it through
 * the real [ProgramRenderer], exactly like `muisc render --context`. Both WAVs go to the session's render directory.
 */
class LabRenderer(private val lab: LabContext) {

    /** The recipe in [text], validated against the registry's modifiers; errors become a 400 listing every problem. */
    fun parseRecipe(text: String, registry: DefaultStrategyRegistry = lab.registry): TransitionRecipe {
        val parsed = RecipeCodec.parse(text)
        val recipe = parsed.recipe
        val problems = if (recipe == null) parsed.problems else parsed.locate(RecipeValidator(registry.modifierIds.toSet()).validate(recipe).problems)
        val errors = problems.filter { it.isError }
        if (recipe == null || errors.isNotEmpty()) {
            throw LabError(400, "the recipe has errors: " + errors.joinToString("; ") { it.toString() })
        }
        return recipe
    }

    fun render(spec: RenderSpec, job: LabJobs.Job? = null, progressFrom: Double = 0.0, progressTo: Double = 1.0): LabRenderResult {
        fun report(f: Double, msg: String) = job?.report(progressFrom + (progressTo - progressFrom) * f, msg)
        val a = lab.track(spec.a)
        val b = lab.track(spec.b)
        val prefs = lab.prefs(spec.style)

        var registry = lab.registry
        var strategyId = spec.strategy
        if (spec.recipeText != null) {
            val recipe = parseRecipe(spec.recipeText, registry)
            registry = registry.withStrategies(RecipeStrategy(recipe))
            if (strategyId == null) strategyId = recipe.strategyId
            else if (strategyId != recipe.strategyId) throw LabError(400, "the recipe is '${recipe.strategyId}' but the request asks for '$strategyId'")
        }
        val preset: StrategyPreset? = spec.preset?.let { id ->
            lab.profile.presetLookup.preset(id) ?: throw LabError(400, "unknown preset '$id'")
        }
        if (preset != null) {
            if (strategyId == null) strategyId = preset.strategyId
            else if (strategyId != preset.strategyId) throw LabError(400, "preset '${preset.id}' is for ${preset.strategyId}, not $strategyId")
        }

        report(0.02, "planning")
        val features = lab.features(a.ref, b.ref, prefs)
        val candidate = candidate(registry, prefs, a.ref, b.ref, features, strategyId, spec, preset)

        report(0.08, "rendering ${candidate.strategy.id}")
        val renderer = DefaultTransitionRenderer(lab.cli.loader, registry, lab.cli.separator)
        val rendered = try {
            renderer.render(a.ref, b.ref, candidate, features, RenderContext(prefs, spec.seed) { p -> report(0.08 + 0.52 * p.coerceIn(0.0, 1.0), "rendering ${candidate.strategy.id}") })
        } catch (e: LabError) {
            throw e
        } catch (e: Exception) {
            throw LabError(500, "render of '${candidate.strategy.id}' failed: ${e.message ?: e.javaClass.simpleName}")
        }

        report(0.62, "measuring")
        val input = input(prefs, a.ref, b.ref, rendered, features)
        val metrics = ArtifactMetrics.evaluate(rendered, input)

        report(0.7, "playing through the player")
        val (program, seams) = RenderSupport.contextProgram(a.ref, b.ref, rendered, prefs, spec.contextSec)
        val context = ProgramRenderer.render(program, lab.cli.streams, prefs, EngineLimits.DESKTOP, null, emptyMap(), spec.limiter)
        val contextMetrics = ArtifactMetrics.evaluateProgramOutput(context, seams)

        report(0.9, "writing")
        val contextFile = lab.newRenderFile("ctx")
        val id = contextFile.nameWithoutExtension.removePrefix("ctx-")
        val segmentFile = File(lab.renderDir, "seg-$id.wav")
        lab.writeWav(contextFile, context)
        lab.writeWav(segmentFile, rendered.audio)

        val sr = prefs.sampleRate.toDouble()
        val seamSec = seams.map { it / sr }
        val segStart = seamSec.firstOrNull() ?: 0.0
        val json = buildJsonObject {
            put("renderId", id)
            put("a", a.id); put("b", b.id)
            put("strategy", candidate.strategy.id)
            put("displayName", candidate.strategy.displayName)
            put("recipe", candidate.strategy is RecipeStrategy)
            put("unsavedRecipe", spec.recipeText != null)
            preset?.let { put("preset", it.id) }
            spec.style?.let { put("style", it) }
            put("score", LabJson.num(candidate.score, 4))
            putJsonObject("params") { for ((k, v) in rendered.plan.params.values.toSortedMap()) put(k, v) }
            putJsonArray("modifiers") { for (m in rendered.plan.modifiers) add(m) }
            put("contextUrl", "/files/${contextFile.name}")
            put("segmentUrl", "/files/${segmentFile.name}")
            put("contextSec", LabJson.num(spec.contextSec, 3))
            put("durationSec", LabJson.num(context.frames / sr, 4))
            put("segmentDurationSec", LabJson.num(rendered.audio.frames / sr, 4))
            put("contextFrames", context.frames)
            put("segmentFrames", rendered.audio.frames)
            put("sampleRate", prefs.sampleRate)
            put("segmentStartSec", LabJson.num(segStart, 4))
            put("segmentEndSec", LabJson.num(seamSec.getOrElse(1) { segStart + rendered.audio.frames / sr }, 4))
            putJsonArray("seams") { for (s in seamSec) add(LabJson.num(s, 4)) }
            putJsonArray("markers") {
                for (m in rendered.markers) addJsonObject {
                    put("t", LabJson.num(segStart + m.frame / sr, 4)); put("label", m.label)
                }
            }
            putJsonArray("lanes") {
                for (lane in rendered.plan.lanes) addJsonObject {
                    put("id", lane.id)
                    putJsonArray("points") {
                        for (p in lane.points) addJsonObject {
                            put("t", LabJson.num(segStart + p.outputSec, 4)); put("v", LabJson.num(p.value, 5))
                        }
                    }
                }
            }
            putJsonArray("notes") { for (n in rendered.plan.notes) add(n) }
            put("peaks", Peaks.of(context, LabContext.PEAK_BINS).toJson())
            put("metrics", metricsJson(metrics))
            put("contextMetrics", metricsJson(contextMetrics))
            put("worst", Verdict.worst(metrics.worst, contextMetrics.worst).name)
            putJsonArray("warnings") { for (w in rendered.report.warnings) add(w) }
            put("renderKey", rendered.report.renderKey)
            put("renderMillis", rendered.report.renderMillis)
        }
        report(1.0, "done")
        return LabRenderResult(id, candidate.strategy.id, preset?.id, features, contextFile, segmentFile, context.frames, rendered.audio.frames, json, metrics, contextMetrics)
    }

    /**
     * The candidate to render: the planner's best when no strategy is named, else that strategy's ranked candidate (or
     * one built for it when the planner blocked it, so every strategy can be heard). When the request carries values,
     * modifiers or a preset, `plan()` is re-run with the merged values and the modifiers re-attached the way
     * [RenderSupport.candidate] does for `muisc render --set`.
     */
    private fun candidate(
        registry: DefaultStrategyRegistry,
        prefs: TransitionPrefs,
        a: TrackRef,
        b: TrackRef,
        features: PairFeatures,
        strategyId: String?,
        spec: RenderSpec,
        preset: StrategyPreset?,
    ): PlanCandidate {
        val ranked = lab.planner(registry).plan(a, b, prefs, spec.seed, null)
        val base = when (strategyId) {
            null -> ranked.best
            else -> ranked.candidates.firstOrNull { it.strategy.id == strategyId } ?: run {
                val strategy = registry.strategy(strategyId) ?: throw LabError(400, "unknown strategy '$strategyId'")
                val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
                val params = Params.defaults(strategy.params).withAll(prefs.paramOverrides[strategyId].orEmpty())
                val plan = try {
                    strategy.plan(a.analysis, b.analysis, features, params, prefs, spec.seed)
                } catch (e: Exception) {
                    throw LabError(400, "strategy '$strategyId' cannot plan this pair: ${e.message ?: e.javaClass.simpleName}")
                }
                PlanCandidate(strategy, app, 0.0, plan)
            }
        }
        val modifierIds = spec.modifiers ?: preset?.modifiers
        if (spec.params.isEmpty() && modifierIds == null && preset == null) return base

        val strategy = base.strategy
        PresetValidation.check(strategy, spec.params)?.let { throw LabError(400, it) }
        val params = Params.defaults(strategy.params)
            .withAll(prefs.paramOverrides[strategy.id].orEmpty())
            .withAll(preset?.params?.values.orEmpty())
            .withAll(spec.params)
        var plan = try {
            strategy.plan(a.analysis, b.analysis, features, params, prefs, spec.seed)
        } catch (e: Exception) {
            throw LabError(400, "strategy '${strategy.id}' cannot plan this pair with these values: ${e.message ?: e.javaClass.simpleName}")
        }
        val modifiers = if (modifierIds == null) base.modifiers else modifierIds.map { id ->
            registry.modifier(id) ?: throw LabError(400, "unknown modifier '$id'. Known: ${registry.modifierIds.joinToString(", ")}")
        }
        for (m in modifiers) {
            val mp = ModifierParams.defaults(m, prefs)
            plan = ModifierParams.record(m.adjustPlan(plan, a.analysis, b.analysis, features, mp, prefs), m, mp)
        }
        return PlanCandidate(strategy, base.applicability, base.score, plan, modifiers)
    }

    /** The [TransitionInput] the renderer consumed, rebuilt so every metric (seam identity, onsets) can be evaluated. */
    private fun input(prefs: TransitionPrefs, a: TrackRef, b: TrackRef, rendered: RenderedTransition, features: PairFeatures): TransitionInput {
        val plan = rendered.plan
        val loader = lab.cli.loader
        val aAudio = loader.load(a, plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
        val bAudio = loader.load(b, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
        return TransitionInput(plan, a, b, features, aAudio, bAudio, LazyStemProvider(aAudio, bAudio, lab.cli.separator, plan.stemNeed))
    }

    companion object {
        fun metricsJson(report: MetricsReport) = kotlinx.serialization.json.buildJsonArray {
            for (m in report.metrics) addJsonObject {
                put("id", m.id)
                put("value", LabJson.num(m.value, 4))
                put("unit", m.unit)
                put("verdict", m.verdict.name)
                put("warnAt", LabJson.num(m.warnAt, 4))
                put("failAt", LabJson.num(m.failAt, 4))
            }
        }
    }
}
