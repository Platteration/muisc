package dev.muisc.transitions.core

import dev.muisc.analysis.model.BeatGrid
import kotlin.math.floor

/**
 * Exact (fractional) frame of a fractional beat index — [BeatGrid.frameOfBeat] without the final rounding. Linear
 * inside a beat, extrapolated with the first/last tracked period outside; 0 for an empty grid. [sampleRate] is only
 * used for a single-beat grid (period from the bpm).
 */
fun BeatGrid.frameOfBeatExact(beat: Double, sampleRate: Int = 44100): Double {
    val f = beatFrames
    val n = f.size
    if (n == 0) return 0.0
    if (n == 1) return f[0] + beat * (periodFrames(sampleRate).takeIf { it > 0 } ?: 1.0)
    val i = floor(beat).toInt()
    return when {
        i < 0 -> f[0] + beat * (f[1] - f[0])
        i >= n - 1 -> f[n - 1] + (beat - (n - 1)) * (f[n - 1] - f[n - 2])
        else -> f[i] + (beat - i) * (f[i + 1] - f[i])
    }
}

/**
 * The same grid with every beat frame shifted by `-windowStart`, i.e. expressed relative to a decoded window that
 * starts at track frame [windowStart]. This is what [PhaseLockedDeck] expects; beat indices are unchanged (beats
 * before the window simply have negative frames).
 */
fun BeatGrid.relativeTo(windowStart: Long): BeatGrid =
    if (windowStart == 0L) this else copy(beatFrames = LongArray(beatFrames.size) { beatFrames[it] - windowStart })

/** Frames shifted by `-windowStart` and restricted to `[0, windowLength)` (window-relative onset list). */
fun LongArray.relativeTo(windowStart: Long, windowLength: Long = Long.MAX_VALUE): LongArray {
    val out = ArrayList<Long>(size)
    for (v in this) { val r = v - windowStart; if (r >= 0 && r < windowLength) out.add(r) }
    return out.toLongArray()
}
