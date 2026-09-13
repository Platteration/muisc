package dev.muisc.player

import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.dsp.stems.Stems
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.PairAnalyzer
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.PlaybackProgram
import dev.muisc.transitions.ProgramBuilder
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.Segment
import dev.muisc.transitions.StemProvider
import dev.muisc.transitions.TrackAudioLoader
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionGating
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlanner
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionRenderer
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.live.LivePlanFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Plan → render → install state machine for the queue (the "mini personal DJ"). Runs entirely on [scope] (a
 * single-threaded dispatcher on the phone; a test dispatcher in tests); all time comes from [clock] and from the
 * player's position — it owns no threads.
 *
 * Life-cycle per edge (track i → i+1), see DESIGN §7.4:
 *  1. [onQueue] resolves analyses for the current and next track (urgent) and the two after them (background),
 *     installs / extends the [PlaybackProgram] on the [player] (bodies from [programBuilder]).
 *  2. Gate ([TransitionGating] plus segue protection and album-flow-in-playlist) → [CoordinatorState.Gated].
 *  3. Plan ([planner]) → [CoordinatorState.Planned].
 *  4. Render when `remainingA ≤ max(90 s, 4 × estimated render time)`; [gate] checks the render; accepted renders
 *     are installed via [EngineCommand.ReplaceTail] while the cursor is `< aExitFrame − 2 s` → [CoordinatorState.Ready].
 *     A rejected or failed render excludes its strategy and moves to the next candidate.
 *  5. Deadline `aExitFrame − 5 s` missed (or no candidate left) → a [LivePlanFactory] plan is installed as
 *     [Segment.Live] → [CoordinatorState.Live].
 *
 * [onUserSkip] performs a "DJ skip" (jump A to `aExitFrame − 4 bars`, then the render) when a render is installed
 * and more than 8 bars remain, else a live transition from now; [onUserSeek] drops a render the seek jumps past;
 * [setPowerMode] restricts the ladder (SAVER: live-capable strategies only; STRICT_SAVER: no transitions);
 * [onTrimMemory] swaps the next render for a live plan.
 */
class TransitionCoordinator(
    val planner: TransitionPlanner,
    val liveFactory: LivePlanFactory,
    val renderer: TransitionRenderer,
    val programBuilder: ProgramBuilder,
    val player: ProgramPlayer,
    val analyses: AnalysisService,
    val gate: RenderGate,
    val limits: EngineLimits,
    val clock: Clock,
    val scope: CoroutineScope,
    val prefsProvider: () -> TransitionPrefs,
    val pairAnalyzer: PairAnalyzer = DefaultPairAnalyzer(),
    /** Decoded windows for the full [RenderGate] check; without it the gate's cheap report check is used. */
    val windows: TrackAudioLoader? = null,
    val seed: Long = 0L,
) {
    private val _state = MutableStateFlow<Map<Int, CoordinatorState>>(emptyMap())

    /** Per-edge state (key = queue index of the outgoing track) for the queue badges. */
    val state: StateFlow<Map<Int, CoordinatorState>> get() = _state

    var powerMode: PowerMode = PowerMode.NORMAL
        private set

    private val sampleRate: Int get() = player.sampleRate

    // ---- queue model (scope thread only) ----
    private var queue: List<QueueItem> = emptyList()
    private var context = PlaybackContext.QUEUE
    private var currentIndex = 0
    private val refs = HashMap<Int, TrackRef>()
    private val analysisCache = HashMap<String, TrackAnalysis>()
    private val inFlight = HashMap<String, Deferred<TrackAnalysis>>()
    private val failedAnalyses = HashSet<String>()
    private val edges = HashMap<Int, Edge>()
    private var driverJob: Job? = null
    private var previousStrategyId: String? = null
    private var renderEstimateMs = DEFAULT_RENDER_ESTIMATE_MS
    private val retained = object : LinkedHashMap<String, RenderedTransition>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RenderedTransition>): Boolean = size > limits.maxRenderedAlive
    }

    // ---- what the player holds (mirror of the submitted program) ----
    private class Entry(val segment: Segment, val queueIndex: Int, val isBody: Boolean)
    private val mirror = ArrayList<Entry>()
    private var programInstalled = false
    private var installedGainDb: Float? = null

    private val _log = ArrayList<String>()

    /** Human-readable transition log (reasons for live fallbacks, rejected renders, skips). */
    val transitionLog: List<String> get() = synchronized(_log) { ArrayList(_log) }

    /** Rolling estimate of a render's wall-clock cost, ms (from [clock]). */
    val renderEstimateMillis: Double get() = renderEstimateMs

    private inner class Edge(val index: Int, val aId: String, val bId: String) {
        var state: CoordinatorState = CoordinatorState.Analysing
        var ranked: RankedPlans? = null
        var features: PairFeatures? = null
        var candidatePos = 0
        val excluded = HashSet<String>()
        var renderJob: Job? = null
        var rendered: RenderedTransition? = null
        var installed: Segment? = null
        var liveGraph: LiveGraph? = null
        var forceLive: String? = null
        val a: TrackRef get() = refs[index]!!
        val b: TrackRef get() = refs[index + 1]!!
        val isDone: Boolean get() = state is CoordinatorState.Gated || state is CoordinatorState.Failed || installed is Segment.Live
    }

    // ================================================================================================ host API

    /** The queue (or a new queue) with its playback context and the index of the track now playing. */
    fun onQueue(context: PlaybackContext, items: List<QueueItem>, currentIndex: Int) {
        scope.launch { setQueue(context, items, currentIndex) }
    }

    fun onPlayerEvent(e: PlayerEvent) {
        scope.launch {
            when (e) {
                is PlayerEvent.TrackChanged -> onTrackChanged(e.track)
                is PlayerEvent.PositionUpdate, is PlayerEvent.SegmentStarted -> evaluate()
                is PlayerEvent.Ended -> for (edge in edges.values) cancelRender(edge)
                is PlayerEvent.Underrun -> log("underrun of ${e.frames} frames at output frame ${e.atOutputFrame}")
            }
        }
    }

    fun onUserSkip() { scope.launch { userSkip() } }

    fun onUserSeek(frame: Long) { scope.launch { userSeek(frame) } }

    /** Android `ComponentCallbacks2` level; ≥ [TRIM_MEMORY_RUNNING_LOW] drops the next render in favour of a live plan. */
    fun onTrimMemory(level: Int) { scope.launch { trimMemory(level) } }

    fun setPowerMode(mode: PowerMode) { scope.launch { applyPowerMode(mode) } }

    /** Cancels every job; the coordinator is unusable afterwards. */
    fun shutdown() {
        driverJob?.cancel()
        for (e in edges.values) e.renderJob?.cancel()
        for (d in inFlight.values) d.cancel()
    }

    // ================================================================================================ queue

    private suspend fun setQueue(ctx: PlaybackContext, items: List<QueueItem>, current: Int) {
        val old = queue
        val oldCurrentId = old.getOrNull(currentIndex)?.id
        queue = items
        context = ctx
        currentIndex = if (items.isEmpty()) 0 else current.coerceIn(0, items.size - 1)
        for (k in refs.keys.toList()) if (k >= items.size || refs[k]!!.id != items[k].id) refs.remove(k)
        for (k in edges.keys.toList()) {
            val e = edges[k]!!
            if (k + 1 >= items.size || e.aId != items[k].id || e.bId != items[k + 1].id) { cancelEdge(e); edges.remove(k) }
        }
        if (old.isEmpty() || oldCurrentId != items.getOrNull(currentIndex)?.id) programInstalled = false
        if (items.isEmpty()) { mirror.clear(); publishState(); return }
        // Fresh queue: go now. Edited queue: 500 ms debounce so a burst of edits plans once.
        scheduleDrive(if (old.isEmpty() || !programInstalled) 0L else QUEUE_DEBOUNCE_MS)
    }

    private fun scheduleDrive(debounceMs: Long) {
        driverJob?.cancel()
        driverJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            drive()
        }
    }

    private suspend fun drive() {
        val ci = currentIndex
        if (ensureRef(ci, urgent = true) == null) { publishState(); return }
        rebuildAndInstall()
        ensureRef(ci + 1, urgent = true)
        rebuildAndInstall()
        evaluate()
        for (k in ci + 2..min(ci + 3, queue.size - 1)) ensureRef(k, urgent = false)
        rebuildAndInstall()
        evaluate()
    }

    /** Resolves the [TrackRef] for queue index [k] (analysis from cache or [analyses]); null when unavailable. */
    private suspend fun ensureRef(k: Int, urgent: Boolean): TrackRef? {
        if (k < 0 || k >= queue.size) return null
        refs[k]?.let { return it }
        val item = queue[k]
        val key = item.sourceId.value
        if (key in failedAnalyses) return null
        val analysis = analysisCache[key] ?: run {
            val d = inFlight.getOrPut(key) { scope.async { analyses.analysis(item.sourceId, urgent) } }
            try {
                d.await().also { analysisCache[key] = it }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failedAnalyses += key
                log("analysis failed for ${item.sourceId}: ${t.message}")
                return null
            } finally {
                inFlight.remove(key)
            }
        }
        // The queue may have changed while we were suspended.
        if (queue.getOrNull(k)?.id != item.id) return null
        val ref = TrackRef(item.id, item.sourceId, analysis, item.albumId, item.title, item.artist)
        refs[k] = ref
        return ref
    }

    private suspend fun onTrackChanged(track: TrackRef) {
        var k = -1
        for (i in currentIndex until queue.size) if (queue[i].id == track.id) { k = i; break }
        if (k < 0 || k == currentIndex) return
        currentIndex = k
        for (i in edges.keys.toList()) if (i < k - 1) { cancelEdge(edges[i]!!); edges.remove(i) }
        edges[k - 1]?.let { e -> (e.installed as? Segment.Rendered)?.let { previousStrategyId = it.rendered.plan.strategyId } }
        scheduleDrive(0L)
    }

    // ================================================================================================ evaluation

    private fun evaluate() {
        if (queue.isEmpty()) return
        val ci = currentIndex
        for (k in ci..min(ci + 1, queue.size - 2)) {
            if (refs[k] == null || refs[k + 1] == null) continue
            val e = edges.getOrPut(k) { Edge(k, queue[k].id, queue[k + 1].id) }
            step(e)
        }
        publishState()
    }

    private fun step(e: Edge) {
        if (e.isDone) return
        if (e.ranked == null) {
            gateAndPlan(e)
            if (e.isDone) return
        }
        if (e.index != currentIndex) return
        e.forceLive?.let { reason -> if (e.installed == null) { installLive(e, reason, null); return } }
        if (e.rendered != null && e.installed == null) { tryInstallRender(e); return }
        if (e.installed is Segment.Rendered) return
        val cand = currentCandidate(e)
        if (cand == null) { installLive(e, "no renderable candidate", null); return }
        val aNow = aNowFrame(e)
        val deadline = cand.plan.aExitFrame - secondsToFrames(DEADLINE_SEC)
        if (aNow >= deadline) {
            val reason = if (e.renderJob != null) "deadline missed while rendering ${cand.strategy.id}" else "deadline missed"
            installLive(e, reason, null)
            return
        }
        if (e.renderJob == null) {
            val remainingSec = (cand.plan.aExitFrame - aNow) / sampleRate.toDouble()
            if (remainingSec <= max(RENDER_HORIZON_SEC, 4.0 * renderEstimateMs / 1000.0)) startRender(e, cand)
        }
    }

    private fun gateAndPlan(e: Edge) {
        val prefs = prefsProvider()
        val a = e.a; val b = e.b
        gateReason(a, b, prefs)?.let { e.state = CoordinatorState.Gated(it); return }
        val key = retainKey(a, b)
        val f = try { pairAnalyzer.features(a.analysis, b.analysis, prefs) } catch (t: Throwable) { e.state = CoordinatorState.Failed("pair analysis: ${t.message}"); return }
        e.features = f
        val ranked = try { planner.plan(a, b, prefs, seed, previousStrategyId) } catch (t: Throwable) { e.state = CoordinatorState.Failed("planner: ${t.message}"); return }
        e.ranked = ranked
        e.candidatePos = 0
        retained[key]?.let { r ->
            if (r.plan.strategyId !in prefs.disabledStrategies && allowedByPower(r.plan.strategyId)) {
                e.rendered = r
                e.state = CoordinatorState.Ready(r.plan.strategyId)
                log("edge ${e.index}: reusing retained ${r.plan.strategyId} render")
                return
            }
        }
        val cand = currentCandidate(e)
        if (cand == null) { e.forceLive = "no strategy allowed"; e.state = CoordinatorState.Planned(ranked.best.strategy.id, ranked.best.score); return }
        e.state = CoordinatorState.Planned(cand.strategy.id, cand.score)
    }

    /** Gating: the frozen rule plus the coordinator's additive refinements; null = transition allowed. */
    private fun gateReason(a: TrackRef, b: TrackRef, prefs: TransitionPrefs): String? {
        if (powerMode == PowerMode.STRICT_SAVER) return "Power saver"
        if (!prefs.enabled) return "Transitions disabled"
        val sameAlbum = a.albumId != null && a.albumId == b.albumId
        val reason: String? = when (context) {
            PlaybackContext.SINGLE -> "Single track"
            PlaybackContext.ALBUM -> if (sameAlbum && !prefs.allowInAlbums) "Album playback" else null
            PlaybackContext.SHUFFLE -> if (prefs.keepAlbumFlowInShuffle && sameAlbum) "Album flow kept" else null
            PlaybackContext.PLAYLIST -> if (prefs.keepAlbumFlowInShuffle && sameAlbum) "Album flow kept" else null
            PlaybackContext.QUEUE -> null
        }
        if (!TransitionGating.transitionsEnabled(context, a, b, prefs)) return reason ?: "Gated"
        if (reason != null) return reason
        val an = a.analysis
        val trailingSilence = (an.totalFrames - an.trimEndFrame).toDouble() / an.sampleRate
        if (an.outro == OutroType.HARD_STOP && trailingSilence < SEGUE_SILENCE_SEC && sameAlbum) return "Segue protection"
        return null
    }

    private fun allowedByPower(strategyId: String): Boolean = powerMode != PowerMode.SAVER || strategyId in LIVE_CAPABLE

    /** The candidate the edge is on (skipping excluded, power-restricted and over-long ones), advancing [Edge.candidatePos]. */
    private fun currentCandidate(e: Edge): PlanCandidate? {
        val ranked = e.ranked ?: return null
        val maxFrames = limits.maxRenderedSec * sampleRate
        while (e.candidatePos < ranked.candidates.size) {
            val c = ranked.candidates[e.candidatePos]
            val id = c.strategy.id
            val ok = id !in e.excluded && allowedByPower(id) && c.plan.expectedOutputFrames <= maxFrames
            if (ok) return c
            if (c.plan.expectedOutputFrames > maxFrames && id !in e.excluded) { e.excluded += id; log("edge ${e.index}: $id skipped, ${c.plan.expectedOutputFrames} frames exceed the ${limits.maxRenderedSec} s cap") }
            e.candidatePos++
        }
        return null
    }

    private fun startRender(e: Edge, cand: PlanCandidate) {
        e.state = CoordinatorState.Rendering(0.0)
        publishState()
        val job = scope.launch {
            val t0 = clock.nowNanos()
            val prefs = prefsProvider()
            val result: Result<RenderedTransition> = try {
                val ctx = RenderContext(prefs, seed) { p ->
                    if (!isActive) throw CancellationException("render cancelled")
                    e.state = CoordinatorState.Rendering(p.coerceIn(0.0, 1.0))
                    publishState()
                }
                Result.success(renderer.render(e.a, e.b, cand, e.features!!, ctx))
            } catch (ce: CancellationException) {
                e.renderJob = null
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }
            e.renderJob = null
            val ms = (clock.nowNanos() - t0) / 1e6
            result.onSuccess { r ->
                renderEstimateMs = 0.5 * renderEstimateMs + 0.5 * ms
                val tooLong = r.audio.frames > limits.maxRenderedSec * sampleRate
                val accepted = !tooLong && accept(r, e)
                if (!accepted) {
                    e.excluded += cand.strategy.id
                    log("edge ${e.index}: ${cand.strategy.id} render rejected" + if (tooLong) " (too long)" else " by the gate")
                    advanceCandidate(e)
                } else {
                    e.rendered = r
                    retained[retainKey(e.a, e.b)] = r
                    e.state = CoordinatorState.Ready(cand.strategy.id)
                    log("edge ${e.index}: ${cand.strategy.id} rendered in ${"%.0f".format(ms)} ms")
                    tryInstallRender(e)
                }
            }.onFailure { t ->
                e.excluded += cand.strategy.id
                log("edge ${e.index}: ${cand.strategy.id} render failed: ${t.message}")
                advanceCandidate(e)
            }
            evaluate()
        }
        e.renderJob = job
    }

    private fun advanceCandidate(e: Edge) {
        val next = currentCandidate(e)
        if (next == null) installLive(e, "all candidates failed", null)
        else e.state = CoordinatorState.Planned(next.strategy.id, next.score)
    }

    private fun accept(r: RenderedTransition, e: Edge): Boolean {
        val w = windows ?: return gate.accept(r)
        val prefs = prefsProvider()
        return try {
            val aAudio = w.load(e.a, r.plan.aWindow, prefs)
            val bAudio = w.load(e.b, r.plan.bWindow, prefs)
            DeckGain.applyInPlace(aAudio, DeckGain.of(e.a.analysis, prefs))
            DeckGain.applyInPlace(bAudio, DeckGain.of(e.b.analysis, prefs))
            gate.accept(r, TransitionInput(r.plan, e.a, e.b, e.features!!, aAudio, bAudio, NoStems))
        } catch (t: Throwable) {
            log("edge ${e.index}: gate check failed: ${t.message}")
            false
        }
    }

    private fun tryInstallRender(e: Edge) {
        val r = e.rendered ?: return
        val aNow = aNowFrame(e)
        if (aNow >= r.plan.aExitFrame - secondsToFrames(INSTALL_MARGIN_SEC)) {
            e.rendered = null
            installLive(e, "render ready too late (${r.plan.strategyId})", null)
            return
        }
        e.installed = Segment.Rendered(e.a, e.b, r)
        e.state = CoordinatorState.Ready(r.plan.strategyId)
        previousStrategyId = r.plan.strategyId
        rebuildAndInstall()
    }

    /** Installs a live plan for [e]; [fromFrame] = where A should start the move (null: the planned exit, else soon). */
    private fun installLive(e: Edge, reason: String, fromFrame: Long?, fadeSecOverride: Double? = null) {
        cancelRender(e)
        val prefs = prefsProvider()
        val aNow = aNowFrame(e)
        val lead = secondsToFrames(LIVE_LEAD_SEC)
        val planned = e.rendered?.plan?.aExitFrame ?: currentCandidate(e)?.plan?.aExitFrame
        val from = fromFrame ?: if (planned != null && planned > aNow + lead) planned else aNow + lead
        val plan = liveFactory.plan(e.a, e.b, e.features, from, prefs, fadeSecOverride)
        val seg = Segment.Live(e.a, e.b, plan)
        e.liveGraph = LiveGraph(plan, sampleRate, player.channels, max(limits.blockFrames, 4096))
        e.installed = seg
        e.rendered = null
        e.forceLive = null
        e.state = CoordinatorState.Live(plan.kind, reason)
        log("edge ${e.index}: live ${plan.kind} — $reason")
        rebuildAndInstall()
    }

    private fun cancelRender(e: Edge) {
        e.renderJob?.cancel()
        e.renderJob = null
    }

    private fun cancelEdge(e: Edge) {
        cancelRender(e)
        e.liveGraph = null
    }

    // ================================================================================================ program

    /** Rebuilds the program from the current track on and submits it (SetProgram once, then ReplaceTail). */
    private fun rebuildAndInstall() {
        if (queue.isEmpty()) return
        val prefs = prefsProvider()
        val ci = currentIndex
        val tail = ArrayList<Entry>()
        var k = ci
        while (k < queue.size) {
            val ref = refs[k] ?: break
            val inEdge = edges[k - 1]; val outEdge = edges[k]
            val incoming = (inEdge?.installed as? Segment.Rendered)?.rendered
            val outgoing = (outEdge?.installed as? Segment.Rendered)?.rendered
            var body = programBuilder.bodySegment(ref, context, prefs, incoming, outgoing)
            (inEdge?.installed as? Segment.Live)?.let { body = body.copy(fromFrame = it.plan.bExitFrame().coerceIn(0L, body.toFrame)) }
            (outEdge?.installed as? Segment.Live)?.let { body = body.copy(toFrame = it.plan.aFromFrame.coerceIn(body.fromFrame, max(body.fromFrame, body.toFrame))) }
            tail += Entry(body, k, true)
            outEdge?.installed?.let { tail += Entry(it, k, false) }
            k++
        }
        if (tail.isEmpty()) return
        val prebuilt = HashMap<Int, LiveGraph>()
        for ((i, entry) in tail.withIndex()) if (entry.segment is Segment.Live) edges[entry.queueIndex]?.liveGraph?.let { prebuilt[i] = it }
        // In an album every track shares one deck gain (that of the loudest track); it can only improve as more
        // analyses resolve, and reaches the player with the install that follows.
        val shared = if (context == PlaybackContext.ALBUM) albumGain(prefs) else null
        val gainChanged = shared != installedGainDb
        val from = mirror.indexOfFirst { it.queueIndex == ci && it.isBody }
        if (!programInstalled || from < 0) {
            mirror.clear(); mirror.addAll(tail)
            installedGainDb = shared
            player.submit(EngineCommand.SetProgram(PlaybackProgram(tail.map { it.segment }), shared, prebuilt))
            programInstalled = true
            return
        }
        val current = mirror.subList(from, mirror.size).map { it.segment }
        if (current == tail.map { it.segment } && !gainChanged) return
        while (mirror.size > from) mirror.removeAt(mirror.size - 1)
        mirror.addAll(tail)
        installedGainDb = shared
        player.submit(EngineCommand.ReplaceTail(from, tail.map { it.segment }, prebuilt, shared))
    }

    /** Album context: one gain for every track, that of the loudest analysed track (minimum dB). */
    private fun albumGain(prefs: TransitionPrefs): Float? {
        val album = queue.getOrNull(currentIndex)?.albumId
        val tracks = refs.values.filter { album == null || it.albumId == album }
        if (tracks.isEmpty()) return null
        return ProgramRenderer.albumGainDb(tracks, prefs)
    }

    /** A's current frame for [e]: the player's position when A is playing, A's body start before it, far past it after. */
    private fun aNowFrame(e: Edge): Long {
        val pos = player.position
        if (pos.nowPlaying?.id == e.a.id) return pos.trackFrame
        val owner = mirror.getOrNull(pos.segmentIndex)?.queueIndex ?: -1
        if (pos.nowPlaying == null || owner < e.index) {
            val an = e.a.analysis
            return Math.round(an.trimStartFrame * (sampleRate / an.sampleRate.toDouble()))
        }
        return FAR_FUTURE
    }

    // ================================================================================================ user actions

    private fun userSkip() {
        val ci = currentIndex
        val e = edges[ci]
        val pos = player.position
        val inTransition = mirror.getOrNull(pos.segmentIndex)?.let { !it.isBody } ?: false
        if (e == null || inTransition || e.isDone || refs[ci + 1] == null || e.features == null) {
            player.submit(EngineCommand.Skip)
            log("skip: plain skip")
            return
        }
        val r = (e.installed as? Segment.Rendered)?.rendered
        val aNow = aNowFrame(e)
        if (r != null) {
            val bars8 = barsToFrames(e.a, 8)
            if (aNow < r.plan.aExitFrame - bars8) {
                var target = r.plan.aExitFrame - barsToFrames(e.a, 4)
                target = snapToDownbeat(e.a, target)
                player.submit(EngineCommand.Seek(max(aNow, target)))
                log("edge $ci: DJ skip to frame $target (${r.plan.strategyId} render)")
                publishState()
                return
            }
        }
        installLive(e, "user skip", aNow + secondsToFrames(SKIP_LEAD_SEC), SKIP_FADE_SEC)
        publishState()
    }

    private fun userSeek(frame: Long) {
        player.submit(EngineCommand.Seek(frame))
        val e = edges[currentIndex] ?: return
        val exit = (e.installed as? Segment.Rendered)?.rendered?.plan?.aExitFrame ?: (e.installed as? Segment.Live)?.plan?.aFromFrame ?: return
        if (frame < exit) return
        cancelRender(e)
        e.installed = null
        e.rendered = null
        e.liveGraph = null
        val an = e.a.analysis
        val trimEnd = Math.round(an.trimEndFrame * (sampleRate / an.sampleRate.toDouble()))
        val remainingSec = (trimEnd - frame) / sampleRate.toDouble()
        if (remainingSec > REPLAN_MIN_SEC) {
            e.ranked = null; e.excluded.clear(); e.candidatePos = 0; e.forceLive = null
            e.state = CoordinatorState.Analysing
            log("edge ${e.index}: seek past the exit, re-planning (${"%.0f".format(remainingSec)} s left)")
            rebuildAndInstall()
            evaluate()
        } else {
            installLive(e, "seek past the exit", frame + secondsToFrames(SKIP_LEAD_SEC))
            publishState()
        }
    }

    private fun trimMemory(level: Int) {
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        retained.clear()
        for (e in edges.values) {
            if (e.index == currentIndex && !e.isDone) {
                cancelRender(e)
                e.rendered = null
                e.installed = null
                installLive(e, "low memory", null)
            } else if (!e.isDone) {
                cancelRender(e)
                e.rendered = null
                if (e.installed is Segment.Rendered) e.installed = null
                e.forceLive = "low memory"
            }
        }
        rebuildAndInstall()
        publishState()
    }

    private fun applyPowerMode(mode: PowerMode) {
        val old = powerMode
        powerMode = mode
        if (mode == old) return
        for (e in edges.values) {
            when (mode) {
                PowerMode.STRICT_SAVER -> {
                    cancelEdge(e)
                    e.rendered = null; e.installed = null; e.forceLive = null
                    e.state = CoordinatorState.Gated("Power saver")
                }
                PowerMode.SAVER -> {
                    if ((e.state as? CoordinatorState.Gated)?.reason == "Power saver") { e.state = CoordinatorState.Analysing; e.ranked = null }
                    val cand = e.ranked?.candidates?.getOrNull(e.candidatePos)
                    if (e.renderJob != null && cand != null && cand.strategy.id !in LIVE_CAPABLE) {
                        cancelRender(e)
                        e.state = CoordinatorState.Planned(cand.strategy.id, cand.score)
                    }
                }
                PowerMode.NORMAL -> if ((e.state as? CoordinatorState.Gated)?.reason == "Power saver") { e.state = CoordinatorState.Analysing; e.ranked = null }
            }
        }
        log("power mode $mode")
        rebuildAndInstall()
        evaluate()
    }

    // ================================================================================================ helpers

    private fun barsToFrames(t: TrackRef, bars: Int): Long {
        val an = t.analysis
        val bpm = if (an.tempo.bpm > 0) an.tempo.bpm else 120.0
        val bpb = if (an.grid.beatsPerBar > 0) an.grid.beatsPerBar else 4
        return Math.round(bars * bpb * 60.0 / bpm * sampleRate)
    }

    private fun snapToDownbeat(t: TrackRef, frame: Long): Long {
        val an = t.analysis
        val grid = an.grid
        if (grid.isEmpty) return frame
        val scale = sampleRate / an.sampleRate.toDouble()
        val beat = grid.beatAtFrame(Math.round(frame / scale))
        val db = grid.previousDownbeat(beat)
        return Math.round(grid.frameOfBeat(db.toDouble()) * scale)
    }

    private fun secondsToFrames(sec: Double): Long = Math.round(sec * sampleRate)

    private fun retainKey(a: TrackRef, b: TrackRef): String = a.id + " " + b.id

    private fun publishState() {
        _state.value = edges.values.filter { it.index >= currentIndex }.associate { it.index to it.state }.toSortedMap()
    }

    private fun log(msg: String) {
        synchronized(_log) { _log += "[${clock.nowMillis()} ms] $msg"; if (_log.size > 500) _log.removeAt(0) }
    }

    private object NoStems : StemProvider {
        override fun aTail(): Stems = throw UnsupportedOperationException("no stems for install checks")
        override fun bHead(): Stems = throw UnsupportedOperationException("no stems for install checks")
    }

    companion object {
        const val RENDER_HORIZON_SEC = 90.0
        const val DEADLINE_SEC = 5.0
        const val INSTALL_MARGIN_SEC = 2.0
        const val LIVE_LEAD_SEC = 1.0
        const val SKIP_LEAD_SEC = 0.25
        const val SKIP_FADE_SEC = 1.5
        const val REPLAN_MIN_SEC = 20.0
        const val SEGUE_SILENCE_SEC = 0.05
        const val QUEUE_DEBOUNCE_MS = 500L
        const val DEFAULT_RENDER_ESTIMATE_MS = 5000.0
        /** `ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW`. */
        const val TRIM_MEMORY_RUNNING_LOW = 10
        private const val FAR_FUTURE = Long.MAX_VALUE / 4
        val LIVE_CAPABLE: Set<String> = setOf("crossfade", "phraseCut", "bassSwap", "filterSweep", "echoOut")
    }
}
