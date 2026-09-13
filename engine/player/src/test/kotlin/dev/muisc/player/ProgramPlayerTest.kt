package dev.muisc.player

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.loudness.TruePeak
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.PlaybackProgram
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.Segment
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.live.Deck
import dev.muisc.transitions.live.LiveNode
import dev.muisc.transitions.live.LivePlan
import dev.muisc.transitions.live.LivePoint
import dev.muisc.transitions.synthetic.SyntheticTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgramPlayerTest {
    private val sr = PlayerFixtures.SR
    private val a by lazy { PlayerFixtures.track(PlayerFixtures.song(120.0, 0, seed = 7), "A", albumId = "album1") }
    private val b by lazy { PlayerFixtures.track(PlayerFixtures.song(128.0, 9, seed = 8), "B", albumId = "album1") }
    private val c by lazy { PlayerFixtures.track(PlayerFixtures.song(124.0, 5, seed = 9), "C", albumId = "album2") }
    private val limits = EngineLimits.DESKTOP

    private fun body(t: SyntheticTrack, from: Long, to: Long) = Segment.Body(t.trackRef, from, to)
    private fun trimStart(t: SyntheticTrack) = t.analysis.trimStartFrame
    private fun trimEnd(t: SyntheticTrack) = t.analysis.trimEndFrame

    private fun equalPowerPlan(a: SyntheticTrack, b: SyntheticTrack, seconds: Double): LivePlan {
        val n = Math.round(seconds * sr).toInt()
        val aTo = trimEnd(a); val aFrom = aTo - n
        return LivePlan("crossfade", aFrom, aTo, trimStart(b), n, listOf(
            LiveNode.Gain(Deck.A, listOf(LivePoint(0, 1f, FadeLaw.EQUAL_POWER), LivePoint(n, 0f))),
            LiveNode.Gain(Deck.B, listOf(LivePoint(0, 0f, FadeLaw.EQUAL_POWER), LivePoint(n, 1f))),
        ))
    }

    @Test
    fun albumGaplessPlaybackIsBitExact() {
        val aud = PlayerFixtures.scaled(a.audio, 0.4f)
        val bud = PlayerFixtures.scaled(b.audio, 0.4f)
        val streams = MemoryEngineStreamFactory().register(a.trackRef.source, aud).register(b.trackRef.source, bud)
        val program = PlaybackProgram(listOf(body(a, 0, aud.frames.toLong()), body(b, 0, bud.frames.toLong())))
        val out = ProgramRenderer.render(program, streams, PlayerFixtures.basePrefs, limits, sharedGainDb = 0f)
        val expected = PlayerFixtures.concat(aud, bud)
        assertEquals(program.totalFrames, out.frames.toLong())
        assertTrue(PlayerFixtures.exactlyEqual(expected, out), "album output must equal the concatenation of the inputs bit-exactly (max diff ${PlayerFixtures.maxDiff(expected, out)})")
    }

    @Test
    fun seamFadeIsANoOpOnIdenticalSignals() {
        val aud = PlayerFixtures.scaled(a.audio, 0.5f)
        val streams = MemoryEngineStreamFactory().register(a.trackRef.source, aud)
        val k = 123_457L
        val program = PlaybackProgram(listOf(body(a, 0, k), body(a, k, aud.frames.toLong())))
        val out = ProgramRenderer.render(program, streams, PlayerFixtures.basePrefs, limits, sharedGainDb = 0f)
        assertTrue(PlayerFixtures.exactlyEqual(aud, out), "a track split into two bodies must play back bit-exactly through the 240-frame seam")
    }

    @Test
    fun renderedSegmentSplicesExactlyAtTheContractFrames() {
        val prefs = PlayerFixtures.prefs(a, b)
        val r = PlayerFixtures.crossfadeRender(a, b, prefs, fadeSec = 3.0)
        val plan = r.plan
        val program = PlaybackProgram(listOf(body(a, trimStart(a), plan.aExitFrame), Segment.Rendered(a.trackRef, b.trackRef, r), body(b, plan.bEntryFrame, trimEnd(b))))
        val out = ProgramRenderer.render(program, PlayerFixtures.streams(a, b), prefs, limits)
        val gA = PlayerFixtures.gain(a, prefs); val gB = PlayerFixtures.gain(b, prefs)
        assertTrue(gA < 1f && gB < 1f)
        val expected = PlayerFixtures.concat(PlayerFixtures.slice(a.audio, trimStart(a), plan.aExitFrame, gA), r.audio, PlayerFixtures.slice(b.audio, plan.bEntryFrame, trimEnd(b), gB))
        assertEquals(expected.frames, out.frames)
        val seamA = (plan.aExitFrame - trimStart(a)).toInt()
        val seamB = seamA + r.audio.frames
        assertEquals(0f, PlayerFixtures.maxDiff(expected, out, seamA - 10, seamA + limits.seamFadeFrames + 10), "A→render seam must be bit-exact")
        assertEquals(0f, PlayerFixtures.maxDiff(expected, out, seamB - 10, seamB + limits.seamFadeFrames + 10), "render→B seam must be bit-exact")
        assertTrue(PlayerFixtures.exactlyEqual(expected, out), "whole program must equal A body + render + B body (max diff ${PlayerFixtures.maxDiff(expected, out)})")
        assertTrue(ArtifactDetector(sr).analyze(out).clicks.isEmpty())
    }

    @Test
    fun liveSegmentExecutesAnEqualPowerCrossfade() {
        val prefs = PlayerFixtures.prefs(a, b)
        val plan = equalPowerPlan(a, b, 2.0)
        val gA = PlayerFixtures.gain(a, prefs); val gB = PlayerFixtures.gain(b, prefs)
        val n = plan.outputFrames
        val mix = AudioBuffer.silence(sr, 2, n)
        for (ch in 0 until 2) for (i in 0 until n) {
            val t = i.toDouble() / n
            mix[ch][i] = (a.audio[ch][(plan.aFromFrame + i).toInt()] * gA * cos(PI / 2 * t) + b.audio[ch][(plan.bFromFrame + i).toInt()] * gB * sin(PI / 2 * t)).toFloat()
        }
        val expected = PlayerFixtures.concat(PlayerFixtures.slice(a.audio, trimStart(a), plan.aFromFrame, gA), mix, PlayerFixtures.slice(b.audio, plan.bExitFrame(), trimEnd(b), gB))
        for (seg in listOf<Segment>(
            Segment.Live(a.trackRef, b.trackRef, plan),
            Segment.LiveCrossfade(a.trackRef, b.trackRef, plan.aFromFrame, plan.aToFrame, plan.bFromFrame, n),
        )) {
            val program = PlaybackProgram(listOf(body(a, trimStart(a), plan.aFromFrame), seg, body(b, plan.bExitFrame(), trimEnd(b))))
            val out = ProgramRenderer.render(program, PlayerFixtures.streams(a, b), prefs, limits, limiter = false)
            assertEquals(expected.frames, out.frames)
            val d = PlayerFixtures.maxDiff(expected, out)
            assertTrue(d < 5e-3f, "${seg::class.simpleName}: live crossfade deviates from the independent equal-power mix by $d")
            val tail = (plan.aFromFrame - trimStart(a)).toInt() + n
            assertEquals(0f, PlayerFixtures.maxDiff(expected, out, tail + limits.seamFadeFrames, expected.frames), "B body after the live segment is verbatim")
            assertTrue(ArtifactDetector(sr).analyze(out).clicks.isEmpty())
        }
    }

    @Test
    fun reportsSegmentsTracksAndHandover() {
        val prefs = PlayerFixtures.prefs(a, b)
        val r = PlayerFixtures.crossfadeRender(a, b, prefs, fadeSec = 2.0)
        val plan = r.plan
        val program = PlaybackProgram(listOf(body(a, trimStart(a), plan.aExitFrame), Segment.Rendered(a.trackRef, b.trackRef, r), body(b, plan.bEntryFrame, trimEnd(b))))
        val events = ArrayList<PlayerEvent>()
        var lastPos: ProgramPosition? = null
        ProgramRenderer.render(program, PlayerFixtures.streams(a, b), prefs, limits) { _, p -> events += p.events.drain(); lastPos = p.position }
        val starts = events.filterIsInstance<PlayerEvent.SegmentStarted>()
        assertEquals(listOf(0, 1, 2), starts.map { it.segmentIndex })
        assertEquals(plan.aExitFrame - trimStart(a), starts[1].atOutputFrame)
        val changes = events.filterIsInstance<PlayerEvent.TrackChanged>()
        assertEquals(listOf("A", "B"), changes.map { it.track.id })
        val marker = r.markers.first { it.label == "B enters" }.frame
        assertEquals(plan.aExitFrame - trimStart(a) + marker, changes[1].atOutputFrame, "handover at the render's 'B enters' marker")
        assertTrue(events.any { it is PlayerEvent.Ended })
        assertTrue(events.count { it is PlayerEvent.PositionUpdate } > 10)
        assertEquals(program.totalFrames, lastPos!!.outputFrame)
        assertEquals("B", lastPos!!.nowPlaying!!.id)
    }

    @Test
    fun pauseAndResumeAddExactlyTheSilenceAndRamp() {
        val prefs = PlayerFixtures.basePrefs
        val aud = PlayerFixtures.scaled(a.audio, 0.4f)
        val streams = MemoryEngineStreamFactory().register(a.trackRef.source, aud)
        val program = PlaybackProgram(listOf(body(a, 0, aud.frames.toLong())))
        val block = limits.blockFrames
        val pauseAt = 20L * block
        val k = 5
        val out = ProgramRenderer.render(program, streams, prefs, limits, sharedGainDb = 0f) { rendered, p ->
            if (rendered == pauseAt) p.submit(EngineCommand.Pause)
            if (rendered == pauseAt + k * block) p.submit(EngineCommand.Play)
        }
        val ramp = sr / 100
        assertEquals(program.totalFrames + k * block - ramp, out.frames.toLong())
        // The pause ramp is monotone, the paused region is digital silence, and playback resumes where it stopped.
        val p = pauseAt.toInt()
        for (i in p + ramp until p + k * block) for (ch in 0 until 2) assertEquals(0f, out[ch][i], "silence at $i")
        assertTrue(kotlin.math.abs(out[0][p + ramp / 2]) <= kotlin.math.abs(aud[0][p + ramp / 2]) + 1e-6f)
        val resume = p + k * block
        val expectedTail = aud.slice(p + ramp + ramp, aud.frames)
        val gotTail = out.slice(resume + ramp, out.frames)
        assertTrue(PlayerFixtures.exactlyEqual(expectedTail, gotTail), "after the resume ramp the program continues bit-exactly")
    }

    @Test
    fun theLimiterOnlyEngagesAboveTheCeilingAndTheDuckGainAttenuates() {
        // Hot material (peak 1.0): the master limiter must hold the true peak at −1 dBTP.
        val hot = PlayerFixtures.scaled(a.audio, 1f / a.audio.peak())
        val streams = MemoryEngineStreamFactory().register(a.trackRef.source, hot)
        val program = PlaybackProgram(listOf(body(a, trimStart(a), trimEnd(a))))
        val loud = ProgramRenderer.render(program, streams, PlayerFixtures.basePrefs, limits, sharedGainDb = 0f)
        assertTrue(TruePeak.measureDbtp(hot) > ProgramPlayer.CEILING_DBTP, "the fixture must exceed the ceiling to test anything")
        assertTrue(TruePeak.measureDbtp(loud) <= ProgramPlayer.CEILING_DBTP + 0.05, "true peak ${TruePeak.measureDbtp(loud)} dBTP above the ceiling")
        assertTrue(ArtifactDetector(sr).analyze(loud).clipRuns.isEmpty())

        // −6 dB duck: the same program comes out at half the amplitude once the 50 ms ramp is over.
        val quiet = PlayerFixtures.scaled(a.audio, 0.3f)
        val quietStreams = MemoryEngineStreamFactory().register(a.trackRef.source, quiet)
        val ducked = ProgramRenderer.render(PlaybackProgram(listOf(body(a, 0, quiet.frames.toLong()))), quietStreams, PlayerFixtures.basePrefs, limits, sharedGainDb = 0f) { rendered, p ->
            if (rendered == 0L) p.setDuckDb(-6f)
        }
        val plain = ProgramRenderer.render(PlaybackProgram(listOf(body(a, 0, quiet.frames.toLong()))), quietStreams, PlayerFixtures.basePrefs, limits, sharedGainDb = 0f)
        assertEquals(plain.frames, ducked.frames)
        val from = sr / 10
        val ratio = ducked.slice(from, ducked.frames).rms() / plain.slice(from, plain.frames).rms()
        assertEquals(0.5f, ratio, 0.01f, "a −6 dB duck must halve the amplitude")
    }

    @Test
    fun seekInsideABodyFadesSeeksAndFadesBack() {
        val aud = PlayerFixtures.scaled(a.audio, 0.4f)
        val streams = MemoryEngineStreamFactory().register(a.trackRef.source, aud)
        val program = PlaybackProgram(listOf(body(a, 0, aud.frames.toLong())))
        val block = limits.blockFrames
        val seekAt = 10L * block
        val target = 200_000L
        val out = ProgramRenderer.render(program, streams, PlayerFixtures.basePrefs, limits, sharedGainDb = 0f) { rendered, p ->
            if (rendered == seekAt) p.submit(EngineCommand.Seek(target))
        }
        val ramp = sr / 100
        assertEquals(seekAt + ramp + (aud.frames - target), out.frames.toLong())
        val after = out.slice((seekAt + 2 * ramp).toInt(), out.frames)
        val expected = aud.slice((target + ramp).toInt(), aud.frames)
        assertTrue(PlayerFixtures.exactlyEqual(expected, after), "after the fade-in the body continues from the seek target")
        assertTrue(ArtifactDetector(sr).analyze(out).clicks.isEmpty())
    }

    // ------------------------------------------------------------------------------------------ property test

    private class Arrangement(val program: PlaybackProgram, val lengths: List<Long>)

    private val renders = HashMap<String, RenderedTransition>()

    private fun renderFor(x: SyntheticTrack, y: SyntheticTrack, prefs: TransitionPrefs): RenderedTransition =
        renders.getOrPut(x.id + ">" + y.id) { PlayerFixtures.crossfadeRender(x, y, prefs, fadeSec = 2.0) }

    /** Body(x) → transition(x→y) → Body(y) → transition(y→z) → Body(z), each transition Rendered / Live / LiveCrossfade / none. */
    private fun arrangement(rnd: Random, prefs: TransitionPrefs): Arrangement {
        val tracks = listOf(a, b, c)
        val segs = ArrayList<Segment>()
        var from = trimStart(tracks[0])
        for (i in tracks.indices) {
            val x = tracks[i]
            if (i == tracks.size - 1) { segs += body(x, from, trimEnd(x)); break }
            val y = tracks[i + 1]
            when (rnd.nextInt(4)) {
                0 -> { val r = renderFor(x, y, prefs); segs += body(x, from, r.plan.aExitFrame); segs += Segment.Rendered(x.trackRef, y.trackRef, r); from = r.plan.bEntryFrame }
                1 -> { val p = equalPowerPlan(x, y, 1.0 + rnd.nextDouble() * 1.5); segs += body(x, from, p.aFromFrame); segs += Segment.Live(x.trackRef, y.trackRef, p); from = p.bExitFrame() }
                2 -> { val p = equalPowerPlan(x, y, 1.0 + rnd.nextDouble()); segs += body(x, from, p.aFromFrame); segs += Segment.LiveCrossfade(x.trackRef, y.trackRef, p.aFromFrame, p.aToFrame, p.bFromFrame, p.outputFrames); from = p.bExitFrame() }
                else -> { segs += body(x, from, trimEnd(x)); from = trimStart(y) }
            }
        }
        val program = PlaybackProgram(segs)
        val lengths = segs.map { s -> when (s) { is Segment.Body -> s.frames; is Segment.Rendered -> s.rendered.audio.frames.toLong(); is Segment.LiveCrossfade -> s.aToFrame - s.aFromFrame; is Segment.Live -> s.plan.outputFrames.toLong() } }
        return Arrangement(program, lengths)
    }

    @Test
    fun skipSeekAndPauseAtRandomPositionsProduceNoClicksAndExactLengths() {
        val prefs = PlayerFixtures.prefs(a, b, c)
        val streams = PlayerFixtures.streams(a, b, c)
        val rnd = Random(20240913)
        val block = limits.blockFrames
        val ramp = sr / 100
        // A skip with no body left ends the program with a 20 ms fade-out to silence instead of a hard cut.
        val endFade = limits.inexactSeamFadeFrames.toLong()
        val detector = ArtifactDetector(sr)
        repeat(50) { iteration ->
            val arr = arrangement(rnd, prefs)
            val lens = arr.lengths
            val cum = LongArray(lens.size + 1).also { for (i in lens.indices) it[i + 1] = it[i] + lens[i] }
            val total = cum.last()
            val blocks = (total / block).toInt()
            val kind = rnd.nextInt(3)
            val p = rnd.nextInt(1, blocks - 3).toLong() * block
            val s = (0 until lens.size).first { cum[it] <= p && p < cum[it + 1] }
            var expected = total
            var command: EngineCommand? = null
            var playAt = -1L
            when (kind) {
                0 -> { // skip to the next body
                    command = EngineCommand.Skip
                    val j = (s + 1 until lens.size).firstOrNull { arr.program.segments[it] is Segment.Body }
                    expected = if (j == null) p + endFade else p + (j until lens.size).sumOf { lens[it] }
                }
                1 -> { // seek inside a body (fall back to a skip when the block boundary lies in a transition)
                    val seg = arr.program.segments[s]
                    val f = if (seg is Segment.Body) seg.fromFrame + (p - cum[s]) else -1L
                    if (seg is Segment.Body && seg.toFrame - f > 4 * ramp && seg.frames > 4000) {
                        val g = seg.fromFrame + rnd.nextLong(0, seg.frames - 2000)
                        command = EngineCommand.Seek(g)
                        expected = p + ramp + (seg.toFrame - g) + (s + 1 until lens.size).sumOf { lens[it] }
                    } else {
                        command = EngineCommand.Skip
                        val j = (s + 1 until lens.size).firstOrNull { arr.program.segments[it] is Segment.Body }
                        expected = if (j == null) p + endFade else p + (j until lens.size).sumOf { lens[it] }
                    }
                }
                else -> { // pause for k blocks
                    val k = rnd.nextInt(1, 5)
                    command = EngineCommand.Pause
                    playAt = p + k * block
                    expected = total + k * block - ramp
                }
            }
            val out = ProgramRenderer.render(arr.program, streams, prefs, limits) { rendered, player ->
                if (rendered == p) player.submit(command!!)
                if (rendered == playAt) player.submit(EngineCommand.Play)
            }
            assertEquals(expected, out.frames.toLong(), "iteration $iteration: ${command} at $p in segment $s of ${arr.program.segments.map { it::class.simpleName }} lengths $lens")
            val report = detector.analyze(out)
            assertTrue(report.clicks.isEmpty(), "iteration $iteration: clicks after $command at $p: ${report.summary()}")
            assertTrue(out.peak() <= 1f)
        }
    }

    @Test
    fun realtimePlayerWithDecoderThreadsCompletes() {
        val prefs = PlayerFixtures.prefs(a, b)
        val player = ProgramPlayer(sr, 2, EngineLimits.PHONE, PlayerFixtures.streams(a, b), prefs, realtime = true)
        val program = PlaybackProgram(listOf(body(a, trimStart(a), trimEnd(a)), body(b, trimStart(b), trimEnd(b))))
        val sink = CapturingSink(sr, 2)
        val events = ArrayList<PlayerEvent>()
        try {
            player.submit(EngineCommand.SetProgram(program))
            val written = SinkPump(player, sink) { events += it.player.events.drain() }.run()
            events += player.events.drain()
            // Real time: a starved decoder ring plays silence and reports it; the output is the program plus exactly
            // the frames the underruns inserted.
            val underrun = events.filterIsInstance<PlayerEvent.Underrun>().sumOf { it.frames.toLong() }
            assertEquals(program.totalFrames + player.latencyFrames + underrun, written)
            assertTrue(underrun < sr, "underruns must be limited to the ring's first fill, was $underrun frames")
            val out = sink.toBuffer()
            for (ch in 0 until 2) for (v in out[ch]) assertTrue(v.isFinite())
            assertTrue(player.isEnded)
        } finally { player.close() }
    }
}
