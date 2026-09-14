package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.WavIo
import dev.muisc.dsp.loudness.LoudnessMeter
import dev.muisc.dsp.loudness.TruePeak
import dev.muisc.metrics.ArtifactMetrics
import dev.muisc.metrics.Verdict
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderReport
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `muisc check` — the metrics report for any WAV. On its own it runs everything that can be measured from the
 * signal alone (clicks, level jumps, true peak, clipping, DC, NaN/Inf, silence gaps, loudness smoothness, stereo
 * correlation). Given `--a`, `--b` and `--plan` it rebuilds the [TransitionInput] the render had and adds the seam
 * metrics — seam identity against the deck-gained sources, bass cancellation, beat alignment and tail containment.
 *
 * Exit code is non-zero when any metric FAILs, so it drops straight into a script.
 */
class CheckCommand : MuiscCommand("check") {

    override fun help(context: Context) = "Run the artifact metrics on a WAV file."

    private val wav by argument("WAV", help = "The rendered WAV to check.").file(mustExist = true, canBeDir = false)
    private val aFile by option("--a", metavar = "FILE", help = "Source A (enables the seam metrics).").file(mustExist = true, canBeDir = false)
    private val bFile by option("--b", metavar = "FILE", help = "Source B (enables the seam metrics).").file(mustExist = true, canBeDir = false)
    private val planFile by option("--plan", metavar = "PLAN.JSON", help = "The plan written by `render`.").file(mustExist = true, canBeDir = false)
    private val json by option("--json", help = "Print the report as JSON.").flag()
    private val warnFails by option("--fail-on-warn", help = "Exit non-zero on WARN too.").flag()

    override fun execute(ctx: CliContext) {
        val audio = readWav(wav)
        val plan = planFile?.let { loadPlan(it) } ?: bare(audio)
        val rendered = RenderedTransition(plan, audio, emptyList(), report(audio))

        val input: TransitionInput? = if (aFile != null && bFile != null) {
            if (planFile == null) throw CliktError("--a/--b need --plan: the seam metrics only mean something against the plan's windows")
            val aRef = ctx.trackRef(aFile!!)
            val bRef = ctx.trackRef(bFile!!)
            RenderSupport.input(ctx, aRef, bRef, plan, ctx.features(aRef, bRef))
        } else {
            if (aFile != null || bFile != null) throw CliktError("--a and --b must be given together")
            null
        }

        val metrics = ArtifactMetrics.evaluate(rendered, input)
        if (json) {
            echo(PRETTY.parseToJsonElement(metrics.toJson()).toString())
        } else {
            echo("file:     ${wav.path}")
            echo("audio:    ${audio.frames} frames (${Fmt.sec(audio.durationSec)}), ${audio.channelCount} ch @ ${audio.sampleRate} Hz")
            echo("plan:     ${if (planFile != null) "${plan.strategyId} (${planFile!!.path})" else "none (signal-only metrics)"}")
            echo("sources:  ${if (input != null) "A=${aFile!!.name} B=${bFile!!.name} — seam metrics enabled" else "none — seam metrics skipped"}")
            echo(Fmt.metrics(metrics, "  "))
        }
        echoElapsed("checked ${metrics.metrics.size} metric(s): ${Fmt.verdictMark(metrics.worst)}")
        val bad = metrics.worst == Verdict.FAIL || (warnFails && metrics.worst == Verdict.WARN)
        if (bad) throw CliktError("metrics verdict is ${metrics.worst}: ${metrics.metrics.filter { it.verdict != Verdict.PASS }.joinToString(", ") { "${it.id}=${Fmt.num(it.value, 3)}" }}")
    }

    private fun readWav(f: File): AudioBuffer = try {
        WavIo.read(f)
    } catch (e: Exception) {
        throw CliktError("cannot read ${f.path} as a WAV file: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun loadPlan(f: File): TransitionPlan = try {
        PLAN_JSON.decodeFromString(TransitionPlan.serializer(), f.readText())
    } catch (e: Exception) {
        throw CliktError("cannot parse the plan ${f.path}: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * A stand-in plan for a WAV that has none: the file itself is the segment. The windows span it so the plan's
     * invariants hold; with no sources the seam metrics are skipped anyway, and `expectedOutputFrames` is the file
     * length, so the length and tail-containment checks are trivially satisfied.
     */
    private fun bare(audio: AudioBuffer): TransitionPlan {
        val n = maxOf(1L, audio.frames.toLong())
        return TransitionPlan(
            strategyId = "unknown",
            params = Params.EMPTY,
            aExitFrame = 0L,
            bEntryFrame = n,
            aWindow = FrameRange(0L, n),
            bWindow = FrameRange(0L, n),
            expectedOutputFrames = audio.frames,
        )
    }

    private fun report(audio: AudioBuffer) = RenderReport(
        renderMillis = 0L,
        peak = audio.peak(),
        truePeakDbtp = if (audio.frames == 0) Float.NEGATIVE_INFINITY else TruePeak.measureDbtp(audio).toFloat(),
        integratedLufs = if (audio.frames == 0) Float.NEGATIVE_INFINITY else LoudnessMeter.integratedLufs(audio).toFloat(),
    )

    private companion object {
        val PLAN_JSON = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true; isLenient = true }
        val PRETTY = Json { prettyPrint = true; allowSpecialFloatingPointValues = true }
    }
}
