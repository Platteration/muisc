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
    /**
     * Mid-band (150 Hz .. 2 kHz) flux at the off-beats over the mid-band flux at the beats (see [MetricalLevel]):
     * well below 1 when the off-beats carry only hats, 1 or more when they carry a snare, a clap or a chord.
     */
    val midRatio: Double,
    /** The same ratio for the high band (above 2 kHz): below 1 when the beats carry the off-beats' hats and more. */
    val highRatio: Double,
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
 * Both balances are symmetric: they cannot tell "the off-beats are weaker" (double time: a hat alone between two
 * beats) from "the off-beats are different" (a backbeat: kick and bass on 1 and 3, snare, clap or chord on 2 and 4,
 * which alternates in the low band just the same). That takes a direction. In every block the parity with more
 * low-band flux is the *beat* parity, the other the *off-beat* parity, and the flux at the off-beats is summed over
 * the blocks and divided by the flux at the beats, per band: the mid band ([OnsetFeatures.rawMidOdf], 150 Hz ..
 * 2 kHz: snare body, chords, voice) gives [MetricalCheck.midRatio], the high band (the rest of the broadband flux:
 * hats, cymbals, the noise of a snare or clap) gives [MetricalCheck.highRatio]. At twice the tempo the off-beats
 * carry a hat and the beats carry the same hat plus the kick, bass, snare and chord changes, so the off-beats are
 * weaker in every band; on a backbeat the snare, clap or chord makes them as strong or stronger in the mid band, the
 * high band or both.
 *
 * The tempo is halved only when all of these hold:
 *  - the track has a low band at all (`lowBandWeight > 0`, [OnsetFeatures.lowBandWeightFor]);
 *  - the low band alternates: low balance below [maxLowBalance] (kick / bass on every other tracked beat only);
 *  - the broadband flux alternates too: broad balance below [maxBroadBalance];
 *  - the off-beats are hat-like: mid ratio below [maxMidRatio] (no snare body or chord on them) and high ratio
 *    below [maxHighRatio] (their hats are no louder than the beats' own; a snare's or clap's noise on top of the
 *    hats, or on beats that have no hats, makes it 1 or more);
 *  - the halved tempo is at least [minBpm]: no reading below 110 BPM is halved (an 84 BPM backbeat that slipped
 *    past the other conditions would otherwise become 42 BPM; the bench's slowest songs are 58.6 BPM, 60 BPM
 *    detuned by −40 cents).
 *
 * Measured on `muisc bench analysis` (seed 1, 48 songs) before this check existed: the three songs read at twice
 * their tempo (61–66 BPM read as 123–132) had low balance 0.03–0.09 and broad balance 0.55–0.59; the 45 songs read
 * correctly had low balance 0.69–0.97 (and broad balance 0.56–0.81). [maxLowBalance] sits in the gap of the low
 * balance. Without the two directional conditions and with a 40 BPM floor (analysis version 3) the check halved
 * backbeats (the louder the snare or clap, the lower the broad balance), boom-chick and oom-pah: 88 → 44,
 * 100 → 50, 120 → 60. Measured on [dev.muisc.audio.synth.SyntheticSong]s at 57–70 BPM read at double time: mid
 * ratio 0.27–0.33, high ratio 0.83–0.86. On the two-beat grooves of `RhythmAnalyzerTest` (claps, snares with a
 * 190–280 Hz body, boom-chick, oom-pah, with and without hats) every groove has a mid ratio of 1.22 or more or a
 * high ratio of 1.10 or more; the narrowest margins are a quiet clap under a loud kick with hats (mid 0.23–0.25,
 * high 1.10–1.12: only the high band vetoes) and oom-pah (high 0.65–0.66, mid 5.5: only the mid band vetoes).
 * These are synthetic measurements. On real music a slow song whose off-beats carry more than hats (strummed
 * eighths, an accented open hat), or one at [minBpm] or slower (a 55 BPM song is read at 109.99 and stays there),
 * keeps its double-time reading, which is the reading it had before this check existed.
 */
class MetricalLevel(
    val blockBeats: Int = 8,
    val maxLowBalance: Double = 0.35,
    val maxBroadBalance: Double = 0.65,
    val maxMidRatio: Double = 0.5,
    val maxHighRatio: Double = 1.0,
    val minBpm: Double = 55.0,
) {
    init { require(blockBeats >= 2 && blockBeats % 2 == 0) { "blockBeats must be even and at least 2" } }

    fun check(beatFrames: IntArray, features: OnsetFeatures, bpm: Double): MetricalCheck {
        val lowWeight = features.lowBandWeight()
        val low = parityBalance(beatFrames, features.rawLowOdf)
        val broad = parityBalance(beatFrames, features.rawOdf)
        val high = FloatArray(features.frames) {
            max(0f, features.rawOdf[it] - features.rawLowOdf[it] - features.rawMidOdf[it])
        }
        val (midRatio, highRatio) = offBeatRatios(beatFrames, features.rawLowOdf, features.rawMidOdf, high)
        val halve = lowWeight > 0.0 && beatFrames.size >= blockBeats &&
            low < maxLowBalance && broad < maxBroadBalance &&
            midRatio < maxMidRatio && highRatio < maxHighRatio && bpm / 2 >= minBpm
        return MetricalCheck(low, broad, lowWeight, midRatio, highRatio, halve)
    }

    /**
     * Off-beat over beat flux of [mid] and of [high], the beat parity of each block of [blockBeats] beats being the
     * one with more [low] flux (see the class KDoc). A band with no flux at either parity reads 0 (no evidence
     * against halving); one with flux at the off-beats only reads [Double.POSITIVE_INFINITY].
     */
    fun offBeatRatios(beatFrames: IntArray, low: FloatArray, mid: FloatArray, high: FloatArray): Pair<Double, Double> {
        var midOn = 0.0; var midOff = 0.0; var highOn = 0.0; var highOff = 0.0
        var b = 0
        while (b + blockBeats <= beatFrames.size) {
            var lowEven = 0.0; var lowOdd = 0.0
            var midEven = 0.0; var midOdd = 0.0
            var highEven = 0.0; var highOdd = 0.0
            for (k in b until b + blockBeats) {
                val f = beatFrames[k]
                if ((k - b) % 2 == 0) {
                    lowEven += strengthAt(low, f); midEven += strengthAt(mid, f); highEven += strengthAt(high, f)
                } else {
                    lowOdd += strengthAt(low, f); midOdd += strengthAt(mid, f); highOdd += strengthAt(high, f)
                }
            }
            if (lowEven >= lowOdd) { midOn += midEven; midOff += midOdd; highOn += highEven; highOff += highOdd }
            else { midOn += midOdd; midOff += midEven; highOn += highOdd; highOff += highEven }
            b += blockBeats
        }
        return ratio(midOff, midOn) to ratio(highOff, highOn)
    }

    private fun ratio(off: Double, on: Double): Double = when {
        on > 0.0 -> off / on
        off > 0.0 -> Double.POSITIVE_INFINITY
        else -> 0.0
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
