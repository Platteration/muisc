package dev.muisc.transitions

/**
 * The reference [ProgramBuilder] — exactly the semantics of the frozen interface KDoc:
 *
 *  - **Body bounds without renders.** In [PlaybackContext.ALBUM] a track's body is the whole file
 *    (`[0, totalFrames)`: gapless, no silence trimming — the album's own silences are part of the album); in every
 *    other context it spans the analysed trim range `[trimStartFrame, trimEndFrame)`.
 *  - **Body bounds with renders** (the splice contract of [TransitionPlan]): a track with an incoming render starts
 *    its body at `incoming.plan.bEntryFrame`; a track with an outgoing render ends its body at
 *    `outgoing.plan.aExitFrame`. The end is never before the start (a degenerate empty body rather than an error).
 *  - **build** walks the queue in order: for each consecutive pair the [TransitionGating] rule decides whether a
 *    transition is allowed at all; when it is, `renders(a, b)` may supply a [RenderedTransition] that is inserted
 *    as a [Segment.Rendered] between the two bodies. Pairs without a render (gated, not rendered yet, or render
 *    failed) are played body-to-body — gapless — which is the album path.
 *
 * ## Room to be a song ([minBodyFrames])
 *
 * The two transitions around a track are planned independently: the one into it decides `bEntryFrame` from B's
 * cues, the one out of it decides `aExitFrame` from A's cues. Nothing stops the second from landing at or before
 * the first — the incoming render hands over at 13.9 s while the outgoing one wants to cut at 5.6 s — and the
 * clamp in [bodySegment] then turns that into a zero-length body. That is not harmless: the program would play
 * the track's 13.9 s mark and then immediately its 5.6 s mark, jumping eight seconds *backwards* inside a song
 * that the listener never got to hear on its own.
 *
 * So [build] guarantees that every track is played for at least [minBodyFrames] — one bar of its own tempo, and
 * never more than half of what the track has to give, so that a genuinely short track (a 4-second interlude) is
 * still allowed a transition on both sides. When a body would be shorter than that, the *outgoing* render is
 * dropped and the pair is played body-to-body: outgoing is the causally later decision (in the player the
 * incoming transition is already installed, and often already sounding, by the time the next one is planned),
 * and dropping it is the escalation the design asks for — no transition rather than a wrong one. If the body is
 * still too short without it — the incoming render alone swallowed the track — the incoming render is dropped
 * too. Each step removes a render, so the repair terminates.
 *
 * Analysis frame positions are at `analysis.sampleRate`; they are rescaled to `prefs.sampleRate` when the two
 * differ (all program positions are engine-rate frames). Renders are trusted to be at the engine rate already.
 */
class DefaultProgramBuilder : ProgramBuilder {

    override fun bodySegment(track: TrackRef, context: PlaybackContext, prefs: TransitionPrefs, incoming: RenderedTransition?, outgoing: RenderedTransition?): Segment.Body {
        val (defaultFrom, defaultTo) = defaultRange(track, context, prefs)
        val from = (incoming?.plan?.bEntryFrame ?: defaultFrom).coerceAtLeast(0L)
        val to = (outgoing?.plan?.aExitFrame ?: defaultTo).coerceAtLeast(from)
        return Segment.Body(track, from, to)
    }

    override fun build(queue: List<TrackRef>, context: PlaybackContext, prefs: TransitionPrefs, renders: (TrackRef, TrackRef) -> RenderedTransition?): PlaybackProgram {
        if (queue.isEmpty()) return PlaybackProgram(emptyList())
        // Render for each consecutive pair (null = gapless body-to-body).
        val pairRenders = ArrayList<RenderedTransition?>(queue.size - 1)
        for (i in 0 until queue.size - 1) {
            val a = queue[i]; val b = queue[i + 1]
            pairRenders += if (TransitionGating.transitionsEnabled(context, a, b, prefs)) renders(a, b) else null
        }
        ensureBodies(queue, context, prefs, pairRenders)
        val segments = ArrayList<Segment>(queue.size * 2)
        for (i in queue.indices) {
            val incoming = if (i > 0) pairRenders[i - 1] else null
            val outgoing = if (i < queue.size - 1) pairRenders[i] else null
            segments += bodySegment(queue[i], context, prefs, incoming, outgoing)
            if (outgoing != null) segments += Segment.Rendered(queue[i], queue[i + 1], outgoing)
        }
        return PlaybackProgram(segments)
    }

    /**
     * Frames of [track] that must play on their own between the transitions around it: one bar of its own grid
     * (one second when it has no usable tempo), capped at half of what the track has to give so a very short
     * track is not barred from having transitions at all. Zero for a track with nothing to play.
     */
    fun minBodyFrames(track: TrackRef, context: PlaybackContext, prefs: TransitionPrefs): Long {
        val (from, to) = defaultRange(track, context, prefs)
        val playable = (to - from).coerceAtLeast(0L)
        if (playable <= 0L) return 0L
        val an = track.analysis
        val bpm = if (an.grid.bpm > 0.0) an.grid.bpm else an.tempo.bpm
        val beatsPerBar = if (an.grid.beatsPerBar > 0) an.grid.beatsPerBar else 4
        val bar = if (bpm > 0.0) Math.round(60.0 / bpm * beatsPerBar * prefs.sampleRate) else prefs.sampleRate.toLong()
        return minOf(bar, playable / 2)
    }

    /** Drops renders until every track in [queue] keeps at least [minBodyFrames] of itself (see the class doc). */
    private fun ensureBodies(queue: List<TrackRef>, context: PlaybackContext, prefs: TransitionPrefs, pairRenders: MutableList<RenderedTransition?>) {
        var repaired = true
        while (repaired) {
            repaired = false
            for (i in queue.indices) {
                val incomingIndex = i - 1
                val outgoingIndex = i
                val incoming = if (incomingIndex >= 0) pairRenders[incomingIndex] else null
                val outgoing = if (outgoingIndex < pairRenders.size) pairRenders[outgoingIndex] else null
                if (incoming == null && outgoing == null) continue
                val body = bodySegment(queue[i], context, prefs, incoming, outgoing)
                if (body.toFrame - body.fromFrame >= minBodyFrames(queue[i], context, prefs)) continue
                if (outgoing != null) pairRenders[outgoingIndex] = null else pairRenders[incomingIndex] = null
                repaired = true
            }
        }
    }

    /** The body bounds a track would have with no transition on either side. */
    private fun defaultRange(track: TrackRef, context: PlaybackContext, prefs: TransitionPrefs): Pair<Long, Long> {
        val an = track.analysis
        val scale = if (an.sampleRate == prefs.sampleRate || an.sampleRate <= 0) 1.0 else prefs.sampleRate / an.sampleRate.toDouble()
        fun f(frame: Long): Long = if (scale == 1.0) frame else Math.round(frame * scale)
        val wholeFile = context == PlaybackContext.ALBUM
        val from = if (wholeFile) 0L else f(an.trimStartFrame)
        val to = if (wholeFile) f(an.totalFrames) else f(an.trimEndFrame)
        return from to to
    }
}
