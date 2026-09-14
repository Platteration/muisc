package dev.muisc.app.playback

import android.content.Context
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.PcmStream
import dev.muisc.audio.readRange
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.TrackAudioLoader
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import java.io.Closeable
import kotlin.math.min

/**
 * The Android [TrackAudioLoader]: the window reader the transition renderer and the Lab use (DESIGN.md §7.1).
 *
 * Windows are read through [AndroidEngineStreamFactory] opened **for the renderer**, so every seek decodes from
 * frame 0 on MP3/AAC and the window the strategy sees is bit-identical to what the body deck will play (the splice
 * contract depends on it). The returned buffer always has exactly `range.length` frames at `prefs.sampleRate` /
 * `prefs.channels`; frames before 0 and past the end of the file are zero.
 *
 * A small LRU of open streams keeps sequential windows of the same track cheap (no re-open, no re-decode when the
 * next window starts where the previous one ended). All methods are synchronised; call [close] when the queue is
 * torn down, [release] when a single track leaves the queue.
 */
class AndroidTrackAudioLoader(
    context: Context,
    private val factory: AndroidEngineStreamFactory = AndroidEngineStreamFactory(context, forRenderer = true),
    private val maxOpenStreams: Int = 4,
) : TrackAudioLoader, Closeable {

    init { require(maxOpenStreams >= 1) }

    private class Open(val source: String, val sampleRate: Int, val channels: Int, val stream: PcmStream)

    private val streams = object : LinkedHashMap<String, Open>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Open>): Boolean {
            if (size <= maxOpenStreams) return false
            try { eldest.value.stream.close() } catch (e: Exception) { }
            return true
        }
    }

    /** Streams currently held open (diagnostics). */
    val openStreamCount: Int get() = synchronized(this) { streams.size }

    @Synchronized
    override fun load(track: TrackRef, range: FrameRange, prefs: TransitionPrefs): AudioBuffer {
        val len = range.length
        if (len <= 0) return AudioBuffer.silence(prefs.sampleRate, prefs.channels, 0)
        val stream = streamFor(track, prefs.sampleRate, prefs.channels)
        if (range.start >= 0L) return stream.readRange(range.start, len)
        // A window that reaches before the beginning of the file: zero-pad the head.
        val out = AudioBuffer.silence(prefs.sampleRate, prefs.channels, len)
        val pad = min(-range.start, len.toLong()).toInt()
        val rest = len - pad
        if (rest > 0) {
            val head = stream.readRange(0L, rest)
            for (c in 0 until out.channelCount) {
                System.arraycopy(head.channels[c], 0, out.channels[c], pad, rest)
            }
        }
        return out
    }

    private fun streamFor(track: TrackRef, sampleRate: Int, channels: Int): PcmStream {
        val source = track.source.value
        val cached = streams[track.id]
        if (cached != null) {
            if (cached.source == source && cached.sampleRate == sampleRate && cached.channels == channels) {
                return cached.stream
            }
            streams.remove(track.id)
            try { cached.stream.close() } catch (e: Exception) { }
        }
        val stream = factory.open(track.source, sampleRate, channels)
        streams[track.id] = Open(source, sampleRate, channels, stream)
        return stream
    }

    /** Closes the cached stream of one track (e.g. after it left the queue). */
    @Synchronized
    fun release(trackId: String) {
        val open = streams.remove(trackId) ?: return
        try { open.stream.close() } catch (e: Exception) { }
    }

    @Synchronized
    override fun close() {
        for (open in streams.values) {
            try { open.stream.close() } catch (e: Exception) { }
        }
        streams.clear()
    }
}
