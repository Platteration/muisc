package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import dev.muisc.metrics.Verdict
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import java.io.File
import kotlin.random.Random

/**
 * `muisc eval` — quality evaluation over a real music library.
 *
 *  1. Every audio file under the arguments (directories are walked recursively) is analysed through the cache;
 *     a file that cannot be decoded or analysed is reported and skipped, never fatal.
 *  2. The analyses are summarised: tempo confidence, grid confidence and key strength distributions and the tracks
 *     below the gates the planner uses.
 *  3. `--pairs N` ordered pairs are sampled from `--seed` ([LibraryEval.samplePairs]): half consecutive pairs of
 *     shuffled orders (planned with the previous pair's strategy, so the variety penalty applies as in a real
 *     shuffle; with `--order smart` the orders come from the smart-shuffle sequencer instead), a quarter the
 *     hardest by tempo stretch, a quarter the hardest by key distance.
 *  4. Each pair is planned with the same planner as `render` (profile, style and presets included) and the
 *     planner's pick is rendered and measured with the full metric set.
 *  5. `report.html` (self-contained), `results.csv` (one row per pair), `results.json` and `prefs.json` (the
 *     effective preferences, which every reproduce command passes back with `--prefs`) are written to `--out`.
 *
 * Exits non-zero when the FAIL rate (pairs with a failing metric, plus renders that threw) exceeds `--fail-on`;
 * the files are written first either way.
 */
class EvalCommand : MuiscCommand("eval") {

    override fun help(context: Context) = "Evaluate transition quality over a music library and write an HTML/CSV/JSON report."

    private val inputs by argument("PATH", help = "Audio files or directories (walked recursively).").file(mustExist = true).multiple(required = true)
    private val pairCount by option("--pairs", metavar = "N", help = "Ordered pairs to plan and render (default 30).").int().default(30)
    private val out by option("-o", "--out", metavar = "DIR", help = "Output directory (default ./muisc-eval).").file(canBeFile = false).default(File("muisc-eval"))
    private val failOn by option("--fail-on", metavar = "RATE", help = "Exit non-zero when the FAIL rate exceeds this (0.1 or 10%).")
        .convert { LibraryEval.parseRate(it) ?: fail("expected a rate between 0 and 1, or a percentage, got '$it'") }
    private val worstCount by option("--worst", metavar = "N", help = "Worst renders to list (default 10).").int().default(10)
    private val orderMode by option("--order", help = "How the consecutive-pair half is drawn: shuffle = random orders (default), smart = smart-shuffle orders (see `muisc order`).")
        .choice("shuffle", "smart").default("shuffle")

    override fun execute(ctx: CliContext) {
        if (pairCount < 1) throw CliktError("--pairs must be at least 1")
        val files = collect(inputs)
        if (files.isEmpty()) throw CliktError("no audio files found (looked for ${CliContext.SUPPORTED_EXTENSIONS.joinToString(", ")})")
        out.mkdirs()
        if (!out.isDirectory) throw CliktError("cannot create output directory ${out.path}")

        // 1. Analyse (cache-fronted). A track that cannot be analysed is skipped with a warning.
        echo("analysing ${files.size} file(s) (cached in ${ctx.cacheDir.path})…")
        val tracks = ArrayList<TrackRef>()
        val skipped = ArrayList<Pair<File, String>>()
        for ((i, f) in files.withIndex()) {
            try {
                tracks += ctx.trackRef(f)
            } catch (e: CliktError) {
                skipped += f to (e.message ?: "cannot analyse")
                echo("warning: skipped ${f.path}: ${e.message}", err = true)
            } catch (e: Exception) {
                skipped += f to (e.message ?: e.javaClass.simpleName)
                echo("warning: skipped ${f.path}: ${e.message ?: e.javaClass.simpleName}", err = true)
            }
            if ((i + 1) % 25 == 0) echo("  ${i + 1}/${files.size}")
        }
        if (tracks.size < 2) throw CliktError("need at least two analysable tracks, found ${tracks.size}" + if (skipped.isEmpty()) "" else " (${skipped.size} skipped)")
        val trackFiles = tracks.map { File(it.source.value) }
        val distributions = LibraryEval.distributions(tracks.map { it.analysis })
        val low = LibraryEval.lowConfidence(trackFiles.zip(tracks.map { it.analysis }))
        echo("")
        echo("analysis: ${tracks.size} track(s)" + if (skipped.isEmpty()) "" else ", ${skipped.size} skipped")
        echo(Fmt.table(listOf(listOf("", "min", "p10", "median", "p90", "max", "below gate")) + distributions.map { d ->
            listOf(d.label, Fmt.num(d.min), Fmt.num(d.p10), Fmt.num(d.median), Fmt.num(d.p90), Fmt.num(d.max), "${d.below} (< ${d.gate})")
        }, "  "))
        if (low.isNotEmpty()) {
            echo("  low confidence: ${low.size} track(s)" + if (low.size > 10) ", first 10:" else ":")
            for (l in low.take(10)) echo("    ${l.file.name}: ${l.reasons.joinToString(", ")}")
        }

        // 2. Sample the pairs.
        val features = HashMap<Pair<Int, Int>, dev.muisc.transitions.PairFeatures>()
        fun featuresOf(a: Int, b: Int) = features.getOrPut(a to b) { ctx.features(tracks[a], tracks[b]) }
        val smart: ((Random) -> List<Int>)? = if (orderMode == "smart") { rnd -> SmartOrder.order(ctx, tracks, rnd.nextLong()) } else null
        val hardness = { a: Int, b: Int ->
            val f = ctx.pairAnalyzer.features(tracks[a].analysis, tracks[b].analysis, ctx.prefs)
            LibraryEval.Hardness(f.stretchPercent, f.camelotDistance)
        }
        val picks = LibraryEval.samplePairs(tracks.size, pairCount, Random(seed), hardness, smart)
        echo("")
        echo("rendering ${picks.size} pair(s): " + LibraryEval.Source.values().filter { s ->
            when (s) { LibraryEval.Source.SHUFFLE -> smart == null; LibraryEval.Source.SMART -> smart != null; else -> true }
        }
            .joinToString(", ") { s -> "${picks.count { it.source == s }} ${s.label}" })

        // 3. Plan and render each pair.
        val prefsFile = File(out, "prefs.json").absoluteFile
        prefsFile.writeText(PrefsIo.PRETTY.encodeToString(TransitionPrefs.serializer(), ctx.prefs))
        val reproDir = File(out, "repro").absoluteFile
        val rows = ArrayList<LibraryEval.Row>()
        for ((i, p) in picks.withIndex()) {
            val a = tracks[p.a]
            val b = tracks[p.b]
            val f = featuresOf(p.a, p.b)
            val previous = p.previousInChain?.let { rows.getOrNull(it)?.strategyId }
            val reproduce = reproduceCommand(ctx, trackFiles[p.a], trackFiles[p.b], previous, prefsFile, File(reproDir, "pair-%03d.wav".format(i + 1)))
            var strategyId: String? = null
            val row = try {
                val ranked = ctx.planner.plan(a, b, ctx.prefs, seed, previous)
                val candidate = RenderSupport.candidate(ctx, ranked, a, b, f, null, emptyList(), emptyMap(), seed)
                strategyId = candidate.strategy.id
                val r = RenderSupport.renderWithMetrics(ctx, a, b, candidate, f, seed)
                LibraryEval.Row(i + 1, p.source, trackFiles[p.a], trackFiles[p.b], f.stretchPercent, f.camelotDistance, previous,
                    r.strategyId, r.plan.modifiers, candidate.score, r.metrics, r.renderMillis, r.rendered.audio.durationSec, null, reproduce)
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                LibraryEval.Row(i + 1, p.source, trackFiles[p.a], trackFiles[p.b], f.stretchPercent, f.camelotDistance, previous,
                    strategyId, emptyList(), 0.0, null, 0, 0.0, e.message ?: e.javaClass.simpleName, reproduce)
            }
            rows += row
            echo("  %3d  %-5s  %-22s  %-4s  %s → %s%s".format(
                row.index, Fmt.verdictMark(row.verdict), row.strategyId ?: "—", "${row.renderMillis}ms".padStart(4),
                row.a.name, row.b.name, row.error?.let { "  ERROR $it" } ?: "",
            ))
        }

        // 4. Aggregate and write.
        val failRate = LibraryEval.failRate(rows)
        val report = EvalReport(
            inputs = inputs, tracks = tracks.size, skipped = skipped, distributions = distributions, lowConfidence = low,
            rows = rows, seed = seed, failOn = failOn, worstCount = worstCount, prefsFile = prefsFile,
        )
        val html = File(out, "report.html")
        val csv = File(out, "results.csv")
        val json = File(out, "results.json")
        report.writeHtml(html)
        report.writeCsv(csv)
        report.writeJson(json)

        echo("")
        echo("strategies chosen:")
        echo(Fmt.table(listOf(listOf("strategy", "chosen", "FAIL", "WARN", "median ms")) + LibraryEval.strategyStats(rows).map {
            listOf(it.id, "${it.chosen}", "${it.fails}", "${it.warns}", Fmt.num(it.medianRenderMillis, 0))
        }, "  "))
        val worst = LibraryEval.worst(rows, worstCount)
        if (worst.isNotEmpty()) {
            echo("worst renders:")
            for (w in worst) {
                echo("  #${w.index} ${Fmt.verdictMark(w.verdict)} ${w.strategyId ?: "—"}  ${w.a.name} → ${w.b.name}: ${w.problems}")
                echo("      ${w.reproduce}")
            }
        }
        echo("")
        echo("pairs ${rows.size}: FAIL ${Fmt.pct(failRate)}, WARN ${Fmt.pct(LibraryEval.warnRate(rows))}, " +
            "errors ${rows.count { it.error != null }}, median render ${Fmt.num(LibraryEval.median(rows.filter { it.error == null }.map { it.renderMillis.toDouble() }), 0)} ms")
        echo("report: ${html.absolutePath}")
        echo("csv:    ${csv.absolutePath}")
        echo("json:   ${json.absolutePath}")
        echoElapsed("eval")
        val limit = failOn
        if (limit != null && failRate > limit) {
            throw CliktError("FAIL rate ${Fmt.pct(failRate)} exceeds --fail-on ${Fmt.pct(limit)} (${rows.count { it.verdict == Verdict.FAIL }} of ${rows.size} pairs)")
        }
    }

    /** Files under [inputs]: plain files as given, directories walked recursively for supported extensions; sorted, de-duplicated. */
    private fun collect(inputs: List<File>): List<File> {
        val out = LinkedHashSet<File>()
        for (i in inputs) {
            if (i.isDirectory) {
                i.walkTopDown().filter { it.isFile && it.extension.lowercase() in CliContext.SUPPORTED_EXTENSIONS }
                    .map { it.absoluteFile }.sortedBy { it.path }.forEach { out += it }
            } else {
                out += i.absoluteFile
            }
        }
        return out.toList()
    }

    /**
     * The `muisc render` command line that re-renders one pair exactly: same seed, same previous strategy, the
     * effective prefs written to [prefsFile] (style, `--set-pref`, rate and presets already folded in), the same
     * profile (pins and learned weights steer the planner), `--preset` when one was given, and the same cache.
     */
    private fun reproduceCommand(ctx: CliContext, a: File, b: File, previous: String?, prefsFile: File, wav: File): String {
        val q = LibraryEval::shellQuote
        return buildString {
            append("muisc render ").append(q(a.path)).append(' ').append(q(b.path))
            append(" --seed ").append(seed)
            previous?.let { append(" --previous ").append(q(it)) }
            append(" --prefs ").append(q(prefsFile.path))
            ctx.profile?.let { append(" --profile-dir ").append(q(it.dir.absolutePath)) }
            ctx.preset?.let { append(" --preset ").append(q(it.id)) }
            append(" --cache-dir ").append(q(ctx.cacheDir.absolutePath))
            append(" -o ").append(q(wav.path)).append(" --context 8")
        }
    }
}
