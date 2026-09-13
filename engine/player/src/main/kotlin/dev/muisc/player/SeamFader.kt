package dev.muisc.player

/**
 * Linear (equal-gain) micro-fade at a segment seam.
 *
 * At a switch the player [retain]s up to [fadeFrames] frames of the *continuation* of the previous segment — what
 * would have played next (the body's next frames, B after a render's `bEntryFrame`) — and [apply] blends them into
 * the first frames of the new segment: `out = prev + (next - prev) * x`, `x = (k + 1) / (n + 1)`. Written that way
 * the blend is a bit-exact no-op when both sides carry identical samples (the splice contract, album gapless) and a
 * click-free 5 ms declick when they differ slightly. The linear law is the right one for coherent material (an
 * equal-power seam would add a +3 dB bump on identical signals).
 */
class SeamFader(val fadeFrames: Int, val channels: Int = 2) {
    init { require(fadeFrames >= 0 && channels > 0) }

    /** Scratch the caller fills before [begin] (capacity [fadeFrames] frames). */
    val tail: Array<FloatArray> = Array(channels) { FloatArray(fadeFrames) }
    private var tailLen = 0
    private var pos = 0

    /** True while a seam blend is in progress. */
    val active: Boolean get() = pos < tailLen

    /** Frames of the blend still to be applied. */
    val remaining: Int get() = tailLen - pos

    /** Starts a blend of the first [frames] frames already written into [tail]. */
    fun begin(frames: Int) {
        tailLen = frames.coerceIn(0, fadeFrames)
        pos = 0
    }

    /** Copies `src[c][offset until offset + n]` into [tail] and starts the blend. */
    fun retain(src: Array<FloatArray>, offset: Int, n: Int) {
        val len = n.coerceIn(0, fadeFrames)
        for (c in 0 until channels) System.arraycopy(src[c], offset, tail[c], 0, len)
        begin(len)
    }

    fun cancel() { tailLen = 0; pos = 0 }

    /**
     * Blends the retained tail into `dst[c][offset ..]` for up to [frames] frames (fewer when the blend ends first).
     * Returns the number of frames blended.
     */
    fun apply(dst: Array<FloatArray>, offset: Int, frames: Int): Int {
        val n = minOf(frames, tailLen - pos)
        if (n <= 0) return 0
        val inv = 1f / (tailLen + 1)
        for (c in 0 until channels) {
            val t = tail[c]; val d = dst[c]
            var k = pos
            for (i in 0 until n) {
                val x = (k + 1) * inv
                val p = t[k]
                val idx = offset + i
                d[idx] = p + (d[idx] - p) * x
                k++
            }
        }
        pos += n
        return n
    }
}
