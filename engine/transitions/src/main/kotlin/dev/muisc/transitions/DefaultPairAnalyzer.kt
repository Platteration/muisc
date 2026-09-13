package dev.muisc.transitions

import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.TrackAnalysis
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The reference [PairAnalyzer]: derives every [PairFeatures] field from two [TrackAnalysis] objects and the
 * preferences. Pure and deterministic (no audio, no randomness, no state).
 *
 * Definitions (A = outgoing track, B = incoming track; `bpb` = beats per bar of the respective grid):
 *
 * **Tempo.** `r0 = B.bpm / A.bpm` (from `tempo.bpm`, falling back to `grid.bpm`). Three interpretations are
 * considered: SAME `r0`, HALF `r0 / 2` (B's beats read in half time, i.e. B.bpm/2 ≈ A.bpm) and DOUBLE `2·r0`
 * (B read in double time). The one closest to 1 *in the log domain* (minimum `|ln r|`) wins, so the choice is
 * symmetric: swapping A and B yields the reciprocal ratio with the mirrored relation. `tempoRatio` is the chosen
 * ratio and `stretchPercent = |tempoRatio − 1| · 100` is the total tempo change needed to match the pair — the
 * planner may apply all of it to one deck or split it between decks (e.g. half on each), the number is the same.
 * When either bpm is unknown (≤ 0): ratio 1, SAME, 0 %.
 *
 * **Key.** `keyA = A.outroKey ?: A.key`, `keyB = B.introKey ?: B.key`; `camelotDistance = keyA.camelot.distanceTo(keyB.camelot)`.
 * `bestPitchShiftSemitones` is the shift `s` of B in `[−S, S]`, `S = min(6, ceil(prefs.maxPitchShiftSemitones))`,
 * that minimises the Camelot distance between keyA and keyB transposed by `s` semitones (tonic + s mod 12, mode
 * unchanged); ties are broken toward the smallest `|s|`, then the upward shift. It is 0 whenever the pair is
 * already compatible (`camelotDistance ≤ 1`). `camelotDistanceAfterShift` is the distance at that shift.
 *
 * **Level.** `loudnessDeltaLu = B.integratedLufs − A.integratedLufs` (0 when either is silence / non-finite).
 *
 * **Tail and head bars.** A's *tail* is the last [WINDOW_BARS] bars that play in full before mixing out starts:
 * the bars ending (exclusive) at the bar of `A.cues.mixOutBeat`, or the last [WINDOW_BARS] bars of A when the cue
 * is unknown; if that leaves no bars, the first [WINDOW_BARS] bars. B's *head* is the first [WINDOW_BARS] bars
 * that play in full once B is in: starting at the bar of `B.cues.mixInBeat` (falling back to `firstDownbeat`,
 * then bar 0), clamped so that at least the last [WINDOW_BARS] bars are used. Bar indices come from the grid
 * ([BeatGrid.barOfBeat]); with an empty grid a bar is `beat / 4`.
 *  - `energyDelta = mean(B.bars.energy[head]) − mean(A.bars.energy[tail])`, clamped to −1..1 (0 when either
 *    track has no bar features).
 *  - `vocalClash = mean(A.bars.vocalActivity[tail]) · mean(B.bars.vocalActivity[head])`, 0..1.
 *  - `lowEndShareA/B = mean(sub + bass)` over the tail / head bars, clamped to 0..1.
 *
 * **Spectrum.** `spectralSimilarity` = cosine similarity of `A.outroLtasDb` (fallback `A.ltasDb`) and
 * `B.introLtasDb` (fallback `B.ltasDb`) after converting each band from dB to linear power (`10^(dB/10)`),
 * over their common length; 1 when either vector is empty.
 *
 * **Room.** `outroBeatsAvailable = lastBeat − mixOut` where `lastBeat = A.cues.lastDownbeat` (fallback: the last
 * grid beat) and `mixOut = A.cues.mixOutBeat` (fallback: 16 bars before `lastBeat`), ≥ 0; 0 for an empty grid.
 * `introBeatsAvailable = mixIn − firstBeat` where `mixIn = B.cues.mixInBeat` (fallback: the end of B's first
 * section, else 8 bars) and `firstBeat = B.cues.firstDownbeat` (fallback 0), clamped to `[0, beatCount]`; 0 for
 * an empty grid.
 *
 * **Confidences.** `gridConfidenceA/B = grid.confidence` (0 for an empty grid), `keyStrengthA/B` = the strength
 * of keyA / keyB as defined above.
 */
class DefaultPairAnalyzer : PairAnalyzer {

    override fun features(a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): PairFeatures {
        // --- tempo -------------------------------------------------------------------------------------
        val bpmA = bpmOf(a)
        val bpmB = bpmOf(b)
        val (relation, ratio) = if (bpmA > 0 && bpmB > 0) chooseRelation(bpmB / bpmA) else TempoRelation.SAME to 1.0
        val stretchPercent = abs(ratio - 1.0) * 100.0

        // --- key ---------------------------------------------------------------------------------------
        val keyA = a.outroKey ?: a.key
        val keyB = b.introKey ?: b.key
        val camelotDistance = keyA.camelot.distanceTo(keyB.camelot)
        val maxShift = min(6, ceil(prefs.maxPitchShiftSemitones).toInt().coerceAtLeast(0))
        val bestShift = if (camelotDistance <= 1) 0 else bestPitchShift(keyA.key, keyB.key, maxShift)
        val distanceAfterShift = if (bestShift == 0) camelotDistance else keyA.camelot.distanceTo(transpose(keyB.key, bestShift).camelot)

        // --- level -------------------------------------------------------------------------------------
        val lufsA = a.loudness.integratedLufs.toDouble()
        val lufsB = b.loudness.integratedLufs.toDouble()
        val loudnessDelta = if (isAudible(lufsA) && isAudible(lufsB)) lufsB - lufsA else 0.0

        // --- tail / head bars ----------------------------------------------------------------------------
        val tail = tailBars(a)
        val head = headBars(b)
        val energyTail = mean(a.bars.energy, tail)
        val energyHead = mean(b.bars.energy, head)
        val energyDelta = if (energyTail.isNaN() || energyHead.isNaN()) 0.0 else (energyHead - energyTail).coerceIn(-1.0, 1.0)
        val vocalClash = (mean(a.bars.vocalActivity, tail).orZero() * mean(b.bars.vocalActivity, head).orZero()).coerceIn(0.0, 1.0)
        val lowEndA = lowEndShare(a.bars, tail)
        val lowEndB = lowEndShare(b.bars, head)

        // --- spectrum ----------------------------------------------------------------------------------
        val ltasA = a.outroLtasDb.takeIf { it.isNotEmpty() } ?: a.ltasDb
        val ltasB = b.introLtasDb.takeIf { it.isNotEmpty() } ?: b.ltasDb
        val spectralSimilarity = ltasCosineSimilarity(ltasA, ltasB)

        // --- room --------------------------------------------------------------------------------------
        val outroBeats = outroBeatsAvailable(a)
        val introBeats = introBeatsAvailable(b)

        return PairFeatures(
            tempoRatio = ratio,
            tempoRelation = relation,
            stretchPercent = stretchPercent,
            camelotDistance = camelotDistance,
            bestPitchShiftSemitones = bestShift,
            camelotDistanceAfterShift = distanceAfterShift,
            loudnessDeltaLu = loudnessDelta,
            energyDelta = energyDelta,
            vocalClash = vocalClash,
            spectralSimilarity = spectralSimilarity,
            outro = a.outro,
            intro = b.intro,
            outroBeatsAvailable = outroBeats,
            introBeatsAvailable = introBeats,
            gridConfidenceA = gridConfidence(a.grid),
            gridConfidenceB = gridConfidence(b.grid),
            keyStrengthA = keyA.strength.toDouble().coerceIn(0.0, 1.0),
            keyStrengthB = keyB.strength.toDouble().coerceIn(0.0, 1.0),
            lowEndShareA = lowEndA,
            lowEndShareB = lowEndB,
        )
    }

    /** Half-open bar range `[start, end)` into a [BarFeatures] array. */
    private class Bars(val start: Int, val end: Int) {
        val isEmpty: Boolean get() = end <= start
    }

    private fun tailBars(a: TrackAnalysis): Bars {
        val n = a.bars.barCount
        if (n == 0) return Bars(0, 0)
        val mixOut = a.cues.mixOutBeat
        var end = if (mixOut >= 0) barOfBeat(a.grid, mixOut).coerceIn(0, n) else n
        var start = max(0, end - WINDOW_BARS)
        if (end <= start) { start = 0; end = min(n, WINDOW_BARS) }
        return Bars(start, end)
    }

    private fun headBars(b: TrackAnalysis): Bars {
        val n = b.bars.barCount
        if (n == 0) return Bars(0, 0)
        val cueBeat = when {
            b.cues.mixInBeat >= 0 -> b.cues.mixInBeat
            b.cues.firstDownbeat >= 0 -> b.cues.firstDownbeat
            else -> 0
        }
        var start = barOfBeat(b.grid, cueBeat).coerceAtLeast(0)
        if (start > n - WINDOW_BARS) start = max(0, n - WINDOW_BARS)
        return Bars(start, min(n, start + WINDOW_BARS))
    }

    private fun outroBeatsAvailable(a: TrackAnalysis): Int {
        val grid = a.grid
        if (grid.isEmpty) return 0
        val lastBeat = if (a.cues.lastDownbeat >= 0) a.cues.lastDownbeat else grid.beatCount - 1
        val mixOut = if (a.cues.mixOutBeat >= 0) a.cues.mixOutBeat else max(0, lastBeat - FALLBACK_OUTRO_BARS * grid.beatsPerBar)
        return max(0, lastBeat - mixOut)
    }

    private fun introBeatsAvailable(b: TrackAnalysis): Int {
        val grid = b.grid
        if (grid.isEmpty) return 0
        val firstBeat = max(0, b.cues.firstDownbeat)
        val mixIn = when {
            b.cues.mixInBeat >= 0 -> b.cues.mixInBeat
            b.sections.isNotEmpty() -> b.sections.first().endBeat
            else -> FALLBACK_INTRO_BARS * grid.beatsPerBar
        }
        return (mixIn - firstBeat).coerceIn(0, grid.beatCount)
    }

    private fun lowEndShare(bars: BarFeatures, range: Bars): Double {
        if (range.isEmpty) return 0.0
        val n = min(bars.sub.size, bars.bass.size)
        var s = 0.0
        var count = 0
        for (i in range.start until min(range.end, n)) { s += bars.sub[i] + bars.bass[i]; count++ }
        return if (count == 0) 0.0 else (s / count).coerceIn(0.0, 1.0)
    }

    private fun mean(values: FloatArray, range: Bars): Double {
        if (range.isEmpty) return Double.NaN
        var s = 0.0
        var count = 0
        for (i in range.start until min(range.end, values.size)) { s += values[i]; count++ }
        return if (count == 0) Double.NaN else s / count
    }

    private fun Double.orZero(): Double = if (isNaN()) 0.0 else this

    companion object {
        /** Number of bars in A's tail and B's head windows. */
        const val WINDOW_BARS = 8
        /** Mix-out cue fallback: this many bars before the last beat. */
        const val FALLBACK_OUTRO_BARS = 16
        /** Mix-in cue fallback when B has no cue and no sections. */
        const val FALLBACK_INTRO_BARS = 8
        /** Loudness values at or below this are treated as "no signal". */
        const val SILENCE_LUFS = -100.0

        private fun bpmOf(t: TrackAnalysis): Double = if (t.tempo.bpm > 0) t.tempo.bpm else t.grid.bpm

        /** SAME / HALF / DOUBLE interpretation of `r0 = B.bpm / A.bpm` closest to 1 in the log domain. */
        fun chooseRelation(r0: Double): Pair<TempoRelation, Double> {
            var best = TempoRelation.SAME
            var bestRatio = r0
            var bestCost = abs(ln(r0))
            val half = r0 / 2.0
            val halfCost = abs(ln(half))
            if (halfCost < bestCost) { best = TempoRelation.HALF; bestRatio = half; bestCost = halfCost }
            val double = r0 * 2.0
            val doubleCost = abs(ln(double))
            if (doubleCost < bestCost) { best = TempoRelation.DOUBLE; bestRatio = double }
            return best to bestRatio
        }

        /** [key] transposed by [semitones] (mode unchanged). */
        fun transpose(key: MusicalKey, semitones: Int): MusicalKey = MusicalKey(Math.floorMod(key.tonic + semitones, 12), key.mode)

        /**
         * Shift of [keyB] in `[-maxShift, maxShift]` minimising the Camelot distance to [keyA]; ties → smallest
         * |shift|, then the upward shift.
         */
        fun bestPitchShift(keyA: MusicalKey, keyB: MusicalKey, maxShift: Int): Int {
            val camelotA = keyA.camelot
            var best = 0
            var bestDistance = camelotA.distanceTo(keyB.camelot)
            for (magnitude in 1..maxShift) {
                for (s in intArrayOf(magnitude, -magnitude)) {
                    val d = camelotA.distanceTo(transpose(keyB, s).camelot)
                    if (d < bestDistance) { bestDistance = d; best = s }
                }
            }
            return best
        }

        /** Cosine similarity of two dB spectra in the linear power domain over their common length; 1 when either is empty. */
        fun ltasCosineSimilarity(aDb: FloatArray, bDb: FloatArray): Double {
            val n = min(aDb.size, bDb.size)
            if (n == 0) return 1.0
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in 0 until n) {
                val pa = 10.0.pow(aDb[i] / 10.0)
                val pb = 10.0.pow(bDb[i] / 10.0)
                dot += pa * pb; na += pa * pa; nb += pb * pb
            }
            if (na <= 0.0 || nb <= 0.0) return 1.0
            return (dot / sqrt(na * nb)).coerceIn(0.0, 1.0)
        }

        private fun isAudible(lufs: Double): Boolean = lufs.isFinite() && lufs > SILENCE_LUFS

        private fun gridConfidence(grid: BeatGrid): Double = if (grid.isEmpty) 0.0 else grid.confidence.toDouble().coerceIn(0.0, 1.0)

        private fun barOfBeat(grid: BeatGrid, beat: Int): Int = if (grid.isEmpty) beat / 4 else grid.barOfBeat(beat)

    }
}
