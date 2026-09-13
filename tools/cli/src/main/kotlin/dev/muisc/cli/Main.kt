package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

/** `muisc` — offline experimentation CLI for the transition engine. Subcommands are added in Commands.kt. */
class Muisc : CliktCommand(name = "muisc") {
    override fun run() = Unit
}

fun main(args: Array<String>) = Muisc().subcommands(allCommands()).main(args)
