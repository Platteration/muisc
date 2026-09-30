package dev.muisc.transitions.recipe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** What a lane controls; decides its neutral value, its valid range and how it interpolates. */
enum class LaneKind(val neutral: Double, val min: Double, val max: Double, val logScale: Boolean, val unit: String) {
    /** Linear gain. */
    LEVEL(1.0, 0.0, 2.0, false, "×"),
    /** EQ band or stem level in dB. */
    DB(0.0, RecipeResolver.OFF_DB, 12.0, false, "dB"),
    /** High-pass cutoff (neutral = fully open at the bottom). */
    HPF(20.0, 20.0, 20000.0, true, "Hz"),
    /** Low-pass cutoff (neutral = fully open at the top). */
    LPF(20000.0, 20.0, 20000.0, true, "Hz"),
    /** Effect send amount. */
    SEND(0.0, 0.0, 1.0, false, ""),
    /** Reverb freeze switch. */
    FREEZE(0.0, 0.0, 1.0, false, ""),
}

data class ResolvedPoint(val bar: Double, val value: Double, val curve: RecipeCurve)

/**
 * A lane with every expression evaluated. Holds its first value before the first point and its last value after the
 * last point; between two points it follows the earlier point's curve. Hz lanes interpolate on a log scale.
 */
class ResolvedLane(val kind: LaneKind, points: List<ResolvedPoint>) {
    /** Points sorted by bar (stable for equal bars, so a vertical step is two points at the same bar). */
    val points: List<ResolvedPoint> = points.sortedBy { it.bar }

    val isEmpty: Boolean get() = points.isEmpty()

    /** True when the lane is neutral everywhere (empty, or every point at the neutral value). */
    val isNeutral: Boolean get() = points.all { it.value == kind.neutral }

    fun valueAt(bar: Double): Double {
        if (points.isEmpty()) return kind.neutral
        if (bar <= points.first().bar) return points.first().value
        if (bar >= points.last().bar) return points.last().value
        // Last point at or before `bar` (for a vertical step at `bar`, the later of the pair wins).
        var i = 0
        while (i + 1 < points.size && points[i + 1].bar <= bar) i++
        val p0 = points[i]
        val p1 = points[i + 1]
        val span = p1.bar - p0.bar
        if (span <= 0.0) return p1.value
        val t = ((bar - p0.bar) / span).coerceIn(0.0, 1.0)
        val rising = p1.value > p0.value
        val f = when (p0.curve) {
            RecipeCurve.LINEAR -> t
            RecipeCurve.S_CURVE -> t * t * (3.0 - 2.0 * t)
            RecipeCurve.EQUAL_POWER -> if (rising) sin(t * PI / 2.0) else 1.0 - cos(t * PI / 2.0)
            RecipeCurve.EXP -> t * t
            RecipeCurve.STEP -> 0.0
        }
        return if (kind.logScale && p0.value > 0.0 && p1.value > 0.0) {
            exp(ln(p0.value) + (ln(p1.value) - ln(p0.value)) * f)
        } else {
            p0.value + (p1.value - p0.value) * f
        }
    }

    /** The bars at which the lane changes: its first and last point, or null when empty. */
    val activeRange: ClosedFloatingPointRange<Double>? get() = if (points.isEmpty()) null else points.first().bar..points.last().bar

    companion object {
        fun neutral(kind: LaneKind) = ResolvedLane(kind, emptyList())
    }
}

data class ResolvedEcho(val send: ResolvedLane, val beats: Double, val feedback: Double, val dampHz: Double, val returnLevel: Double)
data class ResolvedReverb(val send: ResolvedLane, val freeze: ResolvedLane, val decaySec: Double, val dampHz: Double, val returnLevel: Double)
data class ResolvedStems(val drums: ResolvedLane, val bass: ResolvedLane, val vocals: ResolvedLane, val other: ResolvedLane) {
    val all: List<ResolvedLane> get() = listOf(drums, bass, vocals, other)
}

/** One deck with every expression evaluated. */
data class ResolvedDeck(
    val level: ResolvedLane,
    val low: ResolvedLane,
    val mid: ResolvedLane,
    val high: ResolvedLane,
    val hpf: ResolvedLane,
    val lpf: ResolvedLane,
    val resonance: Double,
    val echo: ResolvedEcho?,
    val reverb: ResolvedReverb?,
    val stems: ResolvedStems?,
) {
    val usesEq: Boolean get() = !(low.isNeutral && mid.isNeutral && high.isNeutral)
    val usesFilters: Boolean get() = !(hpf.isNeutral && lpf.isNeutral)
    val usesEcho: Boolean get() = echo != null && !echo.send.isNeutral
    val usesReverb: Boolean get() = reverb != null && !(reverb.send.isNeutral && reverb.freeze.isNeutral)
    val usesStems: Boolean get() = stems != null && stems.all.any { !it.isNeutral }

    /** Every lane with a label, for validation messages and the Lab's lane plot. */
    fun lanes(): List<Pair<String, ResolvedLane>> = buildList {
        add("level" to level); add("low" to low); add("mid" to mid); add("high" to high); add("hpf" to hpf); add("lpf" to lpf)
        echo?.let { add("echo.send" to it.send) }
        reverb?.let { add("reverb.send" to it.send); add("reverb.freeze" to it.freeze) }
        stems?.let { add("stems.drums" to it.drums); add("stems.bass" to it.bass); add("stems.vocals" to it.vocals); add("stems.other" to it.other) }
    }
}

/**
 * A recipe with every variable bound and every expression evaluated — what [RecipeStrategy] renders from.
 * Produced by [RecipeResolver.resolve]; pure data.
 */
data class ResolvedRecipe(
    val recipe: TransitionRecipe,
    /** Final variable values (defaults overridden by params, clamped, integers rounded). */
    val vars: Map<String, Double>,
    val beatsPerBar: Int,
    /** Overlap length in bars (`bars`). */
    val lengthBars: Double,
    val settleBars: Double,
    val holdBars: Double,
    /** Timeline length in bars (`total`). */
    val totalBars: Double,
    val tempo: RecipeTempo,
    val align: RecipeAlign,
    val bEntryOffsetBars: Int,
    /** `none` tempo: timeline bar where B's entry downbeat lands. */
    val bEntersAtBar: Double,
    val lowHz: Double,
    val highHz: Double,
    val a: ResolvedDeck,
    val b: ResolvedDeck,
) {
    val needsStems: Boolean get() = a.usesStems || b.usesStems
    val beatMatched: Boolean get() = tempo != RecipeTempo.NONE
}
