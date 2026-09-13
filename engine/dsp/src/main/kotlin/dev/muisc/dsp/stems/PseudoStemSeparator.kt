package dev.muisc.dsp.stems

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.BiquadCoefficients
import dev.muisc.dsp.filter.BiquadFilter
import dev.muisc.dsp.filter.LinkwitzRiley
import dev.muisc.dsp.hpss.Hpss

/**
 * Signal-processing "pseudo-stems" ([StemQuality.PSEUDO]) that are always available, built from stage-1
 * Linkwitz-Riley crossovers, median-filtering HPSS ([Hpss]) and a stereo centre extractor ([CentreExtractor]):
 *
 *  - **drums**  = the percussive HPSS component, full band. Sub-bass percussive content (< ~60 Hz) is the kick
 *    body and is deliberately *kept in drums* so that swapping drums between decks swaps the kick as a whole;
 *    set [percussiveSubToBassHz] > 0 to move the LR4 low band of the percussive component below that
 *    frequency into the bass stem instead (drums then keep the exact complement, `p - low(p)`).
 *  - **bass**   = the LR4 (24 dB/oct, -6 dB at the corner) low band of the harmonic component below
 *    [bassCrossoverHz].
 *  - **vocals** = the harmonic remainder `h - bass`, band-limited to [vocalLowHz]..[vocalHighHz] with LR4
 *    high- and low-passes, then — for stereo input — centre-extracted with [CentreExtractor] at strength
 *    [centreExtraction] (hard-panned instruments are removed, centred material such as a lead vocal is kept).
 *    For mono (or > 2 channel) input no spatial cue exists: the stem is the band-limited harmonic remainder
 *    scaled by 0.5, and vocal isolation is weak (it is really "harmonic mid band").
 *  - **other**  = `input - drums - bass - vocals`, computed sample by sample, so the four stems always sum
 *    to the input exactly (the [Stems] invariant) regardless of STFT round-trip error. It absorbs the
 *    non-centred / out-of-band harmonic content and the HPSS residual.
 *
 * All crossovers are **zero-phase**: the stage-1 Butterworth section of the LR4 design
 * ([LinkwitzRiley.lr4LowPassSection] / [LinkwitzRiley.lr4HighPassSection]) is run forward and then backward
 * over the whole buffer (Gustafsson's forward-backward "filtfilt"), which squares the magnitude — giving exactly
 * the LR4 magnitude response (-6 dB at the corner, 24 dB/oct) — and cancels the phase. Zero phase matters
 * here because the stems are complementary by *subtraction*: with a phase-rotating IIR band the residual
 * `x - band(x)` would contain a phase-shifted phantom copy of the band (up to +6 dB), whereas with zero-phase
 * bands `band` and `x - band` are magnitude-complementary and sum to the input sample-exactly. The centre
 * extraction is a real-valued STFT mask and therefore zero-phase as well. The price is offline (whole-buffer)
 * processing and a short edge transient at the very start/end of the buffer.
 *
 * Leakage is inherent: sustained bass-drum tails end up in bass, tonal percussion in other/vocals, and the
 * vocal stem contains every centred harmonic instrument in its band. The class is not thread-safe (the HPSS
 * and centre extractor hold streaming state); use one instance per thread.
 *
 * @param bassCrossoverHz LR4 corner between bass and the rest of the harmonic component (default 250 Hz).
 * @param vocalLowHz / vocalHighHz LR4 band limits of the vocal stem (default 200 Hz – 8 kHz).
 * @param centreExtraction strength in [0, 1] of the stereo centre extraction (1 = full similarity mask).
 * @param percussiveSubToBassHz 0 (default) keeps all percussive content in drums; otherwise the corner below
 *   which percussive content is re-routed to the bass stem.
 * @param hpss the HPSS instance used for the harmonic/percussive split. The default uses a 31-frame (≈ 360 ms
 *   at hop 512) time median, longer than the plain [Hpss] default of 17: ringing kick bodies (100–200 ms) then
 *   still count as transients and stay in drums instead of drifting into bass.
 */
class PseudoStemSeparator(
    val bassCrossoverHz: Double = 250.0,
    val vocalLowHz: Double = 200.0,
    val vocalHighHz: Double = 8000.0,
    val centreExtraction: Float = 1f,
    val percussiveSubToBassHz: Double = 0.0,
    val hpss: Hpss = Hpss(harmonicKernel = 31),
) : StemSeparator {
    init {
        require(bassCrossoverHz > 0) { "bassCrossoverHz must be positive" }
        require(vocalLowHz > 0 && vocalHighHz > vocalLowHz) { "vocal band must satisfy 0 < low < high" }
        require(centreExtraction in 0f..1f) { "centreExtraction must be in [0, 1]" }
        require(percussiveSubToBassHz >= 0) { "percussiveSubToBassHz must be >= 0" }
    }

    override val quality: StemQuality get() = StemQuality.PSEUDO

    private val centre = CentreExtractor(hpss.frameSize, hpss.hop, centreExtraction)

    /** Gain applied to the band-limited harmonic remainder when no stereo cue is available. */
    val monoVocalGain: Float = 0.5f

    override fun separate(audio: AudioBuffer): Stems {
        val sr = audio.sampleRate
        val fs = sr.toDouble()
        val ch = audio.channelCount
        val n = audio.frames
        require(bassCrossoverHz < fs / 2 && vocalHighHz < fs / 2) { "crossover frequencies must lie below Nyquist" }

        val hp = hpss.separate(audio)
        val h = hp.harmonic
        val drums = hp.percussive // percussive component; may be modified below

        // Bass = zero-phase LR4 low band of the harmonic component.
        val bass = AudioBuffer.silence(sr, ch, n)
        zeroPhase(LinkwitzRiley.lr4LowPassSection(bassCrossoverHz, fs), h.channels, bass.channels, n)

        if (percussiveSubToBassHz > 0) {
            val sub = AudioBuffer.silence(sr, ch, n)
            zeroPhase(LinkwitzRiley.lr4LowPassSection(percussiveSubToBassHz, fs), drums.channels, sub.channels, n)
            for (c in 0 until ch) {
                val d = drums[c]; val s = sub[c]; val b = bass[c]
                for (i in 0 until n) { d[i] -= s[i]; b[i] += s[i] }
            }
        }

        // Harmonic remainder above the bass crossover, then the vocal band.
        val rest = AudioBuffer.silence(sr, ch, n)
        for (c in 0 until ch) { val r = rest[c]; val hh = h[c]; val b = bass[c]; for (i in 0 until n) r[i] = hh[i] - b[i] }
        val band = AudioBuffer.silence(sr, ch, n)
        zeroPhase(LinkwitzRiley.lr4HighPassSection(vocalLowHz, fs), rest.channels, band.channels, n)
        zeroPhase(LinkwitzRiley.lr4LowPassSection(vocalHighHz, fs), band.channels, band.channels, n)

        val vocals: AudioBuffer = if (ch == 2 && centreExtraction > 0f) {
            centre.extract(band)
        } else {
            band.applyGainInPlace(monoVocalGain)
        }

        // Other = exact residual so that the stems sum to the input bit-for-bit (up to float rounding).
        val other = AudioBuffer.silence(sr, ch, n)
        for (c in 0 until ch) {
            val o = other[c]; val x = audio[c]; val d = drums[c]; val b = bass[c]; val v = vocals[c]
            for (i in 0 until n) o[i] = x[i] - d[i] - b[i] - v[i]
        }
        return Stems(sr, drums, bass, vocals, other, StemQuality.PSEUDO)
    }

    companion object {
        /**
         * Zero-phase filtering (forward-backward): applies the biquad [section] to `input` into `output` (may be
         * the same arrays), reverses, applies it again from cleared state and reverses back. The result has the
         * squared magnitude response of the section and zero phase; for one Butterworth section of an LR4 design
         * this is exactly the LR4 magnitude. `output` is fully overwritten.
         */
        fun zeroPhase(section: BiquadCoefficients, input: Array<FloatArray>, output: Array<FloatArray>, n: Int) {
            val f = BiquadFilter(input.size, section)
            f.process(input, output, n)
            for (ch in output) reverse(ch, n)
            f.reset()
            f.process(output, output, n)
            for (ch in output) reverse(ch, n)
        }

        private fun reverse(x: FloatArray, n: Int) {
            var i = 0; var j = n - 1
            while (i < j) { val t = x[i]; x[i] = x[j]; x[j] = t; i++; j-- }
        }
    }
}
