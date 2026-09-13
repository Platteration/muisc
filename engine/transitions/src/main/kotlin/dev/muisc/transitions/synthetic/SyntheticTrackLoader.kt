package dev.muisc.transitions.synthetic

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.resample.Resampler
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.TrackAudioLoader
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import java.util.concurrent.ConcurrentHashMap

/**
 * [TrackAudioLoader] over in-memory [SyntheticTrack]s: the deterministic audio source for strategy tests, the
 * Lab and the CLI's `synth` mode.
 *
 * Tracks are looked up by [TrackRef.id] first and by [TrackRef.source] (the `synthetic:` source id) second, so a
 * plain `TrackRef` built from a registered track's analysis resolves too. Ranges outside the track are
 * zero-padded (frames before 0 and past the end read as silence) and the result always has exactly
 * `range.length` frames. When `prefs.sampleRate` differs from the rate a track was rendered at, the whole track is
 * resampled once with the `dsp` [Resampler] and cached, and the range is then taken in the requested rate's
 * frames (all plan positions are engine-rate frames); the channel count is adapted with
 * [AudioBuffer.withChannels]. Thread-safe.
 */
class SyntheticTrackLoader(tracks: Iterable<SyntheticTrack> = emptyList()) : TrackAudioLoader {
    private val byId = ConcurrentHashMap<String, SyntheticTrack>()
    private val bySource = ConcurrentHashMap<String, SyntheticTrack>()
    private data class FormatKey(val id: String, val sampleRate: Int, val channels: Int)
    private val converted = ConcurrentHashMap<FormatKey, AudioBuffer>()
    private val resampler = Resampler()

    init { for (t in tracks) register(t) }

    /** Registers (or replaces) a track; returns `this` for chaining. */
    fun register(track: SyntheticTrack): SyntheticTrackLoader {
        byId[track.id] = track
        bySource[track.trackRef.source.value] = track
        converted.keys.removeIf { it.id == track.id }
        return this
    }

    /** Builds the ground-truth track for [song] via [SyntheticTracks.trackRef], registers it and returns it. */
    fun register(song: SyntheticSong, id: String = SyntheticTracks.sourceId(song), albumId: String? = null, prefs: TransitionPrefs = TransitionPrefs()): SyntheticTrack =
        SyntheticTracks.trackRef(song, id, albumId, prefs).also { register(it) }

    operator fun plusAssign(track: SyntheticTrack) { register(track) }

    /** All registered tracks (unordered; look tracks up by id with [get]). */
    val tracks: Collection<SyntheticTrack> get() = byId.values

    operator fun get(id: String): SyntheticTrack? = byId[id]

    /** Resolves a [TrackRef] to a registered track (by id, then by source id) or throws. */
    fun resolve(track: TrackRef): SyntheticTrack =
        byId[track.id] ?: bySource[track.source.value]
            ?: throw IllegalArgumentException("track '${track.id}' (${track.source}) is not registered with this SyntheticTrackLoader")

    override fun load(track: TrackRef, range: FrameRange, prefs: TransitionPrefs): AudioBuffer {
        val t = resolve(track)
        val audio = audioAt(t, prefs.sampleRate, prefs.channels)
        return sliceZeroPadded(audio, range)
    }

    /** The whole track at the requested format (identity when it already matches, else converted once and cached). */
    fun audioAt(track: SyntheticTrack, sampleRate: Int, channels: Int): AudioBuffer {
        if (track.sampleRate == sampleRate && track.channels == channels) return track.audio
        return converted.computeIfAbsent(FormatKey(track.id, sampleRate, channels)) {
            resampler.resample(track.audio, sampleRate).withChannels(channels)
        }
    }

    companion object {
        /** `audio.slice` for Long ranges: frames outside `[0, audio.frames)` are zero. */
        fun sliceZeroPadded(audio: AudioBuffer, range: FrameRange): AudioBuffer {
            val len = range.length
            val out = Array(audio.channelCount) { FloatArray(len) }
            val srcFrom = range.start.coerceAtLeast(0L)
            val srcTo = range.end.coerceAtMost(audio.frames.toLong())
            if (srcTo > srcFrom) {
                val n = (srcTo - srcFrom).toInt()
                val dstOff = (srcFrom - range.start).toInt()
                for (c in 0 until audio.channelCount) System.arraycopy(audio.channels[c], srcFrom.toInt(), out[c], dstOff, n)
            }
            return AudioBuffer(audio.sampleRate, out)
        }
    }
}
