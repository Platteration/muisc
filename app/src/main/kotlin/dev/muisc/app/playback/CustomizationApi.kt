package dev.muisc.app.playback

import dev.muisc.app.data.db.Song
import dev.muisc.transitions.Params
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.custom.Rating
import dev.muisc.transitions.recipe.RecipeOrigin
import dev.muisc.transitions.recipe.RecipeProblem
import dev.muisc.transitions.recipe.RecipeStatus
import dev.muisc.transitions.recipe.TransitionRecipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A listening style as the Style screen shows it. */
data class StyleInfo(
    val id: String,
    val name: String,
    val description: String,
    val builtIn: Boolean,
    /** One line per setting the style changes (`energy = 0.7`, `prefer bassSwap (weight × 1.3)`, ...). */
    val settings: List<String>,
)

/** One recipe found by the library, with everything the list and the detail screen need. */
data class RecipeInfo(
    val id: String,
    /** `recipe:<id>`: the strategy id the planner, pins, presets and `disabledStrategies` use. */
    val strategyId: String,
    val name: String,
    val description: String,
    val origin: RecipeOrigin,
    val status: RecipeStatus,
    /** False when the user switched it off (its strategy id is in `prefs.disabledStrategies`). */
    val enabled: Boolean,
    /** Errors first, then warnings, each with line/column where known. */
    val problems: List<RecipeProblem>,
    /** Why a valid recipe is still not in use (a modifier this build lacks), or null. */
    val skippedReason: String?,
    val recipe: TransitionRecipe,
) {
    val errorCount: Int get() = problems.count { it.isError }
    val warningCount: Int get() = problems.count { !it.isError }

    /** True when the planner can pick it: valid, not shadowed or duplicated, enabled and not skipped. */
    val inUse: Boolean get() = status == RecipeStatus.ACTIVE && enabled && skippedReason == null
}

/** A strategy preset as the Presets screen and the Lab show it. */
data class PresetInfo(
    val id: String,
    val name: String,
    val strategyId: String,
    val strategyName: String,
    val params: Params,
    val note: String,
    val builtIn: Boolean,
    /**
     * True when this is the preset the engine uses for its strategy: the chosen style's preset for the strategy if
     * the style sets one (a style's choice replaces the user's, as `PrefsPatch.apply` does), else the user's
     * `prefs.activePresets[strategyId]`.
     */
    val active: Boolean,
    /** True when the chosen style sets this preset for its strategy (the user's own choice is then not in effect). */
    val activeByStyle: Boolean,
)

/** A stored pin ("always use this transition from A into B"). */
data class PinInfo(
    val aIdentity: String,
    val bIdentity: String,
    val aLabel: String,
    val bLabel: String,
    val strategyId: String,
    val strategyName: String,
    val presetId: String?,
    val note: String,
)

/** What the ratings taught the planner for one strategy in one context bucket. */
data class LearnedInfo(
    val strategyId: String,
    val strategyName: String,
    val bucketKey: String,
    /** e.g. "matched, in key, rising". */
    val bucketLabel: String,
    val multiplier: Double,
    val ratings: Int,
    /** Skipped transitions behind [multiplier] (implicit feedback), counted apart from [ratings]. */
    val skips: Int = 0,
)

/** A technique id with its display name (for pickers). */
data class TechniqueInfo(val id: String, val displayName: String, val description: String, val isRecipe: Boolean, val params: List<ParamSpec>)

/** The one-off choice in force for the upcoming transition. */
data class NextOverride(val aSongId: Long, val bSongId: Long, val strategyId: String, val displayName: String)

/** Everything the DJ screens show, as one snapshot. */
data class DjState(
    /** True until the real implementation is connected and the recipes have been read once. */
    val loading: Boolean = true,
    val styles: List<StyleInfo> = emptyList(),
    val currentStyleId: String? = null,
    val recipes: List<RecipeInfo> = emptyList(),
    val presets: List<PresetInfo> = emptyList(),
    val pins: List<PinInfo> = emptyList(),
    val learned: List<LearnedInfo> = emptyList(),
    val techniques: List<TechniqueInfo> = emptyList(),
    /** Problems met while reading the customization files, one line each (safety net: they never stop startup). */
    val problems: List<String> = emptyList(),
    val nextOverride: NextOverride? = null,
    /** Where the files live, for the About line ("/data/.../files/dj"). */
    val directory: String = "",
)

/** Outcome of a command: a message for the snackbar and, for recipes, the problems found. */
data class DjResult(val ok: Boolean, val message: String, val problems: List<RecipeProblem> = emptyList()) {
    companion object {
        fun ok(message: String, problems: List<RecipeProblem> = emptyList()) = DjResult(true, message, problems)
        fun fail(message: String, problems: List<RecipeProblem> = emptyList()) = DjResult(false, message, problems)
    }
}

/** What importing a recipe file did; the UI asks the user when the outcome needs a decision. */
sealed interface ImportOutcome {
    data class Imported(val id: String, val name: String, val warnings: List<RecipeProblem>) : ImportOutcome
    /** The file could not be read as a recipe. */
    data class Unreadable(val message: String, val problems: List<RecipeProblem>) : ImportOutcome
    /** It is a recipe but has errors: import anyway (kept, never used until fixed) or cancel. */
    data class HasErrors(val id: String, val name: String, val problems: List<RecipeProblem>) : ImportOutcome
    /** One of the user's recipes already uses this id: replace it or cancel. */
    data class Conflict(val id: String, val existingName: String, val problems: List<RecipeProblem>) : ImportOutcome
    data class Failed(val message: String, val problems: List<RecipeProblem>) : ImportOutcome
}

/** One technique offered for the upcoming transition, with the planner's reasons. */
data class TechniqueOption(
    val strategyId: String,
    val displayName: String,
    val score: Double,
    /** The strategy's own reasons followed by the planner's score formula and customization notes. */
    val reasons: List<String>,
    val isRecipe: Boolean,
    /** The coordinator has planned / rendered this one for the edge right now. */
    val planned: Boolean,
    /** The user's one-off choice for this edge. */
    val chosen: Boolean,
)

/** A technique the planner could not use for the pair, and why. */
data class UnavailableTechnique(val strategyId: String, val displayName: String, val reason: String)

/** The upcoming transition (current song → next song) and every technique that could perform it. */
data class UpcomingChoice(
    val a: Song,
    val b: Song,
    val options: List<TechniqueOption>,
    val unavailable: List<UnavailableTechnique>,
    /** Why transitions are off for this pair (album playback, disabled, ...); picking is pointless then. */
    val gatedReason: String?,
    /** True while the transition itself is playing: it can no longer be changed. */
    val inTransition: Boolean,
    /** Planner notes (pin outcome, preset problems). */
    val notes: List<String>,
)

/**
 * The DJ customization the UI drives: styles, recipes, presets, pins, learned preferences and the one-off choice
 * for the next transition. Installed through `AppGraph` the same way the Transition Lab is: the UI holds a
 * delegating instance; the playback service installs the real one (it needs the engine). Every suspending call runs
 * off the main thread and never throws for a user mistake — the [DjResult] / [ImportOutcome] carries the message.
 */
interface CustomizationApi {
    val state: StateFlow<DjState>

    /** Re-reads every customization file (after the user changed something outside the app, or to retry). */
    suspend fun refresh()

    // ---- styles
    suspend fun chooseStyle(id: String?): DjResult

    // ---- recipes
    /** Imports the `.json` document at [uri] (a Storage Access Framework `content://` URI). */
    suspend fun importRecipe(uri: String, allowErrors: Boolean = false, replace: Boolean = false): ImportOutcome
    /** Writes recipe [id] to the document at [uri] (created by the Storage Access Framework). */
    suspend fun exportRecipe(id: String, uri: String): DjResult
    /** The recipe file's text, for sharing. Null when there is no such recipe. */
    suspend fun recipeText(id: String): String?
    suspend fun duplicateRecipe(id: String): DjResult
    suspend fun deleteRecipe(id: String): DjResult
    /** Adds / removes `recipe:<id>` in `prefs.disabledStrategies`. */
    suspend fun setRecipeEnabled(id: String, enabled: Boolean): DjResult

    // ---- presets
    suspend fun savePreset(name: String, strategyId: String, params: Params): DjResult
    suspend fun deletePreset(id: String): DjResult
    /** Makes [presetId] the active preset of [strategyId] (`prefs.activePresets`); null clears it. */
    suspend fun setActivePreset(strategyId: String, presetId: String?): DjResult

    // ---- pins
    suspend fun removePin(aIdentity: String, bIdentity: String): DjResult

    // ---- learned preferences
    suspend fun rate(a: Song, b: Song, strategyId: String, rating: Rating): DjResult
    /** Forgets the ratings of [strategyId], or every rating when null. */
    suspend fun resetLearned(strategyId: String?): DjResult

    // ---- the upcoming transition
    /** Plans current → next with the playback planner and lists every technique with its reasons. */
    suspend fun upcoming(): UpcomingChoice?
    /** Uses [strategyId] for the transition out of the current song (null = back to the planner's choice). */
    suspend fun overrideNext(strategyId: String?): DjResult
}

/**
 * [CustomizationApi] used before the playback service has installed the real one: an empty, "connecting" state;
 * every command fails with a message the screens show.
 */
object NoOpCustomization : CustomizationApi {
    const val NOT_CONNECTED = "Playback engine is not connected yet"

    private val empty = MutableStateFlow(DjState(loading = true))
    override val state: StateFlow<DjState> = empty.asStateFlow()

    override suspend fun refresh() {}
    override suspend fun chooseStyle(id: String?) = DjResult.fail(NOT_CONNECTED)
    override suspend fun importRecipe(uri: String, allowErrors: Boolean, replace: Boolean): ImportOutcome =
        ImportOutcome.Failed(NOT_CONNECTED, emptyList())
    override suspend fun exportRecipe(id: String, uri: String) = DjResult.fail(NOT_CONNECTED)
    override suspend fun recipeText(id: String): String? = null
    override suspend fun duplicateRecipe(id: String) = DjResult.fail(NOT_CONNECTED)
    override suspend fun deleteRecipe(id: String) = DjResult.fail(NOT_CONNECTED)
    override suspend fun setRecipeEnabled(id: String, enabled: Boolean) = DjResult.fail(NOT_CONNECTED)
    override suspend fun savePreset(name: String, strategyId: String, params: Params) = DjResult.fail(NOT_CONNECTED)
    override suspend fun deletePreset(id: String) = DjResult.fail(NOT_CONNECTED)
    override suspend fun setActivePreset(strategyId: String, presetId: String?) = DjResult.fail(NOT_CONNECTED)
    override suspend fun removePin(aIdentity: String, bIdentity: String) = DjResult.fail(NOT_CONNECTED)
    override suspend fun rate(a: Song, b: Song, strategyId: String, rating: Rating) = DjResult.fail(NOT_CONNECTED)
    override suspend fun resetLearned(strategyId: String?) = DjResult.fail(NOT_CONNECTED)
    override suspend fun upcoming(): UpcomingChoice? = null
    override suspend fun overrideNext(strategyId: String?) = DjResult.fail(NOT_CONNECTED)
}
