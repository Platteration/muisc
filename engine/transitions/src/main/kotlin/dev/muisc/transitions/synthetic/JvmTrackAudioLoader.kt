package dev.muisc.transitions.synthetic

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.AudioDecoder
import dev.muisc.audio.PcmStream
import dev.muisc.audio.jvm.JavaSoundDecoder
import dev.muisc.audio.readRange
import dev.muisc.dsp.resample.SincKernel
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.TrackAudioLoader
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * JVM [TrackAudioLoader] for real files: opens the track's [TrackRef.source] with an [AudioDecoder], reads the
 * requested range with [readRange], converts it to `prefs.sampleRate` and adapts the channel count. This is what
 * the CLI uses.
 *
 * **Position-deterministic resampling.** Engine frame `k` of a track is always evaluated at native position
 * `k * nativeRate / engineRate` with the Kaiser-sinc [SincKernel] (the same evaluation `Resampler` performs on a
 * whole file), independent of the range boundaries: the native read is padded by the kernel half-length on both
 * sides, so the same engine frame yields the same sample whichever window it is requested through. Frames before
 * the start and past the end of the file are zero (the file itself is zero-padded, exactly like a one-shot
 * `Resampler.resample` of the whole track). The result always has exactly `range.length` frames.
 *
 * **Stream cache.** The last opened [PcmStream] is kept per track id (up to [maxOpenStreams] tracks, LRU) so
 * sequential windows of one track are decoded forward without re-opening; a request that lies before the current
 * position seeks (compressed formats re-decode from the start). Call [close] to release the streams. All methods
 * are synchronised.
 */
class JvmTrackAudioLoader(
    private val decoder: AudioDecoder = JavaSoundDecoder(),
    private val kernel: SincKernel = SincKernel.DEFAULT,
    private val maxOpenStreams: Int = 8,
) : TrackAudioLoader, Closeable {
    init { require(maxOpenStreams >= 1) }

    private class Open(val source: String, val stream: PcmStream)

    private val streams = object : LinkedHashMap<String, Open>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Open>): Boolean {
            if (size <= maxOpenStreams) return false
            eldest.value.stream.close()
            return true
        }
    }

    /** Number of streams currently held open (diagnostics / tests). */
    val openStreamCount: Int get() = synchronized(this) { streams.size }

    @Synchronized
    override fun load(track: TrackRef, range: FrameRange, prefs: TransitionPrefs): AudioBuffer {
        val stream = streamFor(track)
        val native = stream.sampleRate
        val engine = prefs.sampleRate
        val out = if (native == engine) readZeroPadded(stream, range.start, range.length) else resampledRange(stream, range, engine)
        return out.withChannels(prefs.channels)
    }

    private fun resampledRange(stream: PcmStream, range: FrameRange, engine: Int): AudioBuffer {
        val native = stream.sampleRate
        val ratio = engine.toDouble() / native
        val scale = min(1.0, ratio)
        val span = ceil(kernel.halfTaps / scale).toInt() + 1
        val nativeStart = floor(range.start.toDouble() * native / engine).toLong() - span
        val nativeEnd = ceil(range.end.toDouble() * native / engine).toLong() + span + 1
        val chunk = readZeroPadded(stream, nativeStart, (nativeEnd - nativeStart).toInt())
        val len = range.length
        val out = Array(chunk.channelCount) { FloatArray(len) }
        val n = chunk.frames
        for (c in 0 until chunk.channelCount) {
            val x = chunk.channels[c]
            val dst = out[c]
            for (k in 0 until len) {
                val p = (range.start + k).toDouble() * native / engine - nativeStart
                dst[k] = kernel.interpolate(x, 0, n, p, scale)
            }
        }
        return AudioBuffer(engine, out)
    }

    /** Reads exactly [frames] native frames from [start] (which may be negative); missing frames are zero. */
    private fun readZeroPadded(stream: PcmStream, start: Long, frames: Int): AudioBuffer {
        if (frames <= 0) return AudioBuffer.silence(stream.sampleRate, stream.channelCount, 0)
        val total = stream.totalFrames
        if (start >= 0 && (total < 0 || start < total)) return stream.readRange(start, frames)
        val out = AudioBuffer.silence(stream.sampleRate, stream.channelCount, frames)
        if (start < 0) {
            val skip = (-start).coerceAtMost(frames.toLong()).toInt()
            val rest = frames - skip
            if (rest > 0 && total != 0L) {
                val tail = stream.readRange(0, rest)
                for (c in 0 until out.channelCount) System.arraycopy(tail.channels[c], 0, out.channels[c], skip, rest)
            }
        }
        return out
    }

    private fun streamFor(track: TrackRef): PcmStream {
        val source = track.source.value
        val cached = streams[track.id]
        if (cached != null) {
            if (cached.source == source) return cached.stream
            cached.stream.close()
            streams.remove(track.id)
        }
        val stream = decoder.open(track.source)
        streams[track.id] = Open(source, stream)
        return stream
    }

    /** Closes the cached stream of one track (e.g. after it left the queue). */
    @Synchronized
    fun release(trackId: String) { streams.remove(trackId)?.stream?.close() }

    @Synchronized
    override fun close() {
        for (o in streams.values) o.stream.close()
        streams.clear()
    }
}
