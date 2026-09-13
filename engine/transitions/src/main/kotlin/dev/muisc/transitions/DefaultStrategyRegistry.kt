package dev.muisc.transitions

import dev.muisc.transitions.strategies.CrossfadeStrategy

/**
 * The single place every shipped technique is registered. Immutable; [withStrategies] / [withModifiers] derive
 * extended registries (the Lab registers experimental strategies that way). Ids must be unique across
 * strategies and across modifiers.
 *
 * [default] is the catalogue of DESIGN.md §4 / §12. Order matters only for ties in the planner's ranking
 * (stable sort: earlier wins) and for the contract test's report, so it follows the escalation ladder from the
 * floor upward.
 */
class DefaultStrategyRegistry(
    override val strategies: List<TransitionStrategy>,
    override val modifiers: List<TransitionModifier> = emptyList(),
) : StrategyRegistry {
    init {
        val dupS = strategies.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dupS.isEmpty()) { "duplicate strategy ids: $dupS" }
        val dupM = modifiers.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dupM.isEmpty()) { "duplicate modifier ids: $dupM" }
    }

    /** This registry plus [extra] strategies (an id already present is replaced in place). */
    fun withStrategies(vararg extra: TransitionStrategy): DefaultStrategyRegistry = DefaultStrategyRegistry(merge(strategies, extra.toList()) { it.id }, modifiers)

    /** This registry plus [extra] modifiers (an id already present is replaced in place). */
    fun withModifiers(vararg extra: TransitionModifier): DefaultStrategyRegistry = DefaultStrategyRegistry(strategies, merge(modifiers, extra.toList()) { it.id })

    /** This registry without the given strategy / modifier ids. */
    fun without(vararg ids: String): DefaultStrategyRegistry {
        val drop = ids.toSet()
        return DefaultStrategyRegistry(strategies.filter { it.id !in drop }, modifiers.filter { it.id !in drop })
    }

    val strategyIds: List<String> get() = strategies.map { it.id }
    val modifierIds: List<String> get() = modifiers.map { it.id }

    override fun toString(): String = "DefaultStrategyRegistry(strategies=$strategyIds, modifiers=$modifierIds)"

    companion object {
        /**
         * The shipped catalogue. Strategies are listed in ladder order (floor first). To add a technique, append
         * one line in the marked block below — nothing else in the engine needs to change (the planner, renderer,
         * CLI and the contract test discover it from here).
         */
        fun default(): DefaultStrategyRegistry = DefaultStrategyRegistry(
            strategies = listOf(
                CrossfadeStrategy(),                      // 1  crossfade            — the floor, never blocked
                // ---- LEAD: append the remaining strategies here after merge (ids per DESIGN.md §12) -----------
                // AmbientBridgeStrategy(),              // 13 ambientBridge
                // OutroIntroMinimalStrategy(),          // 2  outroIntroMinimal
                // SpectralFreezeBridgeStrategy(),       // 12 spectralFreezeBridge
                // PhraseCutStrategy(),                  // 3  phraseCut
                // FilterSweepStrategy(),                // 8  filterSweep
                // EchoOutStrategy(),                    // 9  echoOut
                // LoopRollRiserStrategy(),              // 10 loopRollRiser
                // BrakeStopStrategy(),                  // 14 brakeStop
                // BeatMatchedBlendStrategy(),           // 4  beatMatchedBlend
                // BassSwapStrategy(),                   // 5  bassSwap
                // StemSwapStrategy(),                   // 6  stemSwap
                // DrumBreakBridgeStrategy(),            // 7  drumBreakBridge
                // HarmonicBlendStrategy(),              // 11 harmonicBlend
                // ---------------------------------------------------------------------------------------------
            ),
            modifiers = listOf(
                // ---- LEAD: append the modifiers here after merge ------------------------------------------------
                // TempoGlideModifier(),                 // tempoGlide
                // TextureCarryModifier(),               // textureCarry
                // ---------------------------------------------------------------------------------------------
            ),
        )

        /** An empty registry (tests build their own with [withStrategies]). */
        fun empty(): DefaultStrategyRegistry = DefaultStrategyRegistry(emptyList(), emptyList())

        private fun <T> merge(base: List<T>, extra: List<T>, id: (T) -> String): List<T> {
            val out = ArrayList(base)
            for (e in extra) {
                val i = out.indexOfFirst { id(it) == id(e) }
                if (i >= 0) out[i] = e else out += e
            }
            return out
        }
    }
}
