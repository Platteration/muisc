package dev.muisc.transitions.custom

import dev.muisc.transitions.Params
import dev.muisc.transitions.TransitionPrefs
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A named set of parameter values for one strategy — built-in or recipe (`recipe:<id>`) alike — e.g. "tight 8-bar
 * bass swap". Only the values it lists are set; every other parameter keeps its default.
 *
 * Stored as JSON (`{"id": "tight-bass-swap", "name": "...", "strategyId": "bassSwap", "params": {"overlapBars": 8}}`).
 *
 * @property modifiers when non-null, the only modifiers the planner may attach to this strategy while the preset is
 *   in use (each still only when it accepts the pair); an empty list means "no modifiers". Null leaves the choice to
 *   the planner.
 */
@Serializable
data class StrategyPreset(
    val id: String,
    val name: String,
    val strategyId: String,
    @Serializable(with = FlatParamsSerializer::class)
    val params: Params = Params.EMPTY,
    val modifiers: List<String>? = null,
    val note: String = "",
)

/** Resolves a preset id to a preset (built-ins and the user's store). */
fun interface PresetLookup {
    fun preset(id: String): StrategyPreset?

    companion object {
        /** The built-in presets only. */
        val BUILT_IN: PresetLookup = PresetLookup { BuiltInPresets.byId(it) }
    }
}

/**
 * The user's presets. Built-in presets ([BuiltInPresets]) are not stored here and cannot be overwritten; use
 * [withBuiltIns] to look up both.
 */
interface PresetStore {
    /** Every readable user preset, sorted by id. */
    fun list(): List<StrategyPreset>
    fun get(id: String): StrategyPreset?

    /** Saves (or replaces) [preset]. Throws [IllegalArgumentException] for an invalid id or a built-in id. */
    fun save(preset: StrategyPreset)

    /** Removes the preset; false when there was none. */
    fun delete(id: String): Boolean

    /** Problems met by the last read (unreadable files that were skipped). */
    val warnings: List<String>

    /** Built-ins first, then this store. */
    fun withBuiltIns(): PresetLookup = PresetLookup { BuiltInPresets.byId(it) ?: get(it) }

    /** Built-ins followed by the user's presets. */
    fun all(): List<StrategyPreset> = BuiltInPresets.all + list()
}

internal fun validatePreset(p: StrategyPreset) {
    CustomJson.requireId(p.id, "preset")
    require(p.strategyId.isNotBlank()) { "preset '${p.id}' has no strategyId" }
    require(BuiltInPresets.byId(p.id) == null) { "'${p.id}' is a built-in preset and cannot be replaced; save your version under another id" }
}

/** A [PresetStore] in memory (tests, previews). */
class InMemoryPresetStore(initial: Collection<StrategyPreset> = emptyList()) : PresetStore {
    private val items = sortedMapOf<String, StrategyPreset>()

    init { initial.forEach { save(it) } }

    @Synchronized override fun list(): List<StrategyPreset> = items.values.toList()
    @Synchronized override fun get(id: String): StrategyPreset? = items[id]
    @Synchronized override fun save(preset: StrategyPreset) { validatePreset(preset); items[preset.id] = preset }
    @Synchronized override fun delete(id: String): Boolean = items.remove(id) != null
    override val warnings: List<String> get() = emptyList()
}

/**
 * A [PresetStore] holding one `<id>.json` per preset in [dir]. Writes are atomic; a file that cannot be parsed, is
 * misnamed or reuses a built-in id is skipped and reported in [warnings] — it never hides the other presets.
 */
class FilePresetStore(val dir: File) : PresetStore {
    private val files = JsonDirectory(dir, StrategyPreset.serializer(), { it.id }, "preset") { p ->
        when {
            BuiltInPresets.byId(p.id) != null -> "'${p.id}' is a built-in preset id"
            p.strategyId.isBlank() -> "no strategyId"
            else -> null
        }
    }

    override fun list(): List<StrategyPreset> = files.list()
    override fun get(id: String): StrategyPreset? = if (CustomJson.ID.matches(id)) files.get(id) else null
    override fun save(preset: StrategyPreset) { validatePreset(preset); files.save(preset) }
    override fun delete(id: String): Boolean = files.delete(id)
    override val warnings: List<String> get() = files.warnings
}

/** Presets shipped with the engine for the built-in strategies. Every value is inside its parameter's range. */
object BuiltInPresets {
    val all: List<StrategyPreset> = listOf(
        StrategyPreset(
            "tight-bass-swap", "Tight 8-bar bass swap", "bassSwap",
            Params(mapOf("overlapBars" to "8", "swapBar" to "4", "swapBeats" to "1")),
            note = "Short overlap with the low end handed over in one beat halfway through.",
        ),
        StrategyPreset(
            "long-bass-swap", "Long 32-bar bass swap", "bassSwap",
            Params(mapOf("overlapBars" to "32", "swapBar" to "16", "swapBeats" to "2")),
            note = "A slow two-track blend; the bass changes hands after 16 bars over two beats.",
        ),
        StrategyPreset(
            "short-blend", "Short 8-bar blend", "beatMatchedBlend",
            Params(mapOf("overlapBars" to "8", "bassInBar" to "4")),
            note = "Beat-matched blend over 8 bars with B's lows opening at bar 4.",
        ),
        StrategyPreset(
            "quick-crossfade", "Quick 3-second crossfade", "crossfade",
            Params(mapOf("fadeSec" to "3")),
        ),
        StrategyPreset(
            "long-crossfade", "Long 10-second crossfade", "crossfade",
            Params(mapOf("fadeSec" to "10")),
        ),
        StrategyPreset(
            "dub-echo", "Dub echo-out", "echoOut",
            Params(mapOf("delayBeats" to "0.75", "feedback" to "0.85", "dampHz" to "2500", "tailBars" to "4")),
            note = "Darker repeats that ring on longer before B lands.",
        ),
        StrategyPreset(
            "gentle-sweep", "Gentle filter sweep", "filterSweep",
            Params(mapOf("resonance" to "0.9", "hpfToHz" to "2000", "sweepBars" to "8")),
            note = "Low-resonance high-pass that leaves more of A in; no filter howl.",
        ),
        StrategyPreset(
            "dry-cut", "Dry phrase cut", "phraseCut",
            Params(mapOf("tailMs" to "0")),
            note = "A clean edit on the phrase with no reverb tail.",
        ),
    )

    private val byIdMap: Map<String, StrategyPreset> = all.associateBy { it.id }

    fun byId(id: String): StrategyPreset? = byIdMap[id]
}

/**
 * How preset values become a strategy's parameters.
 *
 * Precedence, lowest first (each later layer overwrites the keys it sets):
 *  1. the strategy's defaults;
 *  2. the **active preset** for the strategy (`prefs.activePresets[strategyId]`);
 *  3. `prefs.paramOverrides[strategyId]` — explicit overrides always beat an active preset;
 *  4. a **pin** for the pair (its preset, then its own params) — the most specific choice the user made;
 *  5. in the CLI, `render --set` (applied by the command after planning).
 */
object PresetResolution {

    /** What layer 2 contributes for [strategyId], and why nothing when a preset was named but not usable. */
    data class Active(val preset: StrategyPreset?, val problem: String?)

    fun active(strategyId: String, prefs: TransitionPrefs, lookup: PresetLookup): Active {
        val id = prefs.activePresets[strategyId] ?: return Active(null, null)
        val preset = lookup.preset(id) ?: return Active(null, "active preset '$id' for $strategyId not found — using defaults")
        if (preset.strategyId != strategyId) return Active(null, "active preset '$id' is for ${preset.strategyId}, not $strategyId — ignored")
        return Active(preset, null)
    }

    /** Layers 1–3 as an override map (the defaults are applied by the caller). */
    fun overrides(strategyId: String, prefs: TransitionPrefs, lookup: PresetLookup): Map<String, String> =
        active(strategyId, prefs, lookup).preset?.params?.values.orEmpty() + prefs.paramOverrides[strategyId].orEmpty()

    /**
     * [prefs] with every usable active preset folded into `paramOverrides` (explicit overrides kept on top), and
     * then [forced]'s values written over its strategy's overrides. Code paths that re-plan a strategy from
     * `paramOverrides` alone (the CLI's `render --set`) see the same values as the planner this way. Idempotent.
     */
    fun fold(prefs: TransitionPrefs, lookup: PresetLookup, forced: StrategyPreset? = null): TransitionPrefs {
        val out = LinkedHashMap(prefs.paramOverrides)
        for (strategyId in prefs.activePresets.keys.sorted()) {
            val merged = overrides(strategyId, prefs, lookup)
            if (merged.isNotEmpty()) out[strategyId] = merged
        }
        if (forced != null) out[forced.strategyId] = out[forced.strategyId].orEmpty() + forced.params.values
        return prefs.copy(paramOverrides = out)
    }
}
