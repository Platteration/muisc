package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.custom.BuiltInPresets
import dev.muisc.transitions.custom.CustomJson
import dev.muisc.transitions.custom.StrategyPreset
import dev.muisc.transitions.recipe.TransitionRecipe

/**
 * `muisc preset list | show <id> | save <id> --strategy <id> --set k=v ... | delete <id>` — named parameter sets for
 * one strategy. Built-in presets are read-only; user presets live in `<profile-dir>/presets/<id>.json`. Use one with
 * `--preset <id>` on any command, or by default for its strategy with `--set-pref activePresets.<strategy>=<id>`.
 */
class PresetCommand : CliktCommand(name = "preset") {
    init {
        subcommands(PresetListCommand(), PresetShowCommand(), PresetSaveCommand(), PresetDeleteCommand())
    }

    override fun help(context: Context) = "List, show, save and delete strategy presets."
    override fun run() = Unit
}

class PresetListCommand : MuiscCommand("list") {
    override fun help(context: Context) = "List the built-in and your presets."

    override fun execute(ctx: CliContext) {
        val profile = ctx.requireProfile()
        val rows = ArrayList<List<String>>()
        rows += listOf("id", "strategy", "source", "name", "values")
        for (p in profile.presets.all()) {
            rows += listOf(
                p.id, p.strategyId, if (BuiltInPresets.byId(p.id) != null) "built-in" else "user", p.name,
                p.params.values.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { "—" },
            )
        }
        echo(Fmt.table(rows))
        echo("\n${profile.presets.all().size} preset(s); user presets in ${profile.presets.dir.path}")
    }
}

class PresetShowCommand : MuiscCommand("show") {
    override fun help(context: Context) = "Show one preset and the values its strategy will use."
    private val id by argument("ID")

    override fun execute(ctx: CliContext) {
        val profile = ctx.requireProfile()
        val p = profile.presetLookup.preset(id) ?: throw CliktError("unknown preset '$id'. Known: ${profile.presets.all().joinToString(", ") { it.id }}")
        echo(CustomJson.json.encodeToString(StrategyPreset.serializer(), p))
        val strategy = ctx.registry.strategy(p.strategyId)
        if (strategy == null) {
            echo("\nstrategy '${p.strategyId}' is not registered in this build; values not checked")
            return
        }
        echo("\n${strategy.id} (${strategy.displayName}) with this preset:")
        val rows = ArrayList<List<String>>()
        rows += listOf("param", "value", "default", "")
        for (spec in strategy.params) {
            val v = p.params[spec.id]
            rows += listOf(spec.id, v ?: spec.defaultString, spec.defaultString, if (v != null) "← preset" else "")
        }
        echo(Fmt.table(rows, "  "))
        val unknown = p.params.values.keys - strategy.params.map { it.id }.toSet()
        if (unknown.isNotEmpty()) echo("  unknown to ${strategy.id} (ignored): ${unknown.sorted().joinToString(", ")}")
        if (p.modifiers != null) echo("  modifiers: " + p.modifiers!!.joinToString(", ").ifEmpty { "none" })
    }
}

class PresetSaveCommand : MuiscCommand("save") {
    override fun help(context: Context) = "Save (or replace) a user preset: muisc preset save <id> --strategy <id> --set k=v ..."
    private val id by argument("ID", help = "Preset id: lowercase letters, digits and dashes.")
    private val strategyId by option("--strategy", metavar = "ID", help = "Strategy the preset is for (a built-in id or recipe:<id>).").required()
    private val sets by option("--set", metavar = "ID=VALUE", help = "A parameter value (repeatable).").multiple()
    private val nameOpt by option("--name", metavar = "TEXT", help = "Display name (default: the id).")
    private val note by option("--note", metavar = "TEXT", help = "A note shown with the preset.")
    private val modifiers by option("--modifier", metavar = "ID", help = "Only allow these modifiers with the preset (repeatable; 'none' for no modifiers).").multiple()

    override fun execute(ctx: CliContext) {
        val profile = ctx.requireProfile()
        val values = Sets.parse(sets)
        val strategy = ctx.registry.strategy(strategyId)
        if (strategy != null) {
            PresetValidation.check(strategy, values)?.let { throw CliktError(it) }
        } else if (!strategyId.startsWith(TransitionRecipe.STRATEGY_PREFIX)) {
            throw CliktError("unknown strategy '$strategyId'. Known: ${ctx.registry.strategyIds.joinToString(", ")} (or recipe:<id>)")
        }
        val mods: List<String>? = when {
            modifiers.isEmpty() -> null
            modifiers == listOf("none") -> emptyList()
            else -> modifiers.onEach { m ->
                if (ctx.registry.modifier(m) == null) throw CliktError("unknown modifier '$m'. Known: ${ctx.registry.modifierIds.joinToString(", ")}")
            }
        }
        val preset = StrategyPreset(id, nameOpt ?: id, strategyId, Params(values), mods, note.orEmpty())
        try {
            profile.presets.save(preset)
        } catch (e: IllegalArgumentException) {
            throw CliktError(e.message ?: "invalid preset")
        }
        echo("saved preset '$id' for $strategyId → ${profile.presets.dir.resolve("$id.json").path}")
        if (strategy == null) echo("note: strategy '$strategyId' is not registered in this build, so the values were not checked")
    }
}

class PresetDeleteCommand : MuiscCommand("delete") {
    override fun help(context: Context) = "Delete a user preset."
    private val id by argument("ID")

    override fun execute(ctx: CliContext) {
        val profile = ctx.requireProfile()
        if (BuiltInPresets.byId(id) != null) throw CliktError("'$id' is a built-in preset and cannot be deleted")
        if (!profile.presets.delete(id)) throw CliktError("no user preset '$id' in ${profile.presets.dir.path}")
        echo("deleted preset '$id'")
    }
}

/** Checks preset values against a strategy's parameter specs (unknown ids, unparsable or out-of-range values). */
object PresetValidation {
    fun check(strategy: TransitionStrategy, values: Map<String, String>): String? {
        val specs = strategy.params.associateBy { it.id }
        val problems = ArrayList<String>()
        for ((k, v) in values) {
            val spec = specs[k]
            if (spec == null) { problems += "unknown parameter '$k'"; continue }
            when (spec) {
                is ParamSpec.DoubleSpec -> {
                    val d = v.toDoubleOrNull()
                    if (d == null || d.isNaN()) problems += "$k expects a number, got '$v'"
                    else if (d < spec.min || d > spec.max) problems += "$k=$v is outside ${spec.min}..${spec.max}"
                }
                is ParamSpec.IntSpec -> {
                    val d = v.toDoubleOrNull()
                    if (d == null || d.isNaN()) problems += "$k expects a whole number, got '$v'"
                    else if (Math.round(d) < spec.min || Math.round(d) > spec.max) problems += "$k=$v is outside ${spec.min}..${spec.max}"
                }
                is ParamSpec.BoolSpec -> if (v.lowercase() !in setOf("true", "false", "1", "0", "yes", "no", "on", "off")) problems += "$k expects true/false, got '$v'"
                is ParamSpec.ChoiceSpec -> if (v !in spec.choices) problems += "$k must be one of ${spec.choices.joinToString(", ")}, got '$v'"
            }
        }
        if (problems.isEmpty()) return null
        return "invalid values for ${strategy.id}: ${problems.joinToString("; ")}. Parameters: ${strategy.params.joinToString(", ") { it.id }}"
    }
}
