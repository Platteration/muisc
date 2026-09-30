package dev.muisc.app.playback

import dev.muisc.dsp.stems.StemQuality
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.StrategyRegistry
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionModifier
import dev.muisc.transitions.TransitionPlanner
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.custom.AtomicFiles
import dev.muisc.transitions.custom.BuiltInPresets
import dev.muisc.transitions.custom.CustomJson
import dev.muisc.transitions.custom.FeedbackLearner
import dev.muisc.transitions.custom.LearnedFactor
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.custom.PinLookup
import dev.muisc.transitions.custom.PlannerCustomization
import dev.muisc.transitions.custom.PresetLookup
import dev.muisc.transitions.custom.PresetResolution
import dev.muisc.transitions.custom.Rating
import dev.muisc.transitions.custom.StrategyPreset
import dev.muisc.transitions.custom.StyleProfile
import dev.muisc.transitions.custom.UserProfile
import dev.muisc.transitions.planner.DefaultTransitionPlanner
import dev.muisc.transitions.planner.ExplainedPlans
import dev.muisc.transitions.recipe.LibraryResult
import dev.muisc.transitions.recipe.RecipeCatalog
import dev.muisc.transitions.recipe.RecipeCodec
import dev.muisc.transitions.recipe.RecipeLibrary
import dev.muisc.transitions.recipe.RecipeOrigin
import dev.muisc.transitions.recipe.RecipeProblem
import dev.muisc.transitions.recipe.RecipeSet
import dev.muisc.transitions.recipe.RecipeValidator
import dev.muisc.transitions.recipe.TransitionRecipe
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns the stored (user-edited) [TransitionPrefs] into the prefs the engine plans and renders with. The playback
 * controller and the Lab apply it at the engine boundary only, so what Settings shows and writes back is always
 * the unshaped value and a style is never "baked into" the stored prefs.
 */
interface PrefsShaper {
    /** Bumped whenever [shape] would give a different answer for the same input (the style changed). */
    val version: StateFlow<Long>

    fun shape(base: TransitionPrefs): TransitionPrefs

    companion object {
        /** Leaves prefs alone (the controller's default, and every test that does not care about styles). */
        val IDENTITY: PrefsShaper = object : PrefsShaper {
            override val version: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
            override fun shape(base: TransitionPrefs): TransitionPrefs = base
        }
    }
}

/**
 * A [StrategyRegistry] whose contents can be swapped while the engine runs: the planner, the renderer and the
 * controller hold this one object, and a recipe import / delete / duplicate replaces what it delegates to. Every
 * read sees one complete registry (the reference is swapped atomically), never a half-built one.
 */
class LiveStrategyRegistry(initial: DefaultStrategyRegistry) : StrategyRegistry {
    @Volatile
    var current: DefaultStrategyRegistry = initial
        private set

    override val strategies: List<TransitionStrategy> get() = current.strategies
    override val modifiers: List<TransitionModifier> get() = current.modifiers
    override fun strategy(id: String): TransitionStrategy? = current.strategy(id)
    override fun modifier(id: String): TransitionModifier? = current.modifier(id)

    fun replace(next: DefaultStrategyRegistry) {
        current = next
    }
}

/**
 * A one-off choice for the transition between two specific songs ("use this technique for the next transition").
 * It is a [PairPin] that lives only in memory. [aKeys] / [bKeys] hold the track identities it applies to: the real
 * `TrackAnalysis.identity` of each song and, because the coordinator may still be holding the stand-in analysis of a
 * track it started before its analysis finished, that stand-in's fingerprint (`placeholder:<songId>`). Both keys
 * name the same song; nothing here is keyed by a file path.
 */
data class SessionOverride(
    val aSongId: Long,
    val bSongId: Long,
    val aKeys: Set<String>,
    val bKeys: Set<String>,
    val pin: PairPin,
)

/** The in-memory pin lookup that carries at most one [SessionOverride]; consulted before the user's stored pins. */
class SessionPins : PinLookup {
    @Volatile
    var override: SessionOverride? = null
        private set

    override fun pin(aFingerprint: String, bFingerprint: String): PairPin? {
        val o = override ?: return null
        return if (aFingerprint in o.aKeys && bFingerprint in o.bKeys) o.pin else null
    }

    fun set(value: SessionOverride) {
        override = value
    }

    fun clear() {
        override = null
    }
}

/**
 * A [TransitionPlanner] that forwards to the current [DefaultTransitionPlanner]. The engine's planner takes its
 * customization at construction and a [FeedbackLearner] cannot be emptied in place, so "reset learned preferences"
 * builds a new planner over a fresh learner and swaps it in here; the coordinator and the Lab keep their reference.
 */
class DjPlanner(initial: DefaultTransitionPlanner) : TransitionPlanner {
    @Volatile
    var current: DefaultTransitionPlanner = initial
        private set

    override fun plan(a: TrackRef, b: TrackRef, prefs: TransitionPrefs, seed: Long, previousStrategyId: String?): RankedPlans =
        current.plan(a, b, prefs, seed, previousStrategyId)

    fun planExplained(a: TrackRef, b: TrackRef, prefs: TransitionPrefs, seed: Long = 0L, previousStrategyId: String? = null): ExplainedPlans =
        current.planExplained(a, b, prefs, seed, previousStrategyId)

    fun replace(next: DefaultTransitionPlanner) {
        current = next
    }
}

/** What [DjCustomization.importRecipeText] did with a recipe file. */
sealed interface RecipeImport {
    /** Saved; [problems] are the warnings (and, when imported with errors, the errors) found in it. */
    data class Imported(val id: String, val name: String, val problems: List<RecipeProblem>) : RecipeImport

    /** Not saved: the text is not a recipe ([problems] carry line and column where the parser knows them). */
    data class Unreadable(val problems: List<RecipeProblem>) : RecipeImport

    /** Not saved: the recipe has errors. Importing again with `allowErrors` keeps it (disabled until fixed). */
    data class HasErrors(val id: String, val name: String, val problems: List<RecipeProblem>) : RecipeImport

    /** Not saved: one of the user's recipes already has this id. Importing again with `replace` overwrites it. */
    data class Conflict(val id: String, val existingName: String, val problems: List<RecipeProblem>) : RecipeImport

    /** Not saved for another reason (the library refused it, the file could not be written, ...). */
    data class Failed(val message: String, val problems: List<RecipeProblem>) : RecipeImport
}

/**
 * The phone's DJ customization, in one place: the user's profile directory (`<filesDir>/dj`, laid out exactly like
 * the CLI's `--profile-dir`: `presets/`, `styles/`, `recipes/`, `pins.json`, `feedback.json`), the recipe library
 * over `<dir>/recipes` plus the built-ins shipped in the engine jar, the live strategy registry
 * (`DefaultStrategyRegistry.default()` + one `recipe:<id>` per usable recipe, [RecipeCatalog]), the planner with
 * the profile's [PlannerCustomization], the chosen style, and the in-memory one-off override.
 *
 * Pure JVM (no Android types), so it is unit-tested on the desktop. Nothing here throws on bad files: a recipe,
 * preset, style, pin or rating file that cannot be read is reported in [problems] and skipped (AGENTS.md §5); the
 * constructor itself never reads a recipe (call [reloadRecipes] off the main thread).
 *
 * The chosen style is stored in `<dir>/style` (one line: the style id) so the engine can read it synchronously
 * when it starts, before any other settings have loaded.
 *
 * @param base the built-in catalogue the recipes are added to.
 * @param classLoader where the built-in recipes (`recipes/index.txt` + files) are read from; the engine jar's
 *   resources, which on Android are packaged into the APK and served by the app's class loader.
 */
class DjCustomization(
    val dir: File,
    private val base: DefaultStrategyRegistry = DefaultStrategyRegistry.default(),
    private val stemQuality: StemQuality? = StemQuality.PSEUDO,
    classLoader: ClassLoader = RecipeLibrary::class.java.classLoader,
) : PrefsShaper {

    val recipesDir: File = File(dir, RECIPES_DIR)
    private val styleFile: File = File(dir, STYLE_FILE)
    private val feedbackFile: File = File(dir, FEEDBACK_FILE)

    val library: RecipeLibrary = RecipeLibrary(recipesDir, RecipeValidator(base.modifiers.map { it.id }.toSet()), classLoader)

    @Volatile
    var profile: UserProfile = UserProfile(dir)
        private set

    val registry: LiveStrategyRegistry = LiveStrategyRegistry(base)
    val sessionPins: SessionPins = SessionPins()
    val planner: DjPlanner = DjPlanner(buildPlanner())

    @Volatile
    var recipeSet: RecipeSet = RecipeSet(emptyList(), emptyList())
        private set

    /** Recipe ids that are valid but were left out of the registry, with why. */
    @Volatile
    var recipeSkipped: Map<String, String> = emptyMap()
        private set

    /** True until the first [reloadRecipes] finished. */
    @Volatile
    var recipesLoading: Boolean = true
        private set

    @Volatile
    private var loadFailure: String? = null

    private val _version = MutableStateFlow(0L)
    override val version: StateFlow<Long> = _version.asStateFlow()

    @Volatile
    private var styleProblem: String? = null

    @Volatile
    var style: StyleProfile? = readStyle()
        private set

    // ================================================================================================ recipes

    /**
     * Re-reads every built-in and user recipe and swaps the registry. Never throws: anything unexpected (a class
     * loader that cannot read the engine's resources, an I/O error) leaves the built-in strategies in place and is
     * reported in [problems].
     */
    @Synchronized
    fun reloadRecipes(): RecipeSet {
        try {
            val set = library.load()
            val built = RecipeCatalog.build(base, set)
            recipeSet = set
            recipeSkipped = built.skipped.filterKeys { id -> set.all(id).any { it.active } }
            registry.replace(built.registry)
            loadFailure = null
        } catch (t: Throwable) {
            loadFailure = "recipes could not be loaded (${t.message ?: t.javaClass.simpleName}); only the built-in techniques are available"
            registry.replace(base)
        } finally {
            recipesLoading = false
        }
        return recipeSet
    }

    /**
     * Everything the user should know about their customization files: recipe files that could not be read or
     * have errors, recipes left out of the registry, unreadable presets / styles / pins / ratings, a style that no
     * longer exists. One line each; empty when everything loaded.
     */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        loadFailure?.let { out += it }
        for (p in recipeSet.errors) out += "recipe ${p.source.substringAfterLast('/')}: ${p.problem}"
        for ((id, why) in recipeSkipped) out += "recipe '$id' is not used: $why"
        styleProblem?.let { out += it }
        try {
            out += profile.warnings()
        } catch (t: Throwable) {
            out += "the customization folder could not be read: ${t.message ?: t.javaClass.simpleName}"
        }
        return out.distinct()
    }

    /**
     * Imports a recipe from the text of a `.json` file. Never throws. See [RecipeImport] for the outcomes; nothing
     * is written unless the outcome is [RecipeImport.Imported], and an existing user recipe is only replaced when
     * [replace] is true (the caller asks the user first).
     */
    @Synchronized
    fun importRecipeText(text: String, allowErrors: Boolean = false, replace: Boolean = false): RecipeImport {
        // Nested deeply enough, the JSON or an expression in it overflows the parser's recursion. A
        // StackOverflowError is an Error, which nothing up to the ViewModel catches: it would end the process.
        val parsed = try {
            RecipeCodec.parse(text)
        } catch (e: StackOverflowError) {
            return RecipeImport.Unreadable(listOf(RecipeProblem.error("", TOO_DEEP)))
        }
        val recipe = parsed.recipe ?: return RecipeImport.Unreadable(parsed.problems)
        val problems = try {
            parsed.locate(library.validator.validate(recipe).problems)
        } catch (e: StackOverflowError) {
            return RecipeImport.Unreadable(listOf(RecipeProblem.error("", TOO_DEEP)))
        }
        if (!RecipeValidator.ID_PATTERN.matches(recipe.id)) {
            return RecipeImport.Failed("the recipe id '${recipe.id}' may only use lowercase letters, digits and dashes", problems)
        }
        if (problems.any { it.isError } && !allowErrors) return RecipeImport.HasErrors(recipe.id, recipe.name, problems)
        // Loading and saving read the files already in the recipes folder, and one of those can be the deep one.
        // Both overflow before anything is written (the library writes last), so the import is refused.
        val existing = try {
            library.load().all(recipe.id).firstOrNull { it.origin == RecipeOrigin.USER }
        } catch (e: StackOverflowError) {
            return RecipeImport.Failed(FOLDER_TOO_DEEP, problems)
        }
        if (existing != null && !replace) return RecipeImport.Conflict(recipe.id, existing.recipe.name, problems)
        val result = try {
            library.save(recipe, allowErrors = allowErrors)
        } catch (e: StackOverflowError) {
            return RecipeImport.Failed(FOLDER_TOO_DEEP, problems)
        }
        reloadRecipes()
        return if (result.ok) RecipeImport.Imported(recipe.id, recipe.name, problems)
        else RecipeImport.Failed(result.problems.firstOrNull { it.isError }?.message ?: "the recipe was not saved", result.problems)
    }

    /** The recipe [id] in use (or, failing that, any loaded one) as file text, for export and share. */
    fun recipeText(id: String): String? {
        val set = recipeSet
        val entry = set[id] ?: set.all(id).firstOrNull() ?: return null
        return RecipeCodec.encode(entry.recipe)
    }

    /** Saves a copy of recipe [id] under the first free id `<id>-copy`, `<id>-copy-2`, ... */
    @Synchronized
    fun duplicateRecipe(id: String): LibraryResult {
        val result = try {
            val taken = library.load().entries.map { it.id }.toSet()
            val newId = freeId("$id-copy", taken)
            library.duplicate(id, newId)
        } catch (e: StackOverflowError) {
            LibraryResult(false, null, listOf(RecipeProblem.error("", FOLDER_TOO_DEEP)))
        }
        reloadRecipes()
        return result
    }

    /** Deletes the user recipe [id]; built-ins cannot be deleted. */
    @Synchronized
    fun deleteRecipe(id: String): LibraryResult {
        val result = try {
            library.delete(id)
        } catch (e: StackOverflowError) {
            LibraryResult(false, null, listOf(RecipeProblem.error("", FOLDER_TOO_DEEP)))
        }
        reloadRecipes()
        return result
    }

    // ================================================================================================ styles

    /** Built-in and user styles. */
    fun styles(): List<StyleProfile> = try {
        profile.styles.all()
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Chooses the listening style (null = none) and stores the choice. Throws [IllegalArgumentException] for an
     * unknown id and [IOException] when the choice cannot be written — the caller shows the message.
     */
    @Synchronized
    fun chooseStyle(id: String?) {
        val next = if (id == null) null else profile.style(id) ?: throw IllegalArgumentException("there is no style '$id'")
        if (next == null) {
            if (styleFile.exists() && !styleFile.delete()) throw IOException("cannot clear the style choice in ${styleFile.path}")
        } else {
            AtomicFiles.write(styleFile, next.id + "\n")
        }
        style = next
        styleProblem = null
        _version.value = _version.value + 1
    }

    private fun readStyle(): StyleProfile? {
        if (!styleFile.isFile) return null
        val id = try {
            styleFile.readText(Charsets.UTF_8).trim()
        } catch (t: Throwable) {
            styleProblem = "the chosen style could not be read (${t.message ?: t.javaClass.simpleName}); no style is applied"
            return null
        }
        if (id.isEmpty()) return null
        val found = try {
            profile.style(id)
        } catch (t: Throwable) {
            null
        }
        if (found == null) styleProblem = "the chosen style '$id' no longer exists; no style is applied"
        return found
    }

    /**
     * [PrefsShaper]: the chosen style applied to [base], then every active preset folded into `paramOverrides`
     * ([PresetResolution.fold]) — the same order the CLI uses for `--style`. Never throws; on any failure the
     * unshaped prefs are returned.
     */
    override fun shape(base: TransitionPrefs): TransitionPrefs = try {
        val styled = style?.apply(base) ?: base
        PresetResolution.fold(styled, profile.presetLookup)
    } catch (t: Throwable) {
        base
    }

    // ================================================================================================ presets

    /** Built-ins first, then the user's presets. */
    fun presets(): List<StrategyPreset> = try {
        profile.presets.all()
    } catch (t: Throwable) {
        BuiltInPresets.all
    }

    fun isBuiltInPreset(id: String): Boolean = BuiltInPresets.byId(id) != null

    /**
     * Saves the values of [strategy]'s own parameters from [params] as a new user preset named [name]; keys that
     * are not parameters of the strategy (modifier values the Lab carries along, `tempoGlide.*`) are left out.
     * The id is derived from the name and never overwrites an existing preset. Returns the saved preset.
     */
    @Synchronized
    fun savePreset(name: String, strategy: TransitionStrategy, params: Params, note: String = ""): StrategyPreset {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "a preset needs a name" }
        val ids = strategy.params.map { it.id }.toSet()
        val values = params.values.filterKeys { it in ids }
        val taken = presets().map { it.id }.toSet()
        val preset = StrategyPreset(
            id = freeId(slug(cleanName), taken),
            name = cleanName,
            strategyId = strategy.id,
            params = Params(values),
            note = note,
        )
        profile.presets.save(preset)
        return preset
    }

    fun deletePreset(id: String): Boolean {
        require(!isBuiltInPreset(id)) { "'$id' is a built-in preset and cannot be deleted" }
        return profile.presets.delete(id)
    }

    fun preset(id: String): StrategyPreset? = profile.presetLookup.preset(id)

    // ================================================================================================ pins

    fun pins(): List<PairPin> = try {
        profile.pins.list()
    } catch (t: Throwable) {
        emptyList()
    }

    fun setPin(pin: PairPin) = profile.pins.set(pin)

    fun removePin(aIdentity: String, bIdentity: String): Boolean = profile.pins.clear(aIdentity, bIdentity)

    // ================================================================================================ ratings

    /** Every rated (strategy, context bucket) with its learned factor and the raw tally behind it. */
    fun learned(): List<Pair<String, LearnedFactor>> = try {
        profile.feedback.learner.table()
    } catch (t: Throwable) {
        emptyList()
    }

    /** Records [rating] for [strategyId] on a pair with [features]; the planner uses it from the next plan on. */
    fun record(strategyId: String, features: PairFeatures, rating: Rating): LearnedFactor =
        profile.feedback.record(strategyId, features, rating)

    /**
     * Forgets the ratings of [strategyId] (every strategy when null). The previous `feedback.json` is kept as
     * `feedback.json.bak` (replacing an older backup), so a reset tapped by mistake can be undone by hand. The
     * planner switches to the new tallies immediately.
     */
    @Synchronized
    fun resetLearned(strategyId: String?) {
        val keep: Map<String, Map<String, dev.muisc.transitions.custom.RatingTally>> =
            if (strategyId == null) emptyMap() else profile.feedback.learner.snapshot().filterKeys { it != strategyId }
        if (feedbackFile.isFile) {
            Files.move(feedbackFile.toPath(), File(dir, "$FEEDBACK_FILE.bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (keep.isNotEmpty()) AtomicFiles.write(feedbackFile, FeedbackLearner(keep).toJson() + "\n")
        profile = UserProfile(dir)
        planner.replace(buildPlanner())
    }

    // ================================================================================================ planner

    private fun buildPlanner(): DefaultTransitionPlanner {
        val p = profile
        val customization = PlannerCustomization(
            presets = PresetLookup { id -> p.presetLookup.preset(id) },
            pins = PinLookup.chain(sessionPins, PinLookup { a, b -> p.pins.pin(a, b) }),
            learner = try {
                p.feedback.learner
            } catch (t: Throwable) {
                null
            },
        )
        return DefaultTransitionPlanner(registry, stemQuality = stemQuality, customization = customization)
    }

    companion object {
        const val PROFILE_DIR = "dj"
        const val RECIPES_DIR = "recipes"
        const val STYLE_FILE = "style"
        const val FEEDBACK_FILE = "feedback.json"

        /** Why a file whose JSON or expressions are nested too deeply to parse is [RecipeImport.Unreadable]. */
        const val TOO_DEEP = "the file is nested too deeply to read as a recipe"

        /** Why a recipe was not saved, copied or deleted: a file already in the recipes folder is nested too deeply. */
        const val FOLDER_TOO_DEEP = "a file in the recipes folder is nested too deeply to read; move it out of the folder and try again"

        /** `"Tight 8-bar swap!"` → `"tight-8-bar-swap"`; never empty, at most 48 characters. */
        fun slug(text: String): String {
            val s = text.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-').take(48).trim('-')
            return s.ifEmpty { "preset" }
        }

        /** [wanted] when free, else `wanted-2`, `wanted-3`, ... (always a valid id). */
        fun freeId(wanted: String, taken: Set<String>): String {
            val stem = wanted.take(56).trim('-').ifEmpty { "item" }
            if (stem !in taken && CustomJson.ID.matches(stem)) return stem
            var n = 2
            while ("$stem-$n" in taken) n++
            return "$stem-$n"
        }

        /** A recipe's strategy id → the recipe id, or null for a built-in strategy. */
        fun recipeIdOf(strategyId: String): String? =
            if (strategyId.startsWith(TransitionRecipe.STRATEGY_PREFIX)) strategyId.removePrefix(TransitionRecipe.STRATEGY_PREFIX) else null
    }
}
