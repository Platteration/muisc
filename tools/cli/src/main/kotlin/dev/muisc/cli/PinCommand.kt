package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import dev.muisc.transitions.Params
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.recipe.TransitionRecipe
import java.io.File

/**
 * `muisc pin set <a> <b> <strategyId> [--preset id] [--set k=v] | list | clear <a> <b>` — "always use this transition
 * from A into B". Pins are stored in `<profile-dir>/pins.json` by the tracks' analysis fingerprints (A and B are
 * audio files, analysed through the cache; `--fingerprints` takes the fingerprints themselves). A pin is directional.
 * The planner ranks a pinned strategy first whenever it is applicable to the pair and says why when it is not.
 */
class PinCommand : CliktCommand(name = "pin") {
    init {
        subcommands(PinSetCommand(), PinListCommand(), PinClearCommand())
    }

    override fun help(context: Context) = "Pin a transition to a pair of tracks, list pins, clear a pin."
    override fun run() = Unit
}

/** Resolves the `<a> <b>` arguments of the pin commands to fingerprints (and labels). */
internal object PinArgs {
    class Track(val fingerprint: String, val label: String, val ref: dev.muisc.transitions.TrackRef?)

    fun resolve(ctx: CliContext, arg: String, fingerprints: Boolean): Track {
        if (fingerprints) return Track(arg, "", null)
        val ref = ctx.trackRef(File(arg))
        return Track(ref.analysis.fingerprint, File(arg).name, ref)
    }
}

class PinSetCommand : MuiscCommand("set") {
    override fun help(context: Context) = "Pin <strategyId> (optionally with a preset and values) to the pair A → B."
    private val a by argument("A", help = "Outgoing track (audio file, or a fingerprint with --fingerprints).")
    private val b by argument("B", help = "Incoming track.")
    private val strategyId by argument("STRATEGY", help = "Strategy id (built-in or recipe:<id>).")
    private val sets by option("--set", metavar = "ID=VALUE", help = "Parameter value for the pinned strategy (repeatable; beats the preset).").multiple()
    private val note by option("--note", metavar = "TEXT", help = "Shown next to \"pinned by you\".")
    private val fingerprints by option("--fingerprints", help = "A and B are analysis fingerprints, not files.").flag()

    override fun execute(ctx: CliContext) {
        val profile = ctx.requireProfile()
        // `--preset` is the option every command shares (MuiscCommand); here it names the pin's preset.
        val presetId = ctx.preset?.id
        val strategy = ctx.registry.strategy(strategyId)
        if (strategy == null && !strategyId.startsWith(TransitionRecipe.STRATEGY_PREFIX)) {
            throw CliktError("unknown strategy '$strategyId'. Known: ${ctx.registry.strategyIds.joinToString(", ")} (or recipe:<id>)")
        }
        val values = Sets.parse(sets)
        if (strategy != null) PresetValidation.check(strategy, values)?.let { throw CliktError(it) }
        ctx.preset?.let { p ->
            if (p.strategyId != strategyId) throw CliktError("preset '${p.id}' is for ${p.strategyId}, not $strategyId")
        }
        val ta = PinArgs.resolve(ctx, a, fingerprints)
        val tb = PinArgs.resolve(ctx, b, fingerprints)
        val pin = PairPin(
            ta.fingerprint, tb.fingerprint, strategyId, presetId,
            params = if (values.isEmpty()) null else Params(values),
            aLabel = ta.label, bLabel = tb.label, note = note.orEmpty(),
        )
        try {
            profile.pins.set(pin)
        } catch (e: IllegalArgumentException) {
            throw CliktError(e.message ?: "invalid pin")
        }
        echo("pinned $strategyId${presetId?.let { " (preset $it)" } ?: ""} for ${label(ta)} → ${label(tb)} in ${profile.pins.file.path}")
        // Show at once whether the planner can honour it for this pair.
        if (ta.ref != null && tb.ref != null) {
            val planner = dev.muisc.transitions.planner.DefaultTransitionPlanner(ctx.registry, ctx.pairAnalyzer, customization = profile.customization())
            val outcome = planner.planExplained(ta.ref, tb.ref, ctx.prefs, seed).explanation.pin
            if (outcome != null) echo((if (outcome.used) "now: " else "warning: ") + outcome.reason)
        }
    }

    private fun label(t: PinArgs.Track) = t.label.ifEmpty { t.fingerprint }
}

class PinListCommand : MuiscCommand("list") {
    override fun help(context: Context) = "List your pins."

    override fun execute(ctx: CliContext) {
        val profile = ctx.requireProfile()
        val pins = profile.pins.list()
        if (pins.isEmpty()) {
            echo("no pins in ${profile.pins.file.path}")
            return
        }
        val rows = ArrayList<List<String>>()
        rows += listOf("A", "B", "strategy", "preset", "values", "note")
        for (p in pins) rows += listOf(
            p.aLabel.ifEmpty { short(p.aFingerprint) }, p.bLabel.ifEmpty { short(p.bFingerprint) }, p.strategyId, p.presetId ?: "—",
            p.params?.values?.toSortedMap()?.entries?.joinToString(" ") { "${it.key}=${it.value}" }?.ifEmpty { null } ?: "—", p.note.ifEmpty { "—" },
        )
        echo(Fmt.table(rows))
        echo("\n${pins.size} pin(s) in ${profile.pins.file.path}")
    }

    private fun short(fp: String) = if (fp.length > 12) fp.take(12) + "…" else fp
}

class PinClearCommand : MuiscCommand("clear") {
    override fun help(context: Context) = "Remove the pin for A → B."
    private val a by argument("A")
    private val b by argument("B")
    private val fingerprints by option("--fingerprints", help = "A and B are analysis fingerprints, not files.").flag()

    override fun execute(ctx: CliContext) {
        val profile = ctx.requireProfile()
        val ta = PinArgs.resolve(ctx, a, fingerprints)
        val tb = PinArgs.resolve(ctx, b, fingerprints)
        if (!profile.pins.clear(ta.fingerprint, tb.fingerprint)) throw CliktError("no pin for ${a} → ${b}")
        echo("cleared the pin for $a → $b")
    }
}
