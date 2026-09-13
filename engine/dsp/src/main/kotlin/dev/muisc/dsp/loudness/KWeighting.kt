package dev.muisc.dsp.loudness

import dev.muisc.dsp.filter.BiquadCascade
import dev.muisc.dsp.filter.BiquadCoefficients
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.tan

/**
 * ITU-R BS.1770-4 "K-weighting" pre-filter: the frequency weighting applied to every channel before the
 * mean-square (loudness) measurement.
 *
 * The weighting is a cascade of two second-order sections:
 *  1. a high-frequency shelving filter modelling the acoustic effect of the head (+4 dB above ~1.5 kHz,
 *     corner ~1681 Hz), and
 *  2. the "RLB" (Revised Low-frequency B-curve) high-pass, a 2nd-order high-pass with its -3 dB point near
 *     38 Hz.
 *
 * The standard only tabulates coefficients for 48 kHz. Both sections are, however, bilinear transforms of
 * fixed analogue prototypes, so the coefficients for *any* sample rate follow from the classical bilinear
 * shelving / high-pass design equations with `K = tan(pi f0 / fs)` and the prototype parameters (f0, Q, gain)
 * that reproduce the published 48 kHz table (the values used by pyloudnorm / Brecht De Man's loudness toolbox
 * and libebur128): f0 = 1681.974 Hz, G = 3.99984 dB, Q = 0.70718 for the shelf and f0 = 38.1355 Hz,
 * Q = 0.50033 for the high-pass. At 48 kHz the result matches the BS.1770-4 table to better than 1e-12.
 *
 * The overall gain at 1 kHz is about +0.70 dB (0.698 dB at 48 kHz, 0.700 dB at 44.1 kHz), which is what the
 * `-0.691` constant in the loudness formula compensates for, so that a full-scale 1 kHz sine measures 0 LUFS
 * (per channel; a stereo pair adds 3 dB).
 */
object KWeighting {
    /** Shelf prototype: corner frequency (Hz). */
    const val SHELF_F0: Double = 1681.974450955533
    /** Shelf prototype: gain (dB). */
    const val SHELF_GAIN_DB: Double = 3.999843853973347
    /** Shelf prototype: quality factor. */
    const val SHELF_Q: Double = 0.7071752369554196
    /** RLB high-pass prototype: corner frequency (Hz). */
    const val HIGHPASS_F0: Double = 38.13547087602444
    /** RLB high-pass prototype: quality factor. */
    const val HIGHPASS_Q: Double = 0.5003270373238773

    /**
     * Stage 1: high shelf (+4 dB) designed for [sampleRate] Hz. Bilinear transform of the 2nd-order shelving
     * prototype `H(s) = (Vh s^2 + Vb (w0/Q) s + w0^2) / (s^2 + (w0/Q) s + w0^2)` with `Vh = 10^(G/20)` and
     * `Vb = Vh^0.4997` (the mid-band gain that makes the digital response match the published 48 kHz table).
     * Requires `sampleRate > 2 * SHELF_F0`.
     */
    fun highShelf(sampleRate: Double): BiquadCoefficients {
        require(sampleRate > 2 * SHELF_F0) { "sampleRate $sampleRate too low for K-weighting" }
        val k = tan(PI * SHELF_F0 / sampleRate)
        val vh = 10.0.pow(SHELF_GAIN_DB / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val k2 = k * k
        val a0 = 1.0 + k / SHELF_Q + k2
        return BiquadCoefficients(
            b0 = (vh + vb * k / SHELF_Q + k2) / a0,
            b1 = 2.0 * (k2 - vh) / a0,
            b2 = (vh - vb * k / SHELF_Q + k2) / a0,
            a1 = 2.0 * (k2 - 1.0) / a0,
            a2 = (1.0 - k / SHELF_Q + k2) / a0,
        )
    }

    /**
     * Stage 2: RLB high-pass designed for [sampleRate] Hz. Bilinear transform of the 2nd-order high-pass
     * prototype `H(s) = s^2 / (s^2 + (w0/Q) s + w0^2)`, with the numerator kept un-normalised as `1, -2, 1`
     * exactly as the standard tabulates it (for the standard's Q this leaves the pass-band gain at unity within
     * 0.001 dB).
     */
    fun highPass(sampleRate: Double): BiquadCoefficients {
        require(sampleRate > 2 * SHELF_F0) { "sampleRate $sampleRate too low for K-weighting" }
        val k = tan(PI * HIGHPASS_F0 / sampleRate)
        val k2 = k * k
        val a0 = 1.0 + k / HIGHPASS_Q + k2
        return BiquadCoefficients(
            b0 = 1.0, b1 = -2.0, b2 = 1.0,
            a1 = 2.0 * (k2 - 1.0) / a0,
            a2 = (1.0 - k / HIGHPASS_Q + k2) / a0,
        )
    }

    /** Both sections in processing order (shelf first, then high-pass). */
    fun coefficients(sampleRate: Double): List<BiquadCoefficients> = listOf(highShelf(sampleRate), highPass(sampleRate))

    /**
     * A ready-to-run K-weighting filter for [channels] channels at [sampleRate] Hz: a [BiquadCascade] of the two
     * sections with the coefficients set immediately and zero state. Allocation-free after construction; call
     * [BiquadCascade.reset] between unrelated signals.
     */
    fun newFilter(channels: Int, sampleRate: Int): BiquadCascade = BiquadCascade(channels, coefficients(sampleRate.toDouble()))

    /** Analytic magnitude response of the full weighting in dB at [freqHz]. */
    fun magnitudeDb(freqHz: Double, sampleRate: Double): Double =
        highShelf(sampleRate).magnitudeDb(freqHz, sampleRate) + highPass(sampleRate).magnitudeDb(freqHz, sampleRate)
}
