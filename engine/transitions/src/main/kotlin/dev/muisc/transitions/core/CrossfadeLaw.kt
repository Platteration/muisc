package dev.muisc.transitions.core

import dev.muisc.dsp.gain.Curves
import dev.muisc.dsp.gain.FadeShape
import dev.muisc.transitions.FadeLaw

/**
 * Crossfade gain laws for strategies, on top of `dsp` [Curves].
 *
 * Rule: [FadeLaw.EQUAL_POWER] between two different tracks (uncorrelated at sample level, beat-matched or not: the
 * loudness stays constant through the fade); [FadeLaw.LINEAR] only for coherent material (a signal into a processed
 * copy of itself, loop repeats, seams between identical samples — equal-power would add a +3 dB bump there);
 * [FadeLaw.S_CURVE] for gentle gain moves; [FadeLaw.EXP] for "late" fades (slow start, fast finish).
 *
 * For every law `fadeOut(x) = fadeIn(1 - x)`, so a fade-out / fade-in pair is symmetric in time.
 */
object CrossfadeLaw {
    /** The `dsp` shape behind a law. */
    fun shape(law: FadeLaw): FadeShape = when (law) {
        FadeLaw.LINEAR -> FadeShape.LINEAR
        FadeLaw.EQUAL_POWER -> FadeShape.EQUAL_POWER
        FadeLaw.S_CURVE -> FadeShape.S_CURVE
        FadeLaw.EXP -> FadeShape.EXPONENTIAL
    }

    /** Fade-in gain (0 at x = 0, 1 at x = 1; x clamped). */
    fun fadeIn(x: Double, law: FadeLaw): Float = Curves.fadeIn(shape(law), x).toFloat()

    /** Fade-out gain (1 at x = 0, 0 at x = 1) = `fadeIn(1 - x)`. */
    fun fadeOut(x: Double, law: FadeLaw): Float = Curves.fadeOut(shape(law), x).toFloat()

    /**
     * Gains at crossfade position `x in [0, 1]` (clamped): `first` is the outgoing deck's gain (1 → 0), `second` the
     * incoming deck's (0 → 1). EQUAL_POWER: `(cos, sin)(pi/2 x)`, squares sum to 1; LINEAR: `(1 - x, x)`, sum to 1.
     */
    fun gains(x: Double, law: FadeLaw): Pair<Float, Float> = Pair(fadeOut(x, law), fadeIn(x, law))

    /** Parses a law name as written in params (case-insensitive), or null when unknown. */
    fun parse(name: String?): FadeLaw? = name?.let { n -> FadeLaw.entries.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }
}
