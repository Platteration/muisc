package dev.muisc.cli

import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.stems.PseudoStemSeparator
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
import dev.muisc.transitions.recipe.RecipeCatalog
import dev.muisc.transitions.recipe.RecipeLibrary
import dev.muisc.transitions.synthetic.SyntheticTrack
import dev.muisc.transitions.synthetic.SyntheticTrackLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * The committed golden-render corpus: every registered strategy **and** every built-in recipe, rendered over a
 * small fixed set of synthetic pairs and stored as [GoldenFingerprint] JSON (never audio) under
 * [GoldenStore.DEFAULT_DIR], next to a [Manifest] that records what was rendered and what was not applicable.
 *
 * The render procedure is the one `muisc goldens check|update` uses (same fixture songs with song seed
 * [SONG_SEED], render seed [RENDER_SEED], default [TransitionPrefs] with the target loudness 3 LU under the quietest
 * fixture, [SyntheticTrackLoader], metrics evaluated against the sources), so for the built-in strategies the files
 * of the first three pairs are the same ones `muisc goldens check --pairs 3` compares. The differences: the registry
 * here also carries the built-in recipes, the prefs are fixed (never the user's profile, style or presets), the
 * files are written by [encode] (rounded, compact) and the corpus writes a manifest, so a strategy that silently
 * stops rendering, or a new strategy with no golden, is caught too.
 *
 * Used by `GoldenCorpusTest` (compare, or rewrite with `MUISC_UPDATE_GOLDENS=1`); see docs/TESTING.md.
 */
object GoldenCorpus {

    const val SONG_SEED = 7
    const val RENDER_SEED = 0L
    const val MANIFEST_FILE = "corpus.json"

    /**
     * Two songs the contract fixtures lack, added so every strategy and recipe applies to at least one pair: both
     * have drums from the first beat to the last (no intro, no outro), which is what `drumBreakBridge` (a percussive
     * A tail), `recipe:tension-build` (a B that starts on the beat) and `recipe:club-bass-swap` (A and B at the same
     * energy) need. D major and B minor are relative keys; 124 → 128 BPM is a 3.2 % stretch.
     */
    val EXTRA_SONGS: List<Pair<String, (Int, Int) -> SyntheticSong>> = listOf(
        "t124D0" to { r: Int, s: Int -> SyntheticSong(bpm = 124.0, tonic = 2, mode = Mode.MAJOR, bars = 16, introBars = 0, outroBars = 0, sampleRate = r, seed = s) },
        "t128Bm0" to { r: Int, s: Int -> SyntheticSong(bpm = 128.0, tonic = 11, mode = Mode.MINOR, bars = 16, introBars = 0, outroBars = 0, sampleRate = r, seed = s) },
    )

    /**
     * The pairs rendered, as (outgoing, incoming) song ids of [SynthCommand.FIXTURES] and [EXTRA_SONGS]. The first
     * three are the first three ordered pairs `muisc goldens` walks; together the four make every strategy and
     * recipe applicable at least once (the test asserts it) while a corpus run stays around a minute or two:
     *
     *  - `t120C → t126Am`: +5 % tempo, relative keys (Camelot 8B → 8A), B with an ambient intro — the beat-matched,
     *    harmonic case;
     *  - `t120C → t63G`: half-time partner with a faded outro — the tempo-relation and ambient case;
     *  - `t120C → t140Fs`: +16.7 % tempo, distant keys, cold-start B — the case beat-matching must refuse;
     *  - `t124D0 → t128Bm0`: drums end to end on both sides, relative keys — the club case.
     */
    val PAIRS: List<Pair<String, String>> = listOf(
        "t120C" to "t126Am", "t120C" to "t63G", "t120C" to "t140Fs", "t124D0" to "t128Bm0",
    )

    /** Built-in strategies followed by one `recipe:<id>` strategy per built-in recipe (no user recipes, ever). */
    fun registry(): RecipeCatalog.Result = RecipeCatalog.build(DefaultStrategyRegistry.default(), RecipeLibrary.builtInOnly().load())

    fun pairId(a: String, b: String): String = "${a}_$b"

    /** What happened to one (strategy, pair). Exactly one of [fingerprint] / [reason] is set. */
    class Outcome(val strategyId: String, val pairId: String, val status: Status, val fingerprint: GoldenFingerprint?, val reason: String?, val millis: Long)

    enum class Status { RENDERED, NOT_APPLICABLE, FAILED }

    /**
     * The stored record of a corpus run: which strategies and pairs it covered and each cell's status. (Written by
     * hand with the JSON element builders: this module does not apply the serialization compiler plugin.)
     */
    data class Manifest(
        val strategies: List<String>,
        val pairs: List<String>,
        /** `"<strategyId> <pairId>"` → `RENDERED` / `NOT_APPLICABLE` / `FAILED`. */
        val status: Map<String, String>,
        /** Same keys: the blocker or failure text, for a human reading the diff (not compared). */
        val reasons: Map<String, String> = emptyMap(),
    ) {
        fun toJson(): String = JSON.encodeToString(JsonObject.serializer(), buildJsonObject {
            putJsonArray("strategies") { for (s in strategies) add(s) }
            putJsonArray("pairs") { for (p in pairs) add(p) }
            put("status", JsonObject(status.mapValues { JsonPrimitive(it.value) }))
            put("reasons", JsonObject(reasons.mapValues { JsonPrimitive(it.value) }))
        })

        companion object {
            fun key(strategyId: String, pairId: String) = "$strategyId $pairId"
            fun fromJson(text: String): Manifest {
                val o = JSON.parseToJsonElement(text).jsonObject
                fun strings(k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                fun map(k: String) = o[k]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                return Manifest(strings("strategies"), strings("pairs"), map("status"), map("reasons"))
            }
        }
    }

    private val JSON = Json { prettyPrint = true }

    /** The fixed prefs of a corpus run: defaults, with the target loudness 3 LU under the quietest of [tracks]. */
    fun prefs(tracks: Collection<SyntheticTrack>): TransitionPrefs {
        val base = TransitionPrefs()
        return base.copy(targetLufs = tracks.minOf { it.analysis.loudness.integratedLufs }.toDouble() - 3.0)
    }

    /** Renders every (strategy, pair) of the corpus, in registry order, calling [onOutcome] for each. */
    fun renderAll(onOutcome: (Outcome) -> Unit) {
        val catalog = registry()
        val registry = catalog.registry
        val loader = SyntheticTrackLoader()
        val pairAnalyzer = DefaultPairAnalyzer()
        val separator = PseudoStemSeparator()
        val renderer = DefaultTransitionRenderer(loader, registry, separator)
        val base = TransitionPrefs()
        val needed = PAIRS.flatMap { listOf(it.first, it.second) }.toSet()
        // Every contract fixture is registered (not only the ones the pairs use) and the loudness target is taken
        // over the fixtures alone, so it is the same target `muisc goldens` computes.
        val fixtures: Map<String, SyntheticTrack> = SynthCommand.FIXTURES.associate { (id, make) ->
            id to loader.register(make(base.sampleRate, SONG_SEED), id, prefs = base)
        }
        val tracks = fixtures + EXTRA_SONGS.associate { (id, make) -> id to loader.register(make(base.sampleRate, SONG_SEED), id, prefs = base) }
        require(tracks.keys.containsAll(needed)) { "corpus pairs name unknown songs: ${needed - tracks.keys}" }
        val prefs = prefs(fixtures.values)

        for (strategy in registry.strategies) {
            for ((aId, bId) in PAIRS) {
                val a = tracks.getValue(aId)
                val b = tracks.getValue(bId)
                val pairId = pairId(aId, bId)
                val started = System.nanoTime()
                fun ms() = (System.nanoTime() - started) / 1_000_000
                val features = pairAnalyzer.features(a.analysis, b.analysis, prefs)
                val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
                if (!app.applicable) {
                    onOutcome(Outcome(strategy.id, pairId, Status.NOT_APPLICABLE, null, app.blockers.joinToString("; ").ifEmpty { "score 0" }, ms()))
                    continue
                }
                val fingerprint = try {
                    val plan = strategy.plan(a.analysis, b.analysis, features, Params.defaults(strategy.params), prefs, RENDER_SEED)
                    val rendered = renderer.render(a.trackRef, b.trackRef, PlanCandidate(strategy, app, 1.0, plan), features, RenderContext(prefs, RENDER_SEED))
                    val aAudio = loader.load(a.trackRef, rendered.plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
                    val bAudio = loader.load(b.trackRef, rendered.plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
                    val input = TransitionInput(
                        rendered.plan, a.trackRef, b.trackRef, features, aAudio, bAudio,
                        LazyStemProvider(aAudio, bAudio, separator, rendered.plan.stemNeed),
                    )
                    GoldenFingerprint.of(rendered, ArtifactMetrics.evaluate(rendered, input))
                } catch (e: Exception) {
                    onOutcome(Outcome(strategy.id, pairId, Status.FAILED, null, "${e.javaClass.simpleName}: ${e.message}", ms()))
                    continue
                }
                onOutcome(Outcome(strategy.id, pairId, Status.RENDERED, fingerprint, null, ms()))
            }
        }
    }

    /** One run of the corpus: the outcomes in order plus the manifest they make. */
    class Run(val outcomes: List<Outcome>, val strategies: List<String>) {
        val manifest: Manifest get() = Manifest(
            strategies = strategies,
            pairs = PAIRS.map { pairId(it.first, it.second) },
            status = outcomes.associate { Manifest.key(it.strategyId, it.pairId) to it.status.name },
            reasons = outcomes.filter { it.reason != null }.associate { Manifest.key(it.strategyId, it.pairId) to it.reason!! },
        )
    }

    fun run(): Run {
        val outcomes = ArrayList<Outcome>()
        renderAll { outcomes += it }
        return Run(outcomes, registry().registry.strategyIds)
    }

    /**
     * Compares [run] with what is stored in [dir]; returns one readable paragraph per problem (empty = the corpus
     * matches). Coverage problems (strategy added or removed, a cell that changed status) come first, then every
     * fingerprint that moved, with [GoldenCompare]'s per-feature numbers.
     */
    fun compare(run: Run, dir: File): List<String> {
        val problems = ArrayList<String>()
        val manifestFile = File(dir, MANIFEST_FILE)
        if (!manifestFile.isFile) return listOf("no golden manifest at ${manifestFile.path}: the corpus has never been written. $UPDATE_HINT")
        val stored = Manifest.fromJson(manifestFile.readText())
        val current = run.manifest

        val added = current.strategies - stored.strategies.toSet()
        val removed = stored.strategies - current.strategies.toSet()
        if (added.isNotEmpty()) problems += "strategies with no golden yet: ${added.joinToString(", ")}. $UPDATE_HINT"
        if (removed.isNotEmpty()) problems += "strategies in the goldens that are no longer registered: ${removed.joinToString(", ")}. $UPDATE_HINT"
        if (stored.pairs != current.pairs) problems += "pair set changed: goldens have ${stored.pairs}, the corpus now renders ${current.pairs}. $UPDATE_HINT"

        val store = GoldenStore(dir)
        for (o in run.outcomes) {
            val key = Manifest.key(o.strategyId, o.pairId)
            if (o.status == Status.FAILED) {
                problems += "${o.strategyId} / ${o.pairId}: render FAILED — ${o.reason}"
                continue
            }
            val was = stored.status[key] ?: continue // a new strategy, reported above
            if (was != o.status.name) {
                problems += "${o.strategyId} / ${o.pairId}: was $was, now ${o.status.name}" +
                    (o.reason?.let { " ($it)" } ?: "") + (stored.reasons[key]?.let { " [golden: $it]" } ?: "")
                continue
            }
            val fingerprint = o.fingerprint ?: continue
            val golden = store.load(o.strategyId, o.pairId)
            if (golden == null) {
                problems += "${o.strategyId} / ${o.pairId}: manifest says RENDERED but ${store.file(o.strategyId, o.pairId).path} is missing. $UPDATE_HINT"
                continue
            }
            val diff = GoldenCompare.compare(golden, fingerprint, strict = false)
            if (!diff.matches) {
                problems += "${o.strategyId} / ${o.pairId}: ${diff.issues.joinToString("; ")}\n" +
                    diff.summary().lines().joinToString("\n") { "    $it" }
            }
        }
        return problems
    }

    /**
     * Rewrites [dir] from [run]: every rendered fingerprint, the manifest, and removes fingerprints of cells that
     * are no longer rendered. Returns one line per changed cell (what moved), so an update can be reviewed.
     */
    fun write(run: Run, dir: File): List<String> {
        val store = GoldenStore(dir)
        val lines = ArrayList<String>()
        val oldManifest = File(dir, MANIFEST_FILE).takeIf { it.isFile }?.let { Manifest.fromJson(it.readText()) }
        val keep = HashSet<Pair<String, String>>()
        for (o in run.outcomes) {
            val fp = o.fingerprint ?: continue
            keep += GoldenStore.sanitize(o.strategyId) to o.pairId
            val old = store.load(o.strategyId, o.pairId)
            val file = store.file(o.strategyId, o.pairId)
            file.parentFile?.mkdirs()
            file.writeText(encode(fp), Charsets.UTF_8)
            when {
                old == null -> lines += "new      ${o.strategyId} / ${o.pairId}"
                else -> {
                    val diff = GoldenCompare.compare(old, fp, strict = false)
                    if (!diff.matches) lines += "updated  ${o.strategyId} / ${o.pairId}: ${diff.issues.joinToString("; ")}"
                }
            }
        }
        for (s in store.strategies()) for (p in store.pairs(s)) {
            if ((s to p) !in keep) {
                File(File(dir, s), p + GoldenStore.EXTENSION).delete()
                lines += "removed  $s / $p"
            }
        }
        dir.listFiles()?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }?.forEach { it.delete() }
        val manifest = run.manifest
        if (oldManifest != null) {
            for ((k, v) in manifest.status) {
                val was = oldManifest.status[k]
                if (was != null && was != v) lines += "status   $k: $was -> $v"
            }
        }
        dir.mkdirs()
        File(dir, MANIFEST_FILE).writeText(manifest.toJson() + "\n")
        return lines
    }

    /** Decimal places the stored envelopes and spectra keep (dB). */
    const val STORED_DECIMALS = 2

    /**
     * The corpus's on-disk form of a fingerprint: the same JSON [GoldenFingerprint.fromJson] (and so [GoldenStore])
     * reads, but with the envelope and spectrum values rounded to [STORED_DECIMALS] dB and one array per line.
     * `GoldenStore.save` pretty-prints every float on its own line at full precision, which made this corpus 14 MB;
     * this form is about a third of that and still diffs readably (one line per 250 ms spectrum frame).
     * Rounding to 0.01 dB moves any element by at most 0.005 dB, so it can move an RMSE by at most 0.005 dB — two
     * orders of magnitude under [GoldenCompare.ENVELOPE_RMSE_DB] and [GoldenCompare.SPECTRUM_TOLERANCE_DB]. The
     * plan, the PCM hash and the metrics are stored exactly.
     */
    fun encode(fp: GoldenFingerprint): String = buildString {
        append("{\n")
        append("  \"planJson\": ").append(JsonPrimitive(fp.planJson)).append(",\n")
        append("  \"rmsEnvelope20ms\": ").append(floats(fp.rmsEnvelope20ms)).append(",\n")
        append("  \"bandEnvelopes\": [\n")
        fp.bandEnvelopes.forEachIndexed { i, band -> append("    ").append(floats(band)).append(if (i < fp.bandEnvelopes.size - 1) ",\n" else "\n") }
        append("  ],\n")
        append("  \"logSpec64Per250ms\": [\n")
        fp.logSpec64Per250ms.forEachIndexed { i, row -> append("    ").append(floats(row)).append(if (i < fp.logSpec64Per250ms.size - 1) ",\n" else "\n") }
        append("  ],\n")
        append("  \"pcm16Sha256\": ").append(JsonPrimitive(fp.pcm16Sha256)).append(",\n")
        append("  \"metrics\": ").append(fp.metrics.toJson(pretty = false)).append('\n')
        append("}\n")
    }

    private fun floats(values: FloatArray): String = values.joinToString(",", "[", "]") { number(it) }

    private fun number(v: Float): String = when {
        v.isNaN() -> "NaN"
        v == Float.POSITIVE_INFINITY -> "Infinity"
        v == Float.NEGATIVE_INFINITY -> "-Infinity"
        else -> java.math.BigDecimal(v.toDouble()).setScale(STORED_DECIMALS, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
            .let { if (it == "-0") "0" else it }
    }

    const val UPDATE_HINT =
        "If (and only if) the change is intended, rewrite the goldens with `MUISC_UPDATE_GOLDENS=1 gradle :tools:cli:test --tests '*GoldenCorpusTest*' --rerun` and review the diff (docs/TESTING.md)."
}
