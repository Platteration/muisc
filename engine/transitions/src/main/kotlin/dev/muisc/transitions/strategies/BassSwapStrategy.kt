package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.MultibandCrossover
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
import kotlin.math.abs
import kotlin.math.min

/**
 * `bassSwap` — the EQ mix with a low-band handover.
 *
 * **What it sounds like.** B enters beat-locked on a downbeat of A's last phrase with its whole low band removed
 * (below [P.lowHz]); its mids and highs fade in equal-power over [P.overlapBars] bars while A's fade out. At the
 * downbeat of [P.swapBar] the bass is swapped: within [P.swapBeats] beats A's low band goes and B's comes in —
 * the classic "one bassline at a time" rule, so the two kicks and basslines never fight for the sub. After A is
 * gone B rides back to its own tempo ([P.settleBars]) and holds it ([P.holdBars]) so the seam is at ratio 1.0.
 *
 * **DSP.** Both rendered decks are split into 3 bands with the `dsp` [MultibandCrossover] (Linkwitz-Riley 4th
 * order at [P.lowHz] / [P.highHz], all-pass compensated: the bands sum flat). Per band, per deck, one gain lane;
 * the bands are summed. The crossover's all-pass phase rotation is the only difference to the dry guard regions,
 * bridged by the linear seam blend of [BeatDomain] (a 23 ms partial notch at the crossover frequencies, inaudible).
 *
 * **When the planner picks it.** As `beatMatchedBlend` (beat-matchable, stretch within the limit, room), and it
 * scores higher when both tracks carry real low end (`lowEndShareA/B`): a swap of nothing is pointless. The
 * player's live fallback mirrors it with a rate node when `|tempoRatio − 1| <= 2 %`.
 *
 * **Params.** `overlapBars` 16 (8–32), `swapBar` 8 (1–32, clamped into the overlap), `swapBeats` 1 (1–4),
 * `lowHz` 200 (60–400), `highHz` 4000 (1000–10000), `midHighLaw` EQUAL_POWER, `swapLaw` EQUAL_POWER,
 * `entryOffsetBars` 0, `settleBars` 2, `holdBars` 2.
 *
 * **Failure modes.** The LR4 sum is all-pass, not linear-phase: it rotates the phase a full turn at each split, which
 * delays a 50 Hz kick by roughly 2.5 ms relative to the dry deck (a DJ mixer's EQ does exactly the same, and both
 * decks are delayed equally, so nothing drifts apart — but a beat-alignment metric measured against the dry source
 * must allow for it). Two heavy sub-bass tracks still overlap for `swapBeats` at the swap (the swap is deliberately
 * short); wrong downbeat phase puts the swap on a weak beat; a track whose "bass" lives above `lowHz` (e.g. a
 * bass guitar with little sub) is only partly swapped — lower `lowHz` or use `beatMatchedBlend`.
 */
class BassSwapStrategy : TransitionStrategy {
    /** Parameters. */
    object P : ParamSet(ID) {
        val overlapBars = int("overlapBars", "Overlap", 16, 8, 32, "bars", "Bars during which both decks are audible")
        val swapBar = int("swapBar", "Swap bar", 8, 1, 32, "bar", "Master bar at whose downbeat the low bands are swapped (clamped into the overlap)")
        val swapBeats = int("swapBeats", "Swap length", 1, 1, 4, "beats", "Beats the low-band handover takes")
        val lowHz = double("lowHz", "Low split", 200.0, 60.0, 400.0, "Hz", "Low/mid crossover frequency")
        val highHz = double("highHz", "High split", 4000.0, 1000.0, 10000.0, "Hz", "Mid/high crossover frequency")
        val midHighLaw = choice("midHighLaw", "Mid/high law", FadeLaw.EQUAL_POWER, listOf(FadeLaw.EQUAL_POWER, FadeLaw.S_CURVE, FadeLaw.LINEAR), "Crossfade law of the mid and high bands over the overlap")
        val swapLaw = choice("swapLaw", "Swap law", FadeLaw.EQUAL_POWER, listOf(FadeLaw.EQUAL_POWER, FadeLaw.LINEAR, FadeLaw.S_CURVE), "Law of the low-band handover")
        val entryOffsetBars = int("entryOffsetBars", "Entry offset", 0, -8, 8, "bars", "B enters this many bars after (negative: before) its mix-in cue")
        val settleBars = int("settleBars", "Settle", 2, 1, 8, "bars", "Bars over which B rides back to its own tempo after A is gone")
        val holdBars = int("holdBars", "Hold", 2, 1, 4, "bars", "Bars held at B's own tempo before the seam")
        val detectOnsets = bool("detectOnsets", "Detect onsets", true, "Also pin transients detected on the decoded windows (protects off-beat hats the analysis' onset list may lack)")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Bass swap"
    override val description: String get() = "Beat-matched EQ mix: mids and highs crossfade, the low band is handed over in one short swap."
    override val params: List<ParamSpec> get() = P.specs

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val s = BeatDomain.SubScores(features, prefs, NEEDED_BARS * bpb, BeatDomain::structPrior)
        val blockers = ArrayList<String>()
        if (!features.beatMatchable) blockers += "not beat-matchable: grid confidence ${"%.2f".format(features.gridConfidenceA)}/${"%.2f".format(features.gridConfidenceB)} < 0.5"
        if (features.stretchPercent > prefs.maxStretchPercent) blockers += "tempo ${"%.1f".format(features.stretchPercent)} % stretch exceeds the ${"%.1f".format(prefs.maxStretchPercent)} % limit"
        if (s.room < 0.5) blockers += "room: ${features.outroBeatsAvailable} outro beats / ${features.introBeatsAvailable} intro beats (need ${NEEDED_BARS * bpb} each for s_room >= 0.5)"
        if (blockers.isNotEmpty()) return Applicability.blocked(*blockers.toTypedArray())
        // Low-end presence: a swap only matters when both tracks have bass to swap (0.3 share each ≈ full marks).
        val lowEnd = min(1.0, min(features.lowEndShareA, features.lowEndShareB) / LOW_END_FULL_SHARE)
        val fit = 0.30 * s.tempo + 0.15 * lowEnd + 0.10 * s.key + 0.10 * s.energy + 0.10 * s.vocal + 0.10 * s.grid + 0.05 * s.struct + 0.10 * s.room
        val reasons = BeatDomain.describePair(features, a, b) +
            "low-end share ${"%.2f".format(features.lowEndShareA)}/${"%.2f".format(features.lowEndShareB)} -> ${"%.2f".format(lowEnd)}" +
            (if (abs(features.tempoRatio - 1.0) <= 0.02) "live-capable (rate node, ${"%.1f".format(features.stretchPercent)} % <= 2 %)" else "render only (${"%.1f".format(features.stretchPercent)} % > 2 %)") +
            "fit ${"%.2f".format(fit)} = tempo ${"%.2f".format(s.tempo)}, low end ${"%.2f".format(lowEnd)}, key ${"%.2f".format(s.key)}, energy ${"%.2f".format(s.energy)}, vocal ${"%.2f".format(s.vocal)}, grid ${"%.2f".format(s.grid)}, structure ${"%.2f".format(s.struct)}, room ${"%.2f".format(s.room)}"
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
        val (swapStart, swapEnd) = swapBeats(layout, p)
        val lanes = lanes(layout, p)
        notes.add(0, "$overlapBars-bar EQ mix at ${p.double(P.lowHz).toInt()} / ${p.double(P.highHz).toInt()} Hz: mids+highs ${p.choice(P.midHighLaw)} crossfade, low band swapped at master beat $swapStart (bar ${swapStart / bpb}) over ${swapEnd - swapStart} beat(s)")
        notes += layout.describe()
        notes += "B stretch at entry ${"%.2f".format((layout.bRatioStart - 1.0) * 100.0)} %, ends at ratio 1.0 after a $settle-bar settle and $hold-bar hold"
        return layout.applyTo(provisional.copy(notes = notes), lanes.map { it.toAutomationLane(prefs.sampleRate) })
    }

    override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val plan = input.plan
        val p = P.resolve(plan.params)
        val layout = BeatDomain.resolveLayout(plan, input.aAnalysis, input.bAnalysis, input.features, ctx.prefs)
        val r = BeatDomain.Render(input, ctx, layout, p.bool(P.detectOnsets))
        val lanes = lanes(layout, p)
        val (lowA, lowB, midHighA, midHighB) = lanes
        val g = layout.g.toLong()
        val ch = input.aAudio.channelCount
        val edges = doubleArrayOf(p.double(P.lowHz), p.double(P.highHz))
        ctx.progress(0.05)

        // Deck A: split (crossover warmed on the dry pre-roll), gain per band, sum, seam-blend into the pre-roll.
        val a = r.renderA()
        ctx.progress(0.35)
        val aMix = splitAndGain(a, edges, g, listOf(lowA, midHighA, midHighA), warmup = r.dryPreRoll())
        r.blendHead(aMix)

        // Deck B: split, gain per band, sum, seam-blend into the post-roll.
        val b = r.renderB()
        ctx.progress(0.75)
        val bMix = splitAndGain(b, edges, g, listOf(lowB, midHighB, midHighB), warmup = null)
        r.blendTail(bMix)

        val audio = r.assemble(aMix, null, bMix, null)
        ctx.progress(0.9)
        val (swapStart, swapEnd) = swapBeats(layout, p)
        val markers = listOf(
            Marker(layout.outFrame(0), MARKER_B_ENTERS),
            Marker(layout.outFrame(swapStart), MARKER_BASS_SWAP),
            Marker(layout.outFrame(swapEnd), MARKER_SWAP_DONE),
            Marker(layout.outFrame(layout.aBeats), MARKER_A_GONE),
            Marker(layout.outFrame(layout.totalBeats - layout.holdBeats), MARKER_HOLD),
        )
        val out = r.finish(audio, t0, markers, lanes.map { it.toAutomationLane(ctx.sampleRate) }, mapOf("swapStartBeat" to swapStart.toDouble(), "swapBeats" to (swapEnd - swapStart).toDouble()))
        ctx.progress(1.0)
        return out
    }

    /** Master beats `[start, end)` of the low-band handover: the downbeat of `swapBar`, kept inside the overlap. */
    private fun swapBeats(layout: BeatDomain.Layout, p: Params): Pair<Int, Int> {
        val bpb = layout.beatsPerBar
        val len = min(p.int(P.swapBeats), layout.aBeats)
        val start = (p.int(P.swapBar) * bpb).coerceIn(0, layout.aBeats - len)
        return start to start + len
    }

    /** Frame-domain lanes: low A, low B, mid/high A, mid/high B (linear gains). */
    private fun lanes(layout: BeatDomain.Layout, p: Params): List<Lane> {
        val mhLaw = CrossfadeLaw.parse(p.choice(P.midHighLaw)) ?: FadeLaw.EQUAL_POWER
        val swapLaw = CrossfadeLaw.parse(p.choice(P.swapLaw)) ?: FadeLaw.EQUAL_POWER
        val (swapStart, swapEnd) = swapBeats(layout, p)
        val (lowA, lowB) = BeatDomain.crossfadeLanes(layout.outFrame(swapStart), layout.outFrame(swapEnd), swapLaw, "lowA", "lowB")
        val (midHighA, midHighB) = BeatDomain.crossfadeLanes(layout.outFrame(0), layout.outFrame(layout.aBeats), mhLaw, "midHighA", "midHighB")
        return listOf(lowA, lowB, midHighA, midHighB)
    }

    /** LR4 3-band split of [x] (state primed with [warmup]), each band scaled by its lane (sampled at output frames `frameOffset + i`), summed. */
    private fun splitAndGain(x: AudioBuffer, edges: DoubleArray, frameOffset: Long, gains: List<Lane>, warmup: Array<FloatArray>?): AudioBuffer {
        val ch = x.channelCount
        val n = x.frames
        val xo = MultibandCrossover(x.sampleRate, ch, edges)
        if (warmup != null && warmup[0].isNotEmpty()) {
            val scratch = Array(xo.bands) { Array(ch) { FloatArray(warmup[0].size) } }
            xo.process(warmup, scratch, warmup[0].size)
        }
        val bands = Array(xo.bands) { Array(ch) { FloatArray(n) } }
        xo.process(x.channels, bands, n)
        val out = Array(ch) { FloatArray(n) }
        val scratch = FloatArray(4096)
        for (band in 0 until xo.bands) {
            val lane = gains[band]
            for (c in 0 until ch) {
                lane.applyInPlace(bands[band][c], frameOffset, 0, n, scratch)
                val y = out[c]; val s = bands[band][c]
                for (i in 0 until n) y[i] += s[i]
            }
        }
        return AudioBuffer(x.sampleRate, out)
    }

    companion object {
        const val ID = "bassSwap"
        const val NEEDED_BARS = 8
        /** Sub+bass energy share at which the low-end sub-score reaches 1. */
        const val LOW_END_FULL_SHARE = 0.3
        const val MARKER_B_ENTERS = "B enters"
        const val MARKER_BASS_SWAP = "bass swap"
        const val MARKER_SWAP_DONE = "swap done"
        const val MARKER_A_GONE = "A gone"
        const val MARKER_HOLD = "B at own tempo"
    }
}
