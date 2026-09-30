package dev.muisc.transitions.custom

import dev.muisc.transitions.TransitionPrefs
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A partial [TransitionPrefs]: only the fields it sets change. Scalars replace the base value; the collections
 * combine with the base as documented per field.
 */
@Serializable
data class PrefsPatch(
    /** 0..1, see [TransitionPrefs.energy]. */
    val energy: Double? = null,
    val maxStretchPercent: Double? = null,
    val maxPitchShiftSemitones: Double? = null,
    /** Used by the planner's room sub-score and by the live fallbacks' lengths; strategies keep their own defaults. */
    val preferredOverlapBars: Int? = null,
    /** 0..1, see [TransitionPrefs.varietyPenalty]. */
    val varietyPenalty: Double? = null,
    val keyLock: Boolean? = null,
    /** Multipliers on `strategyWeights` (a strategy without a weight counts as 1). Keys are strategy or recipe ids. */
    val weights: Map<String, Double> = emptyMap(),
    /** Strategy or modifier ids added to `disabledStrategies` (the planner never disables `crossfade`). */
    val disable: Set<String> = emptySet(),
    /** Strategy or recipe ids to favour: each one's weight is multiplied by [PREFER_BOOST] (on top of [weights]). */
    val prefer: List<String> = emptyList(),
    /** Presets to use per strategy; entries replace the base's entry for the same strategy. */
    val activePresets: Map<String, String> = emptyMap(),
) {
    /** [base] with this patch applied. */
    fun apply(base: TransitionPrefs): TransitionPrefs {
        val weightsOut = LinkedHashMap(base.strategyWeights)
        for ((id, m) in weights) weightsOut[id] = (weightsOut[id] ?: 1.0) * m
        for (id in prefer) weightsOut[id] = (weightsOut[id] ?: 1.0) * PREFER_BOOST
        return base.copy(
            energy = energy ?: base.energy,
            maxStretchPercent = maxStretchPercent ?: base.maxStretchPercent,
            maxPitchShiftSemitones = maxPitchShiftSemitones ?: base.maxPitchShiftSemitones,
            preferredOverlapBars = preferredOverlapBars ?: base.preferredOverlapBars,
            varietyPenalty = varietyPenalty ?: base.varietyPenalty,
            keyLock = keyLock ?: base.keyLock,
            strategyWeights = weightsOut,
            disabledStrategies = base.disabledStrategies + disable,
            activePresets = base.activePresets + activePresets,
        )
    }

    /** The first value outside its range, or null when the patch is valid. */
    fun problem(): String? = when {
        energy != null && (energy.isNaN() || energy !in 0.0..1.0) -> "energy $energy is outside 0..1"
        maxStretchPercent != null && (maxStretchPercent.isNaN() || maxStretchPercent !in 0.0..16.0) -> "maxStretchPercent $maxStretchPercent is outside 0..16"
        maxPitchShiftSemitones != null && (maxPitchShiftSemitones.isNaN() || maxPitchShiftSemitones !in 0.0..6.0) -> "maxPitchShiftSemitones $maxPitchShiftSemitones is outside 0..6"
        preferredOverlapBars != null && preferredOverlapBars !in 1..64 -> "preferredOverlapBars $preferredOverlapBars is outside 1..64"
        varietyPenalty != null && (varietyPenalty.isNaN() || varietyPenalty !in 0.0..1.0) -> "varietyPenalty $varietyPenalty is outside 0..1"
        weights.any { (_, w) -> w.isNaN() || w < 0.0 || w.isInfinite() } -> "weights must be finite and ≥ 0: ${weights.filterValues { it.isNaN() || it < 0.0 || it.isInfinite() }}"
        else -> null
    }

    /** One line per setting, for `muisc style show`. */
    fun describe(): List<String> = buildList {
        energy?.let { add("energy = $it") }
        maxStretchPercent?.let { add("maxStretchPercent = $it") }
        maxPitchShiftSemitones?.let { add("maxPitchShiftSemitones = $it") }
        preferredOverlapBars?.let { add("preferredOverlapBars = $it") }
        varietyPenalty?.let { add("varietyPenalty = $it") }
        keyLock?.let { add("keyLock = $it") }
        for ((id, w) in weights.toSortedMap()) add("weight $id × $w")
        for (id in prefer) add("prefer $id (weight × $PREFER_BOOST)")
        if (disable.isNotEmpty()) add("disable " + disable.sorted().joinToString(", "))
        for ((s, p) in activePresets.toSortedMap()) add("preset $s → $p")
    }

    companion object {
        /** Weight multiplier of a [prefer]red strategy. */
        const val PREFER_BOOST: Double = 1.3
    }
}

/**
 * A named listening style: a [PrefsPatch] with a description of what it does. Applying a style never removes a
 * setting the patch does not mention.
 */
@Serializable
data class StyleProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val patch: PrefsPatch = PrefsPatch(),
) {
    fun apply(base: TransitionPrefs): TransitionPrefs = patch.apply(base)
}

/** The user's styles; [BuiltInStyles] are read-only and not stored here. */
interface StyleStore {
    fun list(): List<StyleProfile>
    fun get(id: String): StyleProfile?

    /** Throws [IllegalArgumentException] for an invalid id, a built-in id or an out-of-range patch. */
    fun save(style: StyleProfile)
    fun delete(id: String): Boolean
    val warnings: List<String>

    /** A built-in style or, failing that, a user style. */
    fun find(id: String): StyleProfile? = BuiltInStyles.byId(id) ?: get(id)

    fun all(): List<StyleProfile> = BuiltInStyles.all + list()
}

internal fun validateStyle(s: StyleProfile) {
    CustomJson.requireId(s.id, "style")
    require(BuiltInStyles.byId(s.id) == null) { "'${s.id}' is a built-in style and cannot be replaced; save your version under another id" }
    s.patch.problem()?.let { throw IllegalArgumentException("style '${s.id}': $it") }
}

class InMemoryStyleStore(initial: Collection<StyleProfile> = emptyList()) : StyleStore {
    private val items = sortedMapOf<String, StyleProfile>()

    init { initial.forEach { save(it) } }

    @Synchronized override fun list(): List<StyleProfile> = items.values.toList()
    @Synchronized override fun get(id: String): StyleProfile? = items[id]
    @Synchronized override fun save(style: StyleProfile) { validateStyle(style); items[style.id] = style }
    @Synchronized override fun delete(id: String): Boolean = items.remove(id) != null
    override val warnings: List<String> get() = emptyList()
}

/** One `<id>.json` per style in [dir]; unreadable, misnamed, built-in-id or out-of-range files are skipped and reported. */
class FileStyleStore(val dir: File) : StyleStore {
    private val files = JsonDirectory(dir, StyleProfile.serializer(), { it.id }, "style") { s ->
        if (BuiltInStyles.byId(s.id) != null) "'${s.id}' is a built-in style id" else s.patch.problem()
    }

    override fun list(): List<StyleProfile> = files.list()
    override fun get(id: String): StyleProfile? = if (CustomJson.ID.matches(id)) files.get(id) else null
    override fun save(style: StyleProfile) { validateStyle(style); files.save(style) }
    override fun delete(id: String): Boolean = files.delete(id)
    override val warnings: List<String> get() = files.warnings
}

/**
 * The shipped styles. Each description says what the patch changes and nothing more; `muisc style show <id>` prints
 * the patch itself. No style can remove the crossfade: it stays the floor of every ranking.
 */
object BuiltInStyles {
    val all: List<StyleProfile> = listOf(
        StyleProfile(
            "smooth", "Smooth",
            "Long beat-matched blends. Energy 0.3; blends, bass swaps and harmonic blends favoured; bass swaps use the " +
                "32-bar preset and crossfades the 10-second one; hard phrase cuts and the brake/loop-roll showpieces weighted down.",
            PrefsPatch(
                energy = 0.3, preferredOverlapBars = 24, varietyPenalty = 0.2,
                prefer = listOf("beatMatchedBlend", "bassSwap", "harmonicBlend"),
                weights = mapOf("phraseCut" to 0.8, "brakeStop" to 0.5, "loopRollRiser" to 0.5),
                activePresets = mapOf("bassSwap" to "long-bass-swap", "crossfade" to "long-crossfade"),
            ),
        ),
        StyleProfile(
            "club", "Club",
            "DJ-booth mixing. Energy 0.7, key lock on; bass swaps, stem swaps and drum-break bridges favoured, bass swaps " +
                "use the tight 8-bar preset; crossfades and generated bridges weighted down (they remain as the fallback).",
            PrefsPatch(
                energy = 0.7, keyLock = true,
                prefer = listOf("bassSwap", "stemSwap", "drumBreakBridge"),
                weights = mapOf("crossfade" to 0.7, "ambientBridge" to 0.7),
                activePresets = mapOf("bassSwap" to "tight-bass-swap"),
            ),
        ),
        StyleProfile(
            "radio", "Radio",
            "Short changes that keep each song intact. Energy 0.4; phrase cuts, the producers' own outro/intro and " +
                "echo-outs favoured; long harmonic blends, stem swaps and generated bridges weighted down; 3-second crossfades.",
            PrefsPatch(
                energy = 0.4, preferredOverlapBars = 8,
                prefer = listOf("phraseCut", "outroIntroMinimal", "echoOut"),
                weights = mapOf("harmonicBlend" to 0.7, "stemSwap" to 0.7, "ambientBridge" to 0.6),
                activePresets = mapOf("crossfade" to "quick-crossfade"),
            ),
        ),
        StyleProfile(
            "chill", "Chill",
            "Slow, soft changes for listening. Energy 0.2, at most 4 % tempo change; ambient bridges, spectral freezes, " +
                "harmonic blends and outro/intro overlaps favoured; brake stops and loop rolls disabled; 10-second crossfades.",
            PrefsPatch(
                energy = 0.2, maxStretchPercent = 4.0, preferredOverlapBars = 32,
                prefer = listOf("ambientBridge", "spectralFreezeBridge", "harmonicBlend", "outroIntroMinimal"),
                disable = setOf("brakeStop", "loopRollRiser"),
                activePresets = mapOf("crossfade" to "long-crossfade"),
            ),
        ),
        StyleProfile(
            "adventurous", "Adventurous",
            "Showpieces welcome. Energy 0.9, tempo changes up to 12 %, a strong penalty (0.7) on repeating the previous " +
                "technique; loop rolls, brake stops, echo-outs, filter sweeps, drum-break bridges and stem swaps favoured.",
            PrefsPatch(
                energy = 0.9, maxStretchPercent = 12.0, varietyPenalty = 0.7,
                prefer = listOf("loopRollRiser", "brakeStop", "echoOut", "filterSweep", "drumBreakBridge", "stemSwap"),
            ),
        ),
        StyleProfile(
            "purist", "Purist",
            "Leaves both songs as they are. No effects, stems, generated bridges, texture beds, tempo glides or pitch " +
                "shifts; at most 3 % tempo change, so beat-matched blends and bass swaps only for close tempos (tight presets). " +
                "The producers' own outro/intro, dry phrase cuts and crossfades are favoured; the crossfade covers every pair the rest cannot.",
            PrefsPatch(
                energy = 0.1, maxStretchPercent = 3.0, maxPitchShiftSemitones = 0.0, keyLock = true, preferredOverlapBars = 8,
                prefer = listOf("outroIntroMinimal", "crossfade", "phraseCut"),
                disable = setOf(
                    "echoOut", "filterSweep", "loopRollRiser", "brakeStop", "spectralFreezeBridge", "ambientBridge",
                    "drumBreakBridge", "stemSwap", "textureCarry", "tempoGlide",
                ),
                activePresets = mapOf("phraseCut" to "dry-cut", "bassSwap" to "tight-bass-swap", "beatMatchedBlend" to "short-blend"),
            ),
        ),
    )

    private val byIdMap = all.associateBy { it.id }

    fun byId(id: String): StyleProfile? = byIdMap[id]
}
