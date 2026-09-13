package dev.muisc.dsp.filter

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10

/** Output of a [OnePole]. */
enum class OnePoleMode { LOW_PASS, HIGH_PASS }

/**
 * First-order (6 dB/oct) one-pole filter / leaky integrator, the workhorse smoother for envelopes, damping
 * and parameter de-zippering:
 *
 *     y[n] = y[n-1] + a (x[n] - y[n-1])         (low-pass, `a` in (0, 1])
 *     high-pass output = x[n] - y[n]
 *
 * The coefficient comes from the impulse-invariant mapping `a = 1 - exp(-2 pi fc / fs)` ([setCutoff]) or from a
 * time constant `a = 1 - exp(-1 / (tau fs))` ([setTimeConstant]): the step response then reaches 63.2 % of its
 * final value after `tau` seconds. The frequency response is `H(z) = a / (1 - (1 - a) z^-1)` ([magnitudeDb]);
 * for fc << fs the -3 dB point is at fc. Per-channel state, no allocation after construction, coefficient
 * changes are applied instantly (a one-pole has no zipper problem as it has no resonance).
 */
class OnePole(val channels: Int = 1, val mode: OnePoleMode = OnePoleMode.LOW_PASS) {
    init { require(channels > 0) }

    private val state = DoubleArray(channels)

    /** Smoothing coefficient `a` in (0, 1]; 1 = pass-through (LP) / zero (HP). */
    var coefficient: Double = 1.0
        private set

    fun setCoefficient(a: Double) { require(a > 0.0 && a <= 1.0) { "a must be in (0, 1]" }; coefficient = a }

    /** Sets the -3 dB cutoff (Hz) via `a = 1 - exp(-2 pi fc / fs)`. */
    fun setCutoff(cutoffHz: Double, sampleRate: Double) {
        require(cutoffHz > 0) { "cutoff must be positive" }
        coefficient = (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).coerceIn(1e-12, 1.0)
    }

    /** Sets the time constant (seconds to reach 63.2 % of a step). */
    fun setTimeConstant(tauSec: Double, sampleRate: Double) {
        require(tauSec > 0) { "tau must be positive" }
        coefficient = (1.0 - exp(-1.0 / (tauSec * sampleRate))).coerceIn(1e-12, 1.0)
    }

    /** Sets the state of every channel to [value] (e.g. to start a smoother at its target with no transient). */
    fun reset(value: Double = 0.0) { state.fill(value) }

    /** Current smoothed (low-pass) value on [channel]. */
    fun value(channel: Int = 0): Float = state[channel].toFloat()

    fun processSample(x: Float, channel: Int = 0): Float {
        val s = state[channel] + coefficient * (x - state[channel])
        state[channel] = s
        return if (mode == OnePoleMode.LOW_PASS) s.toFloat() else (x - s).toFloat()
    }

    /** Processes [frames] frames of every channel; [input] and [output] may be the same arrays. */
    fun process(input: Array<FloatArray>, output: Array<FloatArray>, frames: Int) {
        for (ch in 0 until channels) processChannel(input[ch], output[ch], frames, ch)
    }

    fun processInPlace(buffer: Array<FloatArray>, frames: Int = buffer[0].size) = process(buffer, buffer, frames)

    /** Mono convenience on channel 0. */
    fun process(input: FloatArray, output: FloatArray, frames: Int = input.size) = processChannel(input, output, frames, 0)

    private fun processChannel(x: FloatArray, y: FloatArray, frames: Int, ch: Int) {
        val a = coefficient
        var s = state[ch]
        if (mode == OnePoleMode.LOW_PASS) {
            for (i in 0 until frames) { s += a * (x[i] - s); y[i] = s.toFloat() }
        } else {
            for (i in 0 until frames) { val v = x[i]; s += a * (v - s); y[i] = (v - s).toFloat() }
        }
        state[ch] = s
    }

    /** Analytic magnitude (dB) of the current mode at [freqHz]. */
    fun magnitudeDb(freqHz: Double, sampleRate: Double): Double {
        val a = coefficient
        val w = 2.0 * PI * freqHz / sampleRate
        val p = 1.0 - a
        // |1 - p e^{-jw}|^2 = 1 - 2 p cos w + p^2
        val den = 1.0 - 2.0 * p * cos(w) + p * p
        return when (mode) {
            // LP: a / (1 - p z^-1)
            OnePoleMode.LOW_PASS -> 10.0 * log10(a * a / den)
            // HP: 1 - LP = (1 - a - p z^-1) ... = p (1 - z^-1) / (1 - p z^-1)
            OnePoleMode.HIGH_PASS -> 10.0 * log10(p * p * (2.0 - 2.0 * cos(w)) / den)
        }
    }
}
