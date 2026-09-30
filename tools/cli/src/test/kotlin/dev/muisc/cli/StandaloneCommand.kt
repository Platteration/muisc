package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Runs one command tree in-process, the way [Cli.run] runs the whole `muisc` binary, and returns what it printed.
 * Used to test a command (such as `eval` and `bench`) on its own, built directly rather than looked up under the
 * root; that they are registered in [allCommands] is checked through the real root by CliSmokeTest. `--cache-dir`
 * and `--profile-dir` under [root] are appended, so the tests never touch the developer's `~/.muisc`; they land on
 * the innermost subcommand, which is the one that takes them.
 */
internal fun runStandalone(command: CliktCommand, root: File, vararg args: String): String {
    val captured = ByteArrayOutputStream()
    val previousOut = System.out
    val previousErr = System.err
    val stream = PrintStream(captured, true, StandardCharsets.UTF_8.name())
    System.setOut(stream)
    System.setErr(stream)
    try {
        val extra = listOf("--cache-dir", File(root, "cache").absolutePath) +
            (if ("--profile-dir" in args) emptyList() else listOf("--profile-dir", File(root, "profile").absolutePath))
        command.parse(args.toList() + extra)
    } finally {
        stream.flush()
        System.setOut(previousOut)
        System.setErr(previousErr)
    }
    return captured.toString(StandardCharsets.UTF_8.name())
}
