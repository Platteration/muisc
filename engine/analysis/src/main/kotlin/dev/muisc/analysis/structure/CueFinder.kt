package dev.muisc.analysis.structure

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Cues

/**
 * Suggested mix points as beat indices of the [BeatGrid] (-1 when unknown), from the per-beat [BeatActivity].
 *
 * Definitions ("drums present at beat p" = mean [BeatActivity.drums] over the bar `[p, p + beatsPerBar)` ≥
 * [BeatActivity.DRUMS_PRESENT]; "phrase start" = [BeatGrid.isPhraseStart], which itself falls back to downbeats
 * when the grid carries no phrase information):
 *
 * - `firstDownbeat`: first downbeat index whose frame is ≥ `trimStartFrame`.
 * - `lastDownbeat`: last downbeat index whose frame is < `trimEndFrame`. Both are -1 (and every other cue too)
 *   when the trimmed region contains no downbeat.
 * - `mixInBeat`: first phrase start ≥ firstDownbeat at which drums are present; else firstDownbeat.
 * - `mixOutBeat`: last phrase start at which drums are present and at least [minMixOutBars] bars remain before
 *   the drums leave (`drumsEnd` = end of the last bar with drums present) or the track ends; else, when a
 *   `fadeStartFrame` ≥ 0 is given, the downbeat at or before `fadeStart − minMixOutBars` bars; else the downbeat
 *   `lastDownbeat − minMixOutBars` bars (never before firstDownbeat).
 * - `dropBeat`: the phrase start with the largest positive jump of mean phrase energy from the preceding phrase
 *   (the partial phrase between firstDownbeat and the first phrase start counts as a phrase), or -1 when the
 *   largest jump is below [dropJump].
 */
class CueFinder(
    /** Bars of drums that must remain after the mix-out point. */
    val minMixOutBars: Int = 8,
    /** Minimum phrase-to-phrase energy jump (0..1 scale) for a drop. */
    val dropJump: Float = 0.2f,
) {
    fun find(grid: BeatGrid, activity: BeatActivity, trimStartFrame: Long, trimEndFrame: Long, fadeStartFrame: Long = -1L): Cues {
        val n = grid.beatCount
        if (n == 0) return Cues()
        val bpb = grid.beatsPerBar
        val first = (0 until n).firstOrNull { grid.isDownbeat(it) && grid.beatFrames[it] >= trimStartFrame } ?: -1
        val last = (n - 1 downTo 0).firstOrNull { grid.isDownbeat(it) && grid.beatFrames[it] < trimEndFrame } ?: -1
        if (first < 0 || last < 0 || first > last) return Cues() // no downbeat inside [trimStart, trimEnd)
        if (activity.beatCount != n) return Cues(firstDownbeat = first, lastDownbeat = last)

        fun drumsAt(p: Int) = activity.meanDrums(p, p + bpb) >= BeatActivity.DRUMS_PRESENT
        val phraseStarts = (first..last).filter { grid.isPhraseStart(it) }

        // mix in
        val mixIn = phraseStarts.firstOrNull { drumsAt(it) } ?: first

        // drums end: end of the last bar (aligned to downbeats) with drums present
        var drumsEnd = -1
        var b = last
        while (b >= first) {
            if (drumsAt(b)) { drumsEnd = minOf(b + bpb, n); break }
            b -= bpb
        }
        val minBeats = minMixOutBars * bpb
        val mixOut = phraseStarts.lastOrNull { p -> drumsEnd >= 0 && drumsAt(p) && drumsEnd - p >= minBeats }
            ?: run {
                val anchor = if (fadeStartFrame >= 0) grid.previousDownbeat(grid.beatAtFrame(fadeStartFrame)) else last
                grid.previousDownbeat((anchor - minBeats).toDouble()).coerceIn(first, last)
            }

        // drop: largest positive energy jump between consecutive phrases
        val bounds = ArrayList<Int>()
        if (phraseStarts.isEmpty() || phraseStarts.first() > first) bounds.add(first)
        bounds.addAll(phraseStarts)
        bounds.add(last + bpb)
        var bestJump = 0f
        var drop = -1
        var prevE = activity.meanEnergy(bounds[0], bounds[1])
        for (i in 1 until bounds.size - 1) {
            val e = activity.meanEnergy(bounds[i], bounds[i + 1])
            val jump = e - prevE
            if (jump > bestJump) { bestJump = jump; drop = bounds[i] }
            prevE = e
        }
        if (bestJump < dropJump) drop = -1
        return Cues(mixOutBeat = mixOut, mixInBeat = mixIn, firstDownbeat = first, lastDownbeat = last, dropBeat = drop)
    }
}
