package dev.muisc.app.di

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.Song
import dev.muisc.app.playback.CustomizationApi
import dev.muisc.app.playback.DjResult
import dev.muisc.app.playback.DjState
import dev.muisc.app.playback.EngineController
import dev.muisc.app.playback.ImportOutcome
import dev.muisc.app.playback.LabProgress
import dev.muisc.app.playback.NoOpCustomization
import dev.muisc.app.playback.NoOpEngineController
import dev.muisc.app.playback.NoOpTransitionLab
import dev.muisc.app.playback.PlayerState
import dev.muisc.app.playback.RepeatMode
import dev.muisc.app.playback.TransitionLabApi
import dev.muisc.app.playback.UpcomingChoice
import dev.muisc.transitions.Params
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.Rating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one [EngineController] instance the UI ever holds. It forwards to whichever real controller the playback
 * service has installed (`AppGraph.installPlayback`) and, until then, asks for the service to be started and
 * buffers commands (bounded) so "tap a song before the service is up" still starts playback once it binds.
 * [state] follows the currently installed controller, so collectors never need to re-subscribe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DelegatingEngineController(
    scope: CoroutineScope,
    /** Called (at most once per un-installed window) when a command arrives and no real controller is installed. */
    private val requestService: () -> Unit,
) : EngineController {

    private val fallback = NoOpEngineController()
    private val real = MutableStateFlow<EngineController?>(null)
    private val pending = ArrayDeque<(EngineController) -> Unit>()
    private var serviceRequested = false

    /** True once a real controller is installed. */
    val installed: StateFlow<Boolean> = real.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    override val state: StateFlow<PlayerState> =
        real.flatMapLatest { (it ?: fallback).state }.stateIn(scope, SharingStarted.Eagerly, PlayerState())

    /** Installs [controller] and replays buffered commands in order. Safe to call again with a new instance. */
    fun install(controller: EngineController) {
        val replay: List<(EngineController) -> Unit> = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            serviceRequested = false
            copy
        }
        real.value = controller
        replay.forEach { it(controller) }
    }

    /** Detaches the real controller (service destroyed); [state] falls back to idle. Does not release it. */
    fun uninstall(controller: EngineController? = null) {
        if (controller == null || real.value === controller) real.value = null
    }

    private fun dispatch(command: (EngineController) -> Unit) {
        val target = real.value
        if (target != null) {
            command(target)
            return
        }
        var request = false
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(command)
            if (!serviceRequested) { serviceRequested = true; request = true }
        }
        if (request) requestService()
    }

    override fun play() = dispatch { it.play() }
    override fun pause() = dispatch { it.pause() }
    override fun togglePlayPause() = dispatch { it.togglePlayPause() }
    override fun next() = dispatch { it.next() }
    override fun previous() = dispatch { it.previous() }
    override fun seekTo(positionMs: Long) = dispatch { it.seekTo(positionMs) }
    override fun setQueue(songs: List<Song>, startIndex: Int, context: PlaybackContext, playNow: Boolean) =
        dispatch { it.setQueue(songs, startIndex, context, playNow) }
    override fun playNext(songs: List<Song>) = dispatch { it.playNext(songs) }
    override fun addToQueue(songs: List<Song>) = dispatch { it.addToQueue(songs) }
    override fun moveQueueItem(from: Int, to: Int) = dispatch { it.moveQueueItem(from, to) }
    override fun removeQueueItem(index: Int) = dispatch { it.removeQueueItem(index) }
    override fun skipToQueueItem(index: Int) = dispatch { it.skipToQueueItem(index) }
    override fun clearQueue() = dispatch { it.clearQueue() }
    override fun setShuffle(enabled: Boolean) = dispatch { it.setShuffle(enabled) }
    override fun setRepeat(mode: RepeatMode) = dispatch { it.setRepeat(mode) }
    override fun updateTransitionPrefs(prefs: TransitionPrefs) = dispatch { it.updateTransitionPrefs(prefs) }

    /** Releasing is the service's job; the delegating instance lives as long as the process. */
    override fun release() { real.value?.release(); real.value = null }

    private companion object { const val MAX_PENDING = 32 }
}

/**
 * The one [TransitionLabApi] the UI holds. Suspending calls wait (up to [connectTimeoutMs]) for the playback
 * service to install the real lab after asking for it to start; if none arrives they fall through to
 * [NoOpTransitionLab], whose error message the Lab screen displays.
 */
class DelegatingTransitionLab(
    private val requestService: () -> Unit,
    private val connectTimeoutMs: Long = 10_000L,
) : TransitionLabApi {

    private val real = MutableStateFlow<TransitionLabApi?>(null)

    fun install(lab: TransitionLabApi) { real.value = lab }

    fun uninstall(lab: TransitionLabApi? = null) {
        if (lab == null || real.value === lab) real.value = null
    }

    private suspend fun lab(): TransitionLabApi {
        real.value?.let { return it }
        requestService()
        return withTimeoutOrNull(connectTimeoutMs) { real.filterNotNull().first() } ?: NoOpTransitionLab
    }

    override suspend fun analysis(song: Song, progress: ((LabProgress) -> Unit)?): TrackAnalysis =
        lab().analysis(song, progress)

    override suspend fun plan(a: Song, b: Song, prefs: TransitionPrefs, seed: Long): RankedPlans =
        lab().plan(a, b, prefs, seed)

    override suspend fun render(
        a: Song, b: Song, strategyId: String, params: Params, modifiers: List<String>, prefs: TransitionPrefs,
        seed: Long, progress: ((LabProgress) -> Unit)?,
    ): RenderedTransition = lab().render(a, b, strategyId, params, modifiers, prefs, seed, progress)

    override fun audition(a: Song, b: Song, rendered: RenderedTransition, contextSec: Double): Flow<Float> =
        (real.value ?: NoOpTransitionLab).audition(a, b, rendered, contextSec)

    override fun stopAudition() { real.value?.stopAudition() }

    override suspend fun export(a: Song, b: Song, rendered: RenderedTransition): String = lab().export(a, b, rendered)

    override suspend fun rate(a: Song, b: Song, strategyId: String, thumbsUp: Boolean) = lab().rate(a, b, strategyId, thumbsUp)

    override suspend fun pinForPair(a: Song, b: Song, strategyId: String?, params: Params?) =
        lab().pinForPair(a, b, strategyId, params)
}

/**
 * The one [CustomizationApi] the UI holds, installed like the Lab: [state] follows the installed implementation
 * ([NoOpCustomization]'s "connecting" state until then, and asking for the service to start as soon as a screen
 * subscribes); suspending calls wait up to [connectTimeoutMs] for the service before falling through to
 * [NoOpCustomization], whose results carry a "not connected" message the screens show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DelegatingCustomization(
    scope: CoroutineScope,
    private val requestService: () -> Unit,
    private val connectTimeoutMs: Long = 10_000L,
) : CustomizationApi {

    private val real = MutableStateFlow<CustomizationApi?>(null)

    fun install(api: CustomizationApi) { real.value = api }

    fun uninstall(api: CustomizationApi? = null) {
        if (api == null || real.value === api) real.value = null
    }

    override val state: StateFlow<DjState> = real
        .flatMapLatest { (it ?: NoOpCustomization).state }
        .onStart { if (real.value == null) requestService() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), DjState(loading = true))

    private suspend fun api(): CustomizationApi {
        real.value?.let { return it }
        requestService()
        return withTimeoutOrNull(connectTimeoutMs) { real.filterNotNull().first() } ?: NoOpCustomization
    }

    override suspend fun refresh() = api().refresh()
    override suspend fun chooseStyle(id: String?): DjResult = api().chooseStyle(id)
    override suspend fun importRecipe(uri: String, allowErrors: Boolean, replace: Boolean): ImportOutcome =
        api().importRecipe(uri, allowErrors, replace)
    override suspend fun exportRecipe(id: String, uri: String): DjResult = api().exportRecipe(id, uri)
    override suspend fun recipeText(id: String): String? = api().recipeText(id)
    override suspend fun duplicateRecipe(id: String): DjResult = api().duplicateRecipe(id)
    override suspend fun deleteRecipe(id: String): DjResult = api().deleteRecipe(id)
    override suspend fun setRecipeEnabled(id: String, enabled: Boolean): DjResult = api().setRecipeEnabled(id, enabled)
    override suspend fun savePreset(name: String, strategyId: String, params: Params): DjResult = api().savePreset(name, strategyId, params)
    override suspend fun deletePreset(id: String): DjResult = api().deletePreset(id)
    override suspend fun setActivePreset(strategyId: String, presetId: String?): DjResult = api().setActivePreset(strategyId, presetId)
    override suspend fun removePin(aIdentity: String, bIdentity: String): DjResult = api().removePin(aIdentity, bIdentity)
    override suspend fun rate(a: Song, b: Song, strategyId: String, rating: Rating): DjResult = api().rate(a, b, strategyId, rating)
    override suspend fun resetLearned(strategyId: String?): DjResult = api().resetLearned(strategyId)
    override suspend fun upcoming(): UpcomingChoice? = api().upcoming()
    override suspend fun overrideNext(strategyId: String?): DjResult = api().overrideNext(strategyId)
}
