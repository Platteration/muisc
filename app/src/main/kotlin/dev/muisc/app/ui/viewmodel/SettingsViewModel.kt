package dev.muisc.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.muisc.app.data.prefs.SettingsRepository
import dev.muisc.app.data.prefs.UiPrefs
import dev.muisc.app.di.AppGraph
import dev.muisc.transitions.TransitionPrefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The 14 frozen strategy ids (docs/DESIGN.md §12) with display names, used when no registry is reachable from the UI. */
val STRATEGY_IDS: List<String> = listOf(
    "crossfade", "outroIntroMinimal", "phraseCut", "beatMatchedBlend", "bassSwap", "stemSwap", "drumBreakBridge",
    "filterSweep", "echoOut", "loopRollRiser", "harmonicBlend", "spectralFreezeBridge", "ambientBridge", "brakeStop",
)

fun strategyDisplayName(id: String): String = when (id) {
    "crossfade" -> "Crossfade"
    "outroIntroMinimal" -> "Outro / intro"
    "phraseCut" -> "Phrase cut"
    "beatMatchedBlend" -> "Beat-matched blend"
    "bassSwap" -> "Bass swap"
    "stemSwap" -> "Stem swap"
    "drumBreakBridge" -> "Drum-break bridge"
    "filterSweep" -> "Filter sweep"
    "echoOut" -> "Echo out"
    "loopRollRiser" -> "Loop roll + riser"
    "harmonicBlend" -> "Harmonic blend"
    "spectralFreezeBridge" -> "Spectral freeze bridge"
    "ambientBridge" -> "Ambient bridge"
    "brakeStop" -> "Brake stop"
    else -> id
}

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    /** Null until DataStore has emitted the first value. */
    val uiPrefs: StateFlow<UiPrefs?> = settings.uiPrefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transitionPrefs: StateFlow<TransitionPrefs> = settings.transitionPrefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransitionPrefs())

    val strategyIds: List<String> get() = STRATEGY_IDS

    fun updateUi(transform: (UiPrefs) -> UiPrefs) {
        viewModelScope.launch { settings.updateUi(transform) }
    }

    fun updateTransition(transform: (TransitionPrefs) -> TransitionPrefs) {
        viewModelScope.launch {
            val next = transform(settings.transitionPrefs.first())
            settings.update(next)
            AppGraph.engineController.updateTransitionPrefs(next)
        }
    }

    fun setStrategyWeight(id: String, weight: Double) = updateTransition { p ->
        p.copy(strategyWeights = p.strategyWeights + (id to weight))
    }

    fun setStrategyEnabled(id: String, enabled: Boolean) = updateTransition { p ->
        p.copy(disabledStrategies = if (enabled) p.disabledStrategies - id else p.disabledStrategies + id)
    }

    fun rescan(full: Boolean = true) = AppGraph.requestScan(full)
}
