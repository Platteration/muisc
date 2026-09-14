package dev.muisc.app.playback

import android.content.Context
import android.os.Process
import dev.muisc.app.data.db.Song
import dev.muisc.app.data.prefs.SettingsRepository
import dev.muisc.audio.EngineStreamFactory
import dev.muisc.player.AudioSink
import dev.muisc.player.CoordinatorState
import dev.muisc.player.EngineCommand
import dev.muisc.player.EngineLimits
import dev.muisc.player.PlayerEvent
import dev.muisc.player.PowerMode
import dev.muisc.player.ProgramPlayer
import dev.muisc.player.RenderGate
import dev.muisc.player.SinkPump
import dev.muisc.player.SystemClock
import dev.muisc.player.TransitionCoordinator
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.PlaybackProgram
import dev.muisc.transitions.ProgramBuilder
import dev.muisc.transitions.Segment
import dev.muisc.transitions.StrategyRegistry
import dev.muisc.transitions.TrackAudioLoader
import dev.muisc.transitions.TransitionPlanner
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionRenderer
import dev.muisc.transitions.live.LivePlanFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The real [EngineController] (DESIGN §7.2 / §7.4). It owns:
 *
 *  - a [ProgramPlayer] and one [AudioSink] driven by a dedicated **audio thread** at `THREAD_PRIORITY_URGENT_AUDIO`
 *    running the `render → interleave → write` loop (the blocking write is the clock);
 *  - a [TransitionCoordinator] on a **single-thread coroutine dispatcher** at `THREAD_PRIORITY_DEFAULT +
 *    THREAD_PRIORITY_LESS_FAVORABLE` (nice +1: foreground cgroup, below the UI);
 *  - a [QueueManager] (order, shuffle, repeat, persistence) and the audio-focus glue;
 *  - the [PlayerState] the UI collects: position every [POSITION_INTERVAL_MS] while playing, edge badges mapped
 *    from [CoordinatorState], errors, and the transition flag.
 *
 * Every public method returns immediately and is safe to call from the main thread: queue work is handed to a
 * coroutine, transport changes are a flag plus a command on the player's lock-free queue.
 *
 * **Who installs the program.** The coordinator does (`SetProgram` / `ReplaceTail` from its own thread); this class
 * never builds segments itself. It only tells the coordinator what the queue is and when the user skipped or
 * seeked, so there is exactly one writer of the program.
 */
class EngineControllerImpl(
    context: Context,
    val sampleRate: Int,
    val channels: Int,
    private val limits: EngineLimits,
    streams: EngineStreamFactory,
    planner: TransitionPlanner,
    renderer: TransitionRenderer,
    liveFactory: LivePlanFactory,
    programBuilder: ProgramBuilder,
    private val analyses: AndroidAnalysisService,
    gate: RenderGate,
    windows: TrackAudioLoader?,
    private val registry: StrategyRegistry,
    private val settings: SettingsRepository,
    /** Builds the output sink; `AudioTrackSink(sampleRate, channels)` in the app, a fake in tests. */
    private val sinkFactory: (Int, Int) -> AudioSink,
    private val queue: QueueManager = QueueManager(),
    private val persistence: QueuePersistence? = null,
    /** Called on the pump thread when a track starts playing (play history). */
    private val onSongStarted: ((Song, PlaybackContext) -> Unit)? = null,
) : EngineController, DuckableEngine {

    private val appContext: Context = context.applicationContext

    /** The engine's own prefs: whatever the user stored, forced to the real engine format. */
    @Volatile
    private var prefs: TransitionPrefs = normalize(TransitionPrefs())

    private val player = ProgramPlayer(sampleRate, channels, limits, streams, prefs, realtime = true)

    private val coordinatorDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
            r.run()
        }, "muisc-coordinator")
    }.asCoroutineDispatcher()

    private val coordinatorScope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)

    private val coordinator = TransitionCoordinator(
        planner = planner,
        liveFactory = liveFactory,
        renderer = renderer,
        programBuilder = programBuilder,
        player = player,
        analyses = analyses,
        gate = gate,
        limits = limits,
        clock = SystemClock,
        scope = coordinatorScope,
        prefsProvider = { prefs },
        pairAnalyzer = DefaultPairAnalyzer(),
        windows = windows,
        seed = 0L,
    )

    /** Queue bookkeeping, state publishing and persistence (never the audio thread). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PlayerState(transitionPrefs = prefs))
    override val state: StateFlow<PlayerState> get() = _state

    // ---- transport handshake with the audio thread ----
    private val transportLock = ReentrantLock()
    private val transportReady = transportLock.newCondition()

    @Volatile private var wantPlaying = false
    @Volatile private var stopped = false
    private var drainBlocks = 0                       // guarded by transportLock
    private val pauseDrainBlocks = max(2, player.rampFrames / limits.blockFrames + 2)

    @Volatile private var audioThread: Thread? = null
    @Volatile private var sawAudio = false
    @Volatile private var waitingSinceNanos = 0L
    @Volatile private var pendingRestoreMs = 0L
    @Volatile private var lastImmediateSongId: Long? = null
    @Volatile private var released = false

    /** Underruns seen since the last queue install (diagnostics; the sink owns its buffer size). */
    @Volatile
    var underruns: Int = 0
        private set

    private var pumpJob: Job? = null
    private var installJob: Job? = null

    /** Started by [EngineGraph]; stopped from [release] so the receiver does not outlive the service. */
    @Volatile private var powerMonitor: PowerModeMonitor? = null

    /** Last strategy announced per edge, so `Rendering(progress)` can still name it in the badge. */
    private val edgeStrategies = HashMap<Int, String>()

    /** Focus was lost transiently and playback should resume by itself. */
    @Volatile private var resumeOnFocusGain = false

    /**
     * The controller is the single owner of audio focus: it holds the engine state that decides whether a transient
     * loss should arm an auto-resume, and it can duck the master gain directly. [PlaybackService] must not create a
     * second handler.
     */
    private val focus: AudioFocusHandler = AudioFocusHandler(
        appContext,
        onPause = { transient ->
            resumeOnFocusGain = transient && _state.value.isPlaying
            pause()
            if (!transient) abandonFocus()
        },
        onResume = {
            player.setDuckDb(0f)
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                play()
            }
        },
        onDuck = { db -> player.setDuckDb(db) },
    )

    init {
        scope.launch {
            settings.transitionPrefs.collect { stored ->
                val next = normalize(stored)
                val wasEnabled = prefs.enabled
                prefs = next
                _state.update { it.copy(transitionPrefs = next) }
                // A material change only reaches planned edges on the next install; do it now while stopped.
                if (!_state.value.isPlaying && wasEnabled != next.enabled) install(null)
            }
        }
        scope.launch {
            coordinator.state.collect { states -> publishEdges(states) }
        }
        startPump()
    }

    // ================================================================================================ transport

    override fun play() {
        if (released) return
        if (queue.current() == null) return
        // Audio focus is a precondition, not a formality: a phone call or a navigation prompt owning the output
        // means we must not open the sink at all (DESIGN §8). The user sees why nothing started.
        if (!requestFocus()) {
            reportError("Another app is using the audio output")
            return
        }
        focus.playing = true
        _state.update { it.copy(error = null, isPlaying = true) }
        player.submit(EngineCommand.Play)
        transportLock.withLock {
            wantPlaying = true
            drainBlocks = 0
            transportReady.signalAll()
        }
        ensureAudioThread()
        startPump()
        persist()
    }

    override fun pause() {
        if (released) return
        focus.playing = false
        player.submit(EngineCommand.Pause)
        transportLock.withLock {
            // Only a running loop has to write the pause ramp out; a parked one stays parked.
            if (wantPlaying) drainBlocks = pauseDrainBlocks
            wantPlaying = false
            transportReady.signalAll()
        }
        _state.update { it.copy(isPlaying = false) }
        persist()
    }

    override fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    override fun next() {
        val snapshot = queue.snapshot()
        if (snapshot.isEmpty) return
        if (snapshot.repeat == RepeatMode.ONE) {
            seekTo(0)
            return
        }
        if (snapshot.index >= snapshot.songs.size - 1) {
            if (snapshot.repeat == RepeatMode.ALL) {
                queue.skipTo(0)
                install(true, restart = true)
            } else {
                player.submit(EngineCommand.Skip)
            }
            return
        }
        // The coordinator decides: DJ skip into an installed render, or a live move from here.
        coordinator.onUserSkip()
    }

    override fun previous() {
        val snapshot = queue.snapshot()
        if (snapshot.isEmpty) return
        if (_state.value.positionMs > RESTART_THRESHOLD_MS || snapshot.index <= 0) {
            seekTo(0)
            return
        }
        queue.skipTo(snapshot.index - 1)
        install(true, restart = true)
    }

    override fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        val frame = clamped * sampleRate / 1000L
        coordinator.onUserSeek(frame)
        _state.update { it.copy(positionMs = clamped) }
    }

    // ================================================================================================ queue

    override fun setQueue(songs: List<Song>, startIndex: Int, context: PlaybackContext, playNow: Boolean) {
        pendingRestoreMs = 0L
        queue.setQueue(songs, startIndex, context)
        install(playNow, restart = true)
    }

    override fun playNext(songs: List<Song>) {
        val wasEmpty = queue.snapshot().isEmpty
        queue.playNext(songs)
        install(if (wasEmpty) true else null)
    }

    override fun addToQueue(songs: List<Song>) {
        val wasEmpty = queue.snapshot().isEmpty
        queue.addToQueue(songs)
        install(if (wasEmpty) true else null)
    }

    override fun moveQueueItem(from: Int, to: Int) {
        queue.move(from, to)
        install(null)
    }

    override fun removeQueueItem(index: Int) {
        val wasCurrent = queue.snapshot().index == index
        queue.removeAt(index)
        install(if (wasCurrent && _state.value.isPlaying) true else null)
    }

    override fun skipToQueueItem(index: Int) {
        pendingRestoreMs = 0L
        queue.skipTo(index)
        install(true, restart = true)
    }

    override fun clearQueue() {
        queue.clear()
        pendingRestoreMs = 0L
        player.submit(EngineCommand.SetProgram(PlaybackProgram(emptyList())))
        transportLock.withLock {
            wantPlaying = false
            drainBlocks = 0
            transportReady.signalAll()
        }
        coordinator.onQueue(PlaybackContext.SINGLE, emptyList(), 0)
        edgeStrategies.clear()
        _state.value = PlayerState(transitionPrefs = prefs)
        abandonFocus()
        persist()
    }

    override fun setShuffle(enabled: Boolean) {
        queue.setShuffle(enabled)
        install(null)
    }

    override fun setRepeat(mode: RepeatMode) {
        queue.setRepeat(mode)
        // REPEAT_ONE plays a one-track program, so the effective queue changes with the mode.
        install(null)
    }

    override fun updateTransitionPrefs(prefs: TransitionPrefs) {
        val next = normalize(prefs)
        this.prefs = next
        _state.update { it.copy(transitionPrefs = next) }
        if (!_state.value.isPlaying) install(null)
    }

    override fun release() {
        if (released) return
        released = true
        pumpJob?.cancel()
        installJob?.cancel()
        powerMonitor?.stop()
        powerMonitor = null
        coordinator.shutdown()
        stopped = true
        transportLock.withLock {
            wantPlaying = false
            drainBlocks = 0
            transportReady.signalAll()
        }
        val thread = audioThread
        audioThread = null
        if (thread != null) {
            try {
                thread.join(AUDIO_JOIN_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            player.close()
        } catch (t: Throwable) {
            // Closing decks must never take the service down.
        }
        abandonFocus()
        scope.cancel()
        coordinatorScope.cancel()
        coordinatorDispatcher.close()
        analyses.release()
        _state.update { it.copy(isPlaying = false) }
    }

    // ================================================================================================ host hooks

    /** `ComponentCallbacks2.onTrimMemory` from the service. */
    fun onTrimMemory(level: Int) {
        coordinator.onTrimMemory(level)
    }

    fun setPowerMode(mode: PowerMode) {
        coordinator.setPowerMode(mode)
    }

    /** Hands the monitor its owner: [release] unregisters it. */
    fun attachPowerMonitor(monitor: PowerModeMonitor) {
        powerMonitor = monitor
    }

    /** The coordinator's human-readable decision log (Lab / bug reports). */
    fun transitionLog(): List<String> = coordinator.transitionLog

    /**
     * Restores the queue saved by the last session. [resolve] turns song ids into [Song] rows (Room). Nothing is
     * played: the queue is installed paused, and the saved position is applied when playback first starts.
     */
    fun restoreLastQueue(resolve: suspend (List<Long>) -> List<Song>) {
        if (persistence == null) return
        scope.launch {
            if (!queue.snapshot().isEmpty) return@launch
            val saved = try {
                persistence.load()
            } catch (t: Throwable) {
                null
            } ?: return@launch
            val byId = try {
                resolve(saved.orderIds + saved.naturalIds).associateBy { it.id }
            } catch (t: Throwable) {
                return@launch
            }
            val order = saved.orderIds.mapNotNull { byId[it] }
            if (order.isEmpty()) return@launch
            val natural = saved.naturalIds.mapNotNull { byId[it] }
            queue.restore(order, natural, saved.index, saved.context, saved.shuffle, saved.repeat)
            pendingRestoreMs = saved.positionMs
            install(false, restart = true)
        }
    }

    // ================================================================================================ install

    /**
     * Publishes the queue and hands it to the coordinator. [playNow] true starts playback, false pauses, null
     * leaves the transport alone (queue edits while playing).
     */
    private fun install(playNow: Boolean?, restart: Boolean = false) {
        if (released) return
        val snapshot = queue.snapshot()
        publishQueue(snapshot)
        if (snapshot.isEmpty) {
            coordinator.onQueue(PlaybackContext.SINGLE, emptyList(), 0)
            if (playNow != null) _state.update { it.copy(isPlaying = false) }
            return
        }
        sawAudio = false
        underruns = 0
        waitingSinceNanos = System.nanoTime()
        val effectiveSongs = effectiveSongs(snapshot)
        val effectiveIndex = if (snapshot.repeat == RepeatMode.ONE) 0 else snapshot.index.coerceAtLeast(0)
        // The coordinator only re-issues SetProgram when the current track changed; an empty queue first forces the
        // fresh install that "play this now", a repeat restart and a finished program need.
        val forceFresh = restart || player.isEnded
        // Stop the old program the moment the user asked for something else: without this the previous track keeps
        // playing until the coordinator's install lands (a fingerprint read away). The empty program renders nothing,
        // so the audio thread waits for the real one instead.
        if (forceFresh) player.submit(EngineCommand.SetProgram(PlaybackProgram(emptyList())))
        installJob?.cancel()
        installJob = scope.launch {
            prepareImmediate(snapshot.current)
            if (forceFresh) coordinator.onQueue(snapshot.context, emptyList(), 0)
            coordinator.onQueue(snapshot.context, QueueManager.queueItems(effectiveSongs), effectiveIndex)
            persist()
        }
        when (playNow) {
            true -> play()
            false -> pause()
            null -> {}
        }
    }

    /** REPEAT_ONE plays a one-track program and restarts it at the end; every other mode plays the queue. */
    private fun effectiveSongs(snapshot: QueueSnapshot): List<Song> {
        val current = snapshot.current
        return if (snapshot.repeat == RepeatMode.ONE && current != null) listOf(current) else snapshot.songs
    }

    /**
     * Tells the analysis service that this one track is what the listener is waiting for, so its urgent analysis
     * falls back to a placeholder instead of holding playback for seconds. Touches the file (fingerprint), so it
     * only ever runs off the main thread.
     */
    private fun prepareImmediate(current: Song?) {
        if (current == null) {
            lastImmediateSongId = null
            analyses.clearImmediate()
            return
        }
        // Queue edits do not change what is playing: the fingerprint (a megabyte of file I/O) is read once per track.
        if (lastImmediateSongId == current.id) return
        lastImmediateSongId = current.id
        val cached = try {
            analyses.cached(current)
        } catch (t: Throwable) {
            null
        }
        if (cached != null) {
            analyses.clearImmediate()
        } else {
            analyses.expectImmediate(
                QueueManager.sourceOf(current),
                QueueManager.placeholderAnalysis(current, sampleRate, prefs.targetLufs),
            )
        }
    }

    // ================================================================================================ audio thread

    private fun ensureAudioThread() {
        synchronized(this) {
            if (released) return
            val existing = audioThread
            if (existing != null && existing.isAlive) return
            stopped = false
            val thread = Thread({ audioLoop() }, "muisc-audio")
            thread.priority = Thread.MAX_PRIORITY
            audioThread = thread
            thread.start()
        }
    }

    /**
     * `render → interleave → write`, forever. The sink's blocking write paces everything; when nothing should be
     * heard the thread parks on [transportReady] with the sink paused, so a paused player costs no CPU.
     */
    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val blockFrames = limits.blockFrames
        val block = Array(channels) { FloatArray(blockFrames) }
        val interleaved = FloatArray(blockFrames * channels)
        var sink: AudioSink? = null
        var sinkRunning = false
        try {
            while (!stopped) {
                transportLock.withLock {
                    while (!stopped && !wantPlaying && drainBlocks <= 0) {
                        if (sinkRunning) {
                            sink?.let { pauseSink(it) }
                            sinkRunning = false
                        }
                        transportReady.await()
                    }
                }
                if (stopped) break
                if (sink == null) sink = sinkFactory(sampleRate, channels)
                val out = sink ?: break
                if (!sinkRunning) {
                    out.resume()
                    sinkRunning = true
                }
                val produced = player.render(block, blockFrames)
                if (produced <= 0) {
                    if (sawAudio) {
                        // End of the program: the Ended event drives repeat / stop on the pump.
                        transportLock.withLock { wantPlaying = false; drainBlocks = 0 }
                        pauseSink(out)
                        sinkRunning = false
                    } else if (System.nanoTime() - waitingSinceNanos > STARTUP_TIMEOUT_NANOS) {
                        reportError("Could not start playback (no program installed)")
                        transportLock.withLock { wantPlaying = false; drainBlocks = 0 }
                        pauseSink(out)
                        sinkRunning = false
                    } else {
                        // The coordinator has not installed the program yet: wait for it, cheaply.
                        transportLock.withLock { transportReady.await(WAIT_FOR_PROGRAM_MS, TimeUnit.MILLISECONDS) }
                    }
                    continue
                }
                sawAudio = true
                SinkPump.interleave(block, interleaved, produced, channels)
                out.write(interleaved, produced)
                if (!wantPlaying) {
                    var done = false
                    transportLock.withLock {
                        if (drainBlocks > 0) drainBlocks--
                        done = drainBlocks <= 0
                    }
                    if (done) {
                        pauseSink(out)
                        sinkRunning = false
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            reportError("Audio output failed: " + (t.message ?: t.javaClass.simpleName))
            transportLock.withLock { wantPlaying = false; drainBlocks = 0 }
        } finally {
            try {
                sink?.close()
            } catch (t: Throwable) {
                // Nothing useful left to do while tearing down.
            }
        }
    }

    private fun pauseSink(sink: AudioSink) {
        try {
            sink.pause()
        } catch (t: Throwable) {
            // A sink that cannot pause still stops being written to.
        }
    }

    // ================================================================================================ pump

    /** Drains the player's events, forwards them to the coordinator and publishes position / state. */
    private fun startPump() {
        if (released) return
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            var sincePosition = 0L
            while (isActive && !released) {
                val playing = _state.value.isPlaying
                val events = player.events.drain()
                for (event in events) handleEvent(event)
                val step = if (playing) PUMP_INTERVAL_MS else IDLE_INTERVAL_MS
                sincePosition += step
                if (playing && sincePosition >= POSITION_INTERVAL_MS) {
                    sincePosition = 0L
                    publishPosition()
                }
                delay(step)
            }
        }
    }

    private fun handleEvent(event: PlayerEvent) {
        coordinator.onPlayerEvent(event)
        when (event) {
            is PlayerEvent.SegmentStarted -> {
                sawAudio = true
                _state.update { it.copy(inTransition = event.segment !is Segment.Body) }
                val restore = pendingRestoreMs
                if (restore > 0L && event.segment is Segment.Body) {
                    pendingRestoreMs = 0L
                    seekTo(restore)
                }
            }
            is PlayerEvent.TrackChanged -> onTrackChanged(event.track.id)
            is PlayerEvent.Ended -> onProgramEnded()
            is PlayerEvent.Underrun -> underruns += 1
            is PlayerEvent.PositionUpdate -> {}
        }
    }

    private fun onTrackChanged(trackId: String) {
        val index = queue.indexOfEngineId(trackId)
        if (index >= 0 && index != queue.index) {
            publishQueue(queue.skipTo(index))
        }
        val song = queue.current()
        if (song != null) {
            val context = queue.context
            scope.launch {
                try {
                    onSongStarted?.invoke(song, context)
                } catch (t: Throwable) {
                    // History is cosmetic.
                }
            }
        }
        persist()
    }

    private fun onProgramEnded() {
        // Nothing was ever produced by this program: either it is the empty one submitted while a new queue is being
        // installed, or the file / analysis is broken — the audio thread's startup timeout reports that as an error.
        if (!sawAudio) return
        val snapshot = queue.snapshot()
        when (snapshot.repeat) {
            RepeatMode.ONE -> install(true, restart = true)
            RepeatMode.ALL -> {
                queue.skipTo(0)
                install(true, restart = true)
            }
            RepeatMode.OFF -> {
                _state.update { it.copy(isPlaying = false, inTransition = false) }
                transportLock.withLock { wantPlaying = false; drainBlocks = 0; transportReady.signalAll() }
                abandonFocus()
                persist()
            }
        }
    }

    // ================================================================================================ state

    private fun publishQueue(snapshot: QueueSnapshot) {
        val current = snapshot.current
        _state.update {
            it.copy(
                queue = snapshot.songs,
                currentIndex = snapshot.index,
                context = snapshot.context,
                shuffle = snapshot.shuffle,
                repeat = snapshot.repeat,
                durationMs = current?.durationMs ?: 0L,
                edges = analysingEdges(snapshot, it.edges),
            )
        }
    }

    /** Edges the coordinator is about to look at start as "Analysing" so the queue never shows a stale badge. */
    private fun analysingEdges(snapshot: QueueSnapshot, previous: Map<Int, EdgeState>): Map<Int, EdgeState> {
        if (snapshot.songs.size < 2 || snapshot.index < 0) return emptyMap()
        val out = HashMap<Int, EdgeState>()
        for (i in snapshot.index..minOf(snapshot.index + 1, snapshot.songs.size - 2)) {
            out[i] = previous[i] ?: EdgeState.Analysing
        }
        return out
    }

    private fun publishEdges(states: Map<Int, CoordinatorState>) {
        val out = HashMap<Int, EdgeState>(states.size)
        for ((index, state) in states) out[index] = toEdgeState(index, state)
        _state.update { it.copy(edges = out) }
    }

    private fun toEdgeState(index: Int, state: CoordinatorState): EdgeState = when (state) {
        is CoordinatorState.Gated -> EdgeState.Gated(state.reason)
        is CoordinatorState.Analysing -> EdgeState.Analysing
        is CoordinatorState.Planned -> {
            edgeStrategies[index] = state.strategyId
            EdgeState.Planned(state.strategyId, displayName(state.strategyId), state.score)
        }
        is CoordinatorState.Rendering -> EdgeState.Rendering(edgeStrategies[index] ?: "", state.progress.toFloat())
        is CoordinatorState.Ready -> {
            edgeStrategies[index] = state.strategyId
            EdgeState.Ready(state.strategyId, displayName(state.strategyId))
        }
        is CoordinatorState.Live -> EdgeState.Live(state.kind, state.reason)
        is CoordinatorState.Failed -> EdgeState.Failed(state.reason)
    }

    private fun displayName(strategyId: String): String = registry.strategy(strategyId)?.displayName ?: strategyId

    private fun publishPosition() {
        val position = player.position
        val ms = position.trackFrame * 1000L / sampleRate
        _state.update { it.copy(positionMs = if (ms < 0) 0L else ms) }
    }

    private fun reportError(message: String) {
        _state.update { it.copy(error = message, isPlaying = false) }
    }

    private fun persist() {
        val store = persistence ?: return
        val snapshot = queue.snapshot()
        val positionMs = _state.value.positionMs
        scope.launch {
            store.save(
                PersistedQueue(
                    orderIds = snapshot.songs.map { it.id },
                    naturalIds = queue.naturalOrder().map { it.id },
                    index = snapshot.index,
                    context = snapshot.context,
                    shuffle = snapshot.shuffle,
                    repeat = snapshot.repeat,
                    positionMs = positionMs,
                ),
            )
        }
    }

    /** The engine format is the device's, never whatever was stored on another device / build. */
    private fun normalize(value: TransitionPrefs): TransitionPrefs =
        if (value.sampleRate == sampleRate && value.channels == channels) value
        else value.copy(sampleRate = sampleRate, channels = channels)

    // ================================================================================================ audio focus

    /** [DuckableEngine]: attenuates the engine's master gain (0 = none); the player ramps over ~50 ms. */
    override fun setDuckDb(db: Float) { player.setDuckDb(db) }

    /** Requests audio focus before playback starts; playback proceeds only when it is granted. */
    private fun requestFocus(): Boolean = focus.request()

    /** Releases audio focus when playback stops or the controller is released. */
    private fun abandonFocus() = focus.abandon()

    companion object {
        // Ducking depth lives with the thing that decides to duck: AudioFocusHandler.DUCK_DB (DESIGN §8).

        /** "Previous" restarts the track instead of stepping back after this much of it has played. */
        const val RESTART_THRESHOLD_MS = 3_000L

        const val PUMP_INTERVAL_MS = 50L
        const val IDLE_INTERVAL_MS = 250L
        const val POSITION_INTERVAL_MS = 250L
        const val WAIT_FOR_PROGRAM_MS = 25L
        const val AUDIO_JOIN_MS = 1_000L

        /** How long the audio thread waits for a first program before giving up with an error. */
        val STARTUP_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30)
    }
}
