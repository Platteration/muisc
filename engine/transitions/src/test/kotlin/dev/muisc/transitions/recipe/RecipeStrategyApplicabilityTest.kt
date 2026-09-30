package dev.muisc.transitions.recipe

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.transitions.planner.CompatibilityScores
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.fadeInB
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.fadeOutA
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.pt
import dev.muisc.transitions.recipe.RecipeRenderTestSupport.recipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A recipe's rules become blockers a person can read; a pair that meets them is scored from the §5.2 sub-scores. */
class RecipeStrategyApplicabilityTest {
    private val near = RecipeRenderTestSupport.near
    private val far = RecipeRenderTestSupport.far

    private fun blend(tempo: RecipeTempo = RecipeTempo.MATCH, rules: RecipeRules = RecipeRules()) =
        recipe("blend", tempo, DeckRecipe(level = fadeOutA()), DeckRecipe(level = fadeInB()), rules = rules)

    private fun app(rec: TransitionRecipe, pair: dev.muisc.transitions.strategies.BeatDomainTestSupport.Pair, f: dev.muisc.transitions.PairFeatures = pair.features, prefs: dev.muisc.transitions.TransitionPrefs = pair.prefs) =
        RecipeStrategy(rec).applicability(f, pair.a.analysis, pair.b.analysis, prefs)

    @Test
    fun tempoRuleDefaultsToTheStretchPreferenceForBeatMatchedRecipes() {
        val blocked = app(blend(), far)
        assertTrue(!blocked.applicable, blocked.toString())
        assertTrue(blocked.blockers.any { it == "tempos ${"%.1f".format(far.features.stretchPercent)} % apart, this recipe allows 8 %" }, blocked.blockers.toString())
        // Its own limit wins over the preference.
        val own = app(blend(rules = RecipeRules(maxStretchPercent = 20.0)), far)
        assertTrue(own.blockers.none { it.startsWith("tempos") }, own.toString())
        val tight = app(blend(rules = RecipeRules(maxStretchPercent = 0.5)), near)
        assertTrue(tight.blockers.any { it.endsWith("this recipe allows 0.5 %") }, tight.toString())
        // A `none` recipe has no tempo rule unless it states one.
        assertTrue(app(blend(RecipeTempo.NONE), far).applicable)
        assertTrue(!app(blend(RecipeTempo.NONE, RecipeRules(maxStretchPercent = 8.0)), far).applicable)
    }

    @Test
    fun beatMatchRuleDefaultsByTempoMode() {
        val shaky = near.features.copy(gridConfidenceB = 0.3)
        val match = app(blend(), near, shaky)
        assertTrue(match.blockers.any { it.startsWith("not beat-matchable: grid confidence 0.") }, match.toString())
        assertTrue(app(blend(RecipeTempo.NONE), near, shaky).applicable, "a none recipe does not need B's grid")
        assertTrue(!app(blend(RecipeTempo.NONE, RecipeRules(requiresBeatMatch = true)), near, shaky).applicable)
        assertTrue(app(blend(rules = RecipeRules(requiresBeatMatch = false)), near, shaky).blockers.none { it.startsWith("not beat-matchable") })
    }

    @Test
    fun structureKeyAndEnergyRulesExplainThemselves() {
        val f = near.features.copy(outro = OutroType.FADE_OUT, intro = IntroType.COLD_START, camelotDistanceAfterShift = 3, energyDelta = 0.4)
        val rules = RecipeRules(
            outro = listOf(OutroType.BEAT_OUTRO), intro = listOf(IntroType.AMBIENT_INTRO, IntroType.BEAT_INTRO),
            maxKeyDistance = 1, maxEnergyDelta = 0.2,
        )
        val a = app(blend(rules = rules), near, f)
        assertEquals(
            setOf(
                "A's outro is FADE_OUT; this recipe needs BEAT_OUTRO",
                "B's intro is COLD_START; this recipe needs AMBIENT_INTRO or BEAT_INTRO",
                "keys 3 Camelot step(s) apart after the best pitch shift, this recipe allows 1",
                "energy changes by +0.40 from A to B, this recipe allows -1.00..+0.20",
            ),
            a.blockers.toSet(),
        )
        // The same rules pass on a pair that meets them.
        val ok = near.features.copy(outro = OutroType.BEAT_OUTRO, intro = IntroType.BEAT_INTRO, camelotDistanceAfterShift = 1, energyDelta = 0.1)
        assertTrue(app(blend(rules = rules), near, ok).applicable)
        assertEquals("A, B or C", RecipeScoring.orList(listOf("A", "B", "C")))
    }

    @Test
    fun scoreIsBaseTimesTheBlendOfSubScores() {
        val f = near.features
        val rules = RecipeRules(baseScore = 0.8)
        val a = app(blend(rules = rules), near)
        assertTrue(a.applicable, a.toString())
        val needed = 8 * 4
        val fit = 0.30 * CompatibilityScores.tempo(f, near.prefs) + 0.15 * CompatibilityScores.key(f) + 0.15 * CompatibilityScores.energy(f) +
            0.10 * CompatibilityScores.vocal(f) + 0.15 * CompatibilityScores.grid(f) +
            0.05 * dev.muisc.transitions.planner.StructTables.prior(dev.muisc.transitions.planner.StrategyFamily.BEAT_DOMAIN, f.outro, f.intro) +
            0.10 * CompatibilityScores.room(f, needed)
        assertEquals(0.8 * (0.5 + 0.5 * fit), a.score, 1e-12)
        assertTrue(a.score <= 0.8 && a.score >= 0.4, "the sub-scores only nudge the base score: ${a.score}")
        assertTrue(a.reasons.first().startsWith("recipe 'blend': every rule met"), a.reasons.toString())
        // Stem lanes cost the pseudo-stem factor.
        val stems = recipe("stems", RecipeTempo.MATCH, DeckRecipe(level = fadeOutA(), stems = StemsRecipe(drums = listOf(pt(2, 0), pt(3, "off")))), DeckRecipe(level = fadeInB()), rules = rules)
        assertEquals(a.score * CompatibilityScores.STEMS_PSEUDO, app(stems, near).score, 1e-12)
        // A stem section whose lanes are all neutral does not.
        val idle = recipe("idle", RecipeTempo.MATCH, DeckRecipe(level = fadeOutA(), stems = StemsRecipe()), DeckRecipe(level = fadeInB()), rules = rules)
        assertEquals(a.score, app(idle, near).score, 1e-12)
    }

    @Test
    fun neverThrowsOnARecipeThatDoesNotResolve() {
        val broken = recipe("broken", RecipeTempo.MATCH, DeckRecipe(level = listOf(pt(0, 1), pt("nope", 0))), DeckRecipe(level = fadeInB()))
        val a = app(broken, near)
        assertTrue(!a.applicable, a.toString())
        assertTrue(a.blockers.single().startsWith("recipe 'broken' does not resolve: a.level[1].at: unknown name 'nope'"), a.blockers.toString())
        val tooLong = recipe("too-long", RecipeTempo.MATCH, DeckRecipe(level = fadeOutA()), DeckRecipe(level = fadeInB()), length = 200)
        assertTrue(app(tooLong, near).blockers.single().contains("timing.lengthBars"), app(tooLong, near).toString())
        // Overrides from the preferences are honoured when they fix (or break) the recipe.
        val withVar = recipe(
            "var", RecipeTempo.MATCH, DeckRecipe(level = fadeOutA()), DeckRecipe(level = fadeInB()), length = "len",
            vars = mapOf("len" to RecipeVar(8.0, 1.0, 64.0, integer = true)),
        )
        assertTrue(app(withVar, near).applicable)
        val prefs = near.prefs.copy(paramOverrides = mapOf("recipe:var" to mapOf("len" to "64")))
        val longer = app(withVar, near, prefs = prefs)
        assertTrue(longer.applicable && longer.score < app(withVar, near).score, "a 64-bar overlap has less room: ${longer.score}")
    }
}
