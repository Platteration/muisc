package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import dev.muisc.transitions.Params
import dev.muisc.transitions.recipe.LaneKind
import dev.muisc.transitions.recipe.RecipeCodec
import dev.muisc.transitions.recipe.RecipeCurve
import dev.muisc.transitions.recipe.RecipeEntry
import dev.muisc.transitions.recipe.RecipeException
import dev.muisc.transitions.recipe.RecipeLibrary
import dev.muisc.transitions.recipe.RecipeOrigin
import dev.muisc.transitions.recipe.RecipeProblem
import dev.muisc.transitions.recipe.RecipeResolver
import dev.muisc.transitions.recipe.RecipeStatus
import dev.muisc.transitions.recipe.RecipeTempo
import dev.muisc.transitions.recipe.RecipeValidator
import dev.muisc.transitions.recipe.ResolvedDeck
import dev.muisc.transitions.recipe.ResolvedLane
import dev.muisc.transitions.recipe.ResolvedRecipe
import dev.muisc.transitions.recipe.TransitionRecipe
import java.io.File
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * `muisc recipe` — work with transition recipes (user-authored transitions as JSON; see `docs/RECIPES.md`):
 * `list`, `show <id>`, `validate <file>...`, `new <id>` and `lanes <id>`. Self-contained: every subcommand builds its
 * own [RecipeLibrary] from `--recipes-dir` (default `~/.muisc/recipes`) and needs no audio or analysis.
 */
class RecipeCommand : CliktCommand(name = "recipe") {
    override fun help(context: Context) = "List, inspect, validate and create transition recipes (see docs/RECIPES.md)."
    override fun run() = Unit

    companion object {
        fun build(): CliktCommand = RecipeCommand().subcommands(
            RecipeListCommand(), RecipeShowCommand(), RecipeValidateCommand(), RecipeNewCommand(), RecipeLanesCommand(),
        )
    }
}

/** Options shared by the `recipe` subcommands. */
abstract class RecipeSubcommand(name: String) : CliktCommand(name = name) {
    protected val recipesDir by option("--recipes-dir", metavar = "DIR", help = "User recipe directory (default ~/.muisc/recipes).")
        .file(canBeFile = false)

    protected val userDir: File get() = recipesDir ?: RecipeLibrary.defaultUserDir()
    protected fun library(): RecipeLibrary = RecipeLibrary(userDir)

    /**
     * The recipe named by [idOrFile]: a `.json` file that exists, else the id of a library recipe (the one in use, or
     * failing that any loaded one, so an invalid recipe can still be inspected).
     */
    protected fun find(idOrFile: String): Found {
        val f = File(idOrFile)
        if (idOrFile.endsWith(".json", ignoreCase = true) && f.isFile) {
            val parsed = RecipeCodec.parse(f)
            val recipe = parsed.recipe ?: throw CliktError("${f.path} is not a readable recipe:\n" + parsed.problems.joinToString("\n") { "  $it" })
            val problems = parsed.locate(RecipeValidator().validate(recipe).problems)
            return Found(recipe, "file ${f.path}", problems)
        }
        val set = library().load()
        val entry = set[idOrFile] ?: set.all(idOrFile).firstOrNull()
            ?: run {
                val ids = set.entries.map { it.id }.distinct()
                val hint = RecipeCodec.suggest(idOrFile, ids)?.let { " Did you mean '$it'?" } ?: ""
                throw CliktError("no recipe '$idOrFile'.$hint Known: ${ids.joinToString(", ")}")
            }
        return Found(entry.recipe, "${originText(entry)}, ${statusText(entry)}", entry.problems)
    }

    protected class Found(val recipe: TransitionRecipe, val where: String, val problems: List<RecipeProblem>)

    /** `--set name=value` pairs checked against the recipe's knobs. */
    protected fun params(recipe: TransitionRecipe, sets: List<String>): Params {
        val values = Sets.parse(sets)
        for ((k, v) in values) {
            if (k !in recipe.vars) {
                val hint = RecipeCodec.suggest(k, recipe.vars.keys)?.let { " (did you mean '$it'?)" } ?: ""
                throw CliktError("'${recipe.id}' has no knob '$k'$hint. Knobs: ${recipe.vars.keys.joinToString(", ").ifEmpty { "none" }}")
            }
            if (v.toDoubleOrNull() == null) throw CliktError("--set $k expects a number, got '$v'")
        }
        return Params(values)
    }

    protected fun resolve(recipe: TransitionRecipe, params: Params): ResolvedRecipe = try {
        RecipeResolver.resolve(recipe, params)
    } catch (e: RecipeException) {
        throw CliktError("cannot resolve '${recipe.id}': ${e.message}")
    }

    protected fun originText(e: RecipeEntry): String = if (e.origin == RecipeOrigin.BUILT_IN) "built-in" else "user"

    protected fun statusText(e: RecipeEntry): String = when (e.status) {
        RecipeStatus.ACTIVE -> if (e.warnings.isEmpty()) "ok" else "ok, ${count(e.warnings.size, "warning")}"
        RecipeStatus.INVALID -> "invalid, ${count(e.errors.size, "error")}"
        RecipeStatus.SHADOWED -> "replaced by a user recipe"
        RecipeStatus.DUPLICATE -> "skipped, duplicate id"
    }

    protected fun count(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
}

/** `muisc recipe list` — every built-in and user recipe with its status. */
class RecipeListCommand : RecipeSubcommand("list") {
    override fun help(context: Context) = "List the built-in and user recipes with their status."

    override fun run() {
        val set = library().load()
        val rows = ArrayList<List<String>>()
        rows += listOf("id", "name", "origin", "status", "tempo", "knobs")
        for (e in set.entries) {
            rows += listOf(e.id, e.recipe.name, originText(e), statusText(e), tempoName(e.recipe.timing.tempo), e.recipe.vars.keys.joinToString(","))
        }
        echo(Fmt.table(rows))
        echo("")
        echo("${set.active.size} in use (strategy ids recipe:<id>); user recipes: ${userDir.path}")
        val unreadable = set.problems.filter { p -> set.entries.none { it.source == p.source } }
        val invalid = set.entries.filter { it.status == RecipeStatus.INVALID || it.status == RecipeStatus.DUPLICATE }
        if (unreadable.isNotEmpty() || invalid.isNotEmpty()) {
            echo("")
            echo("problems:")
            for (p in unreadable) echo("  $p")
            for (e in invalid) for (p in e.errors) echo("  ${e.source}: $p")
            echo("run 'muisc recipe validate <file>' for details")
        }
    }
}

/** `muisc recipe show <id>` — a resolved summary. */
class RecipeShowCommand : RecipeSubcommand("show") {
    override fun help(context: Context) = "Show a recipe resolved at its defaults (or at --set values): timing, knobs, rules, lanes and problems."

    private val id by argument("ID", help = "Recipe id, or a path to a recipe .json file.")
    private val sets by option("--set", metavar = "KNOB=VALUE", help = "Set a knob (repeatable).").multiple()

    override fun run() {
        val found = find(id)
        val r = found.recipe
        val params = params(r, sets)
        echo("${r.id} — ${r.name}  (${found.where})")
        echo("strategy id: ${r.strategyId}   ambition ${Fmt.num(r.ambition, 2)}   tags: ${r.tags.joinToString(", ").ifEmpty { "none" }}")
        if (r.description.isNotBlank()) echo(wrap(r.description, 100, "  "))
        echo("")

        if (r.vars.isNotEmpty()) {
            val rows = ArrayList<List<String>>()
            rows += listOf("knob", "value", "default", "range", "unit", "what it does")
            val bound = runCatching { RecipeResolver.resolve(r, params).vars }.getOrNull()
            for ((name, v) in r.vars) {
                val value = bound?.get(name)?.let { num(it) } ?: "?"
                rows += listOf(name, value, num(v.default), "${num(v.min)}..${num(v.max)}", v.unit, v.doc.ifBlank { v.label })
            }
            echo("knobs:")
            echo(Fmt.table(rows, "  "))
            echo("")
        }

        val res = runCatching { RecipeResolver.resolve(r, params) }
        res.getOrNull()?.let { rr ->
            echo(timingText(rr))
            echo("EQ crossovers ${num(rr.lowHz)} / ${num(rr.highHz)} Hz")
            echo("rules: ${rulesText(r)}")
            if (r.modifiers.isNotEmpty()) echo("modifiers: ${r.modifiers.joinToString(", ")}")
            echo("needs stems: ${if (rr.needsStems || r.a.stems != null || r.b.stems != null) "yes" else "no"}")
            echo("")
            for ((name, deck) in listOf("A (outgoing)" to rr.a, "B (incoming)" to rr.b)) echo("deck $name: ${deckText(deck)}")
        }
        res.exceptionOrNull()?.let { echo("cannot resolve: ${it.message}") }

        val problems = found.problems
        echo("")
        if (problems.isEmpty()) echo("problems: none") else {
            echo("problems:")
            for (p in problems) echo("  $p")
        }
    }

    private fun deckText(d: ResolvedDeck): String {
        val parts = ArrayList<String>()
        for ((label, lane) in d.lanes()) if (!lane.isNeutral) parts += "$label (${lane.points.size} points)"
        if (d.usesFilters) parts += "resonance ${num(d.resonance)}"
        d.echo?.let { if (!it.send.isNeutral) parts += "echo ${num(it.beats)} beats, feedback ${num(it.feedback)}" }
        d.reverb?.let { if (!it.send.isNeutral || !it.freeze.isNeutral) parts += "reverb ${num(it.decaySec)} s" }
        return if (parts.isEmpty()) "untouched" else parts.joinToString("; ")
    }

    private fun rulesText(r: TransitionRecipe): String {
        val rules = r.rules
        val parts = ArrayList<String>()
        parts += "beat match " + when (rules.requiresBeatMatch) {
            null -> if (r.timing.tempo == RecipeTempo.NONE) "not required (default)" else "required (default)"
            true -> "required"
            false -> "not required"
        }
        rules.maxStretchPercent?.let { parts += "max stretch ${num(it)} %" }
        rules.maxKeyDistance?.let { parts += "max key distance $it" }
        if (rules.outro.isNotEmpty()) parts += "A outro ${rules.outro.joinToString("/")}"
        if (rules.intro.isNotEmpty()) parts += "B intro ${rules.intro.joinToString("/")}"
        if (rules.minEnergyDelta > -1.0 || rules.maxEnergyDelta < 1.0) parts += "energy change ${num(rules.minEnergyDelta)}..${num(rules.maxEnergyDelta)}"
        parts += "base score ${num(rules.baseScore)}"
        return parts.joinToString(", ")
    }
}

/** `muisc recipe validate <file>...` — problems with paths and lines; exit code 1 on errors. */
class RecipeValidateCommand : RecipeSubcommand("validate") {
    override fun help(context: Context) = "Check recipe files (or directories of them) and explain every problem. Exits with 1 when any has errors."

    private val files by argument("FILE", help = "Recipe .json files or directories.").file(mustExist = true).multiple(required = true)

    override fun run() {
        val targets = files.flatMap { f ->
            if (f.isDirectory) f.listFiles { x -> x.isFile && x.name.endsWith(".json", ignoreCase = true) && !x.name.startsWith(".") }?.sortedBy { it.name }.orEmpty() else listOf(f)
        }
        if (targets.isEmpty()) throw CliktError("no .json files to validate")
        val builtIns = RecipeLibrary.builtInOnly().load().active.map { it.id }.toSet()
        var withErrors = 0
        for (f in targets) {
            val parsed = RecipeCodec.parse(f)
            val recipe = parsed.recipe
            val problems = if (recipe == null) parsed.problems else parsed.locate(RecipeValidator().validate(recipe).problems)
            val notes = if (recipe != null && recipe.id in builtIns) listOf("note: installing it replaces the built-in recipe '${recipe.id}'") else emptyList()
            val errors = problems.count { it.isError }
            val warnings = problems.size - errors
            if (errors > 0) withErrors++
            val head = when {
                errors > 0 -> "${count(errors, "error")}" + if (warnings > 0) ", ${count(warnings, "warning")}" else ""
                warnings > 0 -> "ok, ${count(warnings, "warning")}"
                else -> "ok"
            }
            echo("${f.path}: $head" + (recipe?.let { "  (${it.id})" } ?: ""))
            for (p in problems) echo("  $p")
            for (n in notes) echo("  $n")
        }
        if (targets.size > 1) echo("${targets.size} files: ${targets.size - withErrors} usable, $withErrors with errors")
        if (withErrors > 0) throw ProgramResult(1)
    }
}

/** `muisc recipe new <id>` — writes a starter recipe (or a copy of another). */
class RecipeNewCommand : RecipeSubcommand("new") {
    override fun help(context: Context) = "Write a new recipe file to start from: a simple blend, or a copy of another recipe (--from)."

    private val id by argument("ID", help = "Id of the new recipe (lowercase letters, digits, dashes); also its file name.")
    private val from by option("--from", metavar = "ID", help = "Start from a copy of this recipe (built-in or user).")
    private val name by option("--name", metavar = "NAME", help = "Display name (default: the id, or the copied name).")
    private val dir by option("--dir", metavar = "DIR", help = "Write into this directory instead of the user recipe directory.").file(canBeFile = false)

    override fun run() {
        if (!RecipeValidator.ID_PATTERN.matches(id)) {
            val slug = RecipeValidator.slug(id)
            throw CliktError("the id '$id' may only use lowercase letters, digits and dashes" + if (slug.isNotEmpty()) " — for example '$slug'" else "")
        }
        val recipe = from?.let { src ->
            val base = find(src).recipe
            base.copy(id = id, name = name ?: if (src == id) base.name else "${base.name} (copy)", version = 1)
        } ?: RecipeLibrary.starter(id, name ?: id)

        val target = dir ?: userDir
        val lib = RecipeLibrary(target)
        val file = File(target, "$id.json")
        if (file.exists() || lib.load().all(id).any { it.origin == RecipeOrigin.USER }) {
            throw CliktError("a recipe '$id' already exists in ${target.path}; pick another id or edit that file")
        }
        val result = lib.save(recipe, allowErrors = true)
        if (!result.ok) throw CliktError("could not write the recipe:\n" + result.problems.joinToString("\n") { "  $it" })
        echo("wrote ${result.file!!.path}")
        if (RecipeLibrary.builtInOnly().load()[id] != null) echo("note: it replaces the built-in recipe '$id' while it is in ${target.path}")
        for (p in result.problems) echo("  $p")
        echo("next: edit it, then run 'muisc recipe validate ${result.file!!.path}' and 'muisc recipe lanes ${result.file!!.path}'")
    }
}

/** `muisc recipe lanes <id>` — every non-neutral lane as a table of points and a compact ASCII plot. */
class RecipeLanesCommand : RecipeSubcommand("lanes") {
    override fun help(context: Context) = "Print every lane that does something as a table of bars and values plus a small plot."

    private val id by argument("ID", help = "Recipe id, or a path to a recipe .json file.")
    private val sets by option("--set", metavar = "KNOB=VALUE", help = "Set a knob (repeatable).").multiple()
    private val width by option("--width", metavar = "N", help = "Plot width in characters (default 60).").int().restrictTo(16..200).default(60)

    override fun run() {
        val found = find(id)
        val r = found.recipe
        val res = resolve(r, params(r, sets))
        echo("${r.id} — ${r.name}" + if (res.vars.isEmpty()) "" else "  (${res.vars.entries.joinToString(", ") { "${it.key} = ${num(it.value)}" }})")
        echo(timingText(res))
        var any = false
        for ((deckName, deck) in listOf("a" to res.a, "b" to res.b)) {
            for ((label, lane) in deck.lanes()) {
                if (lane.isNeutral) continue
                any = true
                echo("")
                echo("$deckName.$label  (${unitText(lane.kind)}; neutral ${num(lane.kind.neutral)})")
                val rows = ArrayList<List<String>>()
                rows += listOf("bar", "value", "then")
                lane.points.forEachIndexed { i, p ->
                    val then = if (i == lane.points.size - 1) "holds" else curveName(p.curve)
                    rows += listOf(num(p.bar), valueText(lane.kind, p.value), then)
                }
                echo(Fmt.table(rows, "  "))
                echo(plot(lane, res.totalBars, res.lengthBars, width))
            }
        }
        if (!any) {
            echo("")
            echo("every lane is neutral: this recipe leaves both decks untouched")
        }
    }

    private fun plot(lane: ResolvedLane, total: Double, bars: Double, width: Int): String {
        val height = 5
        fun y(v: Double): Double = when {
            lane.kind.logScale -> ln(v.coerceAtLeast(1.0))
            lane.kind == LaneKind.DB -> v.coerceAtLeast(PLOT_FLOOR_DB)
            else -> v
        }
        val samples = DoubleArray(width) { x -> lane.valueAt(total * x / (width - 1)) }
        val ys = samples.map { y(it) } + y(lane.kind.neutral)
        val lo = ys.min()
        val hi = ys.max()
        val span = if (hi > lo) hi - lo else 1.0
        val loLabel = valueText(lane.kind, samples.minOrNull()?.let { if (lane.kind == LaneKind.DB) maxOf(it, PLOT_FLOOR_DB) else it } ?: lo)
        val hiLabel = valueText(lane.kind, samples.maxOrNull() ?: hi)
        val labelWidth = maxOf(loLabel.length, hiLabel.length)
        val grid = Array(height) { CharArray(width) { ' ' } }
        for (x in 0 until width) {
            val level = ((y(samples[x]) - lo) / span * (height - 1)).roundToInt().coerceIn(0, height - 1)
            grid[height - 1 - level][x] = '*'
        }
        val out = StringBuilder()
        for (row in 0 until height) {
            val label = when (row) {
                0 -> hiLabel
                height - 1 -> loLabel
                else -> ""
            }
            out.append("  ").append(label.padStart(labelWidth)).append(" |").append(String(grid[row]).trimEnd()).append('\n')
        }
        val axis = CharArray(width) { '-' }
        val barsCol = ((bars / total) * (width - 1)).roundToInt().coerceIn(0, width - 1)
        axis[barsCol] = '|'
        out.append("  ").append(" ".repeat(labelWidth)).append(" +").append(String(axis)).append('\n')
        val ticks = StringBuilder(" ".repeat(width + 1))
        fun put(col: Int, text: String) {
            val start = col.coerceIn(0, maxOf(0, ticks.length - text.length))
            for (i in text.indices) if (start + i < ticks.length) ticks.setCharAt(start + i, text[i])
        }
        put(0, "0")
        put(barsCol - num(bars).length / 2, num(bars))
        put(width - num(total).length, num(total))
        out.append("  ").append(" ".repeat(labelWidth)).append("  ").append(ticks.toString().trimEnd()).append("  (bars; | = end of overlap)")
        return out.toString()
    }

    private fun unitText(kind: LaneKind): String = when (kind) {
        LaneKind.LEVEL -> "gain ×"
        LaneKind.DB -> "dB"
        LaneKind.HPF -> "high-pass Hz"
        LaneKind.LPF -> "low-pass Hz"
        LaneKind.SEND -> "send 0..1"
        LaneKind.FREEZE -> "freeze 0/1"
    }

    private fun valueText(kind: LaneKind, v: Double): String = when (kind) {
        LaneKind.DB -> if (v <= RecipeResolver.OFF_DB) "off" else "${num(v)} dB"
        LaneKind.HPF, LaneKind.LPF -> "${num(v)} Hz"
        else -> num(v)
    }

    companion object {
        /** dB lanes are plotted down to here; lower values (up to "off") sit on the bottom row. */
        private const val PLOT_FLOOR_DB = -48.0
    }
}

private fun tempoName(t: RecipeTempo) = when (t) {
    RecipeTempo.MATCH -> "match"
    RecipeTempo.GLIDE -> "glide"
    RecipeTempo.NONE -> "none"
}

private fun curveName(c: RecipeCurve) = when (c) {
    RecipeCurve.LINEAR -> "linear"
    RecipeCurve.EQUAL_POWER -> "equalPower"
    RecipeCurve.S_CURVE -> "sCurve"
    RecipeCurve.EXP -> "exp"
    RecipeCurve.STEP -> "step"
}

/** A number without trailing zeros (`16`, `0.75`, `-12.5`). */
private fun num(v: Double): String {
    if (v == Math.rint(v) && kotlin.math.abs(v) < 1e12) return v.toLong().toString()
    return Fmt.num(v, 3).trimEnd('0').trimEnd('.')
}

private fun timingText(r: ResolvedRecipe): String = buildString {
    append("tempo ").append(tempoName(r.tempo)).append(", align ").append(r.align.name.lowercase())
    append("; overlap ").append(num(r.lengthBars)).append(" bars")
    if (r.tempo == RecipeTempo.NONE) {
        append(", B enters at bar ").append(num(r.bEntersAtBar))
    } else {
        append(", settle ").append(num(r.settleBars))
    }
    append(", hold ").append(num(r.holdBars)).append("; total ").append(num(r.totalBars)).append(" bars")
    if (r.bEntryOffsetBars != 0) append("; B enters ").append(r.bEntryOffsetBars).append(" bar(s) from its mix-in cue")
}

private fun wrap(text: String, width: Int, indent: String): String {
    val out = StringBuilder()
    var line = StringBuilder()
    for (word in text.split(Regex("\\s+"))) {
        if (line.isNotEmpty() && line.length + 1 + word.length > width) {
            out.append(indent).append(line).append('\n')
            line = StringBuilder()
        }
        if (line.isNotEmpty()) line.append(' ')
        line.append(word)
    }
    if (line.isNotEmpty()) out.append(indent).append(line)
    return out.toString()
}
