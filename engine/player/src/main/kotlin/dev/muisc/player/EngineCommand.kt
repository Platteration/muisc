package dev.muisc.player

import dev.muisc.transitions.PlaybackProgram
import dev.muisc.transitions.Segment
import dev.muisc.transitions.TrackRef
import java.util.concurrent.ConcurrentLinkedQueue

/** Commands submitted to [ProgramPlayer] from any thread; applied by the audio thread at block boundaries. */
sealed interface EngineCommand {
    data object Play : EngineCommand
    data object Pause : EngineCommand

    /** Seek inside the current body (10 ms fade-out, ring seek, 10 ms fade-in); during a transition, jumps into the reported track's body. */
    data class Seek(val frame: Long) : EngineCommand

    /** Jump to the next body segment (B's body start when a transition is playing) with a 20 ms seam fade. */
    data object Skip : EngineCommand

    /**
     * Replaces the whole program. [sharedGainDb] forces one deck gain on every body (album playback: the gain of the
     * loudest track, so album dynamics are preserved); null = per-track [dev.muisc.transitions.core.DeckGain].
     * [prebuilt] maps indices into `program.segments` to graphs built off the audio thread for `Segment.Live`.
     */
    data class SetProgram(val program: PlaybackProgram, val sharedGainDb: Float? = null, val prebuilt: Map<Int, LiveGraph> = emptyMap()) : EngineCommand

    /**
     * Replaces `segments[fromSegmentIndex..]` with [segments]. When the segment currently playing is replaced by an
     * equal one (or a body of the same track containing the current position) playback continues uninterrupted;
     * otherwise the player cuts to the first replacement with a seam fade. [prebuilt] indices are relative to [segments].
     * [sharedGainDb] updates the forced deck gain (album playback, see [SetProgram]) for the bodies that start from
     * here on; null keeps the gain in force, and the body currently playing always keeps the gain it started with.
     */
    data class ReplaceTail(
        val fromSegmentIndex: Int,
        val segments: List<Segment>,
        val prebuilt: Map<Int, LiveGraph> = emptyMap(),
        val sharedGainDb: Float? = null,
    ) : EngineCommand
}

/** Where the player is, as reported after every block. */
data class ProgramPosition(
    val segmentIndex: Int,
    val frameInSegment: Long,
    /** The track the listener hears (A until a transition's handover marker, then B); null before any program. */
    val nowPlaying: TrackRef?,
    /** Position inside [nowPlaying] in its own frames (approximate during stretched live segments). */
    val trackFrame: Long,
    /** Frames rendered so far (program frames plus paused silence, before the limiter's delay). */
    val outputFrame: Long,
) {
    companion object { val NONE = ProgramPosition(-1, 0L, null, 0L, 0L) }
}

/** Events emitted by the audio thread and drained by the coordinator / UI thread. */
sealed interface PlayerEvent {
    data class SegmentStarted(val segmentIndex: Int, val segment: Segment, val atOutputFrame: Long) : PlayerEvent
    data class TrackChanged(val track: TrackRef, val atOutputFrame: Long) : PlayerEvent
    data class Ended(val atOutputFrame: Long) : PlayerEvent
    /** A body ring could not supply [frames] frames in time; silence was played. */
    data class Underrun(val frames: Int, val atOutputFrame: Long) : PlayerEvent
    data class PositionUpdate(val position: ProgramPosition) : PlayerEvent
}

/** Single-producer / single-consumer event queue (audio thread → coordinator). Lock-free. */
class EventQueue<T : Any> {
    private val q = ConcurrentLinkedQueue<T>()
    fun offer(e: T) { q.offer(e) }
    fun poll(): T? = q.poll()
    val isEmpty: Boolean get() = q.isEmpty()
    /** Removes and returns everything queued so far. */
    fun drain(): List<T> { val out = ArrayList<T>(); while (true) { out += q.poll() ?: break }; return out }
}

/** Command queue (any thread → audio thread). Lock-free; [snapshot] is for diagnostics and tests. */
class CommandQueue<T : Any> {
    private val q = ConcurrentLinkedQueue<T>()
    fun offer(c: T) { q.offer(c) }
    fun poll(): T? = q.poll()
    val isEmpty: Boolean get() = q.isEmpty()
    fun snapshot(): List<T> = q.toList()
}
