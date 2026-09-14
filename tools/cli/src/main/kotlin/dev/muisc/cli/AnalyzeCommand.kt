package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.transitions.TrackRef
import java.io.File

/**
 * `muisc analyze` — the whole [TrackAnalysis] of one or more files, through the cache, in a form you can read:
 * tempo and its confidence, the beat grid's kind and downbeat phase, key + Camelot, loudness, the silence trim,
 * the intro/outro classification, the cue points and the section map.
 *
 * `--click` writes the track with a click on every detected beat (accented on the downbeats): the fastest way to
 * tell whether the grid is right — you hear it drift within two bars.
 */
class AnalyzeCommand : MuiscCommand("analyze") {

    override fun help(context: Context) = "Analyse audio files and print tempo, key, loudness, structure and cues."

    private val files by argument("FILE", help = "Audio files (wav/aiff/mp3/flac/ogg).").file().multiple(required = true)
    private val json by option("--json", help = "Print the full TrackAnalysis as JSON.").flag()
    private val click by option("--click", metavar = "OUT.WAV", help = "Write the audio with a click track on the detected beats.").file()
    private val force by option("--force", help = "Re-analyse even when the cache has an entry.").flag()

    override fun execute(ctx: CliContext) {
        if (click != null && files.size != 1) throw CliktError("--click works on exactly one file (got ${files.size})")
        val refs = files.map { ctx.trackRef(it, force) }
        if (json) {
            echo(if (refs.size == 1) refs[0].analysis.toJson() else refs.joinToString(",\n", "[\n", "\n]") { it.analysis.toJson() })
        } else {
            refs.forEachIndexed { i, ref ->
                if (i > 0) echo("")
                echo(describe(ref, ctx))
            }
        }
        click?.let { writeClick(ctx, refs[0], it) }
        echoElapsed("analysed ${refs.size} file(s)")
    }

    private fun describe(ref: TrackRef, ctx: CliContext): String {
        val a = ref.analysis
        val sr = a.sampleRate
        val rows = ArrayList<List<String>>()
        fun row(k: String, v: String) { rows += listOf(k, v) }
        row("file", ref.source.value)
        row("duration", "${Fmt.sec(a.durationSec)} (${a.totalFrames} frames @ $sr Hz, ${ctx.channels} ch)")
        row("trim", "${Fmt.sec(a.trimStartFrame.toDouble() / sr)} .. ${Fmt.sec(a.trimEndFrame.toDouble() / sr)} (${Fmt.sec(a.trimmedDurationSec)} of music)")
        row("tempo", "${Fmt.num(a.tempo.bpm, 2)} BPM  confidence ${Fmt.num(a.tempo.confidence.toDouble(), 2)}" +
            if (a.tempo.alternates.isEmpty()) "" else "  alternates ${a.tempo.alternates.joinToString(", ") { "${Fmt.num(it.bpm, 1)}@${Fmt.num(it.score.toDouble(), 2)}" }}")
        row("grid", "${a.grid.kind}  ${Fmt.num(a.grid.bpm, 2)} BPM  ${a.grid.beatCount} beats  ${a.grid.beatsPerBar}/bar  confidence ${Fmt.num(a.grid.confidence.toDouble(), 2)}")
        row("downbeat", "phase ${a.grid.downbeatPhase}  first downbeat at ${Fmt.sec(a.grid.frameOfBeat(a.grid.downbeatPhase.toDouble()).toDouble() / sr)}  phrase ${a.grid.phraseBars} bars from beat ${a.grid.phraseStartBeat}")
        row("key", "${Fmt.key(a)}  strength ${Fmt.num(a.key.strength.toDouble(), 2)}" +
            (a.key.secondBest?.let { "  second ${it.shortName} (${it.camelot.code}) ${Fmt.num(a.key.secondStrength.toDouble(), 2)}" } ?: "") +
            "  tuning ${Fmt.num(a.tuningCents.toDouble(), 1)} cents")
        a.introKey?.let { row("intro key", "${it.key.shortName} (${it.camelot.code})") }
        a.outroKey?.let { row("outro key", "${it.key.shortName} (${it.camelot.code})") }
        row("loudness", "${Fmt.num(a.loudness.integratedLufs.toDouble(), 1)} LUFS  true peak ${Fmt.num(a.loudness.truePeakDbtp.toDouble(), 2)} dBTP  range ${Fmt.num(a.loudness.loudnessRangeLu.toDouble(), 1)} LU")
        row("edges", "intro ${a.intro}  outro ${a.outro}")
        row("cues", cues(a))
        row("brightness", "${Fmt.num(a.brightnessHz.toDouble(), 0)} Hz  onsets ${a.onsetFrames.size}  bars analysed ${a.bars.barCount}")
        row("analysis", "${a.analysisMillis} ms  version ${a.version}  fingerprint ${a.fingerprint.take(24)}…")
        val head = Fmt.table(rows)
        val sections = if (a.sections.isEmpty()) "  (no sections)" else Fmt.table(
            listOf(listOf("#", "label", "beats", "time", "energy", "drums", "vocals")) +
                a.sections.mapIndexed { i, s ->
                    listOf(
                        "$i", s.label.name, "${s.startBeat}..${s.endBeat}",
                        "${Fmt.sec(a.grid.frameOfBeat(s.startBeat.toDouble()).toDouble() / sr)} .. ${Fmt.sec(a.grid.frameOfBeat(s.endBeat.toDouble()).toDouble() / sr)}",
                        Fmt.num(s.energy.toDouble(), 2), Fmt.num(s.drums.toDouble(), 2), Fmt.num(s.vocals.toDouble(), 2),
                    )
                },
            "  ",
        )
        return "$head\nsections (${a.sections.size}):\n$sections"
    }

    private fun cues(a: TrackAnalysis): String {
        val sr = a.sampleRate
        fun at(beat: Int): String = if (beat < 0) "—" else "beat $beat (${Fmt.sec(a.grid.frameOfBeat(beat.toDouble()).toDouble() / sr)})"
        return "mixOut ${at(a.cues.mixOutBeat)}  mixIn ${at(a.cues.mixInBeat)}  firstDownbeat ${at(a.cues.firstDownbeat)}  lastDownbeat ${at(a.cues.lastDownbeat)}  drop ${at(a.cues.dropBeat)}"
    }

    /** The decoded track with a short accented burst on every grid beat — audition the grid by ear. */
    private fun writeClick(ctx: CliContext, ref: TrackRef, out: File) {
        val a = ref.analysis
        val audio: AudioBuffer = ctx.decoder.open(ref.source).use { ctx.analysisService.decodeAtEngineFormat(it) }
        val mixed = audio.copy().applyGainInPlace(CLICK_DUCK)
        for ((i, frame) in a.grid.beatFrames.withIndex()) {
            val start = frame.toInt()
            if (start < 0 || start >= mixed.frames) continue
            val down = a.grid.isDownbeat(i)
            for (c in 0 until mixed.channelCount) {
                Synth.addBurst(mixed.channels[c], mixed.sampleRate, start, if (down) DOWNBEAT_HZ else BEAT_HZ, CLICK_DECAY_SEC, if (down) DOWNBEAT_AMP else BEAT_AMP)
            }
        }
        RenderSupport.writeWav(out, mixed)
        echo("click track: ${out.path} (${a.grid.beatCount} beats, ${a.grid.downbeatFrames().size} accented downbeats)")
    }

    private companion object {
        const val CLICK_DUCK = 0.6f
        const val DOWNBEAT_HZ = 1600.0
        const val BEAT_HZ = 1000.0
        const val DOWNBEAT_AMP = 0.55f
        const val BEAT_AMP = 0.28f
        const val CLICK_DECAY_SEC = 0.03
    }
}
