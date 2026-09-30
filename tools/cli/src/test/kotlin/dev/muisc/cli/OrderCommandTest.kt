package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `muisc order` and `muisc mix --order smart` on the synthetic fixture set. */
class OrderCommandTest {

    companion object {
        @JvmStatic
        @TempDir
        lateinit var root: File

        private val cache: File get() = File(root, "cache")
        private lateinit var songs: Cli.Songs
        private val JSON = Json { ignoreUnknownKeys = true }

        @JvmStatic
        @BeforeAll
        fun setUp() {
            cache.mkdirs()
            songs = Cli.songs(File(root, "songs").also { it.mkdirs() }, cache)
        }
    }

    private fun run(vararg args: String) = Cli.run(cache, *args)
    private fun files() = listOf(songs.a, songs.b, songs.c, songs.d).map { it.absolutePath }.toTypedArray()
    private fun orderJson(vararg extra: String): JsonObject = JSON.parseToJsonElement(run("order", *files(), "--json", *extra)).jsonObject
    private fun titles(j: JsonObject) = j["tracks"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }

    @Test
    fun `order prints every track once with the pair costs and the total against random orders`() {
        val text = run("order", *files(), "--seed", "2")
        for (name in listOf("t120C", "t126Am", "t140Fs", "t63G")) assertEquals(1, Regex("\\b$name\\b").findAll(text).count(), "$name listed once:\n$text")
        assertContains(text, "full mode, costs refined by the planner")
        assertContains(text, "median of ${SmartOrder.RANDOM_ORDERS} random orders")
        assertContains(text, "total cost")
    }

    @Test
    fun `order --json is the same order for the same seed and never worse than the random median at variety 0`() {
        val a = orderJson("--seed", "5", "--variety", "0")
        val b = orderJson("--seed", "5", "--variety", "0")
        assertEquals(titles(a), titles(b))
        val tracks = a["tracks"]!!.jsonArray
        assertEquals(4, tracks.size)
        assertEquals((1..4).toList(), tracks.map { it.jsonObject["position"]!!.jsonPrimitive.content.toInt() })
        assertEquals(3, tracks.count { it.jsonObject.containsKey("costToNext") }, "one cost per neighbouring pair")
        assertTrue(tracks.all { it.jsonObject["analysed"]!!.jsonPrimitive.boolean })
        val total = a["totalCost"]!!.jsonPrimitive.double
        assertEquals(tracks.sumOf { it.jsonObject["costToNext"]?.jsonPrimitive?.double ?: 0.0 }, total, 1e-9)
        assertTrue(total <= a["randomMedianTotalCost"]!!.jsonPrimitive.double, "the smart order is no worse than the median random order")
        assertEquals("flat", a["arc"]!!.jsonPrimitive.content)
        assertEquals("peak", orderJson("--arc", "peak")["arc"]!!.jsonPrimitive.content)
    }

    @Test
    fun `order rejects an unknown arc and a variety outside 0 to 1`() {
        assertFailsWith<CliktError> { run("order", *files(), "--arc", "zigzag") }
        val e = assertFailsWith<CliktError> { run("order", *files(), "--variety", "2") }
        assertContains(e.message ?: "", "between 0 and 1")
    }

    @Test
    fun `a file that cannot be analysed is placed without cost information`() {
        val broken = File(root, "broken/not-audio.wav").also { it.parentFile.mkdirs(); it.writeText("not a wav") }
        val text = run("order", songs.a.absolutePath, songs.b.absolutePath, broken.absolutePath)
        assertContains(text, "not analysed")
        assertContains(text, "1 track(s) could not be analysed and were placed without cost information")
        val j = JSON.parseToJsonElement(run("order", songs.a.absolutePath, songs.b.absolutePath, broken.absolutePath, "--json")).jsonObject
        val entry = j["tracks"]!!.jsonArray.single { it.jsonObject["title"]!!.jsonPrimitive.content == "not-audio" }.jsonObject
        assertFalse(entry["analysed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `mix --order smart plays the order that muisc order prints`() {
        val three = listOf(songs.a, songs.c, songs.b).map { it.absolutePath }.toTypedArray()
        val expected = titles(JSON.parseToJsonElement(run("order", *three, "--json", "--seed", "4")).jsonObject)
        val report = File(root, "mix/smart.json")
        val text = run("mix", *three, "--order", "smart", "--seed", "4", "--context", "shuffle", "-o", File(root, "mix/smart.wav").absolutePath, "--report", report.absolutePath)
        assertContains(text, "smart order")
        val j = JSON.parseToJsonElement(report.readText()).jsonObject
        assertEquals("smart", j["order"]!!.jsonPrimitive.content)
        assertEquals(expected, j["tracks"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content })
    }

    @Test
    fun `mix refuses to reorder an album`() {
        val e = assertFailsWith<CliktError> {
            run("mix", songs.a.absolutePath, songs.b.absolutePath, "--order", "smart", "--context", "album", "-o", File(root, "mix/album.wav").absolutePath)
        }
        assertContains(e.message ?: "", "album")
    }
}
