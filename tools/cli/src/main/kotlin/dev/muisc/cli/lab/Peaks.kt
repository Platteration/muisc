package dev.muisc.cli.lab

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.PcmStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.max
import kotlin.math.min

/**
 * Waveform overview for drawing: the audio split into [bins] equal spans, each reduced to the minimum and maximum of
 * the channel average. [durationSec] tells the page how to map a bin to time.
 */
class Peaks(val min: FloatArray, val max: FloatArray, val durationSec: Double) {
    val bins: Int get() = min.size

    fun toJson(): JsonObject = buildJsonObject {
        put("bins", bins)
        put("durationSec", LabJson.num(durationSec, 4))
        // Three decimals is below one pixel on any canvas and keeps 1500 pairs near 20 kB.
        putJsonArray("min") { for (v in min) add(LabJson.num(v, 3)) }
        putJsonArray("max") { for (v in max) add(LabJson.num(v, 3)) }
    }

    private class Acc(bins: Int) {
        val lo = FloatArray(bins) { Float.POSITIVE_INFINITY }
        val hi = FloatArray(bins) { Float.NEGATIVE_INFINITY }
        fun add(bin: Int, v: Float) {
            if (v < lo[bin]) lo[bin] = v
            if (v > hi[bin]) hi[bin] = v
        }
        fun finish(): Pair<FloatArray, FloatArray> {
            for (i in lo.indices) if (lo[i] > hi[i]) { lo[i] = 0f; hi[i] = 0f }
            return lo to hi
        }
    }

    companion object {
        /** Peaks of an in-memory buffer; fewer bins than [bins] when the buffer is shorter than that many frames. */
        fun of(audio: AudioBuffer, bins: Int): Peaks {
            val frames = audio.frames
            val n = max(1, min(bins, frames))
            val acc = Acc(n)
            val ch = audio.channels
            val scale = 1f / ch.size
            for (i in 0 until frames) {
                var s = 0f
                for (c in ch) s += c[i]
                acc.add(((i.toLong() * n) / max(1, frames)).toInt(), s * scale)
            }
            val (lo, hi) = acc.finish()
            return Peaks(lo, hi, frames.toDouble() / audio.sampleRate)
        }

        /**
         * Peaks of a stream read in chunks. When the stream does not report its length (some VBR MP3s),
         * [estimatedSec] is used to lay out the bins and anything past it lands in the last bin.
         */
        fun of(stream: PcmStream, bins: Int, estimatedSec: Double = 0.0): Peaks {
            val total = stream.totalFrames.takeIf { it > 0 } ?: Math.round(estimatedSec * stream.sampleRate)
            require(total > 0) { "the length of this file is unknown" }
            val n = max(1, min(bins.toLong(), total).toInt())
            val acc = Acc(n)
            val chunk = 1 shl 15
            val buf = Array(stream.channelCount) { FloatArray(chunk) }
            val scale = 1f / stream.channelCount
            var pos = 0L
            while (true) {
                val got = stream.read(buf, 0, chunk)
                if (got <= 0) break
                for (i in 0 until got) {
                    var s = 0f
                    for (c in buf) s += c[i]
                    val bin = ((pos + i) * n / total).toInt().coerceAtMost(n - 1)
                    acc.add(bin, s * scale)
                }
                pos += got
            }
            val (lo, hi) = acc.finish()
            return Peaks(lo, hi, total.toDouble() / stream.sampleRate)
        }
    }
}
