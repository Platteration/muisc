package dev.muisc.transitions.recipe

import dev.muisc.analysis.model.Mode
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.dsp.stems.PseudoStemSeparator
import dev.muisc.transitions.LazyStemProvider
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.core.SpliceCheck
import dev.muisc.transitions.strategies.BeatDomainTestSupport
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recipe builders and render helpers shared by the recipe render tests. */
internal object RecipeRenderTestSupport {
    val T = BeatDomainTestSupport
    const val SR = BeatDomainTestSupport.SR

    /** 120 BPM major into 121 BPM minor: a 0.8 % stretch (resampled, fast). */
    val near: BeatDomainTestSupport.Pair by lazy { T.pair(T.song(120.0, 0, Mode.MAJOR), T.song(121.0, 9, Mode.MINOR)) }
    /** 120 into 124 BPM: a 3.3 % glide. */
    val glidePair: BeatDomainTestSupport.Pair by lazy { T.pair(T.song(120.0, 0, Mode.MAJOR), T.song(124.0, 7, Mode.MAJOR)) }
    /** 120 into 140 BPM: not beat-matchable within 8 %, a job for a `none` recipe. */
    val far: BeatDomainTestSupport.Pair by lazy { T.pair(T.song(120.0, 0, Mode.MAJOR), T.song(140.0, 2, Mode.MAJOR)) }

    fun e(x: Any): Expr = when (x) {
        is Int -> Expr.of(x)
        is Double -> Expr.of(x)
        is String -> Expr(x)
        else -> error("not an expression: $x")
    }

    fun pt(at: Any, v: Any, curve: RecipeCurve = RecipeCurve.LINEAR) = RecipePoint(e(at), e(v), curve)

    fun recipe(
        id: String, tempo: RecipeTempo, a: DeckRecipe, b: DeckRecipe, length: Any = 8, settle: Any = 2, hold: Any = 1,
        bEntersAt: Any = 0, align: RecipeAlign = RecipeAlign.PHRASE, rules: RecipeRules = RecipeRules(), vars: Map<String, RecipeVar> = emptyMap(),
    ) = TransitionRecipe(
        id = id, name = id, vars = vars, rules = rules, a = a, b = b,
        timing = RecipeTiming(lengthBars = e(length), tempo = tempo, align = align, settleBars = e(settle), holdBars = e(hold), bEntersAtBar = e(bEntersAt)),
    )

    /** Equal-power crossfade over `[from, to]` bars: A 1 -> 0, B 0 -> 1. */
    fun fadeOutA(from: Any = 0, to: Any = "bars") = listOf(pt(from, 1, RecipeCurve.EQUAL_POWER), pt(to, 0))
    fun fadeInB(from: Any = 0, to: Any = "bars") = listOf(pt(from, 0, RecipeCurve.EQUAL_POWER), pt(to, 1))

    class Rendered(val plan: TransitionPlan, val input: TransitionInput, val out: RenderedTransition)

    /** Plans and renders [recipe] on [pair]; with [stems] the input carries a lazy pseudo-stem provider. */
    fun render(recipe: TransitionRecipe, pair: BeatDomainTestSupport.Pair, params: Params = Params.EMPTY, stems: Boolean = false, silenceA: Boolean = false, silenceB: Boolean = false): Rendered {
        val s = RecipeStrategy(recipe)
        val plan = s.plan(pair.a.analysis, pair.b.analysis, pair.features, params, pair.prefs, 1L)
        var input = pair.input(plan)
        if (silenceA || silenceB) input = T.silenced(input, silenceA, silenceB)
        // Stems are separated from the (possibly silenced) windows, as the renderer's provider would.
        if (stems) input = TransitionInput(plan, input.a, input.b, input.features, input.aAudio, input.bAudio, LazyStemProvider(input.aAudio, input.bAudio, PseudoStemSeparator(), plan.stemNeed))
        return Rendered(plan, input, s.render(input, RenderContext(pair.prefs, 1L)))
    }

    fun reRender(recipe: TransitionRecipe, r: Rendered, pair: BeatDomainTestSupport.Pair): RenderedTransition = RecipeStrategy(recipe).render(r.input, RenderContext(pair.prefs, 1L))

    /**
     * What every recipe render must satisfy: output length == plan.expectedOutputFrames, the splice contract, no
     * clicks, peak <= 0 dBFS, finite samples, notes, and (when [boundaryClean]) no warnings at all.
     */
    fun assertContract(name: String, r: Rendered, boundaryClean: Boolean = true) {
        val out = r.out
        assertEquals(r.plan.expectedOutputFrames, out.audio.frames, "$name: output length equals plan.expectedOutputFrames")
        assertEquals(emptyList(), SpliceCheck.verify(out, r.input), "$name: splice contract")
        for (c in 0 until out.audio.channelCount) assertTrue(out.audio[c].all { it.isFinite() }, "$name: finite samples")
        val report = ArtifactDetector(SR).analyze(out.audio)
        assertTrue(report.clicks.isEmpty(), "$name: clicks ${report.clicks}")
        assertTrue(out.audio.peak() <= 1.0f, "$name: peak ${out.audio.peak()}")
        assertTrue(r.plan.notes.isNotEmpty(), "$name: notes explain the plan")
        assertTrue(out.report.warnings.none { it.startsWith("click") }, "$name: ${out.report.warnings}")
        if (boundaryClean) assertTrue(out.report.warnings.none { it.startsWith("boundary rule") }, "$name: ${out.report.warnings}")
    }

    /** Output frame of timeline bar [bar] read from the plan's lanes / markers is not needed: the geometry is public to the package. */
    fun outFrame(r: Rendered, pair: BeatDomainTestSupport.Pair, recipe: TransitionRecipe, bar: Double): Long {
        val resolved = RecipeResolver.resolve(recipe, r.plan.params, pair.a.analysis.grid.beatsPerBar)
        val t = RecipeGeometry.timeline(r.plan, resolved, pair.a.analysis, pair.b.analysis, pair.features, pair.prefs)
        return Math.round(t.outFrameOfBar(bar))
    }
}
