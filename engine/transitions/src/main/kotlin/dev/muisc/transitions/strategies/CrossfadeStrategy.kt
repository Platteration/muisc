package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.Marker
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.core.CrossfadeLaw
import dev.muisc.transitions.core.Lane
import dev.muisc.transitions.core.RenderReports
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.sdk.ParamSet
import kotlin.math.abs

/**
 * `crossfade` — the universal fallback: A's last [P.fadeSec] seconds fade out while B fades in from its mix-in cue
 * (or first downbeat / trim start), equal-power by default. No stretching; when both grids are confident and
 * [P.alignToBeat] is on, the fade starts on the A downbeat nearest to `trimEnd - fadeSec` and B starts on a
 * downbeat, so the two beat grids at least share a downbeat at the moment B enters.
 *
 * Segment layout (the recommended pattern, see [Splice]): `[0, G)` dry A pre-roll, `[G, G + fade)` the crossfade,
 * `[G + fade, 2G + fade)` dry B post-roll, with `G = Splice.GUARD_FRAMES`; `aExitFrame` is placed `G` frames before
 * the fade begins and `bEntryFrame` `G` frames after it ends, so the splice contract holds bit-exactly.
 * `expectedOutputFrames = fade + 2G`. Deck gains are already applied to the input windows ([dev.muisc.transitions.core.DeckGain]);
 * the strategy does no loudness matching of its own.
 *
 * Never blocked: score floor 0.05, raised to 0.3 when the pair is tempo- or key-incompatible (so it wins over nothing
 * that fits, but is always there). Fails on vocals over vocals and clashing beats — hence the floor.
 */
class CrossfadeStrategy : TransitionStrategy {
    /** Parameters. */
    object P : ParamSet(ID) {
        val fadeSec = double("fadeSec", "Fade length", 6.0, 1.0, 12.0, "s", "Length of the crossfade")
        val law = choice("law", "Fade law", FadeLaw.EQUAL_POWER, listOf(FadeLaw.EQUAL_POWER, FadeLaw.LINEAR, FadeLaw.S_CURVE), "Gain law of both fades (EQUAL_POWER between two different tracks)")
        val alignToBeat = bool("alignToBeat", "Align to beat", true, "When both grids are confident, start the fade on an A downbeat and B on a downbeat (no stretching)")
        val bStartAtMixIn = bool("bStartAtMixIn", "Start B at mix-in cue", true, "Start B at its mixInBeat cue when known, else at its first downbeat / trim start")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Crossfade"
    override val description: String get() = "Equal-power crossfade of A's tail into B's head; the floor every pair can fall back to."
    override val params: List<ParamSpec> get() = P.specs

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val reasons = ArrayList<String>()
        reasons += "universal fallback (floor ${FLOOR_SCORE})"
        val tempoIncompatible = features.stretchPercent > prefs.maxStretchPercent || !features.beatMatchable
        val keyIncompatible = features.camelotDistance > 1 &&
            (features.camelotDistanceAfterShift > 1 || abs(features.bestPitchShiftSemitones) > prefs.maxPitchShiftSemitones)
        var score = FLOOR_SCORE
        if (tempoIncompatible) { score = INCOMPATIBLE_SCORE; reasons += "tempos not beat-matchable (${"%.1f".format(features.stretchPercent)} % stretch, grid confidence ${"%.2f".format(features.gridConfidenceA)}/${"%.2f".format(features.gridConfidenceB)})" }
        if (keyIncompatible) { score = INCOMPATIBLE_SCORE; reasons += "keys incompatible (Camelot distance ${features.camelotDistance})" }
        return Applicability.of(score, *reasons.toTypedArray())
    }

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES.toLong()
        val law = CrossfadeLaw.parse(p.choice(P.law)) ?: FadeLaw.EQUAL_POWER
        val notes = ArrayList<String>()
        val confident = !a.grid.isEmpty && !b.grid.isEmpty && a.grid.confidence >= CONFIDENT_GRID && b.grid.confidence >= CONFIDENT_GRID
        val align = p.bool(P.alignToBeat) && confident
        if (p.bool(P.alignToBeat) && !confident) notes += "alignToBeat requested but a grid is missing or below confidence $CONFIDENT_GRID: unaligned"

        // Analysis frames are engine-rate frames; rescale defensively if an analysis ran at another rate.
        val scaleA = sr / a.sampleRate.toDouble()
        val scaleB = sr / b.sampleRate.toDouble()
        fun fa(f: Long) = Math.round(f * scaleA)
        fun fb(f: Long) = Math.round(f * scaleB)
        val aTrimStart = fa(a.trimStartFrame)
        val aTrimEnd = fa(a.trimEndFrame)

        val requested = Math.round(p.double(P.fadeSec) * sr)
        val fadeFrames = minOf(requested, (aTrimEnd - aTrimStart - g)).coerceAtLeast(minOf(requested, MIN_FADE_FRAMES))
        if (fadeFrames < requested) notes += "fade shortened to ${fadeFrames}/${requested} frames: A is too short"

        // --- A: fade start, snapped to a downbeat when aligning ---
        var fadeStartA = aTrimEnd - fadeFrames
        var aBeat: Int? = null
        if (align) {
            val beat = a.grid.beatAtFrame(Math.round(fadeStartA / scaleA))
            var db = a.grid.nearestDownbeat(beat)
            var f = fa(a.grid.frameOfBeat(db.toDouble()))
            if (f + fadeFrames > aTrimEnd || f - g < aTrimStart) { db = a.grid.previousDownbeat(beat); f = fa(a.grid.frameOfBeat(db.toDouble())) }
            if (f - g >= aTrimStart && f + fadeFrames <= aTrimEnd) { fadeStartA = f; aBeat = db }
            else notes += "no A downbeat fits the fade; unaligned"
        }
        fadeStartA = fadeStartA.coerceAtLeast(aTrimStart + g).coerceAtLeast(g)

        // --- B: entry point ---
        val bTrimStart = fb(b.trimStartFrame)
        var bStart = bTrimStart
        var bBeat: Int? = null
        val mixIn = b.cues.mixInBeat
        if (p.bool(P.bStartAtMixIn) && mixIn >= 0 && !b.grid.isEmpty) {
            bStart = fb(b.grid.frameOfBeat(mixIn.toDouble())); bBeat = mixIn
            notes += "B starts at its mixInBeat $mixIn"
        } else if (align) {
            val fd = if (b.cues.firstDownbeat >= 0) b.cues.firstDownbeat else b.grid.nextDownbeat(b.grid.beatAtFrame(Math.round(bTrimStart / scaleB)))
            bStart = fb(b.grid.frameOfBeat(fd.toDouble())); bBeat = fd
            notes += "B starts at its downbeat $fd"
        } else {
            notes += "B starts at its trim start"
        }
        bStart = bStart.coerceAtLeast(0L)

        val aExit = fadeStartA - g
        val aWindow = FrameRange(aExit, fadeStartA + fadeFrames)
        val bEntry = bStart + fadeFrames + g
        val bWindow = FrameRange(bStart, bEntry)
        val expected = (fadeFrames + 2 * g).toInt()
        val lanes = lanes(fadeFrames.toInt(), g.toInt(), law)
        notes.add(0, "${"%.2f".format(fadeFrames / sr.toDouble())} s $law crossfade, ${Splice.GUARD_FRAMES}-frame dry pre/post-roll")
        if (aBeat != null) notes += "fade starts on A downbeat $aBeat" + (if (bBeat != null) " with B beat $bBeat on it" else "")
        return TransitionPlan(
            strategyId = ID, params = p, aExitFrame = aExit, bEntryFrame = bEntry, aWindow = aWindow, bWindow = bWindow,
            expectedOutputFrames = expected, lanes = listOf(lanes.first.toAutomationLane(sr), lanes.second.toAutomationLane(sr)), notes = notes,
        )
    }

    override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val plan = input.plan
        val p = P.resolve(plan.params)
        val law = CrossfadeLaw.parse(p.choice(P.law)) ?: FadeLaw.EQUAL_POWER
        // Geometry comes from the plan itself so the render is exactly what the plan promised:
        // aWindow = pre-roll + fade, bWindow = fade + post-roll, expected = pre-roll + fade + post-roll.
        val g = plan.expectedOutputFrames - plan.aWindow.length
        val fade = plan.aWindow.length - g
        require(g >= 0 && fade > 0) { "inconsistent crossfade plan: aWindow ${plan.aWindow.length} frames, expected ${plan.expectedOutputFrames}" }
        require(plan.aExitOffset == 0) { "crossfade plans start their segment at aWindow.start (aExitOffset ${plan.aExitOffset})" }
        val post = plan.bEntryOffset - fade
        require(post >= 0) { "bEntryOffset (${plan.bEntryOffset}) must be at least the fade length ($fade)" }
        val outFrames = g + fade + post
        val (laneA, laneB) = lanes(fade, g, law)
        ctx.progress(0.1)
        val audio = Splice.mix(input.aAudio, laneA, input.bAudio, laneB, bOffsetFrames = g, outFrames = outFrames)
        ctx.progress(0.6)
        val fin = RenderReports.finalize(audio, ctx)
        ctx.progress(0.8)
        val markers = listOf(Marker(g.toLong(), MARKER_B_ENTERS), Marker((g + fade).toLong(), MARKER_A_GONE))
        val metrics = mapOf(
            "fadeFrames" to fade.toDouble(), "guardFrames" to g.toDouble(), "limited" to (if (fin.limited) 1.0 else 0.0),
            "gainReductionDb" to fin.gainReductionDb, "truePeakBeforeDbtp" to fin.truePeakBeforeDbtp,
        )
        val report = RenderReports.build(audio, (System.nanoTime() - t0) / 1_000_000, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        return RenderedTransition(plan, audio, markers, report)
    }

    /** The two gain lanes in output frames: A unity through the pre-roll then out, B in then unity. */
    private fun lanes(fade: Int, g: Int, law: FadeLaw): Pair<Lane, Lane> {
        val a = Lane(LANE_A).add(0L, 1.0).add(g.toLong(), 1.0, law).add((g + fade).toLong(), 0.0)
        val b = Lane(LANE_B).add(g.toLong(), 0.0, law).add((g + fade).toLong(), 1.0)
        return a to b
    }

    companion object {
        const val ID = "crossfade"
        const val FLOOR_SCORE = 0.05
        const val INCOMPATIBLE_SCORE = 0.3
        const val CONFIDENT_GRID = 0.5f
        const val MARKER_B_ENTERS = "B enters"
        const val MARKER_A_GONE = "A gone"
        const val LANE_A = "gainA"
        const val LANE_B = "gainB"
        private const val MIN_FADE_FRAMES = 256L
    }
}
