package dev.muisc.dsp.synth

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Oscillator waveforms. SAW and SQUARE are band-limited with PolyBLEP; SINE is exact. */
enum class Waveform { SINE, SAW, SQUARE }

/**
 * Wavetable-free oscillator: sine, and band-limited sawtooth / square using the polynomial band-limited step
 * (PolyBLEP) method with the 4-point (cubic B-spline) residual of Välimäki, Pekonen & Nam, "Perceptually
 * informed synthesis of bandlimited classical waveforms using integrated polynomial interpolation", JASA 2012.
 *
 * The naive waveform is generated from a phase accumulator; whenever a discontinuity of height `A` falls at
 * fractional time `t0` between two samples, the residual `A * r(k - t0)` is added to the four surrounding
 * samples, where `r = H - u` is the difference between the integrated cubic B-spline (`H`) and the ideal step
 * (`u`), a piecewise quartic polynomial ([blepResidual]). Because two of those samples are in the past the
 * oscillator runs with a fixed [LATENCY] of 2 samples (the sine as well, for consistency). The B-spline kernel
 * shapes the harmonic series by `sinc^4(f / fs)` (-0.7 dB at 5 kHz, -3 dB at 10 kHz at 44.1 kHz) — the price
 * for alias components below -40 dB relative to the fundamental at 1 kHz (2-point PolyBLEP leaves them near
 * -30 dB).
 *
 * Frequency changes are immediate ([setFrequency]) or glided ([setGlide]) linearly or exponentially (constant
 * cents per second, the natural pitch glide) over a number of frames. Allocation-free; [process] uses plain loops.
 */
class Oscillator(val sampleRate: Int, var waveform: Waveform = Waveform.SAW) {
    init { require(sampleRate > 0) { "sampleRate must be positive" } }

    /** Current frequency in Hz. */
    var frequencyHz: Double = 440.0
        private set

    private var phase = 0.0
    private val hist = FloatArray(4)
    private var n = 0
    private var glideRemaining = 0
    private var glideExp = true
    private var glideFactor = 1.0
    private var glideStep = 0.0
    private var glideTarget = 440.0

    /** Sets the frequency immediately (cancels a running glide). Clamped to `[0, sampleRate / 4]`. */
    fun setFrequency(hz: Double) {
        frequencyHz = hz.coerceIn(0.0, sampleRate / 4.0)
        glideRemaining = 0
    }

    /**
     * Glides from [fromHz] to [toHz] over [frames] frames, exponentially (geometric, constant cents/second) or
     * linearly in Hz. The glide starts at [fromHz] on the next sample and reaches exactly [toHz] after [frames].
     */
    fun setGlide(fromHz: Double, toHz: Double, frames: Int, exponential: Boolean = true) {
        require(frames > 0) { "frames must be positive" }
        val f0 = fromHz.coerceIn(0.0, sampleRate / 4.0)
        val f1 = toHz.coerceIn(0.0, sampleRate / 4.0)
        frequencyHz = f0
        glideTarget = f1
        glideRemaining = frames
        glideExp = exponential && f0 > 0.0 && f1 > 0.0
        glideFactor = if (glideExp) (f1 / f0).pow(1.0 / frames) else 1.0
        glideStep = if (glideExp) 0.0 else (f1 - f0) / frames
    }

    /** True while a glide is in progress. */
    val isGliding: Boolean get() = glideRemaining > 0

    /** Restarts the phase accumulator (in cycles, [0, 1)) and clears the BLEP history. */
    fun reset(phase: Double = 0.0) {
        this.phase = phase - Math.floor(phase)
        hist.fill(0f)
        n = 0
    }

    /** Produces one sample (delayed by [LATENCY] samples relative to the phase accumulator). */
    fun nextSample(): Float {
        val dt = frequencyHz / sampleRate
        val p0 = phase
        var p1 = p0 + dt
        var wrapped = false
        if (p1 >= 1.0) { p1 -= 1.0; wrapped = true }
        phase = p1
        val naive = when (waveform) {
            Waveform.SINE -> sin(2.0 * PI * p1).toFloat()
            Waveform.SAW -> (2.0 * p1 - 1.0).toFloat()
            Waveform.SQUARE -> if (p1 < 0.5) 1f else -1f
        }
        hist[n and 3] += naive
        if (waveform != Waveform.SINE && dt > 0.0) {
            if (wrapped) addStep(p1 / dt, if (waveform == Waveform.SAW) -2f else 2f)
            if (waveform == Waveform.SQUARE && !wrapped && p0 < 0.5 && p1 >= 0.5) addStep((p1 - 0.5) / dt, -2f)
        }
        val outIdx = (n - 2) and 3
        val out = hist[outIdx]
        hist[outIdx] = 0f
        n++
        if (glideRemaining > 0) {
            glideRemaining--
            frequencyHz = if (glideRemaining == 0) glideTarget else if (glideExp) frequencyHz * glideFactor else frequencyHz + glideStep
        }
        return out
    }

    /** Adds the residual of a step of height [a] that occurred [f] samples (0 <= f < 1) before the current sample. */
    private fun addStep(f: Double, a: Float) {
        hist[(n - 2) and 3] += (a * blepResidual(f - 2.0)).toFloat()
        hist[(n - 1) and 3] += (a * blepResidual(f - 1.0)).toFloat()
        hist[n and 3] += (a * blepResidual(f)).toFloat()
        hist[(n + 1) and 3] += (a * blepResidual(f + 1.0)).toFloat()
    }

    /** Writes (or with [add], adds) [frames] samples to `out[offset..]`. */
    fun process(out: FloatArray, frames: Int, offset: Int = 0, add: Boolean = false) {
        if (add) for (i in 0 until frames) out[offset + i] += nextSample()
        else for (i in 0 until frames) out[offset + i] = nextSample()
    }

    companion object {
        /** Output delay of the oscillator in samples. */
        const val LATENCY = 2

        /**
         * Residual `r(t) = H(t) - u(t)` of the integrated cubic B-spline `H` (support (-2, 2)) against the unit
         * step `u`: `(2+t)^4/24` on [-2,-1], `1/2 + 2t/3 - t^3/3 - t^4/8` on [-1,0], `-1/2 + 2t/3 - t^3/3 + t^4/8`
         * on [0,1], `-(2-t)^4/24` on [1,2], zero elsewhere.
         */
        fun blepResidual(t: Double): Double = when {
            t <= -2.0 || t >= 2.0 -> 0.0
            t < -1.0 -> { val u = 2.0 + t; u * u * u * u / 24.0 }
            t < 0.0 -> 0.5 + 2.0 * t / 3.0 - t * t * t / 3.0 - t * t * t * t / 8.0
            t < 1.0 -> -0.5 + 2.0 * t / 3.0 - t * t * t / 3.0 + t * t * t * t / 8.0
            else -> { val u = 2.0 - t; -u * u * u * u / 24.0 }
        }
    }
}

/** Stage of an [Adsr] envelope. */
enum class AdsrStage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }

/**
 * Linear-segment ADSR envelope generator: attack 0 -> 1 over [attackSec], decay 1 -> [sustain] over [decaySec],
 * hold at [sustain] while the gate is on, release from the current level to 0 over [releaseSec] after
 * [gateOff]. Segment slopes are fixed per segment (a release started mid-attack takes the full release time from
 * the level reached). Retriggering with [gateOn] restarts the attack from the current level without a jump.
 */
class Adsr(
    val sampleRate: Int,
    val attackSec: Double = 0.01,
    val decaySec: Double = 0.1,
    val sustain: Double = 0.7,
    val releaseSec: Double = 0.2,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(attackSec >= 0.0 && decaySec >= 0.0 && releaseSec >= 0.0) { "times must be >= 0" }
        require(sustain in 0.0..1.0) { "sustain must be in [0, 1]" }
    }

    var stage: AdsrStage = AdsrStage.IDLE
        private set
    private var level = 0.0
    private var slope = 0.0
    private val attackFrames = Math.round(attackSec * sampleRate).toInt()
    private val decayFrames = Math.round(decaySec * sampleRate).toInt()
    private val releaseFrames = Math.round(releaseSec * sampleRate).toInt()

    /** Current envelope value. */
    val value: Float get() = level.toFloat()
    val isActive: Boolean get() = stage != AdsrStage.IDLE

    fun gateOn() {
        stage = AdsrStage.ATTACK
        slope = if (attackFrames > 0) (1.0 - level) / attackFrames else 1.0
        if (attackFrames == 0) { level = 1.0; startDecay() }
    }

    fun gateOff() {
        if (stage == AdsrStage.IDLE) return
        stage = AdsrStage.RELEASE
        slope = if (releaseFrames > 0) -level / releaseFrames else -1.0
        if (releaseFrames == 0) { level = 0.0; stage = AdsrStage.IDLE }
    }

    fun reset() { stage = AdsrStage.IDLE; level = 0.0; slope = 0.0 }

    private fun startDecay() {
        stage = AdsrStage.DECAY
        slope = if (decayFrames > 0) (sustain - 1.0) / decayFrames else -1.0
        if (decayFrames == 0) { level = sustain; stage = AdsrStage.SUSTAIN }
    }

    fun nextSample(): Float {
        val out = level
        when (stage) {
            AdsrStage.ATTACK -> { level += slope; if (level >= 1.0) { level = 1.0; startDecay() } }
            AdsrStage.DECAY -> { level += slope; if (level <= sustain) { level = sustain; stage = AdsrStage.SUSTAIN } }
            AdsrStage.SUSTAIN -> {}
            AdsrStage.RELEASE -> { level += slope; if (level <= 0.0) { level = 0.0; stage = AdsrStage.IDLE } }
            AdsrStage.IDLE -> {}
        }
        return out.toFloat()
    }

    /** Writes [frames] envelope values to `out[offset..]`, or with [multiply] scales the existing samples. */
    fun process(out: FloatArray, frames: Int, offset: Int = 0, multiply: Boolean = false) {
        if (multiply) for (i in 0 until frames) out[offset + i] *= nextSample()
        else for (i in 0 until frames) out[offset + i] = nextSample()
    }

    /** One-shot envelope of [totalFrames] frames with the gate on at 0 and off at [gateOffFrame]. */
    fun render(totalFrames: Int, gateOffFrame: Int): FloatArray {
        reset()
        val out = FloatArray(totalFrames)
        gateOn()
        val off = gateOffFrame.coerceIn(0, totalFrames)
        process(out, off, 0)
        gateOff()
        process(out, totalFrames - off, off)
        reset()
        return out
    }
}
