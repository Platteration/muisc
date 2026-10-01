package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.metrics.ArtifactMetrics
import dev.muisc.player.ProgramRenderer
import dev.muisc.transitions.DefaultProgramBuilder
import dev.muisc.transitions.PlaybackContext
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.Segment
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionGating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `muisc mix` — "listen to my playlist as the DJ would play it": analyses every file, plans each consecutive pair
 * (feeding the previous choice in as `previousStrategyId`, so the variety penalty makes the set varied the way it
 * does on the phone), renders each transition, builds one [dev.muisc.transitions.PlaybackProgram] with the gating
 * rule of the chosen [PlaybackContext] and runs it through the real [dev.muisc.player.ProgramPlayer]. Each pair plays
 * the best-ranked candidate that leaves both songs their minimum body ([DefaultProgramBuilder.roomOrder]), so a
 * short song keeps its transitions; a pinned strategy (`muisc pin`, `--preset`) keeps first place unless it would
 * leave a song less than its minimum body. A pair where no candidate leaves both songs their body is played body
 * to body and reported as dropped, and the next pair is planned as if it had no previous transition.
 *
 * With `--order smart` the files are first reordered by the engine's smart shuffle ([SmartOrder.order], the same
 * sequencer as `muisc order`, seeded by `--seed`); it is refused for `--context album`, which always plays in order.
 *
 * The result is one WAV of the whole mini set and — with `--report` — a JSON listing every transition (strategy,
 * score, reasons, metrics) and the seam time in the output where you can hear it.
 */
class MixCommand : MuiscCommand("mix") {

    override fun help(context: Context) = "Render a whole mini DJ set from a list of files."

    private val files by argument("FILE", help = "Tracks, in play order.").file().multiple(required = true)
    private val out by option("-o", "--out", metavar = "SET.WAV", help = "Output WAV of the whole set.").file().required()
    private val playbackContext by option("--context", help = "Playback context; decides the gating rule.")
        .choice("playlist" to PlaybackContext.PLAYLIST, "shuffle" to PlaybackContext.SHUFFLE, "album" to PlaybackContext.ALBUM, "queue" to PlaybackContext.QUEUE, "single" to PlaybackContext.SINGLE)
        .default(PlaybackContext.PLAYLIST)
    private val report by option("--report", metavar = "SET.JSON", help = "Write a JSON report of every transition and seam.").file()
    private val noLimiter by option("--no-limiter", help = "Disable the player's true-peak limiter.").flag()
    private val orderMode by option("--order", help = "given = play the files as listed (default); smart = reorder them so every pair mixes well (see `muisc order`).")
        .choice("given", "smart").default("given")

    override fun execute(ctx: CliContext) {
        if (files.size < 2) throw CliktError("mix needs at least two files (got ${files.size})")
        if (orderMode == "smart" && playbackContext == PlaybackContext.ALBUM) {
            throw CliktError("--order smart reorders the tracks, but an album is played in its own order (use --context shuffle or playlist)")
        }
        val given = files.map { ctx.trackRef(it) }
        val tracks = if (orderMode == "smart") SmartOrder.order(ctx, given, seed).map { given[it] } else given
        echo("queue (${tracks.size} tracks, context $playbackContext" + (if (orderMode == "smart") ", smart order" else "") + "):")
        echo(Fmt.table(listOf(listOf("#", "track", "bpm", "key", "lufs", "length")) + tracks.mapIndexed { i, t ->
            listOf("${i + 1}", t.title, Fmt.num(t.analysis.tempo.bpm, 1), Fmt.key(t.analysis), Fmt.num(t.analysis.loudness.integratedLufs.toDouble(), 1), Fmt.sec(t.analysis.durationSec))
        }, "  "))
        echo("")

        val builder = DefaultProgramBuilder()
        fun enabled(i: Int) = i + 1 < tracks.size && TransitionGating.transitionsEnabled(playbackContext, tracks[i], tracks[i + 1], ctx.prefs)
        val transitions = ArrayList<Leg>()
        var previous: String? = null
        var aEntry: Long? = null // where the transition into `a` hands over (null: none)
        var lookahead: Pair<String?, RankedPlans>? = null // the next pair's ranking and the previous strategy it assumed
        for (i in 0 until tracks.size - 1) {
            val a = tracks[i]
            val b = tracks[i + 1]
            if (!enabled(i)) {
                transitions += Leg(a, b, null, null, "gated: $playbackContext" + if (a.albumId != null && a.albumId == b.albumId) " (same album '${a.albumId}')" else "")
                echo("${i + 1}. ${a.title} → ${b.title}: gapless (gating: $playbackContext)")
                aEntry = null; lookahead = null
                continue
            }
            val features = ctx.features(a, b)
            val ranked = lookahead?.takeIf { it.first == previous }?.second ?: ctx.planner.plan(a, b, ctx.prefs, seed, previous)
            // Room: the program builder guarantees every track a minimum body by dropping renders, so pick the best
            // candidate that leaves both tracks one (with some plan of B's own next transition). The plans do not
            // depend on the previous strategy, only their scores do, so the next pair is ranked once here assuming
            // this pair's best and reused when that is what gets played.
            lookahead = if (enabled(i + 1)) ranked.best.strategy.id to ctx.planner.plan(b, tracks[i + 2], ctx.prefs, seed, ranked.best.strategy.id) else null
            val next = lookahead?.second?.candidates?.map { it.plan }
            val ordered = builder.roomOrder(ranked.candidates, a, b, playbackContext, ctx.prefs, aEntry, next)
            val candidate = ordered.firstOrNull() ?: throw CliktError("no candidate for ${a.title} → ${b.title}")
            if (candidate !== ranked.best) {
                val why = builder.room(ranked.best.plan, a, b, playbackContext, ctx.prefs, aEntry, next)
                val pin = if (ranked.best.pinned) " (pinned)" else ""
                echo("${i + 1}. room: ${ranked.best.strategy.id}$pin ${roomText(why, a, b)}; playing #${ranked.candidates.indexOf(candidate) + 1} ${candidate.strategy.id} instead")
            }
            // No candidate fits: this one starves a track, so the builder would drop it — and when it starves B, it
            // would first drop B's next transition, which may well fit B entered from its own start. So it is
            // rendered for the report but not handed to the builder, and the next pair plans without it.
            val room = builder.room(candidate.plan, a, b, playbackContext, ctx.prefs, aEntry, next)
            val starves = room == DefaultProgramBuilder.Room.STARVES_A || room == DefaultProgramBuilder.Room.STARVES_B
            val result = RenderSupport.renderWithMetrics(ctx, a, b, candidate, features, seed)
            if (!starves) previous = result.strategyId // a leg that is not played is not the previous transition
            aEntry = if (starves) null else result.plan.bEntryFrame
            transitions += Leg(a, b, result, candidate.score, null, played = !starves)
            echo("${i + 1}. ${a.title} → ${b.title}: ${result.strategyId} score ${Fmt.num(candidate.score, 3)}  " +
                "${Fmt.num(Fmt.bars(result.plan.expectedOutputFrames, a.analysis.grid.bpm, ctx.sampleRate), 1)} bars  " +
                "${Fmt.sec(result.rendered.audio.durationSec)}  metrics ${Fmt.verdictMark(result.metrics.worst)}")
            val problems = result.metrics.metrics.filter { it.verdict != dev.muisc.metrics.Verdict.PASS }
            if (problems.isNotEmpty()) echo("     ${problems.joinToString(", ") { "${it.verdict} ${it.id}=${Fmt.num(it.value, 3)}" }}")
            for (r in candidate.applicability.reasons.take(3)) echo("     · $r")
        }

        val renders: Map<String, RenderedTransition> = transitions.filter { it.played }.mapNotNull { leg -> leg.result?.let { key(leg.a, leg.b) to it.rendered } }.toMap()
        val program = builder.build(tracks, playbackContext, ctx.prefs) { x, y -> renders[key(x, y)] }
        val played = program.segments.filterIsInstance<Segment.Rendered>().map { it.rendered }.toSet()
        for ((i, leg) in transitions.withIndex()) {
            val r = leg.result ?: continue
            if (r.rendered !in played) echo("${i + 1}. ${leg.a.title} → ${leg.b.title}: ${r.strategyId} dropped — no candidate left both songs their minimum body; played body to body")
        }
        val sharedGain = if (playbackContext == PlaybackContext.ALBUM) ProgramRenderer.albumGainDb(tracks, ctx.prefs) else null
        val audio = RenderSupport.renderProgram(ctx, program, out, limiter = !noLimiter, sharedGainDb = sharedGain)
        val seams = RenderSupport.seamFrames(program)
        val programMetrics = ArtifactMetrics.evaluateProgramOutput(audio, seams)

        echo("")
        echo("program: ${program.segments.size} segments, ${Fmt.sec(audio.durationSec)}, ${seams.size} seams")
        val starts = program.segments.runningFold(0L) { at, s -> at + lengthOf(s) }
        echo(Fmt.table(listOf(listOf("#", "segment", "start", "length", "what")) + program.segments.mapIndexed { i, s ->
            listOf("${i + 1}", kind(s), Fmt.sec(starts[i].toDouble() / ctx.sampleRate), Fmt.sec(lengthOf(s).toDouble() / ctx.sampleRate), label(s))
        }, "  "))
        echo(Fmt.metrics(programMetrics, "  "))
        echo("")
        echo("set:    ${out.absolutePath}")

        report?.let { f ->
            f.absoluteFile.parentFile?.mkdirs()
            f.writeText(PRETTY.encodeToString(JsonObject.serializer(), reportJson(tracks, transitions, program.segments, seams, ctx, audio.durationSec)))
            echo("report: ${f.absolutePath}")
        }
        echoElapsed("mixed ${tracks.size} tracks (${transitions.count { it.result != null }} transitions)")
    }

    private fun key(a: TrackRef, b: TrackRef) = "${a.id}\u0000${b.id}"

    private fun roomText(room: DefaultProgramBuilder.Room, a: TrackRef, b: TrackRef): String = when (room) {
        DefaultProgramBuilder.Room.FITS -> "fits"
        DefaultProgramBuilder.Room.COSTS_NEXT -> "would leave ${b.title} no room for its next transition"
        DefaultProgramBuilder.Room.STARVES_B -> "would leave ${b.title} less than its minimum body"
        DefaultProgramBuilder.Room.STARVES_A -> "would leave ${a.title} less than its minimum body"
    }

    private fun kind(s: Segment): String = when (s) {
        is Segment.Body -> "body"
        is Segment.Rendered -> "rendered"
        is Segment.LiveCrossfade -> "liveCrossfade"
        is Segment.Live -> "live"
    }

    private fun label(s: Segment): String = when (s) {
        is Segment.Body -> "${s.track.title} [${s.fromFrame}..${s.toFrame})"
        is Segment.Rendered -> "${s.rendered.plan.strategyId}: ${s.from.title} → ${s.to.title}"
        is Segment.LiveCrossfade -> "${s.from.title} → ${s.to.title}"
        is Segment.Live -> "${s.plan.kind}: ${s.from.title} → ${s.to.title}"
    }

    private fun lengthOf(s: Segment): Long = when (s) {
        is Segment.Body -> s.toFrame - s.fromFrame
        is Segment.Rendered -> s.rendered.audio.frames.toLong()
        is Segment.LiveCrossfade -> s.aToFrame - s.aFromFrame
        is Segment.Live -> s.plan.outputFrames.toLong()
    }

    private fun reportJson(
        tracks: List<TrackRef>,
        legs: List<Leg>,
        segments: List<Segment>,
        seams: List<Long>,
        ctx: CliContext,
        durationSec: Double,
    ): JsonObject = buildJsonObject {
        put("out", out.absolutePath)
        put("context", playbackContext.name)
        put("order", orderMode)
        put("seed", seed)
        put("sampleRate", ctx.sampleRate)
        put("durationSec", durationSec)
        put("tracks", buildJsonArray {
            for (t in tracks) add(buildJsonObject {
                put("id", t.id); put("title", t.title); put("bpm", t.analysis.tempo.bpm)
                put("key", t.analysis.key.key.shortName); put("camelot", t.analysis.key.camelot.code)
                put("lufs", t.analysis.loudness.integratedLufs.toDouble()); put("durationSec", t.analysis.durationSec)
            })
        })
        put("transitions", buildJsonArray {
            for (leg in legs) add(buildJsonObject {
                put("from", leg.a.title); put("to", leg.b.title)
                leg.gatedReason?.let { put("gated", it) }
                leg.result?.let { r ->
                    put("strategy", r.strategyId)
                    put("score", leg.score ?: 0.0)
                    put("bars", Fmt.bars(r.plan.expectedOutputFrames, leg.a.analysis.grid.bpm, ctx.sampleRate))
                    put("frames", r.plan.expectedOutputFrames)
                    put("renderKey", r.rendered.report.renderKey)
                    putJsonArray("reasons") { for (x in r.candidate.applicability.reasons) add(x) }
                    putJsonArray("modifiers") { for (x in r.plan.modifiers) add(x) }
                    put("metricsVerdict", r.metrics.worst.name)
                    put("metrics", PRETTY.parseToJsonElement(r.metrics.toJson()))
                }
            })
        })
        put("segments", buildJsonArray {
            var at = 0L
            for (s in segments) {
                add(buildJsonObject {
                    put("kind", kind(s)); put("label", label(s))
                    put("startFrame", at); put("startSec", at.toDouble() / ctx.sampleRate)
                    put("frames", lengthOf(s))
                })
                at += lengthOf(s)
            }
        })
        putJsonArray("seamSec") { for (s in seams) add(s.toDouble() / ctx.sampleRate) }
    }

    private class Leg(
        val a: TrackRef,
        val b: TrackRef,
        val result: RenderSupport.Result?,
        val score: Double?,
        val gatedReason: String?,
        /** False when no candidate left both songs their minimum body: rendered for the report, not played. */
        val played: Boolean = true,
    )

    private companion object {
        val PRETTY = Json { prettyPrint = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    }
}
