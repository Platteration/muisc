package dev.muisc.app.playback

import android.content.Context
import android.net.Uri
import dev.muisc.app.data.db.Song
import dev.muisc.app.data.prefs.SettingsRepository
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.PairAnalyzer
import dev.muisc.transitions.Params
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.BuiltInStyles
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.custom.Rating
import dev.muisc.transitions.recipe.RecipeStatus
import dev.muisc.transitions.recipe.TransitionRecipe
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The real [CustomizationApi], over the [DjCustomization] the playback engine plans with, the playback controller
 * (for the upcoming transition) and the settings (for `disabledStrategies` and `activePresets`, which live in the
 * stored [TransitionPrefs] like every other preference).
 *
 * **The one-off override** ("use this technique for the next transition", [overrideNext]) is built from two
 * existing engine mechanisms and adds no new planner code:
 *  1. a *session pin* ([SessionPins], in memory only) for the ordered pair current song → next song, keyed by
 *     both tracks' identities, which the planner consults before the stored pins — so the chosen strategy is ranked
 *     first whenever it applies to the pair (and the planner falls back, with the reason in its explanation, when
 *     it does not);
 *  2. a re-plan of that one edge: [EngineControllerImpl.replanNext] gives the next song a new engine-id
 *     generation and re-installs the queue, so the coordinator drops the planned or rendered transition (and its
 *     retained render) and plans the edge again — now with the pin.
 * The override is cleared as soon as the current song or the next song changes (the transition played, the user
 * skipped or reordered), so it never applies to a later occurrence of the same pair.
 */
class CustomizationImpl(
    context: Context,
    private val core: DjCustomization,
    private val controller: EngineControllerImpl,
    private val analyses: AndroidAnalysisService,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val pairAnalyzer: PairAnalyzer = DefaultPairAnalyzer(),
) : CustomizationApi {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow(DjState(loading = true, directory = core.dir.path))
    override val state: StateFlow<DjState> = _state.asStateFlow()

    private var watchers: List<Job> = emptyList()

    /** Starts watching settings and playback; the first state is published once the recipes have been read. */
    fun start() {
        watchers = listOf(
            scope.launch(Dispatchers.IO) {
                // Every change of the stored prefs (a recipe switched off, an active preset) re-derives the lists.
                settings.transitionPrefs.collect { prefs -> publish(prefs) }
            },
            scope.launch {
                controller.state.collect { player -> dropStaleOverride(player) }
            },
        )
    }

    fun stop() {
        watchers.forEach { it.cancel() }
        watchers = emptyList()
    }

    // ================================================================================================ state

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        core.reloadRecipes()
        publish(currentPrefs())
    }

    private suspend fun currentPrefs(): TransitionPrefs = try {
        settings.currentTransitionPrefs()
    } catch (t: Throwable) {
        TransitionPrefs()
    }

    private val publishLock = Any()

    /**
     * Rebuilds [state] from the files and [prefs]. Never throws: a failure becomes a problem line. Serialised, so a
     * slow rebuild that started before the recipes were read can never overwrite a newer one that saw them.
     */
    private fun publish(prefs: TransitionPrefs) {
        synchronized(publishLock) {
            val next = try {
                buildState(prefs)
            } catch (t: Throwable) {
                _state.value.copy(loading = false, problems = listOf("could not read the DJ settings: ${t.message ?: t.javaClass.simpleName}"))
            }
            _state.value = next
        }
    }

    private fun buildState(prefs: TransitionPrefs): DjState {
        val registry = core.registry.current
        val names = registry.strategies.associate { it.id to it.displayName }
        fun nameOf(id: String) = names[id] ?: core.recipeSet.all(DjCustomization.recipeIdOf(id) ?: "").firstOrNull()?.recipe?.name ?: id

        val styles = core.styles().map { s ->
            StyleInfo(s.id, s.name, s.description, BuiltInStyles.byId(s.id) != null, s.patch.describe())
        }
        val style = core.style
        val styledPresets = style?.patch?.activePresets.orEmpty()
        val set = core.recipeSet
        val recipes = set.entries.map { e ->
            RecipeInfo(
                id = e.id,
                strategyId = e.recipe.strategyId,
                name = e.recipe.name,
                description = e.recipe.description,
                origin = e.origin,
                status = e.status,
                enabled = e.recipe.strategyId !in prefs.disabledStrategies,
                problems = e.errors + e.warnings,
                skippedReason = if (e.status == RecipeStatus.ACTIVE) core.recipeSkipped[e.id] else null,
                recipe = e.recipe,
            )
        }
        val presets = core.presets().map { p ->
            // A style's preset replaces the user's for the same strategy (PrefsPatch.apply), so it is the one in use.
            val effective = styledPresets[p.strategyId] ?: prefs.activePresets[p.strategyId]
            PresetInfo(
                id = p.id, name = p.name, strategyId = p.strategyId, strategyName = nameOf(p.strategyId),
                params = p.params, note = p.note, builtIn = core.isBuiltInPreset(p.id),
                active = effective == p.id, activeByStyle = styledPresets[p.strategyId] == p.id,
            )
        }
        val pins = core.pins().map { p ->
            PinInfo(p.aFingerprint, p.bFingerprint, p.aLabel, p.bLabel, p.strategyId, nameOf(p.strategyId), p.presetId, p.note)
        }
        val learned = core.learned().mapNotNull { (id, f) ->
            val bucket = f.bucket ?: return@mapNotNull null
            LearnedInfo(id, nameOf(id), bucket.key, bucket.label, f.multiplier, f.ratings, f.implicit)
        }
        val techniques = registry.strategies.map { s ->
            TechniqueInfo(s.id, s.displayName, s.description, DjCustomization.recipeIdOf(s.id) != null, s.params)
        }
        val override = core.sessionPins.override?.let { o ->
            NextOverride(o.aSongId, o.bSongId, o.pin.strategyId, nameOf(o.pin.strategyId))
        }
        return DjState(
            loading = core.recipesLoading,
            styles = styles,
            currentStyleId = style?.id,
            recipes = recipes,
            presets = presets,
            pins = pins,
            learned = learned,
            techniques = techniques,
            problems = core.problems(),
            nextOverride = override,
            directory = core.dir.path,
        )
    }

    private suspend fun republish() = withContext(Dispatchers.IO) { publish(currentPrefs()) }

    /** The learned preferences changed outside this class (a skip was recorded): publish them. */
    fun learnedChanged() {
        scope.launch(Dispatchers.IO) { publish(currentPrefs()) }
    }

    // ================================================================================================ styles

    override suspend fun chooseStyle(id: String?): DjResult = command {
        core.chooseStyle(id)
        republish()
        DjResult.ok(if (id == null) "No style: your own settings only" else "Style: ${core.style?.name ?: id}")
    }

    // ================================================================================================ recipes

    override suspend fun importRecipe(uri: String, allowErrors: Boolean, replace: Boolean): ImportOutcome = withContext(Dispatchers.IO) {
        val text = try {
            readText(Uri.parse(uri))
        } catch (e: IOException) {
            return@withContext ImportOutcome.Unreadable("Could not read the file: ${e.message ?: e.javaClass.simpleName}", emptyList())
        } catch (e: SecurityException) {
            return@withContext ImportOutcome.Unreadable("Not allowed to read the file: ${e.message ?: e.javaClass.simpleName}", emptyList())
        }
        val outcome = when (val r = core.importRecipeText(text, allowErrors, replace)) {
            is RecipeImport.Imported -> ImportOutcome.Imported(r.id, r.name, r.problems)
            is RecipeImport.Unreadable -> ImportOutcome.Unreadable("This file is not a recipe", r.problems)
            is RecipeImport.HasErrors -> ImportOutcome.HasErrors(r.id, r.name, r.problems)
            is RecipeImport.Conflict -> ImportOutcome.Conflict(r.id, r.existingName, r.problems)
            is RecipeImport.Failed -> ImportOutcome.Failed(r.message, r.problems)
        }
        publish(currentPrefs())
        outcome
    }

    /**
     * The document's text, refusing anything over [MAX_IMPORT_BYTES]: the picker lets the user choose any file,
     * and reading a mistakenly chosen video into memory must not take the app down. Real recipes are a few KB.
     */
    private fun readText(uri: Uri): String {
        val input: InputStream = appContext.contentResolver.openInputStream(uri) ?: throw IOException("the file could not be opened")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_IMPORT_BYTES) throw IOException("the file is larger than ${MAX_IMPORT_BYTES / 1024} KB — recipes are small JSON files")
                out.write(buf, 0, n)
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        }
    }

    override suspend fun exportRecipe(id: String, uri: String): DjResult = command {
        val text = core.recipeText(id) ?: return@command DjResult.fail("There is no recipe '$id'")
        val out = appContext.contentResolver.openOutputStream(Uri.parse(uri)) ?: return@command DjResult.fail("The file could not be created")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        DjResult.ok("Exported '$id'")
    }

    override suspend fun recipeText(id: String): String? = withContext(Dispatchers.IO) { core.recipeText(id) }

    override suspend fun duplicateRecipe(id: String): DjResult = command {
        val r = core.duplicateRecipe(id)
        republish()
        if (r.ok) DjResult.ok("Copied as '${r.recipe?.id ?: ""}'", r.problems)
        else DjResult.fail(r.problems.firstOrNull { it.isError }?.message ?: "Could not copy '$id'", r.problems)
    }

    override suspend fun deleteRecipe(id: String): DjResult = command {
        val r = core.deleteRecipe(id)
        if (r.ok) {
            // A deleted recipe must not linger in disabledStrategies / activePresets (it could come back by import).
            val strategyId = TransitionRecipe.STRATEGY_PREFIX + id
            if (core.recipeSet.all(id).isEmpty()) {
                settings.updateTransition { p ->
                    p.copy(disabledStrategies = p.disabledStrategies - strategyId, activePresets = p.activePresets - strategyId)
                }
            }
        }
        republish()
        if (r.ok) DjResult.ok("Deleted '$id'", r.problems)
        else DjResult.fail(r.problems.firstOrNull { it.isError }?.message ?: "Could not delete '$id'", r.problems)
    }

    override suspend fun setRecipeEnabled(id: String, enabled: Boolean): DjResult = command {
        val strategyId = TransitionRecipe.STRATEGY_PREFIX + id
        settings.updateTransition { p ->
            p.copy(disabledStrategies = if (enabled) p.disabledStrategies - strategyId else p.disabledStrategies + strategyId)
        }
        DjResult.ok(if (enabled) "Recipe on" else "Recipe off: the planner will not use it")
    }

    // ================================================================================================ presets

    override suspend fun savePreset(name: String, strategyId: String, params: Params): DjResult = command {
        val strategy = core.registry.strategy(strategyId) ?: return@command DjResult.fail("Unknown technique '$strategyId'")
        val preset = core.savePreset(name, strategy, params)
        republish()
        DjResult.ok("Saved preset '${preset.name}'")
    }

    override suspend fun deletePreset(id: String): DjResult = command {
        val deleted = core.deletePreset(id)
        if (deleted) settings.updateTransition { p -> p.copy(activePresets = p.activePresets.filterValues { it != id }) }
        republish()
        if (deleted) DjResult.ok("Preset deleted") else DjResult.fail("There is no preset '$id'")
    }

    override suspend fun setActivePreset(strategyId: String, presetId: String?): DjResult = command {
        if (presetId != null) {
            val preset = core.preset(presetId) ?: return@command DjResult.fail("There is no preset '$presetId'")
            if (preset.strategyId != strategyId) return@command DjResult.fail("'$presetId' is a preset for ${preset.strategyId}")
        }
        settings.updateTransition { p ->
            p.copy(activePresets = if (presetId == null) p.activePresets - strategyId else p.activePresets + (strategyId to presetId))
        }
        DjResult.ok(if (presetId == null) "Defaults restored" else "Preset in use")
    }

    // ================================================================================================ pins

    override suspend fun removePin(aIdentity: String, bIdentity: String): DjResult = command {
        val removed = core.removePin(aIdentity, bIdentity)
        republish()
        if (removed) DjResult.ok("Pin removed") else DjResult.fail("That pin no longer exists")
    }

    // ================================================================================================ ratings

    override suspend fun rate(a: Song, b: Song, strategyId: String, rating: Rating): DjResult = command {
        val aAnalysis = analyses.analysisOf(a, urgent = true)
        val bAnalysis = analyses.analysisOf(b, urgent = true)
        val features = pairAnalyzer.features(aAnalysis, bAnalysis, controller.effectivePrefs())
        val factor = core.record(strategyId, features, rating)
        republish()
        DjResult.ok("Rated — ${factor.describe()}")
    }

    override suspend fun resetLearned(strategyId: String?): DjResult = command {
        core.resetLearned(strategyId)
        republish()
        DjResult.ok(if (strategyId == null) "Every rating forgotten" else "Ratings for this technique forgotten")
    }

    // ================================================================================================ upcoming transition

    override suspend fun upcoming(): UpcomingChoice? = withContext(Dispatchers.Default) {
        val player = controller.state.value
        val a = player.current ?: return@withContext null
        val b = player.next ?: return@withContext null
        val aRef = QueueManager.trackRef(a, analyses.analysisOf(a, urgent = true))
        val bRef = QueueManager.trackRef(b, analyses.analysisOf(b, urgent = true))
        val explained = core.planner.planExplained(aRef, bRef, controller.effectivePrefs(), 0L, null)
        val edge = player.edges[player.currentIndex]
        val plannedId = when (edge) {
            is EdgeState.Planned -> edge.strategyId
            is EdgeState.Ready -> edge.strategyId
            is EdgeState.Rendering -> edge.strategyId
            else -> null
        }
        val chosen = core.sessionPins.override?.takeIf { it.aSongId == a.id && it.bSongId == b.id }?.pin?.strategyId
        val options = explained.ranked.candidates.mapIndexed { i, c ->
            val breakdown = explained.explanation.ranked.getOrNull(i)
            TechniqueOption(
                strategyId = c.strategy.id,
                displayName = c.strategy.displayName,
                score = breakdown?.score ?: c.score,
                reasons = c.applicability.reasons,
                isRecipe = DjCustomization.recipeIdOf(c.strategy.id) != null,
                planned = c.strategy.id == plannedId,
                chosen = c.strategy.id == chosen,
            )
        }
        val names = core.registry.current.strategies.associate { it.id to it.displayName }
        val unavailable = explained.explanation.skipped.map { s ->
            val why = s.reason + if (s.blockers.isNotEmpty()) ": " + s.blockers.joinToString("; ") else ""
            UnavailableTechnique(s.strategyId, names[s.strategyId] ?: s.strategyId, why)
        }
        UpcomingChoice(
            a = a,
            b = b,
            options = options,
            unavailable = unavailable,
            gatedReason = (edge as? EdgeState.Gated)?.reason,
            inTransition = player.inTransition,
            notes = listOfNotNull(explained.explanation.pin?.reason) + explained.explanation.notes,
        )
    }

    override suspend fun overrideNext(strategyId: String?): DjResult = command {
        val player = controller.state.value
        val a = player.current ?: return@command DjResult.fail("Nothing is playing")
        val b = player.next ?: return@command DjResult.fail("There is no next song")
        if (player.inTransition) return@command DjResult.fail("The transition is already playing")
        if (strategyId == null) {
            core.sessionPins.clear()
        } else {
            val name = core.registry.strategy(strategyId)?.displayName ?: return@command DjResult.fail("Unknown technique '$strategyId'")
            val aAnalysis = analyses.analysisOf(a, urgent = true)
            val bAnalysis = analyses.analysisOf(b, urgent = true)
            core.sessionPins.set(
                SessionOverride(
                    aSongId = a.id,
                    bSongId = b.id,
                    aKeys = setOf(aAnalysis.identity, QueueManager.PLACEHOLDER_PREFIX + a.id),
                    bKeys = setOf(bAnalysis.identity, QueueManager.PLACEHOLDER_PREFIX + b.id),
                    pin = PairPin(aAnalysis.identity, bAnalysis.identity, strategyId, note = "your pick for this transition"),
                ),
            )
            _state.update { it.copy(nextOverride = NextOverride(a.id, b.id, strategyId, name)) }
        }
        if (strategyId == null) _state.update { it.copy(nextOverride = null) }
        if (!controller.replanNext()) {
            core.sessionPins.clear()
            _state.update { it.copy(nextOverride = null) }
            return@command DjResult.fail("The next transition cannot be changed right now")
        }
        DjResult.ok(if (strategyId == null) "Back to the planner's choice" else "Next transition: ${core.registry.strategy(strategyId)?.displayName ?: strategyId}")
    }

    /** Clears the one-off override once its pair is no longer current → next. */
    private fun dropStaleOverride(player: PlayerState) {
        val o = core.sessionPins.override ?: return
        if (player.current?.id == o.aSongId && player.next?.id == o.bSongId) return
        core.sessionPins.clear()
        _state.update { it.copy(nextOverride = null) }
    }

    // ================================================================================================ helpers

    /** Runs [block] off the main thread; an exception becomes a failed [DjResult] with its message. */
    private suspend fun command(block: suspend () -> DjResult): DjResult = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DjResult.fail("Could not save: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            DjResult.fail("Not allowed: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            DjResult.fail(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /** Largest recipe file accepted by [importRecipe]. The shipped recipes are 2–5 KB. */
        const val MAX_IMPORT_BYTES = 512 * 1024
    }
}
