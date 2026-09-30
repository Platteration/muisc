package dev.muisc.transitions.planner

import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.Params
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.custom.BuiltInStyles
import dev.muisc.transitions.custom.CustomJson
import dev.muisc.transitions.custom.InMemoryPinStore
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.custom.PlannerCustomization
import dev.muisc.transitions.custom.PrefsPatch
import dev.muisc.transitions.custom.StyleProfile
import dev.muisc.transitions.custom.StyleProfileTest
import dev.muisc.transitions.recipe.RecipeCatalog
import dev.muisc.transitions.recipe.RecipeCodec
import dev.muisc.transitions.recipe.RecipeLibrary
import dev.muisc.transitions.recipe.RecipeStrategy
import dev.muisc.transitions.sdk.StrategyTraits
import dev.muisc.transitions.sdk.Technique
import dev.muisc.transitions.strategies.CrossfadeStrategy
import dev.muisc.transitions.strategies.EchoOutStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `prefs.excludedTechniques`: what recipes declare from their content ([RecipeStrategy.techniques]) and how the
 * planner enforces it, with the params a preset, an override or a pin supplies.
 */
class TechniqueExclusionTest {

    private val a = TestAnalyses.ref(TestAnalyses.simple("a"))
    private val b = TestAnalyses.ref(TestAnalyses.simple("b", bpm = 121.0))
    private val myDub = RecipeStrategy(assertNotNull(RecipeCodec.parse(StyleProfileTest.MY_DUB).recipe))
    private val echoOnly = PrefsPatch(exclude = setOf(Technique.ECHO)).apply(TransitionPrefs())

    @Test
    fun builtInRecipesDeclareWhatTheirContentDoes() {
        val strategies = RecipeLibrary(null).load().recipes.associate { it.id to RecipeStrategy(it) }
        val declared = strategies.mapValues { (_, s) -> s.techniques(Params.EMPTY) }
        assertEquals(
            mapOf(
                "club-bass-swap" to emptySet(),
                "drums-first" to setOf(Technique.STEMS),
                "echo-wash" to setOf(Technique.ECHO),
                "filter-handoff" to setOf(Technique.FILTER),
                "long-glide" to setOf(Technique.TEMPO_GLIDE),
                "radio-segue" to emptySet(),
                "reverb-freeze-bridge" to setOf(Technique.REVERB, Technique.FILTER),
                "smooth-blend" to emptySet(),
                "tension-build" to setOf(Technique.FILTER, Technique.ECHO),
            ),
            declared.toSortedMap(),
        )
        // The params decide, not the recipe's name or tags: with its echo turned off tension-build is only a filter.
        assertEquals(setOf(Technique.FILTER), strategies.getValue("tension-build").techniques(Params(mapOf("echoSend" to "0"))))
        assertEquals(setOf(Technique.ECHO), myDub.techniques(Params.EMPTY), "tagged 'plain', but it has an echo")
        assertEquals(emptySet(), myDub.techniques(Params(mapOf("wet" to "0"))))
    }

    @Test
    fun anExcludedRecipeIsSkippedWithItsReasonAndTheParamsInUseDecide() {
        val registry = DefaultStrategyRegistry.default().withStrategies(myDub)
        val planner = DefaultTransitionPlanner(registry)
        assertTrue(planner.plan(a, b, TransitionPrefs()).candidates.any { it.strategy.id == "recipe:my-dub" }, "a candidate without exclusions")

        val excluded = planner.planExplained(a, b, echoOnly)
        assertTrue(excluded.ranked.candidates.none { it.strategy.id == "recipe:my-dub" })
        assertEquals("excluded in prefs: uses echo", excluded.explanation.skipped.single { it.strategyId == "recipe:my-dub" }.reason)
        assertTrue(excluded.ranked.candidates.any { it.strategy.id == "echoOut" }, "built-ins declare no techniques: only their ids disable them")

        // An override that silences the echo makes it a plain fade again, so it is allowed.
        val silenced = echoOnly.copy(paramOverrides = mapOf("recipe:my-dub" to mapOf("wet" to "0")))
        assertTrue(planner.plan(a, b, silenced).candidates.any { it.strategy.id == "recipe:my-dub" })
    }

    @Test
    fun aPinCannotBringBackAnExcludedTechniqueButAPinWithoutItIsUsed() {
        val registry = DefaultStrategyRegistry.default().withStrategies(myDub)
        val purist = BuiltInStyles.byId("purist")!!.apply(TransitionPrefs())
        fun planWith(pin: PairPin) = DefaultTransitionPlanner(registry, customization = PlannerCustomization(pins = InMemoryPinStore(listOf(pin))))
            .planExplained(a, b, purist)

        val echoing = planWith(PairPin(a.analysis.identity, b.analysis.identity, "recipe:my-dub", params = Params(mapOf("wet" to "0.9"))))
        assertFalse(echoing.explanation.pin!!.used)
        assertTrue(echoing.explanation.pin!!.reason.contains("excluded in prefs: uses echo"), echoing.explanation.pin!!.reason)
        assertTrue(echoing.ranked.candidates.none { it.strategy.id == "recipe:my-dub" })

        val dry = planWith(PairPin(a.analysis.identity, b.analysis.identity, "recipe:my-dub", params = Params(mapOf("wet" to "0"))))
        assertTrue(dry.explanation.pin!!.used, dry.explanation.pin!!.reason)
        assertEquals("recipe:my-dub", dry.ranked.best.strategy.id)
    }

    @Test
    fun theCrossfadeIsNeverExcludedAndUndeclaredStrategiesAreNot() {
        class DeclaredCrossfade(d: TransitionStrategy = CrossfadeStrategy()) : TransitionStrategy by d, StrategyTraits {
            override val ambition = 0.1
            override val beatDomain = false
            override fun techniques(params: Params, beatsPerBar: Int) = setOf(Technique.ECHO)
        }
        class UndeclaredEcho(d: TransitionStrategy = EchoOutStrategy()) : TransitionStrategy by d, StrategyTraits {
            override val ambition = 0.6
            override val beatDomain = false
        }
        val registry = DefaultStrategyRegistry(listOf(DeclaredCrossfade(), UndeclaredEcho()))
        val all = PrefsPatch(exclude = Technique.entries.toSet()).apply(TransitionPrefs())
        val ids = DefaultTransitionPlanner(registry).plan(a, b, all).candidates.map { it.strategy.id }
        assertTrue("crossfade" in ids, "the floor of the ladder stays: $ids")
        assertTrue("echoOut" in ids, "a strategy that declares nothing is not excluded by technique: $ids")
    }

    @Test
    fun exclusionsRoundTripThroughStyleFilesAndPrefs() {
        val style = CustomJson.json.decodeFromString(
            StyleProfile.serializer(),
            """{"id": "dry", "name": "Dry", "patch": {"exclude": ["echo", "reverb", "tempoGlide"]}}""",
        )
        assertEquals(setOf(Technique.ECHO, Technique.REVERB, Technique.TEMPO_GLIDE), style.patch.exclude)
        assertTrue(style.patch.describe().single().contains("echo, reverb, tempo glide"), style.patch.describe().toString())
        val prefs = style.apply(TransitionPrefs(excludedTechniques = setOf(Technique.STEMS)))
        assertEquals(setOf(Technique.STEMS, Technique.ECHO, Technique.REVERB, Technique.TEMPO_GLIDE), prefs.excludedTechniques, "exclusions add up")
        val json = CustomJson.json.encodeToString(TransitionPrefs.serializer(), prefs)
        assertEquals(prefs, CustomJson.json.decodeFromString(TransitionPrefs.serializer(), json))
        assertEquals(TransitionPrefs(), CustomJson.json.decodeFromString(TransitionPrefs.serializer(), "{}"), "old prefs files read as before")
        assertEquals(Technique.entries.toSet(), BuiltInStyles.byId("purist")!!.patch.exclude)
    }
}
