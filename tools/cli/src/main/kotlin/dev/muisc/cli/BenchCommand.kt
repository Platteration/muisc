package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

/** `muisc bench` — accuracy benches with known ground truth. */
class BenchCommand : CliktCommand(name = "bench") {
    override fun help(context: Context) = "Accuracy benches on synthetic material with known ground truth."
    override fun run() = Unit

    companion object {
        /** The command with its subcommands, ready to register. */
        fun build(): CliktCommand = BenchCommand().subcommands(BenchAnalysisCommand())
    }
}

/**
 * `muisc bench analysis` — generates [AnalysisBench] songs (tempo 60–180, all 24 keys, intro/outro variations,
 * leading silence, detune), runs the analyzer and prints tempo, beat, downbeat, key and trim accuracy, then a
 * table of the failing cases (`--all` for every case). `--seed` picks the song set (same seed, same songs).
 * Nothing is written to disk and the analysis cache is not used, so every run measures the analyzer as it is now.
 */
class BenchAnalysisCommand : MuiscCommand("analysis") {

    override fun help(context: Context) = "Measure tempo, beat, downbeat, key and trim accuracy on synthetic songs."

    private val songs by option("--songs", metavar = "N", help = "Number of songs (default 24: every key once).").int().default(24)
    private val all by option("--all", help = "List every case, not only the failing ones.").flag()

    override fun execute(ctx: CliContext) {
        if (songs < 1 || songs > MAX_SONGS) throw CliktError("--songs must be between 1 and $MAX_SONGS")
        val cases = AnalysisBench.generate(songs, seed)
        echo("analysing ${cases.size} synthetic song(s)…")
        val scores = AnalysisBench.run(cases) { s ->
            echo("  ${if (s.failed) "FAIL" else "ok  "}  ${s.case.name}  (${s.millis} ms)")
        }
        echo("")
        echo(AnalysisBench.report(scores, seed, all).trimEnd())
        echoElapsed("bench")
    }

    private companion object {
        const val MAX_SONGS = 500
    }
}
