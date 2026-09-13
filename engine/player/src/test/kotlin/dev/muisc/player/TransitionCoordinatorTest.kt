package dev.muisc.player

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioSourceId
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.DefaultProgramBuilder
import dev.muisc.transitions.FadeLaw
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

    private inner class FakeAnalyses : AnalysisService {
        var urgentCalls = 0
        private val byId = listOf(a, b).associate { it.trackRef.source to it.analysis }
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
    ) = TransitionCoordinator(
        planner = planner, liveFactory = live, renderer = renderer, programBuilder = DefaultProgramBuilder(),
        player = player, analyses = analyses, gate = gate, limits = limits, clock = clock, scope = scope,
        prefsProvider = { prefs }, seed = SEED,
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

    private fun gainDb(t: SyntheticTrack): Float = dev.muisc.transitions.core.DeckGain.of(t.analysis, prefs)

    private companion object { const val SEED = 1L }
}
