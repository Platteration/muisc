package dev.muisc.player

import dev.muisc.audio.EngineStreamFactory
import dev.muisc.dsp.loudness.TruePeakLimiter
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.Segment
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.live.Deck as LiveDeck
import dev.muisc.transitions.live.LiveNode
import dev.muisc.transitions.live.LivePlan
import dev.muisc.transitions.live.LivePoint
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Walks a [dev.muisc.transitions.PlaybackProgram] sample-accurately. One code path plays albums (gapless bodies),
 * rendered transitions and live DJ moves; the same player writes WAV in the CLI and feeds `AudioTrack`.
 *
 * Threads: [submit] from any thread; [render] only from the audio thread. Commands apply at block boundaries.
 * Per block: (1) apply commands; (2) pull from the current segment — `Body` from its track's [PcmRing] with the
 * deck gain applied, `Rendered` from its buffer, `Live` / `LiveCrossfade` through a [LiveGraph] reading both rings;
 * (3) on a segment end inside the block, switch to the next segment and let the [SeamFader] (LINEAR,
 * `limits.seamFadeFrames`) blend the continuation of the previous segment into the head of the new one — only over
 * the frames that continuation really had, so a body that ran to the end of its file hands over untouched and album
 * gapless output stays bit-exact; (4) master: pause / resume 10 ms ramps, a duck gain ([setDuckDb]), and a true-peak
 * limiter at −1 dBTP that is bit-transparent below the ceiling. The limiter delays the output by [latencyFrames]
 * (0 when disabled); the delay is flushed at the end, so the total output is `program frames + paused silence +
 * latencyFrames` plus whatever a command added (a seek's fade, a skip's 20 ms fade-out at the end of the queue) and,
 * in real time only, the silence an underrun inserted.
 *
 * Position reporting: during `Rendered` / `Live` segments the reported track is A until the plan's handover marker
 * (`Marker("B enters")`, else the midpoint), then B, with a [PlayerEvent.TrackChanged] there.
 *
 * `realtime = true` gives every body deck a producer thread and never blocks the audio thread (starvation → silence
 * plus [PlayerEvent.Underrun]); `realtime = false` (offline rendering, tests) decodes on the calling thread and
 * waits for data, so the output is deterministic. Allocation-free on the audio thread after construction except
 * for segment switches (sources, decks) and per-block position objects.
 */
class ProgramPlayer(
    val sampleRate: Int,
    val channels: Int,
    val limits: EngineLimits,
    val streams: EngineStreamFactory,
    val prefs: TransitionPrefs,
    val realtime: Boolean = true,
    val limiterEnabled: Boolean = true,
) : AutoCloseable {
    init { require(sampleRate > 0 && channels > 0) }

    val commands = CommandQueue<EngineCommand>()
    val events = EventQueue<PlayerEvent>()

    /** Position after the last rendered block (volatile snapshot for other threads). */
    @Volatile
    var position: ProgramPosition = ProgramPosition.NONE
        private set

    private val maxBlock = max(limits.blockFrames, 4096)
    private val chunkFrames = 2048
    private val ringCapacity = max((limits.ringSec * sampleRate).toInt(), chunkFrames * 4 + maxBlock * 2)

    private val limiter: TruePeakLimiter? = if (limiterEnabled) TruePeakLimiter(sampleRate, channels, ceilingDbtp = CEILING_DBTP, maxBlockFrames = maxBlock) else null

    /** Delay of the output behind the program cursor (the limiter's look-ahead). */
    val latencyFrames: Int = limiter?.latencyFrames ?: 0

    private val seam = SeamFader(max(limits.seamFadeFrames, limits.inexactSeamFadeFrames), channels)
    private val aBlock = Array(channels) { FloatArray(maxBlock) }
    private val bBlock = Array(channels) { FloatArray((maxBlock * 1.1).toInt() + 32) }
    private val silence = Array(channels) { FloatArray(maxBlock) }

    // ---- program state (audio thread only) ----
    private val segments = ArrayList<Segment>()
    private val prebuilt = IdentityHashMap<Segment, LiveGraph>()
    private val decks = HashMap<String, Deck>()
    private var segIndex = -1
    private var source: Source? = null
    private var sharedGainDb: Float? = null
    private var ended = true
    private var endFlushRemaining = 0
    private var reportedTrack: TrackRef? = null
    private var outputFrame = 0L
    private var lastPositionEvent = 0L
    private val positionInterval = sampleRate / 10

    // ---- master section ----
    private enum class Transport { PLAYING, FADING_OUT, PAUSED, FADING_IN }
    private var transport = Transport.PLAYING
    private var masterGain = 1f
    /** Pause / resume / seek ramps: 10 ms, integer-driven so a ramp is exactly [rampFrames] frames. */
    val rampFrames: Int = max(1, sampleRate / 100)
    private var rampPos = 0
    private var pendingSeek = -1L
    @Volatile private var duckTargetDb = 0f
    private var duckGain = 1f
    private var duckTarget = 1f
    private val duckStep = 1f / max(1, sampleRate / 20) // 50 ms

    /** True once a Pause completed (silence is being rendered) or while fading out. */
    val isPaused: Boolean get() = transport == Transport.PAUSED || transport == Transport.FADING_OUT

    /** True when the program has ended and the output is fully flushed. */
    val isEnded: Boolean get() = ended && endFlushRemaining == 0

    /** Index of the segment currently playing (-1 without a program). */
    val currentSegmentIndex: Int get() = segIndex

    fun submit(cmd: EngineCommand) { commands.offer(cmd) }

    /** Audio-focus duck: target attenuation in dB (≤ 0), reached with a 50 ms ramp. */
    fun setDuckDb(db: Float) { duckTargetDb = min(0f, db) }

    /** Copy of the current segment list (audio-thread state; call between [render]s or from the audio thread). */
    fun programSnapshot(): List<Segment> = ArrayList(segments)

    /**
     * Renders up to [frames] frames into `dst[c][0 until n]` and returns `n`. Returns fewer than [frames] only at the
     * end of the program and 0 once everything (including the limiter tail) has been delivered.
     */
    fun render(dst: Array<FloatArray>, frames: Int): Int {
        require(dst.size == channels)
        var done = 0
        while (done < frames) {
            val n = renderBlock(dst, done, min(maxBlock, frames - done))
            if (n == 0) break
            done += n
        }
        return done
    }

    private fun renderBlock(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        applyCommands()
        if (ended) return flushEnd(dst, offset, frames)
        var produced = 0
        while (produced < frames) {
            if (transport == Transport.PAUSED) {
                for (c in 0 until channels) java.util.Arrays.fill(dst[c], offset + produced, offset + frames, 0f)
                produced = frames
                break
            }
            val src = source
            if (src == null) {
                // A skip (or a queue edit) past the last segment: fade the retained continuation out to silence
                // instead of cutting the signal dead, then end.
                if (!seam.active) { finishProgram(); break }
                val n = min(frames - produced, seam.remaining)
                for (c in 0 until channels) java.util.Arrays.fill(dst[c], offset + produced, offset + produced + n, 0f)
                seam.apply(dst, offset + produced, n)
                applyMaster(dst, offset + produced, n)
                outputFrame += n
                produced += n
                continue
            }
            var want = frames - produced
            if (transport == Transport.FADING_OUT || transport == Transport.FADING_IN) want = min(want, rampRemaining())
            val rem = src.remaining
            if (rem <= 0L) { if (!switchToNext(limits.seamFadeFrames)) { finishProgram(); break } else continue }
            val n = min(want.toLong(), rem).toInt()
            src.pull(dst, offset + produced, n)
            if (seam.active) seam.apply(dst, offset + produced, n)
            applyMaster(dst, offset + produced, n)
            val before = src.produced
            src.produced += n
            outputFrame += n
            produced += n
            checkHandover(src, before)
            if (src.remaining <= 0L) {
                if (!switchToNext(limits.seamFadeFrames)) { finishProgram(); break }
            }
            if (transport == Transport.PAUSED) continue
        }
        if (produced > 0) {
            limiter?.process(dst, dst, produced, offset, offset)
            updatePosition()
        }
        if (ended && produced < frames) produced += flushEnd(dst, offset + produced, frames - produced)
        return produced
    }

    private fun flushEnd(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        if (endFlushRemaining <= 0) return 0
        val n = min(frames, endFlushRemaining)
        for (c in 0 until channels) java.util.Arrays.fill(dst[c], offset, offset + n, 0f)
        limiter?.process(silence, dst, n, 0, offset)
        endFlushRemaining -= n
        return n
    }

    private fun finishProgram() {
        if (ended) return
        ended = true
        endFlushRemaining = latencyFrames
        source?.release()
        source = null
        events.offer(PlayerEvent.Ended(outputFrame))
        updatePosition()
    }

    // ------------------------------------------------------------------------------------------------ master

    private fun rampRemaining(): Int = when (transport) {
        Transport.FADING_OUT, Transport.FADING_IN -> max(1, rampFrames - rampPos)
        else -> Int.MAX_VALUE
    }

    private fun startRamp(to: Transport) {
        val reversing = (transport == Transport.FADING_OUT && to == Transport.FADING_IN) || (transport == Transport.FADING_IN && to == Transport.FADING_OUT)
        rampPos = if (reversing) rampFrames - rampPos else 0
        transport = to
    }

    private fun applyMaster(dst: Array<FloatArray>, offset: Int, n: Int) {
        val targetDb = duckTargetDb
        val newTarget = if (targetDb == 0f) 1f else DeckGain.linear(targetDb)
        if (newTarget != duckTarget) duckTarget = newTarget
        val ramping = transport == Transport.FADING_OUT || transport == Transport.FADING_IN
        if (!ramping && duckGain == duckTarget && duckGain == 1f) return
        val inv = 1f / rampFrames
        var g = masterGain
        var d = duckGain
        var pos = rampPos
        for (i in 0 until n) {
            if (ramping) {
                pos++
                g = if (transport == Transport.FADING_OUT) 1f - pos * inv else pos * inv
                g = g.coerceIn(0f, 1f)
            }
            if (d < duckTarget) d = min(duckTarget, d + duckStep) else if (d > duckTarget) d = max(duckTarget, d - duckStep)
            val k = g * d
            val idx = offset + i
            for (c in 0 until channels) dst[c][idx] *= k
        }
        masterGain = g
        duckGain = d
        rampPos = pos
        if (transport == Transport.FADING_OUT && rampPos >= rampFrames) {
            masterGain = 0f
            rampPos = 0
            val seekTo = pendingSeek
            if (seekTo >= 0) { pendingSeek = -1L; performSeek(seekTo); transport = Transport.FADING_IN } else transport = Transport.PAUSED
        } else if (transport == Transport.FADING_IN && rampPos >= rampFrames) {
            masterGain = 1f
            rampPos = 0
            transport = Transport.PLAYING
        }
    }

    // ------------------------------------------------------------------------------------------------ commands

    private fun applyCommands() {
        while (true) {
            val cmd = commands.poll() ?: break
            when (cmd) {
                is EngineCommand.Play -> onPlay()
                is EngineCommand.Pause -> onPause()
                is EngineCommand.Seek -> onSeek(cmd.frame)
                is EngineCommand.Skip -> onSkip()
                is EngineCommand.SetProgram -> onSetProgram(cmd)
                is EngineCommand.ReplaceTail -> onReplaceTail(cmd)
            }
        }
    }

    private fun onPlay() {
        when (transport) {
            Transport.PAUSED, Transport.FADING_OUT -> if (pendingSeek < 0) startRamp(Transport.FADING_IN)
            else -> {}
        }
    }

    private fun onPause() {
        when (transport) {
            Transport.PLAYING, Transport.FADING_IN -> if (pendingSeek < 0) startRamp(Transport.FADING_OUT)
            else -> {}
        }
    }

    private fun onSetProgram(cmd: EngineCommand.SetProgram) {
        source?.release(); source = null
        for (d in decks.values) d.close()
        decks.clear()
        segments.clear(); prebuilt.clear()
        segments.addAll(cmd.program.segments)
        for ((i, g) in cmd.prebuilt) segments.getOrNull(i)?.let { prebuilt[it] = g }
        sharedGainDb = cmd.sharedGainDb
        seam.cancel()
        ended = false
        endFlushRemaining = 0
        pendingSeek = -1L
        if (transport == Transport.FADING_OUT) { transport = Transport.PAUSED; masterGain = 0f; rampPos = 0 }
        if (segments.isEmpty()) { segIndex = -1; finishProgram(); return }
        segIndex = 0
        startSegment(0, null)
    }

    private fun onReplaceTail(cmd: EngineCommand.ReplaceTail) {
        cmd.sharedGainDb?.let { sharedGainDb = it }
        val from = cmd.fromSegmentIndex.coerceIn(0, segments.size)
        val newList = ArrayList<Segment>(from + cmd.segments.size)
        for (i in 0 until from) newList += segments[i]
        newList.addAll(cmd.segments)
        val newPrebuilt = IdentityHashMap<Segment, LiveGraph>()
        for (i in 0 until from) prebuilt[segments[i]]?.let { newPrebuilt[segments[i]] = it }
        for ((i, g) in cmd.prebuilt) cmd.segments.getOrNull(i)?.let { newPrebuilt[it] = g }
        val cur = source
        if (segIndex < from || cur == null || ended) {
            replaceList(newList, newPrebuilt)
            if (ended && newList.size > segments.size && segIndex + 1 < newList.size) {
                // Program had ended: continue with the appended segments.
                ended = false; endFlushRemaining = 0
                segIndex += 1
                startSegment(segIndex, null)
            }
            return
        }
        // The current segment is being replaced.
        val curSeg = cur.segment
        val same = newList.getOrNull(segIndex)
        if (same != null && (same == curSeg || cur.adoptable(same))) {
            replaceList(newList, newPrebuilt)
            cur.adopt(same)
            return
        }
        val track = cur.nowPlaying()
        val pos = cur.trackFrame()
        var adoptAt = -1
        for (k in cmd.segments.indices) {
            val s = cmd.segments[k]
            if (s is Segment.Body && s.track.id == track.id && cur is BodySource && pos >= s.fromFrame && pos < s.toFrame) { adoptAt = from + k; break }
        }
        replaceList(newList, newPrebuilt)
        if (adoptAt >= 0) {
            segIndex = adoptAt
            cur.adopt(newList[adoptAt])
            events.offer(PlayerEvent.SegmentStarted(segIndex, newList[adoptAt], outputFrame))
            return
        }
        var target = from
        val first = cmd.segments.firstOrNull()
        if (first is Segment.Body && first.track.id == track.id && first.toFrame <= pos) target = from + 1
        if (target >= segments.size) { source?.let { endWithFade(it) } ?: finishProgram(); return }
        jumpTo(target, null, limits.inexactSeamFadeFrames)
    }

    private fun replaceList(newList: List<Segment>, newPrebuilt: IdentityHashMap<Segment, LiveGraph>) {
        segments.clear(); segments.addAll(newList)
        prebuilt.clear(); prebuilt.putAll(newPrebuilt)
    }

    private fun onSkip() {
        val cur = source ?: return
        var j = segIndex + 1
        while (j < segments.size && segments[j] !is Segment.Body) j++
        if (j >= segments.size) { endWithFade(cur); return }
        jumpTo(j, null, limits.inexactSeamFadeFrames)
    }

    /** Ends the program after a [SeamFader]-shaped fade-out of what [cur] would have played next (no click). */
    private fun endWithFade(cur: Source) {
        seam.begin(cur.continuation(seam.tail, min(limits.inexactSeamFadeFrames, seam.fadeFrames)))
        cur.release()
        source = null
        segIndex = segments.size
        if (!seam.active) finishProgram()
    }

    private fun onSeek(frame: Long) {
        val cur = source ?: return
        if (cur is BodySource) {
            val target = frame.coerceIn(cur.body.fromFrame, max(cur.body.fromFrame, cur.body.toFrame - 1))
            if (transport == Transport.PAUSED) { performSeek(target); return }
            pendingSeek = target
            if (transport != Transport.FADING_OUT) startRamp(Transport.FADING_OUT)
            return
        }
        // Transition: jump into the reported track's body.
        val track = cur.nowPlaying()
        val backwards = track.id == cur.aTrack().id
        var j = if (backwards) segIndex - 1 else segIndex + 1
        while (j in segments.indices) {
            val s = segments[j]
            if (s is Segment.Body && s.track.id == track.id) break
            j += if (backwards) -1 else 1
        }
        if (j !in segments.indices) return
        val body = segments[j] as Segment.Body
        jumpTo(j, frame.coerceIn(body.fromFrame, max(body.fromFrame, body.toFrame - 1)), limits.inexactSeamFadeFrames)
    }

    private fun performSeek(frame: Long) {
        val cur = source as? BodySource ?: return
        cur.seekTo(frame)
    }

    // ------------------------------------------------------------------------------------------------ segments

    /** Switches to the next segment at the end of the current one. Returns false at the end of the program. */
    private fun switchToNext(fade: Int): Boolean {
        val next = segIndex + 1
        if (next >= segments.size) return false
        jumpTo(next, null, fade)
        return true
    }

    /** Cuts to segment [index] (optionally at [trackFrame] for bodies) with a seam fade of [fade] frames. */
    private fun jumpTo(index: Int, trackFrame: Long?, fade: Int) {
        val cur = source
        if (cur != null) {
            // Only the frames the previous segment really could have continued with are blended: a segment that ran
            // to the end of its file has no continuation, and blending silence into the next head would fade it in
            // (album gapless must stay bit-exact).
            seam.begin(if (fade > 0) cur.continuation(seam.tail, min(fade, seam.fadeFrames)) else 0)
            cur.release()
        }
        segIndex = index
        startSegment(index, trackFrame)
    }

    private fun startSegment(index: Int, trackFrame: Long?) {
        val seg = segments[index]
        val src: Source = when (seg) {
            is Segment.Body -> BodySource(seg, deckFor(seg.track), gainFor(seg.track), trackFrame ?: seg.fromFrame)
            is Segment.Rendered -> RenderedSource(seg, deckFor(seg.to), gainFor(seg.to))
            is Segment.Live -> LiveSource(seg, seg.plan, seg.from, seg.to, prebuilt[seg] ?: LiveGraph(seg.plan, sampleRate, channels, maxBlock))
            is Segment.LiveCrossfade -> {
                val plan = crossfadePlan(seg)
                LiveSource(seg, plan, seg.from, seg.to, prebuilt[seg] ?: LiveGraph(plan, sampleRate, channels, maxBlock))
            }
        }
        source = src
        src.start()
        closeUnusedDecks(index)
        events.offer(PlayerEvent.SegmentStarted(index, seg, outputFrame))
        reportTrack(src.nowPlaying())
    }

    private fun closeUnusedDecks(index: Int) {
        if (decks.size <= 2) return
        val keep = HashSet<String>()
        for (i in max(0, index - 1)..min(segments.size - 1, index + 1)) for (t in tracksOf(segments[i])) keep += t.id
        val it = decks.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key !in keep) { e.value.close(); it.remove() } }
    }

    private fun tracksOf(s: Segment): List<TrackRef> = when (s) {
        is Segment.Body -> listOf(s.track)
        is Segment.Rendered -> listOf(s.from, s.to)
        is Segment.LiveCrossfade -> listOf(s.from, s.to)
        is Segment.Live -> listOf(s.from, s.to)
    }

    private fun deckFor(track: TrackRef): Deck = decks.getOrPut(track.id) { Deck(track) }

    private fun gainFor(track: TrackRef): Float = DeckGain.linear(sharedGainDb ?: DeckGain.of(track.analysis, prefs))

    private fun crossfadePlan(seg: Segment.LiveCrossfade): LivePlan {
        val len = (seg.aToFrame - seg.aFromFrame).toInt()
        val fade = seg.fadeFrames.coerceIn(1, max(1, len))
        return LivePlan(
            kind = "crossfade", aFromFrame = seg.aFromFrame, aToFrame = seg.aToFrame, bFromFrame = seg.bFromFrame, outputFrames = len,
            nodes = listOf(
                LiveNode.Gain(LiveDeck.A, listOf(LivePoint(0, 1f, FadeLaw.EQUAL_POWER), LivePoint(fade, 0f))),
                LiveNode.Gain(LiveDeck.B, listOf(LivePoint(0, 0f, FadeLaw.EQUAL_POWER), LivePoint(fade, 1f))),
            ),
        )
    }

    /** Reports the A→B handover of a transition segment at its exact output frame (not at the end of the block). */
    private fun checkHandover(src: Source, before: Long) {
        val h = src.handoverFrame
        if (h < 0) return
        if (before < h && src.produced >= h) reportTrack(src.nowPlaying(), outputFrame - (src.produced - h))
    }

    private fun reportTrack(t: TrackRef, atOutputFrame: Long = outputFrame) {
        if (reportedTrack?.id != t.id) {
            reportedTrack = t
            events.offer(PlayerEvent.TrackChanged(t, atOutputFrame))
        }
    }

    private fun updatePosition() {
        val src = source
        val p = if (src == null) ProgramPosition(segIndex, 0L, reportedTrack, 0L, outputFrame)
        else ProgramPosition(segIndex, src.produced, src.nowPlaying(), src.trackFrame(), outputFrame)
        position = p
        if (outputFrame - lastPositionEvent >= positionInterval || src == null) {
            lastPositionEvent = outputFrame
            events.offer(PlayerEvent.PositionUpdate(p))
        }
    }

    override fun close() {
        source?.release(); source = null
        for (d in decks.values) d.close()
        decks.clear()
    }

    // ------------------------------------------------------------------------------------------------ decks

    private inner class Deck(val track: TrackRef) : AutoCloseable {
        val ring = PcmRing({ streams.open(track.source, sampleRate, channels) }, channels, ringCapacity, chunkFrames)

        init { if (realtime) ring.start("muisc-decoder-" + track.id) }

        /** Reads [n] frames at the ring position into `dst[c][off..]`; zero-fills what the ring cannot supply. Returns real frames. */
        fun read(dst: Array<FloatArray>, off: Int, n: Int): Int {
            val got = if (realtime) ring.read(dst, off, n) else ring.readBlocking(dst, off, n)
            if (got < n) {
                for (c in 0 until channels) java.util.Arrays.fill(dst[c], off + got, off + n, 0f)
                if (realtime && !ring.atEnd) events.offer(PlayerEvent.Underrun(n - got, outputFrame))
            }
            return got
        }

        fun peek(dst: Array<FloatArray>, off: Int, n: Int): Int = if (realtime) ring.peek(dst, off, n) else ring.peekBlocking(dst, off, n)

        fun seekTo(frame: Long) { if (ring.position != frame) ring.seek(frame) }

        override fun close() = ring.close()
    }

    // ------------------------------------------------------------------------------------------------ sources

    private abstract inner class Source(val segment: Segment) {
        /** Output frames produced from this segment. */
        var produced = 0L
        abstract val remaining: Long
        /** Output frame at which the reported track flips from A to B (-1 for bodies). */
        open val handoverFrame: Long get() = -1L
        abstract fun nowPlaying(): TrackRef
        abstract fun aTrack(): TrackRef
        abstract fun trackFrame(): Long
        open fun start() {}
        /** Fills exactly [n] frames (n ≤ remaining) into `dst[c][off..]`. */
        abstract fun pull(dst: Array<FloatArray>, off: Int, n: Int)
        /** Peeks what would have played next (up to [n] frames into `dst[c][0..]`); returns frames written. */
        abstract fun continuation(dst: Array<FloatArray>, n: Int): Int
        open fun adoptable(other: Segment): Boolean = false
        open fun adopt(other: Segment) {}
        open fun release() {}
    }

    private inner class BodySource(var body: Segment.Body, val deck: Deck, val gain: Float, startFrame: Long) : Source(body) {
        var trackPos: Long = startFrame
        override val remaining: Long get() = body.toFrame - trackPos
        override fun nowPlaying(): TrackRef = body.track
        override fun aTrack(): TrackRef = body.track
        override fun trackFrame(): Long = trackPos
        override fun start() { deck.seekTo(trackPos) }

        override fun pull(dst: Array<FloatArray>, off: Int, n: Int) {
            val got = deck.read(dst, off, n)
            // Starved frames (real time) do not advance the track; a shorter file than the segment counts as silence.
            trackPos += if (got == n || deck.ring.atEnd || !realtime) n.toLong() else got.toLong()
            if (gain != 1f) for (c in 0 until channels) { val x = dst[c]; for (i in off until off + n) x[i] *= gain }
        }

        override fun continuation(dst: Array<FloatArray>, n: Int): Int {
            val got = deck.peek(dst, 0, n)
            if (gain != 1f) for (c in 0 until channels) { val x = dst[c]; for (i in 0 until got) x[i] *= gain }
            return got
        }

        fun seekTo(frame: Long) { trackPos = frame; deck.seekTo(frame) }

        override fun adoptable(other: Segment): Boolean =
            other is Segment.Body && other.track.id == body.track.id && trackPos >= other.fromFrame && trackPos < other.toFrame

        override fun adopt(other: Segment) { body = other as Segment.Body }
    }

    private inner class RenderedSource(val seg: Segment.Rendered, val bDeck: Deck, val bGain: Float) : Source(seg) {
        private val audio = seg.rendered.audio
        private val length = audio.frames.toLong()
        override val remaining: Long get() = length - produced
        override val handoverFrame: Long = seg.rendered.markers.firstOrNull { it.label.equals(MARKER_B_ENTERS, ignoreCase = true) }?.frame ?: (length / 2)
        override fun nowPlaying(): TrackRef = if (produced < handoverFrame) seg.from else seg.to
        override fun aTrack(): TrackRef = seg.from
        override fun trackFrame(): Long =
            if (produced < handoverFrame) seg.rendered.plan.aExitFrame + produced else seg.rendered.plan.bEntryFrame - (length - produced)

        override fun start() { bDeck.seekTo(seg.rendered.plan.bEntryFrame) }

        override fun pull(dst: Array<FloatArray>, off: Int, n: Int) {
            val p = produced.toInt()
            for (c in 0 until channels) System.arraycopy(audio[c], p, dst[c], off, n)
        }

        override fun continuation(dst: Array<FloatArray>, n: Int): Int {
            if (remaining > 0L) {
                val m = min(n.toLong(), remaining).toInt()
                val p = produced.toInt()
                for (c in 0 until channels) System.arraycopy(audio[c], p, dst[c], 0, m)
                return m
            }
            bDeck.seekTo(seg.rendered.plan.bEntryFrame)
            val got = bDeck.peek(dst, 0, n)
            if (bGain != 1f) for (c in 0 until channels) { val x = dst[c]; for (i in 0 until got) x[i] *= bGain }
            return got
        }

        override fun adoptable(other: Segment): Boolean = other is Segment.Rendered && other.rendered === seg.rendered
    }

    private inner class LiveSource(seg: Segment, val plan: LivePlan, val a: TrackRef, val b: TrackRef, val graph: LiveGraph) : Source(seg) {
        private val aDeck = deckFor(a)
        private val bDeck = deckFor(b)
        private val gA = gainFor(a)
        private val gB = gainFor(b)
        private var aPos = plan.aFromFrame
        private val length = plan.outputFrames.toLong()
        override val remaining: Long get() = length - produced
        override val handoverFrame: Long = length / 2
        override fun nowPlaying(): TrackRef = if (produced < handoverFrame) a else b
        override fun aTrack(): TrackRef = a
        override fun trackFrame(): Long = if (produced < handoverFrame) min(plan.aToFrame, plan.aFromFrame + produced) else plan.bFromFrame + produced

        override fun start() {
            graph.reset()
            aDeck.seekTo(plan.aFromFrame)
            bDeck.seekTo(plan.bFromFrame)
        }

        private fun readDecks(n: Int): Int {
            val aFrames = min(n.toLong(), max(0L, plan.aToFrame - aPos)).toInt()
            if (aFrames > 0) aDeck.read(aBlock, 0, aFrames)
            aPos += aFrames
            if (aFrames < n) for (c in 0 until channels) java.util.Arrays.fill(aBlock[c], aFrames, n, 0f)
            if (gA != 1f) for (c in 0 until channels) { val x = aBlock[c]; for (i in 0 until aFrames) x[i] *= gA }
            val need = min(graph.bFramesNeeded(n), bBlock[0].size)
            if (need > 0) bDeck.read(bBlock, 0, need)
            if (gB != 1f) for (c in 0 until channels) { val x = bBlock[c]; for (i in 0 until need) x[i] *= gB }
            return need
        }

        override fun pull(dst: Array<FloatArray>, off: Int, n: Int) {
            val need = readDecks(n)
            graph.process(aBlock, bBlock, dst, n, off, need)
        }

        override fun continuation(dst: Array<FloatArray>, n: Int): Int {
            if (remaining > 0L) {
                val m = min(n.toLong(), remaining).toInt()
                val need = readDecks(m)
                graph.process(aBlock, bBlock, dst, m, 0, need)
                return m
            }
            bDeck.seekTo(plan.bExitFrame())
            val got = bDeck.peek(dst, 0, n)
            if (gB != 1f) for (c in 0 until channels) { val x = dst[c]; for (i in 0 until got) x[i] *= gB }
            return got
        }

        override fun release() {
            // B's body resumes where the plan says, whatever the resampler's lookahead consumed.
            if (remaining <= 0L) bDeck.seekTo(plan.bExitFrame())
        }
    }

    companion object {
        const val CEILING_DBTP = -1.0
        const val MARKER_B_ENTERS = "B enters"
    }
}
