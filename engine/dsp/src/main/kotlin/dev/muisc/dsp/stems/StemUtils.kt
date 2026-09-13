package dev.muisc.dsp.stems

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.gain.Curves
import dev.muisc.dsp.gain.FadeShape
import dev.muisc.dsp.gain.Lane
import dev.muisc.dsp.gain.LanePoint
import kotlin.math.sqrt

/**
 * Per-stem gain automation for a crossfade between two [Stems] objects: for every [StemKind] one lane for the
 * outgoing stems (A, normally 1 → 0) and one for the incoming stems (B, normally 0 → 1). Lanes are sampled at
 * the normalised crossfade position `x in [0, 1]` (0 = first frame, 1 = last frame), so one plan works for any
 * crossfade length. Kinds missing from a map get a constant lane: 1 for A (kept), 0 for B (not yet mixed in).
 */
class StemCrossfadeLanes(outLanes: Map<StemKind, Lane>, inLanes: Map<StemKind, Lane>) {
    /** Outgoing (A) gain lane per kind, indexed by `StemKind.ordinal`. */
    val outGains: Array<Lane> = Array(StemKind.entries.size) { outLanes[StemKind.entries[it]] ?: CONSTANT_ONE }
    /** Incoming (B) gain lane per kind, indexed by `StemKind.ordinal`. */
    val inGains: Array<Lane> = Array(StemKind.entries.size) { inLanes[StemKind.entries[it]] ?: CONSTANT_ZERO }

    companion object {
        val CONSTANT_ONE = Lane(listOf(LanePoint(0.0, 1f)))
        val CONSTANT_ZERO = Lane(listOf(LanePoint(0.0, 0f)))

        /** Every stem fades A → B simultaneously over the whole crossfade with [shape]. */
        fun uniform(shape: FadeShape = FadeShape.EQUAL_POWER): StemCrossfadeLanes = staggered(listOf(StemKind.entries.toList()), shape)

        /**
         * Sequential stem swap in the DJ style "drums from B first, then bass, then the rest": the crossfade is
         * divided into `groups.size` equal slots and the stems of group `i` swap (A fades out with
         * `fadeOut(shape)`, B fades in with `fadeIn(shape)`) during slot `i`, i.e. over
         * `x in [i / groups.size, (i + 1) / groups.size]`. Every kind must appear in exactly one group.
         */
        fun staggered(groups: List<List<StemKind>>, shape: FadeShape = FadeShape.EQUAL_POWER): StemCrossfadeLanes {
            val all = groups.flatten()
            require(all.size == StemKind.entries.size && all.toSet().size == StemKind.entries.size) { "each StemKind must appear exactly once" }
            val n = groups.size
            val outL = HashMap<StemKind, Lane>()
            val inL = HashMap<StemKind, Lane>()
            for ((i, g) in groups.withIndex()) {
                val x0 = i.toDouble() / n; val x1 = (i + 1).toDouble() / n
                for (k in g) {
                    outL[k] = fadeOutLane(x0, x1, shape)
                    inL[k] = Lane.segment(x0, 0f, x1, 1f, shape)
                }
            }
            return StemCrossfadeLanes(outL, inL)
        }

        /**
         * Lane holding 1 before [x0], `Curves.fadeOut(shape, t)` with `t = (x - x0) / (x1 - x0)` in between and
         * 0 after [x1]. A [Lane] segment from 1 to 0 would evaluate `1 - fadeIn(shape, t)`, which for
         * non-linear shapes is *not* the matching fade-out (e.g. equal power needs `cos`, not `1 - sin`), so the
         * fade-out is tabulated as [FADE_OUT_POINTS] linear breakpoints (exact at the knots, error < 1e-4
         * in between; exact everywhere for [FadeShape.LINEAR]).
         */
        fun fadeOutLane(x0: Double, x1: Double, shape: FadeShape): Lane {
            if (shape == FadeShape.LINEAR) return Lane.segment(x0, 1f, x1, 0f, FadeShape.LINEAR)
            val pts = ArrayList<LanePoint>(FADE_OUT_POINTS + 1)
            for (i in 0..FADE_OUT_POINTS) {
                val t = i.toDouble() / FADE_OUT_POINTS
                pts.add(LanePoint(x0 + t * (x1 - x0), Curves.fadeOut(shape, t).toFloat(), FadeShape.LINEAR))
            }
            return Lane(pts)
        }

        const val FADE_OUT_POINTS = 128

        /** The classic order: drums first, then bass, then vocals and other together. */
        fun drumsThenBassThenRest(shape: FadeShape = FadeShape.EQUAL_POWER): StemCrossfadeLanes =
            staggered(listOf(listOf(StemKind.DRUMS), listOf(StemKind.BASS), listOf(StemKind.VOCALS, StemKind.OTHER)), shape)
    }
}

/**
 * Helpers for transition strategies working on [Stems]: gain mixes, per-stem crossfades and stem levels.
 * All functions are pure (they never modify their inputs) unless named `Into`, in which case the destination
 * is written and nothing is allocated except small block scratch.
 */
object StemUtils {
    private const val BLOCK = 4096

    /** Gains indexed by `StemKind.ordinal` from a map; kinds absent from the map get [defaultGain]. */
    fun gainArray(gains: Map<StemKind, Float>, defaultGain: Float = 0f): FloatArray =
        FloatArray(StemKind.entries.size) { gains[StemKind.entries[it]] ?: defaultGain }

    /**
     * `sum_k gains[k] * stems[k]` as a new buffer. Kinds absent from [gains] get [defaultGain] (0 by default,
     * so `mixWithGains(stems, mapOf(DRUMS to 1f))` solos the drums; pass `defaultGain = 1f` to express
     * "everything at unity except ...").
     */
    fun mixWithGains(stems: Stems, gains: Map<StemKind, Float>, defaultGain: Float = 0f): AudioBuffer =
        mixWithGains(stems, gainArray(gains, defaultGain))

    /** Named-argument form: `mixWithGains(stems, bass = 0f)` removes the bass and keeps everything else. */
    fun mixWithGains(stems: Stems, drums: Float = 1f, bass: Float = 1f, vocals: Float = 1f, other: Float = 1f): AudioBuffer =
        mixWithGains(stems, floatArrayOf(drums, bass, vocals, other))

    /** `sum_k gains[k] * stems[k]` with gains indexed by `StemKind.ordinal`. */
    fun mixWithGains(stems: Stems, gains: FloatArray): AudioBuffer {
        val out = AudioBuffer.silence(stems.sampleRate, stems.channelCount, stems.frames)
        mixWithGainsInto(out, stems, gains, stems.frames)
        return out
    }

    /**
     * Adds `sum_k gains[k] * stems[k]` over [frames] frames into [dst] (starting at `dstOffset`, reading the
     * stems from `srcOffset`). Allocation-free; `dst` is accumulated into, not overwritten.
     */
    fun mixWithGainsInto(dst: AudioBuffer, stems: Stems, gains: FloatArray, frames: Int, dstOffset: Int = 0, srcOffset: Int = 0) {
        require(gains.size >= StemKind.entries.size) { "need one gain per StemKind" }
        require(dst.channelCount == stems.channelCount) { "channel count mismatch" }
        for (kind in StemKind.entries) {
            val g = gains[kind.ordinal]
            if (g == 0f) continue
            val s = stems[kind]
            for (c in 0 until dst.channelCount) Curves.mixAdd(dst[c], s[c], g, frames, dstOffset, srcOffset)
        }
    }

    /**
     * Per-stem crossfade of [frames] frames from [a] (starting at [aOffset]) to [b] (starting at [bOffset]):
     * `dst = sum_k out_k(x) * a_k + in_k(x) * b_k` with `x = i / (frames - 1)` and the lanes of [lanes].
     * Both stems objects must share channel count and sample rate. Returns a new buffer.
     */
    fun crossfade(a: Stems, b: Stems, lanes: StemCrossfadeLanes, frames: Int = minOf(a.frames, b.frames), aOffset: Int = 0, bOffset: Int = 0): AudioBuffer {
        val out = AudioBuffer.silence(a.sampleRate, a.channelCount, frames)
        crossfadeInto(out, a, b, lanes, frames, 0, aOffset, bOffset)
        return out
    }

    /**
     * Writes the per-stem crossfade into `dst[dstOffset ..< dstOffset + frames]` (overwriting). Gains are
     * rendered block-wise from the lanes (one lane sample per frame). `x` runs from [xStart] to [xEnd] over the
     * block, which lets a long crossfade be rendered in pieces.
     */
    fun crossfadeInto(
        dst: AudioBuffer, a: Stems, b: Stems, lanes: StemCrossfadeLanes, frames: Int,
        dstOffset: Int = 0, aOffset: Int = 0, bOffset: Int = 0, xStart: Double = 0.0, xEnd: Double = 1.0,
    ) {
        require(a.channelCount == b.channelCount && a.sampleRate == b.sampleRate) { "stems must match in channels and sample rate" }
        require(dst.channelCount == a.channelCount) { "dst channel count mismatch" }
        require(aOffset + frames <= a.frames && bOffset + frames <= b.frames && dstOffset + frames <= dst.frames) { "range out of bounds" }
        if (frames <= 0) return
        val kinds = StemKind.entries.size
        val gOut = Array(kinds) { FloatArray(BLOCK) }
        val gIn = Array(kinds) { FloatArray(BLOCK) }
        val dx = if (frames > 1) (xEnd - xStart) / (frames - 1) else 0.0
        for (c in 0 until dst.channelCount) java.util.Arrays.fill(dst[c], dstOffset, dstOffset + frames, 0f)
        var pos = 0
        while (pos < frames) {
            val n = minOf(BLOCK, frames - pos)
            val x0 = xStart + pos * dx
            for (k in 0 until kinds) { lanes.outGains[k].fill(gOut[k], n, x0, dx); lanes.inGains[k].fill(gIn[k], n, x0, dx) }
            for (kind in StemKind.entries) {
                val k = kind.ordinal
                val sa = a[kind]; val sb = b[kind]
                val go = gOut[k]; val gi = gIn[k]
                for (c in 0 until dst.channelCount) {
                    val d = dst[c]; val xa = sa[c]; val xb = sb[c]
                    val di = dstOffset + pos; val ai = aOffset + pos; val bi = bOffset + pos
                    for (i in 0 until n) d[di + i] += xa[ai + i] * go[i] + xb[bi + i] * gi[i]
                }
            }
            pos += n
        }
    }

    /** RMS of one stem over all channels and frames (0 for an empty buffer). */
    fun rms(stems: Stems, kind: StemKind): Float = stems[kind].rms()

    /** RMS of every stem, indexed by `StemKind.ordinal`. */
    fun rms(stems: Stems): FloatArray = FloatArray(StemKind.entries.size) { rms(stems, StemKind.entries[it]) }

    /** RMS of one stem over frames `[start, end)` (all channels). */
    fun rms(stems: Stems, kind: StemKind, start: Int, end: Int): Float {
        val s = stems[kind]
        val n = (end - start).coerceAtLeast(0)
        if (n == 0) return 0f
        var acc = 0.0
        for (c in 0 until s.channelCount) { val ch = s[c]; for (i in start until end) acc += ch[i].toDouble() * ch[i] }
        return sqrt(acc / (n.toDouble() * s.channelCount)).toFloat()
    }

    /**
     * Fraction of the total stem energy (sum of squares) held by each stem, indexed by `StemKind.ordinal`;
     * sums to 1 (all zeros for silence). Note this is relative to `sum_k E(stem_k)`, not to the energy of the
     * mix (stems are not orthogonal).
     */
    fun energyFractions(stems: Stems, start: Int = 0, end: Int = stems.frames): FloatArray {
        val e = DoubleArray(StemKind.entries.size)
        var total = 0.0
        for (kind in StemKind.entries) {
            val s = stems[kind]
            var acc = 0.0
            for (c in 0 until s.channelCount) { val ch = s[c]; for (i in start until end) acc += ch[i].toDouble() * ch[i] }
            e[kind.ordinal] = acc; total += acc
        }
        return FloatArray(e.size) { if (total > 0.0) (e[it] / total).toFloat() else 0f }
    }
}
