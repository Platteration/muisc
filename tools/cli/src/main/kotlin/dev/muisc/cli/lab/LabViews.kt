package dev.muisc.cli.lab

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.TransitionModifier
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.custom.ContextBucket
import dev.muisc.transitions.planner.PlanExplanation
import dev.muisc.transitions.recipe.RecipeStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Engine objects turned into the JSON the page reads. Pure functions; no I/O. */
object LabViews {

    fun trackSummary(t: LabContext.LabTrack): JsonObject = buildJsonObject {
        val a = t.ref.analysis
        put("id", t.id)
        put("name", t.name)
        put("path", t.file.path)
        put("durationSec", LabJson.num(a.durationSec, 3))
        put("bpm", LabJson.num(a.grid.bpm, 2))
        put("key", a.key.key.shortName)
        put("camelot", a.key.key.camelot.code)
        put("lufs", LabJson.num(a.loudness.integratedLufs, 1))
    }

    /** Everything the page draws for one track, times in seconds of the track. */
    fun trackAnalysis(t: LabContext.LabTrack, peaks: Peaks): JsonObject {
        val a = t.ref.analysis
        val sr = a.sampleRate.toDouble()
        fun beatSec(beat: Int): Double? = if (beat < 0 || a.grid.isEmpty) null else a.grid.frameOfBeat(beat.toDouble()) / sr
        return buildJsonObject {
            for ((k, v) in trackSummary(t)) put(k, v)
            put("identity", a.identity)
            put("gridConfidence", LabJson.num(a.grid.confidence, 3))
            put("beatsPerBar", a.grid.beatsPerBar)
            put("keyName", a.key.key.name)
            put("keyStrength", LabJson.num(a.key.strength, 3))
            put("truePeakDbtp", LabJson.num(a.loudness.truePeakDbtp, 2))
            put("loudnessRangeLu", LabJson.num(a.loudness.loudnessRangeLu, 1))
            put("trimStartSec", LabJson.num(a.trimStartFrame / sr, 3))
            put("trimEndSec", LabJson.num(a.trimEndFrame / sr, 3))
            put("intro", a.intro.name)
            put("outro", a.outro.name)
            putJsonObject("cues") {
                put("mixIn", beatSec(a.cues.mixInBeat)?.let { LabJson.num(it, 3) } ?: kotlinx.serialization.json.JsonNull)
                put("mixOut", beatSec(a.cues.mixOutBeat)?.let { LabJson.num(it, 3) } ?: kotlinx.serialization.json.JsonNull)
                put("firstDownbeat", beatSec(a.cues.firstDownbeat)?.let { LabJson.num(it, 3) } ?: kotlinx.serialization.json.JsonNull)
                put("lastDownbeat", beatSec(a.cues.lastDownbeat)?.let { LabJson.num(it, 3) } ?: kotlinx.serialization.json.JsonNull)
                put("drop", beatSec(a.cues.dropBeat)?.let { LabJson.num(it, 3) } ?: kotlinx.serialization.json.JsonNull)
            }
            putJsonArray("sections") {
                for (s in a.sections) addJsonObject {
                    put("startSec", LabJson.num(beatSec(s.startBeat) ?: 0.0, 3))
                    put("endSec", LabJson.num(beatSec(s.endBeat) ?: a.durationSec, 3))
                    put("label", s.label.name)
                    put("energy", LabJson.num(s.energy, 3))
                }
            }
            // Beat grid as seconds; downbeats flagged so the page can draw bar lines heavier.
            putJsonArray("beats") { for (f in a.grid.beatFrames) add(LabJson.num(f / sr, 4)) }
            putJsonArray("downbeats") { for (f in a.grid.downbeatFrames()) add(LabJson.num(f / sr, 4)) }
            put("peaks", peaks.toJson())
        }
    }

    fun pair(a: TrackAnalysis, b: TrackAnalysis, f: PairFeatures): JsonObject = buildJsonObject {
        putJsonObject("a") { deck(a, f.outro.name) }
        putJsonObject("b") { deck(b, f.intro.name) }
        put("tempoRatio", LabJson.num(f.tempoRatio, 4))
        put("tempoRelation", f.tempoRelation.name)
        put("stretchPercent", LabJson.num(f.stretchPercent, 2))
        put("camelotDistance", f.camelotDistance)
        put("pitchShiftSemitones", f.bestPitchShiftSemitones)
        put("camelotDistanceAfterShift", f.camelotDistanceAfterShift)
        put("loudnessDeltaLu", LabJson.num(f.loudnessDeltaLu, 2))
        put("energyDelta", LabJson.num(f.energyDelta, 3))
        put("vocalClash", LabJson.num(f.vocalClash, 3))
        put("spectralSimilarity", LabJson.num(f.spectralSimilarity, 3))
        put("outroBeats", f.outroBeatsAvailable)
        put("introBeats", f.introBeatsAvailable)
        put("gridConfidenceA", LabJson.num(f.gridConfidenceA, 3))
        put("gridConfidenceB", LabJson.num(f.gridConfidenceB, 3))
        put("beatMatchable", f.beatMatchable)
        put("bucket", ContextBucket.of(f).label)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.deck(t: TrackAnalysis, edge: String) {
        put("bpm", LabJson.num(t.grid.bpm, 2))
        put("key", t.key.key.shortName)
        put("camelot", t.key.key.camelot.code)
        put("lufs", LabJson.num(t.loudness.integratedLufs, 1))
        put("edge", edge)
    }

    fun paramSpec(p: ParamSpec): JsonObject = buildJsonObject {
        put("id", p.id)
        put("label", p.label)
        put("doc", p.doc)
        put("default", p.defaultString)
        when (p) {
            is ParamSpec.DoubleSpec -> {
                put("type", "double"); put("min", p.min); put("max", p.max); put("unit", p.unit)
                if (p.step > 0) put("step", p.step)
            }
            is ParamSpec.IntSpec -> { put("type", "int"); put("min", p.min); put("max", p.max); put("unit", p.unit) }
            is ParamSpec.BoolSpec -> put("type", "bool")
            is ParamSpec.ChoiceSpec -> { put("type", "choice"); putJsonArray("choices") { for (c in p.choices) add(c) } }
        }
    }

    fun strategy(s: TransitionStrategy): JsonObject = buildJsonObject {
        put("id", s.id)
        put("displayName", s.displayName)
        put("description", s.description)
        put("recipe", s is RecipeStrategy)
        putJsonArray("params") { for (p in s.params) add(paramSpec(p)) }
    }

    fun modifier(m: TransitionModifier): JsonObject = buildJsonObject {
        put("id", m.id)
        put("displayName", m.displayName)
        putJsonArray("params") { for (p in m.params) add(paramSpec(p)) }
    }

    /** The planner's ranking with its explanation, best first; skipped strategies with their reasons. */
    fun plan(ranked: RankedPlans, ex: PlanExplanation, sampleRate: Int): JsonObject = buildJsonObject {
        putJsonArray("candidates") {
            ranked.candidates.forEachIndexed { i, c ->
                val b = ex.ranked.getOrNull(i)
                addJsonObject {
                    put("rank", i + 1)
                    put("strategy", c.strategy.id)
                    put("displayName", c.strategy.displayName)
                    put("description", c.strategy.description)
                    put("recipe", c.strategy is RecipeStrategy)
                    put("score", LabJson.num(c.score, 4))
                    putJsonObject("params") { for ((k, v) in c.plan.params.values.toSortedMap()) put(k, v) }
                    putJsonArray("modifiers") { for (m in c.modifiers) add(m.id) }
                    put("expectedSec", LabJson.num(c.plan.expectedOutputFrames.toDouble() / sampleRate, 2))
                    putJsonArray("notes") { for (n in c.plan.notes) add(n) }
                    if (b != null) {
                        put("fit", LabJson.num(b.fit, 3))
                        put("weight", LabJson.num(b.weight, 3))
                        put("energyPref", LabJson.num(b.energyPref, 3))
                        put("variety", LabJson.num(b.variety, 3))
                        put("modifierBonus", LabJson.num(b.modifierBonus, 3))
                        put("jitter", LabJson.num(b.jitter, 4))
                        put("learned", LabJson.num(b.learned, 3))
                        b.learnedNote?.let { put("learnedNote", it) }
                        put("pinned", b.pinned)
                        b.pinNote?.let { put("pinNote", it) }
                        b.presetNote?.let { put("presetNote", it) }
                        put("formula", b.formula())
                        putJsonObject("subScores") { for ((k, v) in b.subScores.asMap()) put(k, LabJson.num(v, 3)) }
                        putJsonArray("reasons") { for (r in b.reasons) add(r) }
                        putJsonArray("blockers") { for (r in b.blockers) add(r) }
                    }
                }
            }
        }
        putJsonArray("skipped") {
            for (s in ex.skipped) addJsonObject {
                put("strategy", s.strategyId)
                put("reason", s.reason)
                putJsonArray("blockers") { for (r in s.blockers) add(r) }
            }
        }
        ex.pin?.let { p ->
            putJsonObject("pin") {
                put("strategy", p.strategyId); put("used", p.used); put("reason", p.reason)
                p.presetId?.let { put("preset", it) }
            }
        }
        putJsonArray("notes") { for (n in ex.notes) add(n) }
    }

    fun strings(list: List<String>): JsonArray = buildJsonArray { for (s in list) add(s) }
}
