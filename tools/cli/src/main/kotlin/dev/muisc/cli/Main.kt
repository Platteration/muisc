package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * `muisc` — offline experimentation CLI for the transition engine: make test material, analyse it, plan and render
 * transitions, compare strategies and parameters, and check the numeric quality metrics. Subcommands are listed in
 * [allCommands]; `docs/CLI.md` documents each one with examples.
 */
class Muisc : CliktCommand(name = "muisc") {
    override fun help(context: Context) =
        "Offline tooling for the Muisc DJ transition engine: analyse tracks, plan and render transitions, " +
            "compare strategies and check the quality metrics."

    override val printHelpOnEmptyArgs = true

    override fun run() = Unit
}

fun main(args: Array<String>) {
    // The reports print musical typography (arrows, dashes, the degree of a Camelot wheel). The JVM otherwise
    // encodes stdout with the platform charset, which under a POSIX locale turns every one of them into "?".
    forceUtf8(System::setOut, FileDescriptor.out)
    forceUtf8(System::setErr, FileDescriptor.err)
    Muisc().subcommands(allCommands()).main(args)
}

private fun forceUtf8(install: (PrintStream) -> Unit, fd: FileDescriptor) {
    runCatching { install(PrintStream(FileOutputStream(fd), true, StandardCharsets.UTF_8.name())) }
}
