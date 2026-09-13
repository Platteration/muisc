package dev.muisc.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.LibraryRepository
import dev.muisc.app.data.db.Song
import dev.muisc.app.di.AppGraph
import dev.muisc.app.playback.LabProgress
import dev.muisc.app.playback.TransitionLabApi
import dev.muisc.transitions.Params
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionPrefs
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
) {
    val selected: PlanCandidate? get() = ranked?.candidates?.getOrNull(selectedIndex)
    val ready: Boolean get() = a != null && b != null && a.id != b.id
    val busy: Boolean get() = analysing || planning || rendering
}

class LabViewModel(private val repo: LibraryRepository) : ViewModel() {

    private val lab: TransitionLabApi get() = AppGraph.lab
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

    fun rate(thumbsUp: Boolean) {
        val s = _state.value
        val a = s.a ?: return
        val b = s.b ?: return
        val candidate = s.selected ?: return
        viewModelScope.launch {
            try {
                lab.rate(a, b, candidate.strategy.id, thumbsUp)
                _state.update { it.copy(rated = thumbsUp, message = if (thumbsUp) "Thanks — weight nudged up" else "Noted — weight nudged down") }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Could not save rating") }
            }
        }
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
        stopAudition()
        super.onCleared()
    }
}
