package dev.muisc.dsp.fx

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Feedback Delay Network reverb after Jot & Chaigne ("Digital delay networks for designing artificial
 * reverberators", AES 1991):
 *
 *     s_i[n] = line_i[n - L_i]                          (read the i-th delay line)
 *     f_i[n] = g_i * LP_i(s_i[n])                       (per-line RT60 gain and one-pole damping)
 *     line_i[n] = x[n - P] + sum_j H_ij f_j[n]          (Hadamard feedback matrix H, pre-delayed input)
 *     wet_c[n] = sum_i c_ci s_i[n]                      (per-channel +/-1 output tap patterns)
 *
 * - [lines] (a power of two, default 8) delay lengths are spread geometrically from 23 to 68 ms, scaled to the
 *   sample rate and rounded up to distinct prime numbers of frames, so no two lines share a common period and
 *   the modal density is as even as possible.
 * - The feedback matrix is the normalised Walsh–Hadamard matrix `H / sqrt(N)` (orthogonal, lossless), applied
 *   with the fast butterfly (N log2 N adds).
 * - Decay: `g_i = 10^(-3 L_i / (T60 fs))` gives every mode the same reverberation time [decaySeconds] (RT60)
 *   because the per-line gains are `gamma^{L_i}`, which maps every pole `z` of the lossless network to
 *   `gamma z`.
 * - Damping: a one-pole low-pass at [dampingHz] in every feedback path shortens the decay of high frequencies
 *   (bypassed when `dampingHz >= sampleRate / 2`).
 * - Output channels are decorrelated by different ±1 tap patterns (channel c uses the sign of bit `c mod 3` of
 *   the line index) scaled by `1 / sqrt(N)`.
 * - [freeze]: the per-line gain becomes exactly 1, damping is bypassed and the input is muted; since `H` is
 *   orthogonal the energy stored in the network is conserved and the tail sustains indefinitely.
 * - [preDelayFrames] delays the input (up to [maxPreDelaySeconds]); [mix] blends dry and wet.
 *
 * Input of any channel count is summed to mono before entering the network; dry output channel `c` is input
 * channel `c` (or the mono sum when the input has fewer channels). Allocation-free after construction.
 */
class FdnReverb(
    val sampleRate: Int,
    val channels: Int = 2,
    val lines: Int = 8,
    val maxPreDelaySeconds: Double = 0.25,
) {
    init {
        require(sampleRate > 0 && channels > 0) { "sampleRate and channels must be positive" }
        require(lines >= 2 && (lines and (lines - 1)) == 0) { "lines must be a power of two >= 2, was $lines" }
        require(maxPreDelaySeconds >= 0.0) { "maxPreDelaySeconds must be >= 0" }
    }

    /** Prime delay-line lengths in frames. */
    val delayLengths: IntArray = primeLengths(sampleRate, lines)
    private val bufs = Array(lines) { FloatArray(delayLengths[it]) }
    private val idx = IntArray(lines)
    private val gains = FloatArray(lines)
    private val lpState = FloatArray(lines)
    private var lpCoef = 1f
    private var lpBypass = true
    private val s = FloatArray(lines)
    private val v = FloatArray(lines)
    private val matrixScale = (1.0 / sqrt(lines.toDouble())).toFloat()
    private val outScale = matrixScale
    private val preSize = (maxPreDelaySeconds * sampleRate).toInt() + 1
    private val pre = FloatArray(preSize)
    private var preW = 0
    private val xTmp = FloatArray(channels)

    /** Reverberation time RT60 in seconds (> 0). */
    var decaySeconds: Double = 2.0
        set(value) {
            require(value > 0.0 && value.isFinite()) { "decaySeconds must be positive" }
            field = value
            updateGains()
        }

    /** Cutoff of the per-line damping low-pass (Hz); at or above Nyquist the damping is bypassed. */
    var dampingHz: Double = 6000.0
        set(value) {
            require(value > 0.0) { "dampingHz must be positive" }
            field = value
            lpBypass = value >= sampleRate / 2.0
            lpCoef = (1.0 - exp(-2.0 * PI * value / sampleRate)).coerceIn(1e-6, 1.0).toFloat()
        }

    /** Pre-delay applied to the input, in frames (clamped to the maximum). */
    var preDelayFrames: Int = 0
        set(value) { field = value.coerceIn(0, preSize - 1) }

    /** Dry/wet mix in [0, 1]. */
    var mix: Double = 0.3
        set(value) { field = value.coerceIn(0.0, 1.0) }

    /** Freeze: infinite sustain of the current tail, input muted. */
    var freeze: Boolean = false

    init {
        updateGains()
        dampingHz = 6000.0
    }

    private fun updateGains() {
        for (i in 0 until lines) gains[i] = 10.0.pow(-3.0 * delayLengths[i] / (decaySeconds * sampleRate)).toFloat()
    }

    /** Per-line feedback gain currently in effect (before freeze). */
    fun lineGain(i: Int): Float = gains[i]

    /** Clears every delay line, the damping filters and the pre-delay. */
    fun reset() {
        for (b in bufs) b.fill(0f)
        idx.fill(0)
        lpState.fill(0f)
        pre.fill(0f)
        preW = 0
    }

    /** Processes [frames] frames; [input] (>= 1 channel) and [output] (>= [channels]) may share arrays. */
    fun process(input: Array<FloatArray>, output: Array<FloatArray>, frames: Int, inputOffset: Int = 0, outputOffset: Int = 0) {
        require(input.isNotEmpty() && output.size >= channels) { "need input and $channels output channels" }
        val inCh = input.size
        val invIn = 1f / inCh
        val dry = (1.0 - mix).toFloat()
        val wet = mix.toFloat()
        val frozen = freeze
        val bypass = lpBypass
        val a = lpCoef
        val n = lines
        for (t in 0 until frames) {
            var x = 0f
            for (c in 0 until inCh) x += input[c][inputOffset + t]
            x *= invIn
            for (c in 0 until channels) xTmp[c] = if (c < inCh) input[c][inputOffset + t] else x
            // Pre-delay.
            pre[preW] = x
            var rp = preW - preDelayFrames
            if (rp < 0) rp += preSize
            val xin = if (frozen) 0f else pre[rp]
            preW++
            if (preW == preSize) preW = 0
            // Read the lines, apply damping and decay gains.
            for (i in 0 until n) {
                val si = bufs[i][idx[i]]
                s[i] = si
                if (frozen) {
                    v[i] = si
                } else {
                    var d = si
                    if (!bypass) { val st = lpState[i] + a * (si - lpState[i]); lpState[i] = st; d = st }
                    v[i] = d * gains[i]
                }
            }
            hadamard(v, n)
            // Write back and advance.
            for (i in 0 until n) {
                val b = bufs[i]
                b[idx[i]] = v[i] * matrixScale + xin
                var k = idx[i] + 1
                if (k == b.size) k = 0
                idx[i] = k
            }
            // Output taps.
            for (c in 0 until channels) {
                val bit = c % 3
                var acc = 0f
                for (i in 0 until n) acc += if (((i shr bit) and 1) == 0) s[i] else -s[i]
                output[c][outputOffset + t] = dry * xTmp[c] + wet * acc * outScale
            }
        }
    }

    fun processInPlace(buffer: Array<FloatArray>, frames: Int = buffer[0].size) = process(buffer, buffer, frames)

    companion object {
        /** In-place unnormalised fast Walsh–Hadamard transform of `v[0 until n]` (n a power of two). */
        fun hadamard(v: FloatArray, n: Int) {
            var h = 1
            while (h < n) {
                var i = 0
                while (i < n) {
                    for (j in i until i + h) {
                        val a = v[j]; val b = v[j + h]
                        v[j] = a + b; v[j + h] = a - b
                    }
                    i += 2 * h
                }
                h = h shl 1
            }
        }

        /** Distinct prime delay lengths spread geometrically between 23 ms and 68 ms at [sampleRate]. */
        fun primeLengths(sampleRate: Int, lines: Int): IntArray {
            val out = IntArray(lines)
            var last = 1
            for (i in 0 until lines) {
                val ms = if (lines == 1) 40.0 else 23.0 * (67.7 / 23.0).pow(i.toDouble() / (lines - 1))
                var n = maxOf(Math.round(ms * sampleRate / 1000.0).toInt(), last + 1, 2)
                while (!isPrime(n)) n++
                out[i] = n
                last = n
            }
            return out
        }

        fun isPrime(n: Int): Boolean {
            if (n < 2) return false
            if (n % 2 == 0) return n == 2
            var d = 3
            while (d.toLong() * d <= n) { if (n % d == 0) return false; d += 2 }
            return true
        }
    }
}
