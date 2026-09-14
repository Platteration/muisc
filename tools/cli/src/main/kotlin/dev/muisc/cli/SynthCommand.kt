package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.SyntheticSong
import java.io.File

/**
 * `muisc synth` — the quickest way to get test material: writes a [SyntheticSong] (kick/hat/bass/chords with a
 * known tempo, key and intro/outro shape) to a WAV whose name encodes every parameter, so a directory of them is
 * self-documenting and `analyze` can be checked against the ground truth in the file name.
 *
 * `--set` writes the four-song fixture set the engine's `StrategyContractTest` uses (`t120C`, `t126Am`, `t63G`,
 * `t140Fs`), which is also what `goldens` renders — the standard corpus for `mix`, `ab` and `score`.
 */
class SynthCommand : MuiscCommand("synth") {

    override fun help(context: Context) = "Write synthetic test songs as WAV files."

    private val out by option("-o", "--out", metavar = "DIR", help = "Output directory.").file(canBeFile = false).required()
    private val bpm by option("--bpm", metavar = "BPM").double().default(120.0)
    private val key by option("--key", metavar = "KEY", help = "Tonic and mode, e.g. C, Am, F#m, Bb.").default("C")
    private val bars by option("--bars", metavar = "N").int().default(32)
    private val intro by option("--intro", metavar = "BARS", help = "Intro length in bars.").int().default(4)
    private val outro by option("--outro", metavar = "BARS", help = "Outro length in bars.").int().default(4)
    private val fade by option("--fade", help = "Fade the outro out instead of a beat outro.").flag()
    private val mono by option("--mono", help = "Write one channel instead of two.").flag()
    private val leadSilence by option("--lead-silence", metavar = "SEC").double().default(0.0)
    private val trailSilence by option("--trail-silence", metavar = "SEC").double().default(0.0)
    private val set by option("--set", help = "Write the four-song contract fixture set instead of a single song.").flag()

    override fun execute(ctx: CliContext) {
        out.mkdirs()
        if (!out.isDirectory) throw CliktError("cannot create output directory ${out.path}")
        val rate = ctx.sampleRate
        val written = if (set) writeSet(rate) else listOf(writeOne(rate))
        for ((file, song) in written) {
            echo("${file.path}  ${Fmt.num(song.bpm, 1)} BPM  ${keyName(song)}  ${song.bars} bars (intro ${song.introBars}, outro ${song.outroBars}${if (song.outroFade) ", fade" else ""})  ${Fmt.sec(song.durationSec)}")
        }
        echoElapsed("wrote ${written.size} file(s)")
    }

    private fun writeOne(rate: Int): Pair<File, SyntheticSong> {
        val (tonic, mode) = parseKey(key)
        if (bars <= 0) throw CliktError("--bars must be positive")
        if (intro < 0 || outro < 0 || intro + outro > bars) throw CliktError("--intro/--outro must fit inside --bars ($bars)")
        val song = SyntheticSong(
            bpm = bpm, tonic = tonic, mode = mode, bars = bars, introBars = intro, outroBars = outro,
            outroFade = fade, sampleRate = rate, stereo = !mono, seed = seed.toInt(),
            leadingSilenceSec = leadSilence, trailingSilenceSec = trailSilence,
        )
        return write(File(out, name(song)), song)
    }

    private fun writeSet(rate: Int): List<Pair<File, SyntheticSong>> = FIXTURES.map { (id, make) ->
        write(File(out, "$id.wav"), make(rate, seed.toInt()))
    }

    private fun write(file: File, song: SyntheticSong): Pair<File, SyntheticSong> {
        if (song.durationSec > MAX_SECONDS) throw CliktError("that song would be ${Fmt.sec(song.durationSec)} long; the limit is ${Fmt.sec(MAX_SECONDS)}")
        RenderSupport.writeWav(file, song.render())
        return file to song
    }

    private fun name(song: SyntheticSong): String = buildString {
        append('t').append(trim(song.bpm))
        append('_').append(keyName(song))
        append('_').append(song.bars).append("bars")
        append("_i").append(song.introBars).append('o').append(song.outroBars)
        if (song.outroFade) append("f")
        append("_s").append(song.seed)
        append('_').append(song.sampleRate)
        append(".wav")
    }

    private fun trim(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else Fmt.num(v, 1).replace('.', 'p')

    private fun keyName(song: SyntheticSong): String = NOTES[song.tonic] + if (song.mode == Mode.MINOR) "m" else ""

    companion object {
        const val MAX_SECONDS = 1800.0
        val NOTES = listOf("C", "Cs", "D", "Ds", "E", "F", "Fs", "G", "Gs", "A", "As", "B")

        /** The four songs of `StrategyContractTest` — the CLI's standard corpus. */
        val FIXTURES: List<Pair<String, (Int, Int) -> SyntheticSong>> = listOf(
            "t120C" to { r: Int, s: Int -> SyntheticSong(bpm = 120.0, tonic = 0, mode = Mode.MAJOR, bars = 16, introBars = 4, outroBars = 4, sampleRate = r, seed = s) },
            "t126Am" to { r: Int, s: Int -> SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 16, introBars = 4, outroBars = 4, sampleRate = r, seed = s) },
            "t63G" to { r: Int, s: Int -> SyntheticSong(bpm = 63.0, tonic = 7, mode = Mode.MAJOR, bars = 16, introBars = 4, outroBars = 4, outroFade = true, sampleRate = r, seed = s) },
            "t140Fs" to { r: Int, s: Int -> SyntheticSong(bpm = 140.0, tonic = 6, mode = Mode.MAJOR, bars = 16, introBars = 0, outroBars = 4, sampleRate = r, seed = s) },
        )

        private val TONICS = mapOf(
            "c" to 0, "c#" to 1, "cs" to 1, "db" to 1, "d" to 2, "d#" to 3, "ds" to 3, "eb" to 3, "e" to 4,
            "f" to 5, "f#" to 6, "fs" to 6, "gb" to 6, "g" to 7, "g#" to 8, "gs" to 8, "ab" to 8, "a" to 9,
            "a#" to 10, "as" to 10, "bb" to 10, "b" to 11,
        )

        /** `Am` / `F#m` / `Bb` / `C major` → (pitch class, mode). */
        fun parseKey(spec: String): Pair<Int, Mode> {
            val s = spec.trim()
            if (s.isEmpty()) throw CliktError("--key must not be empty")
            val lower = s.lowercase()
            val minor = lower.endsWith("m") && !lower.endsWith("maj") || lower.endsWith("min") || lower.endsWith("minor")
            val root = lower.removeSuffix("minor").removeSuffix("min").removeSuffix("major").removeSuffix("maj").trim().let {
                if (minor && it.endsWith("m")) it.dropLast(1) else it
            }.trim()
            val tonic = TONICS[root] ?: throw CliktError("cannot parse key '$spec' (expected e.g. C, Am, F#m, Bb)")
            return tonic to if (minor) Mode.MINOR else Mode.MAJOR
        }
    }
}
