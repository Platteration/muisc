package dev.muisc.transitions

import dev.muisc.audio.AudioBuffer
import dev.muisc.transitions.planner.TestAnalyses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultProgramBuilderTest {
    private val sr = TestAnalyses.SR
    private val builder = DefaultProgramBuilder()
    private val prefs = TransitionPrefs()

    private fun track(id: String, album: String? = null, seconds: Double = 60.0, sampleRate: Int = sr): TrackRef =
        TestAnalyses.ref(TestAnalyses.simple(id, seconds = seconds, sampleRate = sampleRate), albumId = album)

    /** A fake render: the plan carries the splice frames, the audio is [frames] frames of silence. */
    private fun fakeRender(aExit: Long, bEntry: Long, frames: Int = 5000): RenderedTransition {
        val plan = TransitionPlan("fake", Params.EMPTY, aExit, bEntry, FrameRange(aExit - 100, aExit + 1000), FrameRange(bEntry - 1000, bEntry), frames)
        return RenderedTransition(plan, AudioBuffer.silence(sr, 2, frames), report = RenderReport(0, 0f, -120f, -120f))
    }

    @Test
    fun albumBodyIsTheWholeFileAndPlaylistBodyIsTheTrimRange() {
        val t = track("t")
        val album = builder.bodySegment(t, PlaybackContext.ALBUM, prefs, null, null)
        assertEquals(0L, album.fromFrame)
        assertEquals(t.analysis.totalFrames, album.toFrame)
        for (ctx in listOf(PlaybackContext.PLAYLIST, PlaybackContext.QUEUE, PlaybackContext.SHUFFLE, PlaybackContext.SINGLE)) {
            val body = builder.bodySegment(t, ctx, prefs, null, null)
            assertEquals(t.analysis.trimStartFrame, body.fromFrame, ctx.name)
            assertEquals(t.analysis.trimEndFrame, body.toFrame, ctx.name)
            assertTrue(body.fromFrame > 0 && body.toFrame < t.analysis.totalFrames)
        }
    }

    @Test
    fun rendersSetTheBodyBoundaries() {
        val t = track("t")
        val incoming = fakeRender(aExit = 12345L, bEntry = 7 * sr.toLong())
        val outgoing = fakeRender(aExit = 50 * sr.toLong(), bEntry = 999L)
        val both = builder.bodySegment(t, PlaybackContext.PLAYLIST, prefs, incoming, outgoing)
        assertEquals(7L * sr, both.fromFrame, "incoming render → body starts at bEntryFrame")
        assertEquals(50L * sr, both.toFrame, "outgoing render → body ends at aExitFrame")
        val onlyIn = builder.bodySegment(t, PlaybackContext.ALBUM, prefs, incoming, null)
        assertEquals(7L * sr, onlyIn.fromFrame); assertEquals(t.analysis.totalFrames, onlyIn.toFrame)
        val onlyOut = builder.bodySegment(t, PlaybackContext.QUEUE, prefs, null, outgoing)
        assertEquals(t.analysis.trimStartFrame, onlyOut.fromFrame); assertEquals(50L * sr, onlyOut.toFrame)
        // A render whose exit precedes the entry yields an empty body, never an exception.
        val degenerate = builder.bodySegment(t, PlaybackContext.PLAYLIST, prefs, fakeRender(1, 40L * sr), fakeRender(30L * sr, 1))
        assertEquals(40L * sr, degenerate.fromFrame); assertEquals(40L * sr, degenerate.toFrame); assertEquals(0L, degenerate.frames)
    }

    @Test
    fun analysisFramesAreRescaledToTheEngineRate() {
        val t = track("t", sampleRate = 22050)
        val body = builder.bodySegment(t, PlaybackContext.PLAYLIST, TransitionPrefs(sampleRate = 44100), null, null)
        assertEquals(t.analysis.trimStartFrame * 2, body.fromFrame)
        assertEquals(t.analysis.trimEndFrame * 2, body.toFrame)
        val whole = builder.bodySegment(t, PlaybackContext.ALBUM, TransitionPrefs(sampleRate = 44100), null, null)
        assertEquals(t.analysis.totalFrames * 2, whole.toFrame)
    }

    @Test
    fun buildWalksTheQueueWithGatingAndRenders() {
        val a = track("a", "alb1"); val b = track("b", "alb1"); val c = track("c", "alb2")
        val rAB = fakeRender(aExit = 55L * sr, bEntry = 4L * sr)
        val rBC = fakeRender(aExit = 56L * sr, bEntry = 3L * sr)
        var asked = ArrayList<String>()
        val renders: (TrackRef, TrackRef) -> RenderedTransition? = { x, y ->
            asked += "${x.id}>${y.id}"
            when (x.id to y.id) { "a" to "b" -> rAB; "b" to "c" -> rBC; else -> null }
        }

        // Playlist: both pairs transition.
        val playlist = builder.build(listOf(a, b, c), PlaybackContext.PLAYLIST, prefs, renders)
        assertEquals(listOf("a>b", "b>c"), asked)
        assertEquals(5, playlist.segments.size)
        val s = playlist.segments
        assertIs<Segment.Body>(s[0]); assertIs<Segment.Rendered>(s[1]); assertIs<Segment.Body>(s[2]); assertIs<Segment.Rendered>(s[3]); assertIs<Segment.Body>(s[4])
        assertEquals(Segment.Body(a, a.analysis.trimStartFrame, 55L * sr), s[0])
        assertEquals(Segment.Rendered(a, b, rAB), s[1])
        assertEquals(Segment.Body(b, 4L * sr, 56L * sr), s[2])
        assertEquals(Segment.Rendered(b, c, rBC), s[3])
        assertEquals(Segment.Body(c, 3L * sr, c.analysis.trimEndFrame), s[4])
        val expectedTotal = (55L * sr - a.analysis.trimStartFrame) + 5000 + (52L * sr) + 5000 + (c.analysis.trimEndFrame - 3L * sr)
        assertEquals(expectedTotal, playlist.totalFrames)

        // Album: a→b is the same album (no transition, whole files, renders not even asked); b→c crosses albums.
        asked = ArrayList()
        val album = builder.build(listOf(a, b, c), PlaybackContext.ALBUM, prefs, renders)
        assertEquals(listOf("b>c"), asked)
        assertEquals(4, album.segments.size)
        assertEquals(Segment.Body(a, 0L, a.analysis.totalFrames), album.segments[0])
        assertEquals(Segment.Body(b, 0L, 56L * sr), album.segments[1])
        assertEquals(Segment.Rendered(b, c, rBC), album.segments[2])
        assertEquals(Segment.Body(c, 3L * sr, c.analysis.totalFrames), album.segments[3])

        // A pair without a render is gapless body-to-body.
        val partial = builder.build(listOf(a, b, c), PlaybackContext.QUEUE, prefs) { x, y -> if (x.id == "b") rBC else null }
        assertEquals(4, partial.segments.size)
        assertEquals(Segment.Body(a, a.analysis.trimStartFrame, a.analysis.trimEndFrame), partial.segments[0])
        assertEquals(Segment.Body(b, b.analysis.trimStartFrame, 56L * sr), partial.segments[1])

        // Disabled / single: bodies only.
        assertTrue(builder.build(listOf(a, b, c), PlaybackContext.PLAYLIST, prefs.copy(enabled = false), renders).segments.all { it is Segment.Body })
        assertEquals(1, builder.build(listOf(a), PlaybackContext.SINGLE, prefs, renders).segments.size)
        assertEquals(0, builder.build(emptyList(), PlaybackContext.PLAYLIST, prefs, renders).segments.size)
    }

    /**
     * Regression: the two transitions around a track are planned independently, so the one out of it can be
     * scheduled at or before the point the one into it hands over. The mix that exposed this had `echoOut`
     * handing t126Am over at 13.9 s and `phraseCut` wanting to cut it at 5.6 s, which the clamp in
     * [DefaultProgramBuilder.bodySegment] turned into a zero-length body: a track scheduled with none of itself
     * playing, and an eight-second jump backwards inside it.
     */
    @Test
    fun aTrackIsNeverScheduledWithoutRoomToPlay() {
        val a = track("a"); val b = track("b"); val c = track("c")
        val rAB = fakeRender(aExit = 50L * sr, bEntry = 30L * sr)
        val rBC = fakeRender(aExit = 20L * sr, bEntry = 5L * sr) // cuts b *before* the echo handed it over

        val program = builder.build(listOf(a, b, c), PlaybackContext.PLAYLIST, prefs) { x, _ ->
            if (x.id == "a") rAB else rBC
        }
        val bodies = program.segments.filterIsInstance<Segment.Body>()
        assertEquals(3, bodies.size)
        for (body in bodies) assertTrue(body.frames >= builder.minBodyFrames(body.track, PlaybackContext.PLAYLIST, prefs), "$body")
        // The later of the two transitions is the one that gives way; the pair is played body-to-body.
        assertEquals(listOf(rAB), program.segments.filterIsInstance<Segment.Rendered>().map { it.rendered })
        assertEquals(30L * sr, bodies[1].fromFrame)
        assertEquals(b.analysis.trimEndFrame, bodies[1].toFrame)
        assertEquals(c.analysis.trimStartFrame, bodies[2].fromFrame)

        // When the incoming render alone leaves nothing, that one goes too.
        val greedy = builder.build(listOf(a, b), PlaybackContext.PLAYLIST, prefs) { _, _ -> fakeRender(aExit = 50L * sr, bEntry = b.analysis.trimEndFrame - 100) }
        assertTrue(greedy.segments.none { it is Segment.Rendered }, "${greedy.segments}")
        assertEquals(b.analysis.trimStartFrame, (greedy.segments[1] as Segment.Body).fromFrame)

        // A minimum that a very short track could never meet does not bar it from having transitions at all:
        // the rule asks for one bar, and never for more than half of what the track has.
        val tiny = track("tiny", seconds = 1.5)
        val min = builder.minBodyFrames(tiny, PlaybackContext.PLAYLIST, prefs)
        val playable = tiny.analysis.trimEndFrame - tiny.analysis.trimStartFrame
        assertTrue(min in 1..(playable / 2), "min $min of playable $playable")
        val short = builder.build(listOf(a, tiny, c), PlaybackContext.PLAYLIST, prefs) { x, _ ->
            if (x.id == "a") fakeRender(aExit = 50L * sr, bEntry = tiny.analysis.trimStartFrame + min) else null
        }
        assertEquals(1, short.segments.filterIsInstance<Segment.Rendered>().size, "${short.segments}")
    }
}
