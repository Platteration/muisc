package dev.muisc.cli.lab

import dev.muisc.audio.WavIo
import dev.muisc.cli.CliContext
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.UserProfile
import dev.muisc.transitions.recipe.RecipeCodec
import dev.muisc.transitions.recipe.RecipeLibrary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Collections
import java.util.Random
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the Lab over real HTTP: a server on an ephemeral loopback port with the four fixture songs and a private
 * profile directory, called with [HttpClient] (and a raw socket where a header must be forged).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabServerTest {
    private lateinit var root: File
    private lateinit var profileDir: File
    private lateinit var cli: CliContext
    private lateinit var lab: LabContext
    private lateinit var server: LabServer
    private val http: HttpClient = HttpClient.newHttpClient()
    private lateinit var t: List<String>

    @BeforeAll
    fun start() {
        root = Files.createTempDirectory("lab-test-").toFile()
        profileDir = File(root, "profile")
        cli = CliContext(TransitionPrefs(), File(root, "cache"), UserProfile(profileDir))
        lab = LabContext(cli, File(root, "session"))
        t = lab.addFixtures().map { it.id }
        server = LabServer(lab, 0, Random(1)).start()
    }

    @AfterAll
    fun stop() {
        server.close()
        lab.close()
        cli.close()
        root.deleteRecursively()
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private fun base(s: LabServer = server) = "http://127.0.0.1:${s.port}"

    private class Res(val status: Int, val body: String, val headers: java.net.http.HttpHeaders) {
        val json: JsonObject get() = LabJson.json.parseToJsonElement(body).jsonObject
    }

    private fun get(path: String, s: LabServer = server): Res {
        val r = http.send(HttpRequest.newBuilder(URI(base(s) + path)).GET().build(), HttpResponse.BodyHandlers.ofString())
        return Res(r.statusCode(), r.body(), r.headers())
    }

    private fun post(path: String, body: JsonElement, s: LabServer = server): Res {
        val r = http.send(
            HttpRequest.newBuilder(URI(base(s) + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Res(r.statusCode(), r.body(), r.headers())
    }

    private fun bytes(path: String, range: String? = null): HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI(base() + path)).GET()
        if (range != null) b.header("Range", range)
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    /** Polls a job until it finishes; returns its result (fails the test with the job's error). */
    private fun await(res: Res, s: LabServer = server): JsonObject {
        assertEquals(200, res.status, res.body)
        val id = res.json["job"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val deadline = System.nanoTime() + 600_000_000_000L
        while (System.nanoTime() < deadline) {
            val j = get("/api/jobs/$id", s).json
            when (j["status"]!!.jsonPrimitive.content) {
                "done" -> return j["result"]!!.jsonObject
                "failed" -> throw AssertionError("job $id failed: ${j["error"]}")
            }
            Thread.sleep(100)
        }
        throw AssertionError("job $id did not finish")
    }

    /** A raw HTTP/1.1 request, so Host and Origin can be anything; returns the status code. */
    private fun raw(requestLine: String, headers: Map<String, String>, body: String = ""): Int {
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            val text = buildString {
                append(requestLine).append("\r\n")
                for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
                if (body.isNotEmpty()) append("Content-Length: ").append(body.toByteArray().size).append("\r\n")
                append("Connection: close\r\n\r\n").append(body)
            }
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            val status = s.getInputStream().bufferedReader().readLine() ?: error("no response")
            return status.split(' ')[1].toInt()
        }
    }

    private fun host() = mapOf("Host" to "127.0.0.1:${server.port}")

    private fun ids(a: JsonElement?): List<String> = a!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun starter(id: String): String = RecipeCodec.encode(RecipeLibrary.starter(id, id))

    // ---- the page -------------------------------------------------------------------------------------------------------

    @Test
    fun `the page and its own files are served with a strict content security policy`() {
        val page = get("/")
        assertEquals(200, page.status)
        assertTrue(page.headers.firstValue("Content-Type").get().startsWith("text/html"))
        assertTrue(page.body.contains("<title>Muisc Transition Lab</title>"), page.body.take(200))
        val csp = page.headers.firstValue("Content-Security-Policy").orElse("")
        assertTrue(csp.contains("script-src 'self'") && csp.contains("default-src 'self'"), csp)
        assertEquals(200, get("/app.js").status)
        assertEquals(200, get("/app.css").status)
        // The page must work offline: no reference to any other host. (The SVG namespace is an identifier the
        // browser never fetches; it is the one URL-shaped string allowed.)
        for (f in listOf("/", "/app.js", "/app.css")) {
            val text = get(f).body.replace("'http://www.w3.org/2000/svg'", "")
            assertFalse(Regex("https?://").containsMatchIn(text), "$f references an external URL")
        }
        assertEquals(404, get("/nope.html").status)
        assertEquals(404, get("/lab/index.html").status)
    }

    // ---- tracks, strategies, plan -------------------------------------------------------------------------------------

    @Test
    fun `tracks list the fixtures and a track's analysis carries peaks and the beat grid`() {
        val tracks = get("/api/tracks").json["tracks"]!!.jsonArray
        assertEquals(4, tracks.size)
        val first = tracks[0].jsonObject
        assertEquals(120.0, first["bpm"]!!.jsonPrimitive.double, 0.5)
        val a = get("/api/tracks/${t[0]}").json
        assertEquals(LabContext.PEAK_BINS, a["peaks"]!!.jsonObject["bins"]!!.jsonPrimitive.int)
        assertTrue(a["beats"]!!.jsonArray.size > 16)
        assertTrue(a["camelot"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(404, get("/api/tracks/t99").status)
    }

    @Test
    fun `adding a folder from the page analyses its files and skips what it cannot decode`() {
        val dir = File(root, "added").apply { mkdirs() }
        File(root, "session/fixtures/t63G.wav").copyTo(File(dir, "copy.wav"), overwrite = true)
        File(dir, "broken.wav").writeText("not audio")
        val r = await(post("/api/tracks", buildJsonObject { put("path", dir.path) }))
        assertEquals(1, r["added"]!!.jsonArray.size)
        assertEquals(1, r["skipped"]!!.jsonArray.size)
        assertEquals(404, post("/api/tracks", buildJsonObject { put("path", File(root, "missing").path) }).status)
    }

    @Test
    fun `strategies include every built-in, the recipes and the modifiers with their parameter specs`() {
        val s = get("/api/strategies").json
        val ids = ids(s["strategies"])
        assertTrue("crossfade" in ids && "bassSwap" in ids)
        for (r in listOf("smooth-blend", "club-bass-swap", "radio-segue")) assertTrue("recipe:$r" in ids, "missing recipe:$r in $ids")
        val recipe = s["strategies"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == "recipe:smooth-blend" }.jsonObject
        assertEquals(true, recipe["recipe"]!!.jsonPrimitive.content.toBoolean())
        val crossfade = s["strategies"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == "crossfade" }.jsonObject
        val specs = crossfade["params"]!!.jsonArray.map { it.jsonObject }
        assertTrue(specs.isNotEmpty())
        for (p in specs) {
            val type = p["type"]!!.jsonPrimitive.content
            assertTrue(type in setOf("double", "int", "bool", "choice"))
            assertNotNull(p["default"]); assertNotNull(p["doc"])
            if (type == "double" || type == "int") { assertNotNull(p["min"]); assertNotNull(p["max"]); assertNotNull(p["unit"]) }
            if (type == "choice") assertTrue(p["choices"]!!.jsonArray.isNotEmpty())
        }
        assertEquals(setOf("tempoGlide", "textureCarry"), ids(s["modifiers"]).toSet())
    }

    @Test
    fun `plan returns ranked candidates with scores, sub-scores and reasons, and the pair summary`() {
        val p = post("/api/plan", buildJsonObject { put("a", t[0]); put("b", t[1]) }).json
        val c = p["candidates"]!!.jsonArray.map { it.jsonObject }
        assertTrue(c.size >= 3, "only ${c.size} candidates")
        val scores = c.map { it["score"]!!.jsonPrimitive.double }
        assertEquals(scores.sortedDescending(), scores, "ranking is not best-first")
        for (x in c) {
            assertEquals(8, x["subScores"]!!.jsonObject.size)
            assertTrue(x["formula"]!!.jsonPrimitive.content.startsWith("score "))
            assertNotNull(x["reasons"]); assertNotNull(x["blockers"])
        }
        val pair = p["pair"]!!.jsonObject
        assertEquals(120.0, pair["a"]!!.jsonObject["bpm"]!!.jsonPrimitive.double, 0.5)
        assertEquals(126.0, pair["b"]!!.jsonObject["bpm"]!!.jsonPrimitive.double, 0.5)
        assertTrue(pair["stretchPercent"]!!.jsonPrimitive.double > 0)
        assertNotNull(p["skipped"])
        assertEquals(400, post("/api/plan", buildJsonObject { put("a", t[0]); put("b", t[1]); put("style", "no-such-style") }).status)
    }

    // ---- render ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a render job completes and its WAVs parse with the lengths it reports`() {
        val r = await(post("/api/render", buildJsonObject {
            put("a", t[0]); put("b", t[1]); put("strategy", "crossfade"); put("contextSec", 4.0)
        }))
        assertEquals("crossfade", r["strategy"]!!.jsonPrimitive.content)
        val sr = r["sampleRate"]!!.jsonPrimitive.int
        val ctx = bytes(r["contextUrl"]!!.jsonPrimitive.content)
        assertEquals(200, ctx.statusCode())
        val ctxAudio = WavIo.read(ByteArrayInputStream(ctx.body()))
        assertEquals(r["contextFrames"]!!.jsonPrimitive.int, ctxAudio.frames)
        val seg = WavIo.read(ByteArrayInputStream(bytes(r["segmentUrl"]!!.jsonPrimitive.content).body()))
        assertEquals(r["segmentFrames"]!!.jsonPrimitive.int, seg.frames)
        // A-tail (4 s) + segment + B-head (4 s): the seams sit at 4 s and 4 s + segment.
        val seams = r["seams"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(2, seams.size)
        assertEquals(4.0, seams[0], 1.0 / sr + 1e-4)
        assertEquals(seg.frames.toDouble() / sr, seams[1] - seams[0], 2e-4)
        assertEquals(ctxAudio.frames.toDouble() / sr, seams[1] + 4.0, 2e-4)
        assertEquals(LabContext.PEAK_BINS, r["peaks"]!!.jsonObject["bins"]!!.jsonPrimitive.int)
        assertTrue(r["metrics"]!!.jsonArray.isNotEmpty() && r["contextMetrics"]!!.jsonArray.isNotEmpty())
        assertTrue(r["worst"]!!.jsonPrimitive.content in setOf("PASS", "WARN", "FAIL"))
        assertTrue(r["renderKey"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(r["lanes"]!!.jsonArray.isNotEmpty(), "crossfade publishes no lanes?")
        for (lane in r["lanes"]!!.jsonArray) for (pt in lane.jsonObject["points"]!!.jsonArray) {
            val at = pt.jsonObject["t"]!!.jsonPrimitive.double
            assertTrue(at >= seams[0] - 1e-3 && at <= seams[1] + 1e-3, "lane point at $at outside the segment ${seams[0]}..${seams[1]}")
        }

        // The audio element seeks with range requests.
        val part = bytes(r["contextUrl"]!!.jsonPrimitive.content, "bytes=100-199")
        assertEquals(206, part.statusCode())
        assertEquals(100, part.body().size)
        assertEquals(ctx.body().copyOfRange(100, 200).toList(), part.body().toList())
        assertEquals(416, bytes(r["contextUrl"]!!.jsonPrimitive.content, "bytes=999999999-").statusCode())
    }

    @Test
    fun `render values and modifiers are applied and bad values are refused`() {
        val strategies = get("/api/strategies").json["strategies"]!!.jsonArray.map { it.jsonObject }
        val crossfade = strategies.first { it["id"]!!.jsonPrimitive.content == "crossfade" }
        val spec = crossfade["params"]!!.jsonArray.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "double" || it["type"]!!.jsonPrimitive.content == "int" }
        val id = spec["id"]!!.jsonPrimitive.content
        val max = spec["max"]!!.jsonPrimitive.double
        val value = if (spec["type"]!!.jsonPrimitive.content == "int") max.toLong().toString() else max.toString()
        val r = await(post("/api/render", buildJsonObject {
            put("a", t[0]); put("b", t[1]); put("strategy", "crossfade"); put("contextSec", 1.0)
            putJsonObject("params") { put(id, value) }
            putJsonArray("modifiers") { }
        }))
        assertEquals(value.toDouble(), r["params"]!!.jsonObject[id]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue(r["modifiers"]!!.jsonArray.isEmpty())
        // Out of range and unknown ids are refused before any job is queued... as a failed job with the reason.
        val bad = post("/api/render", buildJsonObject {
            put("a", t[0]); put("b", t[1]); put("strategy", "crossfade")
            putJsonObject("params") { put(id, (max * 10 + 1).toString()) }
        })
        val error = assertFailsWith<AssertionError> { await(bad) }
        assertTrue(error.message!!.contains("outside"), error.message)
        assertEquals(404, post("/api/render", buildJsonObject { put("a", "t42"); put("b", t[1]) }).status)
        assertEquals(400, post("/api/render", buildJsonObject { put("a", t[0]); put("b", t[1]); put("contextSec", 1000) }).status)
    }

    // ---- recipes --------------------------------------------------------------------------------------------------------

    @Test
    fun `a recipe with errors validates with a path and a line`() {
        // A misspelt key: a shape error from the codec, located on its line.
        val typo = starter("typo-test").replace("\"level\"", "\"levle\"")
        val v1 = post("/api/recipes/validate", buildJsonObject { put("text", typo) }).json
        assertEquals(false, v1["ok"]!!.jsonPrimitive.content.toBoolean())
        val p1 = v1["problems"]!!.jsonArray.map { it.jsonObject }.first { it["severity"]!!.jsonPrimitive.content == "error" }
        assertTrue(p1["path"]!!.jsonPrimitive.content.contains("levle"), p1.toString())
        val line = p1["line"]!!.jsonPrimitive.int
        assertTrue(typo.lines()[line - 1].contains("levle"), "line $line is '${typo.lines()[line - 1]}'")

        // A value out of range: a validator error, located through the codec's positions.
        val range = starter("range-test").replace("\"default\": 16", "\"default\": 99")
        assertTrue(range != starter("range-test"), "the template changed shape; update this test")
        val v2 = post("/api/recipes/validate", buildJsonObject { put("text", range) }).json
        val p2 = v2["problems"]!!.jsonArray.map { it.jsonObject }.first { it["severity"]!!.jsonPrimitive.content == "error" }
        assertTrue(p2["path"]!!.jsonPrimitive.content.startsWith("vars.len"), p2.toString())
        assertNotNull(p2["line"]!!.jsonPrimitive.content.toIntOrNull(), p2.toString())

        // Broken JSON: line and column of the syntax error.
        val v3 = post("/api/recipes/validate", buildJsonObject { put("text", "{\n  \"id\": \"x\",\n  oops\n}") }).json
        val p3 = v3["problems"]!!.jsonArray.first().jsonObject
        assertEquals(3, p3["line"]!!.jsonPrimitive.int)

        val ok = post("/api/recipes/validate", buildJsonObject { put("text", starter("fine")) }).json
        assertEquals(true, ok["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("len"), ids(ok["vars"]))
    }

    @Test
    fun `recipe lanes are resolved at the given knob values`() {
        val text = starter("lanes-test")
        val at8 = post("/api/recipes/lanes", buildJsonObject { put("text", text); putJsonObject("vars") { put("len", 8) } }).json
        val at32 = post("/api/recipes/lanes", buildJsonObject { put("text", text); putJsonObject("vars") { put("len", 32) } }).json
        assertEquals(8.0, at8["lengthBars"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(32.0, at32["lengthBars"]!!.jsonPrimitive.double, 1e-9)
        val lanes = at8["lanes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("a.level" in lanes && "b.level" in lanes && "b.low" in lanes, lanes.toString())
        val bLow = at8["lanes"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "b.low" }
        // The starter holds B's lows at -12 dB until bar len/2 - 1.
        assertEquals(3.0, bLow["points"]!!.jsonArray[0].jsonObject["bar"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun `an unsaved recipe renders without being added to the strategies`() {
        val r = await(post("/api/render", buildJsonObject {
            put("a", t[0]); put("b", t[1]); put("recipe", starter("unsaved-test")); put("contextSec", 2.0)
            putJsonObject("params") { put("len", 8) }
        }))
        assertEquals("recipe:unsaved-test", r["strategy"]!!.jsonPrimitive.content)
        assertEquals(true, r["unsavedRecipe"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("8", r["params"]!!.jsonObject["len"]!!.jsonPrimitive.content.substringBefore('.'))
        assertFalse("recipe:unsaved-test" in ids(get("/api/strategies").json["strategies"]))
        assertFalse(File(profileDir, "recipes/unsaved-test.json").exists())

        val broken = post("/api/render", buildJsonObject { put("a", t[0]); put("b", t[1]); put("recipe", starter("x").replace("\"level\"", "\"levle\"")) })
        val e = assertFailsWith<AssertionError> { await(broken) }
        assertTrue(e.message!!.contains("levle"), e.message)
    }

    @Test
    fun `a saved recipe appears among the strategies and in the recipe list`() {
        assertFalse("recipe:saved-test" in ids(get("/api/strategies").json["strategies"]))
        val saved = post("/api/recipes/save", buildJsonObject { put("text", starter("saved-test")) }).json
        assertEquals(true, saved["ok"]!!.jsonPrimitive.content.toBoolean(), saved.toString())
        assertTrue(File(profileDir, "recipes/saved-test.json").isFile)
        assertTrue("recipe:saved-test" in ids(get("/api/strategies").json["strategies"]))
        assertTrue("saved-test" in ids(get("/api/recipes").json["recipes"]))
        assertTrue(get("/api/recipes/text/saved-test").json["text"]!!.jsonPrimitive.content.contains("saved-test"))

        // A recipe with errors is not saved.
        val bad = post("/api/recipes/save", buildJsonObject { put("text", starter("bad-save").replace("\"default\": 16", "\"default\": 99")) }).json
        assertEquals(false, bad["ok"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(File(profileDir, "recipes/bad-save.json").exists())
    }

    @Test
    fun `saving over an existing user recipe asks first and writes nothing until replace is confirmed`() {
        val file = File(profileDir, "recipes/keep-me.json")
        val first = RecipeCodec.encode(RecipeLibrary.starter("keep-me", "Sunset blend"))
        assertEquals(true, post("/api/recipes/save", buildJsonObject { put("text", first) }).json["ok"]!!.jsonPrimitive.content.toBoolean())
        val before = file.readText()

        // Same id, another recipe: refused with a conflict that names the recipe it would replace; the file is untouched.
        val second = RecipeCodec.encode(RecipeLibrary.starter("keep-me", "Club cut"))
        val conflict = post("/api/recipes/save", buildJsonObject { put("text", second) })
        assertEquals(200, conflict.status, conflict.body)
        assertEquals(false, conflict.json["ok"]!!.jsonPrimitive.content.toBoolean(), conflict.body)
        assertEquals(true, conflict.json["conflict"]?.jsonPrimitive?.content?.toBoolean(), conflict.body)
        assertEquals("keep-me", conflict.json["id"]?.jsonPrimitive?.content)
        assertEquals("Sunset blend", conflict.json["existingName"]?.jsonPrimitive?.content)
        assertEquals(before, file.readText())
        assertEquals(listOf("keep-me.json"), File(profileDir, "recipes").list()!!.filter { it.startsWith("keep-me") })

        // "replace": false is the same as leaving it out.
        val no = post("/api/recipes/save", buildJsonObject { put("text", second); put("replace", false) }).json
        assertEquals(true, no["conflict"]?.jsonPrimitive?.content?.toBoolean(), no.toString())
        assertEquals(before, file.readText())

        // Confirmed: replaced.
        val yes = post("/api/recipes/save", buildJsonObject { put("text", second); put("replace", true) }).json
        assertEquals(true, yes["ok"]!!.jsonPrimitive.content.toBoolean(), yes.toString())
        assertTrue(file.readText().contains("Club cut"))
        assertEquals(null, yes["conflict"])
    }

    @Test
    fun `the blank template takes a recipe id that is not in use`() {
        val firstText = get("/api/recipes/template").json["text"]!!.jsonPrimitive.content
        val firstId = RecipeCodec.parse(firstText).recipe!!.id
        assertEquals("my-transition", firstId) // no other test saves this id
        assertEquals(true, post("/api/recipes/save", buildJsonObject { put("text", firstText) }).json["ok"]!!.jsonPrimitive.content.toBoolean())

        // A second blank template must not reuse the id just saved.
        val secondText = get("/api/recipes/template").json["text"]!!.jsonPrimitive.content
        val secondId = RecipeCodec.parse(secondText).recipe!!.id
        assertEquals("my-transition-2", secondId, "the template must not reuse '$firstId'")
        assertTrue(RecipeLibrary(File(profileDir, "recipes")).load().all(secondId).isEmpty(), secondId)
        val saved = post("/api/recipes/save", buildJsonObject { put("text", secondText) }).json
        assertEquals(true, saved["ok"]!!.jsonPrimitive.content.toBoolean(), saved.toString())
        assertTrue(File(profileDir, "recipes/$firstId.json").isFile)
        assertTrue(File(profileDir, "recipes/$secondId.json").isFile)
    }

    // ---- blind test -----------------------------------------------------------------------------------------------------

    @Test
    fun `a blind test returns shuffled neutral labels and the vote updates the learned weights`() {
        val seed = 11L
        val n = 3
        val expected = (0 until n).toMutableList().also { Collections.shuffle(it, Random(seed)) }
        assertTrue(expected != (0 until n).toList(), "pick a seed whose shuffle is not the identity")
        val blindServer = LabServer(lab, 0, Random(seed)).start()
        try {
            val candidates = listOf("crossfade", "phraseCut", "echoOut")
            val res = await(post("/api/blind", buildJsonObject {
                put("a", t[0]); put("b", t[1]); put("contextSec", 1.0)
                putJsonArray("candidates") { for (c in candidates) addJsonObject { put("strategy", c) } }
            }, blindServer), blindServer)
            val items = res["items"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("X", "Y", "Z"), items.map { it["label"]!!.jsonPrimitive.content })
            val text = res.toString()
            for (c in candidates) assertFalse(text.contains(c, ignoreCase = true), "the blind result gives away '$c'")
            assertFalse(text.contains("strategy") || text.contains("metrics") || text.contains("lanes"), text.take(400))
            for (it in items) assertEquals(200, bytes(it["contextUrl"]!!.jsonPrimitive.content).statusCode())

            val features = lab.features(lab.track(t[0]).ref, lab.track(t[1]).ref, lab.prefs(null))
            val learner = lab.profile.feedback.learner
            val before = candidates.associateWith { learner.factor(it, features).multiplier }

            val blindId = res["blindId"]!!.jsonPrimitive.content
            val vote = post("/api/blind/$blindId/vote", buildJsonObject { put("pick", "Y") }, blindServer)
            assertEquals(200, vote.status, vote.body)
            val reveal = vote.json["items"]!!.jsonArray.map { it.jsonObject }
            // The reveal follows the shuffle: label k is candidate expected[k].
            assertEquals(expected.map { candidates[it] }, reveal.map { it["strategy"]!!.jsonPrimitive.content })
            val picked = candidates[expected[1]]
            for (c in candidates) {
                val after = learner.factor(c, features).multiplier
                if (c == picked) assertTrue(after > before.getValue(c), "$c: $after after an up-vote, was ${before[c]}")
                else assertTrue(after < before.getValue(c), "$c: $after after a down-vote, was ${before[c]}")
            }
            assertTrue(File(profileDir, "feedback.json").isFile)
            // The planner sees the vote at once.
            val plan = post("/api/plan", buildJsonObject { put("a", t[0]); put("b", t[1]) }, blindServer).json
            val cand = plan["candidates"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["strategy"]!!.jsonPrimitive.content == picked }
            if (cand != null) assertTrue(cand["learned"]!!.jsonPrimitive.double > 1.0)
            // One vote per test.
            assertEquals(409, post("/api/blind/$blindId/vote", buildJsonObject { put("pick", "X") }, blindServer).status)
            assertEquals(400, post("/api/blind", buildJsonObject {
                put("a", t[0]); put("b", t[1]); putJsonArray("candidates") { addJsonObject { put("strategy", "crossfade") } }
            }, blindServer).status)
        } finally {
            blindServer.close()
        }
    }

    @Test
    fun `a blind vote never rates one strategy both up and down`() {
        val res = await(post("/api/blind", buildJsonObject {
            put("a", t[3]); put("b", t[2]); put("contextSec", 0.5)
            putJsonArray("candidates") {
                addJsonObject { put("strategy", "crossfade") }
                addJsonObject { put("strategy", "crossfade"); put("seed", 3) }
            }
        }))
        val features = lab.features(lab.track(t[3]).ref, lab.track(t[2]).ref, lab.prefs(null))
        val before = lab.profile.feedback.learner.tally("crossfade", dev.muisc.transitions.custom.ContextBucket.of(features))
        val reveal = post("/api/blind/${res["blindId"]!!.jsonPrimitive.content}/vote", buildJsonObject { put("pick", "X") }).json
        val ratings = reveal["items"]!!.jsonArray.map { it.jsonObject["rating"]!!.jsonPrimitive.content }
        assertEquals("up", ratings[0])
        assertTrue(ratings[1].startsWith("not rated"), ratings.toString())
        val after = lab.profile.feedback.learner.tally("crossfade", dev.muisc.transitions.custom.ContextBucket.of(features))
        assertEquals(before.n + 1, after.n)
        assertEquals(before.sum + 1.0, after.sum, 1e-9)
    }

    @Test
    fun `closing the lab deletes its session directory with every render`() {
        val dir = Files.createTempDirectory("lab-close-").toFile()
        val other = LabContext(cli, dir)
        val f = other.newRenderFile("ctx").apply { writeText("x") }
        assertEquals(f.canonicalFile, other.servedFile(f.name))
        other.close()
        assertFalse(dir.exists())
    }

    // ---- presets, styles, pins, ratings, sweep ---------------------------------------------------------------------

    @Test
    fun `presets save and delete, styles list, pins set and clear, ratings record`() {
        val save = post("/api/presets", buildJsonObject {
            put("id", "lab-quick"); put("name", "Quick"); put("strategy", "crossfade")
            putJsonObject("params") { }
        })
        assertEquals(200, save.status, save.body)
        assertTrue("lab-quick" in ids(get("/api/presets").json["presets"]))
        assertEquals(400, post("/api/presets", buildJsonObject {
            put("id", "lab-bad"); put("strategy", "crossfade"); putJsonObject("params") { put("nope", "1") }
        }).status)
        val del = http.send(HttpRequest.newBuilder(URI(base() + "/api/presets/lab-quick")).header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, del.statusCode(), del.body())
        assertFalse("lab-quick" in ids(get("/api/presets").json["presets"]))

        val styles = ids(get("/api/styles").json["styles"])
        assertTrue(styles.containsAll(listOf("smooth", "club", "radio", "chill", "adventurous", "purist")), styles.toString())

        val pin = post("/api/pins", buildJsonObject { put("a", t[2]); put("b", t[3]); put("strategy", "crossfade") })
        assertEquals(200, pin.status, pin.body)
        val pins = get("/api/pins").json["pins"]!!.jsonArray.map { it.jsonObject }
        val p = pins.single { it["a"]!!.jsonPrimitive.content == t[2] }
        assertEquals(lab.track(t[2]).ref.analysis.identity, p["aIdentity"]!!.jsonPrimitive.content)
        val plan = post("/api/plan", buildJsonObject { put("a", t[2]); put("b", t[3]) }).json
        assertEquals("crossfade", plan["candidates"]!!.jsonArray[0].jsonObject["strategy"]!!.jsonPrimitive.content)
        assertEquals(true, plan["candidates"]!!.jsonArray[0].jsonObject["pinned"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(200, post("/api/pins/clear", buildJsonObject { put("a", t[2]); put("b", t[3]) }).status)
        assertTrue(get("/api/pins").json["pins"]!!.jsonArray.none { it.jsonObject["a"]!!.jsonPrimitive.content == t[2] })

        val rate = post("/api/ratings", buildJsonObject { put("a", t[3]); put("b", t[0]); put("strategy", "phraseCut"); put("rating", "up") })
        assertEquals(200, rate.status, rate.body)
        assertTrue(get("/api/ratings").json["ratings"]!!.jsonArray.any { it.jsonObject["strategy"]!!.jsonPrimitive.content == "phraseCut" })
        assertEquals(400, post("/api/ratings", buildJsonObject { put("a", t[3]); put("b", t[0]); put("strategy", "phraseCut"); put("rating", "meh") }).status)
    }

    @Test
    fun `a sweep renders one playable point per value with its metrics`() {
        val strategies = get("/api/strategies").json["strategies"]!!.jsonArray.map { it.jsonObject }
        val crossfade = strategies.first { it["id"]!!.jsonPrimitive.content == "crossfade" }
        val spec = crossfade["params"]!!.jsonArray.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "double" }
        val lo = spec["min"]!!.jsonPrimitive.double
        val hi = spec["max"]!!.jsonPrimitive.double
        val r = await(post("/api/sweep", buildJsonObject {
            put("a", t[0]); put("b", t[1]); put("strategy", "crossfade"); put("contextSec", 1.0)
            put("param", spec["id"]!!.jsonPrimitive.content); put("from", lo); put("to", hi); put("steps", 3)
        }))
        val points = r["points"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, points.size)
        assertEquals(lo, points[0]["value"]!!.jsonPrimitive.double, 1e-6)
        assertEquals(hi, points[2]["value"]!!.jsonPrimitive.double, 1e-6)
        for (p in points) {
            assertTrue(p["metrics"]!!.jsonObject.containsKey("clicks"))
            assertEquals(200, bytes(p["render"]!!.jsonObject["contextUrl"]!!.jsonPrimitive.content).statusCode())
        }
        assertEquals(400, post("/api/sweep", buildJsonObject {
            put("a", t[0]); put("b", t[1]); put("strategy", "crossfade"); put("param", "nope"); put("from", 0); put("to", 1)
        }).status)
    }

    @Test
    fun `sweep values end exactly on the range and never step outside it`() {
        // 0.3 + 0.6 * 4 / 4 is 0.9000000000000001 in doubles; echoOut's feedback maximum is 0.9.
        assertTrue(0.3 + (0.9 - 0.3) * 4 / 4 > 0.9, "this JVM's arithmetic no longer shows the problem")
        val v = LabApi.sweepValues(0.3, 0.9, 5, false)
        assertEquals(listOf(0.3, 0.45, 0.6, 0.75, 0.9), v)
        assertEquals(listOf(0.9, 0.75, 0.6, 0.45, 0.3), LabApi.sweepValues(0.9, 0.3, 5, false))
        assertEquals(listOf(4.0, 5.0, 6.0), LabApi.sweepValues(4.0, 6.0, 5, true))
        // End to end: the page's default sweep (the parameter's own min..max) over echoOut's feedback renders.
        val r = await(post("/api/sweep", buildJsonObject {
            put("a", t[0]); put("b", t[1]); put("strategy", "echoOut"); put("contextSec", 0.5)
            put("param", "feedback"); put("from", 0.3); put("to", 0.9); put("steps", 5)
        }))
        assertEquals(0.9, r["points"]!!.jsonArray.last().jsonObject["value"]!!.jsonPrimitive.double, 0.0)
    }

    // ---- who may connect, what may be read --------------------------------------------------------------------------------

    @Test
    fun `render files are served only from the session's render directory`() {
        val secret = File(root, "secret.wav").apply { writeText("private") }
        assertTrue(secret.isFile)
        for (path in listOf(
            "/files/..%2Fsecret.wav", "/files/../secret.wav", "/files/..%2F..%2Fsecret.wav", "/files/%2e%2e%2fsecret.wav",
            "/files/../../etc/passwd", "/files/ctx-0000.wav", "/files/", "/files/.hidden.wav", "/files/CTX.WAV",
            "/../secret.wav", "/%2e%2e/secret.wav",
        )) {
            val code = raw("GET $path HTTP/1.1", host())
            assertTrue(code == 404 || code == 400, "$path answered $code")
        }
        assertEquals(null, lab.servedFile("../secret.wav"))
        assertEquals(null, lab.servedFile("..%2Fsecret.wav"))
    }

    @Test
    fun `the server listens on loopback only and refuses foreign hosts, origins and form posts`() {
        assertTrue(server.address.isLoopbackAddress, "bound to ${server.address}")
        val other = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it is Inet4Address }
        Assumptions.assumeTrue(other != null, "this machine has no non-loopback IPv4 address to try")
        assertFailsWith<ConnectException>("reachable on ${other!!.hostAddress}") {
            Socket().use { it.connect(InetSocketAddress(other, server.port), 2000) }
        }

        assertEquals(200, raw("GET /api/session HTTP/1.1", host()))
        // DNS rebinding: a hostile name pointed at 127.0.0.1.
        assertEquals(403, raw("GET /api/session HTTP/1.1", mapOf("Host" to "evil.example:${server.port}")))
        assertEquals(403, raw("GET /api/tracks HTTP/1.1", mapOf("Host" to "evil.example")))
        // Another site in the same browser.
        val body = "{\"a\":\"${t[0]}\",\"b\":\"${t[1]}\"}"
        assertEquals(403, raw("POST /api/plan HTTP/1.1", host() + mapOf("Origin" to "http://evil.example", "Content-Type" to "application/json"), body))
        assertEquals(403, raw("POST /api/plan HTTP/1.1", host() + mapOf("Origin" to "null", "Content-Type" to "application/json"), body))
        // A cross-site form post cannot send JSON.
        assertEquals(403, raw("POST /api/plan HTTP/1.1", host() + mapOf("Content-Type" to "text/plain"), body))
        assertEquals(403, raw("POST /api/plan HTTP/1.1", host() + mapOf("Content-Type" to "application/x-www-form-urlencoded"), body))
        // The page's own requests pass.
        assertEquals(200, raw("POST /api/plan HTTP/1.1", host() + mapOf("Origin" to "http://127.0.0.1:${server.port}", "Content-Type" to "application/json"), body))
        assertEquals(200, raw("POST /api/plan HTTP/1.1", mapOf("Host" to "localhost:${server.port}", "Content-Type" to "application/json; charset=utf-8"), body))
    }

    @Test
    fun `guard and range parsing`() {
        val port = 8765
        assertEquals(null, LabGuard.refusal("127.0.0.1:8765", null, "GET", null, port))
        assertEquals(null, LabGuard.refusal("LOCALHOST:8765", "http://localhost:8765", "POST", "application/json", port))
        assertNotNull(LabGuard.refusal("127.0.0.1:8766", null, "GET", null, port))
        assertNotNull(LabGuard.refusal(null, null, "GET", null, port))
        assertNotNull(LabGuard.refusal("127.0.0.1:8765", "http://127.0.0.1:8765.evil.example", "GET", null, port))
        assertNotNull(LabGuard.refusal("127.0.0.1:8765", null, "DELETE", null, port))

        assertEquals(0L to 99L, LabServer.parseRange("bytes=0-99", 1000))
        assertEquals(900L to 999L, LabServer.parseRange("bytes=900-", 1000))
        assertEquals(950L to 999L, LabServer.parseRange("bytes=-50", 1000))
        assertEquals(990L to 999L, LabServer.parseRange("bytes=990-5000", 1000))
        assertEquals(-1L to -1L, LabServer.parseRange("bytes=1000-", 1000))
        assertEquals(null, LabServer.parseRange("bytes=0-1,5-6", 1000))
        assertEquals(null, LabServer.parseRange("items=0-1", 1000))
    }

    @Test
    fun `json numbers are finite or null`() {
        assertEquals(kotlinx.serialization.json.JsonNull, LabJson.num(Double.NaN))
        assertEquals(kotlinx.serialization.json.JsonNull, LabJson.num(Double.POSITIVE_INFINITY))
        assertEquals(JsonPrimitive(1.235), LabJson.num(1.23456, 3))
        assertTrue(abs((LabJson.num(-0.0001, 3) as JsonPrimitive).double) < 1e-9)
        assertTrue(JsonArray(emptyList()).isEmpty())
    }
}
