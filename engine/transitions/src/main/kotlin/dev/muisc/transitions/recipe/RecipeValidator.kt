package dev.muisc.transitions.recipe

import dev.muisc.transitions.Params
import dev.muisc.transitions.modifiers.TempoGlideModifier
import dev.muisc.transitions.modifiers.TextureCarryModifier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/** What [RecipeValidator.validate] found. [valid] means no errors (warnings allowed). */
class RecipeReport(val recipe: TransitionRecipe, val problems: List<RecipeProblem>) {
    val errors: List<RecipeProblem> get() = problems.filter { it.isError }
    val warnings: List<RecipeProblem> get() = problems.filter { !it.isError }
    val valid: Boolean get() = problems.none { it.isError }

    override fun toString(): String = if (problems.isEmpty()) "${recipe.id}: ok" else "${recipe.id}:\n" + problems.joinToString("\n") { "  $it" }
}

/**
 * Checks a recipe beyond what [RecipeResolver] enforces, and explains each problem in plain words.
 *
 * **Errors** make a recipe unusable: a malformed id, an empty name, values outside their documented ranges, lane
 * points outside the transition, the boundary rule (every lane of deck A untouched at bar 0, every lane of deck B
 * untouched at `total`), echo feedback above 0.95, filter resonance outside 0.5..6, unknown modifiers, unknown
 * names in expressions, and anything [RecipeResolver] refuses.
 *
 * **Warnings** leave the recipe usable: deck A still audible when the overlap ends (the renderer declicks it), an
 * echo/reverb send or reverb freeze still on within the last bar (the renderer force-releases the tail), stem lanes
 * (they need stem separation; pseudo-stems leak), knobs that no expression uses, points written out of time order,
 * `tempoGlide` in [TransitionRecipe.modifiers] (it never attaches to a recipe), and, in `match` / `glide`, a
 * `lengthBars`, `settleBars` or `holdBars` that is rendered as a different number of whole bars (at a checked
 * setting, or because it uses a knob that is not a whole-number knob).
 *
 * **Every setting.** A recipe must work for every knob position the user can choose, not only its defaults, so the
 * setting-dependent checks run at: the defaults; each variable alone at its minimum and at its maximum (the others
 * at their defaults); every variable at its minimum; every variable at its maximum. A problem that appears only
 * away from the defaults says which setting breaks it ("with swapBar = 31: ..."), and is reported once, for the
 * first of those settings (in that order) that shows it. Other combinations are not checked.
 *
 * @param knownModifiers modifier ids a recipe may ask for (default: the shipped `tempoGlide` and `textureCarry`).
 * @param beatsPerBar the meter the recipe is resolved in (the value of `beat` is 1 / beatsPerBar).
 */
class RecipeValidator(
    val knownModifiers: Set<String> = DEFAULT_MODIFIERS,
    val beatsPerBar: Int = 4,
) {
    init {
        require(beatsPerBar >= 1) { "beatsPerBar must be >= 1" }
    }

    fun validate(recipe: TransitionRecipe): RecipeReport {
        val out = Findings()
        val resolvable = staticChecks(recipe, out)
        val exprOk = expressionChecks(recipe, out)
        if (resolvable && exprOk) {
            for (setting in settings(recipe)) settingChecks(recipe, setting, out)
        }
        return RecipeReport(recipe, out.list)
    }

    // ---- findings, deduplicated by rule + path ----------------------------------------------------------------

    private class Findings {
        val list = ArrayList<RecipeProblem>()
        private val seen = HashSet<String>()

        fun add(key: String, problem: RecipeProblem) {
            if (seen.add(key + "@" + problem.path)) list += problem
        }

        fun error(key: String, path: String, message: String, setting: Setting? = null) =
            add(key, RecipeProblem.error(path, prefixed(message, setting), setting?.label))

        fun warning(key: String, path: String, message: String, setting: Setting? = null) =
            add(key, RecipeProblem.warning(path, prefixed(message, setting), setting?.label))

        private fun prefixed(message: String, setting: Setting?) = if (setting?.label == null) message else "with ${setting.label}: $message"
    }

    /** One knob setting; [label] is null for the defaults. */
    private class Setting(val label: String?, val values: Map<String, Double>)

    // ---- checks that do not depend on the knob setting ---------------------------------------------------------

    /** Returns false when the resolver would refuse the recipe at every setting (bad format or variables). */
    private fun staticChecks(r: TransitionRecipe, out: Findings): Boolean {
        if (!ID_PATTERN.matches(r.id)) {
            val slug = slug(r.id)
            val hint = if (slug.isNotEmpty() && slug != r.id) " — for example '$slug'" else ""
            out.error("id", "id", "the id '${r.id}' may only use lowercase letters (a-z), digits and dashes$hint")
        }
        if (r.name.isBlank()) out.error("name", "name", "give the recipe a name; it is what the player and the Lab show")
        if (!(r.ambition in 0.0..1.0)) out.error("range", "ambition", "ambition is ${fmt(r.ambition)}, but it must be between 0 (invisible) and 1 (a showpiece)")
        val before = out.list.count { it.isError }
        if (r.format > TransitionRecipe.FORMAT_VERSION) {
            out.error("format", "format", "this recipe uses format ${r.format}, which is newer than this version of Muisc understands (${TransitionRecipe.FORMAT_VERSION}); update Muisc")
        } else if (r.format < 1) {
            out.error("format", "format", "format must be ${TransitionRecipe.FORMAT_VERSION} (or left out)")
        }

        // Variables.
        val used = allExpressions(r).flatMap { it.expr.names() }.toSet()
        for ((name, v) in r.vars) {
            val path = "vars.$name"
            when {
                !IDENTIFIER.matches(name) -> out.error("var", path, "the knob name '$name' may only use letters, digits and '_' (no dots, dashes or spaces), and cannot start with a digit")
                name in RecipeResolver.RESERVED -> out.error("var", path, "'$name' is a built-in name (${RecipeResolver.RESERVED.sorted().joinToString(", ")}); pick another name for this knob")
            }
            if (v.min > v.max) {
                out.error("var-range", path, "min ${fmt(v.min)} is greater than max ${fmt(v.max)}; swap them")
            } else {
                if (v.default < v.min || v.default > v.max) out.error("var-default", "$path.default", "the default ${fmt(v.default)} is outside the knob's range ${fmt(v.min)}..${fmt(v.max)}")
                if (v.min == v.max) out.warning("var-fixed", path, "min and max are both ${fmt(v.min)}, so this knob cannot be moved")
            }
            if (v.integer && listOf(v.default, v.min, v.max).any { it != round(it) }) {
                out.warning("var-integer", path, "this is a whole-number knob but its default/min/max are not whole numbers; they will be rounded")
            }
            if (name !in used) out.warning("var-unused", path, "no expression uses the knob '$name', so moving it changes nothing")
        }

        val resolvable = out.list.count { it.isError } == before

        // Rules.
        val rules = r.rules
        if (!(rules.baseScore in 0.0..1.0)) out.error("range", "rules.baseScore", "baseScore is ${fmt(rules.baseScore)}, but it must be between 0 and 1")
        if (!(rules.minEnergyDelta in -1.0..1.0)) out.error("range", "rules.minEnergyDelta", "minEnergyDelta must be between -1 and 1 (it is ${fmt(rules.minEnergyDelta)})")
        if (!(rules.maxEnergyDelta in -1.0..1.0)) out.error("range", "rules.maxEnergyDelta", "maxEnergyDelta must be between -1 and 1 (it is ${fmt(rules.maxEnergyDelta)})")
        if (rules.minEnergyDelta > rules.maxEnergyDelta) {
            out.error("energy", "rules.minEnergyDelta", "minEnergyDelta (${fmt(rules.minEnergyDelta)}) is greater than maxEnergyDelta (${fmt(rules.maxEnergyDelta)}), so no pair of tracks could ever match")
        }
        rules.maxStretchPercent?.let { if (!(it >= 0.0)) out.error("range", "rules.maxStretchPercent", "maxStretchPercent cannot be negative (it is ${fmt(it)})") }
        rules.maxKeyDistance?.let { if (it < 0) out.error("range", "rules.maxKeyDistance", "maxKeyDistance cannot be negative (it is $it)") }

        // Modifiers.
        val seenMods = HashSet<String>()
        r.modifiers.forEachIndexed { i, m ->
            if (m !in knownModifiers) {
                val hint = RecipeCodec.suggest(m, knownModifiers)?.let { " (did you mean '$it'?)" } ?: ""
                val known = if (knownModifiers.isEmpty()) "none are available" else "known: ${knownModifiers.sorted().joinToString(", ")}"
                out.error("modifier", "modifiers[$i]", "unknown modifier '$m'$hint; $known")
            } else if (!seenMods.add(m)) {
                out.warning("modifier-dup", "modifiers[$i]", "'$m' is listed twice")
            } else if (m == TempoGlideModifier.ID) {
                out.warning(
                    "modifier-glide", "modifiers[$i]",
                    "tempoGlide never attaches to a recipe (it only works on the built-in beat-matched techniques), so listing it only makes the " +
                        "recipe unavailable where tempoGlide is not installed; for a tempo glide set timing.tempo to \"glide\" and remove it from this list",
                )
            }
        }

        // Stems.
        for ((name, deck) in listOf("a" to r.a, "b" to r.b)) {
            val stems = deck.stems ?: continue
            val empty = listOf(stems.drums, stems.bass, stems.vocals, stems.other).all { it.isEmpty() }
            val msg = if (empty) {
                "an empty stems block still makes the recipe need stem separation; remove it if you do not use stem lanes"
            } else {
                "stem lanes need stem separation, which costs time before the transition can play; without an ML separator " +
                    "Muisc uses pseudo-stems, which leak (a stem handover then sounds more like a staggered EQ mix)"
            }
            out.warning("stems", "$name.stems", msg)
        }

        // Timing fields that do nothing in this tempo mode.
        if (r.timing.tempo != RecipeTempo.NONE && r.timing.bEntersAtBar.literal != 0.0) {
            out.warning("unused-timing", "timing.bEntersAtBar", "bEntersAtBar is only used in \"none\" tempo; in \"${tempoName(r.timing.tempo)}\" B always enters at bar 0 of the overlap")
        }

        // match / glide render whole bars: a timing field that uses a continuous knob can be fractional.
        if (r.timing.tempo != RecipeTempo.NONE) {
            val continuous = r.vars.filter { (n, v) -> IDENTIFIER.matches(n) && n !in RecipeResolver.RESERVED && !v.integer && v.min < v.max }.keys
            for ((path, e) in listOf("timing.lengthBars" to r.timing.lengthBars, "timing.settleBars" to r.timing.settleBars, "timing.holdBars" to r.timing.holdBars)) {
                val knobs = e.names().filter { it in continuous }
                if (knobs.isEmpty()) continue
                val field = path.removePrefix("timing.")
                out.warning(
                    "whole-bars", path,
                    "${knobs.joinToString(" and ") { "'$it'" }} can take any value in its range, so $field can be fractional; in \"${tempoName(r.timing.tempo)}\" " +
                        "the master grid moves in whole bars and rounds it, and lanes then see the rounded bars and total. Make ${if (knobs.size == 1) "it a" else "them"} " +
                        "whole-number knob${if (knobs.size == 1) "" else "s"} (\"integer\": true)",
                )
            }
        }
        return resolvable
    }

    // ---- expressions: every unknown name and syntax error, not just the first ---------------------------------

    private class ExprAt(val path: String, val expr: Expr, val level: Int)

    /** Every expression with the stage of resolution it is evaluated at (which decides the names it may use). */
    private fun allExpressions(r: TransitionRecipe): List<ExprAt> = buildList {
        val t = r.timing
        add(ExprAt("timing.lengthBars", t.lengthBars, 0))
        add(ExprAt("timing.settleBars", t.settleBars, 1))
        add(ExprAt("timing.holdBars", t.holdBars, 1))
        add(ExprAt("timing.bEntersAtBar", t.bEntersAtBar, 2))
        add(ExprAt("timing.bEntryOffsetBars", t.bEntryOffsetBars, 3))
        add(ExprAt("timing.lowHz", t.lowHz, 3))
        add(ExprAt("timing.highHz", t.highHz, 3))
        for ((name, d) in listOf("a" to r.a, "b" to r.b)) {
            for (lane in authoredLanes(d, name)) lane.points.forEachIndexed { i, p ->
                add(ExprAt("${lane.path}[$i].at", p.at, 3))
                add(ExprAt("${lane.path}[$i].v", p.v, 3))
            }
            add(ExprAt("$name.resonance", d.resonance, 3))
            d.echo?.let { e ->
                add(ExprAt("$name.echo.beats", e.beats, 3)); add(ExprAt("$name.echo.feedback", e.feedback, 3))
                add(ExprAt("$name.echo.dampHz", e.dampHz, 3)); add(ExprAt("$name.echo.returnLevel", e.returnLevel, 3))
            }
            d.reverb?.let { v ->
                add(ExprAt("$name.reverb.decaySec", v.decaySec, 3)); add(ExprAt("$name.reverb.dampHz", v.dampHz, 3))
                add(ExprAt("$name.reverb.returnLevel", v.returnLevel, 3))
            }
        }
    }

    private fun expressionChecks(r: TransitionRecipe, out: Findings): Boolean {
        val vars = r.vars.keys.filter { IDENTIFIER.matches(it) && it !in RecipeResolver.RESERVED }.toSet()
        val stageNames = listOf(
            setOf("beat", "off"),
            setOf("beat", "off", "bars"),
            setOf("beat", "off", "bars", "settle", "hold"),
            setOf("beat", "off", "bars", "settle", "hold", "total"),
        )
        var ok = true
        for (e in allExpressions(r)) {
            val allowed = vars + stageNames[e.level]
            if (e.expr.source.isBlank()) { out.error("expr", e.path, "this is empty; write a number or an expression"); ok = false; continue }
            var namesOk = true
            for (n in e.expr.names()) {
                if (n in allowed) continue
                namesOk = false
                val msg = when {
                    n in BUILT_INS -> when (n) {
                        "bars" -> "'bars' is the overlap length, which this field defines or comes before; use a number or a knob here"
                        "settle", "hold" -> "'$n' is not known yet here; it can be used in bEntersAtBar, lanes and effect settings"
                        else -> "'total' is not known yet here; it can be used in lanes, effect settings, bEntryOffsetBars and the EQ crossovers"
                    }
                    else -> {
                        val hint = RecipeCodec.suggest(n, allowed)?.let { " (did you mean '$it'?)" } ?: ""
                        "'$n' is not a knob or a built-in name$hint. Knobs: ${vars.sorted().joinToString(", ").ifEmpty { "none" }}; " +
                            "built-in names here: ${stageNames[e.level].sorted().joinToString(", ")}"
                    }
                }
                out.error("expr-name:$n", e.path, msg)
            }
            if (!namesOk) { ok = false; continue }
            try {
                e.expr.eval { if (it in allowed) PROBE else null }
            } catch (x: ExprException) {
                if (!(x.message ?: "").startsWith("division by zero")) {
                    out.error("expr", e.path, "cannot read the expression: ${x.message}")
                    ok = false
                }
            }
        }
        return ok
    }

    // ---- the knob settings to check -----------------------------------------------------------------------------

    private fun settings(r: TransitionRecipe): List<Setting> {
        val vars = r.vars.filter { (n, v) -> IDENTIFIER.matches(n) && n !in RecipeResolver.RESERVED && v.min <= v.max }
        val out = ArrayList<Setting>()
        out += Setting(null, emptyMap())
        fun value(v: RecipeVar, x: Double) = if (v.integer) round(x.coerceIn(v.min, v.max)) else x
        for ((name, v) in vars) {
            val def = value(v, v.default)
            for (x in listOf(v.min, v.max)) {
                val at = value(v, x)
                if (at != def) out += Setting("$name = ${fmt(at)}", mapOf(name to at))
            }
        }
        if (vars.size >= 2) {
            val mins = vars.mapValues { (_, v) -> value(v, v.min) }
            val maxs = vars.mapValues { (_, v) -> value(v, v.max) }
            out += Setting("every knob at its minimum (${mins.entries.joinToString(", ") { "${it.key} = ${fmt(it.value)}" }})", mins)
            out += Setting("every knob at its maximum (${maxs.entries.joinToString(", ") { "${it.key} = ${fmt(it.value)}" }})", maxs)
        }
        return out
    }

    // ---- checks at one setting -----------------------------------------------------------------------------------

    private fun settingChecks(r: TransitionRecipe, s: Setting, out: Findings) {
        val res = try {
            RecipeResolver.resolve(r, Params(s.values.mapValues { Expr.formatNumber(it.value) }), beatsPerBar)
        } catch (e: RecipeException) {
            val msg = (e.message ?: "").removePrefix("${e.path}: ")
            out.error("resolve", e.path, msg, s)
            return
        }
        val total = res.totalBars
        val scope = HashMap<String, Double>(res.vars).apply {
            put("beat", 1.0 / beatsPerBar); put("off", RecipeResolver.OFF_DB)
            put("bars", res.lengthBars); put("settle", res.settleBars); put("hold", res.holdBars); put("total", total)
        }
        fun ev(e: Expr): Double = e.eval { scope[it] }

        // Point values and positions, with the index the author wrote.
        for ((name, deck) in listOf("a" to r.a, "b" to r.b)) for (lane in authoredLanes(deck, name)) {
            val bars = ArrayList<Double>()
            lane.points.forEachIndexed { i, p ->
                val at = ev(p.at)
                val v = ev(p.v)
                bars += at
                if (v < lane.kind.min - EPS_VALUE || v > lane.kind.max + EPS_VALUE) {
                    out.error("range", "${lane.path}[$i].v", "${fmt(v)}${unitSuffix(lane.kind)} is outside what a ${lane.label} lane accepts (${rangeText(lane.kind)})", s)
                }
                if (at < -EPS_BAR || at > total + EPS_BAR) {
                    out.error("position", "${lane.path}[$i].at", "the point is at bar ${fmt(at)}, outside the transition (bar 0 to total = ${fmt(total)})", s)
                }
            }
            for (i in 1 until bars.size) if (bars[i] < bars[i - 1] - EPS_BAR) {
                out.warning(
                    "order", lane.path,
                    "the points are not in time order (point $i at bar ${fmt(bars[i])} comes after point ${i - 1} at bar ${fmt(bars[i - 1])}); " +
                        "they are sorted by position, so a curve may end up on a different stretch than written",
                    s,
                )
                break
            }
        }

        // The boundary rule.
        for ((label, lane) in res.a.lanes()) {
            val path = "a.$label"
            val at0 = lane.valueAt(0.0)
            if (!isNeutral(lane.kind, at0)) {
                out.error(
                    "boundary", path,
                    "deck A must start exactly as the listener has been hearing it, but at bar 0 its $label is ${fmt(at0)}${unitSuffix(lane.kind)}; " +
                        "it has to be ${neutralText(lane.kind)} there. Start this lane at ${fmt(lane.kind.neutral)} and make the change after bar 0",
                    s,
                )
            } else if (!isNeutral(lane.kind, lane.valueAt(EPS_EDGE))) {
                out.error(
                    "boundary", path,
                    "deck A's $label jumps away from ${neutralText(lane.kind)} right at bar 0, which would be heard as a click at the start; " +
                        "begin the change a little later (for example at \"beat\")",
                    s,
                )
            }
        }
        for ((label, lane) in res.b.lanes()) {
            val path = "b.$label"
            val atEnd = lane.valueAt(total)
            if (!isNeutral(lane.kind, atEnd)) {
                out.error(
                    "boundary", path,
                    "deck B must end exactly as its song continues, but at the end of the transition (bar ${fmt(total)}, \"total\") its $label is " +
                        "${fmt(atEnd)}${unitSuffix(lane.kind)}; it has to be back to ${neutralText(lane.kind)} by then",
                    s,
                )
            } else if (!isNeutral(lane.kind, lane.valueAt(total - EPS_EDGE))) {
                out.error(
                    "boundary", path,
                    "deck B's $label jumps back to ${neutralText(lane.kind)} right at the end (bar ${fmt(total)}), which would be heard as a click at the seam; " +
                        "finish the change a little earlier (for example at \"total - beat\")",
                    s,
                )
            }
        }

        // Deck A is only rendered during the overlap.
        val aEnd = res.a.level.valueAt(res.lengthBars)
        if (aEnd > EPS_VALUE) {
            out.warning(
                "a-level-end", "a.level",
                "deck A is still at level ${fmt(aEnd)} when the overlap ends at bar ${fmt(res.lengthBars)} (\"bars\"). A is only played during the overlap, " +
                    "so the renderer will stop it with a short declick fade there; bring a.level down to 0 by \"bars\" to choose how it ends",
                s,
            )
        }

        // Effect tails must be released in time to die away.
        for ((name, deck) in listOf("a" to res.a, "b" to res.b)) {
            val tails = buildList {
                deck.echo?.let { add(Triple("echo.send", it.send, 0.0)) }
                deck.reverb?.let { add(Triple("reverb.send", it.send, 0.0)); add(Triple("reverb.freeze", it.freeze, 0.5)) }
            }
            for ((label, lane, threshold) in tails) {
                if (name == "b" && !isNeutral(lane.kind, lane.valueAt(total))) continue // already a boundary error
                val offAt = releaseBar(lane, threshold)
                if (offAt > total - 1.0 + EPS_BAR) {
                    val what = if (label == "reverb.freeze") "the reverb freeze is still on" else "the $label is still above 0"
                    val whenText = if (offAt.isInfinite()) "at the end of the transition" else "until bar ${fmt(offAt)}"
                    out.warning(
                        "tail", "$name.$label",
                        "$what $whenText, less than one bar before the end (bar ${fmt(total)}); the tail cannot ring out and the renderer " +
                            "will cut it with a fade. End it by \"total - 1\" or earlier",
                        s,
                    )
                }
            }
        }

        // Scalars.
        for ((name, deck) in listOf("a" to res.a, "b" to res.b)) {
            if (!(deck.resonance in MIN_RESONANCE..MAX_RESONANCE)) {
                out.error("range", "$name.resonance", "filter resonance is ${fmt(deck.resonance)}, but it must be between ${fmt(MIN_RESONANCE)} (gentle) and ${fmt(MAX_RESONANCE)} (very sharp)", s)
            }
            deck.echo?.let { e ->
                if (!(e.feedback in 0.0..MAX_FEEDBACK)) out.error("range", "$name.echo.feedback", "echo feedback is ${fmt(e.feedback)}, but it must be between 0 and ${fmt(MAX_FEEDBACK)} (at 1 the echo would never die away)", s)
                if (!(e.beats > 0.0)) out.error("range", "$name.echo.beats", "the echo time is ${fmt(e.beats)} beats; it must be more than 0 (0.75 is a dotted eighth)", s)
                if (!(e.dampHz in 20.0..20000.0)) out.error("range", "$name.echo.dampHz", "dampHz is ${fmt(e.dampHz)} Hz; it must be between 20 and 20000", s)
                if (!(e.returnLevel in 0.0..2.0)) out.error("range", "$name.echo.returnLevel", "returnLevel is ${fmt(e.returnLevel)}; it must be between 0 and 2", s)
            }
            deck.reverb?.let { v ->
                if (!(v.decaySec > 0.0)) out.error("range", "$name.reverb.decaySec", "the reverb decay is ${fmt(v.decaySec)} s; it must be more than 0", s)
                if (!(v.dampHz in 20.0..20000.0)) out.error("range", "$name.reverb.dampHz", "dampHz is ${fmt(v.dampHz)} Hz; it must be between 20 and 20000", s)
                if (!(v.returnLevel in 0.0..2.0)) out.error("range", "$name.reverb.returnLevel", "returnLevel is ${fmt(v.returnLevel)}; it must be between 0 and 2", s)
            }
        }

        // match / glide: the master grid renders whole bars (and a hold of at least one), and lanes are placed with those.
        if (res.tempo != RecipeTempo.NONE) {
            val bars = res.lengthBars.roundToInt().coerceAtLeast(1)
            val settle = RecipeGeometry.wholeSettle(res)
            val hold = RecipeGeometry.wholeHold(res)
            val renderedTotal = bars + settle + hold
            for ((path, written, rendered) in listOf(
                Triple("timing.lengthBars", res.lengthBars, bars),
                Triple("timing.settleBars", res.settleBars, settle),
                Triple("timing.holdBars", res.holdBars, hold),
            )) {
                if (written == rendered.toDouble()) continue
                val why = if (path == "timing.holdBars" && written < 0.5) "at least one bar is held at B's own tempo before the seam" else "the master grid moves in whole bars"
                out.warning(
                    "whole-bars", path,
                    "${path.removePrefix("timing.")} is ${fmt(written)}, which is rendered as $rendered bar${if (rendered == 1) "" else "s"}: in \"${tempoName(res.tempo)}\" $why. " +
                        "Lanes are placed with the rendered values (total = $renderedTotal rather than ${fmt(total)}); write a whole number to place them exactly",
                    s,
                )
            }
        }

        // `none` tempo: a gap between A's overlap and B's entry.
        if (res.tempo == RecipeTempo.NONE && res.bEntersAtBar > res.lengthBars + EPS_BAR) {
            out.warning(
                "gap", "timing.bEntersAtBar",
                "B enters at bar ${fmt(res.bEntersAtBar)}, but A is only played until bar ${fmt(res.lengthBars)} (\"bars\"), so the music stops in between " +
                    "unless an echo or reverb tail fills the gap",
                s,
            )
        }
    }

    /** The bar after which [lane] stays below [threshold] (strictly: `<= threshold` for 0, `< threshold` otherwise). */
    private fun releaseBar(lane: ResolvedLane, threshold: Double): Double {
        fun active(v: Double) = if (threshold == 0.0) v > 0.0 else v >= threshold
        val pts = lane.points
        if (pts.isEmpty()) return Double.NEGATIVE_INFINITY
        if (active(pts.last().value)) return Double.POSITIVE_INFINITY
        val k = pts.indexOfLast { active(it.value) }
        return if (k < 0) Double.NEGATIVE_INFINITY else pts[k + 1].bar
    }

    companion object {
        /** Modifier ids of the shipped engine. */
        val DEFAULT_MODIFIERS: Set<String> = setOf(TempoGlideModifier.ID, TextureCarryModifier.ID)

        /** What a recipe id may look like. */
        val ID_PATTERN = Regex("[a-z0-9-]+")

        const val MAX_FEEDBACK = 0.95
        const val MIN_RESONANCE = 0.5
        const val MAX_RESONANCE = 6.0

        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val BUILT_INS = setOf("bars", "settle", "hold", "total", "beat", "off")
        private const val PROBE = 1.6180339887
        private const val EPS_BAR = 1e-9
        private const val EPS_EDGE = 1e-9
        private const val EPS_VALUE = 1e-9

        /** Turns any text into a valid id (`My Mix!` → `my-mix`). */
        fun slug(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

        internal fun fmt(v: Double): String = when {
            v.isNaN() -> "NaN"
            v.isInfinite() -> if (v > 0) "infinity" else "-infinity"
            abs(v - round(v)) < 1e-9 && abs(v) < 1e15 -> round(v).toLong().toString()
            else -> String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
        }

        private fun tempoName(t: RecipeTempo) = when (t) {
            RecipeTempo.MATCH -> "match"
            RecipeTempo.GLIDE -> "glide"
            RecipeTempo.NONE -> "none"
        }

        private fun isNeutral(kind: LaneKind, v: Double): Boolean = abs(v - kind.neutral) <= 1e-6 * (kind.max - kind.min)

        private fun unitSuffix(kind: LaneKind): String = when (kind) {
            LaneKind.DB -> " dB"
            LaneKind.HPF, LaneKind.LPF -> " Hz"
            else -> ""
        }

        private fun rangeText(kind: LaneKind): String = when (kind) {
            LaneKind.LEVEL -> "0 to 2, where 1 is full level"
            LaneKind.DB -> "-120 (\"off\") to +12 dB"
            LaneKind.HPF, LaneKind.LPF -> "20 to 20000 Hz"
            LaneKind.SEND -> "0 to 1"
            LaneKind.FREEZE -> "0 (off) or 1 (frozen)"
        }

        internal fun neutralText(kind: LaneKind): String = when (kind) {
            LaneKind.LEVEL -> "1 (full level)"
            LaneKind.DB -> "0 dB (untouched)"
            LaneKind.HPF -> "20 Hz (high-pass fully open)"
            LaneKind.LPF -> "20000 Hz (low-pass fully open)"
            LaneKind.SEND -> "0 (nothing sent)"
            LaneKind.FREEZE -> "0 (not frozen)"
        }
    }
}

/** A lane as written in the recipe (points unevaluated, in the author's order), with its path and kind. */
internal class AuthoredLane(val path: String, val label: String, val kind: LaneKind, val points: List<RecipePoint>)

/** Every lane of [d] in the order and with the labels of [ResolvedDeck.lanes]. */
internal fun authoredLanes(d: DeckRecipe, name: String): List<AuthoredLane> = buildList {
    add(AuthoredLane("$name.level", "level", LaneKind.LEVEL, d.level))
    add(AuthoredLane("$name.low", "low", LaneKind.DB, d.low))
    add(AuthoredLane("$name.mid", "mid", LaneKind.DB, d.mid))
    add(AuthoredLane("$name.high", "high", LaneKind.DB, d.high))
    add(AuthoredLane("$name.hpf", "hpf", LaneKind.HPF, d.hpf))
    add(AuthoredLane("$name.lpf", "lpf", LaneKind.LPF, d.lpf))
    d.echo?.let { add(AuthoredLane("$name.echo.send", "echo.send", LaneKind.SEND, it.send)) }
    d.reverb?.let {
        add(AuthoredLane("$name.reverb.send", "reverb.send", LaneKind.SEND, it.send))
        add(AuthoredLane("$name.reverb.freeze", "reverb.freeze", LaneKind.FREEZE, it.freeze))
    }
    d.stems?.let {
        add(AuthoredLane("$name.stems.drums", "stems.drums", LaneKind.DB, it.drums))
        add(AuthoredLane("$name.stems.bass", "stems.bass", LaneKind.DB, it.bass))
        add(AuthoredLane("$name.stems.vocals", "stems.vocals", LaneKind.DB, it.vocals))
        add(AuthoredLane("$name.stems.other", "stems.other", LaneKind.DB, it.other))
    }
}
