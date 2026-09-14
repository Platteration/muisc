package dev.muisc.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import dev.muisc.player.AudioSink
import kotlin.math.max
import kotlin.math.min

/**
 * The Android [AudioSink] (DESIGN.md §7.2): a streaming `AudioTrack` in `ENCODING_PCM_FLOAT`, `USAGE_MEDIA` /
 * `CONTENT_TYPE_MUSIC`, at the device's own output sample rate so nothing resamples behind our back.
 *
 * The buffer is `max(4 × getMinBufferSize, 120 ms)`. [write] blocks until the track has taken the frames: it is the
 * clock of the audio thread. Underruns are counted through `AudioTrack.getUnderrunCount`; three within a minute
 * double the buffer for the rest of the session (capped at [MAX_BUFFER_MS]), which is the honest latency trade-off
 * of a non-low-latency player: skip/seek become audible after the queued buffer drains.
 *
 * Everything except [close] must be called from the audio thread.
 */
class AudioTrackSink(
    override val sampleRate: Int,
    override val channels: Int,
    bufferMs: Int = DEFAULT_BUFFER_MS,
) : AudioSink {

    init { require(sampleRate > 0 && channels > 0) { "bad sink format: $sampleRate Hz / $channels ch" } }

    private val channelMask = when (channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        else -> AudioFormat.CHANNEL_OUT_STEREO
    }

    private val bytesPerFrame = channels * 4

    /** Size of the track's buffer in bytes (grows once after repeated underruns). */
    var bufferBytes: Int = bufferBytesFor(bufferMs)
        private set

    private var track: AudioTrack = build(bufferBytes)
    private var playing = false
    /** True once playback was started at least once; a later [pause] is not undone by the next [write]. */
    private var started = false
    private var framesSinceFlush = 0L
    private var lastUnderrunCount = 0
    private val underrunTimesMs = ArrayDeque<Long>()
    private var closed = false

    /** Total frames handed to the track since it was created (diagnostics). */
    var framesWritten: Long = 0L
        private set

    /** How often the buffer had to be enlarged (diagnostics / Settings "audio" page). */
    var bufferBumps: Int = 0
        private set

    private fun bufferBytesFor(ms: Int): Int {
        val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        val floor = if (min > 0) 4 * min else 0
        val wanted = (sampleRate.toLong() * ms / 1000L).toInt() * bytesPerFrame
        return max(max(floor, wanted), bytesPerFrame * 256)
    }

    private fun build(sizeBytes: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(sizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override fun write(interleaved: FloatArray, frames: Int) {
        if (closed || frames <= 0) return
        // The track is started on the first block only: after an explicit pause the writes block on a full buffer
        // (the engine's own back-pressure) instead of silently resuming playback.
        if (!started) resume()
        val samples = frames * channels
        var offset = 0
        while (offset < samples) {
            val n = try {
                track.write(interleaved, offset, samples - offset, AudioTrack.WRITE_BLOCKING)
            } catch (e: IllegalStateException) {
                return
            }
            if (n <= 0) {
                // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT: give up on this block rather than spin.
                if (n == AudioTrack.ERROR_DEAD_OBJECT) recreate()
                return
            }
            offset += n
        }
        val written = samples / channels
        framesWritten += written
        framesSinceFlush += written
        checkUnderruns()
    }

    /** Frames handed to the track that the DAC has not played yet. */
    override fun latencyFrames(): Int {
        if (closed) return 0
        val head = try { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (e: IllegalStateException) { return 0 }
        return (framesSinceFlush - head).coerceIn(0L, bufferFrames().toLong()).toInt()
    }

    fun bufferFrames(): Int = bufferBytes / bytesPerFrame

    override fun pause() {
        if (closed) return
        playing = false
        try { track.pause() } catch (e: IllegalStateException) { }
    }

    override fun flush() {
        if (closed) return
        try {
            // AudioTrack.flush() is only valid while the track is not playing.
            val wasPlaying = playing
            if (wasPlaying) track.pause()
            track.flush()
            framesSinceFlush = 0L
            if (wasPlaying) track.play() else playing = false
        } catch (e: IllegalStateException) {
        }
    }

    override fun resume() {
        if (closed) return
        try {
            track.play()
            playing = true
            started = true
        } catch (e: IllegalStateException) {
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { track.pause() } catch (e: IllegalStateException) { }
        try { track.flush() } catch (e: IllegalStateException) { }
        try { track.stop() } catch (e: IllegalStateException) { }
        try { track.release() } catch (e: Exception) { }
        playing = false
        started = false
    }

    // ------------------------------------------------------------------------------------------ underruns

    private fun checkUnderruns() {
        val count = try { track.underrunCount } catch (e: Exception) { return }
        if (count <= lastUnderrunCount) return
        lastUnderrunCount = count
        val now = SystemClock.elapsedRealtime()
        underrunTimesMs.addLast(now)
        while (underrunTimesMs.isNotEmpty() && now - underrunTimesMs.first() > UNDERRUN_WINDOW_MS) {
            underrunTimesMs.removeFirst()
        }
        if (underrunTimesMs.size >= UNDERRUNS_BEFORE_BUMP) {
            underrunTimesMs.clear()
            bumpBuffer()
        }
    }

    /** Doubles the track's buffer (up to [MAX_BUFFER_MS]) without interrupting playback when possible. */
    private fun bumpBuffer() {
        val maxBytes = (sampleRate.toLong() * MAX_BUFFER_MS / 1000L).toInt() * bytesPerFrame
        val wanted = min(bufferBytes * 2, maxBytes)
        if (wanted <= bufferBytes) return
        val frames = wanted / bytesPerFrame
        val applied = try { track.setBufferSizeInFrames(frames) } catch (e: Exception) { -1 }
        if (applied > 0) {
            bufferBytes = applied * bytesPerFrame
            bufferBumps++
        }
    }

    private fun recreate() {
        try { track.release() } catch (e: Exception) { }
        track = build(bufferBytes)
        framesSinceFlush = 0L
        lastUnderrunCount = 0
        playing = false
        started = false
    }

    companion object {
        const val DEFAULT_BUFFER_MS = 120
        const val MAX_BUFFER_MS = 400
        private const val UNDERRUN_WINDOW_MS = 60_000L
        private const val UNDERRUNS_BEFORE_BUMP = 3

        /**
         * The engine sample rate on this device: `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` (normally 48 000),
         * falling back to 48 000 when the property is missing or implausible.
         */
        fun engineSampleRate(context: Context): Int {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 48_000
            val value = try {
                manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            } catch (e: Exception) {
                null
            }
            return if (value != null && value in 8_000..192_000) value else 48_000
        }

        /** Convenience factory: a stereo sink at the device's output rate. */
        fun create(context: Context, channels: Int = 2, bufferMs: Int = DEFAULT_BUFFER_MS): AudioTrackSink =
            AudioTrackSink(engineSampleRate(context), channels, bufferMs)
    }
}
