package dev.muisc.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import dev.muisc.transitions.recipe.DeckRecipe
import dev.muisc.transitions.recipe.Expr
import dev.muisc.transitions.recipe.RecipeCodec
import dev.muisc.transitions.recipe.RecipeLibrary
import dev.muisc.transitions.recipe.RecipePoint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `muisc recipe ...`, run in-process on the command itself (no audio, no analysis cache). */
class RecipeCommandTest {
    @TempDir lateinit var tmp: File

    private val dir: File get() = File(tmp, "recipes")

    private fun run(vararg args: String): CliktCommandTestResult {
        val withDir = if (args.first() == "validate") args.toList() else args.toList() + listOf("--recipes-dir", dir.path)
        return RecipeCommand.build().test(withDir, width = 200)
    }

    private fun writeUser(name: String, text: String): File = File(dir.apply { mkdirs() }, name).apply { writeText(text) }

    @Test fun `list shows built-in and user recipes with their status`() {
        writeUser("mine.json", RecipeCodec.encode(RecipeLibrary.starter("mine", "Mine")))
        writeUser("broken.json", "{ \"id\": \"broken\",\n  \"name\": }")
        writeUser("invalid.json", RecipeCodec.encode(RecipeLibrary.starter("invalid").copy(ambition = 4.0)))
        val r = run("list")
        assertEquals(0, r.statusCode, r.output)
        val out = r.stdout
        for (id in listOf("smooth-blend", "club-bass-swap", "drums-first", "long-glide", "tension-build", "filter-handoff", "echo-wash", "reverb-freeze-bridge", "radio-segue")) {
            assertContains(out, id)
        }
        assertTrue(out.lines().any { it.startsWith("drums-first") && it.contains("built-in") && it.contains("ok, 2 warnings") }, out)
        assertTrue(out.lines().any { it.startsWith("mine") && it.contains("user") && it.contains(" ok ") }, out)
        assertTrue(out.lines().any { it.startsWith("invalid") && it.contains("invalid, 1 error") }, out)
        assertContains(out, "10 in use")
        assertContains(out, "broken.json: error at line 2")
        assertContains(out, "(ambition)")
    }

    @Test fun `show prints a resolved summary and honours --set`() {
        val r = run("show", "club-bass-swap")
        assertEquals(0, r.statusCode, r.output)
        assertContains(r.stdout, "club-bass-swap — Club bass swap  (built-in, ok)")
        assertContains(r.stdout, "strategy id: recipe:club-bass-swap")
        assertContains(r.stdout, "tempo match, align phrase; overlap 16 bars, settle 2, hold 2; total 20 bars")
        assertTrue(r.stdout.lines().any { it.trim().startsWith("swapBar") && it.contains("2..30") }, r.stdout)
        assertContains(r.stdout, "deck A (outgoing): level (2 points); low (2 points)")
        assertContains(r.stdout, "problems: none")

        val set = run("show", "club-bass-swap", "--set", "len=24")
        assertContains(set.stdout, "overlap 24 bars")
        assertTrue(set.stdout.lines().any { it.trim().startsWith("len") && it.contains(" 24 ") }, set.stdout)
    }

    @Test fun `show and lanes refuse unknown ids and knobs`() {
        val unknown = run("show", "club-bass-swp")
        assertEquals(1, unknown.statusCode)
        assertContains(unknown.stderr, "Did you mean 'club-bass-swap'?")
        val knob = run("lanes", "club-bass-swap", "--set", "swapbar=4")
        assertEquals(1, knob.statusCode)
        assertContains(knob.stderr, "did you mean 'swapBar'?")
        val notANumber = run("lanes", "club-bass-swap", "--set", "swapBar=late")
        assertEquals(1, notANumber.statusCode)
    }

    @Test fun `validate reports problems with paths and lines and exits 1 on errors`() {
        val good = File(tmp, "good.json").apply { writeText(RecipeCodec.encode(RecipeLibrary.starter("good"))) }
        val ok = run("validate", good.path)
        assertEquals(0, ok.statusCode, ok.output)
        assertContains(ok.stdout, "good.json: ok  (good)")

        // Deck A's low band is cut at bar 0: a boundary-rule error, pointed at its line.
        val recipe = RecipeLibrary.starter("bad").let { it.copy(a = it.a.copy(low = listOf(RecipePoint(Expr.of(0), Expr.of(-6)), RecipePoint(Expr.of(4), Expr.of(0))))) }
        val bad = File(tmp, "bad.json").apply { writeText(RecipeCodec.encode(recipe)) }
        val lowLine = bad.readLines().indexOfFirst { it.contains("\"low\"") } + 1
        val r = run("validate", bad.path, good.path)
        assertEquals(1, r.statusCode, r.output)
        assertContains(r.stdout, "bad.json: 1 error")
        assertContains(r.stdout, "error at line $lowLine, column 5 (a.low): deck A must start exactly as the listener has been hearing it")
        assertContains(r.stdout, "2 files: 1 usable, 1 with errors")

        val syntax = File(tmp, "syntax.json").apply { writeText("{\n  \"id\": \"x\"\n  \"name\": \"X\"\n}") }
        val s = run("validate", syntax.path)
        assertEquals(1, s.statusCode)
        assertContains(s.stdout, "error at line 3, column 3: JSON syntax")

        val typo = File(tmp, "typo.json").apply { writeText("{ \"id\": \"x\", \"name\": \"X\", \"a\": { \"levle\": [] } }") }
        assertContains(run("validate", typo.path).stdout, "(a.levle): unknown key 'levle' (did you mean 'level'?)")

        // A whole directory, and a note when a file would replace a built-in.
        File(tmp, "more").mkdirs()
        File(tmp, "more/smooth-blend.json").writeText(RecipeCodec.encode(RecipeLibrary.starter("smooth-blend")))
        val d = run("validate", File(tmp, "more").path)
        assertEquals(0, d.statusCode, d.output)
        assertContains(d.stdout, "replaces the built-in recipe 'smooth-blend'")
    }

    @Test fun `new writes a starter or a copy and never overwrites`() {
        val r = run("new", "my-mix", "--name", "My mix")
        assertEquals(0, r.statusCode, r.output)
        val file = File(dir, "my-mix.json")
        assertTrue(file.isFile)
        val written = RecipeCodec.parse(file).recipe!!
        assertEquals("My mix", written.name)
        assertEquals(0, run("validate", file.path).statusCode)

        val again = run("new", "my-mix")
        assertEquals(1, again.statusCode)
        assertContains(again.stderr, "already exists")
        assertEquals(written, RecipeCodec.parse(file).recipe)

        val copy = run("new", "my-swap", "--from", "club-bass-swap")
        assertEquals(0, copy.statusCode, copy.output)
        val copied = RecipeCodec.parse(File(dir, "my-swap.json")).recipe!!
        assertEquals("Club bass swap (copy)", copied.name)
        assertEquals(RecipeLibrary.builtInOnly().load()["club-bass-swap"]!!.recipe.copy(id = "my-swap", name = "Club bass swap (copy)"), copied)
        // The new recipes show up in the library straight away.
        assertTrue(run("list").stdout.lines().any { it.startsWith("my-swap") && it.contains("user") })

        val other = File(tmp, "elsewhere")
        assertEquals(0, run("new", "there", "--dir", other.path).statusCode)
        assertTrue(File(other, "there.json").isFile)

        val badId = run("new", "My Mix")
        assertEquals(1, badId.statusCode)
        assertContains(badId.stderr, "for example 'my-mix'")
        assertFalse(File(dir, "My Mix.json").exists())
    }

    @Test fun `lanes prints every non-neutral lane with a table and a plot`() {
        val r = run("lanes", "club-bass-swap")
        assertEquals(0, r.statusCode, r.output)
        val out = r.stdout
        assertContains(out, "club-bass-swap — Club bass swap  (len = 16, swapBar = 8, swapBeats = 1)")
        for (lane in listOf("a.level", "a.low", "b.level", "b.low")) assertContains(out, "$lane  (")
        for (neutral in listOf("a.mid", "a.hpf", "b.high")) assertFalse(out.contains("$neutral  ("), out)
        // The table: A's low band holds 0 dB until bar 8, then is cut.
        val aLow = out.substringAfter("a.low  (dB; neutral 0)").substringBefore("b.level")
        assertTrue(aLow.lines().any { it.trim().split(Regex("\\s+")) == listOf("8", "0", "dB", "step") }, aLow)
        assertTrue(aLow.lines().any { it.trim().split(Regex("\\s+")) == listOf("8", "off", "holds") }, aLow)
        // The plot: rows of '*' on an axis marked at the end of the overlap.
        assertTrue(aLow.lines().count { it.contains("|") && it.contains("*") } >= 2, aLow)
        assertContains(aLow, "(bars; | = end of overlap)")

        val moved = run("lanes", "club-bass-swap", "--set", "swapBar=4")
        val movedLow = moved.stdout.substringAfter("a.low  (dB; neutral 0)").substringBefore("b.level")
        assertTrue(movedLow.lines().any { it.trim().startsWith("4 ") }, movedLow)

        // A file path works too.
        val f = File(tmp, "file.json").apply { writeText(RecipeCodec.encode(RecipeLibrary.starter("file-one"))) }
        assertContains(run("lanes", f.path).stdout, "b.low  (dB; neutral 0)")
        // A recipe that does nothing says so.
        val nothing = File(tmp, "nothing.json").apply { writeText(RecipeCodec.encode(RecipeLibrary.starter("nothing").copy(a = DeckRecipe(), b = DeckRecipe()))) }
        assertContains(run("lanes", nothing.path).stdout, "every lane is neutral")
    }
}
