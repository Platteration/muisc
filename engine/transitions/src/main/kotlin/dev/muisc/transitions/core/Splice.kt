package dev.muisc.transitions.core

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.gain.Curves
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import kotlin.math.abs

/**
 * Splice-contract toolkit (see `TransitionPlan`'s KDoc).
 *
 * **Recommended segment layout for every strategy — dry pre-roll and post-roll.** Put `aExitFrame` [GUARD_FRAMES]
 * frames *before* the first frame the strategy touches and `bEntryFrame` [GUARD_FRAMES] frames *after* the last
 * one. The segment then starts with [GUARD_FRAMES] verbatim frames of A (deck gain applied, nothing else) and ends
 * with [GUARD_FRAMES] verbatim frames of B, the splice contract holds bit-exactly whatever the fade laws do at their
 * endpoints, the true-peak limiter's release cannot leak into a seam (see `RenderReports.finalize`), and
 * [SpliceCheck.verify] passes without any tolerance games. The dry regions are cheap (93 ms at 44.1 kHz).
 */
object Splice {
    /** Length of the dry pre-roll / post-roll a strategy should carry at each end of its segment. */
    const val GUARD_FRAMES: Int = 4096

    /** Default length of the seam micro-crossfade. */
    const val MICRO_FADE_FRAMES: Int = 64

    /**
     * Blends the last [frames] samples of [prevTail] into the first [frames] samples of [nextHead] (fewer when either
     * is shorter) and returns the blended run. Equal-power by default (uncorrelated material); pass [FadeLaw.LINEAR]
     * for coherent material (identical samples on both sides: a mathematical no-op).
     */
    fun microCrossfade(prevTail: FloatArray, nextHead: FloatArray, frames: Int = MICRO_FADE_FRAMES, law: FadeLaw = FadeLaw.EQUAL_POWER): FloatArray {
        val n = minOf(frames, prevTail.size, nextHead.size).coerceAtLeast(0)
        val out = FloatArray(n)
        if (n == 0) return out
        val shape = CrossfadeLaw.shape(law)
        val off = prevTail.size - n
        val inv = 1.0 / n
        for (i in 0 until n) {
            val x = (i + 1) * inv
            out[i] = (prevTail[off + i] * Curves.fadeOut(shape, x) + nextHead[i] * Curves.fadeIn(shape, x)).toFloat()
        }
        return out
    }

    /**
     * Joins [prev] and [next] with a [frames]-frame micro-crossfade over the seam: the result has
     * `prev.frames + next.frames - frames` frames, where the last [frames] of [prev] overlap the first [frames] of [next].
     */
    fun join(prev: AudioBuffer, next: AudioBuffer, frames: Int = MICRO_FADE_FRAMES, law: FadeLaw = FadeLaw.EQUAL_POWER): AudioBuffer {
        require(prev.sampleRate == next.sampleRate && prev.channelCount == next.channelCount) { "format mismatch" }
        val n = minOf(frames, prev.frames, next.frames).coerceAtLeast(0)
        val total = prev.frames + next.frames - n
        val out = Array(prev.channelCount) { c ->
            val a = FloatArray(total)
            System.arraycopy(prev[c], 0, a, 0, prev.frames - n)
            val blend = microCrossfade(prev[c], next[c], n, law)
            System.arraycopy(blend, 0, a, prev.frames - n, n)
            System.arraycopy(next[c], n, a, prev.frames, next.frames - n)
            a
        }
        return AudioBuffer(prev.sampleRate, out)
    }

    /**
     * `dst[c][dstOffset + i] += src[c][srcOffset + i] * gain(dstOffset + i)` for `i in 0 until frames`, clipped to both
     * buffers; [gain] is sampled in DESTINATION frames (null = unity). Block-wise, no per-sample lookup.
     */
    fun addInPlace(dst: AudioBuffer, src: AudioBuffer, dstOffset: Int, gain: Lane? = null, srcOffset: Int = 0, frames: Int = src.frames - srcOffset) {
        require(dst.channelCount == src.channelCount) { "channel count mismatch" }
        var d0 = dstOffset; var s0 = srcOffset; var n = frames
        if (d0 < 0) { s0 -= d0; n += d0; d0 = 0 }
        if (s0 < 0) { d0 -= s0; n += s0; s0 = 0 }
        n = minOf(n, dst.frames - d0, src.frames - s0)
        if (n <= 0) return
        if (gain == null) {
            for (c in 0 until dst.channelCount) Curves.mixAdd(dst[c], src[c], 1f, n, d0, s0)
            return
        }
        val g = FloatArray(minOf(4096, n))
        var done = 0
        while (done < n) {
            val m = minOf(g.size, n - done)
            gain.fillGains(g, (d0 + done).toLong(), m, 0)
            for (c in 0 until dst.channelCount) {
                val x = src[c]; val y = dst[c]
                val sb = s0 + done; val db = d0 + done
                for (i in 0 until m) y[db + i] += x[sb + i] * g[i]
            }
            done += m
        }
    }

    /**
     * Assembles a segment of [outFrames] frames: `out[n] = a[n - aOffsetFrames] * aGain(n) + b[n - bOffsetFrames] * bGain(n)`,
     * both lanes sampled in OUTPUT frames, each source contributing only where it has samples. Sample rate and
     * channel count are taken from [a] (which must match [b]).
     */
    fun mix(a: AudioBuffer, aGain: Lane, b: AudioBuffer, bGain: Lane, bOffsetFrames: Int, outFrames: Int, aOffsetFrames: Int = 0): AudioBuffer {
        require(a.sampleRate == b.sampleRate && a.channelCount == b.channelCount) { "format mismatch between decks" }
        val out = AudioBuffer.silence(a.sampleRate, a.channelCount, outFrames)
        addInPlace(out, a, aOffsetFrames, aGain)
        addInPlace(out, b, bOffsetFrames, bGain)
        return out
    }
}

/**
 * Verifies the splice contract of a render numerically: the first [DEFAULT_GUARD] frames of the segment must equal
 * `input.aAudio` from `plan.aExitOffset` on and the last ones `input.bAudio` up to `plan.bEntryOffset`, within a
 * tolerance (the inputs already carry the deck gain, see [DeckGain]; strategies copy them verbatim there). Also
 * flags NaN/Inf and a length off `expectedOutputFrames` by more than [DEFAULT_LENGTH_TOLERANCE]. Used by every
 * strategy test and by the renderer, which turns the messages into `RenderReport.warnings`.
 */
object SpliceCheck {
    const val DEFAULT_GUARD: Int = Splice.GUARD_FRAMES
    const val DEFAULT_TOLERANCE: Float = 1e-3f
    const val DEFAULT_LENGTH_TOLERANCE: Double = 0.01

    /** Returns the list of violations (empty = the contract holds). */
    fun verify(
        rendered: RenderedTransition, input: TransitionInput, prefix: Int = DEFAULT_GUARD, suffix: Int = DEFAULT_GUARD,
        tolerance: Float = DEFAULT_TOLERANCE, lengthTolerance: Double = DEFAULT_LENGTH_TOLERANCE,
    ): List<String> {
        val plan = rendered.plan
        val audio = rendered.audio
        val out = ArrayList<String>()
        if (audio.channelCount != input.aAudio.channelCount || audio.channelCount != input.bAudio.channelCount) {
            out += "channel count: render ${audio.channelCount}, A ${input.aAudio.channelCount}, B ${input.bAudio.channelCount}"
        }
        if (audio.sampleRate != input.aAudio.sampleRate) out += "sample rate: render ${audio.sampleRate}, input ${input.aAudio.sampleRate}"
        val expected = plan.expectedOutputFrames
        if (expected > 0 && abs(audio.frames - expected) > lengthTolerance * expected) {
            out += "length: ${audio.frames} frames, plan expected $expected (tolerance ${(lengthTolerance * 100)} %)"
        }
        for (c in 0 until audio.channelCount) {
            val x = audio[c]
            for (i in x.indices) if (!x[i].isFinite()) { out += "non-finite sample at frame $i channel $c"; break }
        }
        // Prefix: render[0 until prefix] == A[aExitOffset until aExitOffset + prefix]
        val pre = minOf(prefix, audio.frames)
        val aOff = plan.aExitOffset
        if (aOff + pre > input.aAudio.frames) {
            out += "aWindow ends ${aOff + pre - input.aAudio.frames} frames before the end of the $pre-frame prefix"
        } else if (pre > 0) {
            compare(audio, 0, input.aAudio, aOff, pre, tolerance, "prefix (A from aExitFrame)")?.let { out += it }
        }
        // Suffix: render[frames - suffix until frames] == B[bEntryOffset - suffix until bEntryOffset]
        val suf = minOf(suffix, audio.frames)
        val bOff = plan.bEntryOffset
        if (bOff - suf < 0) {
            out += "bWindow starts ${suf - bOff} frames after the start of the $suf-frame suffix"
        } else if (bOff > input.bAudio.frames) {
            out += "bEntryOffset $bOff beyond bAudio (${input.bAudio.frames} frames)"
        } else if (suf > 0) {
            compare(audio, audio.frames - suf, input.bAudio, bOff - suf, suf, tolerance, "suffix (B up to bEntryFrame)")?.let { out += it }
        }
        return out
    }

    /** Max abs difference between `x[c][xOff + i]` and `y[c][yOff + i]`, i < n, over all channels. */
    fun maxDiff(x: AudioBuffer, xOff: Int, y: AudioBuffer, yOff: Int, n: Int): Float {
        var m = 0f
        for (c in 0 until minOf(x.channelCount, y.channelCount)) {
            val a = x[c]; val b = y[c]
            for (i in 0 until n) { val d = abs(a[xOff + i] - b[yOff + i]); if (d > m) m = d }
        }
        return m
    }

    private fun compare(x: AudioBuffer, xOff: Int, y: AudioBuffer, yOff: Int, n: Int, tol: Float, what: String): String? {
        var worst = 0f; var worstAt = -1; var worstCh = 0
        for (c in 0 until minOf(x.channelCount, y.channelCount)) {
            val a = x[c]; val b = y[c]
            for (i in 0 until n) {
                val d = abs(a[xOff + i] - b[yOff + i])
                if (d > worst) { worst = d; worstAt = i; worstCh = c }
            }
        }
        return if (worst > tol) "$what differs from the input: max |diff| = $worst at frame ${xOff + worstAt} (channel $worstCh), tolerance $tol" else null
    }
}
