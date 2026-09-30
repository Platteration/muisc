package dev.muisc.analysis.rhythm

import kotlin.math.max
import kotlin.math.min

/** What [MetricalLevel.check] measured on a set of tracked beats. */
class MetricalCheck(
    /** Parity balance of the low-band (kick / bass) flux at the tracked beats, 0..1 (see [MetricalLevel]). */
    val lowBalance: Double,
    /** Parity balance of the broadband flux at the tracked beats, 0..1. */
    val broadBalance: Double,
    /** Weight of the low band in this track ([OnsetFeatures.lowBandWeight]); the check needs it above 0. */
    val lowWeight: Double,
    /** True when the tracked level is twice the beat: the tempo should be halved. */
    val halve: Boolean,
)

/**
 * Metrical-level check of a tempo estimate, made on the beats the [BeatTracker] placed at that tempo.
 *
 * The autocorrelation / harmonic-sum estimate ([TempoEstimator]) cannot tell a slow song with eighth-note hi-hats
 * from a song at twice the tempo: both have a strong pulse at the eighth-note period, and the tempo prior and the
 * preferred range lean towards the faster reading. The tracked beats can: when the tracked level is twice the
 * beat, its beats alternate between a beat (kick, bass note) and an off-beat that carries only a hi-hat.
 *
 * For every block of [blockBeats] consecutive tracked beats the flux at the even-numbered and at the odd-numbered
 * beats is summed (`E`, `O`; the flux at a beat is the maximum over ±1 ODF frame of the RAW, un-normalised flux, so
 * loud drum sections outweigh a quiet intro), and the block's balance is `min(E, O) / max(E, O)`: about 1 when every
 * tracked beat carries the same kind of event, near 0 when only every other one does. The track's balance is the
 * mean of the block balances weighted by the block's flux `E + O`, so a parity slip of the tracker costs one block,
 * not the whole measurement. This is computed on the low-band flux ([OnsetFeatures.rawLowOdf]) and on the broadband
 * flux ([OnsetFeatures.rawOdf]).
 *
 * The tempo is halved only when all of these hold:
 *  - the track has a low band at all (`lowBandWeight > 0`, [OnsetFeatures.lowBandWeightFor]);
 *  - the low band alternates: low balance below [maxLowBalance] (kick / bass on every other tracked beat only);
 *  - the broadband flux alternates too: broad balance below [maxBroadBalance]. A backbeat (kick on 1 and 3, snare
 *    on 2 and 4) alternates in the low band but not in the broadband flux, because the snare is as loud a
 *    broadband event as the kick, so it keeps its tempo;
 *  - the halved tempo is at least [minBpm].
 *
 * Measured on `muisc bench analysis` (seed 1, 48 songs) before this check existed: the three songs read at twice
 * their tempo (61–66 BPM read as 123–132) had low balance 0.03–0.09 and broad balance 0.55–0.59; the 45 songs read
 * correctly had low balance 0.69–0.97 (and broad balance 0.56–0.81). [maxLowBalance] sits in the gap of the low
 * balance. The broadband condition is the backbeat guard; its margin is narrow: the backbeat with a clap-like snare
 * in `RhythmAnalyzerTest` measures 0.70 against the 0.65 limit, so a backbeat whose snare is much weaker than its
 * kick in broadband flux is halved.
 */
class MetricalLevel(
    val blockBeats: Int = 8,
    val maxLowBalance: Double = 0.35,
    val maxBroadBalance: Double = 0.65,
    val minBpm: Double = 40.0,
) {
    init { require(blockBeats >= 2 && blockBeats % 2 == 0) { "blockBeats must be even and at least 2" } }

    fun check(beatFrames: IntArray, features: OnsetFeatures, bpm: Double): MetricalCheck {
        val lowWeight = features.lowBandWeight()
        val low = parityBalance(beatFrames, features.rawLowOdf)
        val broad = parityBalance(beatFrames, features.rawOdf)
        val halve = lowWeight > 0.0 && beatFrames.size >= blockBeats &&
            low < maxLowBalance && broad < maxBroadBalance && bpm / 2 >= minBpm
        return MetricalCheck(low, broad, lowWeight, halve)
    }

    /**
     * Flux-weighted mean over blocks of [blockBeats] beats of `min(E, O) / max(E, O)` (see the class KDoc); 1 when
     * there is no flux at any beat (no evidence of alternation) or fewer than [blockBeats] beats.
     */
    fun parityBalance(beatFrames: IntArray, flux: FloatArray): Double {
        var num = 0.0
        var den = 0.0
        var b = 0
        while (b + blockBeats <= beatFrames.size) {
            var even = 0.0
            var odd = 0.0
            for (k in b until b + blockBeats) {
                val s = strengthAt(flux, beatFrames[k])
                if ((k - b) % 2 == 0) even += s else odd += s
            }
            val sum = even + odd
            if (sum > 0.0) { num += min(even, odd) / max(even, odd) * sum; den += sum }
            b += blockBeats
        }
        return if (den > 0.0) num / den else 1.0
    }

    private fun strengthAt(x: FloatArray, frame: Int): Double {
        if (x.isEmpty()) return 0.0
        var m = 0f
        for (u in max(0, frame - 1)..min(x.size - 1, frame + 1)) if (x[u] > m) m = x[u]
        return m.toDouble()
    }
}
