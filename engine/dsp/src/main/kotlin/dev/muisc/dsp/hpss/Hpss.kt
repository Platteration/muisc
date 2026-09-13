package dev.muisc.dsp.hpss

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.Istft
import dev.muisc.dsp.fft.Stft
import dev.muisc.dsp.fft.StftFrameSink
import dev.muisc.dsp.window.Window
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Output of [Hpss.separate]: the harmonic and percussive components (same shape as the input) plus a per-frame
 * percussiveness curve. `harmonic + percussive` equals the input up to the STFT round-trip error (the masks sum
 * to exactly 1 and the inverse STFT is linear), so `original - harmonic - percussive` is at float-rounding level.
 */
class HpssResult(
    val harmonic: AudioBuffer,
    val percussive: AudioBuffer,
    /**
     * Per STFT frame (centred convention: frame `t` is centred on sample `t * hop`), the ratio
     * `percussive energy / total energy` in `[0, 1]` — the energy of the masked percussive spectrum divided by
     * the energy of the input spectrum, summed over channels; 0 for silent frames. Frames in the sustained part
     * of a tone sit near 0, frames on a click near 1. This is the onset/texture cue for the analysis module.
     */
    val percussiveness: FloatArray,
    val frameSize: Int,
    val hop: Int,
) {
    val sampleRate: Int get() = harmonic.sampleRate

    /** Number of STFT frames (length of [percussiveness]). */
    val frames: Int get() = percussiveness.size

    /** Time in seconds of the centre of frame [t]. */
    fun frameTime(t: Int): Double = t.toDouble() * hop / sampleRate

    /** Frame index whose centre is nearest to [seconds], clamped to the valid range. */
    fun frameAt(seconds: Double): Int = Math.round(seconds * sampleRate / hop).toInt().coerceIn(0, maxOf(0, frames - 1))

    /** `harmonic + percussive` (newly allocated) — equals the input up to STFT round-trip error. */
    fun reconstruct(): AudioBuffer {
        val out = harmonic.copy()
        for (c in 0 until out.channelCount) {
            val o = out[c]; val p = percussive[c]
            for (i in o.indices) o[i] += p[i]
        }
        return out
    }
}

/**
 * Harmonic/percussive source separation by median filtering (D. Fitzgerald, "Harmonic/Percussive Separation
 * using Median Filtering", DAFx 2010), with the soft-mask variant of Driedger, Müller & Disch (ISMIR 2014).
 *
 * Algorithm, per channel:
 *  1. STFT with [frameSize] / [hop] and the given (Hann by default) [window], centred frames (stage-1 [Stft]).
 *  2. Magnitude spectrogram `S[t][k]`.
 *  3. Harmonic enhancement `H = median over time` of S (window of [harmonicKernel] frames per bin): sustained
 *     partials survive, short transients are removed.
 *     Percussive enhancement `P = median over frequency` of S (window of [percussiveKernel] bins per frame):
 *     broadband transients survive, narrow spectral peaks are removed.
 *     Both medians use the shrinking-window edge mode of [MedianFilter].
 *  4. Soft Wiener-type masks `Mh = H^p / (H^p + P^p)`, `Mp = 1 - Mh` with power `p` = [maskPower]
 *     (p = 1: ratio mask, p = 2: Wiener filter, p = +Inf: binary mask). Because `Mp` is computed as the exact
 *     complement of `Mh`, the masked spectra sum to the input spectrum bit-for-bit.
 *  5. Inverse STFT (stage-1 [Istft], least-squares overlap-add) of both masked spectra.
 *
 * The implementation is streaming: frames flow through a ring of `harmonicKernel + 1` STFT frames and a
 * [MedianBank] (one lane per bin), so the memory footprint is independent of the track length and the outputs
 * are written with a latency of `harmonicKernel / 2` frames. A 3-minute stereo 44.1 kHz track separates in a
 * few seconds on a desktop JVM. [separateChannel] allocates nothing after construction; [separate] allocates
 * only the result buffers. Channels are processed independently.
 *
 * @param frameSize STFT frame length (power of two); 2048 at 44.1 kHz gives 21.5 Hz bins and 46 ms frames.
 * @param hop STFT hop in samples (frameSize / 4 with a Hann window for a perfect round trip).
 * @param harmonicKernel odd number of frames in the time median (≈ 200 ms for 17 frames of hop 512).
 * @param percussiveKernel odd number of bins in the frequency median (≈ 366 Hz for 17 bins of 21.5 Hz).
 * @param maskPower mask exponent p (> 0, or `Double.POSITIVE_INFINITY` for a binary mask).
 * @param window analysis and synthesis window of length [frameSize].
 */
class Hpss(
    val frameSize: Int = 2048,
    val hop: Int = 512,
    val harmonicKernel: Int = 17,
    val percussiveKernel: Int = 17,
    val maskPower: Double = 2.0,
    window: FloatArray = Window.hann(frameSize),
) {
    init {
        require(harmonicKernel > 0 && harmonicKernel % 2 == 1) { "harmonicKernel must be a positive odd integer, was $harmonicKernel" }
        require(percussiveKernel > 0 && percussiveKernel % 2 == 1) { "percussiveKernel must be a positive odd integer, was $percussiveKernel" }
        require(maskPower > 0.0) { "maskPower must be positive, was $maskPower" }
    }

    /** Number of STFT bins per frame, `frameSize / 2 + 1`. */
    val bins: Int = frameSize / 2 + 1

    private val stft = Stft(frameSize, hop, window, center = true)
    private val istftH = Istft(frameSize, hop, window, center = true)
    private val istftP = Istft(frameSize, hop, window, center = true)

    private val half = harmonicKernel / 2
    private val ringSize = harmonicKernel + 1
    private val ringRe = Array(ringSize) { FloatArray(bins) }
    private val ringIm = Array(ringSize) { FloatArray(bins) }
    private val ringMag = Array(ringSize) { FloatArray(bins) }
    private val bank = MedianBank(bins, harmonicKernel)
    private val hEst = FloatArray(bins)
    private val pEst = FloatArray(bins)
    private val medianWork = FloatArray(percussiveKernel)
    private val maskedRe = FloatArray(bins)
    private val maskedIm = FloatArray(bins)
    private val synthBuf = FloatArray(frameSize)

    // Per-run state (set by run(), cleared by reset()).
    private var framesIn = 0
    private var framesOut = 0
    private var posH = 0
    private var posP = 0
    private var destLen = 0
    private var destH: FloatArray? = null
    private var destP: FloatArray? = null
    private var percNum: DoubleArray? = null
    private var percDen: DoubleArray? = null
    private var percOut: FloatArray? = null
    private var synthesize = true

    private val sink = StftFrameSink { t, re, im -> onFrame(t, re, im) }

    /** Number of STFT frames (= length of the percussiveness curve) for an input of [length] samples. */
    fun frameCount(length: Int): Int = stft.frameCount(length)

    /** Clears all streaming state. */
    fun reset() {
        stft.reset(); istftH.reset(); istftP.reset(); bank.reset()
        framesIn = 0; framesOut = 0; posH = 0; posP = 0
        destH = null; destP = null; percNum = null; percDen = null
    }

    /**
     * Separates one channel [x] into [harmonic] and [percussive] (each of length ≥ `x.size`; either may be
     * null to skip its synthesis) and optionally writes the per-frame percussiveness ratio into
     * [percussiveness] (length ≥ [frameCount] of `x.size`). Returns the number of STFT frames. Allocates nothing.
     */
    fun separateChannel(x: FloatArray, harmonic: FloatArray?, percussive: FloatArray?, percussiveness: FloatArray? = null): Int {
        val frames = frameCount(x.size)
        require(percussiveness == null || percussiveness.size >= frames) { "percussiveness output too short" }
        run(x, harmonic, percussive, null, null, percussiveness)
        return frames
    }

    /** One-shot separation of every channel of [audio] (channels are independent). */
    fun separate(audio: AudioBuffer): HpssResult {
        val n = audio.frames
        val frames = frameCount(n)
        val h = AudioBuffer.silence(audio.sampleRate, audio.channelCount, n)
        val p = AudioBuffer.silence(audio.sampleRate, audio.channelCount, n)
        val num = DoubleArray(frames)
        val den = DoubleArray(frames)
        for (c in 0 until audio.channelCount) run(audio[c], h[c], p[c], num, den)
        val perc = FloatArray(frames) { ratio(num[it], den[it]) }
        return HpssResult(h, p, perc, frameSize, hop)
    }

    /**
     * Percussiveness curve of [audio] only (masks are computed, the inverse STFTs are skipped): per frame,
     * percussive energy / total energy summed over channels; see [HpssResult.percussiveness].
     */
    fun percussiveness(audio: AudioBuffer): FloatArray {
        val frames = frameCount(audio.frames)
        val num = DoubleArray(frames)
        val den = DoubleArray(frames)
        for (c in 0 until audio.channelCount) run(audio[c], null, null, num, den)
        return FloatArray(frames) { ratio(num[it], den[it]) }
    }

    private fun ratio(num: Double, den: Double): Float = if (den > 0.0) (num / den).toFloat().coerceIn(0f, 1f) else 0f

    /**
     * Streams [x] through the separator; per-frame energies are *accumulated* into [num]/[den] when given
     * (multi-channel path) and the single-channel ratio is written to [perc] when given.
     */
    private fun run(x: FloatArray, h: FloatArray?, p: FloatArray?, num: DoubleArray?, den: DoubleArray?, perc: FloatArray? = null) {
        require(h == null || h.size >= x.size) { "harmonic output too short" }
        require(p == null || p.size >= x.size) { "percussive output too short" }
        reset()
        destLen = x.size
        destH = h; destP = p
        percNum = num; percDen = den; percOut = perc
        synthesize = h != null || p != null
        stft.process(x, 0, x.size, sink)
        stft.flush(sink)
        // Drain: frames whose look-ahead window is truncated by the end of the signal.
        while (framesOut < framesIn) {
            val u = framesOut
            val evict = u - half - 1
            if (evict >= 0) bank.remove(ringMag[evict % ringSize])
            emit(u)
        }
        if (synthesize) {
            if (h != null) posH = copyOut(istftH.flush(synthBuf, 0), h, posH)
            if (p != null) posP = copyOut(istftP.flush(synthBuf, 0), p, posP)
        }
        // Frames beyond the last hop multiple are covered by the trailing padding: nothing more to write.
        destH = null; destP = null; percNum = null; percDen = null; percOut = null
    }

    private fun onFrame(t: Int, re: FloatArray, im: FloatArray) {
        val slot = t % ringSize
        System.arraycopy(re, 0, ringRe[slot], 0, bins)
        System.arraycopy(im, 0, ringIm[slot], 0, bins)
        val mag = ringMag[slot]
        for (k in 0 until bins) { val r = re[k]; val i = im[k]; mag[k] = sqrt(r * r + i * i) }
        if (bank.count < harmonicKernel) bank.push(mag) else bank.replace(ringMag[(t - harmonicKernel) % ringSize], mag)
        framesIn = t + 1
        val u = t - half
        if (u >= 0) emit(u)
    }

    /** Computes masks for frame [u] (whose time window is currently in the bank), pushes it to both ISTFTs. */
    private fun emit(u: Int) {
        val slot = u % ringSize
        val re = ringRe[slot]; val im = ringIm[slot]; val mag = ringMag[slot]
        bank.median(hEst)
        MedianFilter.filter1D(mag, pEst, bins, percussiveKernel, medianWork)
        var eTotal = 0.0
        var ePerc = 0.0
        val power = maskPower
        for (k in 0 until bins) {
            val hv = hEst[k]; val pv = pEst[k]
            val mh: Float = when {
                power == 2.0 -> { val a = hv * hv; val b = pv * pv; val s = a + b; if (s > 0f) a / s else 0.5f }
                power == 1.0 -> { val s = hv + pv; if (s > 0f) hv / s else 0.5f }
                power == Double.POSITIVE_INFINITY -> if (hv > pv) 1f else if (hv < pv) 0f else 0.5f
                else -> { val a = hv.toDouble().pow(power); val b = pv.toDouble().pow(power); val s = a + b; if (s > 0.0) (a / s).toFloat() else 0.5f }
            }
            val r = re[k]; val i = im[k]
            val hr = mh * r; val hi = mh * i
            val pr = r - hr; val pi = i - hi
            maskedRe[k] = hr; maskedIm[k] = hi
            // Percussive spectrum is written into the ring slot in place (the frame is never read again).
            re[k] = pr; im[k] = pi
            eTotal += r.toDouble() * r + i.toDouble() * i
            ePerc += pr.toDouble() * pr + pi.toDouble() * pi
        }
        // Bins 1..N/2-1 appear twice in the full spectrum; the ratio is unaffected by that common factor except at
        // DC/Nyquist, which is negligible for audio, so the one-sided sums are used directly.
        percNum?.let { it[u] += ePerc }
        percDen?.let { it[u] += eTotal }
        percOut?.let { it[u] = ratio(ePerc, eTotal) }
        if (synthesize) {
            val h = destH
            if (h != null) posH = copyOut(istftH.pushFrame(maskedRe, maskedIm, synthBuf, 0), h, posH)
            val p = destP
            if (p != null) posP = copyOut(istftP.pushFrame(re, im, synthBuf, 0), p, posP)
        }
        framesOut = u + 1
    }

    private fun copyOut(written: Int, dest: FloatArray, pos: Int): Int {
        val n = minOf(written, destLen - pos)
        if (n > 0) System.arraycopy(synthBuf, 0, dest, pos, n)
        return pos + written
    }
}
