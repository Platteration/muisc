package dev.muisc.transitions.recipe

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.sdk.StrategyTraits

/**
 * A [TransitionRecipe] as a first-class strategy with id `recipe:<id>`. Its parameters are the recipe's variables.
 *
 * FROZEN SIGNATURE. [applicability] is [RecipeScoring] (rules as blockers, then a score; never throws), [plan] is
 * [RecipeGeometry.plan] (frames per tempo mode, the recipe's lanes for the Lab, notes explaining every choice) and
 * [render] is [RecipeRenderer] (stems, EQ, filters, pre-fader sends into echo / reverb, level, splice guards).
 */
class RecipeStrategy(val recipe: TransitionRecipe) : TransitionStrategy, StrategyTraits {
    override val id: String = recipe.strategyId
    override val displayName: String = recipe.name
    override val description: String = recipe.description
    override val params: List<ParamSpec> = RecipeResolver.paramSpecs(recipe)
    override val ambition: Double = recipe.ambition.coerceIn(0.0, 1.0)
    override val beatDomain: Boolean = recipe.timing.tempo != RecipeTempo.NONE
    override val needsStems: Boolean get() = recipe.a.stems != null || recipe.b.stems != null

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability =
        RecipeScoring.applicability(recipe, features, a, b, prefs)

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan =
        RecipeGeometry.plan(id, recipe, a, b, features, params, prefs)

    override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition =
        RecipeRenderer.render(recipe, input, ctx)
}
