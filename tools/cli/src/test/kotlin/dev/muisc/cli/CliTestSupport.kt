package dev.muisc.cli

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Runs `muisc` in-process, exactly as `main` assembles it, and captures what it printed.
 *
 * Every invocation gets `--cache-dir` appended so the tests share one analysis cache (the synthetic songs are
 * analysed once for the whole class) and never touch the developer's `~/.muisc`.
 */
object Cli {

    /** Runs the command line and returns everything it printed. Throws whatever the command throws. */
    fun run(cacheDir: File, vararg args: String): String {
        val command = Muisc().subcommands(allCommands())
        val captured = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        val stream = PrintStream(captured, true, StandardCharsets.UTF_8.name())
        System.setOut(stream)
        System.setErr(stream)
        try {
            command.parse(args.toList() + listOf("--cache-dir", cacheDir.absolutePath))
        } finally {
            stream.flush()
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
        return captured.toString(StandardCharsets.UTF_8.name())
    }

    /**
     * The three synthetic songs the smoke tests use, written once into [dir] (and analysed once into the shared
     * cache the first time a command needs them). 120 BPM C, 126 BPM Am and 140 BPM F# with a cold start — the
     * same shapes as the engine's `StrategyContractTest` corpus, so every strategy has something to bite on.
     */
    fun songs(dir: File, cacheDir: File): Songs {
        if (!File(dir, "t120C.wav").isFile) {
            run(cacheDir, "synth", "--out", dir.absolutePath, "--set")
        }
        return Songs(File(dir, "t120C.wav"), File(dir, "t126Am.wav"), File(dir, "t140Fs.wav"), File(dir, "t63G.wav"))
    }

    class Songs(val a: File, val b: File, val c: File, val d: File)
}
