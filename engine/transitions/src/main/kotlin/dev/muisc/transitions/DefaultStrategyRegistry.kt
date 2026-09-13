package dev.muisc.transitions

import dev.muisc.transitions.modifiers.TempoGlideModifier
import dev.muisc.transitions.modifiers.TextureCarryModifier
import dev.muisc.transitions.strategies.AmbientBridgeStrategy
import dev.muisc.transitions.strategies.BassSwapStrategy
import dev.muisc.transitions.strategies.BeatMatchedBlendStrategy
import dev.muisc.transitions.strategies.BrakeStopStrategy
import dev.muisc.transitions.strategies.CrossfadeStrategy
import dev.muisc.transitions.strategies.DrumBreakBridgeStrategy
import dev.muisc.transitions.strategies.EchoOutStrategy
import dev.muisc.transitions.strategies.FilterSweepStrategy
import dev.muisc.transitions.strategies.HarmonicBlendStrategy
import dev.muisc.transitions.strategies.LoopRollRiserStrategy
import dev.muisc.transitions.strategies.OutroIntroMinimalStrategy
import dev.muisc.transitions.strategies.PhraseCutStrategy
import dev.muisc.transitions.strategies.SpectralFreezeBridgeStrategy
import dev.muisc.transitions.strategies.StemSwapStrategy

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
                OutroIntroMinimalStrategy(),              // 2  outroIntroMinimal    — respect the producers' outro/intro
                PhraseCutStrategy(),                      // 3  phraseCut            — phrase-aligned hard cut
                BeatMatchedBlendStrategy(),               // 4  beatMatchedBlend     — classic long blend
                BassSwapStrategy(),                       // 5  bassSwap             — EQ mix with low-band handover
                StemSwapStrategy(),                       // 6  stemSwap             — stem-by-stem handover
                DrumBreakBridgeStrategy(),                // 7  drumBreakBridge      — drums-only bridge
                FilterSweepStrategy(),                    // 8  filterSweep          — resonant sweep out / open in
                EchoOutStrategy(),                        // 9  echoOut              — beat-synced echo tail
                LoopRollRiserStrategy(),                  // 10 loopRollRiser        — loop roll + riser into the drop
                HarmonicBlendStrategy(),                  // 11 harmonicBlend        — key-aware blend with pitch shift
                SpectralFreezeBridgeStrategy(),           // 12 spectralFreezeBridge — freeze A's last chord
                AmbientBridgeStrategy(),                  // 13 ambientBridge        — generated bridge, the musical floor
                BrakeStopStrategy(),                      // 14 brakeStop            — vinyl brake into a cold open
                // ---------------------------------------------------------------------------------------------
            ),
            modifiers = listOf(
                TempoGlideModifier(),                     // tempoGlide   — walk the master grid from A's tempo to B's
                TextureCarryModifier(),                   // textureCarry — carry A's texture across and drop it off
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
