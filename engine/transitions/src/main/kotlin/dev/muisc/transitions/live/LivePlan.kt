package dev.muisc.transitions.live

import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import kotlinx.serialization.Serializable

enum class Deck { A, B }

/** A point of a live automation lane: output frame → value, with the shape used to reach the NEXT point. */
@Serializable
data class LivePoint(val frame: Int, val value: Float, val shape: FadeLaw = FadeLaw.LINEAR)

/**
 * Real-time-safe effect nodes for live (un-rendered) transitions. Every node is O(1) per sample, allocation-free
 * after construction and needs no look-ahead, so the player can execute it on the audio thread when no render is
 * ready (skip, deadline miss, queue edit). All frames are OUTPUT frames of the live segment; deck B starts at
 * output frame 0 (its Gain lane usually starts at 0).
 */
@Serializable
sealed interface LiveNode {
    /** Gain lane (linear gain 0..1+) on a deck. */
    @Serializable
    data class Gain(val deck: Deck, val points: List<LivePoint>) : LiveNode

    /** Variable-rate resample of B at [ratio] (|ratio-1| <= 0.02) ramping linearly to 1.0 by [settleFrame] so the seam is at ratio 1.0. */
    @Serializable
    data class Rate(val ratio: Double, val settleFrame: Int) : LiveNode

    /** LR4 low band (< splitHz) of A fades out and B's fades in over [swapFrames] starting at [atFrame]. */
    @Serializable
    data class LowSwap(val atFrame: Int, val swapFrames: Int, val splitHz: Double = 200.0) : LiveNode

    /** Resonant filter sweep on a deck (high-pass when [highPass], else low-pass) from [fromHz] to [toHz] between the frames. */
    @Serializable
    data class Sweep(val deck: Deck, val highPass: Boolean, val fromHz: Double, val toHz: Double, val q: Double, val fromFrame: Int, val toFrame: Int) : LiveNode

    /** Feedback delay on A's dry signal: A is cut at [cutFrame] and only the echo tail continues (contained within the segment). */
    @Serializable
    data class Echo(val cutFrame: Int, val delayFrames: Int, val feedback: Float, val dampHz: Double) : LiveNode
}

/**
 * A transition the player performs itself in real time. Plays A from [aFromFrame] to [aToFrame] (A's own frames)
 * mixed with B from [bFromFrame] according to [nodes]; the segment is exactly [outputFrames] long, and B's body
 * resumes at [bExitFrame].
 */
@Serializable
data class LivePlan(
    /** "crossfade" | "phraseCut" | "bassSwap" | "filterSweep" | "echoOut" */
    val kind: String,
    val aFromFrame: Long,
    val aToFrame: Long,
    val bFromFrame: Long,
    val outputFrames: Int,
    val nodes: List<LiveNode>,
) {
    init {
        require(aToFrame >= aFromFrame)
        require(outputFrames >= 0)
    }

    /** Frames of B consumed by the segment (accounts for a Rate node's ramp). */
    fun framesConsumedFromB(): Long {
        val rate = nodes.filterIsInstance<LiveNode.Rate>().firstOrNull() ?: return outputFrames.toLong()
        val settle = rate.settleFrame.coerceIn(0, outputFrames)
        val ramped = settle * (rate.ratio + 1.0) / 2.0
        return Math.round(ramped + (outputFrames - settle))
    }

    /** Where B's body resumes after this live segment. */
    fun bExitFrame(): Long = bFromFrame + framesConsumedFromB()
}

/** Builds a [LivePlan] from analyses only; must always succeed (a plain crossfade needs nothing). */
interface LivePlanFactory {
    fun plan(a: TrackRef, b: TrackRef, features: PairFeatures?, aNowFrame: Long, prefs: TransitionPrefs, fadeSecOverride: Double? = null): LivePlan
}
