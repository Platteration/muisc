package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.resample.VariableRateResampler
import dev.muisc.dsp.stretch.PitchShifter
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
import kotlin.math.ceil
import kotlin.math.min

/**
 * `harmonicBlend` — the key-aware long blend with a pitch shift.
 *
 * **What it sounds like.** Like `beatMatchedBlend` but longer ([P.overlapBars] 24) and *in key*: when the pair is a
 * semitone (or two) apart on the Camelot wheel, B's head is pitch-shifted by `features.bestPitchShiftSemitones`
 * so that the two tracks' chords agree while they overlap — the mix sounds like one song modulating into the
 * next instead of two keys grinding. After A is gone the shift ramps back to 0 over [P.settleBars] bars (an
 * S-curve, while B also rides back to its own tempo) so the seam into B's body is unshifted and at ratio 1.0.
 * A ducks by [P.vocalDuckDb] whenever B's vocals rise; an optional low shelf ([P.eqDepthDb], off by default)
 * keeps B's bass out until [P.bassInBar].
 *
 * **DSP.** "Resample × WSOLA" as in the `dsp` [PitchShifter], but time-varying and on the substrate: B's window is
 * read by the `dsp` [VariableRateResampler] at rate `2^(s/12)` where `s` is the semitone lane at the master time
 * of each source frame (pitch × 2^(s/12), duration ÷ 2^(s/12)); the warped copy gets B's beat grid and onsets
 * mapped through the same warp and is then rendered by a [dev.muisc.transitions.core.PhaseLockedDeck] (WSOLA,
 * pitch-preserving) onto the master grid, which restores the tempo exactly. One WSOLA pass in total.
 *
 * **When the planner picks it.** Beat-matchable, stretch within the limit, `|bestPitchShiftSemitones| <=
 * prefs.maxPitchShiftSemitones`, `camelotDistanceAfterShift <= 1`, key strengths >= 0.6 on both sides, room. The key
 * sub-score has the largest weight; every semitone of shift costs 5 % (formant-free shifting is audible on vocals).
 *
 * **Params.** `overlapBars` 24 (12–32), `maxShift` 1 (0–2, also capped by `prefs.maxPitchShiftSemitones`),
 * `settleBars` 4 (1–8), `vocalDuckDb` 6, `eqDepthDb` 0 (0–30), `bassInBar` 12, `lowHz` 200, `law` EQUAL_POWER,
 * `entryOffsetBars` 0, `holdBars` 2.
 *
 * **Failure modes.** A wrong key estimate on modal / atonal tracks shifts the wrong way (gated on key strength;
 * users can zero the key weight in prefs); the shift-back ramp is audible on sustained pads when `settleBars` is
 * short; formants move with the pitch (the "chipmunk" shift) — 1 semitone is fine, 2 is a stretch.
 */
class HarmonicBlendStrategy : TransitionStrategy {
    /** Parameters. */
    object P : ParamSet(ID) {
        val overlapBars = int("overlapBars", "Overlap", 24, 12, 32, "bars", "Bars during which both decks are audible")
        val maxShift = int("maxShift", "Max shift", 1, 0, 2, "st", "Largest pitch shift applied to B (also capped by prefs.maxPitchShiftSemitones); 0 = never shift")
        val settleBars = int("settleBars", "Settle", 4, 1, 8, "bars", "Bars over which the shift ramps back to 0 and B rides back to its own tempo after A is gone")
        val vocalDuckDb = double("vocalDuckDb", "Vocal duck", 6.0, 0.0, 18.0, "dB", "A ducks this much when B's vocal activity is high")
        val eqDepthDb = double("eqDepthDb", "B low cut", 0.0, 0.0, 30.0, "dB", "Optional low shelf on B until bassInBar (0 = off)")
        val bassInBar = int("bassInBar", "Bass in", 12, 1, 32, "bar", "Master bar at whose downbeat B's lows are fully in (with eqDepthDb > 0)")
        val lowHz = double("lowHz", "Low shelf", 200.0, 60.0, 500.0, "Hz", "Corner of the optional low shelf")
        val law = choice("law", "Fade law", FadeLaw.EQUAL_POWER, listOf(FadeLaw.EQUAL_POWER, FadeLaw.S_CURVE, FadeLaw.LINEAR), "Crossfade law of the blend")
        val entryOffsetBars = int("entryOffsetBars", "Entry offset", 0, -8, 8, "bars", "B enters this many bars after (negative: before) its mix-in cue")
        val holdBars = int("holdBars", "Hold", 2, 1, 4, "bars", "Bars held at B's own tempo, unshifted, before the seam")
        val detectOnsets = bool("detectOnsets", "Detect onsets", true, "Also pin transients detected on the decoded (and warped) windows (protects off-beat hats the analysis' onset list may lack)")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Harmonic blend"
    override val description: String get() = "Long key-aware beat-matched blend; B is pitch-shifted into A's key and shifted back before the seam."
    override val params: List<ParamSpec> get() = P.specs

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val s = BeatDomain.SubScores(features, prefs, NEEDED_BARS * bpb, BeatDomain::structPrior)
        val blockers = ArrayList<String>()
        if (!features.beatMatchable) blockers += "not beat-matchable: grid confidence ${"%.2f".format(features.gridConfidenceA)}/${"%.2f".format(features.gridConfidenceB)} < 0.5"
        if (features.stretchPercent > prefs.maxStretchPercent) blockers += "tempo ${"%.1f".format(features.stretchPercent)} % stretch exceeds the ${"%.1f".format(prefs.maxStretchPercent)} % limit"
        if (abs(features.bestPitchShiftSemitones) > prefs.maxPitchShiftSemitones) blockers += "best pitch shift ${"%+d".format(features.bestPitchShiftSemitones)} st exceeds the ${prefs.maxPitchShiftSemitones} st limit"
        if (features.camelotDistanceAfterShift > 1) blockers += "keys Camelot distance ${features.camelotDistanceAfterShift} after the best shift (> 1)"
        if (features.keyStrengthA < MIN_KEY_STRENGTH || features.keyStrengthB < MIN_KEY_STRENGTH) blockers += "key strength ${"%.2f".format(features.keyStrengthA)}/${"%.2f".format(features.keyStrengthB)} below $MIN_KEY_STRENGTH"
        if (s.room < 0.5) blockers += "room: ${features.outroBeatsAvailable} outro beats / ${features.introBeatsAvailable} intro beats (need ${NEEDED_BARS * bpb} each for s_room >= 0.5)"
        if (blockers.isNotEmpty()) return Applicability.blocked(*blockers.toTypedArray())
        val shiftCost = 1.0 - SHIFT_COST_PER_SEMITONE * abs(features.bestPitchShiftSemitones)
        val fit = (0.25 * s.tempo + 0.30 * s.key + 0.10 * s.energy + 0.10 * s.vocal + 0.10 * s.grid + 0.05 * s.struct + 0.10 * s.room) * shiftCost
        val reasons = BeatDomain.describePair(features, a, b) +
            (if (features.bestPitchShiftSemitones != 0) "pitch shift ${"%+d".format(features.bestPitchShiftSemitones)} st brings the keys to Camelot distance ${features.camelotDistanceAfterShift} (cost x${"%.2f".format(shiftCost)})" else "keys already compatible: no shift") +
            "fit ${"%.2f".format(fit)} = tempo ${"%.2f".format(s.tempo)}, key ${"%.2f".format(s.key)}, energy ${"%.2f".format(s.energy)}, vocal ${"%.2f".format(s.vocal)}, grid ${"%.2f".format(s.grid)}, structure ${"%.2f".format(s.struct)}, room ${"%.2f".format(s.room)}"
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
        val shift = shiftSemitones(features, p, prefs, notes)
        val gridParams = BeatDomain.baseGridParams(p, overlapBars, settle, hold).with(PARAM_SHIFT, shift)
        val provisional = BeatDomain.provisionalPlan(ID, gridParams.with(BeatDomain.PARAM_A_START_BEAT, aStart).with(BeatDomain.PARAM_B_START_BEAT, bStart).with(BeatDomain.PARAM_OVERLAP_BEATS, overlapBeats))
        val layout = BeatDomain.resolveLayout(provisional, a, b, features, prefs)
        val lanes = lanes(layout, p, shift)
        val (rampStart, rampEnd) = shiftRamp(layout, p)
        notes.add(0, "$overlapBars-bar ${p.choice(P.law)} harmonic blend, B shifted ${"%+d".format(shift)} st (${a.key.key.shortName} <- ${b.key.key.shortName}), shift ramps back to 0 between master beats $rampStart and $rampEnd")
        notes += layout.describe()
        return layout.applyTo(provisional.copy(notes = notes), lanes.map { it.toAutomationLane(prefs.sampleRate) })
    }

    override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val plan = input.plan
        val p = P.resolve(plan.params)
        val shift = plan.params[PARAM_SHIFT]?.toIntOrNull() ?: 0
        val layout = BeatDomain.resolveLayout(plan, input.aAnalysis, input.bAnalysis, input.features, ctx.prefs)
        val r = BeatDomain.Render(input, ctx, layout, p.bool(P.detectOnsets))
        val lanes = lanes(layout, p, shift)
        val (gainA, gainB, duckA, eqLowB, pitchB) = lanes
        val g = layout.g.toLong()
        ctx.progress(0.05)

        val a = r.renderA()
        ctx.progress(0.3)
        BeatDomain.applyDbLaneInPlace(a, duckA, g)
        r.blendHead(a)

        val b = if (shift == 0) r.renderB() else {
            val warp = Warp.of(input.bAudio, r.bGridRel, layout, pitchB)
            ctx.progress(0.5)
            r.renderB(warp.audio, warp.grid, warp.onsets(r.bOnsetsRel))
        }
        ctx.progress(0.8)
        BeatDomain.applyShelfInPlace(b, eqLowB, g, low = true, cornerHz = p.double(P.lowHz))
        r.blendTail(b)

        val audio = r.assemble(a, gainA, b, gainB)
        val (rampStart, rampEnd) = shiftRamp(layout, p)
        val markers = listOf(
            Marker(layout.outFrame(0), MARKER_B_ENTERS),
            Marker(layout.outFrame(layout.aBeats), MARKER_A_GONE),
            Marker(layout.outFrame(rampStart), MARKER_SHIFT_BACK),
            Marker(layout.outFrame(rampEnd), MARKER_UNSHIFTED),
        )
        val out = r.finish(audio, t0, markers, lanes.map { it.toAutomationLane(ctx.sampleRate) }, mapOf("pitchShiftSemitones" to shift.toDouble(), "shiftRampStartBeat" to rampStart.toDouble(), "shiftRampEndBeat" to rampEnd.toDouble()))
        ctx.progress(1.0)
        return out
    }

    /** The shift actually applied: the pair's best shift, or 0 when it exceeds `maxShift` / the prefs limit. */
    private fun shiftSemitones(f: PairFeatures, p: Params, prefs: TransitionPrefs, notes: MutableList<String>): Int {
        val allowed = min(p.int(P.maxShift).toDouble(), prefs.maxPitchShiftSemitones)
        val best = f.bestPitchShiftSemitones
        if (best == 0) return 0
        if (abs(best) > allowed) { notes += "best pitch shift ${"%+d".format(best)} st exceeds the allowed ${"%.1f".format(allowed)} st: blending unshifted (Camelot distance ${f.camelotDistance})"; return 0 }
        return best
    }

    /** Master beats `[start, end)` over which the shift ramps back to 0: the `settleBars` before the hold. */
    private fun shiftRamp(layout: BeatDomain.Layout, p: Params): Pair<Int, Int> {
        val end = layout.totalBeats - layout.holdBeats
        val start = (end - p.int(P.settleBars) * layout.beatsPerBar).coerceAtLeast(0)
        return start to end
    }

    /** Frame-domain lanes: crossfade pair, A's vocal duck (dB), B's optional low shelf (dB), B's pitch (semitones). */
    private fun lanes(layout: BeatDomain.Layout, p: Params, shift: Int): List<Lane> {
        val law = CrossfadeLaw.parse(p.choice(P.law)) ?: FadeLaw.EQUAL_POWER
        val (gainA, gainB) = BeatDomain.crossfadeLanes(layout.outFrame(0), layout.outFrame(layout.aBeats), law)
        val duckA = BeatDomain.vocalDuckLane(layout, p.double(P.vocalDuckDb))
        val bpb = layout.beatsPerBar
        val eqLowB = Lane("eqLowB")
        val depth = p.double(P.eqDepthDb)
        if (depth > 0.0) {
            val bassIn = min(p.int(P.bassInBar) * bpb, layout.aBeats)
            eqLowB.add(0L, -depth).add(layout.outFrame((bassIn - bpb).coerceAtLeast(0)), -depth, FadeLaw.S_CURVE).add(layout.outFrame(bassIn), 0.0)
        }
        val (rampStart, rampEnd) = shiftRamp(layout, p)
        val pitchB = Lane(LANE_PITCH).add(0L, shift.toDouble()).add(layout.outFrame(rampStart), shift.toDouble(), FadeLaw.S_CURVE).add(layout.outFrame(rampEnd), 0.0)
        return listOf(gainA, gainB, duckA, eqLowB, pitchB)
    }

    private operator fun <T> List<T>.component5(): T = this[4]

    /**
     * B's window read at a time-varying rate `2^(s/12)` (the [VariableRateResampler] with anti-aliasing, ratio
     * constant per [BLOCK] output frames), plus the monotone map from window frames to warped frames so the beat
     * grid and onsets follow the warp.
     */
    private class Warp(val audio: AudioBuffer, val grid: BeatGrid, private val srcAtBlock: DoubleArray, private val ratios: DoubleArray, private val blocks: Int) {
        /** Warped frame of a window frame (fractional blocks interpolated; extrapolated outside). */
        fun map(frame: Long): Long {
            val x = frame.toDouble()
            var lo = 0; var hi = blocks - 1
            if (x >= srcAtBlock[hi]) lo = hi
            else if (x > srcAtBlock[0]) { while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (srcAtBlock[mid] <= x) lo = mid else hi = mid } }
            return Math.round(lo.toDouble() * BLOCK + (x - srcAtBlock[lo]) / ratios[lo])
        }

        fun onsets(windowOnsets: LongArray): LongArray = LongArray(windowOnsets.size) { map(windowOnsets[it]) }.filter { it >= 0 && it < audio.frames }.toLongArray()

        companion object {
            const val BLOCK = 64

            /** Warps [window] (B's decoded window) following [pitchLane] (semitones, output frames) via the layout's master time. */
            fun of(window: AudioBuffer, gridRel: BeatGrid, layout: BeatDomain.Layout, pitchLane: Lane): Warp {
                val ch = window.channelCount
                val len = window.frames
                val maxShift = pitchLane.points.maxOf { abs(it.value) }
                val minRatio = PitchShifter.semitonesToRatio(-maxShift)
                val capacityBlocks = ceil(len / minRatio / BLOCK).toInt() + 2
                val out = Array(ch) { FloatArray(capacityBlocks * BLOCK) }
                val srcAt = DoubleArray(capacityBlocks)
                val ratios = DoubleArray(capacityBlocks)
                val rs = VariableRateResampler(ch, antiAlias = true)
                rs.reset(0.0)
                var j = 0
                while (rs.positionInInput < len && j < capacityBlocks) {
                    val pos = rs.positionInInput
                    val masterBeat = gridRel.beatAtFrame(Math.round(pos)) - layout.bStartBeat
                    val semitones = pitchLane.valueAt(layout.outFrame(masterBeat))
                    val ratio = PitchShifter.semitonesToRatio(semitones)
                    srcAt[j] = pos; ratios[j] = ratio
                    rs.setRatio(ratio)
                    rs.process(window.channels, out, BLOCK, j * BLOCK)
                    j++
                }
                val frames = j * BLOCK
                val warped = AudioBuffer(window.sampleRate, Array(ch) { out[it].copyOf(frames) })
                val w = Warp(warped, gridRel, srcAt.copyOf(j), ratios.copyOf(j), j)
                val beatFrames = LongArray(gridRel.beatCount) { w.map(gridRel.beatFrames[it]) }
                return Warp(warped, BeatDomain.gridWithFrames(gridRel, beatFrames), w.srcAtBlock, w.ratios, j)
            }
        }
    }

    companion object {
        const val ID = "harmonicBlend"
        const val NEEDED_BARS = 8
        const val MIN_KEY_STRENGTH = 0.6
        const val SHIFT_COST_PER_SEMITONE = 0.05
        /** Plan param carrying the applied shift in semitones (so render never re-decides). */
        const val PARAM_SHIFT = "harmonic.shiftSemitones"
        const val LANE_PITCH = "pitchB"
        const val MARKER_B_ENTERS = "B enters"
        const val MARKER_A_GONE = "A gone"
        const val MARKER_SHIFT_BACK = "shift ramps back"
        const val MARKER_UNSHIFTED = "B unshifted, own tempo"
    }
}
