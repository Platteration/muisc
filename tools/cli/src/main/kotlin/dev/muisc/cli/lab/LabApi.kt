package dev.muisc.cli.lab

import dev.muisc.cli.PresetValidation
import dev.muisc.transitions.Params
import dev.muisc.transitions.custom.BuiltInPresets
import dev.muisc.transitions.custom.BuiltInStyles
import dev.muisc.transitions.custom.ContextBucket
import dev.muisc.transitions.custom.FeedbackLearner
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.custom.Rating
import dev.muisc.transitions.custom.StrategyPreset
import dev.muisc.transitions.recipe.RecipeCodec
import dev.muisc.transitions.recipe.RecipeException
import dev.muisc.transitions.recipe.RecipeLibrary
import dev.muisc.transitions.recipe.RecipeOrigin
import dev.muisc.transitions.recipe.RecipeProblem
import dev.muisc.transitions.recipe.RecipeResolver
import dev.muisc.transitions.recipe.RecipeValidator
import dev.muisc.transitions.recipe.TransitionRecipe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Random

/**
 * The Lab's JSON API, one method per endpoint (routing and HTTP live in [LabServer]). Slow work is queued on [jobs]
 * and answered with `{"job": {...}}`; the page polls `/api/jobs/{id}`.
 */
class LabApi(
    val lab: LabContext,
    val jobs: LabJobs,
    /** Shuffles the blind test. A [java.security.SecureRandom] in the app; tests pass a seeded one. */
    private val random: Random,
) {
    val renderer = LabRenderer(lab)
    private val blinds = LinkedHashMap<String, BlindSession>()
    private var blindCounter = 0

    // ---- session & tracks -----------------------------------------------------------------------------------------

    fun session(): JsonObject = buildJsonObject {
        put("profileDir", lab.profile.dir.path)
        put("recipesDir", lab.recipesDir.path)
        put("sampleRate", lab.cli.sampleRate)
        put("channels", lab.cli.channels)
        putJsonArray("warnings") {
            for (w in lab.profile.warnings()) add(w)
            for (w in lab.catalog.warnings) add(w)
        }
    }

    fun tracks(): JsonObject = buildJsonObject {
        putJsonArray("tracks") { for (t in lab.tracks()) add(LabViews.trackSummary(t)) }
    }

    fun addTracks(body: JsonObject): JsonObject {
        val path = body.requireStr("path").trim()
        val file = File(expandHome(path))
        if (!file.exists()) throw LabError(404, "no such file or directory: $path")
        return jobOf(jobs.submit("tracks") { job ->
            val r = lab.addPath(file) { done, total, name -> job.report(done.toDouble() / total, "analysing $name") }
            buildJsonObject {
                putJsonArray("added") { for (t in r.added) add(LabViews.trackSummary(t)) }
                put("skipped", LabViews.strings(r.skipped))
            }
        })
    }

    private fun expandHome(p: String): String =
        if (p == "~" || p.startsWith("~/")) (System.getProperty("user.home") ?: "") + p.removePrefix("~") else p

    fun trackAnalysis(id: String): JsonObject {
        val t = lab.track(id)
        return LabViews.trackAnalysis(t, lab.trackPeaks(t))
    }

    // ---- strategies & planning ------------------------------------------------------------------------------------

    fun strategies(): JsonObject {
        val reg = lab.registry
        return buildJsonObject {
            putJsonArray("strategies") { for (s in reg.strategies) add(LabViews.strategy(s)) }
            putJsonArray("modifiers") { for (m in reg.modifiers) add(LabViews.modifier(m)) }
        }
    }

    fun plan(body: JsonObject): JsonObject {
        val a = lab.track(body.requireStr("a"))
        val b = lab.track(body.requireStr("b"))
        val prefs = lab.prefs(body.str("style"))
        val features = lab.features(a.ref, b.ref, prefs)
        val explained = lab.planner().planExplained(a.ref, b.ref, features, prefs, body.lng("seed") ?: 0L, body.str("previous"))
        return buildJsonObject {
            put("pair", LabViews.pair(a.ref.analysis, b.ref.analysis, features))
            for ((k, v) in LabViews.plan(explained.ranked, explained.explanation, prefs.sampleRate)) put(k, v)
        }
    }

    fun render(body: JsonObject): JsonObject {
        val spec = RenderSpec.parse(body)
        lab.track(spec.a); lab.track(spec.b) // fail fast with a 404 instead of a failed job
        return jobOf(jobs.submit("render") { job -> renderer.render(spec, job).json })
    }

    fun job(id: String): JsonObject = (jobs.get(id) ?: throw LabError(404, "no job '$id'")).toJson()

    private fun jobOf(job: LabJobs.Job): JsonObject = buildJsonObject { put("job", job.toJson()) }

    // ---- recipes --------------------------------------------------------------------------------------------------

    fun recipes(): JsonObject {
        val set = lab.catalog.recipeSet
        return buildJsonObject {
            putJsonArray("recipes") {
                for (e in set.entries) addJsonObject {
                    put("id", e.id)
                    put("name", e.recipe.name)
                    put("strategy", e.recipe.strategyId)
                    put("origin", if (e.origin == RecipeOrigin.BUILT_IN) "built-in" else "user")
                    put("status", e.status.name.lowercase())
                    put("errors", e.errors.size)
                    put("warnings", e.warnings.size)
                }
            }
            put("dir", lab.recipesDir.path)
        }
    }

    /** The recipe's text: the user's own file as written (formatting kept), a built-in as the canonical encoding. */
    fun recipeText(id: String): JsonObject {
        val set = lab.catalog.recipeSet
        val entry = set[id] ?: set.all(id).firstOrNull() ?: throw LabError(404, "no recipe '$id'")
        val text = entry.file?.takeIf { it.isFile }?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() } ?: RecipeCodec.encode(entry.recipe)
        return buildJsonObject {
            put("id", entry.id)
            put("origin", if (entry.origin == RecipeOrigin.BUILT_IN) "built-in" else "user")
            put("text", text)
        }
    }

    /**
     * A starter recipe under an id nothing uses yet (`my-transition`, `my-transition-2`, ...): no built-in or user
     * recipe and no file in the recipes folder, so saving a second blank template never lands on the first one.
     */
    fun recipeTemplate(): JsonObject {
        val taken = RecipeLibrary(lab.recipesDir).load().entries.map { it.id }.toSet() +
            lab.recipesDir.list().orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
        return buildJsonObject {
            put("text", RecipeCodec.encode(RecipeLibrary.starter(freeId(TEMPLATE_ID, taken), "My transition")))
        }
    }

    /** Parse + validate: every problem with its severity, path, line and column, and the knobs for the sliders. */
    fun validateRecipe(body: JsonObject): JsonObject {
        val text = body["text"]?.primitiveContent() ?: throw LabError(400, "'text' is required")
        val parsed = RecipeCodec.parse(text)
        val recipe = parsed.recipe
        val problems = if (recipe == null) parsed.problems else parsed.locate(RecipeValidator(lab.registry.modifierIds.toSet()).validate(recipe).problems)
        return buildJsonObject {
            put("ok", recipe != null && problems.none { it.isError })
            put("errors", problems.count { it.isError })
            put("warnings", problems.count { !it.isError })
            putJsonArray("problems") { for (p in problems) add(problem(p)) }
            if (recipe != null) {
                put("id", recipe.id)
                put("name", recipe.name)
                put("strategy", recipe.strategyId)
                putJsonArray("vars") {
                    for ((name, v) in recipe.vars) addJsonObject {
                        put("id", name); put("label", v.label.ifBlank { name }); put("default", v.default)
                        put("min", v.min); put("max", v.max); put("unit", v.unit); put("doc", v.doc); put("integer", v.integer)
                    }
                }
                val builtIn = lab.catalog.recipeSet.all(recipe.id).any { it.origin == RecipeOrigin.BUILT_IN }
                if (builtIn) put("note", "saving it replaces the built-in recipe '${recipe.id}' while it is in your recipes folder")
            }
        }
    }

    private fun problem(p: RecipeProblem): JsonObject = buildJsonObject {
        put("severity", if (p.isError) "error" else "warning")
        put("path", p.path)
        put("message", p.message)
        put("line", p.line?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        put("column", p.column?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        p.setting?.let { put("setting", it) }
        put("text", p.toString())
    }

    /** Every lane of both decks resolved at the given knob values, sampled for plotting over the recipe's bars. */
    fun recipeLanes(body: JsonObject): JsonObject {
        val text = body["text"]?.primitiveContent() ?: throw LabError(400, "'text' is required")
        val recipe = RecipeCodec.parse(text).recipe ?: throw LabError(400, "the recipe cannot be read; validate it to see why")
        val vars = body.stringMap("vars").filterKeys { it in recipe.vars }
        val res = try {
            RecipeResolver.resolve(recipe, Params(vars))
        } catch (e: RecipeException) {
            throw LabError(400, "cannot resolve the recipe at these values (${e.path}): ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw LabError(400, "cannot resolve the recipe at these values: ${e.message}")
        }
        val samples = 240
        return buildJsonObject {
            put("totalBars", LabJson.num(res.totalBars, 4))
            put("lengthBars", LabJson.num(res.lengthBars, 4))
            put("settleBars", LabJson.num(res.settleBars, 4))
            put("holdBars", LabJson.num(res.holdBars, 4))
            put("tempo", res.tempo.name.lowercase())
            putJsonObject("vars") { for ((k, v) in res.vars) put(k, LabJson.num(v, 4)) }
            putJsonArray("lanes") {
                for ((deck, d) in listOf("a" to res.a, "b" to res.b)) for ((label, lane) in d.lanes()) {
                    if (lane.isEmpty) continue
                    addJsonObject {
                        put("deck", deck); put("id", "$deck.$label"); put("kind", lane.kind.name.lowercase())
                        put("unit", lane.kind.unit); put("neutral", LabJson.num(lane.kind.neutral, 4))
                        put("min", LabJson.num(lane.kind.min, 4)); put("max", LabJson.num(lane.kind.max, 4))
                        put("log", lane.kind.logScale); put("neutralEverywhere", lane.isNeutral)
                        putJsonArray("points") {
                            for (p in lane.points) addJsonObject { put("bar", LabJson.num(p.bar, 4)); put("value", LabJson.num(p.value, 4)) }
                        }
                        putJsonArray("samples") {
                            for (i in 0..samples) add(LabJson.num(lane.valueAt(res.totalBars * i / samples), 4))
                        }
                    }
                }
            }
        }
    }

    /**
     * Saves to the user's recipe folder (refused while it has errors) and reloads the registry.
     *
     * When one of the user's recipes already has this id, nothing is written unless the request says
     * `"replace": true`: the answer is `{"ok": false, "conflict": true, "id", "existingName", "existingFile"}` and the
     * page asks the user whether to replace it. (Taking the id of a built-in recipe is not a conflict: the built-in
     * is not touched, and validation already notes that the saved one replaces it while it is in the folder.)
     * Synchronized so two requests (four HTTP threads) cannot both pass the check and then write the same id.
     */
    @Synchronized
    fun saveRecipe(body: JsonObject): JsonObject {
        val text = body["text"]?.primitiveContent() ?: throw LabError(400, "'text' is required")
        val parsed = RecipeCodec.parse(text)
        val recipe: TransitionRecipe = parsed.recipe
            ?: return buildJsonObject {
                put("ok", false)
                putJsonArray("problems") { for (p in parsed.problems) add(problem(p)) }
            }
        val library = RecipeLibrary(lab.recipesDir, RecipeValidator(lab.registry.modifierIds.toSet()))
        if (body.bool("replace") != true) {
            val existing = library.load().all(recipe.id).firstOrNull { it.origin == RecipeOrigin.USER }
            if (existing != null) return buildJsonObject {
                put("ok", false)
                put("conflict", true)
                put("id", recipe.id)
                put("existingName", existing.recipe.name)
                existing.file?.let { put("existingFile", it.path) }
                putJsonArray("problems") { }
            }
        }
        val result = library.save(recipe)
        if (result.ok) lab.reloadRecipes()
        return buildJsonObject {
            put("ok", result.ok)
            put("strategy", recipe.strategyId)
            result.file?.let { put("file", it.path) }
            putJsonArray("problems") { for (p in parsed.locate(result.problems)) add(problem(p)) }
            put("available", lab.registry.strategy(recipe.strategyId) != null)
        }
    }

    // ---- presets, styles, pins, ratings -------------------------------------------------------------------------------

    fun presets(): JsonObject = buildJsonObject {
        putJsonArray("presets") {
            for (p in lab.profile.presets.all()) addJsonObject {
                put("id", p.id); put("name", p.name); put("strategy", p.strategyId); put("note", p.note)
                put("builtIn", BuiltInPresets.byId(p.id) != null)
                putJsonObject("params") { for ((k, v) in p.params.values.toSortedMap()) put(k, v) }
                p.modifiers?.let { mods -> putJsonArray("modifiers") { for (m in mods) add(m) } }
            }
        }
    }

    fun savePreset(body: JsonObject): JsonObject {
        val id = body.requireStr("id")
        val strategyId = body.requireStr("strategy")
        val values = body.stringMap("params")
        val strategy = lab.registry.strategy(strategyId) ?: throw LabError(400, "unknown strategy '$strategyId'")
        PresetValidation.check(strategy, values)?.let { throw LabError(400, it) }
        val mods = body.strList("modifiers")?.onEach { m ->
            if (lab.registry.modifier(m) == null) throw LabError(400, "unknown modifier '$m'")
        }
        if (BuiltInPresets.byId(id) != null) throw LabError(400, "'$id' is a built-in preset id; pick another")
        val preset = StrategyPreset(id, body.str("name") ?: id, strategyId, Params(values), mods, body.str("note").orEmpty())
        try {
            lab.profile.presets.save(preset)
        } catch (e: IllegalArgumentException) {
            throw LabError(400, e.message ?: "invalid preset")
        }
        return buildJsonObject { put("ok", true); put("id", id) }
    }

    fun deletePreset(id: String): JsonObject {
        if (BuiltInPresets.byId(id) != null) throw LabError(400, "'$id' is a built-in preset and cannot be deleted")
        if (!lab.profile.presets.delete(id)) throw LabError(404, "no user preset '$id'")
        return buildJsonObject { put("ok", true) }
    }

    fun styles(): JsonObject = buildJsonObject {
        putJsonArray("styles") {
            for (s in lab.profile.styles.all()) addJsonObject {
                put("id", s.id); put("name", s.name); put("description", s.description)
                put("builtIn", BuiltInStyles.byId(s.id) != null)
                put("changes", LabViews.strings(s.patch.describe()))
            }
        }
    }

    fun pins(): JsonObject {
        val byIdentity = lab.tracks().associateBy { it.ref.analysis.identity }
        return buildJsonObject {
            putJsonArray("pins") {
                for (p in lab.profile.pins.list()) addJsonObject {
                    put("aLabel", p.aLabel.ifEmpty { p.aFingerprint.take(12) })
                    put("bLabel", p.bLabel.ifEmpty { p.bFingerprint.take(12) })
                    put("a", byIdentity[p.aFingerprint]?.id?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                    put("b", byIdentity[p.bFingerprint]?.id?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                    put("aIdentity", p.aFingerprint); put("bIdentity", p.bFingerprint)
                    put("strategy", p.strategyId)
                    p.presetId?.let { put("preset", it) }
                    putJsonObject("params") { for ((k, v) in p.params?.values.orEmpty().toSortedMap()) put(k, v) }
                    put("note", p.note)
                }
            }
        }
    }

    /** Pins are keyed by the tracks' identities (a hash of the decoded audio), never by path. */
    fun setPin(body: JsonObject): JsonObject {
        val a = lab.track(body.requireStr("a"))
        val b = lab.track(body.requireStr("b"))
        val strategyId = body.requireStr("strategy")
        val strategy = lab.registry.strategy(strategyId) ?: throw LabError(400, "unknown strategy '$strategyId'")
        val values = body.stringMap("params")
        PresetValidation.check(strategy, values)?.let { throw LabError(400, it) }
        val presetId = body.str("preset")?.also { pid ->
            val p = lab.profile.presetLookup.preset(pid) ?: throw LabError(400, "unknown preset '$pid'")
            if (p.strategyId != strategyId) throw LabError(400, "preset '$pid' is for ${p.strategyId}, not $strategyId")
        }
        val pin = PairPin(
            a.ref.analysis.identity, b.ref.analysis.identity, strategyId, presetId,
            params = if (values.isEmpty()) null else Params(values),
            aLabel = a.file.name, bLabel = b.file.name, note = body.str("note").orEmpty(),
        )
        try {
            lab.profile.pins.set(pin)
        } catch (e: IllegalArgumentException) {
            throw LabError(400, e.message ?: "invalid pin")
        }
        val outcome = lab.planner().planExplained(a.ref, b.ref, lab.prefs(body.str("style"))).explanation.pin
        return buildJsonObject {
            put("ok", true)
            outcome?.let { put("used", it.used); put("reason", it.reason) }
        }
    }

    fun clearPin(body: JsonObject): JsonObject {
        val (aId, bId) = identities(body)
        if (!lab.profile.pins.clear(aId, bId)) throw LabError(404, "there is no pin for this pair")
        return buildJsonObject { put("ok", true) }
    }

    /** `{a, b}` as track ids, or `{aIdentity, bIdentity}` for a pin whose tracks are not in this session. */
    private fun identities(body: JsonObject): Pair<String, String> {
        body.str("aIdentity")?.let { ai -> return ai to body.requireStr("bIdentity") }
        return lab.track(body.requireStr("a")).ref.analysis.identity to lab.track(body.requireStr("b")).ref.analysis.identity
    }

    fun ratings(): JsonObject {
        val learner = lab.profile.feedback.learner
        val tallies = learner.snapshot()
        return buildJsonObject {
            putJsonArray("ratings") {
                for ((strategy, f) in learner.table()) {
                    val bucket = f.bucket ?: continue
                    val t = tallies[strategy]?.get(bucket.key) ?: continue
                    addJsonObject {
                        put("strategy", strategy); put("bucket", bucket.label); put("n", t.n); put("skips", t.implicitN)
                        put("mean", LabJson.num(t.sum / t.n, 3)); put("weight", LabJson.num(f.multiplier, 3))
                    }
                }
            }
            put("formula", "weight = 0.5 + (${FeedbackLearner.PRIOR_STRENGTH} + Σ rating) / (${2 * FeedbackLearner.PRIOR_STRENGTH} + n)")
        }
    }

    fun rate(body: JsonObject): JsonObject {
        val a = lab.track(body.requireStr("a"))
        val b = lab.track(body.requireStr("b"))
        val strategyId = body.requireStr("strategy")
        if (lab.registry.strategy(strategyId) == null && !strategyId.startsWith(TransitionRecipe.STRATEGY_PREFIX)) {
            throw LabError(400, "unknown strategy '$strategyId'")
        }
        val rating = try { Rating.parse(body.requireStr("rating")) } catch (e: IllegalArgumentException) { throw LabError(400, e.message ?: "bad rating") }
        val features = lab.features(a.ref, b.ref, lab.prefs(body.str("style")))
        val f = lab.profile.feedback.record(strategyId, features, rating)
        return buildJsonObject {
            put("ok", true); put("strategy", strategyId); put("rating", rating.toString())
            put("bucket", ContextBucket.of(features).label); put("weight", LabJson.num(f.multiplier, 3)); put("describe", f.describe())
        }
    }

    // ---- blind test ---------------------------------------------------------------------------------------------------

    private class BlindItem(val label: String, val result: LabRenderResult, val candidate: JsonObject)

    private class BlindSession(val id: String, val a: String, val b: String, val items: List<BlindItem>) {
        @Volatile var voted: JsonObject? = null
    }

    /**
     * Renders 2–4 candidates for the pair and returns them in a random order under neutral labels (X, Y, Z, W). The
     * result names no strategy, preset or recipe; only [blindVote] reveals which was which.
     */
    fun blind(body: JsonObject): JsonObject {
        val a = body.requireStr("a")
        val b = body.requireStr("b")
        lab.track(a); lab.track(b)
        val candidates = body.objList("candidates")
        if (candidates.size !in 2..BLIND_LABELS.size) throw LabError(400, "a blind test takes 2 to ${BLIND_LABELS.size} candidates, got ${candidates.size}")
        val specs = candidates.map { c ->
            if (c.str("strategy") == null && c.str("preset") == null && c.str("recipe") == null) {
                throw LabError(400, "every candidate needs a strategy, a preset or a recipe")
            }
            RenderSpec.parse(buildJsonObject {
                for ((k, v) in c) put(k, v)
                put("a", a); put("b", b)
                body["style"]?.let { put("style", it) }
                body["contextSec"]?.let { put("contextSec", it) }
                body["seed"]?.let { put("seed", it) }
            }) to c
        }
        return jobOf(jobs.submit("blind") { job ->
            val results = specs.mapIndexed { i, (spec, c) ->
                val lo = i.toDouble() / specs.size
                val hi = (i + 1).toDouble() / specs.size
                job.report(lo, "rendering ${i + 1} of ${specs.size}")
                renderer.render(spec, job = null).also { job.report(hi, "rendered ${i + 1} of ${specs.size}") } to c
            }
            val order = results.indices.toMutableList().also { java.util.Collections.shuffle(it, random) }
            val items = order.mapIndexed { pos, idx -> BlindItem(BLIND_LABELS[pos], results[idx].first, results[idx].second) }
            val session = synchronized(blinds) {
                val s = BlindSession("b${++blindCounter}", a, b, items)
                blinds[s.id] = s
                while (blinds.size > 50) blinds.remove(blinds.keys.first())
                s
            }
            buildJsonObject {
                put("blindId", session.id)
                put("a", a); put("b", b)
                putJsonArray("items") {
                    for (it in items) addJsonObject {
                        val j = it.result.json
                        put("label", it.label)
                        // Only what is needed to listen and to draw a plain waveform: no strategy, lanes, markers or metrics.
                        for (k in listOf("contextUrl", "durationSec", "seams", "segmentStartSec", "segmentEndSec", "peaks")) j[k]?.let { v -> put(k, v) }
                    }
                }
            }
        })
    }

    /**
     * Records the vote: the pick is rated up, every other candidate down (through the [FeedbackLearner], in the pair's
     * context bucket) — except a loser with the same strategy id as the pick, which is left unrated so one vote never
     * rates a strategy both up and down. Then reveals which label was which. A blind test takes one vote.
     */
    fun blindVote(id: String, body: JsonObject): JsonObject {
        val session = synchronized(blinds) { blinds[id] } ?: throw LabError(404, "no blind test '$id'")
        val pick = body.requireStr("pick")
        val picked = session.items.firstOrNull { it.label == pick } ?: throw LabError(400, "no candidate '$pick' in this test (${session.items.joinToString(", ") { it.label }})")
        synchronized(session) {
            session.voted?.let { throw LabError(409, "this blind test has already been voted on") }
            val feedback = lab.profile.feedback
            val features = picked.result.features
            val reveal = buildJsonObject {
                put("blindId", session.id)
                put("pick", pick)
                put("bucket", ContextBucket.of(features).label)
                putJsonArray("items") {
                    for (it in session.items) {
                        val strategyId = it.result.strategyId
                        val rating: Rating? = when {
                            it === picked -> Rating.Up
                            strategyId == picked.result.strategyId -> null
                            else -> Rating.Down
                        }
                        val before = feedback.learner.factor(strategyId, features).multiplier
                        val after = rating?.let { r -> feedback.record(strategyId, features, r).multiplier } ?: before
                        addJsonObject {
                            put("label", it.label)
                            put("strategy", strategyId)
                            put("displayName", it.result.json["displayName"] ?: JsonNull)
                            it.result.presetId?.let { p -> put("preset", p) }
                            put("unsavedRecipe", it.candidate.str("recipe") != null)
                            put("picked", it === picked)
                            put("rating", rating?.toString() ?: "not rated (same strategy as the pick)")
                            put("weightBefore", LabJson.num(before, 3))
                            put("weightAfter", LabJson.num(after, 3))
                            put("worst", it.result.json["worst"] ?: JsonNull)
                            put("render", it.result.json)
                        }
                    }
                }
            }
            session.voted = reveal
            return reveal
        }
    }

    // ---- sweep ---------------------------------------------------------------------------------------------------------

    /** One numeric parameter over a range; every point is a full render (playable) with its metrics. */
    fun sweep(body: JsonObject): JsonObject {
        val base = RenderSpec.parse(body)
        val param = body.requireStr("param")
        val from = body.dbl("from") ?: throw LabError(400, "'from' is required")
        val to = body.dbl("to") ?: throw LabError(400, "'to' is required")
        val steps = (body.lng("steps") ?: 5L).toInt()
        if (steps !in 2..MAX_SWEEP_STEPS) throw LabError(400, "steps must be 2..$MAX_SWEEP_STEPS")
        val strategyId = base.strategy ?: throw LabError(400, "a sweep needs a strategy")
        val strategy = if (base.recipeText != null) null else lab.registry.strategy(strategyId) ?: throw LabError(400, "unknown strategy '$strategyId'")
        val spec = strategy?.params?.firstOrNull { it.id == param }
        if (strategy != null && (spec == null || (spec !is dev.muisc.transitions.ParamSpec.DoubleSpec && spec !is dev.muisc.transitions.ParamSpec.IntSpec))) {
            throw LabError(400, "'$param' is not a numeric parameter of $strategyId")
        }
        val isInt = spec is dev.muisc.transitions.ParamSpec.IntSpec
        val values = sweepValues(from, to, steps, isInt)
        lab.track(base.a); lab.track(base.b)
        return jobOf(jobs.submit("sweep") { job ->
            val points = values.mapIndexed { i, v ->
                job.report(i.toDouble() / values.size, "rendering $param = ${fmt(v)} (${i + 1} of ${values.size})")
                val text = if (isInt) v.toLong().toString() else v.toString()
                val r = renderer.render(RenderSpec(base.a, base.b, strategyId, base.params + (param to text), base.modifiers, base.style, base.preset, base.recipeText, base.contextSec, base.seed, base.limiter))
                buildJsonObject {
                    put("value", LabJson.num(v, 6))
                    putJsonObject("metrics") {
                        for (m in r.metrics.metrics) put(m.id, LabJson.num(m.value, 4))
                        for (m in r.contextMetrics.metrics) put("context." + m.id, LabJson.num(m.value, 4))
                    }
                    putJsonObject("verdicts") {
                        for (m in r.metrics.metrics) put(m.id, m.verdict.name)
                        for (m in r.contextMetrics.metrics) put("context." + m.id, m.verdict.name)
                    }
                    put("render", r.json)
                }
            }
            buildJsonObject {
                put("param", param)
                put("strategy", strategyId)
                put("points", kotlinx.serialization.json.JsonArray(points))
            }
        })
    }

    private fun fmt(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else "%.3f".format(v)

    companion object {
        val BLIND_LABELS = listOf("X", "Y", "Z", "W")
        const val TEMPLATE_ID = "my-transition"

        /** [wanted] when it is not in [taken], else `wanted-2`, `wanted-3`, ... */
        fun freeId(wanted: String, taken: Set<String>): String {
            if (wanted !in taken) return wanted
            var n = 2
            while ("$wanted-$n" in taken) n++
            return "$wanted-$n"
        }
        const val MAX_SWEEP_STEPS = 12

        /**
         * [steps] evenly spaced values from [from] to [to], both ends exact. Interior values are rounded to 12
         * significant digits and clamped into the range, so floating-point steps never produce `0.9000000000000001`
         * for a parameter whose maximum is 0.9 (which the range check would then refuse). Integers are rounded and
         * de-duplicated.
         */
        fun sweepValues(from: Double, to: Double, steps: Int, isInt: Boolean): List<Double> {
            val lo = minOf(from, to)
            val hi = maxOf(from, to)
            return (0 until steps).map { i ->
                val v = when (i) {
                    0 -> from
                    steps - 1 -> to
                    else -> java.math.BigDecimal(from + (to - from) * i / (steps - 1)).round(java.math.MathContext(12)).toDouble()
                }
                v.coerceIn(lo, hi)
            }.map { if (isInt) Math.round(it).toDouble() else it }.distinct()
        }
    }
}
