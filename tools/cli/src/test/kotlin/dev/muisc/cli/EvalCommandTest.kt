package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `muisc eval` run in-process on a temp directory of synthetic songs (the four contract fixtures plus a file that
 * cannot be decoded): the files it promises, what is in them, the exit status against `--fail-on`, and that a
 * reproduce command really reproduces its render. The pair sampler is tested on its own below.
 */
class EvalCommandTest {

    companion object {
        @JvmStatic
        @TempDir
        lateinit var root: File

        private val library: File get() = File(root, "library")
        private lateinit var firstRun: File
        private lateinit var firstOutput: String

        private val JSON = Json { ignoreUnknownKeys = true }

        @JvmStatic
        @BeforeAll
        fun setUp() {
            library.mkdirs()
            Cli.songs(library, File(root, "cache"))
            File(library, "not-audio.wav").writeText("this is not a wav file")
            firstRun = File(root, "eval-1")
            firstOutput = run("eval", library.absolutePath, "--pairs", "6", "--seed", "3", "--out", firstRun.absolutePath)
        }

        /** `muisc eval ...`: the leading "eval" names the command, which is the root of the standalone tree. */
        fun run(vararg args: String): String {
            require(args.first() == "eval")
            return runStandalone(EvalCommand(), root, *args.drop(1).toTypedArray())
        }

        fun json(dir: File): JsonObject = JSON.parseToJsonElement(File(dir, "results.json").readText()).jsonObject
    }

    @Test
    fun `eval writes the report, the CSV and the JSON and skips a file it cannot decode`() {
        for (name in listOf("report.html", "results.csv", "results.json", "prefs.json")) assertTrue(File(firstRun, name).isFile, "$name missing")
        assertContains(firstOutput, "skipped")
        assertContains(firstOutput, "not-audio.wav")

        val j = json(firstRun)
        val summary = j["summary"]!!.jsonObject
        assertEquals(4, summary["tracks"]!!.jsonPrimitive.int)
        assertEquals(1, summary["skipped"]!!.jsonPrimitive.int)
        assertEquals(6, summary["pairs"]!!.jsonPrimitive.int)
        val rows = j["rows"]!!.jsonArray
        assertEquals(6, rows.size)
        val sources = rows.map { it.jsonObject["source"]!!.jsonPrimitive.content }
        // 6 pairs: 3 consecutive-in-a-shuffle, (3 + 1) / 2 = 2 hardest by stretch, 1 hardest by key.
        assertEquals(mapOf("shuffle" to 3, "stretch" to 2, "key" to 1), sources.groupingBy { it }.eachCount())
        val chosen = j["strategies"]!!.jsonArray.sumOf { it.jsonObject["chosen"]!!.jsonPrimitive.int }
        assertEquals(6 - summary["renderErrors"]!!.jsonPrimitive.int, chosen)
        assertEquals(3, j["analysis"]!!.jsonArray.size)
        assertTrue(j["metrics"]!!.jsonArray.isNotEmpty())

        val csv = File(firstRun, "results.csv").readLines()
        assertEquals(7, csv.size, "header + one line per pair")
        assertTrue(csv[0].startsWith("pair,source,a,b,stretch_percent"))
        assertTrue(csv[0].endsWith(",reproduce"))

        val html = File(firstRun, "report.html").readText()
        for (section in listOf("Library evaluation", "Analysis", "What the planner chose", "Metrics", "Worst renders", "All pairs")) assertContains(html, section)
        // Self-contained: nothing fetched from anywhere.
        for (external in listOf("http://", "https://", "<script", "<link", "src=\"", "@import")) assertFalse(html.contains(external), "report.html references '$external'")
    }

    @Test
    fun `the same seed samples the same pairs and plans the same strategies`() {
        val second = File(root, "eval-2")
        run("eval", library.absolutePath, "--pairs", "6", "--seed", "3", "--out", second.absolutePath)
        fun key(dir: File) = json(dir)["rows"]!!.jsonArray.map { r ->
            val o = r.jsonObject
            listOf("a", "b", "source", "strategy", "verdict").joinToString("|") { o[it]!!.jsonPrimitive.content }
        }
        assertEquals(key(firstRun), key(second))
    }

    @Test
    fun `a reproduce command renders the same transition`() {
        val row = json(firstRun)["rows"]!!.jsonArray.first { it.jsonObject["strategy"]!!.jsonPrimitive.content != "null" }.jsonObject
        val command = row["reproduce"]!!.jsonPrimitive.content
        assertTrue(command.startsWith("muisc render "), command)
        // Temp paths have no spaces or quotes, so the command splits on spaces.
        val args = command.removePrefix("muisc render ").split(' ').toTypedArray()
        val out = runStandalone(RenderCommand(), root, *args, "--json")
        assertContains(out, "\"strategy\": \"${row["strategy"]!!.jsonPrimitive.content}\"")
        val wav = File(args[args.indexOf("-o") + 1])
        assertTrue(wav.isFile, "reproduce did not write ${wav.path}")
        val report = JSON.parseToJsonElement(File(wav.parentFile, wav.name.removeSuffix(".wav") + ".report.json").readText()).jsonObject
        val metrics = report["metrics"]!!.jsonObject["metrics"]!!.jsonArray
        val worst = metrics.map { it.jsonObject["verdict"]!!.jsonPrimitive.content }.maxByOrNull { listOf("PASS", "WARN", "FAIL").indexOf(it) }
        assertEquals(row["verdict"]!!.jsonPrimitive.content, worst)
    }

    @Test
    fun `--fail-on exits non-zero when the FAIL rate exceeds it, after writing the report`() {
        // A +6 LUFS target gives every deck the maximum +6 dB deck gain, which drives the synthetic songs past full
        // scale, so every render fails the true-peak or clipping check whatever the strategy does.
        val out = File(root, "eval-loud")
        val e = assertFailsWith<CliktError> {
            run("eval", library.absolutePath, "--pairs", "2", "--seed", "3", "--out", out.absolutePath, "--set-pref", "targetLufs=6", "--fail-on", "10%")
        }
        assertContains(e.message.orEmpty(), "exceeds --fail-on")
        assertTrue(File(out, "report.html").isFile && File(out, "results.json").isFile, "the files must be written before the non-zero exit")
        val j = json(out)
        assertTrue(j["summary"]!!.jsonObject["failOnExceeded"]!!.jsonPrimitive.content == "true")
        for (r in j["rows"]!!.jsonArray) {
            val m = r.jsonObject["metrics"]!!.jsonObject
            val loudFail = listOf("clipping", "truePeakDbtp").any { m[it]?.jsonObject?.get("verdict")?.jsonPrimitive?.content == "FAIL" }
            assertTrue(loudFail, "expected a clipping / true-peak FAIL at +6 dB deck gain: $m")
        }

        // The same run with a limit it does not exceed exits normally.
        run("eval", library.absolutePath, "--pairs", "2", "--seed", "3", "--out", File(root, "eval-loud-ok").absolutePath, "--set-pref", "targetLufs=6", "--fail-on", "1")
    }

    @Test
    fun `eval rejects a bad rate, a directory with no audio and a single track`() {
        assertFailsWith<CliktError> { run("eval", library.absolutePath, "--fail-on", "150%") }
        val empty = File(root, "empty").apply { mkdirs() }
        assertFailsWith<CliktError> { run("eval", empty.absolutePath, "--out", File(root, "eval-empty").absolutePath) }
        assertFailsWith<CliktError> { run("eval", File(library, "t120C.wav").absolutePath, "--out", File(root, "eval-one").absolutePath) }
    }

    // ---- sampler ------------------------------------------------------------------------------------------------

    private fun hardness(a: Int, b: Int) = LibraryEval.Hardness(stretchPercent = ((a * 7 + b * 3) % 11).toDouble(), camelotDistance = (a + 2 * b) % 7)

    @Test
    fun `the sampler is deterministic, distinct and splits half shuffle, a quarter stretch, a quarter key`() {
        val picks = LibraryEval.samplePairs(10, 20, Random(9), ::hardness)
        assertEquals(20, picks.size)
        assertEquals(20, picks.map { it.a to it.b }.toSet().size, "pairs must be distinct")
        assertTrue(picks.all { it.a != it.b })
        assertEquals(10, picks.count { it.source == LibraryEval.Source.SHUFFLE })
        assertEquals(5, picks.count { it.source == LibraryEval.Source.STRETCH })
        assertEquals(5, picks.count { it.source == LibraryEval.Source.KEY })
        assertEquals(picks, LibraryEval.samplePairs(10, 20, Random(9), ::hardness))
        // The stretch picks are the hardest pairs by stretch: none of the pairs left out is harder than the easiest of them.
        val taken = picks.map { it.a to it.b }.toSet()
        val easiestPicked = picks.filter { it.source == LibraryEval.Source.STRETCH }.minOf { hardness(it.a, it.b).stretchPercent }
        val hardestLeft = (0 until 10).flatMap { a -> (0 until 10).filter { it != a }.map { a to it } }.filter { it !in taken }.maxOf { hardness(it.first, it.second).stretchPercent }
        assertTrue(easiestPicked >= hardestLeft, "a harder pair ($hardestLeft) was left out while $easiestPicked was picked")
        // Shuffle chains: a pick's previous-in-chain points at an earlier shuffle pick that ends where it starts.
        for ((i, p) in picks.withIndex()) p.previousInChain?.let { prev ->
            assertTrue(prev < i)
            assertEquals(picks[prev].b, p.a)
        }
        assertNotNull(picks.firstOrNull { it.previousInChain != null }, "consecutive shuffle pairs must be chained")
    }

    @Test
    fun `the sampler never asks for more pairs than exist`() {
        val picks = LibraryEval.samplePairs(3, 50, Random(1), ::hardness)
        assertEquals(6, picks.size)
        assertEquals(6, picks.map { it.a to it.b }.toSet().size)
        assertTrue(LibraryEval.samplePairs(1, 5, Random(1), ::hardness).isEmpty())
    }

    @Test
    fun `with an order function the chains follow it and are labelled smart`() {
        // A stand-in "smart" order: a fixed rotation of 0..9 chosen by the sampler's random stream.
        val chains = ArrayList<List<Int>>()
        val picks = LibraryEval.samplePairs(10, 20, Random(9), ::hardness) { r -> val k = r.nextInt(10); List(10) { (it * 3 + k) % 10 }.also { chains += it } }
        val smart = picks.filter { it.source == LibraryEval.Source.SMART }
        assertEquals(10, smart.size, "the consecutive half keeps its quota")
        assertEquals(0, picks.count { it.source == LibraryEval.Source.SHUFFLE })
        val consecutive = chains.flatMap { c -> (0 until c.size - 1).map { c[it] to c[it + 1] } }.toSet()
        assertTrue(smart.all { (it.a to it.b) in consecutive }, "every smart pick is consecutive in an order the function returned")
        assertEquals(5, picks.count { it.source == LibraryEval.Source.STRETCH })
        assertEquals(5, picks.count { it.source == LibraryEval.Source.KEY })
    }

    @Test
    fun `eval --order smart draws the consecutive pairs from smart-shuffle orders`() {
        val dir = File(root, "eval-smart")
        val text = run("eval", library.absolutePath, "--pairs", "4", "--seed", "3", "--order", "smart", "--out", dir.absolutePath)
        assertContains(text, "consecutive in a smart-shuffle order")
        assertFalse(text.contains("consecutive in a shuffled order"), "the unused chain source is not listed")
        val sources = json(dir)["rows"]!!.jsonArray.map { it.jsonObject["source"]!!.jsonPrimitive.content }
        assertEquals(mapOf("smart" to 2, "stretch" to 1, "key" to 1), sources.groupingBy { it }.eachCount())
        assertFalse(firstOutput.contains("smart-shuffle"), "the default eval does not mention smart orders")
    }

    @Test
    fun `rates parse as fractions or percentages`() {
        assertEquals(0.1, LibraryEval.parseRate("0.1"))
        assertEquals(0.1, LibraryEval.parseRate("10%"))
        assertEquals(0.25, LibraryEval.parseRate(" 25 % "))
        assertEquals(null, LibraryEval.parseRate("1.5"))
        assertEquals(null, LibraryEval.parseRate("abc"))
        assertEquals("'/a b/it'\\''s.wav'", LibraryEval.shellQuote("/a b/it's.wav"))
        assertEquals("/plain/path.wav", LibraryEval.shellQuote("/plain/path.wav"))
    }
}
