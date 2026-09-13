package dev.muisc.dsp.filter

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/** Output taps of a [StateVariableFilter]. */
enum class SvfMode { LOW_PASS, HIGH_PASS, BAND_PASS, NOTCH }

/**
 * Topology-preserving-transform (TPT) state variable filter after Zavalishin ("The Art of VA Filter Design")
 * in the trapezoidal-integrator form popularised by Andrew Simper (Cytomic):
 *
 *     g = tan(pi fc / fs),  k = 1 / Q
 *     a1 = 1 / (1 + g (g + k)),  a2 = g a1,  a3 = g a2
 *     v3 = x - ic2 ;  v1 = a1 ic1 + a2 v3 ;  v2 = ic2 + a2 ic1 + a3 v3
 *     ic1 = 2 v1 - ic1 ;  ic2 = 2 v2 - ic2
 *     low = v2,  band = k v1 (unity peak gain),  high = x - k v1 - v2,  notch = x - k v1
 *
 * The zero-delay feedback solution keeps the response exactly matched to the analog SVF (pre-warped by the
 * tan), stays stable for any cutoff in (0, fs/2) and any Q > 0, and tolerates per-sample coefficient changes,
 * which is why it is the classic choice for DJ filter sweeps.
 *
 * Cutoff and resonance are targets: [setCutoff] / [setResonance] are reached by linear interpolation of `g`
 * and `k` over the next processed block (call once per block for zipper-free automation). [setCutoffRamp]
 * schedules an explicit multi-block ramp (linear or exponential in Hz) that takes precedence until finished.
 * State is per channel; allocation-free after construction.
 */
class StateVariableFilter(val sampleRate: Int, val channels: Int = 1, cutoffHz: Double = 1000.0, q: Double = 0.7071067811865476) {
    init { require(channels > 0); require(sampleRate > 0) }

    private val ic1 = DoubleArray(channels)
    private val ic2 = DoubleArray(channels)
    private val fs = sampleRate.toDouble()
    private val maxCutoff = fs * 0.49
    private val minCutoff = 1.0

    var mode: SvfMode = SvfMode.LOW_PASS

    /** Current cutoff (Hz) in effect at the start of the next block. */
    var cutoffHz: Double = cutoffHz.coerceIn(minCutoff, maxCutoff)
        private set
    /** Current Q in effect at the start of the next block. */
    var resonance: Double = q
        private set
    private var targetCutoff = this.cutoffHz
    private var targetQ = q

    // explicit ramp
    private var rampActive = false
    private var rampFrom = 0.0
    private var rampTo = 0.0
    private var rampTotal = 0
    private var rampDone = 0
    private var rampExp = false

    /** Sets the cutoff target (reached at the end of the next block; clamped to [1 Hz, 0.49 fs]). */
    fun setCutoff(hz: Double) { targetCutoff = hz.coerceIn(minCutoff, maxCutoff); rampActive = false }

    /** Sets the resonance target (Q > 0; Q = 1/sqrt 2 is flat, Q = 20 is a strong, still stable, peak). */
    fun setResonance(q: Double) { require(q > 0) { "Q must be positive" }; targetQ = q }

    /**
     * Schedules a cutoff sweep from [fromHz] to [toHz] over the next [frames] processed frames, linear in Hz or
     * ([exponential]) geometric — constant octaves per second, which is what a DJ filter knob feels like.
     * The sweep starts immediately at [fromHz] and ends exactly at [toHz]; after it finishes the cutoff stays at
     * [toHz].
     */
    fun setCutoffRamp(fromHz: Double, toHz: Double, frames: Int, exponential: Boolean = true) {
        require(frames > 0) { "frames must be positive" }
        rampFrom = fromHz.coerceIn(minCutoff, maxCutoff)
        rampTo = toHz.coerceIn(minCutoff, maxCutoff)
        rampTotal = frames; rampDone = 0; rampExp = exponential; rampActive = true
        cutoffHz = rampFrom; targetCutoff = rampTo
    }

    /** True while a [setCutoffRamp] sweep is still in progress. */
    val isRamping: Boolean get() = rampActive

    fun reset() { ic1.fill(0.0); ic2.fill(0.0); cutoffHz = targetCutoff; resonance = targetQ; rampActive = false }

    /** Cutoff value that will be in effect [frames] frames into the next block (for tests / display). */
    private fun rampCutoffAt(done: Int): Double {
        val t = done.toDouble() / rampTotal
        return if (rampExp) rampFrom * exp(ln(rampTo / rampFrom) * t) else rampFrom + (rampTo - rampFrom) * t
    }

    /** Processes all channels ([input] and [output] may be the same arrays) using the current [mode]. */
    fun process(input: Array<FloatArray>, output: Array<FloatArray>, frames: Int) {
        if (frames <= 0) return
        // Determine block start/end cutoff & Q, then interpolate g and k linearly across the block.
        val c0 = cutoffHz
        val c1: Double
        if (rampActive) {
            val n = minOf(frames, rampTotal - rampDone)
            c1 = if (n == frames) rampCutoffAt(rampDone + frames) else rampTo
            rampDone += n
            if (rampDone >= rampTotal) rampActive = false
        } else c1 = targetCutoff
        val q0 = resonance; val q1 = targetQ
        val g0 = tan(PI * c0 / fs); val g1 = tan(PI * c1 / fs)
        val k0 = 1.0 / q0; val k1 = 1.0 / q1
        val dg = (g1 - g0) / frames; val dk = (k1 - k0) / frames
        for (ch in 0 until channels) processChannel(input[ch], output[ch], frames, ch, g0, dg, k0, dk)
        cutoffHz = c1; resonance = q1
    }

    fun processInPlace(buffer: Array<FloatArray>, frames: Int = buffer[0].size) = process(buffer, buffer, frames)

    /** Mono convenience on channel 0. */
    fun process(input: FloatArray, output: FloatArray, frames: Int = input.size) = process(arrayOf(input), arrayOf(output), frames)

    private fun processChannel(x: FloatArray, y: FloatArray, frames: Int, ch: Int, g0: Double, dg: Double, k0: Double, dk: Double) {
        var g = g0; var k = k0
        var s1 = ic1[ch]; var s2 = ic2[ch]
        val m = mode
        for (i in 0 until frames) {
            g += dg; k += dk
            val a1 = 1.0 / (1.0 + g * (g + k))
            val a2 = g * a1
            val a3 = g * a2
            val v0 = x[i].toDouble()
            val v3 = v0 - s2
            val v1 = a1 * s1 + a2 * v3
            val v2 = s2 + a2 * s1 + a3 * v3
            s1 = 2 * v1 - s1
            s2 = 2 * v2 - s2
            val out = when (m) {
                SvfMode.LOW_PASS -> v2
                SvfMode.BAND_PASS -> k * v1
                SvfMode.HIGH_PASS -> v0 - k * v1 - v2
                SvfMode.NOTCH -> v0 - k * v1
            }
            y[i] = out.toFloat()
        }
        ic1[ch] = s1; ic2[ch] = s2
    }

    /** Analytic magnitude (dB) of the current cutoff/Q/mode at [freqHz] (the bilinear-mapped analog SVF). */
    fun magnitudeDb(freqHz: Double): Double {
        // The TPT SVF equals the bilinear transform of the analog SVF with pre-warped cutoff, so its response
        // is that of the analog prototype evaluated at the pre-warped frequency W = tan(pi f / fs) / g.
        val g = tan(PI * cutoffHz / fs)
        val w = tan(PI * freqHz / fs) / g
        val k = 1.0 / resonance
        // denominator: 1 - w^2 + j k w
        val dr = 1.0 - w * w; val di = k * w
        val den = dr * dr + di * di
        val num = when (mode) {
            SvfMode.LOW_PASS -> 1.0
            SvfMode.HIGH_PASS -> w * w * w * w
            SvfMode.BAND_PASS -> k * k * w * w
            SvfMode.NOTCH -> dr * dr
        }
        return 10.0 * kotlin.math.log10(num / den)
    }
}
