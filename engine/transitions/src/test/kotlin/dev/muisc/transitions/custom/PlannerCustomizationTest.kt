package dev.muisc.transitions.custom

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionModifier
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.planner.DefaultTransitionPlanner
import dev.muisc.transitions.planner.TestAnalyses
import dev.muisc.transitions.sdk.StrategyTraits
import dev.muisc.transitions.strategies.CrossfadeStrategy
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The planner hooks: presets, pins, learned weights and strategy traits, and that none of them leak when unused. */
class PlannerCustomizationTest {

    private open class Fake(
        override val id: String,
        val fit: Double,
        val block: (PairFeatures) -> String? = { null },
    ) : TransitionStrategy {
        override val displayName get() = id
        override val description get() = "fake $id"
        override val params: List<ParamSpec> = listOf(
            ParamSpec.IntSpec("bars", "Bars", 8, 1, 64),
            ParamSpec.DoubleSpec("hz", "Hz", 200.0, 20.0, 2000.0),
            ParamSpec.BoolSpec("flag", "Flag", false),
        )
        override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
            block(features)?.let { return Applicability.blocked(it) }
            return Applicability.of(fit, "fake reason for $id")
        }
        override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
            val sr = prefs.sampleRate
            val exit = (a.trimEndFrame - 4L * sr).coerceAtLeast(0L)
            val entry = b.trimStartFrame + 2L * sr
            return TransitionPlan(id, params.resolve(this.params), exit, entry, FrameRange(exit, exit + 4L * sr), FrameRange(b.trimStartFrame, entry), 6 * sr)
        }
        override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition = throw UnsupportedOperationException()
    }

    private class Traited(id: String, fit: Double, override val ambition: Double) : Fake(id, fit), StrategyTraits {
        override val beatDomain: Boolean = false
    }

    private class AlwaysModifier(override val id: String) : TransitionModifier {
        override val displayName get() = id
        override val params: List<ParamSpec> = emptyList()
        override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, base: TransitionStrategy, prefs: TransitionPrefs) = 0.9
        override fun adjustPlan(plan: TransitionPlan, a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs) = plan
        override fun apply(rendered: RenderedTransition, input: TransitionInput, params: Params, ctx: RenderContext) = rendered
    }

    /** `matched` is blocked on unmatchable pairs; `low` always scores lowest; `high` always highest. */
    private fun registry() = DefaultStrategyRegistry(
        strategies = listOf(
            CrossfadeStrategy(),
            Fake("high", 0.95),
            Fake("mid", 0.6),
            Fake("low", 0.12),
            Fake("matched", 0.8, block = { f -> if (!f.beatMatchable) "not beat-matchable" else null }),
        ),
        modifiers = listOf(AlwaysModifier("glide")),
    )

    private val a = TestAnalyses.ref(TestAnalyses.simple("a", bpm = 120.0))
    private val b = TestAnalyses.ref(TestAnalyses.simple("b", bpm = 124.0))
    private val lowConfB = TestAnalyses.ref(TestAnalyses.simple("b2", bpm = 124.0, confidence = 0.2f))
    private val prefs = TransitionPrefs()

    // ---- no customization ---------------------------------------------------------------------------------------

    @Test
    fun emptyCustomizationChangesNothing(@TempDir dir: File) {
        val profile = UserProfile(dir)
        val plain = DefaultTransitionPlanner(DefaultStrategyRegistry.default())
        val custom = DefaultTransitionPlanner(DefaultStrategyRegistry.default(), customization = profile.customization())
        val rnd = Random(5)
        repeat(30) { i ->
            val x = TestAnalyses.ref(TestAnalyses.random(rnd, "x$i"))
            val y = TestAnalyses.ref(TestAnalyses.random(rnd, "y$i"))
            val p = plain.planExplained(x, y, prefs, seed = 9L)
            val c = custom.planExplained(x, y, prefs, seed = 9L)
            assertEquals(p.explanation, c.explanation)
            assertEquals(p.ranked.candidates.map { it.plan }, c.ranked.candidates.map { it.plan })
            assertEquals(p.ranked.candidates.map { it.applicability }, c.ranked.candidates.map { it.applicability })
            assertNull(c.explanation.pin)
            assertTrue(c.explanation.notes.isEmpty())
        }
        assertFalse(File(dir, "pins.json").exists() || File(dir, "feedback.json").exists(), "planning never writes the profile")
    }

    // ---- presets ------------------------------------------------------------------------------------------------

    @Test
    fun activePresetSuppliesParamsAndExplicitOverridesWin() {
        val presets = InMemoryPresetStore(listOf(StrategyPreset("mid-wide", "Wide mid", "mid", Params(mapOf("bars" to "32", "hz" to "900")))))
        val planner = DefaultTransitionPlanner(registry(), customization = PlannerCustomization(presets = presets.withBuiltIns()))

        val withPreset = planner.planExplained(a, b, prefs.copy(activePresets = mapOf("mid" to "mid-wide")))
        val mid = withPreset.ranked.candidates.first { it.strategy.id == "mid" }
        assertEquals("32", mid.plan.params["bars"])
        assertEquals("900", mid.plan.params["hz"])
        assertEquals("false", mid.plan.params["flag"], "untouched params keep their defaults")
        assertTrue(mid.applicability.reasons.contains("preset 'mid-wide' (Wide mid) active for mid"), mid.applicability.reasons.toString())
        // Other strategies are unaffected.
        assertEquals("8", withPreset.ranked.candidates.first { it.strategy.id == "high" }.plan.params["bars"])

        val overridden = planner.planExplained(a, b, prefs.copy(activePresets = mapOf("mid" to "mid-wide"), paramOverrides = mapOf("mid" to mapOf("bars" to "12"))))
        val mid2 = overridden.ranked.candidates.first { it.strategy.id == "mid" }
        assertEquals("12", mid2.plan.params["bars"], "paramOverrides beat the active preset")
        assertEquals("900", mid2.plan.params["hz"], "the preset still supplies what the overrides do not set")

        // The presets change params only: scores are identical.
        val base = planner.planExplained(a, b, prefs)
        assertEquals(base.explanation.ranked.map { it.score }, withPreset.explanation.ranked.map { it.score })
    }

    @Test
    fun missingOrMismatchedPresetIsNotedAndIgnored() {
        val planner = DefaultTransitionPlanner(registry())
        val r = planner.planExplained(a, b, prefs.copy(activePresets = mapOf("mid" to "no-such-preset", "high" to "tight-bass-swap")))
        assertEquals("8", r.ranked.candidates.first { it.strategy.id == "mid" }.plan.params["bars"])
        assertTrue(r.explanation.notes.any { it.contains("'no-such-preset'") && it.contains("not found") }, r.explanation.notes.toString())
        assertTrue(r.explanation.notes.any { it.contains("'tight-bass-swap' is for bassSwap, not high") }, r.explanation.notes.toString())
        assertTrue(r.explanation.render().contains("note: active preset 'no-such-preset'"))
    }

    @Test
    fun builtInPresetResolvesWithoutAnyCustomization() {
        val reg = DefaultStrategyRegistry(listOf(CrossfadeStrategy()))
        val r = DefaultTransitionPlanner(reg).planExplained(a, b, prefs.copy(activePresets = mapOf("crossfade" to "quick-crossfade")))
        assertEquals(3.0, r.ranked.best.plan.params.double(CrossfadeStrategy.P.fadeSec), 0.0)
    }

    @Test
    fun presetModifierListRestrictsTheModifiers() {
        val presets = InMemoryPresetStore(listOf(
            StrategyPreset("mid-dry", "Dry", "mid", modifiers = emptyList()),
            StrategyPreset("mid-glide", "Glide", "mid", modifiers = listOf("glide")),
        ))
        val planner = DefaultTransitionPlanner(registry(), customization = PlannerCustomization(presets = presets.withBuiltIns()))
        val free = planner.plan(a, b, prefs).candidates.first { it.strategy.id == "mid" }
        assertEquals(listOf("glide"), free.plan.modifiers)
        val dry = planner.plan(a, b, prefs.copy(activePresets = mapOf("mid" to "mid-dry"))).candidates.first { it.strategy.id == "mid" }
        assertTrue(dry.plan.modifiers.isEmpty() && dry.modifiers.isEmpty())
        val glide = planner.plan(a, b, prefs.copy(activePresets = mapOf("mid" to "mid-glide"))).candidates.first { it.strategy.id == "mid" }
        assertEquals(listOf("glide"), glide.plan.modifiers)
    }

    @Test
    fun foldPutsPresetValuesUnderExplicitOverridesAndIsIdempotent() {
        val lookup = InMemoryPresetStore(listOf(StrategyPreset("mid-wide", "Wide", "mid", Params(mapOf("bars" to "32", "hz" to "900"))))).withBuiltIns()
        val p = prefs.copy(activePresets = mapOf("mid" to "mid-wide"), paramOverrides = mapOf("mid" to mapOf("bars" to "12")))
        val folded = PresetResolution.fold(p, lookup)
        assertEquals(mapOf("bars" to "12", "hz" to "900"), folded.paramOverrides["mid"])
        assertEquals(folded, PresetResolution.fold(folded, lookup))
        // A forced preset (CLI --preset) wins over explicit overrides for its strategy.
        val forced = PresetResolution.fold(p, lookup, lookup.preset("mid-wide"))
        assertEquals(mapOf("bars" to "32", "hz" to "900"), forced.paramOverrides["mid"])
        assertEquals(forced, PresetResolution.fold(forced, lookup, lookup.preset("mid-wide")))
        // The planner gives the same params for folded and unfolded prefs.
        val planner = DefaultTransitionPlanner(registry(), customization = PlannerCustomization(presets = lookup))
        assertEquals(
            planner.plan(a, b, p).candidates.map { it.plan },
            planner.plan(a, b, folded).candidates.map { it.plan },
        )
    }

    // ---- pins ---------------------------------------------------------------------------------------------------

    @Test
    fun pinIsRankedFirstWithItsPresetAndParams() {
        val presets = InMemoryPresetStore(listOf(StrategyPreset("low-long", "Long low", "low", Params(mapOf("bars" to "40", "hz" to "300")), modifiers = emptyList())))
        val pins = InMemoryPinStore(listOf(PairPin(a.analysis.fingerprint, b.analysis.fingerprint, "low", presetId = "low-long", params = Params(mapOf("hz" to "500")))))
        val planner = DefaultTransitionPlanner(registry(), customization = PlannerCustomization(presets = presets.withBuiltIns(), pins = pins))
        val base = DefaultTransitionPlanner(registry()).planExplained(a, b, prefs)
        assertNotEquals("low", base.ranked.best.strategy.id)

        val r = planner.planExplained(a, b, prefs.copy(paramOverrides = mapOf("low" to mapOf("hz" to "100", "flag" to "true"))))
        val best = r.ranked.best
        assertEquals("low", best.strategy.id)
        assertEquals("40", best.plan.params["bars"], "the pin's preset")
        assertEquals("500", best.plan.params["hz"], "the pin's own params beat the preset and the overrides")
        assertEquals("true", best.plan.params["flag"], "overrides still fill what the pin does not set")
        assertTrue(best.plan.modifiers.isEmpty(), "the pin preset's modifier list applies")
        assertTrue(r.explanation.ranked.first().pinned)
        val pin = assertNotNull(r.explanation.pin)
        assertTrue(pin.used)
        assertTrue(pin.reason.startsWith("pinned by you"), pin.reason)
        assertTrue(pin.reason.contains("its score alone ranks it #"), pin.reason)
        assertTrue(best.applicability.reasons.contains("pinned by you"), best.applicability.reasons.toString())
        assertTrue(best.applicability.reasons.any { it.contains("· pinned (ranked first)") })
        assertTrue(r.explanation.render().contains("pin: pinned by you"))
        // The rest keeps its score order and its scores.
        val rest = r.explanation.ranked.drop(1)
        for (i in 1 until rest.size) assertTrue(rest[i - 1].score >= rest[i].score)
        assertEquals(base.explanation.breakdown("high")!!.score, r.explanation.breakdown("high")!!.score, 0.0)
        // Pins are directional: B → A is not pinned.
        val back = planner.planExplained(b, a, prefs)
        assertNull(back.explanation.pin)
        assertNotEquals("low", back.ranked.best.strategy.id)
    }

    @Test
    fun blockedPinFallsBackToTheNormalRankingAndSaysWhy() {
        val pins = InMemoryPinStore(listOf(PairPin(a.analysis.fingerprint, lowConfB.analysis.fingerprint, "matched")))
        val planner = DefaultTransitionPlanner(registry(), customization = PlannerCustomization(pins = pins))
        val plain = DefaultTransitionPlanner(registry()).planExplained(a, lowConfB, prefs)
        val r = planner.planExplained(a, lowConfB, prefs)
        assertEquals(plain.ranked.candidates.map { it.strategy.id }, r.ranked.candidates.map { it.strategy.id })
        assertEquals(plain.explanation.ranked, r.explanation.ranked)
        val pin = assertNotNull(r.explanation.pin)
        assertFalse(pin.used)
        assertTrue(pin.reason.contains("pinned matched not used") && pin.reason.contains("blocked") && pin.reason.contains("not beat-matchable"), pin.reason)
        assertTrue(r.explanation.render().contains("pin: pinned matched not used"))
    }

    @Test
    fun disabledOrUnknownPinFallsBack() {
        val disabledPin = DefaultTransitionPlanner(registry(), customization = PlannerCustomization(pins = PinLookup.always(PairPin("x", "y", "low"))))
        val r1 = disabledPin.planExplained(a, b, prefs.copy(disabledStrategies = setOf("low")))
        assertFalse(r1.explanation.pin!!.used)
        assertTrue(r1.explanation.pin!!.reason.contains("disabled in prefs"), r1.explanation.pin!!.reason)

        val unknown = DefaultTransitionPlanner(registry(), customization = PlannerCustomization(pins = PinLookup.always(PairPin("x", "y", "recipe:gone"))))
        val r2 = unknown.planExplained(a, b, prefs)
        assertFalse(r2.explanation.pin!!.used)
        assertTrue(r2.explanation.pin!!.reason.contains("no strategy 'recipe:gone' is registered"))
        assertEquals("high", r2.ranked.best.strategy.id)
    }

    @Test
    fun sessionPinFromTheProfileAppliesToEveryPairAndBeatsStoredPins(@TempDir dir: File) {
        val profile = UserProfile(dir)
        profile.pins.set(PairPin(a.analysis.fingerprint, b.analysis.fingerprint, "mid"))
        val stored = DefaultTransitionPlanner(registry(), customization = profile.customization())
        assertEquals("mid", stored.plan(a, b, prefs).best.strategy.id)
        val session = DefaultTransitionPlanner(registry(), customization = profile.customization(PairPin(PairPin.ANY, PairPin.ANY, "low", note = "--preset")))
        assertEquals("low", session.plan(a, b, prefs).best.strategy.id)
        assertEquals("low", session.plan(b, a, prefs).best.strategy.id)
        assertTrue(session.planExplained(a, b, prefs).explanation.pin!!.reason.startsWith("pinned by you (--preset)"))
    }

    // ---- learned weights ------------------------------------------------------------------------------------------

    @Test
    fun learnedFactorMultipliesTheScoreAndIsExplained() {
        val learner = FeedbackLearner()
        val features = DefaultTransitionPlanner(registry()).planExplained(a, b, prefs).ranked.features
        val bucket = ContextBucket.of(features)
        assertEquals("matched, in key, rising", bucket.label)
        repeat(5) { learner.record("mid", bucket, Rating.Up) }
        val base = DefaultTransitionPlanner(registry()).planExplained(a, b, prefs, seed = 4L)
        val r = DefaultTransitionPlanner(registry(), customization = PlannerCustomization(learner = learner)).planExplained(a, b, prefs, seed = 4L)
        val factor = FeedbackLearner.multiplier(RatingTally(5, 5.0))
        val mid = r.explanation.breakdown("mid")!!
        assertEquals(factor, mid.learned, 0.0)
        assertEquals(base.explanation.breakdown("mid")!!.score * factor, mid.score, 1e-12)
        assertEquals(mid.fit * mid.weight * mid.energyPref * mid.variety * mid.modifierBonus * mid.jitter * mid.learned, mid.score, 1e-12)
        assertEquals("learned ×1.28 from 5 ratings in 'matched, in key, rising'", mid.learnedNote)
        assertTrue(mid.formula().endsWith("× learned 1.28"), mid.formula())
        val cand = r.ranked.candidates.first { it.strategy.id == "mid" }
        assertTrue(cand.applicability.reasons.contains("learned ×1.28 from 5 ratings in 'matched, in key, rising'"))
        assertTrue(r.explanation.render().contains("learned ×1.28 from 5 ratings"))
        // Strategies without ratings are untouched.
        assertEquals(base.explanation.breakdown("high")!!.score, r.explanation.breakdown("high")!!.score, 0.0)
        assertNull(r.explanation.breakdown("high")!!.learnedNote)
        // Ratings in another bucket do not apply to this pair.
        val other = FeedbackLearner().apply { repeat(5) { record("mid", ContextBucket(false, false, false), Rating.Down) } }
        val r2 = DefaultTransitionPlanner(registry(), customization = PlannerCustomization(learner = other)).planExplained(a, b, prefs, seed = 4L)
        assertEquals(base.explanation, r2.explanation)
    }

    @Test
    fun enoughDownVotesDemoteTheFavourite() {
        val features = DefaultTransitionPlanner(registry()).plan(a, b, prefs).features
        val learner = FeedbackLearner()
        repeat(30) { learner.record("high", features, Rating.Down) }
        val r = DefaultTransitionPlanner(registry(), customization = PlannerCustomization(learner = learner)).plan(a, b, prefs)
        assertNotEquals("high", r.best.strategy.id)
        assertTrue(r.candidates.any { it.strategy.id == "high" }, "learned weights demote; they never remove")
    }

    // ---- traits and determinism -------------------------------------------------------------------------------

    @Test
    fun ambitionComesFromStrategyTraitsWhenPresent() {
        val reg = DefaultStrategyRegistry(listOf(CrossfadeStrategy(), Traited("recipe:showy", 0.5, ambition = 0.9), Fake("recipe:plain", 0.5)))
        val r = DefaultTransitionPlanner(reg).planExplained(a, b, prefs.copy(energy = 0.9))
        assertEquals(1.0, r.explanation.breakdown("recipe:showy")!!.energyPref, 1e-12)
        assertEquals(1.0 - 0.4 * 0.5, r.explanation.breakdown("recipe:plain")!!.energyPref, 1e-12, "no traits: DEFAULT_AMBITION")
        assertEquals(0.9, DefaultTransitionPlanner.ambitionOf(Traited("t", 0.5, 0.9)), 0.0)
        assertEquals(1.0, DefaultTransitionPlanner.ambitionOf(Traited("t", 0.5, 7.0)), 0.0, "clamped")
        assertEquals(0.5, DefaultTransitionPlanner.ambitionOf(Traited("t", 0.5, Double.NaN)), 0.0, "NaN falls back")
        assertEquals(0.1, DefaultTransitionPlanner.ambitionOf(CrossfadeStrategy()), 0.0)
    }

    @Test
    fun customizedPlanningIsDeterministic() {
        val learner = FeedbackLearner()
        val rnd = Random(3)
        val features = DefaultTransitionPlanner(registry()).plan(a, b, prefs).features
        repeat(12) { learner.record(listOf("mid", "high", "low")[rnd.nextInt(3)], features, Rating.Stars(1 + rnd.nextInt(5))) }
        val c = PlannerCustomization(pins = InMemoryPinStore(listOf(PairPin(a.analysis.fingerprint, b.analysis.fingerprint, "low"))), learner = learner)
        val p1 = DefaultTransitionPlanner(registry(), customization = c).planExplained(a, b, prefs, seed = 11L)
        val p2 = DefaultTransitionPlanner(registry(), customization = c).planExplained(a, b, prefs, seed = 11L)
        assertEquals(p1.explanation, p2.explanation)
        assertEquals(p1.ranked.candidates.map { it.plan }, p2.ranked.candidates.map { it.plan })
    }
}
