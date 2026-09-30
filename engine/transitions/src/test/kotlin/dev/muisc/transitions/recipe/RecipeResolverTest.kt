package dev.muisc.transitions.recipe

import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecipeResolverTest {
    private val json = """
        {
          "id": "test-swap",
          "name": "Test swap",
          "vars": {
            "bars": { "default": 16, "min": 4, "max": 32 }
          }
        }
    """.trimIndent()

    private val recipe = TransitionRecipe(
        id = "club-swap", name = "Club swap",
        vars = linkedMapOf(
            "len" to RecipeVar(16.0, 4.0, 32.0, "Length", "bars", integer = true),
            "swapBar" to RecipeVar(8.0, 1.0, 31.0, "Swap at", "bar", integer = true),
        ),
        timing = RecipeTiming(lengthBars = Expr("len"), settleBars = Expr.of(2), holdBars = Expr.of(1)),
        a = DeckRecipe(
            level = listOf(RecipePoint(Expr.of(0), Expr.of(1), RecipeCurve.EQUAL_POWER), RecipePoint(Expr("bars"), Expr.of(0))),
            low = listOf(RecipePoint(Expr("swapBar"), Expr.of(0), RecipeCurve.STEP), RecipePoint(Expr("swapBar + beat"), Expr("off"))),
        ),
        b = DeckRecipe(
            level = listOf(RecipePoint(Expr.of(0), Expr.of(0), RecipeCurve.EQUAL_POWER), RecipePoint(Expr("bars"), Expr.of(1))),
            low = listOf(RecipePoint(Expr("swapBar"), Expr("off")), RecipePoint(Expr("swapBar + beat"), Expr.of(0))),
            hpf = listOf(RecipePoint(Expr.of(0), Expr.of(1000)), RecipePoint(Expr.of(4), Expr.of(20))),
        ),
    )

    @Test fun resolvesTimingAndLanes() {
        val r = RecipeResolver.resolve(recipe)
        assertEquals(16.0, r.lengthBars); assertEquals(19.0, r.totalBars)
        assertEquals(1.0, r.a.level.valueAt(0.0)); assertEquals(0.0, r.a.level.valueAt(16.0), 1e-12)
        // Equal-power pair: A = cos, B = sin, so A² + B² = 1 anywhere in the overlap.
        for (bar in listOf(2.0, 5.5, 8.0, 13.0)) {
            val t = bar / 16.0
            assertEquals(cos(t * PI / 2), r.a.level.valueAt(bar), 1e-12)
            assertEquals(sin(t * PI / 2), r.b.level.valueAt(bar), 1e-12)
        }
        // Step: A's low band is flat until the swap bar, then off after one beat.
        assertEquals(0.0, r.a.low.valueAt(8.1)); assertEquals(-120.0, r.a.low.valueAt(8.25)); assertEquals(0.0, r.a.low.valueAt(7.0))
        // Log interpolation for Hz lanes: halfway between 1000 and 20 is their geometric mean.
        assertEquals(kotlin.math.sqrt(1000.0 * 20.0), r.b.hpf.valueAt(2.0), 1e-9)
        assertTrue(r.b.hpf.valueAt(10.0) == 20.0 && r.a.usesEq && !r.a.usesFilters && r.b.usesFilters)
    }

    @Test fun paramsOverrideAndClampVariables() {
        val r = RecipeResolver.resolve(recipe, Params(mapOf("len" to "24.4", "swapBar" to "99")))
        assertEquals(24.0, r.lengthBars); assertEquals(31.0, r.vars["swapBar"])
        val specs = RecipeResolver.paramSpecs(recipe)
        assertEquals(listOf("len", "swapBar"), specs.map { it.id })
        assertTrue(specs.all { it is ParamSpec.IntSpec })
    }

    @Test fun errorsNameTheField() {
        val bad = recipe.copy(a = recipe.a.copy(level = listOf(RecipePoint(Expr("lenn"), Expr.of(1)))))
        val e = assertFailsWith<RecipeException> { RecipeResolver.resolve(bad) }
        assertEquals("a.level[0].at", e.path)
        assertFailsWith<RecipeException> { RecipeResolver.resolve(recipe.copy(vars = mapOf("bars" to RecipeVar(1.0, 0.0, 2.0)))) }
        assertFailsWith<RecipeException> { RecipeResolver.resolve(recipe.copy(timing = recipe.timing.copy(lengthBars = Expr.of(100)))) }
        assertFailsWith<RecipeException> { RecipeResolver.resolve(recipe.copy(format = 99)) }
    }

    @Test fun jsonRoundTripAcceptsNumbersAndExpressions() {
        val text = RecipeFormat.encode(recipe)
        assertTrue(text.contains("\"swapBar + beat\"") && text.contains("\"at\": 0"), text)
        assertEquals(recipe, RecipeFormat.decode(text))
        val minimal = RecipeFormat.decode("""{ "id": "x", "name": "X", "timing": { "lengthBars": 8, "tempo": "none" }, "a": { "level": [ { "at": 0, "v": 1 }, { "at": "bars", "v": 0, "curve": "sCurve" } ] } }""")
        assertEquals(RecipeTempo.NONE, minimal.timing.tempo)
        assertEquals(RecipeCurve.S_CURVE, minimal.a.level[1].curve)
        assertFailsWith<Exception> { RecipeFormat.decode("""{ "id": "x", "name": "X", "levle": [] }""") }
        assertTrue(json.isNotEmpty())
    }
}
