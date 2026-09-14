package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import java.io.File

/**
 * `muisc score` — "which of these tracks go together, and how?": the pairwise matrix of the best strategy and its
 * score for every ordered pair of a folder (or an explicit list) of files. Plans only — nothing is rendered, so a
 * folder of twenty tracks takes as long as analysing them once.
 */
class ScoreCommand : MuiscCommand("score") {

    override fun help(context: Context) = "Pairwise best-strategy/score matrix over a folder of tracks."

    private val inputs by argument("DIR|FILE", help = "A directory of audio files, or the files themselves.").file().multiple(required = true)
    private val csv by option("--csv", metavar = "MATRIX.CSV", help = "Write the matrix as CSV.").file()
    private val html by option("--html", help = "Also write an HTML heat-map (next to --csv, or ./matrix.html).").flag()
    private val htmlOut by option("--html-out", metavar = "MATRIX.HTML", help = "Explicit path for the HTML heat-map.").file()

    override fun execute(ctx: CliContext) {
        val files = expand(inputs)
        if (files.size < 2) throw CliktError("score needs at least two audio files (found ${files.size})")
        val tracks = files.map { ctx.trackRef(it) }
        echo("analysed ${tracks.size} track(s):")
        echo(Fmt.table(listOf(listOf("#", "track", "bpm", "key", "lufs", "outro", "intro")) + tracks.mapIndexed { i, t ->
            listOf("${i + 1}", t.title, Fmt.num(t.analysis.tempo.bpm, 1), Fmt.key(t.analysis),
                Fmt.num(t.analysis.loudness.integratedLufs.toDouble(), 1), t.analysis.outro.name, t.analysis.intro.name)
        }, "  "))

        val cells = HashMap<Pair<Int, Int>, Pair<String, Double>>()
        for (i in tracks.indices) for (j in tracks.indices) {
            if (i == j) continue
            val ranked = ctx.planner.plan(tracks[i], tracks[j], ctx.prefs, seed)
            val best = ranked.candidates.firstOrNull() ?: continue
            cells[i to j] = best.strategy.id to best.score
        }

        echo("")
        echo("best strategy (rows = A, columns = B):")
        val names = tracks.map { it.title }
        val rows = ArrayList<List<String>>()
        rows += listOf("A \\ B") + names
        for (i in tracks.indices) {
            rows += listOf(names[i]) + tracks.indices.map { j ->
                cells[i to j]?.let { "${it.first} ${Fmt.num(it.second, 2)}" } ?: "—"
            }
        }
        echo(Fmt.table(rows, "  "))

        val ranking = cells.entries.sortedByDescending { it.value.second }
        echo("")
        echo("most compatible pairs:")
        echo(Fmt.table(ranking.take(10).map { (k, v) -> listOf("${names[k.first]} → ${names[k.second]}", v.first, Fmt.num(v.second, 3)) }, "  "))

        csv?.let { f ->
            val sb = StringBuilder()
            sb.append(Fmt.csvRow(listOf("a", "b", "strategy", "score"))).append('\n')
            for ((k, v) in cells.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
                sb.append(Fmt.csvRow(listOf(names[k.first], names[k.second], v.first, Fmt.num(v.second, 6)))).append('\n')
            }
            f.absoluteFile.parentFile?.mkdirs()
            f.writeText(sb.toString())
            echo("csv:  ${f.absolutePath}")
        }
        val htmlFile = htmlOut ?: if (html) csv?.let { File(it.absoluteFile.parentFile, it.nameWithoutExtension + ".html") } ?: File("matrix.html") else null
        htmlFile?.let { f ->
            Reports.writeMatrixHtml(f, "Compatibility matrix", names, cells)
            echo("html: ${f.absolutePath}")
        }
        echoElapsed("scored ${cells.size} ordered pair(s)")
    }

    private fun expand(inputs: List<File>): List<File> {
        val out = LinkedHashSet<File>()
        for (f in inputs) {
            if (f.isDirectory) {
                f.listFiles()?.sortedBy { it.name }?.filter { it.isFile && it.extension.lowercase() in CliContext.SUPPORTED_EXTENSIONS }?.forEach { out += it }
            } else {
                out += f
            }
        }
        return out.toList()
    }

}
