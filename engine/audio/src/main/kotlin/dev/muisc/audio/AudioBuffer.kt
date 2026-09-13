package dev.muisc.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * In-memory PCM audio, planar (one FloatArray per channel), samples in [-1, 1].
 *
 * This is the single audio container used by every engine module. It is deliberately simple:
 * a sample rate and an array of equally-sized channel arrays. All frame indices are `Int`,
 * which is enough for anything we ever hold in memory (13 hours at 44.1 kHz).
 *
 * Buffers are mutable for DSP efficiency, but the convention is: a function that receives a
 * buffer must not modify it unless its name says so (e.g. `applyGainInPlace`).
 */
class AudioBuffer(
    val sampleRate: Int,
    val channels: Array<FloatArray>,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channels.isNotEmpty()) { "at least one channel required" }
        val n = channels[0].size
        require(channels.all { it.size == n }) { "all channels must have the same length" }
    }

    val channelCount: Int get() = channels.size
    val frames: Int get() = channels[0].size
    val durationSec: Double get() = frames.toDouble() / sampleRate
    val isEmpty: Boolean get() = frames == 0

    operator fun get(channel: Int): FloatArray = channels[channel]

    /** Mixdown to mono by averaging channels. Returns the single channel array directly if already mono. */
    fun mono(): FloatArray {
        if (channelCount == 1) return channels[0]
        val out = FloatArray(frames)
        val scale = 1f / channelCount
        for (ch in channels) for (i in 0 until frames) out[i] += ch[i] * scale
        return out
    }

    /** New buffer holding frames [start, end). Out-of-range parts are zero-filled. */
    fun slice(start: Int, end: Int): AudioBuffer {
        require(end >= start) { "end ($end) < start ($start)" }
        val len = end - start
        val out = Array(channelCount) { FloatArray(len) }
        val srcFrom = max(0, start)
        val srcTo = min(frames, end)
        if (srcTo > srcFrom) {
            for (c in 0 until channelCount) {
                System.arraycopy(channels[c], srcFrom, out[c], srcFrom - start, srcTo - srcFrom)
            }
        }
        return AudioBuffer(sampleRate, out)
    }

    fun sliceSeconds(startSec: Double, endSec: Double): AudioBuffer =
        slice(secondsToFrames(startSec), secondsToFrames(endSec))

    fun copy(): AudioBuffer = AudioBuffer(sampleRate, Array(channelCount) { channels[it].copyOf() })

    fun secondsToFrames(sec: Double): Int = Math.round(sec * sampleRate).toInt()
    fun framesToSeconds(frames: Int): Double = frames.toDouble() / sampleRate

    /** Convert to the given channel count (mono→stereo duplicates, stereo→mono averages). */
    fun withChannels(count: Int): AudioBuffer {
        if (count == channelCount) return this
        return when {
            count == 1 -> AudioBuffer(sampleRate, arrayOf(mono()))
            channelCount == 1 -> AudioBuffer(sampleRate, Array(count) { channels[0].copyOf() })
            else -> {
                val m = mono()
                AudioBuffer(sampleRate, Array(count) { if (it < channelCount) channels[it].copyOf() else m.copyOf() })
            }
        }
    }

    /** Multiplies every sample by [gain]. */
    fun applyGainInPlace(gain: Float): AudioBuffer {
        for (ch in channels) for (i in ch.indices) ch[i] *= gain
        return this
    }

    /** Peak absolute sample value across all channels. */
    fun peak(): Float {
        var p = 0f
        for (ch in channels) for (v in ch) { val a = if (v < 0) -v else v; if (a > p) p = a }
        return p
    }

    /** RMS over all channels and frames. */
    fun rms(): Float {
        if (frames == 0) return 0f
        var acc = 0.0
        for (ch in channels) for (v in ch) acc += v.toDouble() * v
        return sqrt(acc / (frames.toDouble() * channelCount)).toFloat()
    }

    /** Interleaved copy (L R L R ...), useful for AudioTrack / WAV output. */
    fun interleaved(): FloatArray {
        val out = FloatArray(frames * channelCount)
        for (c in 0 until channelCount) {
            val ch = channels[c]
            var j = c
            for (i in 0 until frames) { out[j] = ch[i]; j += channelCount }
        }
        return out
    }

    /** Appends [other] (must match sample rate and channel count). */
    fun concat(other: AudioBuffer): AudioBuffer {
        require(other.sampleRate == sampleRate) { "sample rate mismatch" }
        require(other.channelCount == channelCount) { "channel count mismatch" }
        val out = Array(channelCount) { FloatArray(frames + other.frames) }
        for (c in 0 until channelCount) {
            System.arraycopy(channels[c], 0, out[c], 0, frames)
            System.arraycopy(other.channels[c], 0, out[c], frames, other.frames)
        }
        return AudioBuffer(sampleRate, out)
    }

    override fun toString(): String = "AudioBuffer(sr=$sampleRate, ch=$channelCount, frames=$frames, %.2fs)".format(durationSec)

    companion object {
        fun silence(sampleRate: Int, channelCount: Int, frames: Int): AudioBuffer =
            AudioBuffer(sampleRate, Array(channelCount) { FloatArray(frames) })

        fun mono(sampleRate: Int, samples: FloatArray): AudioBuffer = AudioBuffer(sampleRate, arrayOf(samples))

        fun stereo(sampleRate: Int, left: FloatArray, right: FloatArray): AudioBuffer =
            AudioBuffer(sampleRate, arrayOf(left, right))

        fun fromInterleaved(sampleRate: Int, channelCount: Int, data: FloatArray, frames: Int = data.size / channelCount): AudioBuffer {
            val out = Array(channelCount) { FloatArray(frames) }
            for (i in 0 until frames) for (c in 0 until channelCount) out[c][i] = data[i * channelCount + c]
            return AudioBuffer(sampleRate, out)
        }
    }
}
