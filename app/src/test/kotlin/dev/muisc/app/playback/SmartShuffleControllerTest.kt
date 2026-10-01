package dev.muisc.app.playback

import android.content.Context
import dev.muisc.analysis.AnalysisCache
import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Cues
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.LoudnessInfo
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.SourceAnalysisLookup
import dev.muisc.app.data.db.Song
import dev.muisc.app.data.prefs.SettingsRepository
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.EngineStreamFactory
import dev.muisc.audio.PcmStream
import dev.muisc.player.AudioSink
import dev.muisc.player.EngineLimits
import dev.muisc.player.RenderGate
import dev.muisc.transitions.DefaultProgramBuilder
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionRenderer
import dev.muisc.transitions.live.DefaultLivePlanFactory
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Smart shuffle through the real [EngineControllerImpl] (stubbed Android, no audio heard): the shuffle-mode commands
 * a Media3 controller, a car head unit or a Bluetooth remote sends. Nothing is analysed or played here: the engine's
 * own analysis of the queue fails (no decoder), which does not concern the queue order checked below.
 */
class SmartShuffleControllerTest {

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist $id", artistId = id, album = "Album $id", albumId = 100L + id, albumArtist = null,
        track = 1, disc = 1, year = 2024, durationMs = 180_000L, uri = "/music/$id.mp3",
        path = "/music/$id.mp3", mimeType = "audio/mpeg", size = 1L, dateAdded = 0L, dateModified = 0L, genre = null,
        hasAnalysis = true,
    )

    private fun analysis(id: Long): TrackAnalysis {
        val group = (id % 3).toInt()
        val bpm = doubleArrayOf(90.0, 124.0, 174.0)[group] + (id % 5) * 0.4
        val sr = 44100
        val total = 180L * sr
        val grid = BeatGrid.rigid(bpm, sr, 0L, total - 1, confidence = 0.9f, phraseStartBeat = 0)
        val bars = (grid.beatCount / 4).coerceAtLeast(1)
        fun arr(v: Float) = FloatArray(bars) { v }
        return TrackAnalysis(
            sourceId = "/music/$id.mp3", fingerprint = "fp-$id", sampleRate = sr, totalFrames = total,
            trimStartFrame = 0L, trimEndFrame = total, tempo = TempoEstimate(bpm, 0.9f), grid = grid,
            key = KeyEstimate(MusicalKey((group * 7) % 12, Mode.MAJOR), 0.9f), loudness = LoudnessInfo(-10f, -1f),
            bars = BarFeatures(arr(0.8f), arr(0.1f), arr(0.2f), arr(0.4f), arr(0.2f), arr(0.6f), arr(0.1f)),
            intro = IntroType.BEAT_INTRO, outro = OutroType.BEAT_OUTRO,
            cues = Cues(mixOutBeat = grid.beatCount - 65, mixInBeat = 64, firstDownbeat = 0, lastDownbeat = (grid.beatCount - 1) / 4 * 4),
        )
    }

    private val songs = (1L..40L).map { song(it) }
    private val byPath = songs.associate { it.uri to analysis(it.id) }

    /** The Room cache as smart shuffle sees it; every by-source lookup waits for [open] (the pass is "in flight"). */
    private inner class Cache(private val open: CountDownLatch) : AnalysisCache, SourceAnalysisLookup {
        override fun get(fingerprint: String, sampleRate: Int, version: Int): TrackAnalysis? = null
        override fun put(analysis: TrackAnalysis) {}
        override fun clear() {}
        override fun latestForSource(sourceId: String, sampleRate: Int, version: Int): TrackAnalysis? {
            open.await(10, TimeUnit.SECONDS)
            return byPath[sourceId]
        }
    }

    private object NoStreams : EngineStreamFactory {
        override fun open(source: AudioSourceId, sampleRate: Int, channels: Int): PcmStream = throw java.io.IOException("no decoder in this test")
    }

    private object NoRenders : TransitionRenderer {
        override fun render(a: TrackRef, b: TrackRef, candidate: PlanCandidate, features: PairFeatures, ctx: RenderContext): RenderedTransition =
            throw IllegalStateException("no render in this test")
    }

    private class SilentSink(override val sampleRate: Int, override val channels: Int) : AudioSink {
        override fun write(interleaved: FloatArray, frames: Int) {}
        override fun latencyFrames(): Int = 0
        override fun pause() {}
        override fun flush() {}
        override fun resume() {}
        override fun close() {}
    }

    private fun controller(cache: Cache): EngineControllerImpl {
        val context = Context()
        val dj = DjCustomization(Files.createTempDirectory("muisc-smart").toFile())
        return EngineControllerImpl(
            context = context, sampleRate = 44100, channels = 2, limits = EngineLimits.DESKTOP, streams = NoStreams,
            planner = dj.planner, renderer = NoRenders, liveFactory = DefaultLivePlanFactory(), programBuilder = DefaultProgramBuilder(),
            analyses = AndroidAnalysisService(context, cache, NoStreams, 44100, 2), gate = RenderGate.DEFAULT, windows = null,
            registry = dj.registry, settings = SettingsRepository(context), sinkFactory = { rate, ch -> SilentSink(rate, ch) },
        )
    }

    private fun EngineControllerImpl.order() = state.value.queue.map { it.id }

    /** Waits up to [ms] for [done]. */
    private fun eventually(ms: Long = 5_000, done: () -> Boolean): Boolean {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (System.nanoTime() < until) {
            if (done()) return true
            Thread.sleep(20)
        }
        return done()
    }

    @Test
    fun aShuffleOffCommandThatChangesNothingKeepsTheArrangementInFlight() {
        val open = CountDownLatch(1)
        val controller = controller(Cache(open))
        try {
            controller.shuffle(songs)
            val start = controller.order()
            assertEquals(songs.size, start.size)
            assertFalse(controller.state.value.shuffle, "a shuffled queue is built with the shuffle flag off")
            // A head unit re-sends its shuffle state while the first pass is still reading analyses.
            controller.setShuffle(false)
            open.countDown()
            assertTrue(eventually { controller.order() != start }, "the arrangement landed")
            assertEquals(start.take(2), controller.order().take(2), "the playing song and the next one keep their places")
            assertEquals(start.sorted(), controller.order().sorted())
        } finally {
            controller.release()
        }
    }

    @Test
    fun aRepeatedShuffleOnCommandKeepsTheArrangementInFlight() {
        val open = CountDownLatch(1)
        val controller = controller(Cache(open))
        try {
            controller.setQueue(songs, 0, PlaybackContext.PLAYLIST, playNow = false)
            controller.setShuffle(true)
            val start = controller.order()
            assertTrue(controller.state.value.shuffle)
            controller.setShuffle(true)
            assertEquals(start, controller.order(), "a repeated command does not reshuffle")
            open.countDown()
            assertTrue(eventually { controller.order() != start }, "the arrangement landed")
            assertEquals(start.sorted(), controller.order().sorted())
        } finally {
            controller.release()
        }
    }
}
