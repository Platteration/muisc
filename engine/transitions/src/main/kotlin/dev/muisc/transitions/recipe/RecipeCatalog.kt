package dev.muisc.transitions.recipe

import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.StrategyRegistry

/**
 * Turns recipes into strategies: a registry that is [base] plus one [RecipeStrategy] (id `recipe:<id>`) per VALID
 * recipe, appended after the base strategies in the order given. Invalid recipes are left out (the library has
 * already reported why). [base] itself is not changed, and neither is [DefaultStrategyRegistry.default].
 */
object RecipeCatalog {

    /** What [build] added and what it left out (recipe id → reason). */
    class Result(val registry: DefaultStrategyRegistry, val added: List<String>, val skipped: Map<String, String>)

    /** [base] plus the recipes [library] has in use. */
    fun registry(base: StrategyRegistry, library: RecipeLibrary): DefaultStrategyRegistry = build(base, library.load()).registry

    /** [base] plus the recipes in use in [set]. */
    fun registry(base: StrategyRegistry, set: RecipeSet): DefaultStrategyRegistry = build(base, set).registry

    /** [base] plus every recipe of [recipes] that validates against [base]'s modifiers. */
    fun registry(base: StrategyRegistry, recipes: List<TransitionRecipe>): DefaultStrategyRegistry = build(base, recipes).registry

    /**
     * [base] plus the ACTIVE entries of [set] (already validated by the library) whose modifiers [base] provides.
     */
    fun build(base: StrategyRegistry, set: RecipeSet): Result {
        val modifiers = base.modifiers.map { it.id }.toSet()
        val skipped = LinkedHashMap<String, String>()
        val activeIds = set.active.map { it.id }.toSet()
        for (e in set.entries) if (!e.active && e.id !in activeIds) skipped[e.id] = "${e.status.name.lowercase()}: ${e.source}"
        val usable = set.active.map { it.recipe }.filter { r ->
            val missing = r.modifiers.filter { it !in modifiers }
            if (missing.isNotEmpty()) skipped[r.id] = "needs modifier(s) this registry lacks: ${missing.joinToString(", ")}"
            missing.isEmpty()
        }
        return result(base, usable, skipped)
    }

    /** [base] plus every recipe of [recipes] with no validation errors (validated against [base]'s modifiers). */
    fun build(base: StrategyRegistry, recipes: List<TransitionRecipe>, beatsPerBar: Int = 4): Result {
        val validator = RecipeValidator(base.modifiers.map { it.id }.toSet(), beatsPerBar)
        val skipped = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        val usable = recipes.filter { r ->
            val report = validator.validate(r)
            when {
                !report.valid -> { skipped[r.id] = report.errors.first().toString(); false }
                !seen.add(r.id) -> { skipped[r.id] = "duplicate id"; false }
                else -> true
            }
        }
        return result(base, usable, skipped)
    }

    private fun result(base: StrategyRegistry, recipes: List<TransitionRecipe>, skipped: Map<String, String>): Result {
        val start = base as? DefaultStrategyRegistry ?: DefaultStrategyRegistry(base.strategies, base.modifiers)
        val registry = start.withStrategies(*recipes.map { RecipeStrategy(it) }.toTypedArray())
        return Result(registry, recipes.map { it.strategyId }, skipped)
    }
}
