package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.transitions.TransitionStrategy
import java.io.File

/**
 * `muisc ab` — render the same pair with every strategy and listen to them side by side: `dir/<id>.wav` (segment),
 * `dir/<id>.context.wav` (A-tail + segment + B-head through the real player), `dir/metrics.csv` and
 * `dir/index.html` with level-matched `<audio>` players and the whole metrics table.
 *
 * By default only the strategies the planner considers applicable are rendered; `--all` renders every registered
 * strategy including the blocked ones, which is exactly what you want when you are debugging a blocker.
 */
class AbCommand : MuiscCommand("ab") {

    override fun help(context: Context) = "Render one pair with every strategy and build a comparison page."

    private val a by argument("A").file()
    private val b by argument("B").file()
    private val out by option("-o", "--out", metavar = "DIR", help = "Output directory.").file(canBeFile = false).required()
    private val all by option("--all", help = "Include strategies the planner considers blocked.").flag()
    private val only by option("--strategies", metavar = "ID,ID", help = "Render only these strategies.").split(",")
    private val contextSec by option("--context", metavar = "SEC", help = "Seconds of A and B around each segment (0 disables).").double().default(8.0)
    private val noLimiter by option("--no-limiter", help = "Disable the player's limiter in the context renders.").flag()

    override fun execute(ctx: CliContext) {
        out.mkdirs()
        if (!out.isDirectory) throw CliktError("cannot create output directory ${out.path}")
        val aRef = ctx.trackRef(a)
        val bRef = ctx.trackRef(b)
        val features = ctx.features(aRef, bRef)
        val ranked = ctx.planner.plan(aRef, bRef, ctx.prefs, seed)
        val scores = ranked.candidates.associate { it.strategy.id to it.score }

        val chosen: List<TransitionStrategy> = when {
            only != null -> only!!.map { id ->
                ctx.registry.strategy(id.trim()) ?: throw CliktError("unknown strategy '${id.trim()}'. Known: ${ctx.registry.strategyIds.joinToString(", ")}")
            }
            all -> ctx.registry.strategies
            else -> ranked.candidates.map { it.strategy }
        }
        if (chosen.isEmpty()) throw CliktError("no strategy to render")

        val rows = ArrayList<Reports.Row>()
        val failures = ArrayList<String>()
        for (strategy in chosen) {
            val candidate = try {
                RenderSupport.candidate(ctx, ranked, aRef, bRef, features, strategy.id, emptyList(), emptyMap(), seed)
            } catch (e: CliktError) {
                failures += "${strategy.id}: ${e.message}"
                echo("${strategy.id}: skipped — ${e.message}")
                continue
            }
            val result = try {
                RenderSupport.renderWithMetrics(ctx, aRef, bRef, candidate, features, seed)
            } catch (e: CliktError) {
                failures += "${strategy.id}: ${e.message}"
                echo("${strategy.id}: skipped — ${e.message}")
                continue
            }
            val wav = File(out, "${strategy.id}.wav")
            RenderSupport.writeWav(wav, result.rendered.audio)
            var contextName: String? = null
            if (contextSec > 0) {
                val (program, _) = RenderSupport.contextProgram(aRef, bRef, result.rendered, ctx.prefs, contextSec)
                val cf = File(out, "${strategy.id}.context.wav")
                RenderSupport.renderProgram(ctx, program, cf, limiter = !noLimiter)
                contextName = cf.name
            }
            rows += Reports.Row(
                id = strategy.id,
                wav = wav.name,
                contextWav = contextName,
                extra = linkedMapOf(
                    "score" to Fmt.num(scores[strategy.id] ?: 0.0, 3),
                    "applicable" to candidate.applicability.applicable.toString(),
                    "modifiers" to result.plan.modifiers.joinToString("+"),
                    "bars" to Fmt.num(Fmt.bars(result.plan.expectedOutputFrames, aRef.analysis.grid.bpm, ctx.sampleRate), 1),
                    "seconds" to Fmt.num(result.rendered.audio.durationSec, 2),
                ),
                metrics = result.metrics,
                lufs = result.rendered.report.integratedLufs.toDouble(),
            )
            echo("${strategy.id.padEnd(22)} score ${Fmt.num(scores[strategy.id] ?: 0.0, 3)}  ${Fmt.sec(result.rendered.audio.durationSec)}  ${Fmt.verdictMark(result.metrics.worst)}  ${result.renderMillis} ms")
        }
        if (rows.isEmpty()) throw CliktError("every strategy failed to render (${failures.joinToString("; ")})")

        Reports.levelMatch(rows)
        val csv = File(out, "metrics.csv")
        Reports.writeCsv(csv, rows, "strategy")
        val html = File(out, "index.html")
        Reports.writeHtml(html, "A/B — ${aRef.title} → ${bRef.title}",
            "${Fmt.num(aRef.analysis.tempo.bpm, 1)} BPM ${Fmt.key(aRef.analysis)} → ${Fmt.num(bRef.analysis.tempo.bpm, 1)} BPM ${Fmt.key(bRef.analysis)} · seed $seed · ${rows.size} strategies",
            rows)
        echo("")
        echo("csv:  ${csv.absolutePath}")
        echo("html: ${html.absolutePath}")
        if (failures.isNotEmpty()) echo("skipped: ${failures.size} strategy(ies)")
        echoElapsed("rendered ${rows.size} strategy(ies)")
    }
}
