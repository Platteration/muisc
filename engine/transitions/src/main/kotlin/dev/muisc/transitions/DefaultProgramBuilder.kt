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
 * Analysis frame positions are at `analysis.sampleRate`; they are rescaled to `prefs.sampleRate` when the two
 * differ (all program positions are engine-rate frames). Renders are trusted to be at the engine rate already.
 */
class DefaultProgramBuilder : ProgramBuilder {

    override fun bodySegment(track: TrackRef, context: PlaybackContext, prefs: TransitionPrefs, incoming: RenderedTransition?, outgoing: RenderedTransition?): Segment.Body {
        val an = track.analysis
        val scale = if (an.sampleRate == prefs.sampleRate || an.sampleRate <= 0) 1.0 else prefs.sampleRate / an.sampleRate.toDouble()
        fun f(frame: Long): Long = if (scale == 1.0) frame else Math.round(frame * scale)
        val wholeFile = context == PlaybackContext.ALBUM
        val defaultFrom = if (wholeFile) 0L else f(an.trimStartFrame)
        val defaultTo = if (wholeFile) f(an.totalFrames) else f(an.trimEndFrame)
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
        val segments = ArrayList<Segment>(queue.size * 2)
        for (i in queue.indices) {
            val incoming = if (i > 0) pairRenders[i - 1] else null
            val outgoing = if (i < queue.size - 1) pairRenders[i] else null
            segments += bodySegment(queue[i], context, prefs, incoming, outgoing)
            if (outgoing != null) segments += Segment.Rendered(queue[i], queue[i + 1], outgoing)
        }
        return PlaybackProgram(segments)
    }
}
