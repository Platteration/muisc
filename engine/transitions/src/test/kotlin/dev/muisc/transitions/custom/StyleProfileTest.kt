package dev.muisc.transitions.custom

import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.planner.DefaultTransitionPlanner
import dev.muisc.transitions.planner.TestAnalyses
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What applying a style does to prefs, and that the shipped styles are consistent with the shipped catalogue. */
class StyleProfileTest {

    private val base = TransitionPrefs(
        energy = 0.5, maxStretchPercent = 8.0, keyLock = false,
        strategyWeights = mapOf("bassSwap" to 2.0, "echoOut" to 0.5),
        disabledStrategies = setOf("brakeStop"),
        paramOverrides = mapOf("crossfade" to mapOf("fadeSec" to "4")),
        activePresets = mapOf("crossfade" to "long-crossfade", "echoOut" to "dub-echo"),
        targetLufs = -16.0,
    )

    @Test
    fun emptyPatchIsTheIdentity() {
        assertEquals(base, PrefsPatch().apply(base))
        assertEquals(TransitionPrefs(), StyleProfile("x", "X").apply(TransitionPrefs()))
    }

    @Test
    fun patchChangesOnlyWhatItSets() {
        val p = PrefsPatch(
            energy = 0.9, keyLock = true, preferredOverlapBars = 8,
            weights = mapOf("bassSwap" to 1.5, "stemSwap" to 0.5),
            prefer = listOf("echoOut", "recipe:dub"),
            disable = setOf("loopRollRiser"),
            activePresets = mapOf("crossfade" to "quick-crossfade"),
        ).apply(base)
        assertEquals(0.9, p.energy)
        assertEquals(true, p.keyLock)
        assertEquals(8, p.preferredOverlapBars)
        assertEquals(8.0, p.maxStretchPercent, "not in the patch: unchanged")
        assertEquals(base.varietyPenalty, p.varietyPenalty)
        assertEquals(-16.0, p.targetLufs)
        assertEquals(base.paramOverrides, p.paramOverrides)
        assertEquals(3.0, p.strategyWeights["bassSwap"]!!, 1e-12, "multiplier on the existing weight")
        assertEquals(0.5, p.strategyWeights["stemSwap"]!!, 1e-12, "a missing weight counts as 1")
        assertEquals(0.5 * PrefsPatch.PREFER_BOOST, p.strategyWeights["echoOut"]!!, 1e-12)
        assertEquals(PrefsPatch.PREFER_BOOST, p.strategyWeights["recipe:dub"]!!, 1e-12)
        assertEquals(setOf("brakeStop", "loopRollRiser"), p.disabledStrategies, "disables add up")
        assertEquals(mapOf("crossfade" to "quick-crossfade", "echoOut" to "dub-echo"), p.activePresets)
    }

    @Test
    fun builtInStylesAreConsistentWithTheCatalogue() {
        val registry = DefaultStrategyRegistry.default()
        val known = registry.strategyIds.toSet() + registry.modifierIds
        assertEquals(listOf("smooth", "club", "radio", "chill", "adventurous", "purist"), BuiltInStyles.all.map { it.id })
        for (s in BuiltInStyles.all) {
            CustomJson.requireId(s.id, "style")
            assertNull(s.patch.problem(), s.id)
            assertTrue(s.description.length > 40, "${s.id} has a real description")
            for (id in s.patch.weights.keys + s.patch.prefer + s.patch.disable) assertTrue(id in known, "${s.id}: unknown id $id")
            assertTrue("crossfade" !in s.patch.disable, "${s.id} must not try to disable the floor")
            for ((strategy, presetId) in s.patch.activePresets) {
                val preset = assertNotNull(BuiltInPresets.byId(presetId), "${s.id}: preset $presetId")
                assertEquals(strategy, preset.strategyId, "${s.id}: preset $presetId is for ${preset.strategyId}")
            }
            assertTrue(s.patch.describe().isNotEmpty())
        }
    }

    @Test
    fun purIstNeverPicksAnEffectAndEnergyStylesMoveTheRanking() {
        val registry = DefaultStrategyRegistry.default()
        val planner = DefaultTransitionPlanner(registry)
        val purist = BuiltInStyles.byId("purist")!!.apply(TransitionPrefs())
        val effects = setOf("echoOut", "filterSweep", "loopRollRiser", "brakeStop", "spectralFreezeBridge", "ambientBridge", "drumBreakBridge", "stemSwap")
        val rnd = Random(8)
        repeat(40) { i ->
            val a = TestAnalyses.ref(TestAnalyses.random(rnd, "a$i"))
            val b = TestAnalyses.ref(TestAnalyses.random(rnd, "b$i"))
            val r = planner.plan(a, b, purist)
            assertTrue(r.candidates.none { it.strategy.id in effects }, r.candidates.map { it.strategy.id }.toString())
            assertTrue(r.candidates.all { it.modifiers.isEmpty() }, "no glides or texture beds")
            assertTrue(r.candidates.any { it.strategy.id == "crossfade" })
            for (c in r.candidates) if (c.strategy.id in setOf("bassSwap", "beatMatchedBlend", "harmonicBlend")) assertTrue(r.features.stretchPercent <= 3.0)
        }
        // Energy moves the energy preference the way the descriptions say.
        val a = TestAnalyses.ref(TestAnalyses.simple("a"))
        val b = TestAnalyses.ref(TestAnalyses.simple("b", bpm = 122.0))
        val chill = planner.planExplained(a, b, BuiltInStyles.byId("chill")!!.apply(TransitionPrefs())).explanation
        val adventurous = planner.planExplained(a, b, BuiltInStyles.byId("adventurous")!!.apply(TransitionPrefs())).explanation
        assertTrue(chill.breakdown("crossfade")!!.energyPref > adventurous.breakdown("crossfade")!!.energyPref)
        // Club's active preset reaches the bass swap's plan.
        val club = planner.plan(a, b, BuiltInStyles.byId("club")!!.apply(TransitionPrefs()))
        assertEquals("8", club.candidates.first { it.strategy.id == "bassSwap" }.plan.params["overlapBars"])
    }
}
