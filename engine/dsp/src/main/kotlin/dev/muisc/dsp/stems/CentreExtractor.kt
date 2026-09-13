package dev.muisc.dsp.stems

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.Istft
import dev.muisc.dsp.fft.Stft
import dev.muisc.dsp.fft.StftFrameSink
import dev.muisc.dsp.window.Window

/**
 * Centre-channel extraction for stereo audio by an inter-channel similarity mask in the STFT domain
 * (C. Avendano, "Frequency-domain source identification and manipulation in stereo mixes for enhancement,
 * suppression and re-panning applications", WASPAA 2003 — the "panning index" family of methods).
 *
 * For every STFT bin with left/right spectra `L`, `R` the similarity
 *
 *     sim = 2 Re(L R*) / (|L|^2 + |R|^2)  =  (|M|^2 - |S|^2) / (|M|^2 + |S|^2),   M = (L+R)/2, S = (L-R)/2
 *
 * is 1 for a component that is identical in both channels (centred), 0 for a hard-panned or fully decorrelated
 * one and negative for anti-phase ("wide") content; it is clamped to `[0, 1]`. This is exactly "mid energy minus
 * side energy, normalised", i.e. the fraction of each bin that is *not* explained by the side channel. The mask
 * `m = 1 - strength * (1 - sim)` is applied to both channels (`L' = m L`, `R' = m R`), so the stereo image of the
 * centred material is preserved and hard-panned material is removed; [strength] = 0 passes the input unchanged.
 * Because the mask is a real gain per bin the extraction is a linear-phase, artefact-light operation with the
 * usual STFT time smearing (frame length).
 *
 * The implementation is streaming (both channels are pushed through stage-1 [Stft] instances hop by hop and
 * paired frame by frame, then resynthesised with [Istft]) and allocates nothing after construction in
 * [extractChannels]; [extract] allocates only the result.
 */
class CentreExtractor(
    val frameSize: Int = 2048,
    val hop: Int = 512,
    val strength: Float = 1f,
    window: FloatArray = Window.hann(frameSize),
) {
    init { require(strength in 0f..1f) { "strength must be in [0, 1], was $strength" } }

    /** Number of STFT bins per frame. */
    val bins: Int = frameSize / 2 + 1
    private val stftL = Stft(frameSize, hop, window)
    private val stftR = Stft(frameSize, hop, window)
    private val istftL = Istft(frameSize, hop, window)
    private val istftR = Istft(frameSize, hop, window)
    private val queueSize = frameSize / hop + 2
    private val qRe = Array(queueSize) { FloatArray(bins) }
    private val qIm = Array(queueSize) { FloatArray(bins) }
    private var qHead = 0
    private var qTail = 0
    private val mRe = FloatArray(bins)
    private val mIm = FloatArray(bins)
    private val synth = FloatArray(frameSize)
    private var outL: FloatArray? = null
    private var outR: FloatArray? = null
    private var posL = 0
    private var posR = 0
    private var destLen = 0

    private val sinkL = StftFrameSink { _, re, im ->
        val s = qHead % queueSize
        System.arraycopy(re, 0, qRe[s], 0, bins); System.arraycopy(im, 0, qIm[s], 0, bins)
        qHead++
    }
    private val sinkR = StftFrameSink { _, re, im -> pair(re, im) }

    /** Clears the streaming state. */
    fun reset() {
        stftL.reset(); stftR.reset(); istftL.reset(); istftR.reset()
        qHead = 0; qTail = 0; posL = 0; posR = 0; outL = null; outR = null
    }

    /**
     * Extracts the centre of the stereo pair ([left], [right]) into ([outLeft], [outRight]) (length ≥ input; may
     * not alias the inputs). Allocates nothing.
     */
    fun extractChannels(left: FloatArray, right: FloatArray, outLeft: FloatArray, outRight: FloatArray) {
        require(left.size == right.size) { "left/right length mismatch" }
        require(outLeft.size >= left.size && outRight.size >= left.size) { "outputs too short" }
        reset()
        destLen = left.size
        outL = outLeft; outR = outRight
        var pos = 0
        while (pos < left.size) {
            val n = minOf(hop, left.size - pos)
            stftL.process(left, pos, n, sinkL)
            stftR.process(right, pos, n, sinkR)
            pos += n
        }
        stftL.flush(sinkL)
        stftR.flush(sinkR)
        check(qHead == qTail) { "channel frame counts diverged" }
        posL = copyOut(istftL.flush(synth, 0), outLeft, posL)
        posR = copyOut(istftR.flush(synth, 0), outRight, posR)
        outL = null; outR = null
    }

    /** One-shot centre extraction of a stereo buffer (returns a new stereo buffer of the same length). */
    fun extract(stereo: AudioBuffer): AudioBuffer {
        require(stereo.channelCount == 2) { "CentreExtractor needs a stereo buffer, got ${stereo.channelCount} channels" }
        val out = AudioBuffer.silence(stereo.sampleRate, 2, stereo.frames)
        extractChannels(stereo[0], stereo[1], out[0], out[1])
        return out
    }

    private fun pair(rRe: FloatArray, rIm: FloatArray) {
        check(qTail < qHead) { "right frame arrived before its left frame" }
        val s = qTail % queueSize
        val lRe = qRe[s]; val lIm = qIm[s]
        val k1 = strength
        for (k in 0 until bins) {
            val lr = lRe[k]; val li = lIm[k]; val rr = rRe[k]; val ri = rIm[k]
            val den = lr * lr + li * li + rr * rr + ri * ri
            val sim = if (den > 0f) (2f * (lr * rr + li * ri) / den).coerceIn(0f, 1f) else 1f
            val m = 1f - k1 * (1f - sim)
            lRe[k] = m * lr; lIm[k] = m * li
            mRe[k] = m * rr; mIm[k] = m * ri
        }
        qTail++
        posL = copyOut(istftL.pushFrame(lRe, lIm, synth, 0), outL!!, posL)
        posR = copyOut(istftR.pushFrame(mRe, mIm, synth, 0), outR!!, posR)
    }

    private fun copyOut(written: Int, dest: FloatArray, pos: Int): Int {
        val n = minOf(written, destLen - pos)
        if (n > 0) System.arraycopy(synth, 0, dest, pos, n)
        return pos + written
    }
}
