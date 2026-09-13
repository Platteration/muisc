package dev.muisc.transitions.planner

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType

/**
 * Strategy families for the structural prior and the planner's per-family defaults. The families mirror the
 * escalation ladder of DESIGN.md §5.4: beat-domain (4, 5, 6, 7, 11) → tempo-agnostic beat-aware "cut" moves
 * (3, 8, 9, 10, 14) → structural (2, 12) → generated bridge (13) → crossfade (1). [GENERIC] is what an unknown
 * (third-party / experimental) strategy id maps to: a flat prior of [StructTables.GENERIC_PRIOR].
 */
enum class StrategyFamily { BEAT_DOMAIN, CUT, STRUCTURAL, BRIDGE, CROSSFADE, GENERIC }

/**
 * `s_struct = table_family[outro][intro]`: the 6×6 [OutroType] × [IntroType] priors of DESIGN.md §5.2, kept per
 * strategy *family* (one table per family, looked up by strategy id) with the document's anchor values:
 * `beatMatchedBlend[BEAT_OUTRO][BEAT_INTRO] = 1.0`, `phraseCut[*][COLD_START] = 1.0`,
 * `outroIntroMinimal[FADE_OUT][AMBIENT_INTRO] = 1.0`, `ambientBridge[HARD_STOP][AMBIENT_INTRO] = 0.9`.
 *
 * Rows are indexed by `OutroType.ordinal` (HARD_STOP, FADE_OUT, BEAT_OUTRO, AMBIENT_OUTRO, VOCAL_OUTRO, UNKNOWN),
 * columns by `IntroType.ordinal` (BEAT_INTRO, AMBIENT_INTRO, VOCAL_INTRO, COLD_START, SILENCE, UNKNOWN). Every
 * value is in 0..1. The tables are priors, not gates: a strategy's own `applicability()` still decides whether it
 * is applicable at all.
 */
object StructTables {
    /** Flat prior for strategies of an unknown family. */
    const val GENERIC_PRIOR: Double = 0.7

    /** Strategy ids per family (DESIGN.md §12). Ids not listed here are [StrategyFamily.GENERIC]. */
    val BEAT_DOMAIN_IDS: Set<String> = setOf("beatMatchedBlend", "bassSwap", "stemSwap", "drumBreakBridge", "harmonicBlend")
    val CUT_IDS: Set<String> = setOf("phraseCut", "filterSweep", "echoOut", "loopRollRiser", "brakeStop")
    val STRUCTURAL_IDS: Set<String> = setOf("outroIntroMinimal", "spectralFreezeBridge")
    val BRIDGE_IDS: Set<String> = setOf("ambientBridge")
    val CROSSFADE_IDS: Set<String> = setOf("crossfade")

    /** Strategies that need stems (`s_stems` applies only to these). */
    val STEM_STRATEGY_IDS: Set<String> = setOf("stemSwap", "drumBreakBridge")

    /** Family of a strategy id ([StrategyFamily.GENERIC] when unknown). */
    fun family(strategyId: String): StrategyFamily = when (strategyId) {
        in BEAT_DOMAIN_IDS -> StrategyFamily.BEAT_DOMAIN
        in CUT_IDS -> StrategyFamily.CUT
        in STRUCTURAL_IDS -> StrategyFamily.STRUCTURAL
        in BRIDGE_IDS -> StrategyFamily.BRIDGE
        in CROSSFADE_IDS -> StrategyFamily.CROSSFADE
        else -> StrategyFamily.GENERIC
    }

    /** The structural prior of [strategyId] for an [outro] → [intro] pair. */
    fun prior(strategyId: String, outro: OutroType, intro: IntroType): Double = prior(family(strategyId), outro, intro)

    /** The structural prior of a [family] for an [outro] → [intro] pair. */
    fun prior(family: StrategyFamily, outro: OutroType, intro: IntroType): Double = table(family)[outro.ordinal][intro.ordinal]

    /** The whole 6×6 table of a family (rows = outro, columns = intro); a defensive copy. */
    fun table(family: StrategyFamily): Array<DoubleArray> = Array(OutroType.entries.size) { TABLES.getValue(family)[it].copyOf() }

    // Column order: BEAT_INTRO, AMBIENT_INTRO, VOCAL_INTRO, COLD_START, SILENCE, UNKNOWN
    private val BEAT_DOMAIN = arrayOf(
        /* HARD_STOP     */ doubleArrayOf(0.50, 0.30, 0.30, 0.40, 0.20, 0.40),
        /* FADE_OUT      */ doubleArrayOf(0.60, 0.50, 0.40, 0.40, 0.30, 0.50),
        /* BEAT_OUTRO    */ doubleArrayOf(1.00, 0.80, 0.60, 0.70, 0.40, 0.70),
        /* AMBIENT_OUTRO */ doubleArrayOf(0.70, 0.60, 0.50, 0.50, 0.30, 0.50),
        /* VOCAL_OUTRO   */ doubleArrayOf(0.60, 0.50, 0.30, 0.50, 0.30, 0.50),
        /* UNKNOWN       */ doubleArrayOf(0.70, 0.60, 0.50, 0.50, 0.40, 0.60),
    )
    private val CUT = arrayOf(
        /* HARD_STOP     */ doubleArrayOf(0.90, 0.50, 0.60, 1.00, 0.40, 0.70),
        /* FADE_OUT      */ doubleArrayOf(0.50, 0.30, 0.30, 1.00, 0.30, 0.50),
        /* BEAT_OUTRO    */ doubleArrayOf(0.90, 0.50, 0.50, 1.00, 0.40, 0.70),
        /* AMBIENT_OUTRO */ doubleArrayOf(0.60, 0.40, 0.40, 1.00, 0.30, 0.50),
        /* VOCAL_OUTRO   */ doubleArrayOf(0.60, 0.40, 0.40, 1.00, 0.30, 0.50),
        /* UNKNOWN       */ doubleArrayOf(0.70, 0.40, 0.40, 1.00, 0.40, 0.60),
    )
    private val STRUCTURAL = arrayOf(
        /* HARD_STOP     */ doubleArrayOf(0.30, 0.60, 0.50, 0.30, 0.50, 0.40),
        /* FADE_OUT      */ doubleArrayOf(0.50, 1.00, 0.90, 0.30, 0.90, 0.70),
        /* BEAT_OUTRO    */ doubleArrayOf(0.30, 0.50, 0.40, 0.20, 0.30, 0.40),
        /* AMBIENT_OUTRO */ doubleArrayOf(0.50, 0.95, 0.80, 0.30, 0.80, 0.70),
        /* VOCAL_OUTRO   */ doubleArrayOf(0.40, 0.80, 0.60, 0.30, 0.70, 0.60),
        /* UNKNOWN       */ doubleArrayOf(0.40, 0.70, 0.60, 0.30, 0.60, 0.50),
    )
    private val BRIDGE = arrayOf(
        /* HARD_STOP     */ doubleArrayOf(0.60, 0.90, 0.80, 0.50, 0.80, 0.70),
        /* FADE_OUT      */ doubleArrayOf(0.60, 0.80, 0.70, 0.50, 0.70, 0.70),
        /* BEAT_OUTRO    */ doubleArrayOf(0.50, 0.70, 0.60, 0.40, 0.60, 0.60),
        /* AMBIENT_OUTRO */ doubleArrayOf(0.70, 0.90, 0.80, 0.50, 0.80, 0.70),
        /* VOCAL_OUTRO   */ doubleArrayOf(0.60, 0.80, 0.70, 0.50, 0.70, 0.70),
        /* UNKNOWN       */ doubleArrayOf(0.60, 0.80, 0.70, 0.50, 0.70, 0.70),
    )
    private val CROSSFADE = arrayOf(
        /* HARD_STOP     */ doubleArrayOf(0.50, 0.60, 0.60, 0.40, 0.60, 0.60),
        /* FADE_OUT      */ doubleArrayOf(0.60, 0.80, 0.70, 0.40, 0.70, 0.60),
        /* BEAT_OUTRO    */ doubleArrayOf(0.40, 0.60, 0.50, 0.40, 0.60, 0.60),
        /* AMBIENT_OUTRO */ doubleArrayOf(0.60, 0.80, 0.70, 0.40, 0.70, 0.60),
        /* VOCAL_OUTRO   */ doubleArrayOf(0.50, 0.70, 0.50, 0.40, 0.60, 0.60),
        /* UNKNOWN       */ doubleArrayOf(0.60, 0.60, 0.60, 0.40, 0.60, 0.60),
    )
    private val GENERIC = Array(OutroType.entries.size) { DoubleArray(IntroType.entries.size) { GENERIC_PRIOR } }

    private val TABLES: Map<StrategyFamily, Array<DoubleArray>> = mapOf(
        StrategyFamily.BEAT_DOMAIN to BEAT_DOMAIN, StrategyFamily.CUT to CUT, StrategyFamily.STRUCTURAL to STRUCTURAL,
        StrategyFamily.BRIDGE to BRIDGE, StrategyFamily.CROSSFADE to CROSSFADE, StrategyFamily.GENERIC to GENERIC,
    )

    init {
        for ((family, t) in TABLES) {
            check(t.size == OutroType.entries.size) { "$family: ${t.size} rows" }
            for (row in t) { check(row.size == IntroType.entries.size) { "$family: ${row.size} columns" }; for (v in row) check(v in 0.0..1.0) { "$family: prior $v" } }
        }
    }
}
