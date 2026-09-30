package dev.muisc.transitions.recipe

import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import kotlin.math.roundToInt

/** A recipe problem with the JSON-ish path it concerns, e.g. `a.level[2].at`. */
class RecipeException(val path: String, message: String, cause: Throwable? = null) :
    IllegalArgumentException(if (path.isEmpty()) message else "$path: $message", cause)

/**
 * Binds a recipe's variables and evaluates every expression.
 *
 * Names available in expressions, besides the recipe's own [TransitionRecipe.vars]:
 *
 * | name | value |
 * |---|---|
 * | `bars` | the overlap length (`timing.lengthBars`) |
 * | `settle`, `hold` | `timing.settleBars`, `timing.holdBars` |
 * | `total` | the whole timeline in bars (overlap + settle + hold, or, in `none` tempo, max(bars, bEntersAtBar) + hold) |
 * | `beat` | one beat expressed in bars (0.25 in 4/4) |
 * | `off` | −120, a dB value that means silence |
 *
 * `bars` may only be used after it is defined: `lengthBars` itself can use variables and `beat` but not `bars`,
 * `settle`, `hold` or `total`.
 */
object RecipeResolver {
    const val OFF_DB = -120.0
    const val MIN_LENGTH_BARS = 1.0
    const val MAX_LENGTH_BARS = 64.0

    /** Names a variable may not take. */
    val RESERVED: Set<String> = setOf("bars", "settle", "hold", "total", "beat", "off") + Expr.FUNCTIONS.keys

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The recipe's variables as strategy parameters (integer variables become [ParamSpec.IntSpec]). */
    fun paramSpecs(recipe: TransitionRecipe): List<ParamSpec> = recipe.vars.map { (name, v) ->
        val label = v.label.ifBlank { name }
        if (v.integer) ParamSpec.IntSpec(name, label, v.default.roundToInt(), v.min.roundToInt(), v.max.roundToInt(), v.unit, v.doc)
        else ParamSpec.DoubleSpec(name, label, v.default, v.min, v.max, v.unit, 0.0, v.doc)
    }

    /**
     * Resolves [recipe] with [params] (keyed by variable name; unknown keys are ignored, values clamped to the
     * variable's range). Throws [RecipeException] naming the offending field.
     */
    fun resolve(recipe: TransitionRecipe, params: Params = Params.EMPTY, beatsPerBar: Int = 4): ResolvedRecipe =
        resolve(recipe, params, beatsPerBar, null)

    /** Timing values the geometry actually renders, bound in place of the recipe's own (see [resolveAsRendered]). */
    internal class RenderedTiming(val bars: Double, val settle: Double, val hold: Double)

    /**
     * Resolves [recipe] as [resolve] does, but with `bars`, `settle` and `hold` (and so `total`) bound to [timing]:
     * the whole bars a `match` / `glide` geometry renders after rounding and shortening. The timing fields
     * (`lengthBars`, `settleBars`, `holdBars`, `bEntersAtBar`) are evaluated with the recipe's own values, as in
     * [resolve]; every later expression (`bEntryOffsetBars`, the EQ crossovers, lanes, effect settings) sees the
     * rendered ones, so a lane written against `total` ends on the rendered seam. ([RecipeGeometry.asRendered] then
     * puts back the planned `bEntryOffsetBars`, which decided where B starts.)
     */
    internal fun resolveAsRendered(recipe: TransitionRecipe, params: Params, beatsPerBar: Int, timing: RenderedTiming): ResolvedRecipe =
        resolve(recipe, params, beatsPerBar, timing)

    private fun resolve(recipe: TransitionRecipe, params: Params, beatsPerBar: Int, rendered: RenderedTiming?): ResolvedRecipe {
        require(beatsPerBar >= 1) { "beatsPerBar must be >= 1" }
        if (recipe.format > TransitionRecipe.FORMAT_VERSION) {
            throw RecipeException("format", "recipe format ${recipe.format} is newer than this engine understands (${TransitionRecipe.FORMAT_VERSION})")
        }

        // 1. Variables.
        val vars = LinkedHashMap<String, Double>()
        for ((name, v) in recipe.vars) {
            val path = "vars.$name"
            if (!IDENTIFIER.matches(name)) throw RecipeException(path, "variable names are letters, digits and '_' (no dots or spaces)")
            if (name in RESERVED) throw RecipeException(path, "'$name' is a built-in name and cannot be a variable")
            if (v.min > v.max) throw RecipeException(path, "min ${v.min} is greater than max ${v.max}")
            if (v.default < v.min || v.default > v.max) throw RecipeException(path, "default ${v.default} is outside ${v.min}..${v.max}")
            val raw = params[name]?.toDoubleOrNull() ?: v.default
            var value = raw.coerceIn(v.min, v.max)
            if (v.integer) value = value.roundToInt().toDouble()
            vars[name] = value
        }
        val beat = 1.0 / beatsPerBar
        val scope = HashMap<String, Double>(vars).apply { put("beat", beat); put("off", OFF_DB) }

        fun eval(e: Expr, path: String): Double {
            val v = try { e.eval { scope[it] } } catch (x: ExprException) { throw RecipeException(path, x.message ?: "bad expression", x) }
            if (v.isNaN() || v.isInfinite()) throw RecipeException(path, "evaluates to $v")
            return v
        }

        // 2. Timing (in dependency order).
        val t = recipe.timing
        var length = eval(t.lengthBars, "timing.lengthBars")
        if (length < MIN_LENGTH_BARS || length > MAX_LENGTH_BARS) throw RecipeException("timing.lengthBars", "must be between ${MIN_LENGTH_BARS.toInt()} and ${MAX_LENGTH_BARS.toInt()} bars, got $length")
        scope["bars"] = length
        var settle = eval(t.settleBars, "timing.settleBars").also { if (it < 0) throw RecipeException("timing.settleBars", "cannot be negative") }
        var hold = eval(t.holdBars, "timing.holdBars").also { if (it < 0) throw RecipeException("timing.holdBars", "cannot be negative") }
        scope["settle"] = settle
        scope["hold"] = hold
        val bEntersAt = eval(t.bEntersAtBar, "timing.bEntersAtBar").also { if (it < 0) throw RecipeException("timing.bEntersAtBar", "cannot be negative") }
        if (rendered != null) {
            // The timing fields above were read with the recipe's own values; everything below sees the rendered ones.
            length = rendered.bars; settle = rendered.settle; hold = rendered.hold
            scope["bars"] = length; scope["settle"] = settle; scope["hold"] = hold
        }
        val total = when (t.tempo) {
            RecipeTempo.NONE -> maxOf(length, bEntersAt) + hold
            else -> length + settle + hold
        }
        scope["total"] = total
        val entryOffset = eval(t.bEntryOffsetBars, "timing.bEntryOffsetBars").roundToInt()
        val lowHz = eval(t.lowHz, "timing.lowHz")
        val highHz = eval(t.highHz, "timing.highHz")
        if (lowHz !in 20.0..20000.0 || highHz !in 20.0..20000.0 || lowHz >= highHz) {
            throw RecipeException("timing", "EQ crossovers must satisfy 20 <= lowHz < highHz <= 20000 (got $lowHz / $highHz)")
        }

        // 3. Lanes.
        fun lane(points: List<RecipePoint>, kind: LaneKind, path: String): ResolvedLane =
            ResolvedLane(kind, points.mapIndexed { i, p ->
                ResolvedPoint(eval(p.at, "$path[$i].at"), eval(p.v, "$path[$i].v"), p.curve)
            })

        fun deck(d: DeckRecipe, name: String) = ResolvedDeck(
            level = lane(d.level, LaneKind.LEVEL, "$name.level"),
            low = lane(d.low, LaneKind.DB, "$name.low"),
            mid = lane(d.mid, LaneKind.DB, "$name.mid"),
            high = lane(d.high, LaneKind.DB, "$name.high"),
            hpf = lane(d.hpf, LaneKind.HPF, "$name.hpf"),
            lpf = lane(d.lpf, LaneKind.LPF, "$name.lpf"),
            resonance = eval(d.resonance, "$name.resonance"),
            echo = d.echo?.let { e ->
                ResolvedEcho(lane(e.send, LaneKind.SEND, "$name.echo.send"), eval(e.beats, "$name.echo.beats"), eval(e.feedback, "$name.echo.feedback"),
                    eval(e.dampHz, "$name.echo.dampHz"), eval(e.returnLevel, "$name.echo.returnLevel"))
            },
            reverb = d.reverb?.let { r ->
                ResolvedReverb(lane(r.send, LaneKind.SEND, "$name.reverb.send"), lane(r.freeze, LaneKind.FREEZE, "$name.reverb.freeze"),
                    eval(r.decaySec, "$name.reverb.decaySec"), eval(r.dampHz, "$name.reverb.dampHz"), eval(r.returnLevel, "$name.reverb.returnLevel"))
            },
            stems = d.stems?.let { s ->
                ResolvedStems(lane(s.drums, LaneKind.DB, "$name.stems.drums"), lane(s.bass, LaneKind.DB, "$name.stems.bass"),
                    lane(s.vocals, LaneKind.DB, "$name.stems.vocals"), lane(s.other, LaneKind.DB, "$name.stems.other"))
            },
        )

        return ResolvedRecipe(
            recipe = recipe, vars = vars, beatsPerBar = beatsPerBar,
            lengthBars = length, settleBars = settle, holdBars = hold, totalBars = total,
            tempo = t.tempo, align = t.align, bEntryOffsetBars = entryOffset, bEntersAtBar = bEntersAt,
            lowHz = lowHz, highHz = highHz,
            a = deck(recipe.a, "a"), b = deck(recipe.b, "b"),
        )
    }
}
