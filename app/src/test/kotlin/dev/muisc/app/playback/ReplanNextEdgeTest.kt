package dev.muisc.app.playback

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.EngineStreamFactory
import dev.muisc.audio.PcmStream
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.player.AnalysisService
import dev.muisc.player.CoordinatorState
import dev.muisc.player.EngineLimits
import dev.muisc.player.ProgramPlayer
import dev.muisc.player.QueueItem
import dev.muisc.player.RenderGate
import dev.muisc.player.SystemClock
import dev.muisc.player.TransitionCoordinator
import dev.muisc.transitions.DefaultProgramBuilder
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionRenderer
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.live.DefaultLivePlanFactory
import dev.muisc.transitions.synthetic.SyntheticTracks
import java.nio.file.Files
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The mechanism behind "use this technique for the next transition", run against the REAL [TransitionCoordinator]
 * and the real planner of [DjCustomization]: a session pin alone does not change an edge the coordinator has
 * already planned (it keys edges by the pair's engine ids and keeps them across queue re-sends), and giving the next
 * song a new engine-id generation ([QueueManager.queueItems]) makes it plan that edge again — with the pin.
 *
 * The songs are slow enough (80 BPM, 48 bars) that no render is due yet, so the edge stays `Planned` and shows
 * exactly which strategy the coordinator chose.
 */
class ReplanNextEdgeTest {

    private val prefs = TransitionPrefs()
    private val a: TrackRef = SyntheticTracks.trackRef(SyntheticSong(bpm = 80.0, tonic = 9, bars = 48, introBars = 8, outroBars = 8)).trackRef
    private val b: TrackRef = SyntheticTracks.trackRef(SyntheticSong(bpm = 80.0, tonic = 4, bars = 48, introBars = 8, outroBars = 8, seed = 11)).trackRef

    private class Analyses(private val byId: Map<AudioSourceId, TrackAnalysis>) : AnalysisService {
        override suspend fun analysis(track: AudioSourceId, urgent: Boolean): TrackAnalysis = byId.getValue(track)
    }

    /** Nothing is played in this test, so no stream is ever opened. */
    private object NoStreams : EngineStreamFactory {
        override fun open(source: AudioSourceId, sampleRate: Int, channels: Int): PcmStream = throw UnsupportedOperationException("not played in this test")
    }

    private class RecordingRenderer : TransitionRenderer {
        val calls = ArrayList<String>()
        override fun render(a: TrackRef, b: TrackRef, candidate: PlanCandidate, features: PairFeatures, ctx: RenderContext): RenderedTransition {
            calls += candidate.strategy.id
            throw IllegalStateException("no render is due in this test")
        }
    }

    private fun item(t: TrackRef, id: String) = QueueItem(t.source, t.albumId, t.title, t.artist, id)

    @Test
    fun aNewGenerationForTheNextSongReplansTheEdgeWithTheSessionPin() = runTest {
        val dj = DjCustomization(Files.createTempDirectory("muisc-replan").toFile())
        val player = ProgramPlayer(prefs.sampleRate, prefs.channels, EngineLimits.DESKTOP, NoStreams, prefs, realtime = false)
        val renderer = RecordingRenderer()
        val coordinator = TransitionCoordinator(
            planner = dj.planner, liveFactory = DefaultLivePlanFactory(), renderer = renderer, programBuilder = DefaultProgramBuilder(),
            player = player, analyses = Analyses(mapOf(a.source to a.analysis, b.source to b.analysis)), gate = RenderGate.DEFAULT,
            limits = EngineLimits.DESKTOP, clock = SystemClock, scope = this, prefsProvider = { prefs },
        )
        try {
            coordinator.onQueue(PlaybackContext.PLAYLIST, listOf(item(a, "1"), item(b, "2")), 0)
            advanceUntilIdle()
            val first = coordinator.state.value[0]
            assertTrue(first is CoordinatorState.Planned, "expected a planned edge, was $first")
            val ranked = dj.planner.planExplained(a, b, prefs).ranked
            assertEquals(ranked.best.strategy.id, first.strategyId)
            val chosen = ranked.candidates.last().strategy.id
            assertNotEquals(first.strategyId, chosen)

            // The one-off pick for this pair.
            dj.sessionPins.set(SessionOverride(1L, 2L, setOf(a.analysis.identity), setOf(b.analysis.identity), PairPin(a.analysis.identity, b.analysis.identity, chosen)))

            // Re-sending the same queue keeps the planned edge: the pin alone is not enough.
            coordinator.onQueue(PlaybackContext.PLAYLIST, listOf(item(a, "1"), item(b, "2")), 0)
            advanceUntilIdle()
            assertEquals(first.strategyId, (coordinator.state.value[0] as CoordinatorState.Planned).strategyId)

            // A new generation for the next song re-plans the edge, and the pin decides it.
            val songs = mapOf(1L to a, 2L to b)
            val items = QueueManager.queueItems(listOf(1L, 2L).map { id -> fakeSong(id) }, mapOf(2L to 1))
                .map { qi -> item(songs.getValue(QueueManager.baseEngineId(qi.id).toLong()), qi.id) }
            assertEquals(listOf("1", "2~1"), items.map { it.id })
            coordinator.onQueue(PlaybackContext.PLAYLIST, items, 0)
            advanceUntilIdle()
            val replanned = coordinator.state.value[0]
            assertTrue(replanned is CoordinatorState.Planned, "expected a planned edge, was $replanned")
            assertEquals(chosen, replanned.strategyId)
            assertTrue(renderer.calls.isEmpty(), "no render was due: ${renderer.calls}")
        } finally {
            coordinator.shutdown()
            player.close()
        }
    }

    private fun fakeSong(id: Long) = dev.muisc.app.data.db.Song(
        id = id, title = "Song $id", artist = "Artist", artistId = 1L, album = "Album", albumId = 0L, albumArtist = null,
        track = 1, disc = 1, year = 0, durationMs = 0L, uri = "content://song/$id", path = "/$id.mp3", mimeType = "audio/mpeg",
        size = 0L, dateAdded = 0L, dateModified = 0L, genre = null,
    )
}
