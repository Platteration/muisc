package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.stems.StemKind
import java.io.File

/**
 * `muisc stems` — runs the [dev.muisc.dsp.stems.PseudoStemSeparator] over a whole file (or the `--from`/`--to`
 * window, which is what the strategies actually separate) and writes `drums.wav`, `bass.wav`, `vocals.wav` and
 * `other.wav`. Listening to the four is the only honest way to judge how much a stem strategy will leak.
 */
class StemsCommand : MuiscCommand("stems") {

    override fun help(context: Context) = "Separate a file into four pseudo-stems."

    private val file by argument("FILE").file()
    private val out by option("-o", "--out", metavar = "DIR").file(canBeFile = false).required()
    private val from by option("--from", metavar = "SEC", help = "Start of the window to separate.").double()
    private val to by option("--to", metavar = "SEC", help = "End of the window to separate.").double()

    override fun execute(ctx: CliContext) {
        out.mkdirs()
        if (!out.isDirectory) throw CliktError("cannot create output directory ${out.path}")
        val ref = ctx.trackRef(file)
        val whole: AudioBuffer = ctx.decoder.open(ref.source).use { ctx.analysisService.decodeAtEngineFormat(it) }
        val startSec = from ?: 0.0
        val endSec = to ?: whole.durationSec
        if (endSec <= startSec) throw CliktError("--to (${Fmt.sec(endSec)}) must be after --from (${Fmt.sec(startSec)})")
        val start = (startSec * whole.sampleRate).toInt().coerceIn(0, whole.frames)
        val end = Math.round(endSec * whole.sampleRate).toInt().coerceIn(start, whole.frames)
        if (end - start < MIN_FRAMES) throw CliktError("the window is only ${end - start} frames; separation needs at least $MIN_FRAMES")
        val audio = if (start == 0 && end == whole.frames) whole else whole.slice(start, end)

        val stems = ctx.separator.separate(audio)
        val written = listOf(
            "drums" to stems.drums, "bass" to stems.bass, "vocals" to stems.vocals, "other" to stems.other,
        ).map { (name, buf) ->
            val f = File(out, "$name.wav")
            RenderSupport.writeWav(f, buf)
            listOf(name, f.absolutePath, Fmt.sec(buf.durationSec), "peak ${Fmt.num(buf.peak().toDouble(), 3)}", "rms ${Fmt.num(buf.rms().toDouble(), 4)}")
        }
        echo("source:  ${ref.source.value}")
        echo("window:  ${Fmt.sec(start.toDouble() / whole.sampleRate)} .. ${Fmt.sec(end.toDouble() / whole.sampleRate)} (${audio.frames} frames)")
        echo("quality: ${stems.quality}")
        echo(Fmt.table(listOf(listOf("stem", "file", "length", "peak", "rms")) + written, "  "))
        val sum = stems.mix(*StemKind.entries.toTypedArray())
        echo("sum of stems: peak ${Fmt.num(sum.peak().toDouble(), 3)} (the separation is energy-preserving, not perfect)")
        echoElapsed("separated ${audio.frames} frames")
    }

    private companion object {
        const val MIN_FRAMES = 4096
    }
}
