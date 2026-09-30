package dev.muisc.transitions.recipe

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A user-authored transition, described as data. A recipe says, bar by bar, what happens to each deck — its level,
 * its three EQ bands, its filters, how much of it is sent to an echo or a reverb, and (optionally) the level of each
 * of its stems — plus how the two tracks are lined up in time and which pairs it suits. The engine turns every
 * recipe into a first-class [dev.muisc.transitions.TransitionStrategy] ([RecipeStrategy]) with id `recipe:<id>`, so
 * the planner can rank it, the Lab can tune it and the player can play it exactly like a built-in technique.
 *
 * ## Timeline
 * Every position (`at`) is in **bars** from the start of the transition (bar 0). The overlap — both decks audible —
 * runs from bar 0 to `timing.lengthBars` (available in expressions as `bars`). In `match` / `glide` tempo mode B then
 * rides back to its own tempo for `settleBars` and holds it for `holdBars`; the whole timeline is `total` bars long.
 * `match` / `glide` render whole bars (the overlap, settle and hold rounded, the hold at least 1, the overlap shortened
 * when a song is too short); lanes and effect settings are then evaluated with the `bars`, `settle`, `hold` and
 * `total` actually rendered, so a lane written against `total` ends on the seam.
 * Before bar 0 the segment plays a few thousand frames of A untouched and after `total` a few thousand frames of B
 * untouched (the splice guards), which is why the **boundary rule** below exists.
 *
 * ## Lanes
 * A lane is a list of points `{ "at": <bars>, "v": <value>, "curve": "linear" }`. Before its first point a lane
 * holds the first value, after its last point it holds the last value, and between two points the value moves
 * along the EARLIER point's `curve`. An empty lane is neutral everywhere. Units per lane:
 *
 * | lane | unit | neutral | notes |
 * |---|---|---|---|
 * | `level` | linear gain 0..2 | 1 | `equalPower` from 1 to 0 on A and 0 to 1 on B is the classic −3 dB crossfade |
 * | `low`, `mid`, `high` | dB, −120..+12 | 0 | 3-band DJ EQ, crossovers at `timing.lowHz` / `timing.highHz`; `"off"` = −120 |
 * | `hpf`, `lpf` | Hz, 20..20000 | 20 / 20000 | resonant state-variable filters, interpolated on a log scale |
 * | `echo.send`, `reverb.send` | 0..1 | 0 | taken PRE-fader, so a deck can be muted while its tail rings on |
 * | `reverb.freeze` | 0 or 1 | 0 | ≥ 0.5 holds the reverb tail indefinitely |
 * | `stems.drums/bass/vocals/other` | dB | 0 | needs stem separation (pseudo-stems unless an ML separator is installed) |
 *
 * ## Boundary rule (the splice contract)
 * At bar 0 every lane of deck **A** must be neutral, so the segment starts with A exactly as the listener has been
 * hearing it; at `total` every lane of deck **B** must be neutral, so B's body continues seamlessly. Deck A is only
 * rendered during the overlap, so its `level` should reach 0 by `bars` (the renderer declicks a hard stop and
 * warns). Effect tails must have died away by `total`.
 */
@Serializable
data class TransitionRecipe(
    /** Stable identifier: lowercase letters, digits and dashes. The strategy id is `recipe:<id>`. */
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    /** The recipe's own revision, bumped by its author. */
    val version: Int = 1,
    /** Recipe format version; readers refuse formats newer than [FORMAT_VERSION]. */
    val format: Int = FORMAT_VERSION,
    /** 0..1: how showy the transition is (0 = invisible, 1 = a showpiece). Feeds the planner's energy preference. */
    val ambition: Double = 0.5,
    val tags: List<String> = emptyList(),
    /**
     * User-tunable knobs, referenced by name from any expression. Each becomes a parameter of the strategy, so the
     * Lab shows a slider for it and presets can store it. Names are identifiers (letters, digits, `_`; no dots).
     */
    val vars: Map<String, RecipeVar> = emptyMap(),
    val timing: RecipeTiming = RecipeTiming(),
    /** Which pairs the recipe suits. Unmet rules block it; met rules give [RecipeRules.baseScore]. */
    val rules: RecipeRules = RecipeRules(),
    /** The outgoing deck. */
    val a: DeckRecipe = DeckRecipe(),
    /** The incoming deck. */
    val b: DeckRecipe = DeckRecipe(),
    /**
     * Modifier ids the recipe REQUIRES (e.g. `textureCarry`): a recipe listing a modifier the registry does not have
     * is left out ([RecipeCatalog]). The list does not choose what is attached: as for a built-in strategy, the
     * planner attaches every installed modifier that accepts the pair (unless the listener or a preset turned it
     * off), listed here or not. `tempoGlide` never accepts a recipe (the validator warns when it is listed); a recipe
     * glides with `timing.tempo = glide`.
     */
    val modifiers: List<String> = emptyList(),
) {
    /** The strategy id this recipe registers as. */
    val strategyId: String get() = STRATEGY_PREFIX + id

    companion object {
        const val FORMAT_VERSION = 1
        const val STRATEGY_PREFIX = "recipe:"
    }
}

/** A tunable number. [integer] rounds values (bar positions usually want whole bars). */
@Serializable
data class RecipeVar(
    val default: Double,
    val min: Double,
    val max: Double,
    val label: String = "",
    val unit: String = "",
    val doc: String = "",
    val integer: Boolean = false,
)

/** How the two tracks are lined up in time. */
@Serializable
enum class RecipeTempo {
    /** Beat-matched: B is time-stretched onto A's tempo for the overlap, then rides back to its own tempo. */
    @SerialName("match") MATCH,

    /** Beat-matched with the master tempo gliding from A's tempo to B's across the overlap. */
    @SerialName("glide") GLIDE,

    /** No stretching: the timeline follows A's bars and B plays at its own tempo from `bEntersAtBar`. */
    @SerialName("none") NONE,
}

/** Where on A the transition may start. */
@Serializable
enum class RecipeAlign {
    /** At the start of an 8-bar phrase (falls back to a downbeat when phrases are unknown). */
    @SerialName("phrase") PHRASE,

    /** At any downbeat. */
    @SerialName("downbeat") DOWNBEAT,
}

@Serializable
data class RecipeTiming(
    /** Overlap length in bars (1..64). Available in expressions as `bars`. */
    val lengthBars: Expr = Expr("16"),
    val tempo: RecipeTempo = RecipeTempo.MATCH,
    val align: RecipeAlign = RecipeAlign.PHRASE,
    /** Bars B enters relative to its mix-in cue; negative starts earlier, inside B's intro. */
    val bEntryOffsetBars: Expr = Expr("0"),
    /** `none` tempo only: the timeline bar at which B's entry downbeat lands. */
    val bEntersAtBar: Expr = Expr("0"),
    /** `match` / `glide`: bars for B alone to ride back to its own tempo after the overlap. */
    val settleBars: Expr = Expr("2"),
    /** Bars held at B's own tempo before the seam (all modes). */
    val holdBars: Expr = Expr("2"),
    /** 3-band EQ crossover frequencies in Hz. */
    val lowHz: Expr = Expr("200"),
    val highHz: Expr = Expr("4000"),
)

/**
 * Suitability rules. A rule that is not met blocks the recipe for that pair (the planner moves on to other
 * techniques); when every rule is met the recipe scores [baseScore] shaped by the generic compatibility sub-scores.
 */
@Serializable
data class RecipeRules(
    /** Requires confident beat grids on both tracks. Default: true for `match` / `glide`, false for `none`. */
    val requiresBeatMatch: Boolean? = null,
    /** Largest tempo difference, in percent, the recipe accepts. Default: the user's max-stretch preference. */
    val maxStretchPercent: Double? = null,
    /** Largest Camelot distance (after the best allowed pitch shift) the recipe accepts; null = any. */
    val maxKeyDistance: Int? = null,
    /** Allowed outro types of A (empty = any). */
    val outro: List<OutroType> = emptyList(),
    /** Allowed intro types of B (empty = any). */
    val intro: List<IntroType> = emptyList(),
    /** Accepted range of B's energy minus A's energy (−1..1). */
    val minEnergyDelta: Double = -1.0,
    val maxEnergyDelta: Double = 1.0,
    /** Score (0..1) when every rule is met, before the planner's weights. */
    val baseScore: Double = 0.6,
)

/** What happens to one deck. Every lane defaults to empty (neutral). */
@Serializable
data class DeckRecipe(
    val level: List<RecipePoint> = emptyList(),
    val low: List<RecipePoint> = emptyList(),
    val mid: List<RecipePoint> = emptyList(),
    val high: List<RecipePoint> = emptyList(),
    val hpf: List<RecipePoint> = emptyList(),
    val lpf: List<RecipePoint> = emptyList(),
    /** Filter resonance (Q) for both filters, 0.5..6. */
    val resonance: Expr = Expr("0.707"),
    val echo: EchoRecipe? = null,
    val reverb: ReverbRecipe? = null,
    val stems: StemsRecipe? = null,
)

@Serializable
data class RecipePoint(
    /** Position in bars from the start of the transition. */
    val at: Expr,
    /** Value in the lane's unit. */
    val v: Expr,
    /** How the value moves from this point to the next. */
    val curve: RecipeCurve = RecipeCurve.LINEAR,
)

@Serializable
enum class RecipeCurve {
    @SerialName("linear") LINEAR,

    /** Sine-shaped: pairs a falling lane on one deck with a rising one on the other at constant power. */
    @SerialName("equalPower") EQUAL_POWER,

    /** Smoothstep: slow start, slow end. */
    @SerialName("sCurve") S_CURVE,

    /** Slow start, fast end. */
    @SerialName("exp") EXP,

    /** Holds this point's value until the next point, then jumps. */
    @SerialName("step") STEP,
}

/** A tempo-synced feedback delay fed from the deck before its level fader. */
@Serializable
data class EchoRecipe(
    /** Send amount lane, 0..1. */
    val send: List<RecipePoint> = emptyList(),
    /** Delay time in beats of the master tempo (0.75 = dotted eighth). */
    val beats: Expr = Expr("0.75"),
    /** Feedback 0..0.95. */
    val feedback: Expr = Expr("0.7"),
    /** Low-pass in the feedback loop, Hz. */
    val dampHz: Expr = Expr("4000"),
    /** Wet return level, linear 0..2. */
    val returnLevel: Expr = Expr("1"),
)

/** A reverb fed from the deck before its level fader, with an optional freeze. */
@Serializable
data class ReverbRecipe(
    /** Send amount lane, 0..1. */
    val send: List<RecipePoint> = emptyList(),
    /** Freeze lane: ≥ 0.5 holds the tail indefinitely. */
    val freeze: List<RecipePoint> = emptyList(),
    /** Decay time (RT60) in seconds when not frozen. */
    val decaySec: Expr = Expr("3"),
    val dampHz: Expr = Expr("6000"),
    /** Wet return level, linear 0..2. */
    val returnLevel: Expr = Expr("1"),
)

/** Per-stem level lanes in dB. Using any of them makes the recipe need stem separation. */
@Serializable
data class StemsRecipe(
    val drums: List<RecipePoint> = emptyList(),
    val bass: List<RecipePoint> = emptyList(),
    val vocals: List<RecipePoint> = emptyList(),
    val other: List<RecipePoint> = emptyList(),
)
