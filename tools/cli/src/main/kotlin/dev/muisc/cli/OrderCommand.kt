package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.sequence.EnergyArc
import dev.muisc.transitions.sequence.PairCostModel
import dev.muisc.transitions.sequence.SequenceItem
import dev.muisc.transitions.sequence.SequenceOptions
import dev.muisc.transitions.sequence.SequenceResult
import dev.muisc.transitions.sequence.SetSequencer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.random.Random

/**
 * `muisc order` — "in which order do these songs mix best?": analyses the files, orders them with the engine's
 * [SetSequencer] (smart shuffle) and prints the order with the cost of every neighbouring pair, the total, and the
 * median total of [SmartOrder.RANDOM_ORDERS] seeded random orders of the same files for comparison.
 *
 * Up to [SmartOrder.PLANNER_REFINE_MAX] tracks the pair costs blend in the planner's best score
 * ([SetSequencer.plannerRefineMax]); the random orders are scored with the same costs. The CLI reads no tags, so the
 * same-artist rule never applies here.
 */
class OrderCommand : MuiscCommand("order") {

    override fun help(context: Context) = "Order tracks so that every neighbouring pair mixes well (smart shuffle)."

    private val inputs by argument("PATH", help = "Audio files or directories (not walked recursively).").file(mustExist = true).multiple(required = true)
    private val arc by option("--arc", help = "Energy shape of the set (default flat).")
        .choice(EnergyArc.entries.associateBy { it.id }).default(EnergyArc.FLAT)
    private val variety by option("--variety", metavar = "X", help = "0..1: 0 = best order, 1 = close to random (default ${SequenceOptions.DEFAULT_VARIETY}).")
        .convert { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() && v in 0.0..1.0 } ?: fail("expected a number between 0 and 1, got '$it'") }
        .default(SequenceOptions.DEFAULT_VARIETY)
    private val json by option("--json", help = "Print the order as JSON (nothing else is printed).").flag()

    override fun execute(ctx: CliContext) {
        val files = SmartOrder.expand(inputs)
        if (files.size < 2) throw CliktError("order needs at least two audio files (found ${files.size})")
        val (tracks, skipped) = SmartOrder.analyse(ctx, files) { if (!json) echo(it, err = true) }
        if (tracks.size + skipped.size < 2) throw CliktError("order needs at least two tracks")
        val items = tracks.map { SmartOrder.item(it) } + skipped.map { SequenceItem(it.path, null) }
        val options = SequenceOptions(seed = seed, arc = arc, variety = variety, prefs = ctx.prefs)
        val sequencer = SmartOrder.sequencer(ctx, items.size)
        val result = sequencer.sequence(items, options)
        val random = SmartOrder.randomTotals(items.size, seed) { a, b -> sequencer.pairCost(items[a], items[b], options, items.size) }
        val median = LibraryEval.median(random)
        val names = tracks.map { it.title } + skipped.map { it.nameWithoutExtension }
        if (json) {
            echo(PRETTY.encodeToString(JsonObject.serializer(), toJson(items, names, result, median, options)))
            return
        }
        echo("order (${items.size} tracks, seed $seed, arc ${arc.id}, variety ${Fmt.num(variety, 2)}, ${result.mode.name.lowercase()} mode" +
            (if (items.size <= SmartOrder.PLANNER_REFINE_MAX) ", costs refined by the planner" else "") + "):")
        echo(Fmt.table(listOf(listOf("#", "track", "bpm", "key", "energy", "→ next")) + result.order.mapIndexed { k, i ->
            val a = items[i].analysis
            listOf(
                "${k + 1}", names[i],
                a?.let { Fmt.num(it.tempo.bpm, 1) } ?: "—",
                a?.let { Fmt.key(it) } ?: "not analysed",
                a?.let { Fmt.num(PairCostModel.energyProxy(it), 2) } ?: "—",
                result.pairCosts.getOrNull(k)?.let { Fmt.num(it, 3) } ?: "",
            )
        }, "  "))
        echo("")
        val gain = if (median > 0) 1.0 - result.totalCost / median else 0.0
        echo("total cost ${Fmt.num(result.totalCost, 3)} (mean ${Fmt.num(result.meanCost, 3)} per pair); " +
            "median of ${random.size} random orders ${Fmt.num(median, 3)} (mean ${Fmt.num(median / (items.size - 1), 3)}): ${Fmt.pct(gain)} lower")
        if (skipped.isNotEmpty()) echo("${skipped.size} track(s) could not be analysed and were placed without cost information (pair cost ${PairCostModel.UNKNOWN_PAIR_COST})")
        echoElapsed("ordered ${items.size} track(s)")
    }

    private fun toJson(items: List<SequenceItem>, names: List<String>, r: SequenceResult, median: Double, o: SequenceOptions): JsonObject = buildJsonObject {
        put("seed", o.seed)
        put("arc", o.arc.id)
        put("variety", o.variety)
        put("mode", r.mode.name.lowercase())
        put("tracks", buildJsonArray {
            for ((k, i) in r.order.withIndex()) add(buildJsonObject {
                put("position", k + 1)
                put("path", items[i].id)
                put("title", names[i])
                put("analysed", items[i].analysis != null)
                items[i].analysis?.let { a ->
                    put("bpm", a.tempo.bpm); put("camelot", a.key.camelot.code); put("energy", PairCostModel.energyProxy(a))
                }
                r.pairCosts.getOrNull(k)?.let { put("costToNext", it) }
            })
        })
        put("totalCost", r.totalCost)
        put("meanCost", r.meanCost)
        put("randomMedianTotalCost", median)
        put("randomOrders", SmartOrder.RANDOM_ORDERS)
    }

    private companion object {
        val PRETTY = Json { prettyPrint = true; encodeDefaults = true }
    }
}

/** Smart-order plumbing shared by `order`, `mix --order smart` and `eval --order smart`. */
object SmartOrder {
    /** Up to this many tracks the pair costs blend in the planner's best score. */
    const val PLANNER_REFINE_MAX = 24

    /** Random orders `order` compares against. */
    const val RANDOM_ORDERS = 200

    fun sequencer(ctx: CliContext, size: Int): SetSequencer =
        if (size <= PLANNER_REFINE_MAX) SetSequencer(planner = ctx.planner, plannerRefineMax = PLANNER_REFINE_MAX) else SetSequencer()

    fun item(t: TrackRef): SequenceItem = SequenceItem(t.id, t.analysis, t.artist)

    /** Orders [tracks] (all analysed) with [seed]; the indices of the smart order. */
    fun order(ctx: CliContext, tracks: List<TrackRef>, seed: Long): List<Int> {
        val items = tracks.map(::item)
        return sequencer(ctx, items.size).sequence(items, SequenceOptions(seed = seed, prefs = ctx.prefs)).order
    }

    /** Totals of [RANDOM_ORDERS] seeded random orders of `0 until size` under [cost]. */
    fun randomTotals(size: Int, seed: Long, cost: (Int, Int) -> Double): List<Double> {
        val memo = HashMap<Long, Double>()
        fun c(a: Int, b: Int) = memo.getOrPut(a.toLong() * size + b) { cost(a, b) }
        val rnd = Random(seed xor 0x5EED5L)
        return List(RANDOM_ORDERS) {
            val o = (0 until size).shuffled(rnd)
            (0 until size - 1).sumOf { k -> c(o[k], o[k + 1]) }
        }
    }

    /** Files and the audio files directly inside directories, sorted, de-duplicated. */
    fun expand(inputs: List<File>): List<File> {
        val out = LinkedHashSet<File>()
        for (f in inputs) {
            if (f.isDirectory) {
                f.listFiles()?.sortedBy { it.name }?.filter { it.isFile && it.extension.lowercase() in CliContext.SUPPORTED_EXTENSIONS }?.forEach { out += it.absoluteFile }
            } else {
                out += f.absoluteFile
            }
        }
        return out.toList()
    }

    /** Analyses [files]; a file that cannot be analysed is reported through [warn] and returned in the second list. */
    fun analyse(ctx: CliContext, files: List<File>, warn: (String) -> Unit): Pair<List<TrackRef>, List<File>> {
        val tracks = ArrayList<TrackRef>()
        val skipped = ArrayList<File>()
        for (f in files) {
            try {
                tracks += ctx.trackRef(f)
            } catch (e: CliktError) {
                skipped += f
                warn("warning: ${e.message}; placed without cost information")
            }
        }
        return tracks to skipped
    }
}
