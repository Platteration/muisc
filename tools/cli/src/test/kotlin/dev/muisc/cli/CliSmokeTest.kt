package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import dev.muisc.audio.WavIo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Smoke tests for every `muisc` subcommand: each one is run in-process on synthetic WAVs in a temp directory and
 * checked for the artifacts it promises (WAV, JSON, CSV, SVG, HTML) and for the key lines of its output. They are
 * deliberately shallow — the engine's own tests cover the DSP; these guard the wiring, the switches and the
 * readability of the errors.
 */
class CliSmokeTest {

    companion object {
        @JvmStatic
        @TempDir
        lateinit var root: File

        private val cache: File get() = File(root, "cache")
        private val songsDir: File get() = File(root, "songs")
        private lateinit var songs: Cli.Songs

        @JvmStatic
        @BeforeAll
        fun setUp() {
            cache.mkdirs()
            songsDir.mkdirs()
            songs = Cli.songs(songsDir, cache)
        }

        private val JSON = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true; isLenient = true }
    }

    private fun run(vararg args: String) = Cli.run(cache, *args)
    private fun out(name: String) = File(root, "out/$name")

    // ---- synth ----------------------------------------------------------------------------------------------

    @Test
    fun `synth --set writes the four contract fixtures`() {
        for (f in listOf(songs.a, songs.b, songs.c, songs.d)) {
            assertTrue(f.isFile, "${f.name} was not written")
            val audio = WavIo.read(f)
            assertTrue(audio.durationSec > 20.0, "${f.name} is only ${audio.durationSec} s")
            assertEquals(2, audio.channelCount)
            assertEquals(44100, audio.sampleRate)
        }
    }

    @Test
    fun `synth encodes its parameters in the file name`() {
        val dir = File(root, "synth-one")
        val text = run("synth", "--out", dir.absolutePath, "--bpm", "128", "--key", "F#m", "--bars", "8", "--intro", "2", "--outro", "2", "--fade")
        val written = dir.listFiles()!!.single()
        assertTrue(written.name.startsWith("t128_Fsm_8bars_i2o2f"), "unexpected name ${written.name}")
        assertContains(text, "128.0 BPM")
        assertContains(text, "fade")
    }

    @Test
    fun `synth rejects an unparsable key and an impossible bar layout`() {
        val dir = File(root, "synth-bad")
        assertFailsWith<CliktError> { run("synth", "--out", dir.absolutePath, "--key", "H#") }
        assertFailsWith<CliktError> { run("synth", "--out", dir.absolutePath, "--bars", "4", "--intro", "4", "--outro", "4") }
    }

    // ---- analyze --------------------------------------------------------------------------------------------

    @Test
    fun `analyze reports the synthesised tempo and key`() {
        val text = run("analyze", songs.a.absolutePath)
        val bpm = Regex("""tempo\s+([\d.]+) BPM""").find(text)?.groupValues?.get(1)?.toDouble()
        assertTrue(bpm != null && bpm in 119.0..121.0, "expected ~120 BPM, got $bpm in:\n$text")
        assertContains(text, "C (8B)")
        assertContains(text, "sections")
        assertContains(text, "cues")
        assertContains(text, "LUFS")
    }

    @Test
    fun `analyze --json emits a parsable TrackAnalysis`() {
        val text = run("analyze", songs.b.absolutePath, "--json")
        val json = JSON.parseToJsonElement(text.substringAfter('{').let { "{$it" }.substringBeforeLast('}') + "}").jsonObject
        val bpm = json["tempo"]!!.jsonObject["bpm"]!!.jsonPrimitive.content.toDouble()
        assertTrue(bpm in 125.0..127.0, "bpm $bpm")
        assertTrue(json.containsKey("grid"))
        assertTrue(json.containsKey("loudness"))
    }

    @Test
    fun `analyze --click writes a click track of the same length`() {
        val click = out("t120C.click.wav")
        run("analyze", songs.a.absolutePath, "--click", click.absolutePath)
        assertTrue(click.isFile)
        assertEquals(WavIo.read(songs.a).frames, WavIo.read(click).frames)
    }

    @Test
    fun `analyze --click refuses more than one file`() {
        val e = assertFailsWith<CliktError> {
            run("analyze", songs.a.absolutePath, songs.b.absolutePath, "--click", out("x.wav").absolutePath)
        }
        assertContains(e.message ?: "", "exactly one file")
    }

    // ---- plan -----------------------------------------------------------------------------------------------

    @Test
    fun `plan ranks candidates with sub-scores and blockers`() {
        val text = run("plan", songs.a.absolutePath, songs.b.absolutePath)
        assertContains(text, "candidates (")
        assertContains(text, "crossfade")
        assertContains(text, "sub-scores")
        assertContains(text, "tempo ")
        assertTrue(text.contains("skipped (") || text.contains("blockers"), "expected blocked strategies to be explained")
    }

    @Test
    fun `plan --json carries the features and every candidate`() {
        val text = run("plan", songs.a.absolutePath, songs.b.absolutePath, "--json")
        val json = JSON.parseToJsonElement(text.trim().substringBeforeLast("planned").trim()).jsonObject
        assertTrue(json["features"]!!.jsonObject.containsKey("tempoRatio"))
        val candidates = json["candidates"]!!.jsonArray
        assertTrue(candidates.size >= 2, "only ${candidates.size} candidates")
        assertTrue(candidates.all { it.jsonObject.containsKey("score") })
    }

    @Test
    fun `plan --previous applies the variety penalty`() {
        val best = run("plan", songs.a.absolutePath, songs.b.absolutePath, "--json")
        val bestId = JSON.parseToJsonElement(best.trim().substringBeforeLast("planned").trim())
            .jsonObject["candidates"]!!.jsonArray.first().jsonObject["strategyId"]!!.jsonPrimitive.content
        val penalised = run("plan", songs.a.absolutePath, songs.b.absolutePath, "--json", "--previous", bestId)
        val scores = JSON.parseToJsonElement(penalised.trim().substringBeforeLast("planned").trim())
            .jsonObject["candidates"]!!.jsonArray.associate {
                it.jsonObject["strategyId"]!!.jsonPrimitive.content to it.jsonObject["score"]!!.jsonPrimitive.content.toDouble()
            }
        val before = JSON.parseToJsonElement(best.trim().substringBeforeLast("planned").trim())
            .jsonObject["candidates"]!!.jsonArray.associate {
                it.jsonObject["strategyId"]!!.jsonPrimitive.content to it.jsonObject["score"]!!.jsonPrimitive.content.toDouble()
            }
        assertTrue(scores.getValue(bestId) < before.getValue(bestId), "the repeated strategy should score lower")
    }

    // ---- render ---------------------------------------------------------------------------------------------

    @Test
    fun `render writes the segment, its plan and its report`() {
        val wav = out("render/basic.wav")
        val text = run("render", songs.a.absolutePath, songs.b.absolutePath, "-o", wav.absolutePath, "--strategy", "crossfade")
        assertTrue(wav.isFile)
        val plan = File(wav.parentFile, "basic.plan.json")
        val report = File(wav.parentFile, "basic.report.json")
        assertTrue(plan.isFile, "plan side-car missing")
        assertTrue(report.isFile, "report side-car missing")
        assertContains(plan.readText(), "\"strategyId\": \"crossfade\"")
        val reportJson = JSON.parseToJsonElement(report.readText()).jsonObject
        assertEquals("crossfade", reportJson["strategy"]!!.jsonPrimitive.content)
        assertTrue(reportJson["renderKey"]!!.jsonPrimitive.content.length == 64, "renderKey should be a sha256 hex")
        assertContains(text, "renderKey:")
        assertContains(text, "metrics:")
        assertTrue(WavIo.read(wav).frames > 1000)
    }

    @Test
    fun `render --context runs the seams through the real player`() {
        val wav = out("render/ctx.wav")
        val text = run("render", songs.a.absolutePath, songs.b.absolutePath, "-o", wav.absolutePath, "--strategy", "crossfade", "--context", "4")
        val context = File(wav.parentFile, "ctx.context.wav")
        assertTrue(context.isFile, "context render missing")
        val segment = WavIo.read(wav)
        val whole = WavIo.read(context)
        assertTrue(whole.frames > segment.frames, "the context render must be longer than the segment")
        assertTrue(whole.frames <= segment.frames + 9 * 44100, "the context render should add about 2 x 4 s")
        assertContains(text, "seams at")
    }

    @Test
    fun `render --set changes the plan and the render key`() {
        val short = out("render/short.wav")
        val long = out("render/long.wav")
        run("render", songs.a.absolutePath, songs.b.absolutePath, "-o", short.absolutePath, "--strategy", "crossfade", "--set", "fadeSec=3")
        run("render", songs.a.absolutePath, songs.b.absolutePath, "-o", long.absolutePath, "--strategy", "crossfade", "--set", "fadeSec=10")
        val a = WavIo.read(short).frames
        val b = WavIo.read(long).frames
        assertTrue(b > a, "a longer fade must produce a longer segment ($a vs $b)")
    }

    @Test
    fun `render is deterministic for the same inputs and seed`() {
        val first = out("render/det1.wav")
        val second = out("render/det2.wav")
        run("render", songs.a.absolutePath, songs.b.absolutePath, "-o", first.absolutePath, "--strategy", "crossfade", "--seed", "42")
        run("render", songs.a.absolutePath, songs.b.absolutePath, "-o", second.absolutePath, "--strategy", "crossfade", "--seed", "42")
        assertTrue(first.readBytes().contentEquals(second.readBytes()), "the same render must be byte-identical")
    }

    @Test
    fun `render reports unknown strategies and parameters readably`() {
        val wav = out("render/none.wav")
        val badStrategy = assertFailsWith<CliktError> {
            run("render", songs.a.absolutePath, songs.b.absolutePath, "-o", wav.absolutePath, "--strategy", "noSuchThing")
        }
        assertContains(badStrategy.message ?: "", "unknown strategy")
        assertContains(badStrategy.message ?: "", "crossfade")
        val badParam = assertFailsWith<CliktError> {
            run("render", songs.a.absolutePath, songs.b.absolutePath, "-o", wav.absolutePath, "--strategy", "crossfade", "--set", "nope=1")
        }
        assertContains(badParam.message ?: "", "unknown parameter")
    }

    // ---- mix ------------------------------------------------------------------------------------------------

    @Test
    fun `mix renders a three-track set with a report of every transition`() {
        val wav = out("mix/set.wav")
        val report = out("mix/set.json")
        val text = run(
            "mix", songs.a.absolutePath, songs.b.absolutePath, songs.c.absolutePath,
            "-o", wav.absolutePath, "--report", report.absolutePath,
        )
        assertTrue(wav.isFile)
        val audio = WavIo.read(wav)
        assertTrue(audio.durationSec > 40.0, "the set is only ${audio.durationSec} s")
        val json = JSON.parseToJsonElement(report.readText()).jsonObject
        assertEquals(3, json["tracks"]!!.jsonArray.size)
        val transitions = json["transitions"]!!.jsonArray
        assertEquals(2, transitions.size)
        for (t in transitions) {
            val o = t.jsonObject
            assertTrue(o.containsKey("strategy"), "a transition has no strategy: $o")
            assertTrue(o.containsKey("metricsVerdict"))
            assertTrue(o["reasons"]!!.jsonArray.isNotEmpty(), "a transition has no reasons")
        }
        assertTrue(json["seamSec"]!!.jsonArray.size >= 2)
        assertContains(text, "program:")
        assertContains(text, "rendered")
    }

    @Test
    fun `mix in the album context plays the album gapless`() {
        val wav = out("mix/album.wav")
        val text = run("mix", songs.a.absolutePath, songs.b.absolutePath, "-o", wav.absolutePath, "--context", "album")
        // Both files live in the same directory, so they are one album and the gating rule forbids transitions.
        assertContains(text, "gapless")
        assertTrue(wav.isFile)
    }

    @Test
    fun `mix needs at least two tracks`() {
        val e = assertFailsWith<CliktError> { run("mix", songs.a.absolutePath, "-o", out("mix/one.wav").absolutePath) }
        assertContains(e.message ?: "", "at least two")
    }

    // ---- ab / sweep / score ---------------------------------------------------------------------------------

    @Test
    fun `ab renders the chosen strategies with a csv and an html page`() {
        val dir = out("ab")
        val text = run("ab", songs.a.absolutePath, songs.b.absolutePath, "-o", dir.absolutePath, "--strategies", "crossfade,phraseCut", "--context", "2")
        assertTrue(File(dir, "crossfade.wav").isFile)
        assertTrue(File(dir, "phraseCut.wav").isFile)
        assertTrue(File(dir, "crossfade.context.wav").isFile)
        val csv = File(dir, "metrics.csv").readLines()
        assertEquals(3, csv.size, "expected a header and two rows")
        assertContains(csv[0], "strategy,score")
        assertContains(csv[0], "truePeakDbtp")
        val html = File(dir, "index.html").readText()
        assertContains(html, "<audio")
        assertContains(html, "data-gain=")
        assertContains(html, "crossfade.wav")
        assertContains(text, "html:")
    }

    @Test
    fun `sweep writes a row per grid point plus a csv and an svg`() {
        val dir = out("sweep")
        run(
            "sweep", songs.a.absolutePath, songs.b.absolutePath, "--strategy", "crossfade",
            "--param", "fadeSec=4:8:3", "-o", dir.absolutePath, "--metric", "truePeakDbtp",
        )
        val csv = File(dir, "sweep.csv").readLines()
        assertEquals(4, csv.size, "expected a header and three rows: ${csv.joinToString(" | ")}")
        assertContains(csv[0], "fadeSec")
        val svg = File(dir, "sweep.svg").readText()
        assertContains(svg, "<svg")
        assertContains(svg, "polyline")
        assertTrue(dir.listFiles()!!.count { it.name.endsWith(".wav") } == 3)
    }

    @Test
    fun `sweep rejects an unknown parameter and a malformed range`() {
        val dir = out("sweep-bad")
        assertContains(
            assertFailsWith<CliktError> {
                run("sweep", songs.a.absolutePath, songs.b.absolutePath, "--strategy", "crossfade", "--param", "nope=1:2:2", "-o", dir.absolutePath)
            }.message ?: "",
            "unknown parameter",
        )
        assertContains(
            assertFailsWith<CliktError> {
                run("sweep", songs.a.absolutePath, songs.b.absolutePath, "--strategy", "crossfade", "--param", "fadeSec=1:2", "-o", dir.absolutePath)
            }.message ?: "",
            "LO:HI:STEPS",
        )
    }

    @Test
    fun `score builds the pairwise matrix over a folder`() {
        val csv = out("score/matrix.csv")
        val text = run("score", songsDir.absolutePath, "--csv", csv.absolutePath, "--html")
        assertContains(text, "best strategy")
        assertContains(text, "most compatible pairs")
        val rows = csv.readLines().filter { it.isNotBlank() }
        // Four songs -> 12 ordered pairs plus the header.
        assertEquals(13, rows.size, "expected 12 ordered pairs")
        assertContains(rows[0], "a,b,strategy,score")
        assertTrue(File(csv.parentFile, "matrix.html").isFile)
    }

    @Test
    fun `score needs at least two files`() {
        val e = assertFailsWith<CliktError> { run("score", songs.a.absolutePath, "--csv", out("score/one.csv").absolutePath) }
        assertContains(e.message ?: "", "at least two")
    }

    // ---- check ----------------------------------------------------------------------------------------------

    @Test
    fun `check evaluates a render with and without its sources`() {
        val wav = out("check/seg.wav")
        run("render", songs.a.absolutePath, songs.b.absolutePath, "-o", wav.absolutePath, "--strategy", "crossfade")
        val plan = File(wav.parentFile, "seg.plan.json")

        val signalOnly = runCatching { run("check", wav.absolutePath) }
        val withSources = runCatching {
            run("check", wav.absolutePath, "--a", songs.a.absolutePath, "--b", songs.b.absolutePath, "--plan", plan.absolutePath)
        }
        // Either verdict is legitimate; what matters is that both paths produce a report and that only the second
        // one can compute the seam metrics.
        val sourced = withSources.getOrElse { (it as CliktError).message ?: "" } + withSources.getOrDefault("")
        assertTrue(signalOnly.isSuccess || (signalOnly.exceptionOrNull() is CliktError))
        assertTrue(sourced.contains("seamIdentity") || sourced.contains("metrics verdict"), "seam metrics were not attempted: $sourced")
    }

    @Test
    fun `check needs the plan before it will use the sources`() {
        val wav = out("check/seg.wav")
        val e = assertFailsWith<CliktError> {
            run("check", wav.absolutePath, "--a", songs.a.absolutePath, "--b", songs.b.absolutePath)
        }
        assertContains(e.message ?: "", "--plan")
    }

    @Test
    fun `check reports a non-WAV file readably`() {
        val text = File(root, "notes.wav").also { it.writeText("this is not audio") }
        val e = assertFailsWith<CliktError> { run("check", text.absolutePath) }
        assertContains(e.message ?: "", "cannot read")
    }

    // ---- live / stems / play --------------------------------------------------------------------------------

    @Test
    fun `live renders the plan the ladder chooses`() {
        val wav = out("live/auto.wav")
        val text = run("live", songs.a.absolutePath, songs.c.absolutePath, "-o", wav.absolutePath)
        assertTrue(wav.isFile)
        assertContains(text, "kind:")
        assertContains(text, "nodes (")
        assertContains(text, "limiter:")
        assertTrue(WavIo.read(wav).frames > 1000)
    }

    @Test
    fun `live --kind forces a rung of the ladder`() {
        val wav = out("live/crossfade.wav")
        val text = run("live", songs.a.absolutePath, songs.c.absolutePath, "-o", wav.absolutePath, "--kind", "crossfade", "--now", "20")
        assertContains(text, "kind:     crossfade")
        assertTrue(wav.isFile)
    }

    @Test
    fun `live explains why a bass swap cannot be forced across a big tempo gap`() {
        val e = assertFailsWith<CliktError> {
            run("live", songs.a.absolutePath, songs.c.absolutePath, "-o", out("live/bs.wav").absolutePath, "--kind", "bassSwap")
        }
        assertContains(e.message ?: "", "bass swap")
        assertContains(e.message ?: "", "apart")
    }

    @Test
    fun `stems writes four stems for a window`() {
        val dir = out("stems")
        val text = run("stems", songs.a.absolutePath, "-o", dir.absolutePath, "--from", "8", "--to", "12")
        for (name in listOf("drums", "bass", "vocals", "other")) {
            val f = File(dir, "$name.wav")
            assertTrue(f.isFile, "$name.wav missing")
            assertEquals(4 * 44100, WavIo.read(f).frames, "$name.wav is not the requested 4 s window")
        }
        assertContains(text, "PSEUDO")
    }

    @Test
    fun `stems rejects an empty window`() {
        val e = assertFailsWith<CliktError> { run("stems", songs.a.absolutePath, "-o", out("stems2").absolutePath, "--from", "10", "--to", "9") }
        assertContains(e.message ?: "", "must be after")
    }

    @Test
    fun `play skips gracefully when the machine has no audio device`() {
        val wav = out("render/basic.wav")
        if (!wav.isFile) run("render", songs.a.absolutePath, songs.b.absolutePath, "-o", wav.absolutePath, "--strategy", "crossfade")
        val text = run("play", wav.absolutePath)
        assertTrue(
            text.contains("no audio output device available") || text.contains("played"),
            "play should either play or say why it cannot: $text",
        )
    }

    // ---- goldens --------------------------------------------------------------------------------------------

    @Test
    fun `goldens update then check is clean`() {
        val dir = out("golden")
        val updated = run("goldens", "update", "--dir", dir.absolutePath, "--strategies", "crossfade", "--pairs", "2")
        assertContains(updated, "new")
        assertTrue(File(dir, "crossfade").isDirectory, "no fingerprints were written")
        val checked = run("goldens", "check", "--dir", dir.absolutePath, "--strategies", "crossfade", "--pairs", "2")
        assertContains(checked, "ok")
        assertTrue(!checked.contains("CHANGED"), "a freshly written golden must not differ")
    }

    @Test
    fun `goldens check fails when a fingerprint is missing`() {
        val dir = out("golden-empty")
        val e = assertFailsWith<CliktError> {
            run("goldens", "check", "--dir", dir.absolutePath, "--strategies", "crossfade", "--pairs", "1")
        }
        assertContains(e.message ?: "", "missing")
    }

    // ---- shared wiring --------------------------------------------------------------------------------------

    @Test
    fun `unsupported and missing files produce readable errors`() {
        val text = File(root, "cover.txt").also { it.writeText("nope") }
        assertContains(assertFailsWith<CliktError> { run("analyze", text.absolutePath) }.message ?: "", "unsupported audio file")
        assertContains(assertFailsWith<CliktError> { run("analyze", File(root, "ghost.wav").absolutePath) }.message ?: "", "no such audio file")
        val fake = File(root, "fake.wav").also { it.writeText("still not audio") }
        assertContains(assertFailsWith<CliktError> { run("analyze", fake.absolutePath) }.message ?: "", "cannot decode")
    }

    @Test
    fun `an unknown preference lists the ones that exist`() {
        val e = assertFailsWith<CliktError> { run("plan", songs.a.absolutePath, songs.b.absolutePath, "--set-pref", "loudness=3") }
        assertContains(e.message ?: "", "unknown preference")
        assertContains(e.message ?: "", "targetLufs")
    }

    @Test
    fun `the analysis cache is reused between commands`() {
        run("analyze", songs.d.absolutePath)
        val files = cache.listFiles()?.filter { it.isFile }.orEmpty()
        assertTrue(files.isNotEmpty(), "nothing was cached in ${cache.path}")
        val before = files.map { it.lastModified() to it.length() }
        run("analyze", songs.d.absolutePath)
        val after = cache.listFiles()!!.filter { it.isFile }.map { it.lastModified() to it.length() }
        assertEquals(before.toSet(), after.toSet(), "the second analysis rewrote the cache")
    }
}
