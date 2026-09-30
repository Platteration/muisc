package dev.muisc.transitions.recipe

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.dsp.stems.StemQuality
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.planner.CompatibilityScores
import dev.muisc.transitions.planner.StrategyFamily
import dev.muisc.transitions.planner.StructTables
import dev.muisc.transitions.strategies.BeatDomain
import kotlin.math.ceil

/**
 * [RecipeStrategy.applicability]: a recipe's [RecipeRules] become blockers with reasons a person can read, and a
 * pair that meets every rule is scored
 *
 * ```
 * score = baseScore × (0.5 + 0.5 × fit) × s_stems
 * fit (match / glide) = 0.30 s_tempo + 0.15 s_key + 0.15 s_energy + 0.10 s_vocal + 0.15 s_grid + 0.05 s_struct + 0.10 s_room
 * fit (none)          = 0.25 s_key + 0.20 s_energy + 0.15 s_vocal + 0.15 gridConfidenceA + 0.10 s_struct + 0.15 s_room
 * ```
 *
 * with the §5.2 sub-scores of [CompatibilityScores] (`s_room` needs `ceil(lengthBars)` bars on each side;
 * `s_struct` is 1 when the recipe names both the outro and the intro types it wants — it already vetted them —
 * else the beat-domain (match / glide) or cut-family (none) prior of [StructTables]; `s_stems` = 0.8, the
 * pseudo-stem score, when the recipe uses stem lanes, else 1). So a perfect pair scores `baseScore` and a poor one
 * no less than half of it: the rules decide *whether*, the sub-scores only nudge *how well*.
 *
 * Rules (a rule the recipe leaves out does not apply):
 * - `requiresBeatMatch` (default: true for `match` / `glide`, false for `none`): both grids confident
 *   ([PairFeatures.beatMatchable]).
 * - `match` / `glide` always need a beat grid on both tracks; `none` needs one on A (its bars are the timeline).
 * - `maxStretchPercent` (default for `match` / `glide`: `prefs.maxStretchPercent`; `none` has no default because it
 *   stretches nothing): [PairFeatures.stretchPercent] at most this.
 * - `maxKeyDistance`: [PairFeatures.camelotDistanceAfterShift] at most this.
 * - `outro` / `intro`: A's outro type / B's intro type in the list.
 * - `minEnergyDelta..maxEnergyDelta`: [PairFeatures.energyDelta] inside the range.
 *
 * Never throws: a recipe that does not resolve with its default variables (plus `prefs.paramOverrides` for its
 * strategy id) is blocked with the resolver's message, and any other failure is reported as a blocker too.
 */
internal object RecipeScoring {
    const val STEMS_SCORE: Double = CompatibilityScores.STEMS_PSEUDO

    fun applicability(recipe: TransitionRecipe, features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability = try {
        evaluate(recipe, features, a, b, prefs)
    } catch (e: Exception) {
        Applicability.blocked("recipe '${recipe.id}' could not be evaluated: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun evaluate(recipe: TransitionRecipe, f: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val overrides = Params(prefs.paramOverrides[recipe.strategyId].orEmpty())
        val r = try {
            RecipeResolver.resolve(recipe, overrides, bpb)
        } catch (e: RecipeException) {
            return Applicability.blocked("recipe '${recipe.id}' does not resolve: ${e.message}")
        }
        val rules = recipe.rules
        val beatDomain = r.tempo != RecipeTempo.NONE
        val blockers = ArrayList<String>()

        if (beatDomain) {
            if (a.grid.beatCount < 2) blockers += "A has no beat grid: a ${r.tempo.name.lowercase()} recipe beat-matches the two tracks"
            if (b.grid.beatCount < 2) blockers += "B has no beat grid: a ${r.tempo.name.lowercase()} recipe beat-matches the two tracks"
        } else {
            if (a.grid.beatCount < 2 || BeatDomain.bpmOf(a) <= 0.0) blockers += "A has no beat grid: this recipe's bars follow A's grid"
        }
        if (rules.requiresBeatMatch ?: beatDomain) {
            if (!f.beatMatchable) blockers += "not beat-matchable: grid confidence ${"%.2f".format(f.gridConfidenceA)}/${"%.2f".format(f.gridConfidenceB)}, this recipe needs 0.5 on both"
        }
        val stretchLimit = rules.maxStretchPercent ?: (if (beatDomain) prefs.maxStretchPercent else null)
        if (stretchLimit != null && f.stretchPercent > stretchLimit) {
            blockers += "tempos ${"%.1f".format(f.stretchPercent)} % apart, this recipe allows ${RecipeGeometry.fmt(stretchLimit)} %"
        }
        rules.maxKeyDistance?.let { max ->
            if (f.camelotDistanceAfterShift > max) blockers += "keys ${f.camelotDistanceAfterShift} Camelot step(s) apart after the best pitch shift, this recipe allows $max"
        }
        if (rules.outro.isNotEmpty() && f.outro !in rules.outro) blockers += "A's outro is ${f.outro}; this recipe needs ${orList(rules.outro.map { it.name })}"
        if (rules.intro.isNotEmpty() && f.intro !in rules.intro) blockers += "B's intro is ${f.intro}; this recipe needs ${orList(rules.intro.map { it.name })}"
        if (f.energyDelta < rules.minEnergyDelta || f.energyDelta > rules.maxEnergyDelta) {
            blockers += "energy changes by ${"%+.2f".format(f.energyDelta)} from A to B, this recipe allows ${"%+.2f".format(rules.minEnergyDelta)}..${"%+.2f".format(rules.maxEnergyDelta)}"
        }
        val pair = BeatDomain.describePair(f, a, b)
        if (blockers.isNotEmpty()) return Applicability(0.0, reasons = pair, blockers = blockers)

        val needed = ceil(r.lengthBars).toInt().coerceAtLeast(1) * bpb
        val sTempo = CompatibilityScores.tempo(f, prefs)
        val sKey = CompatibilityScores.key(f)
        val sEnergy = CompatibilityScores.energy(f)
        val sVocal = CompatibilityScores.vocal(f)
        val sGrid = if (beatDomain) CompatibilityScores.grid(f) else f.gridConfidenceA.coerceIn(0.0, 1.0)
        val sStruct = if (rules.outro.isNotEmpty() && rules.intro.isNotEmpty()) 1.0 else StructTables.prior(if (beatDomain) StrategyFamily.BEAT_DOMAIN else StrategyFamily.CUT, f.outro, f.intro)
        val sRoom = CompatibilityScores.room(f, needed)
        val sStems = if (r.needsStems) CompatibilityScores.stems(StemQuality.PSEUDO) else 1.0
        val fit = if (beatDomain) {
            0.30 * sTempo + 0.15 * sKey + 0.15 * sEnergy + 0.10 * sVocal + 0.15 * sGrid + 0.05 * sStruct + 0.10 * sRoom
        } else {
            0.25 * sKey + 0.20 * sEnergy + 0.15 * sVocal + 0.15 * sGrid + 0.10 * sStruct + 0.15 * sRoom
        }.coerceIn(0.0, 1.0)
        val base = rules.baseScore.coerceIn(0.0, 1.0)
        val score = base * (0.5 + 0.5 * fit) * sStems
        val reasons = ArrayList<String>()
        reasons += "recipe '${recipe.name}': every rule met, score ${"%.2f".format(score)} = base ${"%.2f".format(base)} x (0.5 + 0.5 x fit ${"%.2f".format(fit)})" +
            (if (sStems < 1.0) " x stems ${"%.2f".format(sStems)} (pseudo-stems)" else "")
        reasons += "fit: " + (if (beatDomain) "tempo ${"%.2f".format(sTempo)}, " else "") +
            "key ${"%.2f".format(sKey)}, energy ${"%.2f".format(sEnergy)}, vocal ${"%.2f".format(sVocal)}, grid ${"%.2f".format(sGrid)}, structure ${"%.2f".format(sStruct)}, room ${"%.2f".format(sRoom)} (needs $needed beats a side)"
        reasons += pair
        return Applicability.of(score, *reasons.toTypedArray())
    }

    /** `"A"`, `"A or B"`, `"A, B or C"`. */
    fun orList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " or " + items.last()
    }
}
