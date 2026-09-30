package dev.muisc.transitions.recipe

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shipped recipe library: every built-in is valid at every setting the validator checks, and together they cover the format. */
class BuiltInRecipesTest {
    private val set = RecipeLibrary.builtInOnly().load()
    private val recipes = set.active.map { it.recipe }

    @Test fun theWholeShippedLibraryValidatesWithZeroErrors() {
        assertTrue(set.problems.none { it.problem.isError }, set.problems.joinToString("\n"))
        assertEquals(set.entries.size, set.active.size)
        assertTrue(recipes.size in 8..10, "${recipes.size} built-ins")
        // Re-validate directly, so this test does not depend on the library's own bookkeeping.
        for (r in recipes) {
            val report = RecipeValidator().validate(r)
            assertTrue(report.valid, report.toString())
            // The only warning a built-in may carry is the documented stem-separation note.
            assertTrue(report.warnings.all { it.path.endsWith(".stems") }, report.toString())
        }
    }

    @Test fun indexListsEveryShippedFileAndIdsMatchFileNames() {
        val index = javaClass.classLoader.getResource("recipes/index.txt")!!
        val dir = File(index.toURI()).parentFile
        val onDisk = dir.list()!!.filter { it.endsWith(".json") }.sorted()
        val listed = index.readText().lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        assertEquals(onDisk, listed.sorted())
        assertEquals(listed.map { it.removeSuffix(".json") }, recipes.map { it.id })
    }

    @Test fun everyBuiltInIsDescribedForAListener() {
        for (r in recipes) {
            assertTrue(r.name.isNotBlank() && r.description.length >= 120, "${r.id}: describe what it sounds like and when it works")
            assertTrue(r.tags.isNotEmpty(), "${r.id}: tags")
            assertTrue(r.vars.isNotEmpty() && r.vars.values.all { it.label.isNotBlank() && it.doc.isNotBlank() }, "${r.id}: knobs need a label and a doc")
            assertTrue(r.ambition in 0.0..1.0)
            assertEquals(r, RecipeCodec.parse(RecipeCodec.encode(r)).recipe, "${r.id}: round trip")
        }
    }

    @Test fun togetherTheyCoverEveryTempoModeAndLaneType() {
        assertEquals(RecipeTempo.entries.toSet(), recipes.map { it.timing.tempo }.toSet())
        val used = HashSet<String>()
        for (r in recipes) {
            val resolved = RecipeResolver.resolve(r)
            for (deck in listOf(resolved.a, resolved.b)) for ((label, lane) in deck.lanes()) if (!lane.isNeutral) used += label
        }
        val all = setOf(
            "level", "low", "mid", "high", "hpf", "lpf", "echo.send", "reverb.send", "reverb.freeze",
            "stems.drums", "stems.bass", "stems.vocals", "stems.other",
        )
        assertEquals(all, used)
    }
}
