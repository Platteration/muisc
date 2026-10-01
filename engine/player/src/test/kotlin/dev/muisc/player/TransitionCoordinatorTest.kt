package dev.muisc.player

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioSourceId
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.DefaultProgramBuilder
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.PlaybackProgram
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.Segment
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPlanner
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionRenderer
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.live.Deck
import dev.muisc.transitions.live.LiveNode
import dev.muisc.transitions.live.LivePlan
import dev.muisc.transitions.live.LivePlanFactory
import dev.muisc.transitions.live.LivePoint
import dev.muisc.transitions.strategies.CrossfadeStrategy
import dev.muisc.transitions.synthetic.SyntheticTrack
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The coordinator's state machine on fakes: a [FakeClock], a planner that hands out fixed candidates, a renderer
 * that succeeds / throws on demand and a [LivePlanFactory] that always yields a crossfade. The player is real
 * (offline mode), so what the coordinator installs is checked on the command queue and on the program the player
 * actually adopts.
 */
class TransitionCoordinatorTest {
    private val sr = PlayerFixtures.SR
    private val a = PlayerFixtures.track(PlayerFixtures.song(120.0, 0, bars = 12, seed = 7), "A", albumId = "album1")
    private val b = PlayerFixtures.track(PlayerFixtures.song(124.0, 5, bars = 12, seed = 8), "B", albumId = "album1")
    private val prefs = PlayerFixtures.prefs(a, b)
    private val limits = EngineLimits.DESKTOP
    private val clock = FakeClock()
    private val streams = PlayerFixtures.streams(a, b)
    private val player = ProgramPlayer(sr, 2, limits, streams, prefs, realtime = false)
    private val block = Array(2) { FloatArray(limits.blockFrames) }

    private val strategy = CrossfadeStrategy()
    private val features: PairFeatures = PlayerFixtures.features(a, b, prefs)
    private val plan: TransitionPlan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("fadeSec", 3.0), prefs, SEED)
    private val rendered: RenderedTransition by lazy { strategy.render(PlayerFixtures.input(plan, a, b, prefs, features), RenderContext(prefs, SEED)) }

    @AfterTest fun tearDown() = player.close()

    // ------------------------------------------------------------------------------------------------ fakes

    private fun items(vararg tracks: SyntheticTrack) = tracks.map { QueueItem(it.trackRef.source, it.trackRef.albumId, it.trackRef.title, it.trackRef.artist, it.id) }

    private inner class FakeAnalyses(tracks: List<SyntheticTrack> = listOf(a, b)) : AnalysisService {
        var urgentCalls = 0
        private val byId = tracks.associate { it.trackRef.source to it.analysis }
        override suspend fun analysis(track: AudioSourceId, urgent: Boolean): TrackAnalysis {
            if (urgent) urgentCalls++
            return byId[track] ?: error("no analysis for $track")
        }
    }

    private class StubStrategy(override val id: String, private val fixed: TransitionPlan) : TransitionStrategy {
        override val displayName: String get() = id
        override val description: String get() = "stub"
        override val params: List<ParamSpec> get() = emptyList()
        override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs) = Applicability.of(0.5, "stub")
        override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long) = fixed
        override fun render(input: dev.muisc.transitions.TransitionInput, ctx: RenderContext): RenderedTransition = throw UnsupportedOperationException()
    }

    private class FakePlanner(val candidates: List<PlanCandidate>, val features: PairFeatures) : TransitionPlanner {
        var calls = 0
        override fun plan(a: TrackRef, b: TrackRef, prefs: TransitionPrefs, seed: Long, previousStrategyId: String?): RankedPlans {
            calls++
            return RankedPlans(features, candidates)
        }
    }

    /** A planner whose ranking can change between plans, as when the listener switches style in between. */
    private class SwitchablePlanner(var candidates: List<PlanCandidate>, val features: PairFeatures) : TransitionPlanner {
        override fun plan(a: TrackRef, b: TrackRef, prefs: TransitionPrefs, seed: Long, previousStrategyId: String?): RankedPlans =
            RankedPlans(features, candidates)
    }

    /** Renders by strategy id: a supplied result, or a thrown failure. */
    private class FakeRenderer(val results: Map<String, () -> RenderedTransition>) : TransitionRenderer {
        val calls = ArrayList<String>()
        override fun render(a: TrackRef, b: TrackRef, candidate: PlanCandidate, features: PairFeatures, ctx: RenderContext): RenderedTransition {
            calls += candidate.strategy.id
            ctx.progress(0.5)
            val f = results[candidate.strategy.id] ?: throw IllegalStateException("render of ${candidate.strategy.id} failed")
            return f()
        }
    }

    private inner class FakeLiveFactory : LivePlanFactory {
        val requests = ArrayList<Long>()
        override fun plan(a: TrackRef, b: TrackRef, features: PairFeatures?, aNowFrame: Long, prefs: TransitionPrefs, fadeSecOverride: Double?): LivePlan {
            requests += aNowFrame
            val n = Math.round((fadeSecOverride ?: 2.0) * sr).toInt()
            return LivePlan(
                kind = "crossfade", aFromFrame = aNowFrame, aToFrame = aNowFrame + n, bFromFrame = b.analysis.trimStartFrame, outputFrames = n,
                nodes = listOf(
                    LiveNode.Gain(Deck.A, listOf(LivePoint(0, 1f, FadeLaw.EQUAL_POWER), LivePoint(n, 0f))),
                    LiveNode.Gain(Deck.B, listOf(LivePoint(0, 0f, FadeLaw.EQUAL_POWER), LivePoint(n, 1f))),
                ),
            )
        }
    }

    private fun coordinator(
        planner: TransitionPlanner,
        renderer: TransitionRenderer,
        live: LivePlanFactory,
        scope: kotlinx.coroutines.CoroutineScope,
        analyses: AnalysisService = FakeAnalyses(),
        gate: RenderGate = RenderGate.DEFAULT,
        prefs: TransitionPrefs = this.prefs,
        onSkip: ((TransitionSkip) -> Unit)? = null,
    ) = TransitionCoordinator(
        planner = planner, liveFactory = live, renderer = renderer, programBuilder = DefaultProgramBuilder(),
        player = player, analyses = analyses, gate = gate, limits = limits, clock = clock, scope = scope,
        prefsProvider = { prefs }, seed = SEED, onTransitionSkipped = onSkip,
    )

    private fun candidate(s: TransitionStrategy, p: TransitionPlan, score: Double) = PlanCandidate(s, Applicability.of(score, "test"), score, p)

    /** Applies whatever the coordinator submitted and renders [blocks] blocks of audio. */
    private fun pump(blocks: Int = 1) { repeat(blocks) { player.render(block, limits.blockFrames) } }

    private fun installedSegments(): List<Segment> = player.programSnapshot()

    // ------------------------------------------------------------------------------------------------ tests

    @Test
    fun renderReadyInTimeBecomesReadyAndIsInstalledWithReplaceTail() = runTest {
        val planner = FakePlanner(listOf(candidate(strategy, plan, 0.8)), features)
        val renderer = FakeRenderer(mapOf("crossfade" to { rendered }))
        val coord = coordinator(planner, renderer, FakeLiveFactory(), this)

        coord.onQueue(PlaybackContext.QUEUE, items(a, b), 0)
        advanceUntilIdle()

        assertEquals(CoordinatorState.Ready("crossfade"), coord.state.value[0])
        assertEquals(listOf("crossfade"), renderer.calls)
        val tail = player.commands.snapshot().filterIsInstance<EngineCommand.ReplaceTail>().last()
        val installed = tail.segments.filterIsInstance<Segment.Rendered>().single()
        assertEquals(plan.aExitFrame, installed.rendered.plan.aExitFrame)
        // The player adopts it: body A up to aExitFrame, the render, then B from bEntryFrame.
        pump(2)
        val segs = installedSegments()
        assertEquals(3, segs.size)
        assertEquals(plan.aExitFrame, (segs[0] as Segment.Body).toFrame)
        assertTrue(segs[1] is Segment.Rendered)
        assertEquals(plan.bEntryFrame, (segs[2] as Segment.Body).fromFrame)
        coord.shutdown()
    }

    @Test
    fun aRenderThatWouldArriveAfterTheDeadlineIsReplacedByALivePlan() = runTest {
        // The cursor is already inside the last 5 s of A: the deadline (aExitFrame − 5 s) has passed.
        val aExit = plan.aExitFrame
        player.submit(EngineCommand.SetProgram(PlaybackProgram(listOf(Segment.Body(a.trackRef, aExit - sr * 3L, a.analysis.trimEndFrame)))))
        pump(1)
        assertEquals("A", player.position.nowPlaying?.id)
        assertTrue(player.position.trackFrame > aExit - 5L * sr)

        val planner = FakePlanner(listOf(candidate(strategy, plan, 0.8)), features)
        val renderer = FakeRenderer(mapOf("crossfade" to { rendered }))
        val live = FakeLiveFactory()
        val coord = coordinator(planner, renderer, live, this)

        coord.onQueue(PlaybackContext.QUEUE, items(a, b), 0)
        advanceUntilIdle()

        val state = coord.state.value[0]
        assertTrue(state is CoordinatorState.Live, "expected a live fallback, was $state")
        assertEquals("crossfade", (state as CoordinatorState.Live).kind)
        assertTrue(state.reason.contains("deadline"), "reason was '${state.reason}'")
        assertTrue(renderer.calls.isEmpty(), "no render may be started past the deadline")
        assertEquals(1, live.requests.size)
        val segs = player.commands.snapshot().filterIsInstance<EngineCommand.ReplaceTail>().last().segments
        val liveSeg = segs.filterIsInstance<Segment.Live>().single()
        assertEquals(aExit, liveSeg.plan.aFromFrame)
        assertEquals(liveSeg.plan.aFromFrame, (segs.first() as Segment.Body).toFrame)
        assertTrue(coord.transitionLog.any { it.contains("live crossfade") })
        coord.shutdown()
    }

    @Test
    fun aRejectedRenderFallsToTheNextCandidateAndThenToALivePlan() = runTest {
        val first = StubStrategy("stubFirst", plan.copy(strategyId = "stubFirst"))
        val planner = FakePlanner(listOf(candidate(first, first.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED), 0.9), candidate(strategy, plan, 0.4)), features)
        // The first strategy renders but the gate refuses it; the second one throws.
        val renderer = FakeRenderer(mapOf("stubFirst" to { rendered }))
        val gate = object : RenderGate {
            var calls = 0
            override fun accept(rendered: RenderedTransition, input: dev.muisc.transitions.TransitionInput): Boolean = false
            override fun accept(rendered: RenderedTransition): Boolean { calls++; return false }
        }
        val live = FakeLiveFactory()
        val coord = coordinator(planner, renderer, live, this, gate = gate)

        coord.onQueue(PlaybackContext.QUEUE, items(a, b), 0)
        advanceUntilIdle()

        assertEquals(listOf("stubFirst", "crossfade"), renderer.calls, "both candidates must be attempted in order")
        assertEquals(1, gate.calls)
        val state = coord.state.value[0]
        assertTrue(state is CoordinatorState.Live, "expected a live fallback, was $state")
        assertTrue(coord.transitionLog.any { it.contains("stubFirst") && it.contains("rejected") }, coord.transitionLog.toString())
        assertTrue(coord.transitionLog.any { it.contains("crossfade") && it.contains("failed") }, coord.transitionLog.toString())
        assertTrue(player.commands.snapshot().filterIsInstance<EngineCommand.ReplaceTail>().last().segments.any { it is Segment.Live })
        coord.shutdown()
    }

    /**
     * A pair that comes back into the queue reuses its retained render only while the planner still ranks that
     * strategy. A strategy the planner has since dropped (a style excluding its technique, say) is not played from
     * the retained render: the edge is planned and rendered again.
     */
    @Test
    fun aRetainedRenderIsReusedOnlyWhileThePlannerStillRanksItsStrategy() = runTest {
        val other = StubStrategy("stubOther", plan.copy(strategyId = "stubOther"))
        val otherCandidate = candidate(other, other.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED), 0.6)
        val planner = SwitchablePlanner(listOf(candidate(strategy, plan, 0.8), otherCandidate), features)
        val renderer = FakeRenderer(mapOf("crossfade" to { rendered }, "stubOther" to { RenderedTransition(rendered.plan.copy(strategyId = "stubOther"), rendered.audio, rendered.markers, rendered.report) }))
        val coord = coordinator(planner, renderer, FakeLiveFactory(), this)

        coord.onQueue(PlaybackContext.QUEUE, items(a, b), 0)
        advanceUntilIdle()
        assertEquals(CoordinatorState.Ready("crossfade"), coord.state.value[0])

        // B leaves the queue and comes back: still ranked, so the retained render is reused without rendering.
        coord.onQueue(PlaybackContext.QUEUE, items(a), 0); advanceUntilIdle()
        coord.onQueue(PlaybackContext.QUEUE, items(a, b), 0); advanceUntilIdle()
        assertEquals(CoordinatorState.Ready("crossfade"), coord.state.value[0])
        assertEquals(listOf("crossfade"), renderer.calls)
        assertEquals(1, coord.transitionLog.count { it.contains("reusing retained crossfade") }, coord.transitionLog.toString())

        // The planner no longer ranks crossfade: the retained render must not come back.
        coord.onQueue(PlaybackContext.QUEUE, items(a), 0); advanceUntilIdle()
        planner.candidates = listOf(otherCandidate)
        coord.onQueue(PlaybackContext.QUEUE, items(a, b), 0); advanceUntilIdle()
        assertEquals(CoordinatorState.Ready("stubOther"), coord.state.value[0], coord.transitionLog.toString())
        assertEquals(listOf("crossfade", "stubOther"), renderer.calls)
        assertEquals(1, coord.transitionLog.count { it.contains("reusing retained") }, coord.transitionLog.toString())
        coord.shutdown()
    }

    @Test
    fun albumPlaybackIsGatedAndStrictSaverGatesEverything() = runTest {
        val planner = FakePlanner(listOf(candidate(strategy, plan, 0.8)), features)
        val renderer = FakeRenderer(mapOf("crossfade" to { rendered }))
        val coord = coordinator(planner, renderer, FakeLiveFactory(), this)

        coord.onQueue(PlaybackContext.ALBUM, items(a, b), 0)
        advanceUntilIdle()

        assertEquals(CoordinatorState.Gated("Album playback"), coord.state.value[0])
        assertEquals(0, planner.calls, "a gated edge is never planned")
        assertTrue(renderer.calls.isEmpty())
        // Album context also fixes one shared deck gain (that of the loudest track, i.e. the minimum dB) for every body.
        val lastGain = player.commands.snapshot().mapNotNull {
            when (it) { is EngineCommand.SetProgram -> it.sharedGainDb; is EngineCommand.ReplaceTail -> it.sharedGainDb; else -> null }
        }.last()
        assertEquals(minOf(gainDb(a), gainDb(b)), lastGain)
        assertTrue(gainDb(a) != gainDb(b), "the two tracks must differ in loudness for this to mean anything")
        // Two gapless bodies, no transition segment anywhere.
        pump(2)
        assertEquals(2, installedSegments().size)
        assertTrue(installedSegments().all { it is Segment.Body })

        // A playlist of the same two tracks is not gated, but STRICT_SAVER gates it again.
        coord.setPowerMode(PowerMode.STRICT_SAVER)
        coord.onQueue(PlaybackContext.PLAYLIST, items(a, b), 0)
        advanceUntilIdle()
        assertEquals(CoordinatorState.Gated("Power saver"), coord.state.value[0])
        coord.shutdown()
    }

    // ------------------------------------------------------------------------------------------------ room

    /** Ranks per pair of track ids, for queues longer than two. */
    private class PairPlanner(val byPair: Map<Pair<String, String>, List<PlanCandidate>>, val features: PairFeatures) : TransitionPlanner {
        override fun plan(a: TrackRef, b: TrackRef, prefs: TransitionPrefs, seed: Long, previousStrategyId: String?): RankedPlans =
            RankedPlans(features, byPair[a.id to b.id] ?: error("no candidates for ${a.id} → ${b.id}"))
    }

    private class MapAnalyses(tracks: List<SyntheticTrack>) : AnalysisService {
        private val byId = tracks.associate { it.trackRef.source to it.analysis }
        override suspend fun analysis(track: AudioSourceId, urgent: Boolean): TrackAnalysis = byId[track] ?: error("no analysis for $track")
    }

    /** A stub candidate for [from] → [to] leaving [from] at [aExit] and entering [to] at [bEntry] (one-second windows). */
    private fun stub(id: String, aExit: Long, bEntry: Long, score: Double): PlanCandidate {
        val p = plan.copy(strategyId = id, aExitFrame = aExit, bEntryFrame = bEntry, aWindow = FrameRange(aExit - sr, aExit + sr), bWindow = FrameRange(bEntry - sr, bEntry))
        return candidate(StubStrategy(id, p), p, score)
    }

    /** [rendered]'s audio carrying [c]'s plan, as the fake renderer's result for that candidate. */
    private fun renderOf(c: PlanCandidate): () -> RenderedTransition = { RenderedTransition(c.plan, rendered.audio, rendered.markers, rendered.report) }

    /**
     * B is the last track and the planner's favourite enters it so late that B would play less than a bar of
     * itself: the coordinator used to render and install it anyway (its bodies never pass through the program
     * builder's repair). The candidate that leaves B its body is rendered first.
     */
    @Test
    fun theCandidateThatLeavesTheIncomingTrackItsBodyIsRenderedFirst() = runTest {
        val late = stub("stubLate", plan.aExitFrame, b.analysis.trimEndFrame - sr / 4, 0.9)
        val planner = FakePlanner(listOf(late, candidate(strategy, plan, 0.4)), features)
        val renderer = FakeRenderer(mapOf("stubLate" to renderOf(late), "crossfade" to { rendered }))
        val coord = coordinator(planner, renderer, FakeLiveFactory(), this)

        coord.onQueue(PlaybackContext.QUEUE, items(a, b), 0)
        advanceUntilIdle()

        assertEquals(listOf("crossfade"), renderer.calls, coord.transitionLog.toString())
        assertEquals(CoordinatorState.Ready("crossfade"), coord.state.value[0])
        val bBody = player.commands.snapshot().filterIsInstance<EngineCommand.ReplaceTail>().last().segments.filterIsInstance<Segment.Body>().last()
        assertTrue(bBody.frames >= DefaultProgramBuilder().minBodyFrames(b.trackRef, PlaybackContext.QUEUE, prefs), "$bBody")
        assertTrue(coord.transitionLog.any { it.contains("room") && it.contains("stubLate") }, coord.transitionLog.toString())
        coord.shutdown()
    }

    /**
     * A short middle track: the favourite out of B leaves it before the transition into it has even handed it over.
     * The coordinator used to find that candidate's deadline long gone and play a live transition at once, so B
     * was heard for about a second. The candidate that leaves B a body is rendered instead, and B keeps it.
     */
    @Test
    fun aShortMiddleTrackKeepsItsBodyAndBothTransitions() = runTest {
        val c = PlayerFixtures.track(PlayerFixtures.song(128.0, 2, bars = 12, seed = 9), "C")
        val player3 = ProgramPlayer(sr, 2, limits, PlayerFixtures.streams(a, b, c), prefs, realtime = false)
        try {
            val bEntry = plan.bEntryFrame
            val early = stub("stubEarly", bEntry - sr, c.analysis.trimStartFrame + sr, 0.9) // leaves B before it was entered
            val roomy = stub("stubRoomy", b.analysis.trimEndFrame - 2L * sr, c.analysis.trimStartFrame + sr, 0.5)
            val planner = PairPlanner(mapOf(("A" to "B") to listOf(candidate(strategy, plan, 0.8)), ("B" to "C") to listOf(early, roomy)), features)
            val renderer = FakeRenderer(mapOf("crossfade" to { rendered }, "stubEarly" to renderOf(early), "stubRoomy" to renderOf(roomy)))
            val live = FakeLiveFactory()
            val coord = TransitionCoordinator(
                planner = planner, liveFactory = live, renderer = renderer, programBuilder = DefaultProgramBuilder(),
                player = player3, analyses = MapAnalyses(listOf(a, b, c)), gate = RenderGate.DEFAULT, limits = limits, clock = clock,
                scope = this, prefsProvider = { prefs }, seed = SEED,
            )
            coord.onQueue(PlaybackContext.QUEUE, items(a, b, c), 0)
            advanceUntilIdle()
            assertEquals(CoordinatorState.Ready("crossfade"), coord.state.value[0])

            // Play A and the crossfade until B has taken over, telling the coordinator as the host does.
            var guard = 0
            while (player3.position.nowPlaying?.id != "B" && guard++ < 10_000) {
                player3.render(block, limits.blockFrames)
                for (ev in player3.events.drain()) coord.onPlayerEvent(ev)
                advanceUntilIdle()
            }
            assertEquals("B", player3.position.nowPlaying?.id)
            advanceUntilIdle()

            assertEquals(CoordinatorState.Ready("stubRoomy"), coord.state.value[1], coord.transitionLog.toString())
            assertEquals(listOf("crossfade", "stubRoomy"), renderer.calls)
            assertTrue(live.requests.isEmpty(), "no live fallback: ${coord.transitionLog}")
            val segs = player3.commands.snapshot().filterIsInstance<EngineCommand.ReplaceTail>().last().segments
            val bBody = segs.filterIsInstance<Segment.Body>().first { it.track.id == "B" }
            assertEquals(bEntry, bBody.fromFrame)
            assertEquals(roomy.plan.aExitFrame, bBody.toFrame)
            assertTrue(bBody.frames >= DefaultProgramBuilder().minBodyFrames(b.trackRef, PlaybackContext.QUEUE, prefs), "$bBody")
            coord.shutdown()
        } finally {
            player3.close()
        }
    }

    /**
     * Looking one transition ahead: the favourite into the short B enters it so late that no plan out of B leaves B
     * a bar, so that next transition would be lost. The candidate that keeps both is the one rendered.
     *
     * The look-ahead needs the analysis of the track after B. On a queue that has just been installed it is not
     * there yet when the first evaluation runs, so this uses a long first track, whose render is not due until the
     * look-ahead is known (as it is for every transition after the first, whose analyses were resolved one track
     * earlier).
     */
    @Test
    fun theTransitionIntoAShortTrackLeavesRoomForTheOneOutOfIt() = runTest {
        val long = PlayerFixtures.track(PlayerFixtures.song(120.0, 0, bars = 64, seed = 11), "L")
        val c = PlayerFixtures.track(PlayerFixtures.song(128.0, 2, bars = 12, seed = 9), "C")
        val player3 = ProgramPlayer(sr, 2, limits, PlayerFixtures.streams(long, b, c), prefs, realtime = false)
        try {
            val aExit = long.analysis.trimEndFrame - 4L * sr
            val out = stub("stubOut", b.analysis.trimStartFrame + 6L * sr, c.analysis.trimStartFrame + sr, 0.9)
            val deep = stub("stubDeep", aExit, b.analysis.trimStartFrame + 10L * sr, 0.9) // B keeps a body alone, but not with stubOut
            val shallow = stub("stubShallow", aExit, b.analysis.trimStartFrame + 2L * sr, 0.5)
            val planner = PairPlanner(mapOf(("L" to "B") to listOf(deep, shallow), ("B" to "C") to listOf(out)), features)
            val renderer = FakeRenderer(mapOf("stubDeep" to renderOf(deep), "stubShallow" to renderOf(shallow)))
            val coord = TransitionCoordinator(
                planner = planner, liveFactory = FakeLiveFactory(), renderer = renderer, programBuilder = DefaultProgramBuilder(),
                player = player3, analyses = MapAnalyses(listOf(long, b, c)), gate = RenderGate.DEFAULT, limits = limits, clock = clock,
                scope = this, prefsProvider = { prefs }, seed = SEED,
            )
            coord.onQueue(PlaybackContext.QUEUE, items(long, b, c), 0)
            advanceUntilIdle()
            assertTrue(renderer.calls.isEmpty(), "more than 90 s left: nothing is rendered yet")
            assertEquals(CoordinatorState.Planned("stubShallow", 0.5), coord.state.value[0], coord.transitionLog.toString())

            // Half a minute before the exit the render is due.
            player3.submit(EngineCommand.Seek(aExit - 30L * sr))
            player3.render(block, limits.blockFrames)
            for (ev in player3.events.drain()) coord.onPlayerEvent(ev)
            advanceUntilIdle()

            assertEquals(listOf("stubShallow"), renderer.calls, coord.transitionLog.toString())
            assertEquals(CoordinatorState.Ready("stubShallow"), coord.state.value[0])
            assertTrue(coord.transitionLog.any { it.contains("room") && it.contains("stubDeep") }, coord.transitionLog.toString())
            coord.shutdown()
        } finally {
            player3.close()
        }
    }

    /** Renders [p] block by block, handing its events to [coord] as the host does, until [done]. */
    private fun kotlinx.coroutines.test.TestScope.playOn(p: ProgramPlayer, coord: TransitionCoordinator, what: String, done: () -> Boolean) {
        var n = 0
        while (!done()) {
            assertTrue(n++ < 40_000, "never reached: $what (position ${p.position}) ${coord.transitionLog}")
            p.render(block, limits.blockFrames)
            for (ev in p.events.drain()) coord.onPlayerEvent(ev)
            advanceUntilIdle()
        }
    }

    private fun bodyOf(p: ProgramPlayer, id: String): Segment.Body = p.programSnapshot().filterIsInstance<Segment.Body>().first { it.track.id == id }

    private fun playingBodyOf(p: ProgramPlayer, id: String): Boolean = (p.programSnapshot().getOrNull(p.position.segmentIndex) as? Segment.Body)?.track?.id == id

    /** L (2 min) → B (23 s) → C with the stubs of the scenario: into B late (10 s) or early (2 s); out of B early (6 s) or late. */
    private inner class ShortB {
        val long = PlayerFixtures.track(PlayerFixtures.song(120.0, 0, bars = 64, seed = 11), "L")
        val c = PlayerFixtures.track(PlayerFixtures.song(128.0, 2, bars = 12, seed = 9), "C")
        val player3 = ProgramPlayer(sr, 2, limits, PlayerFixtures.streams(long, b, c), prefs, realtime = false)
        val aExit = long.analysis.trimEndFrame - 4L * sr
        val out = stub("stubOut", b.analysis.trimStartFrame + 6L * sr, c.analysis.trimStartFrame + sr, 0.9)
        val outLate = stub("stubOutLate", b.analysis.trimEndFrame - 2L * sr, c.analysis.trimStartFrame + sr, 0.5)
        val deep = stub("stubDeep", aExit, b.analysis.trimStartFrame + 10L * sr, 0.9) // B keeps a body alone, or with stubOutLate, but not with stubOut
        val shallow = stub("stubShallow", aExit, b.analysis.trimStartFrame + 2L * sr, 0.5)
        val renderer = FakeRenderer(listOf(out, outLate, deep, shallow).associate { it.strategy.id to renderOf(it) })
        val live = FakeLiveFactory()
        val minBodyB = DefaultProgramBuilder().minBodyFrames(b.trackRef, PlaybackContext.QUEUE, prefs)

        fun coordinator(planner: TransitionPlanner, scope: kotlinx.coroutines.CoroutineScope) = TransitionCoordinator(
            planner = planner, liveFactory = live, renderer = renderer, programBuilder = DefaultProgramBuilder(),
            player = player3, analyses = MapAnalyses(listOf(long, b, c)), gate = RenderGate.DEFAULT, limits = limits, clock = clock,
            scope = scope, prefsProvider = { prefs }, seed = SEED,
        )

        /** Plays one block of what the coordinator installed, telling it the player's events as the host does. */
        fun kotlinx.coroutines.test.TestScope.settle(coord: TransitionCoordinator) {
            player3.render(block, limits.blockFrames)
            for (ev in player3.events.drain()) coord.onPlayerEvent(ev)
            advanceUntilIdle()
        }

        /**
         * What the app's install does for "play from here" (Previous, a tap in the queue): the player is emptied at
         * once, then the coordinator gets an empty queue and the queue again, from [index]; with [keepQueue] the
         * empty queue is left out, so the coordinator keeps the edges it has.
         */
        fun kotlinx.coroutines.test.TestScope.playFrom(coord: TransitionCoordinator, tracks: List<SyntheticTrack>, index: Int, keepQueue: Boolean = false) {
            player3.submit(EngineCommand.SetProgram(PlaybackProgram(emptyList())))
            player3.render(block, limits.blockFrames)
            player3.events.drain()
            if (!keepQueue) coord.onQueue(PlaybackContext.QUEUE, emptyList(), 0)
            coord.onQueue(PlaybackContext.QUEUE, items(*tracks.toTypedArray()), index)
            advanceUntilIdle()
        }

        /** Half a minute before L's exit: the render into B is due. */
        fun kotlinx.coroutines.test.TestScope.nearLsExit(coord: TransitionCoordinator) {
            player3.submit(EngineCommand.Seek(aExit - 30L * sr))
            settle(coord)
        }
    }

    /**
     * The listener starts at the short B, so B → C renders its favourite (out of B at 6 s) at once, and the render
     * is retained. Then Previous (the host empties the program and the queue, and installs it again at L). The
     * look-ahead for L → B used to judge B's room against every plan B → C could choose, so the deep entry (10 s)
     * "fitted" through a late exit that would never play, while the retained 6 s exit was what B → C reused: B got
     * a zero-length body, a live crossfade "ready too late", and a jump back inside B. The look-ahead now plans
     * around the render B → C will actually play.
     */
    @Test
    fun theLookAheadPlansAroundTheRetainedRenderOutOfTheShortTrack() = runTest {
        val s = ShortB()
        try {
            val planner = PairPlanner(mapOf(("L" to "B") to listOf(s.deep, s.shallow), ("B" to "C") to listOf(s.out, s.outLate)), features)
            val coord = s.coordinator(planner, this)
            coord.onQueue(PlaybackContext.QUEUE, items(s.long, b, s.c), 1)
            advanceUntilIdle()
            with(s) { settle(coord) }
            assertEquals(listOf("stubOut"), s.renderer.calls, coord.transitionLog.toString())

            // Previous, as the app does it: an empty program and queue, then the queue again from L.
            with(s) { playFrom(coord, listOf(s.long, b, s.c), 0) }
            with(s) { nearLsExit(coord) }

            assertEquals(listOf("stubOut", "stubShallow"), s.renderer.calls, coord.transitionLog.toString())
            assertTrue(coord.transitionLog.any { "reusing retained stubOut" in it }, coord.transitionLog.toString())
            assertTrue(coord.transitionLog.any { it.contains("room") && it.contains("stubDeep") && it.contains("costs_next") }, coord.transitionLog.toString())

            playOn(s.player3, coord, "B's body") { playingBodyOf(s.player3, "B") }
            val bBody = bodyOf(s.player3, "B")
            assertEquals(s.shallow.plan.bEntryFrame, bBody.fromFrame)
            assertEquals(s.out.plan.aExitFrame, bBody.toFrame)
            assertTrue(bBody.frames >= s.minBodyB, "$bBody")
            assertEquals(CoordinatorState.Ready("stubOut"), coord.state.value[1], coord.transitionLog.toString())
            assertTrue(s.live.requests.isEmpty(), "no live fallback: ${coord.transitionLog}")
            coord.shutdown()
        } finally {
            s.player3.close()
        }
    }

    /**
     * As above, but the coordinator keeps the queue (the same items again with a new index, no empty queue in
     * between): B → C stays installed, and the look-ahead plans around it.
     */
    @Test
    fun theLookAheadPlansAroundTheInstalledRenderOutOfTheShortTrack() = runTest {
        val s = ShortB()
        try {
            val planner = PairPlanner(mapOf(("L" to "B") to listOf(s.deep, s.shallow), ("B" to "C") to listOf(s.out, s.outLate)), features)
            val coord = s.coordinator(planner, this)
            coord.onQueue(PlaybackContext.QUEUE, items(s.long, b, s.c), 1)
            advanceUntilIdle()
            with(s) { settle(coord) }
            assertEquals(CoordinatorState.Ready("stubOut"), coord.state.value[1])

            with(s) { playFrom(coord, listOf(s.long, b, s.c), 0, keepQueue = true) }
            with(s) { nearLsExit(coord) }

            assertEquals(listOf("stubOut", "stubShallow"), s.renderer.calls, coord.transitionLog.toString())
            val segs = s.player3.commands.snapshot().filterIsInstance<EngineCommand.ReplaceTail>().last().segments
            val bBody = segs.filterIsInstance<Segment.Body>().first { it.track.id == "B" }
            assertEquals(s.shallow.plan.bEntryFrame, bBody.fromFrame)
            assertEquals(s.out.plan.aExitFrame, bBody.toFrame)
            assertTrue(bBody.frames >= s.minBodyB, "$bBody")
            coord.shutdown()
        } finally {
            s.player3.close()
        }
    }

    /**
     * B → C was rendered (out of B at 6 s) while B played from its start. Later L → B enters B at 10 s and only
     * then is C queued after B: the retained B → C render is not reused, since it would leave B nothing between the
     * two transitions, and B, with no candidate out of it that leaves it a body, plays on into C without a transition.
     */
    @Test
    fun aRetainedRenderThatWouldLeaveTheTrackNoBodyIsNotReused() = runTest {
        val s = ShortB()
        try {
            val planner = PairPlanner(mapOf(("L" to "B") to listOf(s.deep, s.shallow), ("B" to "C") to listOf(s.out)), features)
            val coord = s.coordinator(planner, this)
            coord.onQueue(PlaybackContext.QUEUE, items(b, s.c), 0)
            advanceUntilIdle()
            with(s) { settle(coord) }
            assertEquals(listOf("stubOut"), s.renderer.calls, coord.transitionLog.toString())

            // L, B: B is the last track, so the deep entry fits and is rendered.
            with(s) { playFrom(coord, listOf(s.long, b), 0) }
            with(s) { nearLsExit(coord) }
            assertEquals(CoordinatorState.Ready("stubDeep"), coord.state.value[0], coord.transitionLog.toString())

            // C is queued after B.
            coord.onQueue(PlaybackContext.QUEUE, items(s.long, b, s.c), 0)
            advanceUntilIdle()
            assertTrue(coord.transitionLog.none { "reusing retained" in it }, coord.transitionLog.toString())
            assertEquals(CoordinatorState.Planned("stubOut", 0.9), coord.state.value[1], coord.transitionLog.toString())

            playOn(s.player3, coord, "B's body") { playingBodyOf(s.player3, "B") }
            assertEquals(CoordinatorState.Gated(TransitionCoordinator.NO_ROOM), coord.state.value[1], coord.transitionLog.toString())
            assertEquals(listOf("stubOut", "stubDeep"), s.renderer.calls)
            val bBody = bodyOf(s.player3, "B")
            assertEquals(s.deep.plan.bEntryFrame, bBody.fromFrame)
            assertEquals(b.analysis.trimEndFrame, bBody.toFrame)
            assertTrue(s.live.requests.isEmpty(), "no live fallback: ${coord.transitionLog}")
            coord.shutdown()
        } finally {
            s.player3.close()
        }
    }

    /**
     * The user's pick into B (pinned: it stays first although it costs B's next transition) enters B at 10 s, but
     * the transition out of B already holds a plan that leaves B at 6 s: a retained render reused (the queue
     * played again) or a live plan installed on an earlier visit (low memory, and the queue kept). When the pick is
     * installed, that transition out of B is dropped and chosen again when B plays: here nothing else fits, so B
     * plays on into C.
     */
    private fun kotlinx.coroutines.test.TestScope.theTransitionOutOfBIsDroppedWhenThePickIntoBLeavesItNoRoom(outOfB: String) {
        val s = ShortB()
        try {
            val planner = PairPlanner(mapOf(("L" to "B") to listOf(s.deep.copy(pinned = true), s.shallow), ("B" to "C") to listOf(s.out)), features)
            val coord = TransitionCoordinator(
                planner = planner, liveFactory = s.live, renderer = s.renderer, programBuilder = DefaultProgramBuilder(),
                player = s.player3, analyses = MapAnalyses(listOf(s.long, b, s.c)), gate = RenderGate.DEFAULT, limits = limits, clock = clock,
                scope = this, prefsProvider = { prefs }, seed = SEED,
            )
            coord.onQueue(PlaybackContext.QUEUE, items(s.long, b, s.c), 1)
            advanceUntilIdle()
            with(s) { settle(coord) }
            if (outOfB == "live") {
                // Low memory while B is current: B → C becomes a live crossfade at its planned exit (6 s).
                coord.onTrimMemory(TransitionCoordinator.TRIM_MEMORY_RUNNING_LOW)
                advanceUntilIdle()
                assertEquals(CoordinatorState.Live("crossfade", "low memory"), coord.state.value[1], coord.transitionLog.toString())
                with(s) { playFrom(coord, listOf(s.long, b, s.c), 0, keepQueue = true) }
            } else {
                assertEquals(CoordinatorState.Ready("stubOut"), coord.state.value[1], coord.transitionLog.toString())
                with(s) { playFrom(coord, listOf(s.long, b, s.c), 0) }
            }
            val livesBefore = s.live.requests.size
            with(s) { nearLsExit(coord) }

            assertEquals(CoordinatorState.Ready("stubDeep"), coord.state.value[0], coord.transitionLog.toString())
            val dropped = if (outOfB == "live") "live crossfade dropped" else "stubOut dropped"
            assertTrue(coord.transitionLog.any { dropped in it }, coord.transitionLog.toString())
            playOn(s.player3, coord, "B's body") { playingBodyOf(s.player3, "B") }
            assertEquals(CoordinatorState.Gated(TransitionCoordinator.NO_ROOM), coord.state.value[1], coord.transitionLog.toString())
            val bBody = bodyOf(s.player3, "B")
            assertEquals(s.deep.plan.bEntryFrame, bBody.fromFrame)
            assertEquals(b.analysis.trimEndFrame, bBody.toFrame)
            assertEquals(livesBefore, s.live.requests.size, "no live fallback: ${coord.transitionLog}")
            coord.shutdown()
        } finally {
            s.player3.close()
        }
    }

    @Test
    fun aRetainedRenderOutOfBIsDroppedWhenThePickIntoBLeavesItNoRoom() = runTest { theTransitionOutOfBIsDroppedWhenThePickIntoBLeavesItNoRoom("retained") }

    @Test
    fun anInstalledLivePlanOutOfBIsDroppedWhenThePickIntoBLeavesItNoRoom() = runTest { theTransitionOutOfBIsDroppedWhenThePickIntoBLeavesItNoRoom("live") }

    /**
     * Every candidate enters the last track B within its last bar. The coordinator used to render and install the
     * favourite anyway (it never goes through the program builder's repair), so B played a quarter of a second.
     * Nothing is rendered now: the pair plays body to body, as the builder would play it.
     */
    @Test
    fun whenNoCandidateLeavesATrackItsBodyThePairPlaysWithoutATransition() = runTest {
        val late = stub("stubLate", plan.aExitFrame, b.analysis.trimEndFrame - sr / 4, 0.9)
        val late2 = stub("stubLate2", plan.aExitFrame, b.analysis.trimEndFrame - sr / 2, 0.4)
        val renderer = FakeRenderer(mapOf("stubLate" to renderOf(late), "stubLate2" to renderOf(late2)))
        val live = FakeLiveFactory()
        val coord = coordinator(FakePlanner(listOf(late, late2), features), renderer, live, this)

        coord.onQueue(PlaybackContext.QUEUE, items(a, b), 0)
        advanceUntilIdle()

        assertEquals(emptyList(), renderer.calls, coord.transitionLog.toString())
        assertEquals(CoordinatorState.Gated(TransitionCoordinator.NO_ROOM), coord.state.value[0])
        assertTrue(coord.transitionLog.any { "no room" in it && "stubLate" in it }, coord.transitionLog.toString())
        pump(2)
        val segs = installedSegments()
        assertEquals(listOf(Segment.Body(a.trackRef, a.analysis.trimStartFrame, a.analysis.trimEndFrame), Segment.Body(b.trackRef, b.analysis.trimStartFrame, b.analysis.trimEndFrame)), segs)
        // Played to its end, the pair stays without a transition: no deadline, no live fallback.
        player.submit(EngineCommand.Seek(plan.aExitFrame - sr))
        step(coord)
        playUntil(coord, "B") { player.position.nowPlaying?.id == "B" }
        assertTrue(live.requests.isEmpty(), coord.transitionLog.toString())
        assertTrue(installedSegments().none { it is Segment.Rendered || it is Segment.Live }, "${installedSegments()}")
        coord.shutdown()
    }

    /**
     * Power saver allows only live-capable strategies; here the only one starves B, so the pair has no transition.
     * Back to normal power, the pair is planned again and the candidate that fits is rendered.
     */
    @Test
    fun aPairWithoutRoomIsPlannedAgainWhenThePowerModeAllowsMore() = runTest {
        val fits = stub("stubFits", plan.aExitFrame, plan.bEntryFrame, 0.9)
        val starves = stub("crossfade", plan.aExitFrame, b.analysis.trimEndFrame - sr / 4, 0.4)
        val renderer = FakeRenderer(mapOf("stubFits" to renderOf(fits), "crossfade" to renderOf(starves)))
        val coord = coordinator(FakePlanner(listOf(fits, starves), features), renderer, FakeLiveFactory(), this)
        coord.setPowerMode(PowerMode.SAVER)
        coord.onQueue(PlaybackContext.QUEUE, items(a, b), 0)
        advanceUntilIdle()
        assertEquals(CoordinatorState.Gated(TransitionCoordinator.NO_ROOM), coord.state.value[0], coord.transitionLog.toString())
        assertEquals(emptyList(), renderer.calls)

        coord.setPowerMode(PowerMode.NORMAL)
        advanceUntilIdle()
        assertEquals(CoordinatorState.Ready("stubFits"), coord.state.value[0], coord.transitionLog.toString())
        assertEquals(listOf("stubFits"), renderer.calls)
        coord.shutdown()
    }

    // ------------------------------------------------------------------------------------------------ pins and room

    /** A strategy with one fixed plan per outgoing track (by analysis identity), blocked for every other pair. */
    private class PairStub(override val id: String, private val fit: Double, private val plans: Map<String, TransitionPlan>) : TransitionStrategy {
        override val displayName: String get() = id
        override val description: String get() = "stub"
        override val params: List<ParamSpec> get() = emptyList()
        override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs) =
            if (a.identity in plans) Applicability.of(fit, "stub") else Applicability.blocked("not this pair")
        override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long) = plans.getValue(a.identity)
        override fun render(input: dev.muisc.transitions.TransitionInput, ctx: RenderContext): RenderedTransition = throw UnsupportedOperationException()
    }

    /**
     * The real planner with [pins]: L → B has the planner's favourite stubShallow and the user's stubDeep, which
     * enters B so late that B's only way out (stubOut, at 6 s) cannot follow it. Room order used to put stubShallow
     * first anyway, so the user's pick never played; it is what plays now, and B, with no transition out of it that
     * leaves it a body, plays on into C without one.
     */
    private fun kotlinx.coroutines.test.TestScope.thePinnedCandidatePlays(pins: (ShortB) -> dev.muisc.transitions.custom.PinLookup) {
        val s = ShortB()
        try {
            val registry = object : dev.muisc.transitions.StrategyRegistry {
                override val strategies: List<TransitionStrategy> = listOf(
                    PairStub("stubShallow", 0.9, mapOf(s.long.analysis.identity to s.shallow.plan)),
                    PairStub("stubDeep", 0.3, mapOf(s.long.analysis.identity to s.deep.plan)),
                    PairStub("stubOut", 0.9, mapOf(b.analysis.identity to s.out.plan)),
                )
                override val modifiers: List<dev.muisc.transitions.TransitionModifier> = emptyList()
            }
            val unpinned = dev.muisc.transitions.planner.DefaultTransitionPlanner(registry)
            assertEquals("stubShallow", unpinned.plan(s.long.trackRef, b.trackRef, prefs).best.strategy.id, "the planner's own favourite")
            val planner = dev.muisc.transitions.planner.DefaultTransitionPlanner(
                registry, customization = dev.muisc.transitions.custom.PlannerCustomization(pins = pins(s)),
            )
            assertTrue(planner.plan(s.long.trackRef, b.trackRef, prefs).best.let { it.pinned && it.strategy.id == "stubDeep" })
            val coord = s.coordinator(planner, this)
            coord.onQueue(PlaybackContext.PLAYLIST, items(s.long, b, s.c), 0)
            advanceUntilIdle()
            with(s) { nearLsExit(coord) }

            assertEquals(listOf("stubDeep"), s.renderer.calls, coord.transitionLog.toString())
            assertEquals(CoordinatorState.Ready("stubDeep"), coord.state.value[0])
            playOn(s.player3, coord, "B's body") { playingBodyOf(s.player3, "B") }
            assertEquals(CoordinatorState.Gated(TransitionCoordinator.NO_ROOM), coord.state.value[1], coord.transitionLog.toString())
            val bBody = bodyOf(s.player3, "B")
            assertEquals(s.deep.plan.bEntryFrame, bBody.fromFrame)
            assertEquals(b.analysis.trimEndFrame, bBody.toFrame)
            assertTrue(s.live.requests.isEmpty(), "no live fallback: ${coord.transitionLog}")
            coord.shutdown()
        } finally {
            s.player3.close()
        }
    }

    /** The one-off "Next transition" pick and the CLI's `--preset` are session pins: one pin for every pair. */
    @Test
    fun aSessionPinKeepsFirstPlaceWhenItCostsOnlyTheNextTransition() = runTest {
        thePinnedCandidatePlays { dev.muisc.transitions.custom.PinLookup.always(dev.muisc.transitions.custom.PairPin(dev.muisc.transitions.custom.PairPin.ANY, dev.muisc.transitions.custom.PairPin.ANY, "stubDeep", note = "your pick for this transition")) }
    }

    @Test
    fun aStoredPinKeepsFirstPlaceWhenItCostsOnlyTheNextTransition() = runTest {
        val dir = kotlin.io.path.createTempDirectory("muisc-pins").toFile()
        try {
            val store = dev.muisc.transitions.custom.FilePinStore(java.io.File(dir, "pins.json"))
            thePinnedCandidatePlays { s ->
                store.set(dev.muisc.transitions.custom.PairPin(s.long.analysis.identity, b.analysis.identity, "stubDeep"))
                dev.muisc.transitions.custom.FilePinStore(java.io.File(dir, "pins.json"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------------------------------------ skips as feedback

    /** Renders one block, hands the player's events to [coord] and lets it act on them. */
    private fun kotlinx.coroutines.test.TestScope.step(coord: TransitionCoordinator) {
        pump(1)
        for (ev in player.events.drain()) coord.onPlayerEvent(ev)
        advanceUntilIdle()
    }

    private fun kotlinx.coroutines.test.TestScope.playUntil(coord: TransitionCoordinator, what: String, done: () -> Boolean) {
        var n = 0
        while (!done()) {
            assertTrue(n++ < 20_000, "never reached: $what (position ${player.position})")
            step(coord)
        }
    }

    private fun segmentPlaying(): Segment? = installedSegments().getOrNull(player.position.segmentIndex)

    /** Empties the player between two scenarios and drops its events, so the next coordinator starts clean. */
    private fun stopAndForget() {
        player.submit(EngineCommand.SetProgram(PlaybackProgram(emptyList())))
        pump(1)
        player.events.drain()
    }

    /** Queue [tracks] in [context] with a rendered crossfade, and play A up to [secondsBeforeExit] before its exit. */
    private fun kotlinx.coroutines.test.TestScope.readyToTransition(
        context: PlaybackContext,
        skips: MutableList<TransitionSkip>,
        next: SyntheticTrack = b,
        renderer: TransitionRenderer? = null,
        prefs: TransitionPrefs = this@TransitionCoordinatorTest.prefs,
    ): TransitionCoordinator {
        streams[next.trackRef.source] = next.audio
        val f = PlayerFixtures.features(a, next, prefs)
        val r = if (next === b) rendered else PlayerFixtures.crossfadeRender(a, next, prefs, fadeSec = 3.0, seed = SEED)
        val planner = FakePlanner(listOf(candidate(strategy, r.plan, 0.8)), f)
        val coord = coordinator(planner, renderer ?: FakeRenderer(mapOf("crossfade" to { r })), FakeLiveFactory(), this,
            analyses = FakeAnalyses(listOf(a, next)), prefs = prefs, onSkip = { skips += it })
        coord.onQueue(context, items(a, next), 0)
        advanceUntilIdle()
        step(coord)
        player.submit(EngineCommand.Seek(r.plan.aExitFrame - sr))
        step(coord)
        return coord
    }

    @Test
    fun aSkipDuringARenderedTransitionIsReportedOnceAsWeakFeedback() = runTest {
        val skips = ArrayList<TransitionSkip>()
        val coord = readyToTransition(PlaybackContext.PLAYLIST, skips)
        assertEquals(CoordinatorState.Ready("crossfade"), coord.state.value[0])
        playUntil(coord, "the transition") { segmentPlaying() is Segment.Rendered }

        coord.onUserSkip()
        advanceUntilIdle()
        val s = skips.single()
        assertEquals(TransitionSkip("A", "B", "crossfade", features, TransitionSkip.Phase.DURING, 0.0), s)

        // The skip landed in B's body: skipping again right away is the same transition, still one report.
        playUntil(coord, "B's body") { (segmentPlaying() as? Segment.Body)?.track?.id == "B" }
        coord.onUserSkip()
        advanceUntilIdle()
        assertEquals(1, skips.size, skips.toString())
        coord.shutdown()
    }

    @Test
    fun theSameTransitionPlayedAgainIsNotReportedTwice() = runTest {
        val skips = ArrayList<TransitionSkip>()
        val coord = readyToTransition(PlaybackContext.PLAYLIST, skips)
        playUntil(coord, "the transition") { segmentPlaying() is Segment.Rendered }
        coord.onUserSkip(); advanceUntilIdle()
        assertEquals(1, skips.size)
        step(coord)
        // The listener goes back and plays the pair again (the retained render is reused) and skips it again.
        coord.onQueue(PlaybackContext.PLAYLIST, emptyList(), 0); advanceUntilIdle()
        coord.onQueue(PlaybackContext.PLAYLIST, items(a, b), 0); advanceUntilIdle()
        step(coord)
        player.submit(EngineCommand.Seek(plan.aExitFrame - sr)); step(coord)
        playUntil(coord, "the transition again") { segmentPlaying() is Segment.Rendered }
        coord.onUserSkip(); advanceUntilIdle()
        assertEquals(1, skips.size, skips.toString())
        coord.shutdown()
    }

    @Test
    fun aSkipJustAfterBTakesOverCountsAndTwoMinutesLaterDoesNot() = runTest {
        val long = PlayerFixtures.track(PlayerFixtures.song(124.0, 5, bars = 72, seed = 9), "L", albumId = "album2")
        run {
            val skips = ArrayList<TransitionSkip>()
            val coord = readyToTransition(PlaybackContext.SHUFFLE, skips, next = long)
            playUntil(coord, "L's body") { (segmentPlaying() as? Segment.Body)?.track?.id == "L" }
            val body = segmentPlaying() as Segment.Body
            player.submit(EngineCommand.Seek(body.fromFrame + 5L * sr))
            step(coord)
            coord.onUserSkip(); advanceUntilIdle()
            val s = skips.single()
            assertEquals(TransitionSkip.Phase.JUST_AFTER, s.phase)
            assertEquals("L", s.bId)
            assertTrue(s.secondsAfter in 5.0..6.0, "${s.secondsAfter}")
            coord.shutdown()
        }
        stopAndForget()
        run {
            val skips = ArrayList<TransitionSkip>()
            val coord = readyToTransition(PlaybackContext.SHUFFLE, skips, next = long)
            playUntil(coord, "L's body") { (segmentPlaying() as? Segment.Body)?.track?.id == "L" }
            val body = segmentPlaying() as Segment.Body
            // Two minutes into B: the skip is about the song, not the transition.
            player.submit(EngineCommand.Seek(body.fromFrame + 120L * sr))
            step(coord)
            assertTrue(player.position.trackFrame - body.fromFrame >= 120L * sr, "${player.position}")
            coord.onUserSkip(); advanceUntilIdle()
            assertEquals(emptyList(), skips)
            coord.shutdown()
        }
    }

    /**
     * Repeat ALL, Next on the last song: the host calls [TransitionCoordinator.noteUserSkip] and then empties and
     * re-installs the program itself, on its own thread. The audio thread may render the empty program before the
     * coordinator's thread gets to run, so the position must be the one at the call, not the one the coordinator
     * finds later.
     */
    @Test
    fun aWrapSkipCountsEvenWhenTheHostEmptiesTheProgramBeforeTheCoordinatorRuns() = runTest {
        val skips = ArrayList<TransitionSkip>()
        val coord = readyToTransition(PlaybackContext.PLAYLIST, skips)
        playUntil(coord, "the transition") { segmentPlaying() is Segment.Rendered }

        coord.noteUserSkip()
        // What EngineControllerImpl.next() does right after, before the coordinator's thread has run.
        player.submit(EngineCommand.SetProgram(PlaybackProgram(emptyList())))
        pump(1)
        assertEquals(-1, player.position.segmentIndex, "${player.position}")
        advanceUntilIdle()

        assertEquals(listOf(TransitionSkip("A", "B", "crossfade", features, TransitionSkip.Phase.DURING, 0.0)), skips)
        coord.shutdown()
    }

    @Test
    fun albumPlaybackNeverCountsEvenWhenItTransitions() = runTest {
        val skips = ArrayList<TransitionSkip>()
        // The listener allowed transitions inside albums: the album really transitions, and a skip still never counts.
        val coord = readyToTransition(PlaybackContext.ALBUM, skips, prefs = prefs.copy(allowInAlbums = true))
        assertEquals(CoordinatorState.Ready("crossfade"), coord.state.value[0])
        playUntil(coord, "the transition") { segmentPlaying() is Segment.Rendered }
        coord.onUserSkip(); advanceUntilIdle()
        assertEquals(emptyList(), skips)
        coord.shutdown()
    }

    @Test
    fun aSkipDuringALiveFallbackOrAfterADjSkipDoesNotCount() = runTest {
        run {
            val skips = ArrayList<TransitionSkip>()
            // Every render fails: the edge falls back to a live crossfade, which is not the planner's choice.
            val coord = readyToTransition(PlaybackContext.PLAYLIST, skips, renderer = FakeRenderer(emptyMap()))
            assertTrue(coord.state.value[0] is CoordinatorState.Live, "${coord.state.value[0]}")
            playUntil(coord, "the live transition") { segmentPlaying() is Segment.Live }
            coord.onUserSkip(); advanceUntilIdle()
            assertEquals(emptyList(), skips)
            coord.shutdown()
        }
        stopAndForget()
        run {
            val skips = ArrayList<TransitionSkip>()
            val coord = coordinator(FakePlanner(listOf(candidate(strategy, plan, 0.8)), features), FakeRenderer(mapOf("crossfade" to { rendered })),
                FakeLiveFactory(), this, onSkip = { skips += it })
            coord.onQueue(PlaybackContext.PLAYLIST, items(a, b), 0); advanceUntilIdle()
            step(coord)
            // Early in A with the render installed: a DJ skip jumps to 4 bars before the exit. Skipping A is not
            // feedback, nor is skipping the transition the listener asked for.
            coord.onUserSkip(); advanceUntilIdle()
            assertTrue(coord.transitionLog.any { "DJ skip" in it }, coord.transitionLog.toString())
            playUntil(coord, "the transition") { segmentPlaying() is Segment.Rendered }
            coord.onUserSkip(); advanceUntilIdle()
            assertEquals(emptyList(), skips)
            coord.shutdown()
        }
    }

    private fun gainDb(t: SyntheticTrack): Float = dev.muisc.transitions.core.DeckGain.of(t.analysis, prefs)

    private companion object { const val SEED = 1L }
}
