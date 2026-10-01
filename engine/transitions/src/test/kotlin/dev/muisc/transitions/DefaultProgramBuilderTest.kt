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

    // ---- room -------------------------------------------------------------------------------------------------

    private fun cand(name: String, aExit: Long, bEntry: Long): PlanCandidate {
        val r = fakeRender(aExit, bEntry)
        return PlanCandidate(dev.muisc.transitions.strategies.CrossfadeStrategy(), Applicability.of(0.5, name), 0.5, r.plan.copy(strategyId = name))
    }

    private fun renderOf(c: PlanCandidate): RenderedTransition = fakeRender(c.plan.aExitFrame, c.plan.bEntryFrame)

    /**
     * A 30 s track between two normal ones. The transition into it hands over at 14 s; the planner's favourite
     * out of it leaves at 5 s, which [DefaultProgramBuilder.build] can only repair by dropping it. [roomOrder] puts
     * the candidate that leaves it a body first, and with that one the program keeps both transitions.
     */
    @Test
    fun roomOrderPutsTheCandidateThatKeepsBothTransitionsFirst() {
        val a = track("a"); val b = track("b", seconds = 30.0); val c = track("c")
        val intoB = fakeRender(aExit = 50L * sr, bEntry = 14L * sr)
        val early = cand("early", aExit = 5L * sr, bEntry = 3L * sr)
        val late = cand("late", aExit = 25L * sr, bEntry = 3L * sr)

        assertEquals(DefaultProgramBuilder.Room.STARVES_A, builder.room(early.plan, b, c, PlaybackContext.PLAYLIST, prefs, intoB.plan.bEntryFrame, null))
        assertEquals(DefaultProgramBuilder.Room.FITS, builder.room(late.plan, b, c, PlaybackContext.PLAYLIST, prefs, intoB.plan.bEntryFrame, null))
        val ordered = builder.roomOrder(listOf(early, late), b, c, PlaybackContext.PLAYLIST, prefs, intoB.plan.bEntryFrame, null)
        assertEquals(listOf("late", "early"), ordered.map { it.plan.strategyId })

        fun program(out: PlanCandidate) = builder.build(listOf(a, b, c), PlaybackContext.PLAYLIST, prefs) { x, _ -> if (x.id == "a") intoB else renderOf(out) }
        assertEquals(1, program(early).segments.count { it is Segment.Rendered }, "the favourite costs a transition")
        val kept = program(ordered.first())
        assertEquals(2, kept.segments.count { it is Segment.Rendered }, "${kept.segments}")
        for (body in kept.segments.filterIsInstance<Segment.Body>()) assertTrue(body.frames >= builder.minBodyFrames(body.track, PlaybackContext.PLAYLIST, prefs), "$body")
    }

    @Test
    fun roomOrderLooksAtTheNextTransitionOutOfB() {
        val a = track("a"); val b = track("b", seconds = 30.0)
        val next = listOf(cand("out", aExit = 12L * sr, bEntry = 2L * sr).plan)
        val deep = cand("deep", aExit = 50L * sr, bEntry = 20L * sr)      // B plays 20..29.5 s alone, but "out" leaves at 12 s
        val tooDeep = cand("tooDeep", aExit = 50L * sr, bEntry = 29L * sr) // not even a bar of B alone
        val shallow = cand("shallow", aExit = 50L * sr, bEntry = 4L * sr)
        fun room(c: PlanCandidate, n: List<TransitionPlan>?) = builder.room(c.plan, a, b, PlaybackContext.PLAYLIST, prefs, null, n)
        assertEquals(DefaultProgramBuilder.Room.COSTS_NEXT, room(deep, next))
        assertEquals(DefaultProgramBuilder.Room.FITS, room(deep, null), "B with no transition out of it keeps 20..29.5 s")
        assertEquals(DefaultProgramBuilder.Room.STARVES_B, room(tooDeep, next))
        assertEquals(DefaultProgramBuilder.Room.STARVES_B, room(tooDeep, null))
        assertEquals(DefaultProgramBuilder.Room.FITS, room(shallow, next))
        val ordered = builder.roomOrder(listOf(tooDeep, deep, shallow), a, b, PlaybackContext.PLAYLIST, prefs, null, next)
        assertEquals(listOf("shallow", "deep", "tooDeep"), ordered.map { it.plan.strategyId })
    }

    /** Tracks with room to spare: the planner's order comes back untouched, the same objects in the same order. */
    @Test
    fun roomOrderKeepsThePlannersOrderWhenEveryCandidateFits() {
        val a = track("a"); val b = track("b")
        val cands = listOf(cand("x", 50L * sr, 5L * sr), cand("y", 40L * sr, 8L * sr), cand("z", 55L * sr, 3L * sr))
        val ordered = builder.roomOrder(cands, a, b, PlaybackContext.PLAYLIST, prefs, 4L * sr, listOf(cand("n", 45L * sr, 1L).plan))
        assertEquals(cands.size, ordered.size)
        for (i in cands.indices) assertTrue(cands[i] === ordered[i], "position $i")
        // And when nothing fits, nothing is removed and the planner's order stands.
        val starving = listOf(cand("p", 2L * sr, 5L * sr), cand("q", 3L * sr, 5L * sr))
        val same = builder.roomOrder(starving, a, b, PlaybackContext.PLAYLIST, prefs, 30L * sr, null)
        assertEquals(listOf("p", "q"), same.map { it.plan.strategyId })
    }

    /**
     * When no candidate keeps both tracks their minimum body, the planner's order comes back unchanged even when
     * the candidates starve different tracks: the sort used to put STARVES_B ahead of STARVES_A and so reordered
     * [x (starves A), y (starves B)] into [y, x], although both are dropped. Starving candidates also keep the
     * planner's order behind the ones that fit, and a candidate that only costs B's next transition still goes ahead
     * of one that starves B (it plays; the other one is dropped).
     */
    @Test
    fun roomOrderKeepsThePlannersOrderAmongCandidatesThatStarveATrack() {
        val a = track("a"); val b = track("b", seconds = 30.0)
        val next = listOf(cand("out", aExit = 12L * sr, bEntry = 2L * sr).plan)
        val startsA = cand("startsA", aExit = 5L * sr, bEntry = 4L * sr)       // A entered at 4.5 s, left at 5 s
        val startsB = cand("startsB", aExit = 50L * sr, bEntry = 29L * sr)     // B alone keeps 0.5 s
        val costsNext = cand("costsNext", aExit = 50L * sr, bEntry = 20L * sr) // B alone keeps 9.5 s, but "out" leaves at 12 s
        val fits = cand("fits", aExit = 50L * sr, bEntry = 4L * sr)
        val aEntry = 9L * sr / 2
        fun room(c: PlanCandidate) = builder.room(c.plan, a, b, PlaybackContext.PLAYLIST, prefs, aEntry, next)
        assertEquals(DefaultProgramBuilder.Room.STARVES_A, room(startsA))
        assertEquals(DefaultProgramBuilder.Room.STARVES_B, room(startsB))
        assertEquals(DefaultProgramBuilder.Room.COSTS_NEXT, room(costsNext))
        assertEquals(DefaultProgramBuilder.Room.FITS, room(fits))
        fun order(vararg cs: PlanCandidate) = builder.roomOrder(cs.toList(), a, b, PlaybackContext.PLAYLIST, prefs, aEntry, next).map { it.plan.strategyId }

        assertEquals(listOf("startsA", "startsB"), order(startsA, startsB))
        assertEquals(listOf("startsB", "startsA"), order(startsB, startsA))
        val unchanged = listOf(startsA, startsB)
        assertTrue(builder.roomOrder(unchanged, a, b, PlaybackContext.PLAYLIST, prefs, aEntry, next) === unchanged, "nothing fits: the planner's list itself")
        assertEquals(listOf("fits", "startsA", "startsB"), order(startsA, startsB, fits))
        assertEquals(listOf("costsNext", "startsB"), order(startsB, costsNext))
        assertEquals(listOf("fits", "costsNext", "startsA", "startsB"), order(startsA, costsNext, startsB, fits))
    }

    /**
     * The user's own choice (a pin, or the one-off "next transition" pick; [PlanCandidate.pinned]) keeps first
     * place when it leaves both tracks their body, even when it costs B's next transition: room order used to put
     * every candidate that fits ahead of it, so the pick silently never played. It gives way only when it would
     * starve a track, which [DefaultProgramBuilder.build] would drop anyway.
     */
    @Test
    fun roomOrderKeepsThePinnedCandidateFirstUnlessItStarvesATrack() {
        val a = track("a"); val b = track("b", seconds = 30.0)
        val next = listOf(cand("out", aExit = 12L * sr, bEntry = 2L * sr).plan)
        fun pinned(c: PlanCandidate) = c.copy(pinned = true)
        val costsNext = cand("costsNext", aExit = 50L * sr, bEntry = 20L * sr)
        val startsB = cand("startsB", aExit = 50L * sr, bEntry = 29L * sr)
        val startsA = cand("startsA", aExit = 5L * sr, bEntry = 4L * sr)
        val fits = cand("fits", aExit = 50L * sr, bEntry = 4L * sr)
        val aEntry = 9L * sr / 2
        fun order(vararg cs: PlanCandidate) = builder.roomOrder(cs.toList(), a, b, PlaybackContext.PLAYLIST, prefs, aEntry, next).map { it.plan.strategyId }

        assertEquals(listOf("fits", "costsNext"), order(costsNext, fits), "not pinned: the one that fits goes first")
        assertEquals(listOf("costsNext", "fits"), order(pinned(costsNext), fits), "pinned: the user's pick stays first")
        assertEquals(listOf("fits", "startsB"), order(pinned(startsB), fits), "a pin that starves B gives way")
        assertEquals(listOf("fits", "startsA"), order(pinned(startsA), fits), "a pin that starves A gives way")
        assertEquals(listOf("startsA", "startsB"), order(pinned(startsA), startsB), "nothing fits: the planner's order, pin first")
    }

    /**
     * Full-length songs with the real planner: every candidate leaves both songs their body, so the room order is
     * the planner's ranking, object for object — including the pair `ReplanNextEdgeTest` (app) plays, whose pinned
     * last-ranked candidate must stay first.
     */
    @Test
    fun theRealPlannersRankingIsUntouchedForFullLengthSongs() {
        val p = TransitionPrefs()
        val planner = dev.muisc.transitions.planner.DefaultTransitionPlanner(DefaultStrategyRegistry.default())
        fun song(bpm: Double, tonic: Int, bars: Int, seed: Int) =
            dev.muisc.transitions.synthetic.SyntheticTracks.trackRef(dev.muisc.audio.synth.SyntheticSong(bpm = bpm, tonic = tonic, bars = bars, introBars = 8, outroBars = 8, seed = seed)).trackRef
        val pairs = listOf(song(80.0, 9, 48, 7) to song(80.0, 4, 48, 11), song(124.0, 0, 64, 3) to song(128.0, 7, 64, 5))
        for ((a, b) in pairs) {
            val ranked = planner.plan(a, b, p)
            val next = planner.plan(b, a, p).candidates.map { it.plan }
            for (c in ranked.candidates) assertEquals(DefaultProgramBuilder.Room.FITS, builder.room(c.plan, a, b, PlaybackContext.PLAYLIST, p, null, null), "${a.id} → ${b.id} ${c.strategy.id}")
            for (n in listOf(null, next)) {
                val ordered = builder.roomOrder(ranked.candidates, a, b, PlaybackContext.PLAYLIST, p, null, n)
                assertTrue(ranked.candidates.indices.all { ranked.candidates[it] === ordered[it] }, "${a.id} → ${b.id}: ${ordered.map { it.strategy.id }}")
            }
        }
    }

    /**
     * [DefaultProgramBuilder.hasRoom] is the exact test [DefaultProgramBuilder.build] repairs with: for a middle
     * track entered and left at random frames (its neighbours with room to spare), both transitions survive the
     * build exactly when [hasRoom] says so. A room rule that disagreed would reorder candidates for nothing, or
     * pick one the build then drops.
     */
    @Test
    fun hasRoomAgreesWithWhatBuildKeeps() {
        val rnd = kotlin.random.Random(42)
        val a = track("a"); val c = track("c")
        for (seconds in listOf(1.5, 4.0, 12.0, 30.0)) {
            val b = track("b", seconds = seconds)
            val total = b.analysis.totalFrames
            val min = builder.minBodyFrames(b, PlaybackContext.PLAYLIST, prefs)
            repeat(300) {
                val entry = rnd.nextLong(0L, total)
                // Near the boundary half of the time, so the ≥ in the rule is exercised.
                val exit = if (rnd.nextBoolean()) entry + min + rnd.nextLong(-2L, 3L) else rnd.nextLong(0L, total)
                if (exit < 1000) return@repeat
                val rAB = fakeRender(aExit = 50L * sr, bEntry = entry)
                val rBC = fakeRender(aExit = exit, bEntry = 5L * sr)
                val program = builder.build(listOf(a, b, c), PlaybackContext.PLAYLIST, prefs) { x, _ -> if (x.id == "a") rAB else rBC }
                val bothKept = program.segments.count { it is Segment.Rendered } == 2
                assertEquals(builder.hasRoom(b, PlaybackContext.PLAYLIST, prefs, entry, exit), bothKept, "b ${seconds}s entry $entry exit $exit min $min")
            }
        }
    }
}
