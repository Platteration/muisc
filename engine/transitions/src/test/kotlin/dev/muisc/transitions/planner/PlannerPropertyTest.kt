package dev.muisc.transitions.planner

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.Marker
import dev.muisc.transitions.ModifierParams
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionModifier
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.strategies.CrossfadeStrategy
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §5.4 planner properties over random synthetic analyses, against the shipped registry (which grows as strategies
 * are merged — the beat-domain assertion is by id) and against a fake registry that exercises every planner rule.
 */
class PlannerPropertyTest {
    private val n = 200

    /** Ids of the beat-domain family: never a candidate when the pair cannot be beat-matched. */
    private val beatDomainIds = StructTables.BEAT_DOMAIN_IDS

    // ---- fakes -------------------------------------------------------------------------------------------------

    /** A strategy with a configurable applicability; plans a small valid segment. */
    private open class FakeStrategy(
        override val id: String,
        val score: (PairFeatures, TransitionPrefs) -> Double,
        val block: (PairFeatures, TransitionPrefs) -> String? = { _, _ -> null },
        override val params: List<ParamSpec> = listOf(ParamSpec.IntSpec("bars", "Bars", 8, 1, 64)),
    ) : TransitionStrategy {
        override val displayName get() = id
        override val description get() = "fake $id"
        override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
            block(features, prefs)?.let { return Applicability.blocked(it) }
            return Applicability.of(score(features, prefs), "fake reason for $id")
        }
        override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
            val sr = prefs.sampleRate
            val exit = (a.trimEndFrame - 4L * sr).coerceAtLeast(0L)
            val entry = b.trimStartFrame + 2L * sr
            return TransitionPlan(id, params.resolve(this.params), exit, entry, FrameRange(exit, exit + 4L * sr), FrameRange(b.trimStartFrame, entry), 4 * sr + 2 * sr, notes = listOf("seed $seed"))
        }
        override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition = throw UnsupportedOperationException()
    }

    /** Beat-domain fake: the §4 gate of beatMatchedBlend. */
    private class FakeBeatDomain(id: String) : FakeStrategy(
        id,
        score = { f, p -> 0.5 + 0.5 * CompatibilityScores.tempo(f, p) },
        block = { f, p ->
            when {
                !f.beatMatchable -> "not beat-matchable"
                f.stretchPercent > p.maxStretchPercent -> "stretch ${f.stretchPercent} > ${p.maxStretchPercent}"
                else -> null
            }
        },
    )

    private class FakeModifier(override val id: String, val app: (TransitionStrategy) -> Double, val bonusNote: String = "adjusted by") : TransitionModifier {
        override val displayName get() = id
        override val params: List<ParamSpec> = listOf(ParamSpec.IntSpec("glideBars", "Glide", 16, 8, 64))
        override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, base: TransitionStrategy, prefs: TransitionPrefs): Double = app(base)
        override fun adjustPlan(plan: TransitionPlan, a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs): TransitionPlan =
            plan.copy(notes = plan.notes + "$bonusNote $id glideBars=${params.int(this.params[0] as ParamSpec.IntSpec)}", expectedOutputFrames = plan.expectedOutputFrames + 1000)
        override fun apply(rendered: RenderedTransition, input: TransitionInput, params: Params, ctx: RenderContext): RenderedTransition =
            RenderedTransition(rendered.plan, rendered.audio, rendered.markers + Marker(0, id), rendered.report)
    }

    private fun fakeRegistry(): DefaultStrategyRegistry = DefaultStrategyRegistry(
        strategies = listOf(
            CrossfadeStrategy(),
            FakeStrategy("ambientBridge", score = { f, _ -> maxOf(0.15, if (f.stretchPercent > 12) 0.6 else 0.2) }),
            FakeBeatDomain("beatMatchedBlend"),
            FakeBeatDomain("bassSwap"),
            FakeStrategy("phraseCut", score = { f, _ -> if (f.gridConfidenceA >= 0.5) 0.6 else 0.0 }),
            FakeStrategy("brakeStop", score = { _, p -> 0.7 }, block = { _, p -> if (p.energy < 0.5) "energy too low" else null }),
            FakeStrategy("loopRollRiser", score = { _, _ -> 0.5 }),
        ),
        modifiers = listOf(
            FakeModifier("tempoGlide", app = { base -> if (base.id in StructTables.BEAT_DOMAIN_IDS) 0.9 else 0.0 }),
            FakeModifier("textureCarry", app = { _ -> 0.2 }),   // below the 0.3 threshold: never attached
        ),
    )

    private fun pairs(seed: Int): Sequence<Pair<TrackRef, TrackRef>> = sequence {
        val rnd = Random(seed)
        for (i in 0 until n) {
            val a = TestAnalyses.random(rnd, "a$i")
            val b = TestAnalyses.random(rnd, "b$i")
            yield(TestAnalyses.ref(a) to TestAnalyses.ref(b))
        }
    }

    // ---- properties on the shipped registry --------------------------------------------------------------------

    @Test
    fun everyPairPlansWithTheShippedRegistry() {
        val prefs = TransitionPrefs()
        val planner = DefaultTransitionPlanner(DefaultStrategyRegistry.default())
        var beatDomainSeen = 0
        for ((a, b) in pairs(11)) {
            val r1 = planner.plan(a, b, prefs, seed = 3L)
            assertTrue(r1.candidates.isNotEmpty())
            assertTrue(r1.candidates.any { it.strategy.id == "crossfade" }, "crossfade always present (${a.id} → ${b.id})")
            val f = r1.features
            for (c in r1.candidates) {
                assertTrue(c.applicability.applicable, c.strategy.id)
                assertTrue(c.score >= 0.0 && c.score.isFinite())
                assertEquals(c.strategy.id, c.plan.strategyId)
                if (c.strategy.id in beatDomainIds) {
                    beatDomainSeen++
                    assertTrue(f.beatMatchable && f.stretchPercent <= prefs.maxStretchPercent, "${c.strategy.id} offered on a non-matchable pair: $f")
                }
                assertTrue(c.applicability.reasons.any { it.startsWith("scores: tempo ") }, "sub-scores in reasons: ${c.applicability.reasons}")
                assertTrue(c.applicability.reasons.any { it.startsWith("score ") && it.contains("= fit") }, "formula in reasons")
            }
            // best-first
            for (i in 1 until r1.candidates.size) assertTrue(r1.candidates[i - 1].score >= r1.candidates[i].score)
            // deterministic per seed
            val r2 = planner.plan(a, b, prefs, seed = 3L)
            assertEquals(r1.candidates.map { it.strategy.id }, r2.candidates.map { it.strategy.id })
            assertEquals(r1.candidates.map { it.plan }, r2.candidates.map { it.plan })
            assertEquals(r1.candidates.map { it.score }, r2.candidates.map { it.score })
        }
        // Not an assertion (the shipped registry may hold only the crossfade on a branch), just a sanity print.
        println("shipped registry: ${DefaultStrategyRegistry.default().strategyIds}, beat-domain candidates seen: $beatDomainSeen")
    }

    // ---- properties on the fake registry (exercise the rules) --------------------------------------------------

    @Test
    fun everyPairPlansAndGatesAreRespected() {
        val prefs = TransitionPrefs(energy = 0.7)
        val planner = DefaultTransitionPlanner(fakeRegistry())
        var matchable = 0
        var glides = 0
        for ((a, b) in pairs(21)) {
            val (ranked, explanation) = planner.planExplained(a, b, prefs, seed = 5L)
            val f = ranked.features
            val ids = ranked.candidates.map { it.strategy.id }
            assertTrue(ids.size >= 2, "crossfade + ambientBridge always qualify: $ids")
            assertTrue("crossfade" in ids && "ambientBridge" in ids)
            val bm = f.beatMatchable && f.stretchPercent <= prefs.maxStretchPercent
            assertEquals(bm, "beatMatchedBlend" in ids, "gate: $f")
            assertEquals(bm, "bassSwap" in ids)
            if (bm) matchable++
            for (c in ranked.candidates) {
                if (c.strategy.id in beatDomainIds) {
                    assertEquals(listOf("tempoGlide"), c.modifiers.map { it.id }, "glide attached to beat-domain strategies only")
                    assertEquals(listOf("tempoGlide"), c.plan.modifiers)
                    assertTrue(c.plan.notes.any { it.contains("adjusted by tempoGlide glideBars=16") })
                    assertEquals("16", c.plan.params[ModifierParams.key("tempoGlide", "glideBars")], "modifier params recorded in the plan")
                    glides++
                } else {
                    assertTrue(c.modifiers.isEmpty() && c.plan.modifiers.isEmpty(), "${c.strategy.id} has no modifiers")
                }
            }
            assertEquals(ids, explanation.ranked.map { it.strategyId })
            for (bd in explanation.ranked) {
                assertTrue(abs(bd.jitter - 1.0) <= 0.05 + 1e-12)
                assertEquals(bd.fit * bd.weight * bd.energyPref * bd.variety * bd.modifierBonus * bd.jitter, bd.score, 1e-12)
                assertEquals(1.0, bd.variety, 1e-12)
                if (bd.strategyId in beatDomainIds) assertEquals(1.0 + 0.15 * 0.9, bd.modifierBonus, 1e-12) else assertEquals(1.0, bd.modifierBonus, 1e-12)
            }
            val skippedIds = explanation.skipped.map { it.strategyId }
            assertTrue(ids.none { it in skippedIds })
            assertEquals(fakeRegistry().strategies.size, ids.size + explanation.skipped.size, "every strategy is either ranked or skipped")
            if (!bm) assertTrue(explanation.skipped.first { it.strategyId == "beatMatchedBlend" }.reason == "blocked")
            assertTrue(explanation.render().contains("1. "))
        }
        assertTrue(matchable in 3 until n - 3, "the random pairs cover both matchable ($matchable) and unmatchable pairs")
        assertTrue(glides > 0)
    }

    @Test
    fun deterministicPerSeedAndJitterIsBounded() {
        val prefs = TransitionPrefs()
        val planner = DefaultTransitionPlanner(fakeRegistry())
        val rnd = Random(99)
        val a = TestAnalyses.ref(TestAnalyses.random(rnd, "a"))
        val b = TestAnalyses.ref(TestAnalyses.random(rnd, "b"))
        val r1 = planner.planExplained(a, b, prefs, seed = 42L)
        val r2 = planner.planExplained(a, b, prefs, seed = 42L)
        assertEquals(r1.explanation, r2.explanation)
        assertEquals(r1.ranked.candidates.map { it.plan }, r2.ranked.candidates.map { it.plan })
        // Another seed: same set of ids, jitter differs for at least one strategy, every jitter within ±5 %.
        val r3 = planner.planExplained(a, b, prefs, seed = 43L)
        assertEquals(r1.ranked.candidates.map { it.strategy.id }.toSet(), r3.ranked.candidates.map { it.strategy.id }.toSet())
        val j1 = r1.explanation.ranked.associate { it.strategyId to it.jitter }
        val j3 = r3.explanation.ranked.associate { it.strategyId to it.jitter }
        assertTrue(j1.keys.any { j1[it] != j3[it] })
        for (v in j1.values + j3.values) assertTrue(abs(v - 1.0) <= 0.05)
        // Jitter differs between strategies for the same pair/seed (otherwise it could never reorder anything).
        assertTrue(j1.values.toSet().size > 1)
        assertEquals(DefaultTransitionPlanner.jitter("x", "y", 1, "s"), DefaultTransitionPlanner.jitter("x", "y", 1, "s"), 0.0)
        assertTrue(DefaultTransitionPlanner.jitter("x", "y", 1, "s") != DefaultTransitionPlanner.jitter("y", "x", 1, "s"))
    }

    @Test
    fun previousStrategyLowersItsScoreAndCooldownZeroesIt() {
        val prefs = TransitionPrefs(varietyPenalty = 0.3, energy = 0.8)
        val planner = DefaultTransitionPlanner(fakeRegistry())
        val rnd = Random(7)
        var checked = 0
        repeat(40) {
            val a = TestAnalyses.ref(TestAnalyses.random(rnd, "a$it"))
            val b = TestAnalyses.ref(TestAnalyses.random(rnd, "b$it"))
            val base = planner.planExplained(a, b, prefs, seed = 1L)
            for (bd in base.explanation.ranked) {
                val again = planner.planExplained(a, b, prefs, seed = 1L, previousStrategyId = bd.strategyId)
                val after = assertNotNull(again.explanation.breakdown(bd.strategyId))
                if (bd.strategyId in DefaultTransitionPlanner.COOLDOWN_IDS) {
                    assertEquals(0.0, after.variety, 0.0); assertEquals(0.0, after.score, 0.0)
                } else {
                    assertEquals(0.7, after.variety, 1e-12)
                    assertEquals(bd.score * 0.7, after.score, 1e-12)
                }
                // Everyone else is untouched.
                for (other in base.explanation.ranked) if (other.strategyId != bd.strategyId) assertEquals(other.score, again.explanation.breakdown(other.strategyId)!!.score, 1e-12)
                checked++
            }
        }
        assertTrue(checked > 100)
        assertEquals(1.0, DefaultTransitionPlanner.variety("x", null, 0.5), 0.0)
        assertEquals(0.0, DefaultTransitionPlanner.variety("x", "x", 1.0), 0.0)
    }

    @Test
    fun weightsEnergyDisabledAndOverrides() {
        val a = TestAnalyses.ref(TestAnalyses.simple("a", bpm = 120.0))
        val b = TestAnalyses.ref(TestAnalyses.simple("b", bpm = 124.0))
        val planner = DefaultTransitionPlanner(fakeRegistry())
        val base = planner.planExplained(a, b, TransitionPrefs(energy = 0.9), 0L)
        val ids = base.ranked.candidates.map { it.strategy.id }
        assertTrue("beatMatchedBlend" in ids && "brakeStop" in ids, ids.toString())

        // Strategy weight scales the score linearly.
        val weighted = planner.planExplained(a, b, TransitionPrefs(energy = 0.9, strategyWeights = mapOf("phraseCut" to 2.0)), 0L)
        assertEquals(2.0 * base.explanation.breakdown("phraseCut")!!.score, weighted.explanation.breakdown("phraseCut")!!.score, 1e-12)
        assertEquals(2.0, weighted.explanation.breakdown("phraseCut")!!.weight, 0.0)

        // Energy preference follows the ambition table.
        for (bd in base.explanation.ranked) assertEquals(DefaultTransitionPlanner.energyPreference(bd.strategyId, 0.9), bd.energyPref, 1e-12)
        assertEquals(1.0 - 0.8 * 0.5, DefaultTransitionPlanner.energyPreference("crossfade", 0.9), 1e-12)
        assertEquals(1.0, DefaultTransitionPlanner.energyPreference("brakeStop", 0.9), 1e-12)
        assertEquals(1.0 - 0.5 * 0.5, DefaultTransitionPlanner.energyPreference("unknownStrategy", 1.0), 1e-12)
        // Low energy: brakeStop blocks itself (its own gate) and crossfade's energy preference improves.
        val calm = planner.planExplained(a, b, TransitionPrefs(energy = 0.1), 0L)
        assertFalse(calm.ranked.candidates.any { it.strategy.id == "brakeStop" })
        assertTrue(calm.explanation.breakdown("crossfade")!!.energyPref > base.explanation.breakdown("crossfade")!!.energyPref)

        // Disabled strategies vanish; the crossfade cannot be disabled.
        val disabled = planner.planExplained(a, b, TransitionPrefs(energy = 0.9, disabledStrategies = setOf("beatMatchedBlend", "crossfade", "tempoGlide")), 0L)
        val dIds = disabled.ranked.candidates.map { it.strategy.id }
        assertFalse("beatMatchedBlend" in dIds)
        assertTrue("crossfade" in dIds)
        assertTrue(disabled.explanation.skipped.any { it.strategyId == "beatMatchedBlend" && it.reason == "disabled in prefs" })
        assertTrue(disabled.ranked.candidates.all { it.modifiers.isEmpty() }, "a disabled modifier is never attached")

        // Param overrides reach the plan (and modifier overrides reach the modifier).
        val overridden = planner.planExplained(a, b, TransitionPrefs(energy = 0.9, paramOverrides = mapOf("phraseCut" to mapOf("bars" to "12"), "crossfade" to mapOf("fadeSec" to "3"), "tempoGlide" to mapOf("glideBars" to "32"))), 0L)
        assertEquals("12", overridden.ranked.candidates.first { it.strategy.id == "phraseCut" }.plan.params["bars"])
        assertEquals(3.0, overridden.ranked.candidates.first { it.strategy.id == "crossfade" }.plan.params.double(CrossfadeStrategy.P.fadeSec), 0.0)
        val bmb = overridden.ranked.candidates.first { it.strategy.id == "beatMatchedBlend" }
        assertTrue(bmb.plan.notes.any { it.endsWith("glideBars=32") }, bmb.plan.notes.toString())
        assertEquals("32", bmb.plan.params["tempoGlide.glideBars"])

        // The explanation lists the modifier and its applicability.
        val reasons = bmb.applicability.reasons
        assertTrue(reasons.any { it == "modifiers: tempoGlide (0.90)" }, reasons.toString())
        assertTrue(reasons.first() == "fake reason for beatMatchedBlend")
    }

    @Test
    fun strategyThatFailsToPlanIsSkippedNotFatal_andNoFloorIsAnError() {
        val a = TestAnalyses.ref(TestAnalyses.simple("a"))
        val b = TestAnalyses.ref(TestAnalyses.simple("b"))
        val broken = object : FakeStrategy("broken", score = { _, _ -> 0.9 }) {
            override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan = throw IllegalStateException("boom")
        }
        val planner = DefaultTransitionPlanner(DefaultStrategyRegistry(listOf(CrossfadeStrategy(), broken)))
        val r = planner.planExplained(a, b, TransitionPrefs(), 0L)
        assertEquals(listOf("crossfade"), r.ranked.candidates.map { it.strategy.id })
        assertEquals("plan() failed: boom", r.explanation.skipped.single().reason)

        val alwaysBlocked = FakeStrategy("nope", score = { _, _ -> 1.0 }, block = { _, _ -> "never" })
        val noFloor = DefaultTransitionPlanner(DefaultStrategyRegistry(listOf(alwaysBlocked)))
        val e = assertFailsWith<IllegalStateException> { noFloor.plan(a, b, TransitionPrefs()) }
        assertTrue(e.message!!.contains("nope"))
    }

    @Test
    fun registryHelpers() {
        val reg = DefaultStrategyRegistry.default()
        assertEquals("crossfade", reg.strategies.first().id)
        assertNotNull(reg.strategy("crossfade"))
        val ext = reg.withStrategies(FakeStrategy("phraseCut", score = { _, _ -> 0.5 })).withModifiers(FakeModifier("tempoGlide", { 0.0 }))
        assertEquals(reg.strategies.size + 1, ext.strategies.size)
        assertEquals(listOf("tempoGlide"), ext.modifierIds)
        // Replacing an id keeps its position; removing works; duplicates are rejected.
        val replaced = ext.withStrategies(FakeStrategy("crossfade", score = { _, _ -> 0.5 }))
        assertEquals(ext.strategyIds, replaced.strategyIds)
        assertTrue(replaced.strategy("crossfade") is FakeStrategy)
        assertEquals(reg.strategyIds, ext.without("phraseCut").strategyIds)
        assertFailsWith<IllegalArgumentException> { DefaultStrategyRegistry(listOf(CrossfadeStrategy(), CrossfadeStrategy())) }
        assertTrue(DefaultStrategyRegistry.empty().strategies.isEmpty())
    }
}
