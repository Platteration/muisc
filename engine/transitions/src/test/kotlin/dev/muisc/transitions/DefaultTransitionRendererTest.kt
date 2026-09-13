package dev.muisc.transitions

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.stems.StemQuality
import dev.muisc.dsp.stems.StemSeparator
import dev.muisc.dsp.stems.Stems
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.core.SpliceCheck
import dev.muisc.transitions.planner.DefaultTransitionPlanner
import dev.muisc.transitions.strategies.CrossfadeStrategy
import dev.muisc.transitions.synthetic.SyntheticTrackLoader
import dev.muisc.transitions.synthetic.SyntheticTracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DefaultTransitionRendererTest {
    private val songA = SyntheticSong(bpm = 120.0, tonic = 0, mode = dev.muisc.audio.synth.Mode.MAJOR, bars = 16, introBars = 4, outroBars = 4)
    private val songB = SyntheticSong(bpm = 126.0, tonic = 9, mode = dev.muisc.audio.synth.Mode.MINOR, bars = 16, introBars = 4, outroBars = 4)
    private val loader by lazy { SyntheticTrackLoader().also { it.register(songA, "A"); it.register(songB, "B") } }
    private val a by lazy { loader["A"]!! }
    private val b by lazy { loader["B"]!! }
    private val prefs by lazy { TransitionPrefs(targetLufs = minOf(a.analysis.loudness.integratedLufs, b.analysis.loudness.integratedLufs).toDouble() - 3.0) }
    // The full catalogue's strategies, but no modifiers: these tests exercise the renderer's own modifier plumbing
    // with fakes, so the real auto-attaching modifiers (tempoGlide/textureCarry) must not join the candidate.
    private val registry = DefaultStrategyRegistry(DefaultStrategyRegistry.default().strategies)
    private val planner by lazy { DefaultTransitionPlanner(registry) }

    /** A separator that counts calls and returns trivially valid stems (everything in `other`). */
    private class CountingSeparator : StemSeparator {
        var calls = 0
        override val quality get() = StemQuality.PSEUDO
        override fun separate(audio: AudioBuffer): Stems {
            calls++
            val z = { AudioBuffer.silence(audio.sampleRate, audio.channelCount, audio.frames) }
            return Stems(audio.sampleRate, z(), z(), z(), audio.copy(), StemQuality.PSEUDO)
        }
    }

    /** Appends a marker with its id and the `tag` param it received; records the apply order. */
    private class TagModifier(override val id: String, val log: MutableList<String>) : TransitionModifier {
        override val displayName get() = id
        override val params: List<ParamSpec> = listOf(ParamSpec.ChoiceSpec("tag", "Tag", "x", listOf("x", "y", "z")))
        override fun applicability(features: PairFeatures, a: dev.muisc.analysis.model.TrackAnalysis, b: dev.muisc.analysis.model.TrackAnalysis, base: TransitionStrategy, prefs: TransitionPrefs) = 1.0
        override fun adjustPlan(plan: TransitionPlan, a: dev.muisc.analysis.model.TrackAnalysis, b: dev.muisc.analysis.model.TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs) = plan
        override fun apply(rendered: RenderedTransition, input: TransitionInput, params: Params, ctx: RenderContext): RenderedTransition {
            val tag = params.choice(this.params[0] as ParamSpec.ChoiceSpec)
            log += "$id:$tag"
            return RenderedTransition(rendered.plan, rendered.audio, rendered.markers + Marker(rendered.markers.size.toLong(), "$id:$tag"), rendered.report)
        }
    }

    private fun crossfadeCandidate(seed: Long = 0L): Pair<PlanCandidate, PairFeatures> {
        val ranked = planner.plan(a.trackRef, b.trackRef, prefs, seed)
        return ranked.candidates.first { it.strategy.id == "crossfade" } to ranked.features
    }

    @Test
    fun rendersCrossfadeWithDeckGainSpliceCheckAndRenderKey() {
        val (cand, features) = crossfadeCandidate(seed = 7L)
        val progress = ArrayList<Double>()
        val ctx = RenderContext(prefs, seed = 7L, progress = { progress += it })
        val renderer = DefaultTransitionRenderer(loader, registry, CountingSeparator())
        val rendered = renderer.render(a.trackRef, b.trackRef, cand, features, ctx)
        val plan = cand.plan
        assertSame(plan, rendered.plan)
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames)

        // Deck gain applied: the dry pre-roll is A's source × gainA, the post-roll B's source × gainB.
        val ga = DeckGain.linear(DeckGain.of(a.analysis, prefs)); val gb = DeckGain.linear(DeckGain.of(b.analysis, prefs))
        assertTrue(ga < 0.75f && gb < 0.75f, "both decks attenuated ($ga / $gb)")
        for (i in 0 until Splice.GUARD_FRAMES step 97) {
            assertEquals(a.audio[0][plan.aExitFrame.toInt() + i] * ga, rendered.audio[0][i], 1e-6f, "pre-roll frame $i")
        }
        val n = rendered.audio.frames
        for (i in 1..Splice.GUARD_FRAMES step 97) {
            assertEquals(b.audio[1][(plan.bEntryFrame - i).toInt()] * gb, rendered.audio[1][n - i], 1e-6f, "post-roll frame -$i")
        }
        // SpliceCheck clean: no "splice:" warnings and the check itself agrees on a reconstructed input.
        assertTrue(rendered.report.warnings.none { it.startsWith("splice:") }, rendered.report.warnings.toString())
        assertTrue(rendered.report.warnings.none { it.startsWith("click") }, rendered.report.warnings.toString())
        val aAudio = loader.load(a.trackRef, plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
        val bAudio = loader.load(b.trackRef, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
        val input = TransitionInput(plan, a.trackRef, b.trackRef, features, aAudio, bAudio, LazyStemProvider(aAudio, bAudio, CountingSeparator(), StemNeed.NONE))
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input))

        // Render key: stamped, 64 hex digits, reproducible from the inputs.
        val key = rendered.report.renderKey
        assertEquals(64, key.length); assertTrue(key.all { it in "0123456789abcdef" }, key)
        assertEquals(RenderKey.compute(a.analysis, b.analysis, plan, prefs, 7L), key)
        assertTrue(RenderKey.compute(a.analysis, b.analysis, plan, prefs, 8L) != key, "seed is part of the key")
        assertTrue(RenderKey.compute(a.analysis, b.analysis, plan, prefs.copy(targetLufs = -20.0), 7L) != key, "targetLufs is part of the key")
        assertEquals(key, RenderKey.compute(a.analysis, b.analysis, plan, prefs.copy(energy = 0.9, varietyPenalty = 0.0), 7L), "planning-only prefs are not")
        assertTrue(RenderKey.material(a.analysis.fingerprint, b.analysis.fingerprint, plan, prefs, 7L).contains("\"fadeSec\":\"6.0\""))
        // Report metrics / peak carried over from the strategy's report.
        assertTrue(rendered.report.metrics.containsKey("fadeFrames"))
        assertTrue(rendered.report.peak > 0f)
        // Progress: starts at 0, ends at 1, never decreases.
        assertEquals(0.0, progress.first()); assertEquals(1.0, progress.last())
        for (i in 1 until progress.size) assertTrue(progress[i] >= progress[i - 1], progress.toString())
        assertTrue(progress.any { it in 0.1..0.8 })
    }

    @Test
    fun deterministicAcrossRenderersAndSeeds() {
        val (cand, features) = crossfadeCandidate(seed = 1L)
        val r1 = DefaultTransitionRenderer(loader, registry, CountingSeparator()).render(a.trackRef, b.trackRef, cand, features, RenderContext(prefs, 1L))
        val r2 = DefaultTransitionRenderer(loader, registry, CountingSeparator()).render(a.trackRef, b.trackRef, cand, features, RenderContext(prefs, 1L))
        for (c in 0 until 2) assertTrue(r1.audio[c].contentEquals(r2.audio[c]), "bit-identical")
        assertEquals(r1.report.renderKey, r2.report.renderKey)
        // Loading twice does not accumulate the deck gain (the loader hands out fresh buffers).
        val again = loader.load(a.trackRef, cand.plan.aWindow, prefs)
        assertEquals(a.audio[0][cand.plan.aWindow.start.toInt() + 500], again[0][500], 0f)
    }

    @Test
    fun modifiersApplyInOrderWithTheirRecordedParams() {
        val (base, features) = crossfadeCandidate()
        val log = ArrayList<String>()
        val m1 = TagModifier("m1", log); val m2 = TagModifier("m2", log)
        val reg = registry.withModifiers(m1, m2)
        val plan0 = base.plan
        val prefs2 = prefs.copy(paramOverrides = mapOf("m2" to mapOf("tag" to "z")))
        // The planner records modifier params in the plan; the renderer resolves them back (plan values > prefs > defaults).
        var plan = ModifierParams.record(plan0, m1, ModifierParams.defaults(m1, prefs2).with("tag", "y"))
        plan = ModifierParams.record(plan, m2, ModifierParams.defaults(m2, prefs2))
        assertEquals(listOf("m1", "m2"), plan.modifiers)
        assertEquals("y", plan.params["m1.tag"]); assertEquals("z", plan.params["m2.tag"])
        assertEquals("y", ModifierParams.resolve(m1, plan, prefs2).choice(m1.params[0] as ParamSpec.ChoiceSpec))
        assertEquals("z", ModifierParams.resolve(m2, plan, prefs2).choice(m2.params[0] as ParamSpec.ChoiceSpec))
        assertEquals("x", ModifierParams.resolve(m2, plan0, prefs).choice(m2.params[0] as ParamSpec.ChoiceSpec), "default when nothing is recorded")
        // The strategy's own param resolution ignores the prefixed keys.
        assertEquals(6.0, CrossfadeStrategy.P.resolve(plan.params).double(CrossfadeStrategy.P.fadeSec), 0.0)

        val renderer = DefaultTransitionRenderer(loader, reg, CountingSeparator())
        val cand = PlanCandidate(base.strategy, base.applicability, base.score, plan, listOf(m1, m2))
        val rendered = renderer.render(a.trackRef, b.trackRef, cand, features, RenderContext(prefs2, 0L))
        assertEquals(listOf("m1:y", "m2:z"), log, "applied in candidate order with the plan's params")
        assertEquals(listOf(CrossfadeStrategy.MARKER_B_ENTERS, CrossfadeStrategy.MARKER_A_GONE, "m1:y", "m2:z"), rendered.markers.map { it.label })
        assertEquals(RenderKey.compute(a.analysis, b.analysis, plan, prefs2, 0L), rendered.report.renderKey)
        assertTrue(RenderKey.compute(a.analysis, b.analysis, plan, prefs2, 0L) != RenderKey.compute(a.analysis, b.analysis, plan0, prefs2, 0L), "modifiers and their params change the key")

        // Without candidate.modifiers the ids in the plan are resolved through the registry, in plan order.
        log.clear()
        val fromPlan = PlanCandidate(base.strategy, base.applicability, base.score, plan)
        assertEquals(listOf(m1, m2), renderer.resolveModifiers(fromPlan))
        renderer.render(a.trackRef, b.trackRef, fromPlan, features, RenderContext(prefs2, 0L))
        assertEquals(listOf("m1:y", "m2:z"), log)
        val unknown = PlanCandidate(base.strategy, base.applicability, base.score, plan0.copy(modifiers = listOf("nope")))
        assertFailsWith<IllegalArgumentException> { renderer.render(a.trackRef, b.trackRef, unknown, features, RenderContext(prefs, 0L)) }
        // A plan for another strategy is rejected up front.
        assertFailsWith<IllegalArgumentException> {
            renderer.render(a.trackRef, b.trackRef, PlanCandidate(base.strategy, base.applicability, base.score, plan0.copy(strategyId = "other")), features, RenderContext(prefs, 0L))
        }
    }

    @Test
    fun stemsAreLazyCachedAndPrecomputedPerNeed() {
        val (cand, features) = crossfadeCandidate()
        val sep = CountingSeparator()
        val renderer = DefaultTransitionRenderer(loader, registry, sep)
        renderer.render(a.trackRef, b.trackRef, cand, features, RenderContext(prefs, 0L))
        assertEquals(0, sep.calls, "crossfade declares StemNeed.NONE: nothing separated")
        // A plan asking for A's stems gets them precomputed (B stays lazy).
        val sep2 = CountingSeparator()
        val needA = PlanCandidate(cand.strategy, cand.applicability, cand.score, cand.plan.copy(stemNeed = StemNeed.A_TAIL))
        DefaultTransitionRenderer(loader, registry, sep2).render(a.trackRef, b.trackRef, needA, features, RenderContext(prefs, 0L))
        assertEquals(1, sep2.calls)
        val sep3 = CountingSeparator()
        val needBoth = PlanCandidate(cand.strategy, cand.applicability, cand.score, cand.plan.copy(stemNeed = StemNeed.BOTH))
        DefaultTransitionRenderer(loader, registry, sep3).render(a.trackRef, b.trackRef, needBoth, features, RenderContext(prefs, 0L))
        assertEquals(2, sep3.calls)

        // The provider itself: one separation per side however often it is asked; precompute honours `need`.
        val x = AudioBuffer.silence(44100, 2, 1000); val y = AudioBuffer.silence(44100, 2, 2000)
        val sep4 = CountingSeparator()
        val p = LazyStemProvider(x, y, sep4, StemNeed.B_HEAD)
        assertFalse(p.aComputed || p.bComputed)
        p.precompute()
        assertEquals(1, sep4.calls); assertTrue(p.bComputed); assertFalse(p.aComputed)
        assertSame(p.bHead(), p.bHead()); assertEquals(1, sep4.calls)
        assertEquals(1000, p.aTail().frames); assertSame(p.aTail(), p.aTail()); assertEquals(2, sep4.calls)
        assertTrue(p.aComputed)
        p.precompute(); assertEquals(2, sep4.calls)
        assertEquals(0, LazyStemProvider(x, y, CountingSeparator(), StemNeed.NONE).also { it.precompute() }.let { if (it.aComputed || it.bComputed) 1 else 0 })
    }

    @Test
    fun cancellationBetweenStages() {
        val (cand, features) = crossfadeCandidate()
        val renderer = DefaultTransitionRenderer(loader, registry, CountingSeparator())
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { renderer.render(a.trackRef, b.trackRef, cand, features, RenderContext(prefs, 0L)) }
        } finally {
            Thread.interrupted() // make sure the flag is clear for the rest of the suite
        }
        assertFalse(Thread.currentThread().isInterrupted, "the interrupt flag was consumed by the renderer")
        // Interrupt raised inside the strategy (i.e. from another thread mid-render) is caught at the next stage boundary.
        val interrupting = object : TransitionStrategy by cand.strategy {
            override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition {
                val r = cand.strategy.render(input, ctx); Thread.currentThread().interrupt(); return r
            }
        }
        val cand2 = PlanCandidate(interrupting, cand.applicability, cand.score, cand.plan)
        try {
            assertFailsWith<InterruptedException> { renderer.render(a.trackRef, b.trackRef, cand2, features, RenderContext(prefs, 0L)) }
        } finally {
            Thread.interrupted()
        }
    }
}
