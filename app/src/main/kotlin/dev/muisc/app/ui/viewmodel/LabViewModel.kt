package dev.muisc.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.LibraryRepository
import dev.muisc.app.data.db.Song
import dev.muisc.app.di.AppGraph
import dev.muisc.app.playback.CustomizationApi
import dev.muisc.app.playback.DjResult
import dev.muisc.app.playback.DjState
import dev.muisc.app.playback.LabProgress
import dev.muisc.app.playback.PresetInfo
import dev.muisc.app.playback.TransitionLabApi
import dev.muisc.transitions.Params
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.Rating
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LabUiState(
    val a: Song? = null,
    val b: Song? = null,
    val analysisA: TrackAnalysis? = null,
    val analysisB: TrackAnalysis? = null,
    val analysing: Boolean = false,
    val analysisProgress: LabProgress? = null,
    val ranked: RankedPlans? = null,
    val planning: Boolean = false,
    val selectedIndex: Int = 0,
    /** Edited parameters for the selected candidate (starts from the plan's params). */
    val params: Params = Params.EMPTY,
    val rendering: Boolean = false,
    val renderProgress: LabProgress? = null,
    val rendered: RenderedTransition? = null,
    val auditioning: Boolean = false,
    val auditionPosition: Float = 0f,
    val pinned: Boolean = false,
    val rated: Boolean? = null,
    /** One-shot message for a snackbar; clear with [LabViewModel.consumeMessage]. */
    val message: String? = null,
    /** The blind A/B comparison in progress, or null. */
    val ab: AbTest? = null,
) {
    val selected: PlanCandidate? get() = ranked?.candidates?.getOrNull(selectedIndex)
    val ready: Boolean get() = a != null && b != null && a.id != b.id
    val busy: Boolean get() = analysing || planning || rendering
}

/**
 * A blind comparison of two candidates for the pair: both are rendered, played under the neutral labels X and Y
 * (which one is X is decided by a coin toss), and the vote becomes a rating — up for the winner, down for the
 * other — recorded in the learned preferences. The names are revealed only after the vote.
 */
data class AbTest(
    val first: PlanCandidate,
    val second: PlanCandidate,
    /** True when X is [first]. */
    val xIsFirst: Boolean,
    val renderX: RenderedTransition? = null,
    val renderY: RenderedTransition? = null,
    val rendering: Boolean = true,
    val progress: LabProgress? = null,
    /** 'X' or 'Y' while one is playing. */
    val playing: Char? = null,
    val position: Float = 0f,
    val heardX: Boolean = false,
    val heardY: Boolean = false,
    /** Set once the vote is in: what X and Y were, and what was recorded. */
    val reveal: String? = null,
    val error: String? = null,
) {
    val x: PlanCandidate get() = if (xIsFirst) first else second
    val y: PlanCandidate get() = if (xIsFirst) second else first
    val ready: Boolean get() = renderX != null && renderY != null
    val canVote: Boolean get() = ready && heardX && heardY && reveal == null
}

class LabViewModel(private val repo: LibraryRepository) : ViewModel() {

    private val lab: TransitionLabApi get() = AppGraph.lab
    private val customization: CustomizationApi get() = AppGraph.customization

    /** Presets (and the rest of the DJ state) for the preset menu. */
    val djState: StateFlow<DjState> get() = customization.state
    private val prefs: TransitionPrefs get() = AppGraph.engineController.state.value.transitionPrefs

    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state.asStateFlow()

    /** Songs offered by the picker dialog. */
    val allSongs: StateFlow<List<Song>> = repo.songs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var analysisJob: Job? = null
    private var planJob: Job? = null
    private var renderJob: Job? = null
    private var auditionJob: Job? = null
    private var abJob: Job? = null

    /** Resolves the pair from navigation arguments (-1 = unset) or falls back to current + next. */
    fun loadPair(aId: Long, bId: Long) {
        viewModelScope.launch {
            val current = AppGraph.engineController.state.value
            var a: Song? = current.current
            var b: Song? = current.next
            if (aId >= 0) a = repo.songById(aId) ?: a
            if (bId >= 0) b = repo.songById(bId) ?: b
            setPair(a, b)
        }
    }

    fun useCurrentAndNext() {
        val s = AppGraph.engineController.state.value
        setPair(s.current, s.next)
    }

    fun setA(song: Song) = setPair(song, _state.value.b)
    fun setB(song: Song) = setPair(_state.value.a, song)

    fun swap() = setPair(_state.value.b, _state.value.a)

    private fun setPair(a: Song?, b: Song?) {
        stopAudition()
        abJob?.cancel()
        renderJob?.cancel()
        planJob?.cancel()
        analysisJob?.cancel()
        _state.value = LabUiState(a = a, b = b)
        if (a != null && b != null && a.id != b.id) analyseAndPlan()
    }

    private fun analyseAndPlan() {
        val a = _state.value.a ?: return
        val b = _state.value.b ?: return
        analysisJob = viewModelScope.launch {
            _state.update { it.copy(analysing = true) }
            try {
                val progress: (LabProgress) -> Unit = { p -> _state.update { it.copy(analysisProgress = p) } }
                val (analysisA, analysisB) = withContext(Dispatchers.Default) {
                    lab.analysis(a, progress) to lab.analysis(b, progress)
                }
                _state.update { it.copy(analysisA = analysisA, analysisB = analysisB, analysing = false, analysisProgress = null) }
                plan()
            } catch (e: Exception) {
                _state.update { it.copy(analysing = false, analysisProgress = null, message = e.message ?: "Analysis failed") }
            }
        }
    }

    fun plan() {
        val a = _state.value.a ?: return
        val b = _state.value.b ?: return
        planJob?.cancel()
        planJob = viewModelScope.launch {
            _state.update { it.copy(planning = true) }
            try {
                val ranked = withContext(Dispatchers.Default) { lab.plan(a, b, prefs) }
                _state.update {
                    it.copy(
                        ranked = ranked,
                        planning = false,
                        selectedIndex = 0,
                        params = ranked.best.plan.params,
                        rendered = null,
                        rated = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(planning = false, message = e.message ?: "Planning failed") }
            }
        }
    }

    fun selectCandidate(index: Int) {
        val ranked = _state.value.ranked ?: return
        val candidate = ranked.candidates.getOrNull(index) ?: return
        stopAudition()
        _state.update { it.copy(selectedIndex = index, params = candidate.plan.params, rendered = null, rated = null) }
    }

    fun setParam(id: String, value: String) {
        _state.update { it.copy(params = it.params.with(id, value)) }
    }

    fun resetParams() {
        val candidate = _state.value.selected ?: return
        _state.update { it.copy(params = Params.defaults(candidate.strategy.params)) }
    }

    fun render() {
        val s = _state.value
        val a = s.a ?: return
        val b = s.b ?: return
        val candidate = s.selected ?: return
        stopAudition()
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            _state.update { it.copy(rendering = true, renderProgress = LabProgress("Starting", 0f), rendered = null) }
            try {
                val rendered = withContext(Dispatchers.Default) {
                    lab.render(
                        a = a,
                        b = b,
                        strategyId = candidate.strategy.id,
                        params = s.params,
                        modifiers = candidate.modifiers.map { it.id },
                        prefs = prefs,
                        progress = { p -> _state.update { it.copy(renderProgress = p) } },
                    )
                }
                _state.update { it.copy(rendering = false, renderProgress = null, rendered = rendered) }
            } catch (e: Exception) {
                _state.update { it.copy(rendering = false, renderProgress = null, message = e.message ?: "Render failed") }
            }
        }
    }

    fun audition() {
        val s = _state.value
        val a = s.a ?: return
        val b = s.b ?: return
        val rendered = s.rendered ?: return
        auditionJob?.cancel()
        auditionJob = viewModelScope.launch {
            _state.update { it.copy(auditioning = true, auditionPosition = 0f) }
            try {
                lab.audition(a, b, rendered).collect { pos ->
                    _state.update { it.copy(auditionPosition = pos) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Audition failed") }
            } finally {
                _state.update { it.copy(auditioning = false) }
            }
        }
    }

    fun stopAudition() {
        if (auditionJob != null) {
            auditionJob?.cancel()
            auditionJob = null
            runCatching { lab.stopAudition() }
            _state.update { it.copy(auditioning = false, auditionPosition = 0f) }
        }
    }

    /**
     * Thumbs up/down: the Lab's weight nudge (as before) and, in addition, a rating in the learned preferences for
     * this pair's context, so the planner learns when this technique suits the user.
     */
    fun rate(thumbsUp: Boolean) {
        val s = _state.value
        val a = s.a ?: return
        val b = s.b ?: return
        val candidate = s.selected ?: return
        viewModelScope.launch {
            try {
                lab.rate(a, b, candidate.strategy.id, thumbsUp)
                val learned = customization.rate(a, b, candidate.strategy.id, if (thumbsUp) Rating.Up else Rating.Down)
                val base = if (thumbsUp) "Thanks — weight nudged up" else "Noted — weight nudged down"
                _state.update { it.copy(rated = thumbsUp, message = if (learned.ok) "$base · ${learned.message}" else base) }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Could not save rating") }
            }
        }
    }

    // ---- presets

    /** Loads [preset]'s values into the editor: the values it lists, defaults for everything else. */
    fun loadPreset(preset: PresetInfo) {
        val candidate = _state.value.selected ?: return
        if (preset.strategyId != candidate.strategy.id) return
        stopAudition()
        _state.update {
            it.copy(
                params = Params.defaults(candidate.strategy.params).withAll(preset.params.values),
                rendered = null,
                rated = null,
                message = "Loaded preset '${preset.name}'",
            )
        }
    }

    /** Saves the edited values of the selected technique as a new preset called [name]. */
    fun savePreset(name: String) {
        val s = _state.value
        val candidate = s.selected ?: return
        viewModelScope.launch {
            val result = try {
                customization.savePreset(name, candidate.strategy.id, s.params)
            } catch (e: Exception) {
                DjResult.fail(e.message ?: "Could not save the preset")
            }
            _state.update { it.copy(message = result.message) }
        }
    }

    // ---- blind A/B test

    /** Renders the selected candidate and the best other one for a blind comparison. */
    fun startAb() {
        val s = _state.value
        val a = s.a ?: return
        val b = s.b ?: return
        val ranked = s.ranked ?: return
        if (ranked.candidates.size < 2) return
        val first = s.selected ?: ranked.best
        val second = ranked.candidates.firstOrNull { it.strategy.id != first.strategy.id } ?: return
        stopAudition()
        abJob?.cancel()
        val test = AbTest(first, second, xIsFirst = Random.nextBoolean())
        _state.update { it.copy(ab = test) }
        abJob = viewModelScope.launch {
            try {
                val progress: (LabProgress) -> Unit = { p -> _state.update { st -> st.copy(ab = st.ab?.copy(progress = p)) } }
                val renderX = withContext(Dispatchers.Default) { renderCandidate(a, b, test.x, progress) }
                _state.update { st -> st.copy(ab = st.ab?.copy(renderX = renderX)) }
                val renderY = withContext(Dispatchers.Default) { renderCandidate(a, b, test.y, progress) }
                _state.update { st -> st.copy(ab = st.ab?.copy(renderY = renderY, rendering = false, progress = null)) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { st -> st.copy(ab = st.ab?.copy(rendering = false, progress = null, error = e.message ?: "Render failed")) }
            }
        }
    }

    private suspend fun renderCandidate(a: Song, b: Song, c: PlanCandidate, progress: (LabProgress) -> Unit): RenderedTransition =
        lab.render(a = a, b = b, strategyId = c.strategy.id, params = c.plan.params, modifiers = c.modifiers.map { it.id }, prefs = prefs, progress = progress)

    /** Plays X or Y ([slot]) through the engine, like Audition. */
    fun playAb(slot: Char) {
        val s = _state.value
        val a = s.a ?: return
        val b = s.b ?: return
        val test = s.ab ?: return
        val rendered = (if (slot == 'X') test.renderX else test.renderY) ?: return
        stopAudition()
        auditionJob = viewModelScope.launch {
            _state.update { st ->
                st.copy(ab = st.ab?.let { t -> t.copy(playing = slot, position = 0f, heardX = t.heardX || slot == 'X', heardY = t.heardY || slot == 'Y') })
            }
            try {
                lab.audition(a, b, rendered).collect { pos -> _state.update { st -> st.copy(ab = st.ab?.copy(position = pos)) } }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(message = e.message ?: "Audition failed") }
            } finally {
                _state.update { st -> st.copy(ab = st.ab?.copy(playing = null)) }
            }
        }
    }

    /**
     * The vote: [winner] 'X' or 'Y' records up for it and down for the other; null ("no preference") records
     * nothing. Either way the names are revealed.
     */
    fun voteAb(winner: Char?) {
        val s = _state.value
        val a = s.a ?: return
        val b = s.b ?: return
        val test = s.ab ?: return
        if (!test.canVote) return
        stopAudition()
        viewModelScope.launch {
            val names = "X was ${test.x.strategy.displayName}, Y was ${test.y.strategy.displayName}."
            val recorded = if (winner == null) {
                "No preference — nothing recorded."
            } else {
                val (win, lose) = if (winner == 'X') test.x to test.y else test.y to test.x
                val up = customization.rate(a, b, win.strategy.id, Rating.Up)
                val down = customization.rate(a, b, lose.strategy.id, Rating.Down)
                if (up.ok && down.ok) "Recorded: ${win.strategy.displayName} up, ${lose.strategy.displayName} down."
                else "Could not record the vote: " + listOf(up, down).first { !it.ok }.message
            }
            _state.update { st -> st.copy(ab = st.ab?.copy(reveal = "$names $recorded")) }
        }
    }

    fun closeAb() {
        abJob?.cancel()
        stopAudition()
        _state.update { it.copy(ab = null) }
    }

    fun togglePin() {
        val s = _state.value
        val a = s.a ?: return
        val b = s.b ?: return
        val candidate = s.selected ?: return
        viewModelScope.launch {
            try {
                if (s.pinned) {
                    lab.pinForPair(a, b, null, null)
                    _state.update { it.copy(pinned = false, message = "Pin removed") }
                } else {
                    lab.pinForPair(a, b, candidate.strategy.id, s.params)
                    _state.update { it.copy(pinned = true, message = "Pinned ${candidate.strategy.displayName} for this pair") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Could not pin") }
            }
        }
    }

    fun export() {
        val s = _state.value
        val a = s.a ?: return
        val b = s.b ?: return
        val rendered = s.rendered ?: return
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) { lab.export(a, b, rendered) }
                _state.update { it.copy(message = "Exported to $path") }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Export failed") }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        abJob?.cancel()
        stopAudition()
        super.onCleared()
    }
}
