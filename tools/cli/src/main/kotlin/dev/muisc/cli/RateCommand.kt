package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import dev.muisc.transitions.custom.ContextBucket
import dev.muisc.transitions.custom.FeedbackLearner
import dev.muisc.transitions.custom.Rating
import dev.muisc.transitions.recipe.TransitionRecipe
import java.io.File

/**
 * `muisc rate <a> <b> <strategyId> up|down|1..5` records how a transition sounded; `muisc rate show` prints what was
 * learned. Ratings are tallied per strategy in the pair's context bucket (beat-matchable × keys compatible × energy
 * rising — see `ContextBucket`) in `<profile-dir>/feedback.json`, and the planner multiplies each strategy's score
 * by the learned weight (0.5..1.5, pulled toward 1 while there are few ratings — see `FeedbackLearner`).
 */
class RateCommand : MuiscCommand("rate") {
    override fun help(context: Context) = "Rate a transition (A B STRATEGY up|down|1..5), or 'rate show' for the learned weights."

    private val args by argument("ARGS", help = "A B STRATEGY RATING, or 'show'.").multiple(required = true)

    override fun execute(ctx: CliContext) {
        when {
            args == listOf("show") -> show(ctx)
            args.size == 4 -> rate(ctx, args[0], args[1], args[2], args[3])
            else -> throw CliktError("usage: muisc rate <a> <b> <strategyId> up|down|1..5   or   muisc rate show")
        }
    }

    private fun rate(ctx: CliContext, a: String, b: String, strategyId: String, ratingText: String) {
        val profile = ctx.requireProfile()
        val rating = try { Rating.parse(ratingText) } catch (e: IllegalArgumentException) { throw CliktError(e.message ?: "bad rating") }
        if (ctx.registry.strategy(strategyId) == null && !strategyId.startsWith(TransitionRecipe.STRATEGY_PREFIX)) {
            throw CliktError("unknown strategy '$strategyId'. Known: ${ctx.registry.strategyIds.joinToString(", ")} (or recipe:<id>)")
        }
        val aRef = ctx.trackRef(File(a))
        val bRef = ctx.trackRef(File(b))
        val features = ctx.features(aRef, bRef)
        val factor = profile.feedback.record(strategyId, features, rating)
        echo("rated $strategyId $rating for ${File(a).name} → ${File(b).name}")
        echo("$strategyId: ${factor.describe()}")
    }

    private fun show(ctx: CliContext) {
        val profile = ctx.requireProfile()
        val learner = profile.feedback.learner
        val table = learner.table()
        if (table.isEmpty()) {
            echo("no ratings yet (${profile.feedback.file.path})")
            return
        }
        val tallies = learner.snapshot()
        val rows = ArrayList<List<String>>()
        rows += listOf("strategy", "context", "ratings", "mean", "skips", "weight")
        for ((strategy, f) in table) {
            val bucket = f.bucket ?: continue
            val t = tallies[strategy]?.get(bucket.key) ?: continue
            val mean = if (t.n > 0) Fmt.num(t.sum / t.n, 2) else "–"
            rows += listOf(strategy, bucket.label, "${t.n}", mean, "${t.implicitN}", "×" + Fmt.num(f.multiplier, 2))
        }
        echo(Fmt.table(rows))
        echo("\nweight = 0.5 + (${FeedbackLearner.PRIOR_STRENGTH} + Σ rating) / (${2 * FeedbackLearner.PRIOR_STRENGTH} + n), rating up = 1, down = 0, stars (s − 1)/4")
        echo("skips (the app's \"learn from skips\") count as ${FeedbackLearner.IMPLICIT_WEIGHT} of a down-vote each, at most ${FeedbackLearner.IMPLICIT_CAP} down-votes together")
        echo("contexts: ${ContextBucket.ALL.size} = beat-matchable (≤ ${Fmt.num(ContextBucket.MATCH_STRETCH_PERCENT, 0)} % stretch) × keys compatible × energy rising")
    }
}
