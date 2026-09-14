package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.metrics.ArtifactMetrics
import dev.muisc.metrics.MetricsReport
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * `muisc render` — the core listen-and-iterate command: plan A → B (or force a strategy), render it, and write
 *
 *  - `out.wav` — the transition **segment** alone (what the player splices in);
 *  - `out.plan.json` — the [TransitionPlan], replayable and diffable;
 *  - `out.report.json` — the `RenderReport` plus the full [MetricsReport];
 *  - `out.context.wav` (with `--context N`) — A's last N seconds + the segment + B's first N seconds, built as a
 *    [dev.muisc.transitions.PlaybackProgram] and run through the real [dev.muisc.player.ProgramPlayer], so the two
 *    seams you hear are exactly the seams the player produces.
 *
 * Prints the markers, the automation lanes, every metric verdict and the `RenderKey` that identifies this render.
 */
class RenderCommand : MuiscCommand("render") {

    override fun help(context: Context) = "Render one transition to WAV with its plan, report and metrics."

    private val a by argument("A", help = "Outgoing track.").file()
    private val b by argument("B", help = "Incoming track.").file()
    private val out by option("-o", "--out", metavar = "OUT.WAV", help = "Output WAV (side-cars are written next to it).").file().required()
    private val strategy by option("--strategy", metavar = "ID", help = "Strategy to render (default: the planner's best).")
    private val modifiers by option("--modifier", metavar = "ID", help = "Force a modifier (repeatable).").multiple()
    private val sets by option("--set", metavar = "ID=VALUE", help = "Override a strategy parameter (repeatable).").multiple()
    private val contextSec by option("--context", metavar = "SEC", help = "Also write out.context.wav with N seconds of A and B around the segment.").double()
    private val noLimiter by option("--no-limiter", help = "Disable the player's true-peak limiter in the context render.").flag()
    private val previous by option("--previous", metavar = "ID", help = "Previous strategy id (variety penalty when picking the best).")
    private val json by option("--json", help = "Print the result as JSON.").flag()

    override fun execute(ctx: CliContext) {
        val aRef = ctx.trackRef(a)
        val bRef = ctx.trackRef(b)
        val features = ctx.features(aRef, bRef)
        val ranked = ctx.planner.plan(aRef, bRef, ctx.prefs, seed, previous)
        val candidate = RenderSupport.candidate(ctx, ranked, aRef, bRef, features, strategy, modifiers, Sets.parse(sets), seed)
        val result = RenderSupport.renderWithMetrics(ctx, aRef, bRef, candidate, features, seed)

        val wavFile = out.absoluteFile
        RenderSupport.writeWav(wavFile, result.rendered.audio)
        val planFile = RenderSupport.sidecar(wavFile, ".plan.json")
        val reportFile = RenderSupport.sidecar(wavFile, ".report.json")
        planFile.writeText(PLAN_JSON.encodeToString(TransitionPlan.serializer(), result.plan))
        reportFile.writeText(reportJson(result, aRef, bRef).toString())
        if (!json) echo(describe(result, aRef, ctx))

        var contextFile: File? = null
        var contextMetrics: MetricsReport? = null
        if (contextSec != null) {
            if (contextSec!! < 0) throw CliktError("--context must not be negative")
            val (program, seams) = RenderSupport.contextProgram(aRef, bRef, result.rendered, ctx.prefs, contextSec!!)
            contextFile = RenderSupport.sidecar(wavFile, ".context.wav")
            val audio = RenderSupport.renderProgram(ctx, program, contextFile, limiter = !noLimiter)
            contextMetrics = ArtifactMetrics.evaluateProgramOutput(audio, seams)
            if (!json) {
                echo("context render (A tail + segment + B head through the player): ${Fmt.sec(audio.durationSec)}, " +
                    "seams at ${seams.joinToString(", ") { Fmt.sec(it.toDouble() / ctx.sampleRate) }}")
                echo(Fmt.metrics(contextMetrics, "  "))
            }
        }

        if (json) {
            echo(PRETTY.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("wav", wavFile.path); put("plan", planFile.path); put("report", reportFile.path)
                contextFile?.let { put("context", it.path) }
                put("strategy", result.strategyId)
                put("score", candidate.score)
                put("renderKey", result.rendered.report.renderKey)
                put("metrics", PRETTY.parseToJsonElement(result.metrics.toJson()))
                contextMetrics?.let { put("contextMetrics", PRETTY.parseToJsonElement(it.toJson())) }
            }))
        } else {
            echo("")
            echo("wav:     ${wavFile.path}")
            echo("plan:    ${planFile.path}")
            echo("report:  ${reportFile.path}")
            contextFile?.let { echo("context: ${it.path}") }
        }
        echoElapsed("rendered ${result.strategyId}")
    }

    private fun describe(r: RenderSupport.Result, a: TrackRef, ctx: CliContext): String = buildString {
        val rendered = r.rendered
        append("strategy: ").append(r.strategyId).append(" (").append(r.candidate.strategy.displayName).append(")")
        if (r.plan.modifiers.isNotEmpty()) append(" +").append(r.plan.modifiers.joinToString("+"))
        append("  score ").append(Fmt.num(r.candidate.score, 3)).append('\n')
        append("plan:     ").append(Fmt.planLine(r.plan, a.analysis.grid.bpm, ctx.sampleRate)).append('\n')
        append("audio:    ").append(rendered.audio.frames).append(" frames (").append(Fmt.sec(rendered.audio.durationSec)).append("), ")
            .append(rendered.audio.channelCount).append(" ch @ ").append(rendered.audio.sampleRate).append(" Hz  peak ")
            .append(Fmt.num(rendered.report.peak.toDouble(), 3)).append("  true peak ").append(Fmt.num(rendered.report.truePeakDbtp.toDouble(), 2))
            .append(" dBTP  ").append(Fmt.num(rendered.report.integratedLufs.toDouble(), 1)).append(" LUFS\n")
        if (r.plan.params.values.isNotEmpty()) {
            append("params:   ").append(r.plan.params.values.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }).append('\n')
        }
        append("markers (").append(rendered.markers.size).append("):\n")
        append(if (rendered.markers.isEmpty()) "  (none)" else Fmt.table(
            listOf(listOf("frame", "time", "label")) + rendered.markers.map { listOf("${it.frame}", Fmt.sec(it.frame.toDouble() / ctx.sampleRate), it.label) }, "  ",
        ))
        append('\n')
        append("lanes (").append(r.plan.lanes.size).append("):\n")
        append(if (r.plan.lanes.isEmpty()) "  (none)" else Fmt.table(
            listOf(listOf("lane", "points", "range", "span")) + r.plan.lanes.map { lane ->
                val vs = lane.points.map { it.value }
                listOf(
                    lane.id, "${lane.points.size}",
                    if (vs.isEmpty()) "—" else "${Fmt.num(vs.min(), 2)}..${Fmt.num(vs.max(), 2)}",
                    if (lane.points.isEmpty()) "—" else "${Fmt.sec(lane.points.first().outputSec)} .. ${Fmt.sec(lane.points.last().outputSec)}",
                )
            },
            "  ",
        ))
        append('\n')
        if (rendered.report.warnings.isNotEmpty()) append("warnings: ").append(rendered.report.warnings.joinToString("; ")).append('\n')
        append(Fmt.metrics(r.metrics, "  ")).append('\n')
        append("renderKey: ").append(rendered.report.renderKey).append('\n')
        append("render:    ").append(rendered.report.renderMillis).append(" ms in the strategy, ").append(r.renderMillis).append(" ms total\n")
    }

    private fun reportJson(r: RenderSupport.Result, a: TrackRef, b: TrackRef): JsonObject = buildJsonObject {
        put("a", a.source.value); put("b", b.source.value)
        put("strategy", r.strategyId); put("score", r.candidate.score); put("seed", seed)
        putJsonArray("modifiers") { for (m in r.plan.modifiers) add(m) }
        put("renderKey", r.rendered.report.renderKey)
        put("report", PRETTY.encodeToJsonElement(dev.muisc.transitions.RenderReport.serializer(), r.rendered.report))
        put("metrics", PRETTY.parseToJsonElement(r.metrics.toJson()))
        put("markers", buildJsonArray {
            for (m in r.rendered.markers) add(buildJsonObject { put("frame", m.frame); put("label", m.label) })
        })
    }

    private companion object {
        val PLAN_JSON = Json { prettyPrint = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
        val PRETTY = Json { prettyPrint = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    }
}

/** `--set id=value` parsing, shared by `render`, `ab` and `sweep`. */
object Sets {
    fun parse(assignments: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (s in assignments) {
            val i = s.indexOf('=')
            if (i <= 0) throw CliktError("--set expects ID=VALUE, got '$s'")
            out[s.substring(0, i).trim()] = s.substring(i + 1).trim()
        }
        return out
    }
}
