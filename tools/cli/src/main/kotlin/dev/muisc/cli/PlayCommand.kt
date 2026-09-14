package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.audio.WavIo
import dev.muisc.player.JavaSoundSink
import javax.sound.sampled.LineUnavailableException

/**
 * `muisc play` — plays a WAV through the same [dev.muisc.player.AudioSink] the desktop engine uses. On a machine
 * with no sound card (CI, a container, a headless sandbox) it says so and exits 0: playback is a convenience, not
 * a test, and no script should break because a box has no speakers.
 */
class PlayCommand : MuiscCommand("play") {

    override fun help(context: Context) = "Play a WAV file through the system audio device."

    private val file by argument("WAV").file(mustExist = true, canBeDir = false)
    private val from by option("--from", metavar = "SEC", help = "Start position.").double()
    private val to by option("--to", metavar = "SEC", help = "Stop position.").double()

    override fun execute(ctx: CliContext) {
        val whole = try {
            WavIo.read(file)
        } catch (e: Exception) {
            throw CliktError("cannot read ${file.path} as a WAV file: ${e.message ?: e.javaClass.simpleName}")
        }
        val start = ((from ?: 0.0) * whole.sampleRate).toInt().coerceIn(0, whole.frames)
        val end = (to?.let { Math.round(it * whole.sampleRate).toInt() } ?: whole.frames).coerceIn(start, whole.frames)
        val audio = if (start == 0 && end == whole.frames) whole else whole.slice(start, end)
        echo("${file.name}: ${Fmt.sec(audio.durationSec)}, ${audio.channelCount} ch @ ${audio.sampleRate} Hz")

        val sink = try {
            JavaSoundSink(audio.sampleRate, audio.channelCount)
        } catch (e: LineUnavailableException) {
            echo("no audio output device available (${e.message ?: "line unavailable"}) — skipping playback")
            return
        } catch (e: IllegalArgumentException) {
            echo("no audio output device available (${e.message ?: "unsupported format"}) — skipping playback")
            return
        } catch (e: Exception) {
            echo("no audio output device available (${e.javaClass.simpleName}) — skipping playback")
            return
        }
        try {
            sink.resume()
            val interleaved = audio.interleaved()
            var at = 0
            val block = BLOCK_FRAMES
            val buf = FloatArray(block * audio.channelCount)
            while (at < audio.frames) {
                val n = minOf(block, audio.frames - at)
                System.arraycopy(interleaved, at * audio.channelCount, buf, 0, n * audio.channelCount)
                sink.write(buf, n)
                at += n
            }
        } finally {
            try { sink.close() } catch (_: Exception) { /* the device may already be gone */ }
        }
        echoElapsed("played ${Fmt.sec(audio.durationSec)}")
    }


    private companion object {
        const val BLOCK_FRAMES = 2048
    }
}
