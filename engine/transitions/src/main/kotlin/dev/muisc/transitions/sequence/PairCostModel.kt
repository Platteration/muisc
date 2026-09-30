package dev.muisc.transitions.sequence

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.PairAnalyzer
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.planner.CompatibilityScores
import dev.muisc.transitions.planner.StrategyFamily
import dev.muisc.transitions.planner.StructTables

/**
 * How badly A → B mixes, for ordering a set: `0` = an ideal pair, larger = worse. Pure and deterministic.
 *
 * From the pair's [PairFeatures] (the same analyzer the planner uses) and the §5.2 sub-scores of
 * [CompatibilityScores]:
 *
 * ```
 * cost = 0.30·(1 − s_tempo) + 0.20·(1 − s_key) + 0.15·(1 − s_energy) + 0.10·(1 − s_grid)
 *      + 0.10·(1 − s_struct[beat-domain]) + 0.05·(1 − s_room[8 bars]) + 0.10·(1 − s_vocal)
 *      + 0.25 · [no beat-matched move is possible]
 * ```
 *
 * The last term is the gate every beat-domain strategy applies (beatMatchedBlend, bassSwap, harmonicBlend,
 * drumBreakBridge): a grid below 0.5 on either side, a tempo stretch above `prefs.maxStretchPercent`, or less than
 * half of the 8 bars they need on either side (`s_room < 0.5`). Such a pair can still be mixed, but only with the
 * moves that do not beat-match (crossfade, the ambient bridge, cuts and effects). The range is `0..1.25`.
 *
 * A pair where either track has no analysis costs [UNKNOWN_PAIR_COST]: nothing is known, so it is neither
 * preferred nor avoided.
 */
class PairCostModel(
    val pairAnalyzer: PairAnalyzer = DefaultPairAnalyzer(),
) {
    /** The terms of one pair's cost, each already weighted; [total] is their sum. */
    data class Breakdown(
        val tempo: Double,
        val key: Double,
        val energy: Double,
        val grid: Double,
        val struct: Double,
        val room: Double,
        val vocal: Double,
        /** True when no beat-matched move is possible (the [GATE_PENALTY] is included in [total]). */
        val beatMatchBlocked: Boolean,
    ) {
        val total: Double get() = tempo + key + energy + grid + struct + room + vocal + if (beatMatchBlocked) GATE_PENALTY else 0.0
    }

    /** The cost of A → B; [UNKNOWN_PAIR_COST] when either analysis is missing. */
    fun cost(a: TrackAnalysis?, b: TrackAnalysis?, prefs: TransitionPrefs): Double {
        if (a == null || b == null) return UNKNOWN_PAIR_COST
        return breakdown(a, pairAnalyzer.features(a, b, prefs), prefs).total
    }

    /** The weighted terms for a pair whose features are already known ([a] supplies the beats per bar). */
    fun breakdown(a: TrackAnalysis?, f: PairFeatures, prefs: TransitionPrefs): Breakdown {
        val bpb = a?.grid?.beatsPerBar?.takeIf { it >= 1 } ?: 4
        val sRoom = CompatibilityScores.room(f, BEAT_MIX_BARS * bpb)
        val blocked = !f.beatMatchable || f.stretchPercent > prefs.maxStretchPercent || sRoom < 0.5
        return Breakdown(
            tempo = W_TEMPO * (1.0 - CompatibilityScores.tempo(f, prefs)),
            key = W_KEY * (1.0 - CompatibilityScores.key(f)),
            energy = W_ENERGY * (1.0 - CompatibilityScores.energy(f)),
            grid = W_GRID * (1.0 - CompatibilityScores.grid(f)),
            struct = W_STRUCT * (1.0 - StructTables.prior(StrategyFamily.BEAT_DOMAIN, f.outro, f.intro)),
            room = W_ROOM * (1.0 - sRoom),
            vocal = W_VOCAL * (1.0 - CompatibilityScores.vocal(f)),
            beatMatchBlocked = blocked,
        )
    }

    companion object {
        const val W_TEMPO = 0.30
        const val W_KEY = 0.20
        const val W_ENERGY = 0.15
        const val W_GRID = 0.10
        const val W_STRUCT = 0.10
        const val W_ROOM = 0.05
        const val W_VOCAL = 0.10

        /** Added when no beat-matched move is possible for the pair. */
        const val GATE_PENALTY = 0.25

        /** Bars each side the beat-domain strategies need (their `NEEDED_BARS`). */
        const val BEAT_MIX_BARS = 8

        /** Cost of a pair where either track is not analysed yet: no information, a mid-range value. */
        const val UNKNOWN_PAIR_COST = 0.5

        /**
         * The track-level energy an arc follows, 0..1, comparable across tracks (bar energies are normalised per
         * track, so they are not): `0.4·loudness + 0.3·tempo + 0.3·percussiveness` with loudness mapped from
         * −20..−6 LUFS, tempo from 70..170 BPM (0.5 when unknown) and the mean bar
         * percussiveness (0.5 when the analysis has none). Each part is clamped to 0..1.
         */
        fun energyProxy(a: TrackAnalysis): Double {
            val lufs = a.loudness.integratedLufs.toDouble()
            val loud = if (lufs.isFinite()) ((lufs + 20.0) / 14.0).coerceIn(0.0, 1.0) else 0.0
            val bpm = if (a.tempo.bpm > 0) a.tempo.bpm else a.grid.bpm
            val tempo = if (bpm > 0) ((bpm - 70.0) / 100.0).coerceIn(0.0, 1.0) else 0.5
            val perc = a.bars.percussiveness
            val percussive = if (perc.isEmpty()) 0.5 else (perc.sumOf { it.toDouble() } / perc.size).coerceIn(0.0, 1.0)
            return 0.4 * loud + 0.3 * tempo + 0.3 * percussive
        }
    }
}
