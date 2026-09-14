package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.transitions.ParamSpec
import java.io.File
import java.util.Locale

/**
 * `muisc sweep` — the parameter-tuning loop: render one strategy across a grid of parameter values, write every
 * render, a `sweep.csv` with the parameter values next to every metric, and an SVG of one metric against the first
 * parameter so a trend is visible without opening a spreadsheet.
 *
 * `--param overlapBars=4:32:5` means five values from 4 to 32 inclusive; integer parameters are rounded and every
 * value is clamped into the [ParamSpec]'s declared range. Several `--param` switches form the cartesian grid.
 */
class SweepCommand : MuiscCommand("sweep") {

    override fun help(context: Context) = "Sweep a strategy's parameters over a grid and report the metrics."

    private val a by argument("A").file()
    private val b by argument("B").file()
    private val strategyId by option("--strategy", metavar = "ID", help = "Strategy to sweep.").required()
    private val params by option("--param", metavar = "ID=LO:HI:STEPS", help = "Parameter range (repeatable).").multiple(required = true)
    private val out by option("-o", "--out", metavar = "DIR", help = "Output directory.").file(canBeFile = false).required()
    private val metricId by option("--metric", metavar = "ID", help = "Metric to plot against the first parameter.").default("levelJumpDb")
    private val modifiers by option("--modifier", metavar = "ID", help = "Force a modifier (repeatable).").multiple()

    override fun execute(ctx: CliContext) {
        out.mkdirs()
        if (!out.isDirectory) throw CliktError("cannot create output directory ${out.path}")
        val strategy = ctx.registry.strategy(strategyId)
            ?: throw CliktError("unknown strategy '$strategyId'. Known: ${ctx.registry.strategyIds.joinToString(", ")}")
        val axes = params.map { Axis.parse(it, strategy.params) }
        if (axes.any { it.values.isEmpty() }) throw CliktError("every --param needs at least one step")

        val aRef = ctx.trackRef(a)
        val bRef = ctx.trackRef(b)
        val features = ctx.features(aRef, bRef)
        val ranked = ctx.planner.plan(aRef, bRef, ctx.prefs, seed)

        val combos = cartesian(axes)
        echo("sweeping ${strategy.id} over ${combos.size} combination(s): ${axes.joinToString(", ") { "${it.id}=${it.values.joinToString("/")}" }}")
        val rows = ArrayList<Reports.Row>()
        for (combo in combos) {
            val id = combo.entries.joinToString("_") { "${it.key}${sanitize(it.value)}" }
            val candidate = RenderSupport.candidate(ctx, ranked, aRef, bRef, features, strategy.id, modifiers, combo, seed)
            val result = try {
                RenderSupport.renderWithMetrics(ctx, aRef, bRef, candidate, features, seed)
            } catch (e: CliktError) {
                echo("$id: skipped — ${e.message}")
                continue
            }
            val wav = File(out, "$id.wav")
            RenderSupport.writeWav(wav, result.rendered.audio)
            rows += Reports.Row(
                id = id,
                wav = wav.name,
                contextWav = null,
                extra = LinkedHashMap(combo) + mapOf("seconds" to Fmt.num(result.rendered.audio.durationSec, 2)),
                metrics = result.metrics,
                lufs = result.rendered.report.integratedLufs.toDouble(),
            )
            echo("  ${id.padEnd(28)} ${Fmt.verdictMark(result.metrics.worst)}  $metricId=${result.metrics.value(metricId)?.let { Fmt.num(it, 3) } ?: "—"}  ${Fmt.sec(result.rendered.audio.durationSec)}")
        }
        if (rows.isEmpty()) throw CliktError("no combination rendered successfully")

        Reports.levelMatch(rows)
        val csv = File(out, "sweep.csv")
        Reports.writeCsv(csv, rows, "run")
        val html = File(out, "index.html")
        Reports.writeHtml(html, "Sweep — ${strategy.id}", "${aRef.title} → ${bRef.title} · seed $seed · ${rows.size} runs", rows)

        val axis = axes.first()
        val points = rows.mapNotNull { r ->
            val x = r.extra[axis.id]?.toDoubleOrNull() ?: return@mapNotNull null
            val y = r.metrics.value(metricId) ?: return@mapNotNull null
            x to y
        }
        val svg = File(out, "sweep.svg")
        Reports.writeSvg(svg, "${strategy.id}: $metricId vs ${axis.id}", axis.id, metricId, points)
        if (points.isEmpty()) echo("note: '$metricId' is not in these reports, so the plot is empty (available: ${Reports.metricIds(rows).joinToString(", ")})")

        echo("")
        echo("csv:  ${csv.absolutePath}")
        echo("svg:  ${svg.absolutePath}")
        echo("html: ${html.absolutePath}")
        echoElapsed("swept ${rows.size} combination(s)")
    }

    private fun sanitize(v: String) = v.replace('.', 'p').replace("-", "m").filter { it.isLetterOrDigit() || it == 'p' || it == 'm' }

    private fun cartesian(axes: List<Axis>): List<Map<String, String>> {
        var acc: List<Map<String, String>> = listOf(emptyMap())
        for (axis in axes) {
            acc = acc.flatMap { base -> axis.values.map { v -> base + (axis.id to v) } }
        }
        return acc
    }

    /** One `--param id=lo:hi:steps` axis, already resolved against the strategy's [ParamSpec]. */
    class Axis(val id: String, val values: List<String>) {
        companion object {
            fun parse(spec: String, specs: List<ParamSpec>): Axis {
                val eq = spec.indexOf('=')
                if (eq <= 0) throw CliktError("--param expects ID=LO:HI:STEPS, got '$spec'")
                val id = spec.substring(0, eq).trim()
                val decl = specs.firstOrNull { it.id == id }
                    ?: throw CliktError("unknown parameter '$id'. Known: ${specs.joinToString(", ") { it.id }}")
                val body = spec.substring(eq + 1).trim()
                if (body.contains(',') || !body.contains(':')) {
                    val values = body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (values.isEmpty()) throw CliktError("--param $id has no values")
                    return Axis(id, values)
                }
                val parts = body.split(':')
                if (parts.size != 3) throw CliktError("--param expects ID=LO:HI:STEPS or ID=v1,v2,…, got '$spec'")
                val lo = parts[0].toDoubleOrNull() ?: throw CliktError("--param $id: '${parts[0]}' is not a number")
                val hi = parts[1].toDoubleOrNull() ?: throw CliktError("--param $id: '${parts[1]}' is not a number")
                val steps = parts[2].toIntOrNull() ?: throw CliktError("--param $id: '${parts[2]}' is not an integer")
                if (steps < 1) throw CliktError("--param $id: steps must be at least 1")
                val raw = (0 until steps).map { if (steps == 1) lo else lo + (hi - lo) * it / (steps - 1) }
                val values = when (decl) {
                    is ParamSpec.IntSpec -> raw.map { Math.round(it).toInt().coerceIn(decl.min, decl.max).toString() }.distinct()
                    is ParamSpec.DoubleSpec -> raw.map { String.format(Locale.ROOT, "%.6g", it.coerceIn(decl.min, decl.max)).trim() }.distinct()
                    is ParamSpec.BoolSpec -> raw.map { (it >= 0.5).toString() }.distinct()
                    is ParamSpec.ChoiceSpec -> throw CliktError("--param $id is a choice parameter; use ID=${decl.choices.joinToString(",")}")
                }
                return Axis(id, values)
            }
        }
    }
}
