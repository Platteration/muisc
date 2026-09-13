package dev.muisc.dsp.filter

import dev.muisc.audio.AudioBuffer
import kotlin.math.min

/**
 * Linkwitz-Riley 4th-order (LR4, 24 dB/oct) crossover building blocks.
 *
 * An LR4 low-pass is two cascaded 2nd-order Butterworth low-passes (Q = 1/sqrt 2) at the same corner
 * frequency; likewise for the high-pass. Each is -6 dB at the corner, and their sum is a 2nd-order all-pass
 * at the same corner with Q = 1/sqrt 2 (`LP4 + HP4 = (s^4 + w0^4) / D^2 = conj(D) / D`), i.e. the sum is magnitude-flat.
 * Because the RBJ designs are bilinear transforms of those prototypes, the identity holds exactly in the
 * z-domain, which lets [MultibandCrossover] compensate lower bands with plain [Biquad.allPass] sections.
 */
object LinkwitzRiley {
    /** Coefficients of one of the two identical Butterworth sections of an LR4 low-pass. */
    fun lr4LowPassSection(freqHz: Double, sampleRate: Double): BiquadCoefficients = Biquad.lowPass(freqHz, sampleRate, Biquad.BUTTERWORTH_Q)

    /** Coefficients of one of the two identical Butterworth sections of an LR4 high-pass. */
    fun lr4HighPassSection(freqHz: Double, sampleRate: Double): BiquadCoefficients = Biquad.highPass(freqHz, sampleRate, Biquad.BUTTERWORTH_Q)

    /** The all-pass that equals `LR4 low + LR4 high` at [freqHz]. */
    fun lr4SumAllPass(freqHz: Double, sampleRate: Double): BiquadCoefficients = Biquad.allPass(freqHz, sampleRate, Biquad.BUTTERWORTH_Q)

    /** New LR4 low-pass cascade (2 stages) for [channels] channels. */
    fun lowPass(freqHz: Double, sampleRate: Double, channels: Int): BiquadCascade =
        BiquadCascade(channels, 2).also { it.setAll(lr4LowPassSection(freqHz, sampleRate), immediate = true) }

    /** New LR4 high-pass cascade (2 stages) for [channels] channels. */
    fun highPass(freqHz: Double, sampleRate: Double, channels: Int): BiquadCascade =
        BiquadCascade(channels, 2).also { it.setAll(lr4HighPassSection(freqHz, sampleRate), immediate = true) }
}

/**
 * Streaming 2-way LR4 crossover: splits the input into a low band and a high band whose sum is an all-pass
 * version of the input (flat magnitude). [setFrequency] ramps the corner over the next block.
 */
class LinkwitzRileyCrossover(val sampleRate: Int, val channels: Int, freqHz: Double) {
    private val low = BiquadCascade(channels, 2)
    private val high = BiquadCascade(channels, 2)

    var frequencyHz: Double = freqHz
        private set

    init { setFrequency(freqHz, immediate = true) }

    fun setFrequency(freqHz: Double, immediate: Boolean = false) {
        frequencyHz = freqHz
        low.setAll(LinkwitzRiley.lr4LowPassSection(freqHz, sampleRate.toDouble()), immediate)
        high.setAll(LinkwitzRiley.lr4HighPassSection(freqHz, sampleRate.toDouble()), immediate)
    }

    fun reset() { low.reset(); high.reset() }

    /** Writes the low band to [lowOut] and the high band to [highOut]; neither may alias [input]. */
    fun process(input: Array<FloatArray>, lowOut: Array<FloatArray>, highOut: Array<FloatArray>, frames: Int) {
        low.process(input, lowOut, frames)
        high.process(input, highOut, frames)
    }

    /** One-shot split of a whole buffer into (low, high). */
    fun split(buffer: AudioBuffer): Pair<AudioBuffer, AudioBuffer> {
        require(buffer.channelCount == channels && buffer.sampleRate == sampleRate) { "buffer shape mismatch" }
        val lo = AudioBuffer.silence(sampleRate, channels, buffer.frames)
        val hi = AudioBuffer.silence(sampleRate, channels, buffer.frames)
        reset()
        process(buffer.channels, lo.channels, hi.channels, buffer.frames)
        return lo to hi
    }

    fun lowMagnitudeDb(freqHz: Double): Double = low.magnitudeDb(freqHz, sampleRate.toDouble())
    fun highMagnitudeDb(freqHz: Double): Double = high.magnitudeDb(freqHz, sampleRate.toDouble())
}

/**
 * N-band LR4 crossover tree with all-pass compensation (the standard "cascaded 2-way + all-pass" topology).
 *
 * With crossover frequencies `f1 < f2 < ... < f(N-1)` the input is split at `f1` into band 0 and a remainder,
 * the remainder is split at `f2` into band 1 and a remainder, and so on. Because each 2-way split sums to the
 * all-pass `AP(fi)` (see [LinkwitzRiley]), band k is additionally passed through `AP(f(k+2)) ... AP(f(N-1))` so
 * that the sum of all bands is `AP(f1) AP(f2) ... AP(f(N-1)) * input`: magnitude-flat (within numerical
 * precision) with a smooth phase rotation. Note that the sum is therefore *not* sample-identical to the input;
 * use [allPassReference] to obtain the exact expected sum for a given input.
 *
 * Streaming and allocation-free after construction (internal scratch buffers of [maxBlockFrames] frames; larger
 * blocks are processed in chunks). Frequencies are fixed for the lifetime of the object.
 */
class MultibandCrossover(
    val sampleRate: Int,
    val channels: Int,
    val crossoverFreqs: DoubleArray,
    private val maxBlockFrames: Int = 4096,
) {
    init {
        require(crossoverFreqs.isNotEmpty()) { "at least one crossover frequency" }
        require(crossoverFreqs.all { it > 0 && it < sampleRate / 2.0 }) { "frequencies must lie in (0, fs/2)" }
        for (i in 1 until crossoverFreqs.size) require(crossoverFreqs[i] > crossoverFreqs[i - 1]) { "frequencies must be ascending" }
        require(maxBlockFrames > 0)
    }

    /** Number of output bands = crossoverFreqs.size + 1. */
    val bands: Int = crossoverFreqs.size + 1
    private val splits = crossoverFreqs.size
    private val fs = sampleRate.toDouble()

    private val lows = Array(splits) { LinkwitzRiley.lowPass(crossoverFreqs[it], fs, channels) }
    private val highs = Array(splits) { LinkwitzRiley.highPass(crossoverFreqs[it], fs, channels) }
    /** compensation[k] = all-pass chain applied to band k (frequencies above split k+1). */
    private val compensation: Array<BiquadCascade?> = Array(bands) { k ->
        val n = splits - (k + 1)
        if (n <= 0) null else BiquadCascade(channels, List(n) { j -> LinkwitzRiley.lr4SumAllPass(crossoverFreqs[k + 1 + j], fs) })
    }
    private val scratchA = Array(channels) { FloatArray(maxBlockFrames) }
    private val scratchB = Array(channels) { FloatArray(maxBlockFrames) }

    fun reset() {
        for (f in lows) f.reset(); for (f in highs) f.reset()
        for (c in compensation) c?.reset()
    }

    /**
     * Splits [frames] frames of [input] into `outBands[band][channel]`. Output arrays must not alias the input.
     * Works for any [frames] (chunked internally at [maxBlockFrames]).
     */
    fun process(input: Array<FloatArray>, outBands: Array<Array<FloatArray>>, frames: Int, offset: Int = 0) {
        require(outBands.size == bands) { "expected $bands band outputs" }
        var pos = offset
        val end = offset + frames
        while (pos < end) {
            val n = min(maxBlockFrames, end - pos)
            processChunk(input, outBands, pos, n)
            pos += n
        }
    }

    private fun processChunk(input: Array<FloatArray>, outBands: Array<Array<FloatArray>>, offset: Int, n: Int) {
        // Copy the chunk into scratchA (the current "remainder").
        var rem = scratchA
        var next = scratchB
        for (ch in 0 until channels) System.arraycopy(input[ch], offset, rem[ch], 0, n)
        for (k in 0 until splits) {
            val band = outBands[k]
            val isLast = k == splits - 1
            // low band -> band k (written at offset; the biquad works on whole arrays from index 0, so use scratch)
            lows[k].process(rem, next, n)
            compensation[k]?.processInPlace(next, n)
            for (ch in 0 until channels) System.arraycopy(next[ch], 0, band[ch], offset, n)
            // high band -> new remainder (or the final band)
            if (isLast) {
                highs[k].process(rem, next, n)
                for (ch in 0 until channels) System.arraycopy(next[ch], 0, outBands[bands - 1][ch], offset, n)
            } else {
                highs[k].process(rem, next, n)
                val t = rem; rem = next; next = t
            }
        }
    }

    /** One-shot split of a whole buffer into [bands] buffers of the same shape (state is reset first). */
    fun split(buffer: AudioBuffer): Array<AudioBuffer> {
        require(buffer.channelCount == channels && buffer.sampleRate == sampleRate) { "buffer shape mismatch" }
        val out = Array(bands) { AudioBuffer.silence(sampleRate, channels, buffer.frames) }
        reset()
        process(buffer.channels, Array(bands) { out[it].channels }, buffer.frames)
        return out
    }

    /**
     * A fresh all-pass chain `AP(f1) ... AP(f(N-1))` equal to the transfer function of the sum of all bands.
     * Running the input through it yields exactly what `sum(bands)` produces (up to float rounding).
     */
    fun allPassReference(): BiquadCascade =
        BiquadCascade(channels, List(splits) { LinkwitzRiley.lr4SumAllPass(crossoverFreqs[it], fs) })

    /** Analytic magnitude (dB) of band [band] at [freqHz] (including its all-pass compensation, which is 0 dB). */
    fun bandMagnitudeDb(band: Int, freqHz: Double): Double {
        var db = 0.0
        for (k in 0 until min(band, splits)) db += highs[k].magnitudeDb(freqHz, fs)
        if (band < splits) db += lows[band].magnitudeDb(freqHz, fs)
        return db
    }

    /** Analytic magnitude (dB) of the sum of all bands at [freqHz] (complex sum of the band responses). */
    fun sumMagnitudeDb(freqHz: Double): Double {
        var re = 0.0; var im = 0.0
        for (band in 0 until bands) {
            var bre = 1.0; var bim = 0.0
            fun mul(r: DoubleArray) { val nre = bre * r[0] - bim * r[1]; bim = bre * r[1] + bim * r[0]; bre = nre }
            for (k in 0 until min(band, splits)) mul(highs[k].response(freqHz, fs))
            if (band < splits) mul(lows[band].response(freqHz, fs))
            compensation[band]?.let { mul(it.response(freqHz, fs)) }
            re += bre; im += bim
        }
        return 10.0 * kotlin.math.log10(re * re + im * im)
    }
}
