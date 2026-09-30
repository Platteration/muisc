package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.BuiltInStyles

/**
 * `muisc style list | show <id>` — the listening styles `--style <id>` applies to the prefs of any command.
 * User styles are JSON files in `<profile-dir>/styles/<id>.json` (a `StyleProfile`: id, name, description, patch).
 */
class StyleCommand : CliktCommand(name = "style") {
    init {
        subcommands(StyleListCommand(), StyleShowCommand())
    }

    override fun help(context: Context) = "List and show listening styles (use one with --style <id>)."
    override fun run() = Unit
}

class StyleListCommand : MuiscCommand("list") {
    override fun help(context: Context) = "List the built-in and your styles."

    override fun execute(ctx: CliContext) {
        val profile = ctx.requireProfile()
        val rows = ArrayList<List<String>>()
        rows += listOf("id", "source", "name")
        val all = profile.styles.all()
        for (s in all) rows += listOf(s.id, if (BuiltInStyles.byId(s.id) != null) "built-in" else "user", s.name)
        echo(Fmt.table(rows))
        echo("")
        for (s in all) echo("${s.id}: ${s.description}")
        echo("\n${all.size} style(s); user styles in ${profile.styles.dir.path}")
    }
}

class StyleShowCommand : MuiscCommand("show") {
    override fun help(context: Context) = "Show a style's settings and what it changes in your current prefs."
    private val id by argument("ID")

    override fun execute(ctx: CliContext) {
        val profile = ctx.requireProfile()
        val style = profile.style(id) ?: throw CliktError("unknown style '$id'. Known: ${profile.styles.all().joinToString(", ") { it.id }}")
        echo("${style.id} — ${style.name}")
        echo(style.description)
        echo("\nsettings:")
        for (line in style.patch.describe()) echo("  $line")
        echo("\nchanges to your current prefs:")
        val changes = diff(ctx.prefs, style.apply(ctx.prefs))
        if (changes.isEmpty()) echo("  (none — your prefs already match)") else for (c in changes) echo("  $c")
    }

    private fun diff(before: TransitionPrefs, after: TransitionPrefs): List<String> = buildList {
        fun <T> field(name: String, a: T, b: T) { if (a != b) add("$name: $a → $b") }
        field("energy", before.energy, after.energy)
        field("maxStretchPercent", before.maxStretchPercent, after.maxStretchPercent)
        field("maxPitchShiftSemitones", before.maxPitchShiftSemitones, after.maxPitchShiftSemitones)
        field("preferredOverlapBars", before.preferredOverlapBars, after.preferredOverlapBars)
        field("varietyPenalty", before.varietyPenalty, after.varietyPenalty)
        field("keyLock", before.keyLock, after.keyLock)
        for (k in (before.strategyWeights.keys + after.strategyWeights.keys).sorted()) {
            field("weight $k", Fmt.num(before.strategyWeights[k] ?: 1.0, 2), Fmt.num(after.strategyWeights[k] ?: 1.0, 2))
        }
        val disabled = after.disabledStrategies - before.disabledStrategies
        if (disabled.isNotEmpty()) add("disabled: " + disabled.sorted().joinToString(", "))
        for (k in (before.activePresets.keys + after.activePresets.keys).sorted()) field("preset for $k", before.activePresets[k] ?: "—", after.activePresets[k] ?: "—")
    }
}
