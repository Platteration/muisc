package dev.muisc.analysis.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor

enum class GridKind {
    /** Constant tempo: beat k is at firstBeatFrame + k * period (DJ-software style). */
    RIGID,
    /** Beats follow the tracked positions (tempo drifts); positions are interpolated between them. */
    FLEX,
}

/**
 * The beat grid of a track, in frames at [TrackAnalysis.sampleRate].
 *
 * `beatFrames` is always populated (for RIGID grids it is derived) and strictly increasing.
 * Beat index `i` with `(i - downbeatPhase) mod beatsPerBar == 0` is a downbeat (beat 1 of a bar).
 * Musical positions are expressed as fractional beat indices (`Double`), which is what every
 * transition strategy schedules against.
 */
@Serializable
data class BeatGrid(
    val kind: GridKind,
    val bpm: Double,
    val beatFrames: LongArray,
    val downbeatPhase: Int = 0,
    val beatsPerBar: Int = 4,
    /** 0..1: how strongly onsets support this grid. Beat-matched strategies gate on this. */
    val confidence: Float,
    /** Beat index of the first beat of the first detected 8-bar phrase (-1 if unknown). */
    val phraseStartBeat: Int = -1,
    val phraseBars: Int = 8,
) {
    init {
        require(beatsPerBar >= 1)
        require(beatFrames.isEmpty() || beatFrames.size == 1 || (1 until beatFrames.size).all { beatFrames[it] > beatFrames[it - 1] }) { "beatFrames must be strictly increasing" }
    }

    val beatCount: Int get() = beatFrames.size
    val isEmpty: Boolean get() = beatFrames.isEmpty()

    /** Average beat period in frames (from bpm when available, else from the beat array). */
    fun periodFrames(sampleRate: Int): Double = if (bpm > 0) 60.0 * sampleRate / bpm else averagePeriodFrames()

    private fun averagePeriodFrames(): Double =
        if (beatFrames.size < 2) 0.0 else (beatFrames.last() - beatFrames.first()).toDouble() / (beatFrames.size - 1)

    /** Frame of a fractional beat index; extrapolates linearly beyond the first/last tracked beat. */
    fun frameOfBeat(beat: Double): Long {
        if (beatFrames.isEmpty()) return 0L
        val p = averagePeriodFrames().takeIf { it > 0 } ?: 1.0
        if (beatFrames.size == 1) return Math.round(beatFrames[0] + beat * p)
        val i = floor(beat).toInt()
        val frac = beat - i
        return when {
            i < 0 -> Math.round(beatFrames[0] + beat * (beatFrames[1] - beatFrames[0]))
            i >= beatFrames.size - 1 -> {
                val n = beatFrames.size
                val lastP = (beatFrames[n - 1] - beatFrames[n - 2]).toDouble()
                Math.round(beatFrames[n - 1] + (beat - (n - 1)) * lastP)
            }
            else -> Math.round(beatFrames[i] + frac * (beatFrames[i + 1] - beatFrames[i]))
        }
    }

    /** Fractional beat index at a frame (inverse of [frameOfBeat]). */
    fun beatAtFrame(frame: Long): Double {
        if (beatFrames.isEmpty()) return 0.0
        if (beatFrames.size == 1) return (frame - beatFrames[0]) / (averagePeriodFrames().takeIf { it > 0 } ?: 1.0)
        val n = beatFrames.size
        if (frame <= beatFrames[0]) return (frame - beatFrames[0]).toDouble() / (beatFrames[1] - beatFrames[0])
        if (frame >= beatFrames[n - 1]) return (n - 1) + (frame - beatFrames[n - 1]).toDouble() / (beatFrames[n - 1] - beatFrames[n - 2])
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (beatFrames[mid] <= frame) lo = mid else hi = mid }
        return lo + (frame - beatFrames[lo]).toDouble() / (beatFrames[hi] - beatFrames[lo])
    }

    fun isDownbeat(beatIndex: Int): Boolean = Math.floorMod(beatIndex - downbeatPhase, beatsPerBar) == 0

    /** Index of the nearest downbeat at or after [beat] (fractional beats allowed). */
    fun nextDownbeat(beat: Double): Int {
        var i = kotlin.math.ceil(beat).toInt()
        while (!isDownbeat(i)) i++
        return i
    }

    /** Index of the nearest downbeat at or before [beat]. */
    fun previousDownbeat(beat: Double): Int {
        var i = floor(beat).toInt()
        while (!isDownbeat(i)) i--
        return i
    }

    /** Nearest downbeat index to [beat]. */
    fun nearestDownbeat(beat: Double): Int {
        val a = previousDownbeat(beat); val b = nextDownbeat(beat)
        return if (abs(beat - a) <= abs(b - beat)) a else b
    }

    /** Frames of all tracked downbeats. */
    fun downbeatFrames(): LongArray = beatFrames.indices.filter { isDownbeat(it) }.map { beatFrames[it] }.toLongArray()

    /** Bar index (0-based, may be negative before the first downbeat) of a beat index. */
    fun barOfBeat(beatIndex: Int): Int = Math.floorDiv(beatIndex - downbeatPhase, beatsPerBar)

    /** First beat index of a bar. */
    fun beatOfBar(bar: Int): Int = bar * beatsPerBar + downbeatPhase

    /** Whether [beat] is the start of a phrase (if phrase information exists; else falls back to downbeats). */
    fun isPhraseStart(beatIndex: Int): Boolean =
        if (phraseStartBeat < 0) isDownbeat(beatIndex) else Math.floorMod(beatIndex - phraseStartBeat, phraseBars * beatsPerBar) == 0

    /** Next phrase start at or after [beat]. */
    fun nextPhraseStart(beat: Double): Int {
        var i = kotlin.math.ceil(beat).toInt()
        while (!isPhraseStart(i)) i++
        return i
    }

    fun previousPhraseStart(beat: Double): Int {
        var i = floor(beat).toInt()
        while (!isPhraseStart(i)) i--
        return i
    }

    override fun equals(other: Any?): Boolean = other is BeatGrid && other.kind == kind && other.bpm == bpm && other.beatFrames.contentEquals(beatFrames) &&
        other.downbeatPhase == downbeatPhase && other.beatsPerBar == beatsPerBar && other.confidence == confidence && other.phraseStartBeat == phraseStartBeat && other.phraseBars == phraseBars
    override fun hashCode(): Int = beatFrames.contentHashCode() * 31 + bpm.hashCode()

    companion object {
        /** Builds a RIGID grid with beats every 60/bpm seconds starting at [firstBeatFrame] up to [endFrame]. */
        fun rigid(bpm: Double, sampleRate: Int, firstBeatFrame: Long, endFrame: Long, downbeatPhase: Int = 0, beatsPerBar: Int = 4, confidence: Float = 1f, phraseStartBeat: Int = -1, phraseBars: Int = 8): BeatGrid {
            require(bpm > 0)
            val period = 60.0 * sampleRate / bpm
            val n = if (endFrame <= firstBeatFrame) 0 else floor((endFrame - firstBeatFrame) / period).toInt() + 1
            val frames = LongArray(n) { Math.round(firstBeatFrame + it * period) }
            return BeatGrid(GridKind.RIGID, bpm, frames, downbeatPhase, beatsPerBar, confidence, phraseStartBeat, phraseBars)
        }

        val EMPTY = BeatGrid(GridKind.FLEX, 0.0, LongArray(0), 0, 4, 0f)
    }
}
