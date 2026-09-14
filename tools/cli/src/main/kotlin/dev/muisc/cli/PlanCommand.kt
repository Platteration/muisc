package dev.muisc.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.planner.ExplainedPlans
import dev.muisc.transitions.planner.ScoreBreakdown
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `muisc plan` — what the DJ would consider for A → B and why: the [PairFeatures] both tracks agree on, then every
 * candidate strategy with its score, the §5.2 sub-scores that produced it, the strategy's own reasons, its planned
 * length in bars and frames — and, at the bottom, the strategies that were skipped and what blocked them.
 *
 * `--previous <id>` applies the variety penalty as it would in a set, so you see the ranking the DJ would really get
 * after having just played that strategy.
 */
class PlanCommand : MuiscCommand("plan") {

    override fun help(context: Context) = "Rank every transition strategy for a pair of tracks and explain the scores."

    private val a by argument("A", help = "Outgoing track.").file()
    private val b by argument("B", help = "Incoming track.").file()
    private val previous by option("--previous", metavar = "ID", help = "Strategy id played on the previous transition (variety penalty).")
    private val json by option("--json", help = "Print the ranking as JSON.").flag()
    private val top by option("--top", metavar = "N", help = "Show only the best N candidates.")

    override fun execute(ctx: CliContext) {
        val aRef = ctx.trackRef(a)
        val bRef = ctx.trackRef(b)
        val explained = ctx.planner.planExplained(aRef, bRef, ctx.prefs, seed, previous)
        val limit = top?.toIntOrNull() ?: Int.MAX_VALUE
        if (json) echo(JSON.encodeToString(JsonObject.serializer(), toJson(explained, aRef, bRef, ctx)))
        else echo(text(explained, aRef, bRef, ctx, limit))
        echoElapsed("planned ${explained.ranked.candidates.size} candidate(s)")
    }

    private fun text(e: ExplainedPlans, a: TrackRef, b: TrackRef, ctx: CliContext, limit: Int): String = buildString {
        val f = e.ranked.features
        append("A  ").append(a.title).append("  ").append(Fmt.num(a.analysis.tempo.bpm, 2)).append(" BPM  ").append(Fmt.key(a.analysis))
            .append("  ").append(Fmt.num(a.analysis.loudness.integratedLufs.toDouble(), 1)).append(" LUFS  outro ").append(f.outro).append('\n')
        append("B  ").append(b.title).append("  ").append(Fmt.num(b.analysis.tempo.bpm, 2)).append(" BPM  ").append(Fmt.key(b.analysis))
            .append("  ").append(Fmt.num(b.analysis.loudness.integratedLufs.toDouble(), 1)).append(" LUFS  intro ").append(f.intro).append('\n')
        append('\n').append(features(f)).append('\n')
        append("\ncandidates (").append(e.ranked.candidates.size).append(", best first):\n")
        val rows = ArrayList<List<String>>()
        rows += listOf("#", "strategy", "score", "modifiers", "bars", "frames", "sub-scores")
        e.ranked.candidates.take(limit).forEachIndexed { i, c ->
            val bd = e.explanation.breakdown(c.strategy.id)
            rows += listOf(
                "${i + 1}", c.strategy.id, Fmt.num(c.score, 3),
                c.plan.modifiers.joinToString("+").ifEmpty { "—" },
                Fmt.num(Fmt.bars(c.plan.expectedOutputFrames, a.analysis.grid.bpm, ctx.sampleRate), 1),
                "${c.plan.expectedOutputFrames}",
                bd?.subScores?.summary() ?: "",
            )
        }
        append(Fmt.table(rows, "  "))
        append("\n\nwhy:\n")
        for (c in e.ranked.candidates.take(limit)) {
            append("  ").append(c.strategy.id).append(" — ").append(c.strategy.displayName).append('\n')
            // The planner already appends the §5.2 sub-scores and the score formula to the strategy's own reasons.
            for (r in reasons(c)) append("    · ").append(r).append('\n')
            if (c.applicability.blockers.isNotEmpty()) append("    ! blockers: ").append(c.applicability.blockers.joinToString("; ")).append('\n')
            append("    plan: ").append(Fmt.planLine(c.plan, a.analysis.grid.bpm, ctx.sampleRate)).append('\n')
            if (c.plan.notes.isNotEmpty()) append("    notes: ").append(c.plan.notes.joinToString("; ")).append('\n')
            if (c.plan.lanes.isNotEmpty()) append("    lanes: ").append(c.plan.lanes.joinToString(", ") { "${it.id}(${it.points.size})" }).append('\n')
        }
        if (e.explanation.skipped.isNotEmpty()) {
            append("\nskipped (").append(e.explanation.skipped.size).append("):\n")
            append(Fmt.table(e.explanation.skipped.map { listOf(it.strategyId, it.reason, it.blockers.joinToString("; ")) }, "  "))
        }
    }

    private fun reasons(c: PlanCandidate): List<String> = c.applicability.reasons

    private fun features(f: PairFeatures): String = Fmt.table(
        listOf(
            listOf("tempo", "ratio ${Fmt.num(f.tempoRatio, 4)}  ${f.tempoRelation}  stretch ${Fmt.num(f.stretchPercent, 2)} %  beat-matchable ${f.beatMatchable}"),
            listOf("key", "camelot distance ${f.camelotDistance}  best shift ${f.bestPitchShiftSemitones} st → distance ${f.camelotDistanceAfterShift}  strengths ${Fmt.num(f.keyStrengthA, 2)}/${Fmt.num(f.keyStrengthB, 2)}"),
            listOf("level", "loudness Δ ${Fmt.num(f.loudnessDeltaLu, 1)} LU  energy Δ ${Fmt.num(f.energyDelta, 2)}  low-end share ${Fmt.num(f.lowEndShareA, 2)}/${Fmt.num(f.lowEndShareB, 2)}"),
            listOf("content", "vocal clash ${Fmt.num(f.vocalClash, 2)}  spectral similarity ${Fmt.num(f.spectralSimilarity, 2)}"),
            listOf("room", "outro beats ${f.outroBeatsAvailable}  intro beats ${f.introBeatsAvailable}  grid confidence ${Fmt.num(f.gridConfidenceA, 2)}/${Fmt.num(f.gridConfidenceB, 2)}"),
        ),
        "  ",
    )

    private fun toJson(e: ExplainedPlans, a: TrackRef, b: TrackRef, ctx: CliContext): JsonObject = buildJsonObject {
        put("a", a.source.value)
        put("b", b.source.value)
        put("seed", seed)
        previous?.let { put("previousStrategyId", it) }
        putJsonObject("features") {
            val f = e.ranked.features
            put("tempoRatio", f.tempoRatio); put("tempoRelation", f.tempoRelation.name); put("stretchPercent", f.stretchPercent)
            put("camelotDistance", f.camelotDistance); put("bestPitchShiftSemitones", f.bestPitchShiftSemitones)
            put("camelotDistanceAfterShift", f.camelotDistanceAfterShift)
            put("loudnessDeltaLu", f.loudnessDeltaLu); put("energyDelta", f.energyDelta); put("vocalClash", f.vocalClash)
            put("spectralSimilarity", f.spectralSimilarity); put("outro", f.outro.name); put("intro", f.intro.name)
            put("outroBeatsAvailable", f.outroBeatsAvailable); put("introBeatsAvailable", f.introBeatsAvailable)
            put("gridConfidenceA", f.gridConfidenceA); put("gridConfidenceB", f.gridConfidenceB)
            put("keyStrengthA", f.keyStrengthA); put("keyStrengthB", f.keyStrengthB)
            put("lowEndShareA", f.lowEndShareA); put("lowEndShareB", f.lowEndShareB)
            put("beatMatchable", f.beatMatchable)
        }
        put("candidates", buildJsonArray {
            for (c in e.ranked.candidates) add(candidateJson(c, e.explanation.breakdown(c.strategy.id), a.analysis.grid.bpm, ctx.sampleRate))
        })
        put("skipped", buildJsonArray {
            for (s in e.explanation.skipped) add(buildJsonObject {
                put("strategyId", s.strategyId); put("reason", s.reason)
                putJsonArray("blockers") { for (x in s.blockers) add(x) }
            })
        })
    }

    private fun candidateJson(c: PlanCandidate, bd: ScoreBreakdown?, bpm: Double, sampleRate: Int): JsonObject = buildJsonObject {
        put("strategyId", c.strategy.id)
        put("displayName", c.strategy.displayName)
        put("score", c.score)
        put("applicability", c.applicability.score)
        putJsonArray("reasons") { for (r in c.applicability.reasons) add(r) }
        putJsonArray("blockers") { for (r in c.applicability.blockers) add(r) }
        putJsonArray("modifiers") { for (m in c.plan.modifiers) add(m) }
        put("expectedOutputFrames", c.plan.expectedOutputFrames)
        put("bars", Fmt.bars(c.plan.expectedOutputFrames, bpm, sampleRate))
        put("aExitFrame", c.plan.aExitFrame); put("bEntryFrame", c.plan.bEntryFrame)
        put("aWindow", "${c.plan.aWindow.start}..${c.plan.aWindow.end}")
        put("bWindow", "${c.plan.bWindow.start}..${c.plan.bWindow.end}")
        put("stemNeed", c.plan.stemNeed.name)
        if (bd != null) {
            putJsonObject("subScores") { for ((k, v) in bd.subScores.asMap()) put(k, v) }
            put("fit", bd.fit); put("weight", bd.weight); put("energyPref", bd.energyPref)
            put("variety", bd.variety); put("modifierBonus", bd.modifierBonus); put("jitter", bd.jitter)
            put("formula", bd.formula())
        }
        putJsonObject("params") { for ((k, v) in c.plan.params.values) put(k, v) }
    }

    private companion object {
        val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}

