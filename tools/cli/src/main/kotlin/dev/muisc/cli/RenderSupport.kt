package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.WavIo
import dev.muisc.metrics.ArtifactMetrics
import dev.muisc.metrics.MetricsReport
import dev.muisc.player.EngineLimits
import dev.muisc.player.ProgramRenderer
import dev.muisc.transitions.DefaultProgramBuilder
import dev.muisc.transitions.LazyStemProvider
import dev.muisc.transitions.ModifierParams
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.PlaybackProgram
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.Segment
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.DeckGain
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The bits every rendering command (`render`, `mix`, `ab`, `sweep`, `check`) needs and that are not in the engine:
 * picking / rebuilding a [PlanCandidate] from CLI switches, rebuilding the [TransitionInput] the renderer consumed
 * (so [ArtifactMetrics] can see the sources), assembling the A-tail + segment + B-head context program, and writing
 * the WAV / JSON side-cars.
 */
object RenderSupport {

    /** Every render written by the CLI is 24-bit PCM: universally playable and well under the 1e-6 seam tolerance. */
    val ENCODING = WavIo.Encoding.PCM24

    /**
     * The candidate to render, given the CLI's `--strategy` / `--modifier` / `--set` switches.
     *
     * With no switches this is the planner's best. `--strategy` picks that id out of the ranking (or, when the
     * planner skipped it because it is blocked on this pair, builds it anyway so the user can still listen to it).
     * `--set` / `--modifier` re-run `strategy.plan()` with the merged params and re-attach the modifiers exactly
     * the way [dev.muisc.transitions.planner.DefaultTransitionPlanner] does, so `plan.params` stays self-describing
     * and the [dev.muisc.transitions.RenderKey] covers the overrides.
     */
    fun candidate(
        ctx: CliContext,
        ranked: RankedPlans,
        a: TrackRef,
        b: TrackRef,
        features: PairFeatures,
        strategyId: String?,
        modifierIds: List<String>,
        sets: Map<String, String>,
        seed: Long,
    ): PlanCandidate {
        val prefs = ctx.prefs
        val base = when {
            strategyId == null -> ranked.candidates.firstOrNull() ?: throw CliktError("the planner produced no candidate for this pair")
            else -> ranked.candidates.firstOrNull { it.strategy.id == strategyId } ?: forced(ctx, a, b, features, strategyId, seed)
        }
        if (sets.isEmpty() && modifierIds.isEmpty()) return base

        val strategy = base.strategy
        unknownParams(strategy.params.map { it.id }, sets.keys).takeIf { it.isNotEmpty() }?.let {
            throw CliktError("unknown parameter(s) for '${strategy.id}': ${it.joinToString(", ")}. Known: ${strategy.params.joinToString(", ") { p -> p.id }}")
        }
        val params = Params.defaults(strategy.params)
            .withAll(prefs.paramOverrides[strategy.id].orEmpty())
            .withAll(sets)
        var plan = try {
            strategy.plan(a.analysis, b.analysis, features, params, prefs, seed)
        } catch (e: Exception) {
            throw CliktError("strategy '${strategy.id}' cannot plan this pair: ${e.message ?: e.javaClass.simpleName}")
        }
        val modifiers = if (modifierIds.isEmpty()) base.modifiers else modifierIds.map { id ->
            ctx.registry.modifier(id) ?: throw CliktError("unknown modifier '$id'. Known: ${ctx.registry.modifierIds.joinToString(", ")}")
        }
        for (m in modifiers) {
            val mp = ModifierParams.defaults(m, prefs)
            plan = ModifierParams.record(m.adjustPlan(plan, a.analysis, b.analysis, features, mp, prefs), m, mp)
        }
        return PlanCandidate(strategy, base.applicability, base.score, plan, modifiers)
    }

    private fun unknownParams(known: List<String>, given: Set<String>): List<String> = given.filter { it !in known }

    /** A candidate for a strategy the planner did not rank (blocked or disabled) — so `--strategy` always works. */
    fun forced(ctx: CliContext, a: TrackRef, b: TrackRef, features: PairFeatures, strategyId: String, seed: Long): PlanCandidate {
        val strategy = ctx.registry.strategy(strategyId)
            ?: throw CliktError("unknown strategy '$strategyId'. Known: ${ctx.registry.strategyIds.joinToString(", ")}")
        val prefs = ctx.prefs
        val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
        val params = Params.defaults(strategy.params).withAll(prefs.paramOverrides[strategyId].orEmpty())
        val plan = try {
            strategy.plan(a.analysis, b.analysis, features, params, prefs, seed)
        } catch (e: Exception) {
            throw CliktError("strategy '$strategyId' cannot plan this pair: ${e.message ?: e.javaClass.simpleName}")
        }
        return PlanCandidate(strategy, app, 0.0, plan)
    }

    /**
     * The exact [TransitionInput] the renderer built for [plan] (same windows, same deck gain), so the full metric
     * set — seam identity, bass cancellation, source-onset exclusion — can be evaluated after the fact.
     */
    fun input(ctx: CliContext, a: TrackRef, b: TrackRef, plan: TransitionPlan, features: PairFeatures): TransitionInput {
        val prefs = ctx.prefs
        val aAudio = ctx.loader.load(a, plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
        val bAudio = ctx.loader.load(b, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
        return TransitionInput(plan, a, b, features, aAudio, bAudio, LazyStemProvider(aAudio, bAudio, ctx.separator, plan.stemNeed))
    }

    /** Renders [candidate] and evaluates the full metric set against the sources. */
    fun renderWithMetrics(ctx: CliContext, a: TrackRef, b: TrackRef, candidate: PlanCandidate, features: PairFeatures, seed: Long): Result {
        val started = System.nanoTime()
        val rendered = try {
            ctx.renderer.render(a, b, candidate, features, RenderContext(ctx.prefs, seed))
        } catch (e: CliktError) {
            throw e
        } catch (e: Exception) {
            throw CliktError("render of '${candidate.strategy.id}' failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val input = input(ctx, a, b, rendered.plan, features)
        val metrics = ArtifactMetrics.evaluate(rendered, input)
        return Result(candidate, rendered, input, metrics, (System.nanoTime() - started) / 1_000_000)
    }

    class Result(
        val candidate: PlanCandidate,
        val rendered: RenderedTransition,
        val input: TransitionInput,
        val metrics: MetricsReport,
        val renderMillis: Long,
    ) {
        val plan: TransitionPlan get() = rendered.plan
        val strategyId: String get() = candidate.strategy.id
    }

    /**
     * A-tail + rendered segment + B-head as a real [PlaybackProgram]: the seams around the segment are exactly the
     * ones [dev.muisc.player.ProgramPlayer] produces at playback, which is the point of `--context`.
     * Returns the program and the output frame of each seam.
     */
    fun contextProgram(
        a: TrackRef,
        b: TrackRef,
        rendered: RenderedTransition,
        prefs: TransitionPrefs,
        contextSec: Double,
        playback: PlaybackContext = PlaybackContext.PLAYLIST,
    ): Pair<PlaybackProgram, List<Long>> {
        val builder = DefaultProgramBuilder()
        val aBody = builder.bodySegment(a, playback, prefs, null, rendered)
        val bBody = builder.bodySegment(b, playback, prefs, rendered, null)
        val ctxFrames = max(0L, Math.round(contextSec * prefs.sampleRate))
        val aFrom = max(aBody.fromFrame, aBody.toFrame - ctxFrames)
        val bTo = min(bBody.toFrame, bBody.fromFrame + ctxFrames)
        val segs = listOf(
            Segment.Body(a, aFrom, aBody.toFrame),
            Segment.Rendered(a, b, rendered),
            Segment.Body(b, bBody.fromFrame, max(bBody.fromFrame, bTo)),
        )
        val seams = seamFrames(PlaybackProgram(segs))
        return PlaybackProgram(segs) to seams
    }

    /** The output frame at which each segment of [program] gives way to the next. */
    fun seamFrames(program: PlaybackProgram): List<Long> {
        val out = ArrayList<Long>()
        var at = 0L
        for (s in program.segments.dropLast(1)) {
            at += PlaybackProgram(listOf(s)).totalFrames
            out += at
        }
        return out
    }

    /** Runs [program] through the real player into [file]; returns the rendered audio. */
    fun renderProgram(ctx: CliContext, program: PlaybackProgram, file: File?, limiter: Boolean = true, sharedGainDb: Float? = null): AudioBuffer {
        val audio = ProgramRenderer.render(program, ctx.streams, ctx.prefs, EngineLimits.DESKTOP, sharedGainDb, emptyMap(), limiter)
        if (file != null) writeWav(file, audio)
        return audio
    }

    fun writeWav(file: File, audio: AudioBuffer) {
        file.absoluteFile.parentFile?.mkdirs()
        WavIo.write(file, audio, ENCODING)
    }

    /** `out.wav` → `out.plan.json` (strips one known audio extension, never the whole name). */
    fun sidecar(out: File, suffix: String): File {
        val name = out.name
        val stem = if (name.endsWith(".wav", true)) name.dropLast(4) else name
        return File(out.absoluteFile.parentFile, "$stem$suffix")
    }
}
