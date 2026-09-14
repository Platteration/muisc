package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.metrics.ArtifactMetrics
import dev.muisc.metrics.GoldenCompare
import dev.muisc.metrics.GoldenFingerprint
import dev.muisc.metrics.GoldenStore
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.DefaultTransitionRenderer
import dev.muisc.transitions.LazyStemProvider
import dev.muisc.transitions.Params
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.synthetic.SyntheticTrack
import dev.muisc.transitions.synthetic.SyntheticTrackLoader
import java.io.File

/** `muisc goldens` — the golden-render regression over the synthetic fixture set. */
class GoldensCommand : CliktCommand(name = "goldens") {
    override fun help(context: Context) = "Check or update the golden renders of the synthetic fixture set."
    override fun run() = Unit

    companion object {
        fun build(): CliktCommand = GoldensCommand().subcommands(GoldensCheckCommand(), GoldensUpdateCommand())
    }
}

/**
 * Shared machinery of `goldens check` and `goldens update`: renders every selected strategy over every ordered
 * pair of the four contract songs through [SyntheticTrackLoader] (no files, no decoder, fully deterministic) and
 * fingerprints each render with [GoldenFingerprint].
 */
abstract class GoldensBase(name: String) : MuiscCommand(name) {

    protected val dir by option("--dir", metavar = "DIR", help = "Golden directory.").file(canBeFile = false).default(File(GoldenStore.DEFAULT_DIR))
    protected val strategyFilter by option("--strategies", metavar = "ID,ID", help = "Only these strategies.").split(",")
    protected val pairLimit by option("--pairs", metavar = "N", help = "Only the first N ordered pairs.").int()

    protected fun songs(rate: Int): Map<String, SyntheticSong> =
        SynthCommand.FIXTURES.associate { (id, make) -> id to make(rate, DEFAULT_SEED) }

    /** Renders every selected (strategy, pair); calls [onResult] with the fingerprint or the reason it was skipped. */
    protected fun forEachRender(ctx: CliContext, onResult: (String, String, GoldenFingerprint?, String?) -> Unit) {
        val registry = DefaultStrategyRegistry.default()
        val loader = SyntheticTrackLoader()
        val pairAnalyzer = DefaultPairAnalyzer()
        val renderer = DefaultTransitionRenderer(loader, registry)
        val prefsBase = ctx.prefs
        val tracks: Map<String, SyntheticTrack> = songs(prefsBase.sampleRate).mapValues { (id, song) -> loader.register(song, id, prefs = prefsBase) }
        // Both decks attenuated under the quietest track so dry material sits below 0 dBFS (as in StrategyContractTest).
        val prefs: TransitionPrefs = prefsBase.copy(targetLufs = tracks.values.minOf { it.analysis.loudness.integratedLufs }.toDouble() - 3.0)

        val strategies = strategyFilter?.map { id ->
            registry.strategy(id.trim()) ?: throw CliktError("unknown strategy '${id.trim()}'. Known: ${registry.strategyIds.joinToString(", ")}")
        } ?: registry.strategies

        val pairs = ArrayList<Pair<SyntheticTrack, SyntheticTrack>>()
        for (a in tracks.values) for (b in tracks.values) if (a !== b) pairs += a to b
        val selected = pairLimit?.let { pairs.take(it) } ?: pairs

        for (strategy in strategies) {
            for ((a, b) in selected) {
                val pairId = "${a.id}_${b.id}"
                val features = pairAnalyzer.features(a.analysis, b.analysis, prefs)
                val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
                if (!app.applicable) {
                    onResult(strategy.id, pairId, null, "not applicable (${app.blockers.joinToString("; ").ifEmpty { "score 0" }})")
                    continue
                }
                val fingerprint = try {
                    val plan = strategy.plan(a.analysis, b.analysis, features, Params.defaults(strategy.params), prefs, seed)
                    val candidate = PlanCandidate(strategy, app, 1.0, plan)
                    val rendered = renderer.render(a.trackRef, b.trackRef, candidate, features, RenderContext(prefs, seed))
                    val aAudio = loader.load(a.trackRef, rendered.plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
                    val bAudio = loader.load(b.trackRef, rendered.plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
                    val input = TransitionInput(
                        rendered.plan, a.trackRef, b.trackRef, features, aAudio, bAudio,
                        LazyStemProvider(aAudio, bAudio, ctx.separator, rendered.plan.stemNeed),
                    )
                    GoldenFingerprint.of(rendered, ArtifactMetrics.evaluate(rendered, input))
                } catch (e: Exception) {
                    onResult(strategy.id, pairId, null, "render failed: ${e.message ?: e.javaClass.simpleName}")
                    continue
                }
                onResult(strategy.id, pairId, fingerprint, null)
            }
        }
    }

    protected companion object {
        const val DEFAULT_SEED = 7
    }
}

/** `muisc goldens check` — compare every render against the stored fingerprint; exit non-zero on any regression. */
class GoldensCheckCommand : GoldensBase("check") {

    override fun help(context: Context) = "Compare the golden renders and report regressions."

    private val strict by option("--strict", help = "Require the PCM16 hash to match, not just the fingerprint.").flag()

    override fun execute(ctx: CliContext) {
        val store = GoldenStore(dir)
        var checked = 0
        var missing = 0
        var skipped = 0
        val failures = ArrayList<String>()
        forEachRender(ctx) { strategyId, pairId, fingerprint, reason ->
            if (fingerprint == null) { skipped++; return@forEachRender }
            val golden = store.load(strategyId, pairId)
            if (golden == null) {
                missing++
                echo("MISSING  $strategyId / $pairId — run `muisc goldens update`")
                return@forEachRender
            }
            checked++
            val diff = GoldenCompare.compare(golden, fingerprint, strict)
            if (diff.matches) {
                echo("ok       $strategyId / $pairId")
            } else {
                failures += "$strategyId / $pairId: ${diff.issues.joinToString("; ")}"
                echo("CHANGED  $strategyId / $pairId")
                echo(diff.summary().lines().joinToString("\n") { "           $it" })
            }
        }
        echo("")
        echo("checked $checked, missing $missing, not applicable $skipped, changed ${failures.size}")
        echoElapsed("golden check")
        if (failures.isNotEmpty()) throw CliktError("${failures.size} golden(s) changed:\n  " + failures.joinToString("\n  "))
        if (missing > 0) throw CliktError("$missing golden(s) are missing; run `muisc goldens update`")
    }
}

/** `muisc goldens update` — rewrite the stored fingerprints and print what moved. */
class GoldensUpdateCommand : GoldensBase("update") {

    override fun help(context: Context) = "Rewrite the golden renders, printing the diff against the old ones."

    override fun execute(ctx: CliContext) {
        val store = GoldenStore(dir)
        var written = 0
        var changed = 0
        var skipped = 0
        forEachRender(ctx) { strategyId, pairId, fingerprint, reason ->
            if (fingerprint == null) {
                skipped++
                echo("skip     $strategyId / $pairId — $reason")
                return@forEachRender
            }
            val old = store.load(strategyId, pairId)
            store.save(strategyId, pairId, fingerprint)
            written++
            if (old == null) {
                echo("new      $strategyId / $pairId")
            } else {
                val diff = GoldenCompare.compare(old, fingerprint, strict = false)
                if (diff.matches) {
                    echo("same     $strategyId / $pairId")
                } else {
                    changed++
                    echo("updated  $strategyId / $pairId")
                    echo(diff.summary().lines().joinToString("\n") { "           $it" })
                }
            }
        }
        echo("")
        echo("wrote $written golden(s) to ${dir.path} ($changed changed, $skipped not applicable)")
        echoElapsed("golden update")
    }
}
