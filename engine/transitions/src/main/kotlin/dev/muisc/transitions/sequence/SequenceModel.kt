package dev.muisc.transitions.sequence

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.PI
import kotlin.math.cos

/**
 * One song for [SetSequencer] to place. [analysis] is null when the song has not been analysed yet: it is placed
 * anyway, with [PairCostModel.UNKNOWN_PAIR_COST] for its pairs and no energy-arc term. [artist] feeds the soft
 * rule against the same artist back to back (blank = unknown, never matches).
 */
data class SequenceItem(
    val id: String,
    val analysis: TrackAnalysis?,
    val artist: String = "",
) {
    override fun toString(): String = id
}

/**
 * The energy shape a sequenced set follows, as a target *quantile* of the set's track energies
 * ([PairCostModel.energyProxy]) at position `k` of `n`.
 */
enum class EnergyArc(val id: String, val description: String) {
    /** No positional term: only the pair costs (which already penalise energy jumps) shape the order. */
    FLAT("flat", "no energy shape; only neighbour fit"),
    /** From the calmest songs to the most energetic: target `k / (n − 1)`. */
    RISING("rising", "calm to energetic"),
    /** Up and down every [WAVE_PERIOD] songs: target `0.5 − 0.5·cos(2π·k / WAVE_PERIOD)`. */
    WAVE("wave", "rises and falls every 10 songs"),
    /** Warm-up → peak → cool-down: rises from 0.15 to 1 over the first 70 %, then falls to 0.4. */
    PEAK("peak", "warm-up, peak at 70 %, cool-down");

    /** Target energy quantile (0..1) for position [k] of [n]; NaN for [FLAT]. */
    fun target(k: Int, n: Int): Double {
        val p = if (n <= 1) 0.0 else k.toDouble() / (n - 1)
        return when (this) {
            FLAT -> Double.NaN
            RISING -> p
            WAVE -> 0.5 - 0.5 * cos(2.0 * PI * k / WAVE_PERIOD)
            PEAK -> if (p <= PEAK_AT) 0.15 + 0.85 * p / PEAK_AT else 1.0 - 0.6 * (p - PEAK_AT) / (1.0 - PEAK_AT)
        }
    }

    companion object {
        const val WAVE_PERIOD = 10
        const val PEAK_AT = 0.7

        /** By [id] (case-insensitive), or null. */
        fun parse(text: String): EnergyArc? = entries.firstOrNull { it.id.equals(text.trim(), ignoreCase = true) }
    }
}

/**
 * How [SetSequencer] orders a set.
 *
 * @param seed every random choice (start song, noise, candidate sampling) derives from it: same items, options and
 *   seed → same order.
 * @param variety 0..1, the temperature: each pair's cost gets `variety · `[SetSequencer.NOISE_SCALE]` · u` added,
 *   `u` uniform in 0..1 seeded per (seed, A, B). 0 = the best order the search finds; 1 = close to random.
 * @param sameArtistPenalty added to a pair whose two songs have the same (non-blank, case-insensitive) artist.
 * @param arcWeight weight of `|energy quantile − arc target|` per position (ignored for [EnergyArc.FLAT]).
 * @param first index of the song that must play first (the shuffle already started it), or null to let the seed
 *   pick the start.
 * @param fullLimit [SetSequencer.sequence] optimises the whole order up to this many songs and switches to the
 *   online mode above it.
 * @param searchBudget deterministic cap on the local search's work (move evaluations plus the positions an
 *   energy-arc delta walks); it bounds the time without making the result depend on the machine's speed.
 * @param poolSize online mode: how many sampled candidates compete for each next slot.
 * @param horizon online mode: how many slots after the first are chosen by cost; the rest keep their sampled
 *   (random) order. [Int.MAX_VALUE] = all.
 */
data class SequenceOptions(
    val seed: Long = 0L,
    val arc: EnergyArc = EnergyArc.FLAT,
    val variety: Double = DEFAULT_VARIETY,
    val sameArtistPenalty: Double = DEFAULT_SAME_ARTIST_PENALTY,
    val arcWeight: Double = DEFAULT_ARC_WEIGHT,
    val first: Int? = null,
    val prefs: TransitionPrefs = TransitionPrefs(),
    val fullLimit: Int = DEFAULT_FULL_LIMIT,
    val searchBudget: Long = DEFAULT_SEARCH_BUDGET,
    val poolSize: Int = DEFAULT_POOL_SIZE,
    val horizon: Int = Int.MAX_VALUE,
) {
    init {
        require(variety.isFinite() && variety in 0.0..1.0) { "variety must be in 0..1, got $variety" }
        require(sameArtistPenalty.isFinite() && sameArtistPenalty >= 0.0) { "sameArtistPenalty must be ≥ 0" }
        require(arcWeight.isFinite() && arcWeight >= 0.0) { "arcWeight must be ≥ 0" }
        require(fullLimit >= 0) { "fullLimit must be ≥ 0" }
        require(searchBudget >= 0) { "searchBudget must be ≥ 0" }
        require(poolSize >= 1) { "poolSize must be ≥ 1" }
        require(horizon >= 0) { "horizon must be ≥ 0" }
    }

    companion object {
        const val DEFAULT_VARIETY = 0.3
        const val DEFAULT_SAME_ARTIST_PENALTY = 0.35
        const val DEFAULT_ARC_WEIGHT = 0.4
        const val DEFAULT_FULL_LIMIT = 500
        const val DEFAULT_SEARCH_BUDGET = 40_000_000L
        const val DEFAULT_POOL_SIZE = 32
    }
}

/**
 * An order of `items` (indices into the list [SetSequencer] was given).
 *
 * @param pairCosts [PairCostModel] cost of each consecutive pair (without noise or the artist penalty);
 *   `pairCosts[k]` is `order[k] → order[k + 1]`.
 * @param sameArtistPairs consecutive pairs with the same artist.
 * @param unanalysed songs placed without an analysis.
 * @param work the local search's work units (full mode) or pair costs computed (online mode).
 */
class SequenceResult(
    val order: List<Int>,
    val pairCosts: List<Double>,
    val mode: Mode,
    val sameArtistPairs: Int,
    val unanalysed: Int,
    val work: Long,
) {
    enum class Mode { FULL, ONLINE }

    val totalCost: Double get() = pairCosts.sum()
    val meanCost: Double get() = if (pairCosts.isEmpty()) 0.0 else totalCost / pairCosts.size
}
