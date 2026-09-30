package dev.muisc.transitions.recipe

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeLibraryTest {
    @TempDir lateinit var tmp: File

    private val userDir: File get() = File(tmp, "recipes")
    private fun library() = RecipeLibrary(userDir)
    private fun write(name: String, text: String) = File(userDir.apply { mkdirs() }, name).apply { writeText(text) }
    private fun write(recipe: TransitionRecipe, name: String = "${recipe.id}.json") = write(name, RecipeCodec.encode(recipe))

    private val builtInIds = listOf(
        "smooth-blend", "club-bass-swap", "drums-first", "long-glide", "tension-build",
        "filter-handoff", "echo-wash", "reverb-freeze-bridge", "radio-segue",
    )

    @Test fun builtInsLoadWithoutAUserDirectory() {
        val set = RecipeLibrary.builtInOnly().load()
        assertEquals(builtInIds, set.active.map { it.id })
        assertTrue(set.active.all { it.origin == RecipeOrigin.BUILT_IN && it.file == null })
        assertTrue(set.errors.isEmpty(), set.problems.joinToString("\n"))
        // A missing user directory is not a problem either.
        assertTrue(RecipeLibrary(File(tmp, "nope")).load().errors.isEmpty())
    }

    @Test fun oneCorruptUserFileNeverBlocksTheOthers() {
        write(RecipeLibrary.starter("aaa-good"))
        val corrupt = write("bbb-corrupt.json", "{\n  \"id\": \"bbb\",\n  \"name\": \"B\"\n  \"ambition\": 1\n}")
        val invalid = write(RecipeLibrary.starter("ccc-invalid").copy(ambition = 3.0))
        write(RecipeLibrary.starter("ddd-good"))
        write("notes.txt", "not a recipe")
        write(".hidden.json", "{ broken")

        val set = library().load()
        assertEquals(builtInIds + listOf("aaa-good", "ddd-good"), set.active.map { it.id })
        // The corrupt file is reported with its line, and has no entry.
        val bad = set.problems.single { it.source == corrupt.path }
        assertEquals(4, bad.problem.line)
        assertNull(set.all("bbb").firstOrNull())
        // The invalid recipe is listed as such, with its problem, and is not in use.
        val inv = set.all("ccc-invalid").single()
        assertEquals(RecipeStatus.INVALID, inv.status)
        assertTrue(inv.errors.any { it.path == "ambition" })
        assertTrue(set.problems.any { it.source == invalid.path && it.problem.path == "ambition" })
        assertNull(set["ccc-invalid"])
        // Hidden and non-JSON files are ignored.
        assertTrue(set.problems.none { it.source.endsWith("notes.txt") || it.source.endsWith(".hidden.json") })
    }

    @Test fun anUnreadableUserLocationIsReportedAndBuiltInsStillLoad() {
        tmp.mkdirs()
        val notADir = File(tmp, "recipes").apply { writeText("oops") }
        val set = RecipeLibrary(notADir).load()
        assertEquals(builtInIds, set.active.map { it.id })
        assertTrue(set.errors.single().problem.message.contains("not a directory"))
    }

    @Test fun aUserRecipeShadowsABuiltInWithAWarning() {
        val mine = RecipeLibrary.starter("smooth-blend", "My smooth blend")
        val file = write(mine)
        val set = library().load()
        val active = assertNotNull(set["smooth-blend"])
        assertEquals(RecipeOrigin.USER, active.origin)
        assertEquals("My smooth blend", active.recipe.name)
        assertEquals(file, active.file)
        assertTrue(active.warnings.any { it.message.contains("replaces the built-in") })
        assertEquals(RecipeStatus.SHADOWED, set.all("smooth-blend").single { it.origin == RecipeOrigin.BUILT_IN }.status)
        assertEquals(1, set.active.count { it.id == "smooth-blend" })
        assertEquals(builtInIds.size, set.active.size)
    }

    @Test fun anInvalidUserRecipeDoesNotShadowTheBuiltIn() {
        write(RecipeLibrary.starter("smooth-blend").copy(name = ""))
        val set = library().load()
        val active = assertNotNull(set["smooth-blend"])
        assertEquals(RecipeOrigin.BUILT_IN, active.origin)
        val mine = set.all("smooth-blend").single { it.origin == RecipeOrigin.USER }
        assertEquals(RecipeStatus.INVALID, mine.status)
        assertTrue(mine.warnings.any { it.message.contains("built-in recipe 'smooth-blend' is used instead") })
    }

    @Test fun twoUserFilesWithTheSameIdKeepTheFirst() {
        write(RecipeLibrary.starter("twin", "First"), "a.json")
        write(RecipeLibrary.starter("twin", "Second"), "b.json")
        val set = library().load()
        assertEquals("First", set["twin"]!!.recipe.name)
        val second = set.all("twin").single { it.status == RecipeStatus.DUPLICATE }
        assertEquals("Second", second.recipe.name)
        assertTrue(second.errors.single().message.contains("a.json"))
    }

    @Test fun saveIsAnAtomicRoundTrip() {
        val lib = library()
        val recipe = RecipeLibrary.starter("my-mix", "My mix").copy(tags = listOf("mine"))
        val saved = lib.save(recipe)
        assertTrue(saved.ok, saved.toString())
        val file = File(userDir, "my-mix.json")
        assertEquals(file, saved.file)
        assertEquals(recipe, RecipeCodec.parse(file).recipe)
        assertEquals(recipe, lib.load()["my-mix"]!!.recipe)
        // Saving again replaces the same file, and leaves no temporary files behind.
        val v2 = recipe.copy(version = 2, name = "My mix v2")
        assertTrue(lib.save(v2).ok)
        assertEquals(listOf("my-mix.json"), userDir.list()!!.sorted())
        assertEquals(v2, lib.load()["my-mix"]!!.recipe)
    }

    @Test fun saveWritesOverTheFileThatHoldsTheId() {
        val odd = write(RecipeLibrary.starter("my-mix"), "Odd Name.json")
        assertTrue(library().save(RecipeLibrary.starter("my-mix", "Renamed")).ok)
        assertEquals("Renamed", RecipeCodec.parse(odd).recipe!!.name)
        assertFalse(File(userDir, "my-mix.json").exists())
    }

    @Test fun saveRefusesWhatWouldLoseData() {
        val lib = library()
        // A recipe with errors is not written (unless asked).
        val broken = RecipeLibrary.starter("broken").copy(ambition = 5.0)
        val refused = lib.save(broken)
        assertFalse(refused.ok)
        assertTrue(refused.problems.any { it.path == "ambition" })
        assertFalse(File(userDir, "broken.json").exists())
        assertTrue(lib.save(broken, allowErrors = true).ok)
        assertEquals(RecipeStatus.INVALID, lib.load().all("broken").single().status)
        // A file named after the id that holds ANOTHER recipe, or cannot be read, is never overwritten.
        val other = write(RecipeLibrary.starter("someone-else"), "taken.json")
        val before = other.readText()
        assertFalse(lib.save(RecipeLibrary.starter("taken")).ok)
        assertEquals(before, other.readText())
        val garbage = write("junk.json", "{ half-written")
        assertFalse(lib.save(RecipeLibrary.starter("junk")).ok)
        assertEquals("{ half-written", garbage.readText())
        // Ids that are not file-name safe are refused.
        assertFalse(lib.save(RecipeLibrary.starter("../escape")).ok)
        assertFalse(File(tmp, "escape.json").exists())
        // No directory, no save.
        assertFalse(RecipeLibrary.builtInOnly().save(RecipeLibrary.starter("x")).ok)
    }

    @Test fun deleteRemovesUserRecipesOnly() {
        val lib = library()
        assertTrue(lib.save(RecipeLibrary.starter("gone")).ok)
        val deleted = lib.delete("gone")
        assertTrue(deleted.ok, deleted.toString())
        assertFalse(File(userDir, "gone.json").exists())
        assertNull(lib.load()["gone"])
        // Built-ins cannot be deleted.
        val builtIn = lib.delete("smooth-blend")
        assertFalse(builtIn.ok)
        assertTrue(builtIn.problems.single().message.contains("built-in"))
        assertFalse(lib.delete("never-existed").ok)
        // Deleting a shadowing recipe brings the built-in back.
        write(RecipeLibrary.starter("radio-segue", "Mine"))
        assertEquals(RecipeOrigin.USER, lib.load()["radio-segue"]!!.origin)
        val back = lib.delete("radio-segue")
        assertTrue(back.ok && back.problems.any { it.message.contains("used again") })
        assertEquals(RecipeOrigin.BUILT_IN, lib.load()["radio-segue"]!!.origin)
    }

    @Test fun duplicateSavesACopyUnderANewId() {
        val lib = library()
        val copy = lib.duplicate("club-bass-swap", "my-swap")
        assertTrue(copy.ok, copy.toString())
        val entry = lib.load()["my-swap"]!!
        assertEquals(RecipeOrigin.USER, entry.origin)
        assertEquals("Club bass swap (copy)", entry.recipe.name)
        val original = lib.load()["club-bass-swap"]!!.recipe
        assertEquals(original.copy(id = "my-swap", name = "Club bass swap (copy)", version = 1), entry.recipe)
        assertTrue(lib.duplicate("club-bass-swap", "named", "Named").ok)
        assertEquals("Named", lib.load()["named"]!!.recipe.name)
        assertFalse(lib.duplicate("club-bass-swap", "smooth-blend").ok) // taken
        assertFalse(lib.duplicate("no-such", "x").ok)
        assertFalse(lib.duplicate("club-bass-swap", "Bad Id").ok)
    }

    @Test fun builtInIndexProblemsAreReported() {
        val root = File(tmp, "cp")
        val dir = File(root, "recipes").apply { mkdirs() }
        File(dir, "index.txt").writeText("# test index\nok.json\nmissing.json\n\ncorrupt.json  # trailing comment\n")
        File(dir, "ok.json").writeText(RecipeCodec.encode(RecipeLibrary.starter("ok")))
        File(dir, "corrupt.json").writeText("{")
        URLClassLoader(arrayOf(root.toURI().toURL()), null).use { cl ->
            val set = RecipeLibrary(null, classLoader = cl).load()
            assertEquals(listOf("ok"), set.active.map { it.id })
            assertTrue(set.problems.any { it.source == "recipes/missing.json" && it.problem.message.contains("missing") })
            assertTrue(set.problems.any { it.source == "recipes/corrupt.json" })
        }
        URLClassLoader(arrayOf(File(tmp, "empty").apply { mkdirs() }.toURI().toURL()), null).use { cl ->
            val set = RecipeLibrary(null, classLoader = cl).load()
            assertTrue(set.active.isEmpty())
            assertTrue(set.errors.single().problem.message.contains("index is missing"))
        }
    }

    @Test fun theStarterIsValid() {
        val report = RecipeValidator().validate(RecipeLibrary.starter("x", "X"))
        assertTrue(report.problems.isEmpty(), report.toString())
    }
}
