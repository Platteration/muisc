package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import dev.muisc.analysis.FileAnalysisCache
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioSourceId
import dev.muisc.transitions.Params
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.recipe.TransitionRecipe
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * `muisc pin set <a> <b> <strategyId> [--preset id] [--set k=v] | list | clear <a> <b>` — "always use this transition
 * from A into B". Pins are stored in `<profile-dir>/pins.json` by the tracks' identities (TrackAnalysis.identity:
 * a hash of the decoded audio, so a pin survives copying, touching or re-tagging the files; A and B are audio files,
 * analysed through the cache; `--identities` takes the identities themselves, as `muisc analyze` and `pin list` print
 * them). A pin is directional.
 * The planner ranks a pinned strategy first whenever it is applicable to the pair and says why when it is not.
 */
class PinCommand : CliktCommand(name = "pin") {
    init {
        subcommands(PinSetCommand(), PinListCommand(), PinClearCommand())
    }

    override fun help(context: Context) = "Pin a transition to a pair of tracks, list pins, clear a pin."
    override fun run() = Unit
}

/**
 * Resolves the `<a> <b>` arguments of the pin commands to the key the planner matches pins on,
 * [TrackAnalysis.identity] (and a label).
 *
 * A file is analysed through the cache. With `--identities` the argument is taken as an identity, except that a
 * value which is the fingerprint of an analysis in the cache (at this sample rate) is replaced by that analysis's
 * identity — the planner would never match the fingerprint — and the caller says so. A value that is neither is kept
 * as given and flagged [Track.unknown]. Tracks found in the cache get a [TrackRef] built from the cached analysis,
 * so `pin set` can show at once whether the planner honours the pin.
 */
internal class PinArgs(private val ctx: CliContext) {
    /**
     * @property mappedFrom the fingerprint given in place of [identity], or null.
     * @property unknown an `--identities` value that is no analysed track's identity.
     */
    class Track(val identity: String, val label: String, val ref: TrackRef?, val mappedFrom: String? = null, val unknown: Boolean = false)

    /** Identity → analysis of every readable cache entry at this sample rate and analysis version; read on first use. */
    private val cachedByIdentity: Map<String, TrackAnalysis> by lazy {
        val suffix = "-${ctx.sampleRate}-v${TrackAnalysis.CURRENT_VERSION}${FileAnalysisCache.SUFFIX}"
        ctx.cache.dir.listFiles { f -> f.isFile && f.name.endsWith(suffix) }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            try {
                GZIPInputStream(f.inputStream().buffered()).use { TrackAnalysis.fromJson(it.readBytes().toString(Charsets.UTF_8)) }
            } catch (e: Exception) {
                null // an unreadable entry is a cache miss everywhere else too
            }
        }.associateBy { it.identity }
    }

    fun resolve(arg: String, identities: Boolean): Track {
        if (!identities) {
            val ref = ctx.trackRef(File(arg))
            return Track(ref.analysis.identity, File(arg).name, ref)
        }
        ctx.cache.get(arg, ctx.sampleRate, TrackAnalysis.CURRENT_VERSION)?.let { an ->
            return Track(an.identity, labelOf(an), refOf(an), mappedFrom = arg.takeIf { it != an.identity })
        }
        cachedByIdentity[arg]?.let { an -> return Track(arg, labelOf(an), refOf(an)) }
        return Track(arg, "", null, unknown = true)
    }

    /** What the user should know about [t]: a fingerprint replaced by the identity, or an identity no track has. */
    fun notes(t: Track, arg: String): List<String> = listOfNotNull(
        t.mappedFrom?.let { "note: '$it' is an analysis fingerprint${if (t.label.isEmpty()) "" else " (${t.label})"}; pins are keyed by the track's identity, so ${t.identity} is used" },
        if (t.unknown) {
            "warning: '$arg' is not the identity of any analysed track in ${ctx.cache.dir.path} at ${ctx.sampleRate} Hz; " +
                "the pin is kept but applies only to a track with exactly this identity (`muisc analyze FILE` prints it)"
        } else null,
    )

    private fun labelOf(an: TrackAnalysis): String = File(an.sourceId).name

    private fun refOf(an: TrackAnalysis): TrackRef {
        val f = File(an.sourceId)
        return TrackRef(id = an.sourceId, source = AudioSourceId(an.sourceId), analysis = an, albumId = f.parentFile?.name, title = f.nameWithoutExtension)
    }
}

class PinSetCommand : MuiscCommand("set") {
    override fun help(context: Context) = "Pin <strategyId> (optionally with a preset and values) to the pair A → B."
    private val a by argument("A", help = "Outgoing track (audio file, or its identity with --identities).")
    private val b by argument("B", help = "Incoming track.")
    private val strategyId by argument("STRATEGY", help = "Strategy id (built-in or recipe:<id>).")
    private val sets by option("--set", metavar = "ID=VALUE", help = "Parameter value for the pinned strategy (repeatable; beats the preset).").multiple()
    private val note by option("--note", metavar = "TEXT", help = "Shown next to \"pinned by you\".")
    private val identities by option("--identities", help = IDENTITIES_HELP).flag()

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
        val args = PinArgs(ctx)
        val ta = args.resolve(a, identities)
        val tb = args.resolve(b, identities)
        for (n in args.notes(ta, a) + args.notes(tb, b)) echo(n, err = n.startsWith("warning"))
        val pin = PairPin(
            ta.identity, tb.identity, strategyId, presetId,
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

    private fun label(t: PinArgs.Track) = t.label.ifEmpty { t.identity }
}

private const val IDENTITIES_HELP = "A and B are track identities (TrackAnalysis.identity, as `muisc analyze` and `muisc pin list` print them), " +
    "not files. The fingerprint of an analysis in the cache is replaced by that track's identity."

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
        echo("\nidentities (for --identities):")
        for (p in pins) echo("  ${p.aLabel.ifEmpty { short(p.aFingerprint) }} → ${p.bLabel.ifEmpty { short(p.bFingerprint) }}: ${p.aFingerprint} ${p.bFingerprint}")
        echo("\n${pins.size} pin(s) in ${profile.pins.file.path}")
    }

    private fun short(fp: String) = if (fp.length > 12) fp.take(12) + "…" else fp
}

class PinClearCommand : MuiscCommand("clear") {
    override fun help(context: Context) = "Remove the pin for A → B."
    private val a by argument("A")
    private val b by argument("B")
    private val identities by option("--identities", help = IDENTITIES_HELP).flag()

    override fun execute(ctx: CliContext) {
        val profile = ctx.requireProfile()
        val args = PinArgs(ctx)
        val ta = args.resolve(a, identities)
        val tb = args.resolve(b, identities)
        for ((t, arg) in listOf(ta to a, tb to b)) if (t.mappedFrom != null) for (n in args.notes(t, arg)) echo(n)
        if (!profile.pins.clear(ta.identity, tb.identity)) throw CliktError("no pin for ${a} → ${b}")
        echo("cleared the pin for $a → $b")
    }
}
