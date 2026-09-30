package dev.muisc.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.muisc.app.di.AppGraph
import dev.muisc.app.playback.CustomizationApi
import dev.muisc.app.playback.DjResult
import dev.muisc.app.playback.DjState
import dev.muisc.app.playback.ImportOutcome
import dev.muisc.app.playback.UpcomingChoice
import dev.muisc.transitions.recipe.RecipeProblem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A recipe import that needs the user's decision, or its final report. */
sealed interface ImportDialog {
    /** Waiting for the file to be read and checked. */
    data object Working : ImportDialog
    /** It has errors: keep it anyway (never used until fixed)? */
    data class ConfirmErrors(val uri: String, val name: String, val problems: List<RecipeProblem>) : ImportDialog
    /** It would replace one of the user's recipes: replace? */
    data class ConfirmReplace(val uri: String, val allowErrors: Boolean, val existingName: String, val problems: List<RecipeProblem>) : ImportDialog
    /** Final outcome to show (success with warnings, or why it failed). */
    data class Report(val ok: Boolean, val title: String, val problems: List<RecipeProblem>) : ImportDialog
}

/** State of the "Next transition" bottom sheet. */
data class UpcomingSheetState(
    val loading: Boolean = false,
    val choice: UpcomingChoice? = null,
    val error: String? = null,
    /** The strategy being applied right now (its row shows progress). */
    val applying: String? = null,
)

/** The DJ settings screens and the Now Playing sheet: everything goes through [AppGraph.customization]. */
class DjViewModel : ViewModel() {

    private val api: CustomizationApi get() = AppGraph.customization

    val state: StateFlow<DjState> = api.state

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot snackbar text; clear with [consumeMessage]. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importDialog = MutableStateFlow<ImportDialog?>(null)
    val importDialog: StateFlow<ImportDialog?> = _importDialog.asStateFlow()

    private val _upcoming = MutableStateFlow(UpcomingSheetState())
    val upcoming: StateFlow<UpcomingSheetState> = _upcoming.asStateFlow()

    private var upcomingJob: Job? = null

    fun consumeMessage() { _message.value = null }

    private fun run(block: suspend () -> DjResult) {
        viewModelScope.launch {
            val result = try {
                block()
            } catch (e: Exception) {
                DjResult.fail(e.message ?: e.javaClass.simpleName)
            }
            _message.value = result.message
        }
    }

    fun refresh() { viewModelScope.launch { api.refresh() } }

    // ---- styles
    fun chooseStyle(id: String?) = run { api.chooseStyle(id) }

    // ---- recipes
    /** Starts importing the document the picker returned; null = the user cancelled the picker (nothing happens). */
    fun importRecipe(uri: String?, allowErrors: Boolean = false, replace: Boolean = false) {
        if (uri == null) return
        _importDialog.value = ImportDialog.Working
        viewModelScope.launch {
            val outcome = try {
                api.importRecipe(uri, allowErrors, replace)
            } catch (e: Exception) {
                ImportOutcome.Failed(e.message ?: e.javaClass.simpleName, emptyList())
            }
            _importDialog.value = when (outcome) {
                is ImportOutcome.Imported -> ImportDialog.Report(true, "Imported \"${outcome.name}\"", outcome.warnings)
                is ImportOutcome.Unreadable -> ImportDialog.Report(false, outcome.message, outcome.problems)
                is ImportOutcome.HasErrors -> ImportDialog.ConfirmErrors(uri, outcome.name, outcome.problems)
                is ImportOutcome.Conflict -> ImportDialog.ConfirmReplace(uri, allowErrors, outcome.existingName, outcome.problems)
                is ImportOutcome.Failed -> ImportDialog.Report(false, outcome.message, outcome.problems)
            }
        }
    }

    fun dismissImport() { _importDialog.value = null }

    fun exportRecipe(id: String, uri: String?) {
        if (uri == null) return
        run { api.exportRecipe(id, uri) }
    }

    /** The recipe's file text for the share sheet, or null (a message says why). */
    suspend fun recipeText(id: String): String? {
        val text = try {
            api.recipeText(id)
        } catch (e: Exception) {
            null
        }
        if (text == null) _message.value = "Could not read recipe '$id'"
        return text
    }

    fun duplicateRecipe(id: String) = run { api.duplicateRecipe(id) }
    fun deleteRecipe(id: String) = run { api.deleteRecipe(id) }
    fun setRecipeEnabled(id: String, enabled: Boolean) = run { api.setRecipeEnabled(id, enabled) }

    // ---- presets
    fun deletePreset(id: String) = run { api.deletePreset(id) }
    fun setActivePreset(strategyId: String, presetId: String?) = run { api.setActivePreset(strategyId, presetId) }

    // ---- pins
    fun removePin(aIdentity: String, bIdentity: String) = run { api.removePin(aIdentity, bIdentity) }

    // ---- learned
    fun resetLearned(strategyId: String?) = run { api.resetLearned(strategyId) }

    // ---- the upcoming transition
    fun loadUpcoming() {
        upcomingJob?.cancel()
        _upcoming.value = UpcomingSheetState(loading = true)
        upcomingJob = viewModelScope.launch {
            _upcoming.value = try {
                val choice = api.upcoming()
                if (choice == null) UpcomingSheetState(error = "Nothing is queued after this song")
                else UpcomingSheetState(choice = choice)
            } catch (e: Exception) {
                UpcomingSheetState(error = e.message ?: "Could not plan the next transition")
            }
        }
    }

    fun chooseUpcoming(strategyId: String?) {
        _upcoming.update { it.copy(applying = strategyId ?: "") }
        viewModelScope.launch {
            val result = try {
                api.overrideNext(strategyId)
            } catch (e: Exception) {
                DjResult.fail(e.message ?: e.javaClass.simpleName)
            }
            _message.value = result.message
            _upcoming.update { it.copy(applying = null) }
            if (result.ok) loadUpcoming()
        }
    }
}
