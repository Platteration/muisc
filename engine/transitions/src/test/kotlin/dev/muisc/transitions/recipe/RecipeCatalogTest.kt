package dev.muisc.transitions.recipe

import dev.muisc.transitions.DefaultStrategyRegistry
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeCatalogTest {
    @TempDir lateinit var tmp: File

    @Test fun addsExactlyTheValidRecipes() {
        val base = DefaultStrategyRegistry.default()
        val good = RecipeLibrary.starter("good-one")
        val alsoGood = RecipeLibrary.starter("good-two")
        val bad = RecipeLibrary.starter("bad-one").copy(a = DeckRecipe()) // A never fades out: warning only, still valid
            .copy(b = DeckRecipe(hpf = listOf(RecipePoint(Expr.of(0), Expr.of(400))))) // B filtered at the seam: error
        val result = RecipeCatalog.build(base, listOf(good, bad, alsoGood))
        assertEquals(base.strategyIds + listOf("recipe:good-one", "recipe:good-two"), result.registry.strategyIds)
        assertEquals(listOf("recipe:good-one", "recipe:good-two"), result.added)
        assertTrue(result.skipped.getValue("bad-one").contains("b.hpf"), result.skipped.toString())
        val strategy = result.registry.strategy("recipe:good-one") as RecipeStrategy
        assertEquals(good, strategy.recipe)
        // The base registry and the shipped default are untouched.
        assertEquals(DefaultStrategyRegistry.default().strategyIds, base.strategyIds)
        assertEquals(base.modifierIds, result.registry.modifierIds)
    }

    @Test fun fromTheLibraryAddsActiveRecipesOnly() {
        val dir = File(tmp, "recipes").apply { mkdirs() }
        File(dir, "mine.json").writeText(RecipeCodec.encode(RecipeLibrary.starter("mine")))
        File(dir, "broken.json").writeText(RecipeCodec.encode(RecipeLibrary.starter("broken").copy(ambition = 9.0)))
        File(dir, "corrupt.json").writeText("{")
        val base = DefaultStrategyRegistry.default()
        val lib = RecipeLibrary(dir)
        val registry = RecipeCatalog.registry(base, lib)
        val builtIns = RecipeLibrary.builtInOnly().load().recipes.map { it.strategyId }
        assertEquals(base.strategyIds + builtIns + "recipe:mine", registry.strategyIds)
        val result = RecipeCatalog.build(base, lib.load())
        assertTrue("broken" in result.skipped)
    }

    @Test fun recipesNeedingAMissingModifierAreSkipped() {
        val needs = RecipeLibrary.starter("needs-texture").copy(modifiers = listOf("textureCarry"))
        val empty = DefaultStrategyRegistry.empty()
        assertEquals(emptyList(), RecipeCatalog.registry(empty, listOf(needs)).strategyIds)
        assertEquals(listOf("recipe:needs-texture"), RecipeCatalog.registry(DefaultStrategyRegistry.default(), listOf(needs)).strategyIds.filter { it.startsWith("recipe:") })
        val set = RecipeSet(listOf(RecipeEntry(needs, RecipeOrigin.USER, "x", null, RecipeStatus.ACTIVE, emptyList())), emptyList())
        val result = RecipeCatalog.build(empty, set)
        assertTrue(result.registry.strategyIds.isEmpty())
        assertTrue(result.skipped.getValue("needs-texture").contains("textureCarry"))
    }

    @Test fun duplicateIdsInAListAreAddedOnce() {
        val r = RecipeCatalog.build(DefaultStrategyRegistry.empty(), listOf(RecipeLibrary.starter("x", "One"), RecipeLibrary.starter("x", "Two")))
        assertEquals(listOf("recipe:x"), r.registry.strategyIds)
        assertEquals("One", (r.registry.strategies.single() as RecipeStrategy).recipe.name)
    }
}
