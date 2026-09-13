package dev.muisc.dsp.stretch

import dev.muisc.audio.AudioBuffer

/**
 * Streaming side of a [TimeStretcher]: push input frames, pull output frames, change the ratio in between.
 *
 * Time mapping: output frame `n` corresponds to input time `n / ratio` (for a constant ratio), i.e. **there is
 * no alignment shift** between input and output. What a stream does have is *look-ahead*: an output frame can
 * only be produced once the input is available [latencyFrames] input frames beyond the corresponding input
 * position, so in a real-time system the output stream is delayed by that much input.
 *
 * Contract:
 *  - [push] accepts any number of frames and produces every output hop that becomes computable;
 *  - [available] is the number of output frames that can be pulled right now;
 *  - [pull] copies up to `frames` produced frames and returns how many were copied;
 *  - [setRatio] takes effect at the next synthesis hop (hops are ~15 ms), so a ratio glide is a sequence of
 *    `setRatio` calls between small pushes, or a [setRatioCurve];
 *  - [flush] marks the end of the input and produces the tail; after it [totalOutputFrames] is known exactly:
 *    the integral of the ratio over the input, which for a constant ratio is `round(inputFrames * ratio)`;
 *  - [reset] returns to the freshly constructed state.
 *
 * Implementations pre-allocate their work buffers; they only grow the output queue when the consumer stops
 * pulling (documented per implementation).
 */
interface StretchStream {
    val sampleRate: Int
    val channels: Int

    /** Ratio (output duration / input duration) applied to the next synthesis hop. */
    val ratio: Double

    /** Sets the ratio for the following hops (must be positive; useful range 0.5..2). Clears any ratio curve. */
    fun setRatio(ratio: Double)

    /**
     * Installs a ratio curve sampled once per synthesis hop at the output frame index where the hop starts
     * (the argument is the number of output frames produced so far); `null` removes the curve and goes back to
     * the value given to [setRatio]. Used for tempo glides.
     */
    fun setRatioCurve(curve: ((outputFrame: Int) -> Double)?)

    /** Input look-ahead in input frames: output at input position p needs input up to `p + latencyFrames`. */
    val latencyFrames: Int

    /** Total input frames pushed since the last reset. */
    val inputFramesPushed: Int

    /** Total output frames pulled since the last reset. */
    val outputFramesPulled: Int

    /** Pushes `frames` input frames (from `input[ch][offset..]`) and produces every computable output hop. */
    fun push(input: Array<FloatArray>, frames: Int, offset: Int = 0)

    /** Output frames that can be pulled now. */
    fun available(): Int

    /** Copies up to [frames] produced frames into `output[ch][offset..]`; returns the number copied. */
    fun pull(output: Array<FloatArray>, frames: Int, offset: Int = 0): Int

    /** Ends the input: renders the remaining output so that every frame up to [totalOutputFrames] can be pulled. */
    fun flush()

    /** True once [flush] has been called. */
    val isFlushed: Boolean

    /** Exact total output length; only defined (>= 0) after [flush], -1 before. */
    val totalOutputFrames: Int

    fun reset()
}

/**
 * Pitch-preserving time-stretch with a ratio that may vary over time.
 *
 * `ratio = outputDuration / inputDuration`: 1.05 makes the audio 5 % longer (slower), 0.95 shorter (faster);
 * pitch is preserved in every case. All one-shot methods are deterministic and return a new [AudioBuffer]
 * at the input sample rate and channel count.
 *
 * `transients` is an optional sorted list of input frame indices of transients (kicks, snares — typically the
 * output of an onset detector or a beat grid). Implementations use it to make sure each transient is copied to
 * the output exactly once and at its nominal output time (`frame * ratio`), so that a beat grid stays exact and
 * kicks are never doubled or dropped. Passing `null` disables the protection.
 */
interface TimeStretcher {
    /** Stretches the whole buffer by a constant [ratio]; output length is exactly `round(input.frames * ratio)`. */
    fun stretch(input: AudioBuffer, ratio: Double, transients: IntArray? = null): AudioBuffer {
        require(ratio > 0.0) { "ratio must be positive, was $ratio" }
        val target = Math.round(input.frames * ratio).toInt()
        val stream = createStream(input.sampleRate, input.channelCount, transients)
        stream.setRatio(ratio)
        return runStream(stream, input, target)
    }

    /**
     * Stretches with a ratio curve sampled at each synthesis hop as a function of the output frame index (tempo
     * glide). The output length is the integral of the ratio over the input (reported exactly by the stream).
     */
    fun stretch(input: AudioBuffer, ratioAtOutputFrame: (Int) -> Double, transients: IntArray? = null): AudioBuffer {
        val stream = createStream(input.sampleRate, input.channelCount, transients)
        stream.setRatioCurve(ratioAtOutputFrame)
        return runStream(stream, input, -1)
    }

    /**
     * Stretches so that the output has EXACTLY [outputFrames] frames (ratio `outputFrames / input.frames`),
     * for phase-locking a deck to a master beat grid.
     */
    fun stretchToLength(input: AudioBuffer, outputFrames: Int, transients: IntArray? = null): AudioBuffer {
        require(outputFrames >= 0) { "outputFrames must be >= 0" }
        require(input.frames > 0) { "input must not be empty" }
        val stream = createStream(input.sampleRate, input.channelCount, transients)
        stream.setRatio(outputFrames.toDouble() / input.frames)
        return runStream(stream, input, outputFrames)
    }

    /** Creates a streaming instance for the given format. */
    fun createStream(sampleRate: Int, channels: Int, transients: IntArray? = null): StretchStream

    companion object {
        /**
         * Drives a stream over the whole [input]: pushes in blocks, flushes and collects the output, then trims or
         * zero-pads the result to [targetFrames] (or to the stream's exact total when `targetFrames < 0`).
         */
        fun runStream(stream: StretchStream, input: AudioBuffer, targetFrames: Int): AudioBuffer {
            val ch = input.channelCount
            val block = 4096
            var pos = 0
            var produced = 0
            var cap = maxOf(1024, (input.frames * maxOf(stream.ratio, 1.0)).toInt() + 8192)
            var out = Array(ch) { FloatArray(cap) }
            fun drain() {
                var avail = stream.available()
                while (avail > 0) {
                    if (produced + avail > cap) {
                        cap = maxOf(cap * 2, produced + avail)
                        out = Array(ch) { out[it].copyOf(cap) }
                    }
                    val got = stream.pull(out, avail, produced)
                    produced += got
                    if (got == 0) break
                    avail = stream.available()
                }
            }
            while (pos < input.frames) {
                val n = minOf(block, input.frames - pos)
                stream.push(input.channels, n, pos)
                pos += n
                drain()
            }
            stream.flush()
            drain()
            val target = if (targetFrames >= 0) targetFrames else stream.totalOutputFrames
            val result = Array(ch) { c ->
                val a = FloatArray(target)
                System.arraycopy(out[c], 0, a, 0, minOf(target, produced))
                a
            }
            return AudioBuffer(input.sampleRate, result)
        }
    }
}
