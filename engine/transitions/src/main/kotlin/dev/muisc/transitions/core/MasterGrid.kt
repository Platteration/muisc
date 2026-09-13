package dev.muisc.transitions.core

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.dsp.gain.Curves
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.ceil
import kotlin.math.floor

/** Shape of the per-beat BPM curve of a [MasterGrid] glide, as a function of the normalised glide position `t in [0, 1]`. */
enum class GlideCurve {
    /** No glide: every beat runs at the start tempo (the end tempo is ignored). */
    CONSTANT,
    /** `t` — the tempo changes by the same amount every beat. */
    LINEAR,
    /** Smoothstep `3t^2 - 2t^3` — gentle start and end, the DJ's "nudge the pitch fader slowly". */
    S_CURVE,
    /** `(e^{4t} - 1) / (e^4 - 1)` — slow start, fast finish. */
    EXP,
}

/**
 * The OUTPUT beat timeline of a transition: beat frames `F_0 .. F_K` of the rendered segment plus the tempo of every
 * beat. Beat `k` occupies output frames `[F_k, F_{k+1})` at `bpmPerBeat[k]`; the period of beat `k` is
 * `60 * sampleRate / bpmPerBeat[k]` frames. Built by [build] / [constant] / [glide] / [fromPlan], consumed by
 * [PhaseLockedDeck], which renders each deck so that its beats land exactly on these frames.
 *
 * The grid never drifts: the builders accumulate the exact (fractional) beat period in a `Double` and round only
 * when they publish `F_k`, so `F_K - F_0` equals the sum of the periods within one frame however many beats the
 * grid has.
 *
 * Master beat 0 is a downbeat by convention (`beatsPerBar` beats per bar); strategies choose the first master beat
 * so that it coincides with a downbeat of deck A.
 */
class MasterGrid(
    val sampleRate: Int,
    /** `K + 1` strictly increasing output frames `F_0 .. F_K`. */
    val beatFrames: LongArray,
    /** `K` tempos: `bpmPerBeat[k]` is the tempo of beat `k` (between `F_k` and `F_{k+1}`). */
    val bpmPerBeat: DoubleArray,
    val beatsPerBar: Int = 4,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(beatFrames.isNotEmpty()) { "a grid needs at least F_0" }
        require(bpmPerBeat.size == beatFrames.size - 1) { "bpmPerBeat needs ${beatFrames.size - 1} entries, has ${bpmPerBeat.size}" }
        require((1 until beatFrames.size).all { beatFrames[it] > beatFrames[it - 1] }) { "beatFrames must be strictly increasing" }
        require(bpmPerBeat.all { it > 0.0 && it.isFinite() }) { "every bpm must be positive and finite" }
        require(beatsPerBar >= 1)
    }

    /** Number of beats `K` (one less than the number of beat frames). */
    val beatCount: Int get() = bpmPerBeat.size

    /** First / last beat frame. */
    val startFrame: Long get() = beatFrames[0]
    val endFrame: Long get() = beatFrames[beatFrames.size - 1]

    /** Output length covered by the grid: `F_K - F_0`. */
    val totalFrames: Long get() = endFrame - startFrame

    /** Number of bars (possibly fractional). */
    val bars: Double get() = beatCount.toDouble() / beatsPerBar

    /** Period of beat [beat] in frames (`F_{k+1} - F_k`). */
    fun periodFrames(beat: Int): Long = beatFrames[beat + 1] - beatFrames[beat]

    /** Tempo in effect at a (fractional) beat position; piecewise constant per beat, clamped outside the grid. */
    fun bpmAt(beat: Double): Double {
        if (beatCount == 0) return Double.NaN
        val i = floor(beat).toInt().coerceIn(0, beatCount - 1)
        return bpmPerBeat[i]
    }

    /** Tempo of the first / last beat. */
    val bpmStart: Double get() = bpmPerBeat.first()
    val bpmEnd: Double get() = bpmPerBeat.last()

    /** Exact (fractional) output frame of a fractional beat index: linear inside a beat, extrapolated with the first/last period outside. */
    fun frameOfBeatExact(beat: Double): Double {
        val n = beatFrames.size
        if (n == 1) return beatFrames[0].toDouble()
        val i = floor(beat).toInt()
        return when {
            i < 0 -> beatFrames[0] + beat * (beatFrames[1] - beatFrames[0])
            i >= n - 1 -> beatFrames[n - 1] + (beat - (n - 1)) * (beatFrames[n - 1] - beatFrames[n - 2])
            else -> beatFrames[i] + (beat - i) * (beatFrames[i + 1] - beatFrames[i])
        }
    }

    /** Output frame of a fractional beat index (rounded [frameOfBeatExact]). Integer beats return `F_k` exactly. */
    fun frameOfBeat(beat: Double): Long = Math.round(frameOfBeatExact(beat))

    /** Fractional beat index at an output frame (inverse of [frameOfBeat]); extrapolates outside the grid. */
    fun beatAtFrame(frame: Long): Double {
        val n = beatFrames.size
        if (n == 1) return 0.0
        if (frame <= beatFrames[0]) return (frame - beatFrames[0]).toDouble() / (beatFrames[1] - beatFrames[0])
        if (frame >= beatFrames[n - 1]) return (n - 1) + (frame - beatFrames[n - 1]).toDouble() / (beatFrames[n - 1] - beatFrames[n - 2])
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (beatFrames[mid] <= frame) lo = mid else hi = mid }
        return lo + (frame - beatFrames[lo]).toDouble() / (beatFrames[hi] - beatFrames[lo])
    }

    /** Whether master beat [beat] is a downbeat (beat 0 is). */
    fun isDownbeat(beat: Int): Boolean = Math.floorMod(beat, beatsPerBar) == 0

    /** Grid shifted so that `F_0` becomes [frame] (same periods). */
    fun shiftedTo(frame: Long): MasterGrid {
        val d = frame - startFrame
        return MasterGrid(sampleRate, LongArray(beatFrames.size) { beatFrames[it] + d }, bpmPerBeat, beatsPerBar)
    }

    /** Sub-grid of beats `[from, from + beats]`, re-based so that its first frame is 0. */
    fun slice(from: Int, beats: Int): MasterGrid {
        require(from >= 0 && beats >= 0 && from + beats <= beatCount) { "beats [$from, ${from + beats}] outside 0..$beatCount" }
        val f0 = beatFrames[from]
        return MasterGrid(sampleRate, LongArray(beats + 1) { beatFrames[from + it] - f0 }, bpmPerBeat.copyOfRange(from, from + beats), beatsPerBar)
    }

    override fun toString(): String = "MasterGrid(beats=$beatCount, bpm ${"%.2f".format(bpmStart)}..${"%.2f".format(bpmEnd)}, frames=$totalFrames)"

    companion object {
        /** Plan param keys written by the tempo-glide modifier and read by [fromPlan]. */
        const val PARAM_MODE = "grid.mode"
        const val PARAM_GLIDE_BARS = "grid.glideBars"
        const val PARAM_HOLD_BARS = "grid.holdBars"
        const val PARAM_PRE_BARS = "grid.preBars"
        const val PARAM_CURVE = "grid.curve"
        const val PARAM_BEATS = "grid.beats"
        const val MODE_CONSTANT = "constant"
        const val MODE_GLIDE = "glide"

        /** Normalised glide shape `s(t)` of [curve]. */
        fun shape(curve: GlideCurve, t: Double): Double = when (curve) {
            GlideCurve.CONSTANT -> 0.0
            GlideCurve.LINEAR -> Curves.linear(t)
            GlideCurve.S_CURVE -> Curves.sCurve(t)
            GlideCurve.EXP -> Curves.exponential(t)
        }

        /**
         * General builder: [beats] output beats starting at frame 0; beats before [glideStartBeat] run at [bpmStart],
         * beats from [glideEndBeat] on run at [bpmEnd], and beat `k` in between runs at
         * `bpmStart + (bpmEnd - bpmStart) * s((k - glideStartBeat) / (glideEndBeat - glideStartBeat))`
         * (so the first glide beat is exactly at [bpmStart] and the first beat after the glide exactly at [bpmEnd]).
         * `F_{k+1} = F_k + 60 * sampleRate / bpm_k` with the fractional remainder carried exactly.
         */
        fun build(
            sampleRate: Int, bpmStart: Double, bpmEnd: Double, beats: Int, curve: GlideCurve = GlideCurve.LINEAR,
            glideStartBeat: Int = 0, glideEndBeat: Int = beats, beatsPerBar: Int = 4,
        ): MasterGrid {
            require(beats >= 0) { "beats must be >= 0" }
            require(bpmStart > 0 && bpmEnd > 0) { "tempos must be positive" }
            val gs = glideStartBeat
            val ge = glideEndBeat
            val bpm = DoubleArray(beats) { k ->
                when {
                    curve == GlideCurve.CONSTANT -> bpmStart
                    k < gs -> bpmStart
                    k >= ge -> bpmEnd
                    else -> bpmStart + (bpmEnd - bpmStart) * shape(curve, (k - gs).toDouble() / (ge - gs))
                }
            }
            return fromTempos(sampleRate, bpm, beatsPerBar)
        }

        /** Grid from an explicit per-beat tempo array (exact accumulation, first frame [startFrame]). */
        fun fromTempos(sampleRate: Int, bpmPerBeat: DoubleArray, beatsPerBar: Int = 4, startFrame: Long = 0L): MasterGrid {
            val frames = LongArray(bpmPerBeat.size + 1)
            var acc = 0.0
            frames[0] = startFrame
            for (k in bpmPerBeat.indices) {
                acc += 60.0 * sampleRate / bpmPerBeat[k]
                frames[k + 1] = startFrame + Math.round(acc)
            }
            return MasterGrid(sampleRate, frames, bpmPerBeat.copyOf(), beatsPerBar)
        }

        /** Constant tempo grid of [beats] beats at [bpm] starting at frame 0 of the output. */
        fun constant(sampleRate: Int, bpm: Double, beats: Int, beatsPerBar: Int = 4): MasterGrid =
            build(sampleRate, bpm, bpm, beats, GlideCurve.CONSTANT, beatsPerBar = beatsPerBar)

        /**
         * Glide from [bpmFrom] to [bpmTo] over [glideBeats] beats along [curve], then hold [bpmTo] for [holdBeats];
         * optionally [preBeats] beats at [bpmFrom] first. The first beat's period is A's, the last one's is B's
         * (when `holdBeats >= 1`).
         */
        fun glide(sampleRate: Int, bpmFrom: Double, bpmTo: Double, glideBeats: Int, holdBeats: Int, curve: GlideCurve, beatsPerBar: Int = 4, preBeats: Int = 0): MasterGrid {
            require(glideBeats >= 0 && holdBeats >= 0 && preBeats >= 0)
            val c = if (curve == GlideCurve.CONSTANT) GlideCurve.LINEAR else curve
            return build(sampleRate, bpmFrom, bpmTo, preBeats + glideBeats + holdBeats, c, preBeats, preBeats + glideBeats, beatsPerBar)
        }

        /**
         * Builds the grid a plan asks for. With `grid.mode = glide` (written by the tempo-glide modifier) the grid
         * glides from A's tempo to B's matched tempo (`a.bpm * features.tempoRatio`, i.e. after half/double-time
         * selection) over `grid.glideBars` bars along `grid.curve` (default S_CURVE), preceded by `grid.preBars` bars
         * at A's tempo and followed by `grid.holdBars` bars at B's tempo. Otherwise it is a constant grid at A's tempo
         * with `grid.beats` beats (default: enough beats to cover `plan.expectedOutputFrames`, at least one bar).
         */
        fun fromPlan(plan: TransitionPlan, a: TrackAnalysis, b: TrackAnalysis, f: PairFeatures, prefs: TransitionPrefs): MasterGrid {
            val p = plan.params
            val beatsPerBar = a.grid.beatsPerBar.coerceAtLeast(1)
            val bpmA = if (a.grid.bpm > 0) a.grid.bpm else a.tempo.bpm
            require(bpmA > 0) { "track A has no tempo" }
            val sr = prefs.sampleRate
            val mode = p[PARAM_MODE] ?: MODE_CONSTANT
            if (mode.equals(MODE_GLIDE, ignoreCase = true)) {
                val glideBars = p[PARAM_GLIDE_BARS]?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 16
                val holdBars = p[PARAM_HOLD_BARS]?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 2
                val preBars = p[PARAM_PRE_BARS]?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 0
                val curve = p[PARAM_CURVE]?.let { c -> GlideCurve.entries.firstOrNull { it.name.equals(c.trim(), ignoreCase = true) } } ?: GlideCurve.S_CURVE
                val bpmB = bpmA * f.tempoRatio
                return glide(sr, bpmA, bpmB, glideBars * beatsPerBar, holdBars * beatsPerBar, curve, beatsPerBar, preBars * beatsPerBar)
            }
            val period = 60.0 * sr / bpmA
            val beats = p[PARAM_BEATS]?.toDoubleOrNull()?.let { Math.round(it).toInt() }
                ?: ceil(plan.expectedOutputFrames / period).toInt().coerceAtLeast(beatsPerBar)
            return constant(sr, bpmA, beats, beatsPerBar)
        }
    }
}
