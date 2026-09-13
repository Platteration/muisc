package dev.muisc.analysis.rhythm

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Result of [DownbeatEstimator.estimate]. */
class DownbeatResult(
    /** Beat index (0..beatsPerBar-1) of the first downbeat. */
    val phase: Int,
    /** 0..1 margin between the best and the second-best phase. */
    val confidence: Float,
    /** Beat index of the first phrase start, or -1 when unclear. */
    val phraseStartBeat: Int,
    val phraseConfidence: Float,
    /** Score of every phase (diagnostics). */
    val phaseScores: DoubleArray,
    val phraseScores: DoubleArray,
)

/**
 * Downbeat (bar phase) and phrase estimation for a fixed metre (4/4 by default) on top of a tracked beat sequence.
 *
 * Every beat `b` gets three features:
 * - `low(b)`: low-band onset strength at the beat (kick / bass emphasis on beat 1), max over ±1 ODF frame;
 * - `chroma(b)`: harmonic change, the cosine distance between the mean chroma of the bar before and the bar
 *   after the beat (chord changes mostly happen on downbeats);
 * - `energy(b)`: broadband loudness change between the bar before and the bar after (arrangement changes).
 *
 * Each feature is normalised to unit mean over the beats and the phase score is
 * `S(φ) = mean_k [ low(4k+φ) + w1 * chroma(4k+φ) + w2 * energy(4k+φ) ]`. The phase with the highest score wins;
 * confidence is `(S1 - S2) / (S1 - Smin)`, i.e. 1 when the runner-up is no better than the worst phase and 0
 * when it ties with the winner.
 *
 * Phrase starts (8 bars) are scored the same way over the 32-beat period, restricted to the beats that are
 * downbeats, but with the chroma / energy context widened to four bars on each side so that the every-bar chord
 * changes cancel and only section-level changes (drums in / out, key or progression changes) count. When the
 * margin is below [minPhraseConfidence] the phrase start is reported as -1 (unknown).
 */
class DownbeatEstimator(
    val beatsPerBar: Int = 4,
    val phraseBars: Int = 8,
    val chromaWeight: Double = 1.0,
    val energyWeight: Double = 1.0,
    val minPhraseConfidence: Float = 0.3f,
) {
    init { require(beatsPerBar >= 1 && phraseBars >= 1) }

    fun estimate(beatTimesSec: DoubleArray, features: OnsetFeatures): DownbeatResult {
        val n = beatTimesSec.size
        val bpb = beatsPerBar
        val phraseBeats = bpb * phraseBars
        if (n < 2 * bpb || features.frames == 0) {
            return DownbeatResult(0, 0f, -1, 0f, DoubleArray(bpb), DoubleArray(phraseBeats))
        }
        val frames = IntArray(n) { features.frameAt(beatTimesSec[it]) }
        val chromaPrefix = Array(12) { DoubleArray(features.frames + 1) }
        val energyPrefix = DoubleArray(features.frames + 1)
        for (t in 0 until features.frames) {
            val c = features.chroma[t]
            for (p in 0 until 12) chromaPrefix[p][t + 1] = chromaPrefix[p][t] + c[p]
            energyPrefix[t + 1] = energyPrefix[t] + features.energy[t]
        }

        val low = DoubleArray(n) { i ->
            val f = frames[i]
            var m = features.lowOdf[f]
            if (f > 0) m = max(m, features.lowOdf[f - 1])
            if (f + 1 < features.frames) m = max(m, features.lowOdf[f + 1])
            m.toDouble()
        }
        val chromaBar = contextFeature(frames, bpb, chromaPrefix, null)
        val energyBar = contextFeature(frames, bpb, null, energyPrefix)
        normaliseMean(low); normaliseMean(chromaBar); normaliseMean(energyBar)

        val phaseScores = DoubleArray(bpb)
        for (phi in 0 until bpb) {
            var acc = 0.0; var cnt = 0
            var b = phi
            while (b < n) { acc += low[b] + chromaWeight * chromaBar[b] + energyWeight * energyBar[b]; cnt++; b += bpb }
            phaseScores[phi] = if (cnt > 0) acc / cnt else 0.0
        }
        val phase = argmax(phaseScores)
        val confidence = margin(phaseScores)

        // Phrase: 4-bar context, downbeats only.
        val phraseScores = DoubleArray(phraseBeats)
        var phraseStart = -1
        var phraseConf = 0f
        if (n >= phraseBeats + bpb) {
            val ctx = bpb * max(1, phraseBars / 2)
            val chromaWide = contextFeature(frames, ctx, chromaPrefix, null)
            val energyWide = contextFeature(frames, ctx, null, energyPrefix)
            normaliseMean(chromaWide); normaliseMean(energyWide)
            for (j in 0 until phraseBars) {
                val psi = phase + j * bpb
                var acc = 0.0; var cnt = 0
                var b = psi
                while (b < n) { acc += low[b] + chromaWeight * chromaWide[b] + energyWeight * energyWide[b]; cnt++; b += phraseBeats }
                phraseScores[psi] = if (cnt > 0) acc / cnt else 0.0
            }
            val candidates = DoubleArray(phraseBars) { phraseScores[phase + it * bpb] }
            phraseConf = margin(candidates)
            if (phraseConf >= minPhraseConfidence) phraseStart = phase + argmax(candidates) * bpb
        }
        return DownbeatResult(phase, confidence, phraseStart, phraseConf, phaseScores, phraseScores)
    }

    /**
     * Per-beat novelty with a context of [contextBeats] beats on each side: cosine distance of mean chroma
     * (when [chromaPrefix] is given) or absolute mean energy difference (when [energyPrefix] is given).
     * Beats without a full context on both sides get 0.
     */
    private fun contextFeature(frames: IntArray, contextBeats: Int, chromaPrefix: Array<DoubleArray>?, energyPrefix: DoubleArray?): DoubleArray {
        val n = frames.size
        val out = DoubleArray(n)
        val before = DoubleArray(12); val after = DoubleArray(12)
        for (b in contextBeats until n - contextBeats) {
            val f0 = frames[b - contextBeats]; val f1 = frames[b]; val f2 = frames[b + contextBeats]
            if (f1 <= f0 || f2 <= f1) continue
            if (chromaPrefix != null) {
                for (p in 0 until 12) {
                    before[p] = (chromaPrefix[p][f1] - chromaPrefix[p][f0]) / (f1 - f0)
                    after[p] = (chromaPrefix[p][f2] - chromaPrefix[p][f1]) / (f2 - f1)
                }
                out[b] = cosineDistance(before, after)
            } else if (energyPrefix != null) {
                val eb = (energyPrefix[f1] - energyPrefix[f0]) / (f1 - f0)
                val ea = (energyPrefix[f2] - energyPrefix[f1]) / (f2 - f1)
                out[b] = abs(ea - eb)
            }
        }
        return out
    }

    private fun cosineDistance(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na <= 1e-18 || nb <= 1e-18) return 0.0
        return (1.0 - dot / sqrt(na * nb)).coerceIn(0.0, 1.0)
    }

    private fun normaliseMean(x: DoubleArray) {
        var s = 0.0
        for (v in x) s += v
        if (s <= 1e-12) return
        val k = x.size / s
        for (i in x.indices) x[i] *= k
    }

    private fun argmax(x: DoubleArray): Int { var b = 0; for (i in 1 until x.size) if (x[i] > x[b]) b = i; return b }

    /** `(best - secondBest) / (best - worst)` clamped to 0..1; 0 when all scores are equal. */
    private fun margin(x: DoubleArray): Float {
        if (x.size < 2) return 0f
        var best = Double.NEGATIVE_INFINITY; var second = Double.NEGATIVE_INFINITY; var worst = Double.POSITIVE_INFINITY
        for (v in x) {
            if (v > best) { second = best; best = v } else if (v > second) second = v
            worst = min(worst, v)
        }
        val range = best - worst
        if (range <= 1e-12) return 0f
        return ((best - second) / range).coerceIn(0.0, 1.0).toFloat()
    }
}
