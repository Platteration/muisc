package dev.muisc.transitions

/**
 * What the player actually plays: a sample-accurate sequence of segments. Built from the queue, the
 * playback context and the rendered transitions. Body segments reference track frames at the engine
 * format; rendered segments carry their PCM.
 */
sealed interface Segment {
    /** Frames [fromFrame, toFrame) of a track. */
    data class Body(val track: TrackRef, val fromFrame: Long, val toFrame: Long) : Segment {
        init { require(toFrame >= fromFrame) }
        val frames: Long get() = toFrame - fromFrame
    }

    /** A pre-rendered transition from [from] into [to]. */
    data class Rendered(val from: TrackRef, val to: TrackRef, val rendered: RenderedTransition) : Segment

    /**
     * A transition the player performs itself in real time (equal-power crossfade of [fadeFrames]) when no render is
     * available (skip, queue change, render deadline missed). Plays A's tail [aFromFrame, aToFrame) crossfaded into
     * B's head starting at [bFromFrame].
     */
    data class LiveCrossfade(val from: TrackRef, val to: TrackRef, val aFromFrame: Long, val aToFrame: Long, val bFromFrame: Long, val fadeFrames: Int) : Segment
}

data class PlaybackProgram(val segments: List<Segment>) {
    val totalFrames: Long get() = segments.sumOf {
        when (it) {
            is Segment.Body -> it.frames
            is Segment.Rendered -> it.rendered.audio.frames.toLong()
            is Segment.LiveCrossfade -> it.aToFrame - it.aFromFrame
        }
    }
}

/**
 * Turns a queue into segments, applying the gating rule and whatever renders are available.
 * Body boundaries follow the splice contract of [TransitionPlan]: a track that has an incoming render starts its
 * body at `incoming.plan.bEntryFrame`; a track with an outgoing render ends its body at `outgoing.plan.aExitFrame`.
 * Without renders, in [PlaybackContext.ALBUM] the whole file plays (gapless, no silence trimming); in other contexts
 * the body spans the analysed trim range.
 */
interface ProgramBuilder {
    fun bodySegment(track: TrackRef, context: PlaybackContext, prefs: TransitionPrefs, incoming: RenderedTransition?, outgoing: RenderedTransition?): Segment.Body

    /** Full program for a static queue; [renders] returns the render for a consecutive pair when one exists. */
    fun build(queue: List<TrackRef>, context: PlaybackContext, prefs: TransitionPrefs, renders: (TrackRef, TrackRef) -> RenderedTransition?): PlaybackProgram
}
