package dev.muisc.player

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.EngineStreamFactory
import dev.muisc.audio.WavIo
import dev.muisc.transitions.PlaybackProgram
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.DeckGain
import java.io.File

/**
 * Offline driver: runs a [ProgramPlayer] (non-real-time, deterministic) over a whole program into a
 * [CapturingSink] or a WAV file. Used by the CLI for `mix` / `--context` renders and by the tests. The limiter's
 * delay is compensated, so the result has exactly the program's frames (plus whatever [commands] add or remove).
 */
object ProgramRenderer {
    /**
     * Renders [program] to memory. [commands] is called before every block with the frames rendered so far and the
     * player, so tests can inject skips / seeks / pauses at exact block boundaries.
     */
    fun render(
        program: PlaybackProgram,
        streams: EngineStreamFactory,
        prefs: TransitionPrefs,
        limits: EngineLimits = EngineLimits.DESKTOP,
        sharedGainDb: Float? = null,
        prebuilt: Map<Int, LiveGraph> = emptyMap(),
        limiter: Boolean = true,
        commands: ((framesRendered: Long, player: ProgramPlayer) -> Unit)? = null,
    ): AudioBuffer {
        val sink = CapturingSink(prefs.sampleRate, prefs.channels)
        val latency = run(program, streams, prefs, limits, sharedGainDb, prebuilt, limiter, sink, commands)
        val all = sink.toBuffer()
        return if (latency == 0) all else all.slice(latency, all.frames)
    }

    /** Renders [program] straight to [file] (latency compensated). Returns the frames written. */
    fun renderToWav(
        program: PlaybackProgram,
        file: File,
        streams: EngineStreamFactory,
        prefs: TransitionPrefs,
        limits: EngineLimits = EngineLimits.DESKTOP,
        encoding: WavIo.Encoding = WavIo.Encoding.FLOAT32,
        sharedGainDb: Float? = null,
        prebuilt: Map<Int, LiveGraph> = emptyMap(),
    ): Long {
        val audio = render(program, streams, prefs, limits, sharedGainDb, prebuilt)
        WavIo.write(file, audio, encoding)
        return audio.frames.toLong()
    }

    /** Runs the player into [sink] (raw, latency not compensated); returns the player's latency in frames. */
    fun run(
        program: PlaybackProgram,
        streams: EngineStreamFactory,
        prefs: TransitionPrefs,
        limits: EngineLimits,
        sharedGainDb: Float?,
        prebuilt: Map<Int, LiveGraph>,
        limiter: Boolean,
        sink: AudioSink,
        commands: ((framesRendered: Long, player: ProgramPlayer) -> Unit)? = null,
    ): Int {
        val player = ProgramPlayer(prefs.sampleRate, prefs.channels, limits, streams, prefs, realtime = false, limiterEnabled = limiter)
        try {
            player.submit(EngineCommand.SetProgram(program, sharedGainDb, prebuilt))
            var rendered = 0L
            val pump = SinkPump(player, sink) { p -> rendered = p.framesWritten; commands?.invoke(rendered, player) }
            commands?.invoke(0L, player)
            pump.run()
            return player.latencyFrames
        } finally {
            player.close()
        }
    }

    /** The shared album gain: the gain of the loudest track (minimum dB), so album dynamics are preserved. */
    fun albumGainDb(tracks: List<TrackRef>, prefs: TransitionPrefs): Float =
        tracks.minOfOrNull { DeckGain.of(it.analysis, prefs) } ?: 0f
}
