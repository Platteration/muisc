package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.loudness.TruePeakLimiter
import dev.muisc.metrics.ArtifactMetrics
import dev.muisc.player.ProgramPlayer
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.live.DefaultLivePlanFactory
import dev.muisc.transitions.live.LiveNode
import dev.muisc.transitions.live.LiveOffline
import dev.muisc.transitions.live.LivePlan
import dev.muisc.transitions.live.LivePlanBuilders
import kotlin.math.abs
import kotlin.math.ceil

/**
 * `muisc live` — audition the live ladder: the moves the player performs on the audio thread when no render is
 * ready (a skip, a missed deadline, a queue edit). [DefaultLivePlanFactory] picks the rung it would pick at
 * `--now`; `--kind` forces one so you can hear all five on the same pair. Rendered offline by [LiveOffline], which
 * is the same node graph the real-time `LiveExecutor` runs.
 */
class LiveCommand : MuiscCommand("live") {

    override fun help(context: Context) = "Build and render a live transition plan (the real-time fallback moves)."

    private val a by argument("A").file()
    private val b by argument("B").file()
    private val out by option("-o", "--out", metavar = "OUT.WAV").file().required()
    private val kind by option("--kind", help = "Force a live plan kind instead of letting the ladder choose.")
        .choice(
            LivePlanBuilders.KIND_CROSSFADE, LivePlanBuilders.KIND_BASS_SWAP, LivePlanBuilders.KIND_FILTER_SWEEP,
            LivePlanBuilders.KIND_ECHO_OUT, LivePlanBuilders.KIND_PHRASE_CUT,
        )
    private val now by option("--now", metavar = "SEC", help = "Where in A the user pressed skip (default: 8 s before its trim end).").double()
    private val fadeSec by option("--fade", metavar = "SEC", help = "Crossfade length override.").double()
    private val noLimiter by option("--no-limiter", help = "Write the raw deck sum instead of limiting it the way the player does.").flag()

    override fun execute(ctx: CliContext) {
        val aRef = ctx.trackRef(a)
        val bRef = ctx.trackRef(b)
        val features = ctx.features(aRef, bRef)
        val nowFrame = nowFrame(aRef.analysis)
        val factory = DefaultLivePlanFactory()
        val chosen = factory.plan(aRef, bRef, features, nowFrame, ctx.prefs, fadeSec)
        val plan = if (kind == null || kind == chosen.kind) chosen else force(kind!!, aRef, bRef, nowFrame, ctx)

        val problems = LivePlanBuilders.problems(plan)
        if (problems.isNotEmpty()) throw CliktError("the live plan is malformed: ${problems.joinToString("; ")}")

        val aAudio = load(ctx, aRef, plan.aFromFrame, plan.aToFrame)
        val bAudio = load(ctx, bRef, plan.bFromFrame, plan.bExitFrame())
        val audio = LiveOffline.render(plan, aAudio, bAudio, ctx.sampleRate)
        // LiveOffline sums two decks at unity; on the real path ProgramPlayer's output limiter catches the overshoot,
        // so the audition gets the same treatment unless the user asks for the raw sum.
        val reduction = if (noLimiter) 0.0 else TruePeakLimiter.processInPlace(audio, ProgramPlayer.CEILING_DBTP)
        RenderSupport.writeWav(out, audio)
        val metrics = ArtifactMetrics.evaluateProgramOutput(audio, listOf(0L, audio.frames.toLong()))

        echo("kind:     ${plan.kind}${if (kind != null && kind != chosen.kind) " (forced; the ladder chose ${chosen.kind})" else ""}")
        echo("now:      ${Fmt.sec(nowFrame.toDouble() / ctx.sampleRate)} in ${aRef.title}")
        echo("A:        frames ${plan.aFromFrame}..${plan.aToFrame} (${Fmt.sec((plan.aToFrame - plan.aFromFrame).toDouble() / ctx.sampleRate)})")
        echo("B:        from frame ${plan.bFromFrame}, resumes at ${plan.bExitFrame()} (consumes ${plan.framesConsumedFromB()} frames)")
        echo("segment:  ${plan.outputFrames} frames (${Fmt.sec(plan.outputFrames.toDouble() / ctx.sampleRate)})")
        echo("nodes (${plan.nodes.size}):")
        echo(Fmt.table(plan.nodes.map { node(it, ctx.sampleRate) }, "  "))
        val ceiling = Fmt.num(ProgramPlayer.CEILING_DBTP, 1)
        echo(
            if (noLimiter) "limiter: off — the raw deck sum, which can exceed 0 dBFS (the player limits it at $ceiling dBTP)"
            else "limiter: $ceiling dBTP ceiling as the player applies it, ${Fmt.num(reduction, 2)} dB of gain reduction",
        )
        echo(Fmt.metrics(metrics, "  "))
        echo("wav:      ${out.absolutePath}")
        echoElapsed("rendered a live ${plan.kind}")
    }

    private fun node(n: LiveNode, sr: Int): List<String> = when (n) {
        is LiveNode.Gain -> listOf("Gain", "deck ${n.deck}", "${n.points.size} points", n.points.joinToString(" → ") { "${Fmt.sec(it.frame.toDouble() / sr)}=${Fmt.num(it.value.toDouble(), 2)}" })
        is LiveNode.Rate -> listOf("Rate", "ratio ${Fmt.num(n.ratio, 5)}", "settles at ${Fmt.sec(n.settleFrame.toDouble() / sr)}", "")
        is LiveNode.LowSwap -> listOf("LowSwap", "at ${Fmt.sec(n.atFrame.toDouble() / sr)}", "over ${Fmt.sec(n.swapFrames.toDouble() / sr)}", "split ${Fmt.num(n.splitHz, 0)} Hz")
        is LiveNode.Sweep -> listOf("Sweep", "deck ${n.deck}", if (n.highPass) "high-pass" else "low-pass", "${Fmt.num(n.fromHz, 0)} → ${Fmt.num(n.toHz, 0)} Hz, Q ${Fmt.num(n.q, 2)}")
        is LiveNode.Echo -> listOf("Echo", "cut at ${Fmt.sec(n.cutFrame.toDouble() / sr)}", "delay ${Fmt.sec(n.delayFrames.toDouble() / sr)}", "feedback ${Fmt.num(n.feedback.toDouble(), 2)}, damp ${Fmt.num(n.dampHz, 0)} Hz")
    }

    private fun nowFrame(a: TrackAnalysis): Long {
        val sr = a.sampleRate
        val explicit = now?.let { Math.round(it * sr) }
        val fallback = (a.trimEndFrame - DEFAULT_NOW_BEFORE_END_SEC * sr).toLong()
        return (explicit ?: fallback).coerceIn(0L, (a.totalFrames - 1).coerceAtLeast(0L))
    }

    /** The same builders [DefaultLivePlanFactory] uses, aimed at A's next downbeat, so `--kind` always produces a plan. */
    private fun force(kind: String, a: TrackRef, b: TrackRef, nowFrame: Long, ctx: CliContext): LivePlan {
        val an = a.analysis
        val sr = ctx.sampleRate
        val grid = an.grid
        val total = an.totalFrames
        val bFrom = bStart(b.analysis)
        val fade = Math.round((fadeSec ?: DEFAULT_FADE_SEC) * sr).toInt().coerceAtLeast(2)
        if (grid.isEmpty || grid.bpm <= 0.0) {
            if (kind != LivePlanBuilders.KIND_CROSSFADE) {
                throw CliktError("'$kind' needs a beat grid and ${a.title} has none; only 'crossfade' can be forced here")
            }
            return LivePlanBuilders.crossfade(nowFrame, total, bFrom, fade)
        }
        val beatFrames = grid.periodFrames(sr)
        val barFrames = (beatFrames * grid.beatsPerBar)
        val db = grid.nextDownbeat(grid.beatAtFrame(nowFrame))
        val dbFrame = grid.frameOfBeat(db.toDouble()).coerceIn(0L, (total - 1).coerceAtLeast(0L))
        val roomBars = ((an.trimEndFrame - dbFrame) / barFrames).toInt().coerceAtLeast(0)
        val bars = roomBars.coerceIn(1, DEFAULT_BARS)
        val out = Math.round(bars * barFrames).toInt().coerceAtLeast(2)
        return when (kind) {
            LivePlanBuilders.KIND_CROSSFADE -> LivePlanBuilders.crossfade(nowFrame, total, bFrom, fade)
            LivePlanBuilders.KIND_PHRASE_CUT -> LivePlanBuilders.phraseCut(dbFrame, total, bFrom, Math.round(CUT_MS / 1000.0 * sr).toInt().coerceAtLeast(2))
            LivePlanBuilders.KIND_FILTER_SWEEP -> LivePlanBuilders.filterSweep(dbFrame, total, bFrom, out, sr)
            LivePlanBuilders.KIND_BASS_SWAP -> {
                val features = ctx.features(a, b)
                val ratio = if (features.tempoRatio > 0) 1.0 / features.tempoRatio else 1.0
                if (abs(ratio - 1.0) > MAX_LIVE_RATE + 1e-9) {
                    throw CliktError(
                        "a live bass swap needs the tempi within ${Fmt.pct(MAX_LIVE_RATE)} (the live resampler's range), " +
                            "but ${a.title} and ${b.title} are ${Fmt.pct(abs(features.tempoRatio - 1.0))} apart — " +
                            "render one instead (`muisc render --strategy bassSwap`), or force --kind filterSweep.",
                    )
                }
                LivePlanBuilders.bassSwap(
                    dbFrame, total, bFrom, out,
                    swapAtFrame = out / 2, swapFrames = Math.round(beatFrames).toInt().coerceAtLeast(1),
                    ratio = ratio, settleFrame = (out - Math.round(barFrames).toInt()).coerceIn(0, out),
                )
            }
            LivePlanBuilders.KIND_ECHO_OUT -> {
                val cut = Math.round(barFrames).toInt().coerceAtLeast(2)
                val delay = Math.round(ECHO_DELAY_BEATS * beatFrames).toInt().coerceAtLeast(1)
                val decay = LivePlanBuilders.echoTailFrames(delay, ECHO_FEEDBACK)
                val tailBars = ceil(decay.toDouble() / cut).toInt().coerceIn(1, 4)
                LivePlanBuilders.echoOut(dbFrame, total, bFrom, cut, delay, tailBars * cut, ECHO_FEEDBACK, ECHO_DAMP_HZ)
            }
            else -> throw CliktError("unknown live kind '$kind'")
        }
    }

    private fun bStart(b: TrackAnalysis): Long {
        val cue = b.cues.mixInBeat
        val frame = if (cue >= 0 && !b.grid.isEmpty) b.grid.frameOfBeat(cue.toDouble()) else b.trimStartFrame
        return frame.coerceIn(0L, (b.totalFrames - 1).coerceAtLeast(0L))
    }

    private fun load(ctx: CliContext, track: TrackRef, from: Long, to: Long): AudioBuffer {
        val range = FrameRange(from.coerceAtLeast(0L), to.coerceAtLeast(from.coerceAtLeast(0L) + 1))
        return ctx.loader.load(track, range, ctx.prefs).also { DeckGain.applyInPlace(it, DeckGain.of(track.analysis, ctx.prefs)) }
    }

    private companion object {
        const val DEFAULT_NOW_BEFORE_END_SEC = 8.0
        const val DEFAULT_FADE_SEC = 1.5
        const val DEFAULT_BARS = 4
        const val CUT_MS = 16.0
        const val ECHO_DELAY_BEATS = 0.75
        const val ECHO_FEEDBACK = 0.6f
        const val ECHO_DAMP_HZ = 4000.0
        const val MAX_LIVE_RATE = 0.02
    }
}
