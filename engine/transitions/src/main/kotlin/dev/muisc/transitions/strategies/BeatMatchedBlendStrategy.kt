package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.FadeLaw
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
import dev.muisc.transitions.sdk.ParamSet
import kotlin.math.min

/**
 * `beatMatchedBlend` — the classic phrase-aligned DJ blend.
 *
 * **What it sounds like.** B's first phrase (its mix-in cue) drops in exactly on a downbeat of A's last phrase,
 * beat-locked to A's tempo, with its bass cut. Both tracks play together for [P.overlapBars] bars while an
 * equal-power crossfade moves the balance from A to B. At [P.bassInBar] B's low shelf opens (the "bass in"
 * moment), from the midpoint A's high shelf dips 6 dB so B's hats and vocals take the top end, and whenever B's
 * vocals rise A ducks by [P.vocalDuckDb]. After A is gone, B alone rides back to its own tempo over
 * [P.settleBars] bars (S-curve) and holds it for [P.holdBars] bars, so the segment ends with B at stretch ratio 1.0
 * and the player continues B's body seamlessly.
 *
 * **When the planner picks it.** Both grids confident (`beatMatchable`), `stretchPercent <= prefs.maxStretchPercent`
 * and room on both sides (`s_room >= 0.5`, i.e. at least half the overlap available after A's mix-out cue and
 * before B's mix-in cue). The score weights tempo closeness most, then key, energy, vocals, grid confidence,
 * structure (beat outro into beat intro) and room.
 *
 * **Params.**
 *  - `overlapBars` (16, 8–32): bars both decks are audible. Longer = smoother, more "radio mix"; shorter = punchier.
 *  - `bassInBar` (8): master bar at whose downbeat B's lows reach 0 dB (clamped inside the overlap).
 *  - `bassInBeats` (4): how many beats the low shelf takes to open (1 = a quick DJ "bass in", 16 = a slow fade).
 *  - `eqDepthDb` (12): how far B's lows are cut before `bassInBar` (0 disables the EQ).
 *  - `aHighsOutDb` (6): A's high-shelf dip from the midpoint of the overlap.
 *  - `lowHz` (200) / `highHz` (4000): shelf corners.
 *  - `vocalDuckDb` (6): A's duck when B's vocal activity is high (0 = off).
 *  - `law`: crossfade law (EQUAL_POWER for two different tracks; S_CURVE is softer at the ends).
 *  - `entryOffsetBars` (0, −8..8): shifts B's entry relative to its mix-in cue (negative = B enters during its intro).
 *  - `settleBars` (2) / `holdBars` (2): the tempo ride back to B's tempo after A is gone, and the bars held there.
 *
 * **Failure modes.** WSOLA smears sustained material above ~6 % stretch (the planner caps at `maxStretchPercent`);
 * wrong downbeat phase in either analysis puts kicks out of phase (the `bassCancellation` metric catches it);
 * two heavy basslines overlap after `bassInBar` unless A's crossfade has already taken it down.
 *
 * Geometry and rendering glue live in [BeatDomain]; the tempo-glide modifier can move the grid (`grid.*` params).
 */
class BeatMatchedBlendStrategy : TransitionStrategy {
    /** Parameters. */
    object P : ParamSet(ID) {
        val overlapBars = int("overlapBars", "Overlap", 16, 8, 32, "bars", "Bars during which both decks are audible")
        val bassInBar = int("bassInBar", "Bass in", 8, 1, 32, "bar", "Master bar at whose downbeat B's lows are fully in")
        val bassInBeats = int("bassInBeats", "Bass-in length", 4, 1, 16, "beats", "Beats the low shelf takes to open")
        val eqDepthDb = double("eqDepthDb", "B low cut", 12.0, 0.0, 30.0, "dB", "How far B's lows are cut before bassInBar (0 = no EQ)")
        val aHighsOutDb = double("aHighsOutDb", "A high dip", 6.0, 0.0, 24.0, "dB", "A's high-shelf dip from the midpoint of the overlap")
        val lowHz = double("lowHz", "Low shelf", 200.0, 60.0, 500.0, "Hz", "Corner of the low shelf")
        val highHz = double("highHz", "High shelf", 4000.0, 1000.0, 10000.0, "Hz", "Corner of the high shelf")
        val vocalDuckDb = double("vocalDuckDb", "Vocal duck", 6.0, 0.0, 18.0, "dB", "A ducks this much when B's vocal activity is high")
        val law = choice("law", "Fade law", FadeLaw.EQUAL_POWER, listOf(FadeLaw.EQUAL_POWER, FadeLaw.S_CURVE, FadeLaw.LINEAR), "Crossfade law of the blend")
        val entryOffsetBars = int("entryOffsetBars", "Entry offset", 0, -8, 8, "bars", "B enters this many bars after (negative: before) its mix-in cue")
        val settleBars = int("settleBars", "Settle", 2, 1, 8, "bars", "Bars over which B rides back to its own tempo after A is gone")
        val holdBars = int("holdBars", "Hold", 2, 1, 4, "bars", "Bars held at B's own tempo before the seam")
        val detectOnsets = bool("detectOnsets", "Detect onsets", true, "Also pin transients detected on the decoded windows (protects off-beat hats the analysis' onset list may lack)")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Beat-matched blend"
    override val description: String get() = "Phrase-aligned equal-power blend on a master beat grid with bass-in EQ, high dip and vocal ducking."
    override val params: List<ParamSpec> get() = P.specs

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val s = BeatDomain.SubScores(features, prefs, NEEDED_BARS * bpb, BeatDomain::structPrior)
        val blockers = ArrayList<String>()
        if (!features.beatMatchable) blockers += "not beat-matchable: grid confidence ${"%.2f".format(features.gridConfidenceA)}/${"%.2f".format(features.gridConfidenceB)} < 0.5"
        if (features.stretchPercent > prefs.maxStretchPercent) blockers += "tempo ${"%.1f".format(features.stretchPercent)} % stretch exceeds the ${"%.1f".format(prefs.maxStretchPercent)} % limit"
        if (s.room < 0.5) blockers += "room: ${features.outroBeatsAvailable} outro beats / ${features.introBeatsAvailable} intro beats (need ${NEEDED_BARS * bpb} each for s_room >= 0.5)"
        if (blockers.isNotEmpty()) return Applicability.blocked(*blockers.toTypedArray())
        val fit = 0.30 * s.tempo + 0.15 * s.key + 0.15 * s.energy + 0.10 * s.vocal + 0.10 * s.grid + 0.10 * s.struct + 0.10 * s.room
        val reasons = BeatDomain.describePair(features, a, b) + "fit ${"%.2f".format(fit)} = tempo ${"%.2f".format(s.tempo)}, key ${"%.2f".format(s.key)}, energy ${"%.2f".format(s.energy)}, vocal ${"%.2f".format(s.vocal)}, grid ${"%.2f".format(s.grid)}, structure ${"%.2f".format(s.struct)}, room ${"%.2f".format(s.room)}"
        return Applicability.of(fit, *reasons.toTypedArray())
    }

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p = P.resolve(params, prefs)
        val notes = ArrayList<String>()
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val matched = BeatDomain.matchedGrid(b.grid, features.tempoRelation)
        val settle = p.int(P.settleBars); val hold = p.int(P.holdBars)
        val bStart = BeatDomain.chooseBStart(b, matched, features.tempoRelation, p.int(P.entryOffsetBars), notes)
        var overlapBars = p.int(P.overlapBars)
        // B must have overlap + settle + hold bars of music after its start beat.
        val bRoomBars = (BeatDomain.lastUsableBeat(b, matched) - bStart) / bpb
        if (overlapBars + settle + hold > bRoomBars) {
            overlapBars = (bRoomBars - settle - hold).coerceAtLeast(1)
            notes += "overlap shortened to $overlapBars bars: B has only $bRoomBars bars after matched beat $bStart"
        }
        val (aStart, overlapBeats) = BeatDomain.chooseAStart(a, overlapBars * bpb, notes)
        overlapBars = overlapBeats / bpb
        val gridParams = BeatDomain.baseGridParams(p, overlapBars, settle, hold)
        val provisional = BeatDomain.provisionalPlan(ID, gridParams.with(BeatDomain.PARAM_A_START_BEAT, aStart).with(BeatDomain.PARAM_B_START_BEAT, bStart).with(BeatDomain.PARAM_OVERLAP_BEATS, overlapBeats))
        val layout = BeatDomain.resolveLayout(provisional, a, b, features, prefs)
        val lanes = lanes(layout, p)
        notes.add(0, "$overlapBars-bar ${p.choice(P.law)} blend, bass in at bar ${min(p.int(P.bassInBar), overlapBars)} over ${p.int(P.bassInBeats)} beats (B lows -${p.double(P.eqDepthDb)} dB before), A highs -${p.double(P.aHighsOutDb)} dB from bar ${overlapBars / 2}")
        notes += layout.describe()
        notes += "B stretch at entry ${"%.2f".format((layout.bRatioStart - 1.0) * 100.0)} % (${if (BeatDomain.keyLockOf(gridParams, prefs)) "key lock" else "vinyl"}), ends at ratio 1.0 after a $settle-bar settle and $hold-bar hold"
        return layout.applyTo(provisional.copy(notes = notes), lanes.map { it.toAutomationLane(prefs.sampleRate) })
    }

    override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val plan = input.plan
        val p = P.resolve(plan.params)
        val layout = BeatDomain.resolveLayout(plan, input.aAnalysis, input.bAnalysis, input.features, ctx.prefs)
        val r = BeatDomain.Render(input, ctx, layout, p.bool(P.detectOnsets))
        val lanes = lanes(layout, p)
        val (gainA, gainB, duckA, eqLowB, eqHighA) = lanes
        val g = layout.g.toLong()
        ctx.progress(0.05)

        // Deck A: beat-locked, high shelf dip, vocal duck; seam-blended into the dry pre-roll.
        val a = r.renderA()
        ctx.progress(0.35)
        BeatDomain.applyShelfInPlace(a, eqHighA, g, low = false, cornerHz = p.double(P.highHz), warmup = r.dryPreRoll())
        BeatDomain.applyDbLaneInPlace(a, duckA, g)
        r.blendHead(a)

        // Deck B: beat-locked over the whole grid, low shelf closed until bassInBar; seam-blended into the dry post-roll.
        val b = r.renderB()
        ctx.progress(0.75)
        BeatDomain.applyShelfInPlace(b, eqLowB, g, low = true, cornerHz = p.double(P.lowHz))
        r.blendTail(b)

        val audio = r.assemble(a, gainA, b, gainB)
        ctx.progress(0.9)
        val bassIn = min(p.int(P.bassInBar) * layout.beatsPerBar, layout.aBeats)
        val markers = listOf(
            Marker(layout.outFrame(0), MARKER_B_ENTERS),
            Marker(layout.outFrame(bassIn), MARKER_BASS_IN),
            Marker(layout.outFrame(layout.aBeats / 2), MARKER_A_HIGHS_OUT),
            Marker(layout.outFrame(layout.aBeats), MARKER_A_GONE),
            Marker(layout.outFrame(layout.totalBeats - layout.holdBeats), MARKER_HOLD),
        )
        val out = r.finish(audio, t0, markers, lanes.map { it.toAutomationLane(ctx.sampleRate) }, mapOf("bassInBeat" to bassIn.toDouble()))
        ctx.progress(1.0)
        return out
    }

    /** Frame-domain lanes: crossfade pair, A's vocal duck (dB), B's low shelf (dB), A's high shelf (dB). */
    private fun lanes(layout: BeatDomain.Layout, p: Params): List<Lane> {
        val law = CrossfadeLaw.parse(p.choice(P.law)) ?: FadeLaw.EQUAL_POWER
        val (gainA, gainB) = BeatDomain.crossfadeLanes(layout.outFrame(0), layout.outFrame(layout.aBeats), law)
        val duckA = BeatDomain.vocalDuckLane(layout, p.double(P.vocalDuckDb))
        val bpb = layout.beatsPerBar
        val eqLowB = Lane("eqLowB")
        val depth = p.double(P.eqDepthDb)
        if (depth > 0.0) {
            val bassIn = min(p.int(P.bassInBar) * bpb, layout.aBeats)
            val rampStart = (bassIn - p.int(P.bassInBeats)).coerceAtLeast(0)
            eqLowB.add(0L, -depth).add(layout.outFrame(rampStart), -depth, FadeLaw.S_CURVE).add(layout.outFrame(bassIn), 0.0)
        }
        val eqHighA = Lane("eqHighA")
        val dip = p.double(P.aHighsOutDb)
        if (dip > 0.0) {
            val mid = layout.aBeats / 2
            val end = min(mid + bpb, layout.aBeats)
            eqHighA.add(0L, 0.0).add(layout.outFrame(mid), 0.0, FadeLaw.S_CURVE).add(layout.outFrame(end), -dip)
        }
        return listOf(gainA, gainB, duckA, eqLowB, eqHighA)
    }

    private operator fun <T> List<T>.component5(): T = this[4]

    companion object {
        const val ID = "beatMatchedBlend"
        /** Bars of room the score asks for on each side (§5.2 `needed_s`). */
        const val NEEDED_BARS = 8
        const val MARKER_B_ENTERS = "B enters"
        const val MARKER_BASS_IN = "bass in"
        const val MARKER_A_HIGHS_OUT = "A highs out"
        const val MARKER_A_GONE = "A gone"
        const val MARKER_HOLD = "B at own tempo"
    }
}
