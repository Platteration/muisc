package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
import dev.muisc.dsp.fx.LoopRoll
import dev.muisc.dsp.gain.Curves
import dev.muisc.dsp.synth.RiserEnding
import dev.muisc.dsp.synth.RiserSpec
import dev.muisc.dsp.synth.Riser
import dev.muisc.dsp.synth.Waveform
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.AutomationLane
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.LanePoint
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
import dev.muisc.transitions.core.Lane
import dev.muisc.transitions.core.RenderReports
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.sdk.ParamSet
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * `loopRollRiser` — the **build**: A's last bar is captured and stuttered in ever-shorter loops while a synthesised
 * riser climbs over it, and on the downbeat where the roll would have restarted, B drops.
 *
 * ## What it sounds like
 * Half a bar of A, then a quarter, then an eighth, then sixteenths — the same beat folding in on itself, faster
 * and faster, its bass progressively high-passed away so the low end empties out — under a noise sweep and a
 * pitch-gliding tone that rise together. Then either a hole ([P.gapBeats]) or nothing at all, and B is simply
 * there at full level on the one. This is the loudest, most obvious move in the catalogue: a showpiece, gated on
 * `prefs.energy`, and it gets boring fast — the planner's variety penalty exists mostly for this strategy.
 *
 * ## How the frames are chosen
 * The roll replaces A's last [P.rollBars] bar(s): every loop starts at the same source frame (the downbeat that
 * begins that bar), so the roll's first sample is A's natural continuation of the pre-roll and there is no seam at
 * all where the effect starts. Loop lengths halve — `total/2, total/4, …` with the shortest one repeated so the
 * sum is exactly the roll length — so the last loop lands the drop exactly on the next downbeat. B enters at its
 * `dropBeat` when that cue is within 4 bars of its `mixInBeat` (the drop is the point of this transition), else at
 * `mixInBeat`.
 *
 * ## Parameters
 *  - [P.rollBars] — 1 bar is a tight, modern build; 2 bars gives the stutter room to breathe but eats a whole
 *    phrase of A.
 *  - [P.rollSteps] — how many halvings (3 = subtle, 5 = the classic 1/2 → 1/16, 6 = a machine-gun 1/32).
 *  - [P.hpfToHz] — where the high-pass on the loop ends. 800 Hz empties the low end completely so B's first kick
 *    hits into a vacuum; 200 Hz keeps the roll fat and makes the drop less dramatic.
 *  - [P.riserDb] — level of the riser. −8 dB sits under the roll; above −4 dB it takes over the mix (and the
 *    limiter starts working).
 *  - [P.riserCurve] — how the riser's build is shaped: `EXP` (default) holds back and rushes at the end, `LINEAR`
 *    is a steady climb, `S_CURVE` eases in and out.
 *  - [P.riserToHz] — top of the noise sweep; the tone glides an octave and a half under it.
 *  - [P.gapBeats] — silence between the roll and the drop. One beat of nothing is the oldest trick there is; it
 *    makes the drop enormous and is also the easiest way to make the transition sound broken if B is not tight.
 *
 * ## Failure modes
 *  - Gimmicky when overused: gate on `prefs.energy` and the planner's variety penalty.
 *  - A wrong downbeat phase in A's grid makes the roll start off-grid, which is immediately audible because the
 *    loop repeats the wrong slice of the bar.
 *  - Against a B whose intro is ambient there is no drop to land on and the build promises something that never
 *    arrives — scored down through the intro type.
 */
class LoopRollRiserStrategy : TransitionStrategy {

    /** Tunables (see the class doc for what each one does musically). */
    object P : ParamSet(ID) {
        val rollBars = int("rollBars", "Roll length", 1, 1, 2, "bars", "Bars of A replaced by the loop roll")
        val rollSteps = int("rollSteps", "Halvings", 5, 2, 6, "", "How many times the loop length halves (5 = 1/2 → 1/16 of the roll)")
        val hpfToHz = double("hpfToHz", "Roll high-pass", 800.0, 100.0, 4000.0, "Hz", "Where the high-pass on the roll ends: higher = emptier low end before the drop")
        val riserDb = double("riserDb", "Riser level", -8.0, -30.0, 0.0, "dB", "Level of the synthesised riser under the roll")
        val riserCurve = choice("riserCurve", "Riser curve", "EXP", listOf("LINEAR", "S_CURVE", "EXP"), "Shape of the riser's build")
        val riserToHz = double("riserToHz", "Riser top", 8000.0, 1000.0, 16000.0, "Hz", "Top of the riser's noise sweep (it starts at 200 Hz)")
        val gapBeats = double("gapBeats", "Gap", 0.0, 0.0, 1.0, "beats", "Silence between the end of the roll and the drop")
        val useDropBeat = bool("useDropBeat", "Land on B's drop", true, "Enter at B's dropBeat when it is within 4 bars of its mixInBeat")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Loop roll + riser"
    override val description: String get() = "Stutters A's last bar in halving loops under a riser and drops B on the next downbeat."
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val blockers = ArrayList<String>()
        if (a.grid.isEmpty) blockers += "A has no beat grid: the roll would not be in time"
        else if (features.gridConfidenceA < MIN_GRID_CONFIDENCE) {
            blockers += "A's grid confidence ${"%.2f".format(features.gridConfidenceA)} < $MIN_GRID_CONFIDENCE: the roll would start off-grid"
        }
        if (b.grid.isEmpty && b.cues.mixInBeat < 0) blockers += "B has no grid and no mix-in cue: nothing to drop onto"
        if (prefs.energy < MIN_ENERGY) blockers += "energy preference ${"%.2f".format(prefs.energy)} < $MIN_ENERGY: showpieces are switched off"
        if (features.intro == IntroType.SILENCE) blockers += "B starts with silence: a build into nothing"
        if (blockers.isNotEmpty()) return Applicability.blocked(*blockers.toTypedArray())

        val reasons = ArrayList<String>()
        val sStruct = introScore(features.intro) * (if (b.cues.dropBeat >= 0) 1.0 else 0.85)
        reasons += "B's intro is ${features.intro}${if (b.cues.dropBeat >= 0) " with a drop at beat ${b.cues.dropBeat}" else " (no drop cue)"}"
        // A build wants the new track to arrive at least as hard as the old one left off.
        val sEnergy = (0.5 + 0.5 * ((features.energyDelta + 1.0) / 2.0)).coerceIn(0.0, 1.0)
        if (features.energyDelta < -0.3) reasons += "B's head is quieter than A's tail (${"%.2f".format(features.energyDelta)}): the drop will underwhelm"
        val sGrid = features.gridConfidenceA
        // Tempo does not matter (the loop is A's own bar and B is never stretched), but a matched pair has better options.
        val incompatible = !features.beatMatchable || features.stretchPercent > prefs.maxStretchPercent
        val sTempo = if (incompatible) 1.0 else 0.8
        val sAmbition = 1.0 - abs(prefs.energy - AMBITION)
        reasons += "ambition $AMBITION against energy preference ${"%.2f".format(prefs.energy)}"
        val score = 0.32 * sStruct + 0.22 * sEnergy + 0.18 * sGrid + 0.14 * sTempo + 0.14 * sAmbition
        reasons.add(0, "loop roll + riser into the drop (score ${"%.2f".format(score)})")
        return Applicability.of(score, *reasons.toTypedArray())
    }

    /** How much of a drop B's head can deliver. */
    private fun introScore(intro: IntroType): Double = when (intro) {
        IntroType.BEAT_INTRO -> 1.0
        IntroType.COLD_START -> 1.0
        IntroType.VOCAL_INTRO -> 0.6
        IntroType.UNKNOWN -> 0.5
        IntroType.AMBIENT_INTRO -> 0.4
        IntroType.SILENCE -> 0.0
    }

    // -------------------------------------------------------------------------------------------------- plan

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val geo = geometry(a, b, p, prefs)
        val notes = ArrayList<String>()
        notes += "roll of A's last ${p.int(P.rollBars)} bar(s) from beat ${geo.rollBeat} (${"%.2f".format(geo.rollStartA / sr.toDouble())} s): " +
            geo.loopLengths.joinToString(" + ") { "${"%.0f".format(it * 1000.0 / sr)} ms" }
        notes += "high-pass on the roll ramps 20 → ${"%.0f".format(p.double(P.hpfToHz))} Hz; riser 200 → ${"%.0f".format(p.double(P.riserToHz))} Hz " +
            "at ${"%.1f".format(p.double(P.riserDb))} dB, ${p.choice(P.riserCurve)} build"
        notes += if (geo.gap > 0) "${"%.0f".format(geo.gap * 1000.0 / sr)} ms of silence before the drop" else "no gap: the drop lands on the roll's last frame"
        notes += "B drops at its beat ${geo.bBeat} (${geo.bBeatKind}), output frame ${geo.dropOut}"
        val lanes = ArrayList<AutomationLane>()
        lanes += rollStepLane(geo, sr)
        lanes += riserLane(geo, p, sr)
        lanes += masterBeatLane(a, geo, sr)
        return TransitionPlan(
            strategyId = ID,
            params = p,
            aExitFrame = geo.aExit,
            bEntryFrame = geo.bEntry,
            aWindow = FrameRange(geo.aExit, geo.rollStartA + geo.roll),
            bWindow = FrameRange(geo.bStart, geo.bEntry),
            expectedOutputFrames = geo.outFrames,
            lanes = lanes,
            notes = notes,
        )
    }

    // ------------------------------------------------------------------------------------------------ render

    override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val plan = input.plan
        val p = P.resolve(plan.params)
        val geo = geometry(input.aAnalysis, input.bAnalysis, p, ctx.prefs)
        val sr = ctx.sampleRate
        val ch = input.aAudio.channelCount
        val g = Splice.GUARD_FRAMES

        val out = AudioBuffer.silence(sr, ch, geo.outFrames)
        // 1. A dry through the pre-roll: the roll's first sample continues it exactly, so there is no seam.
        Splice.addInPlace(out, input.aAudio, dstOffset = 0, gain = null, srcOffset = 0, frames = min(g, input.aAudio.frames))
        ctx.progress(0.15)

        // 2. The roll: every loop starts at the same source frame (the downbeat at the start of A's last bar).
        val roll = LoopRoll(ROLL_CROSSFADE_MS).render(input.aAudio, g, geo.loopLengths)
        // 3. High-pass ramp over the roll, warmed up on the pre-roll so the filter state is settled at the seam.
        val filtered = highPassRamp(input.aAudio, roll, p.double(P.hpfToHz), g)
        // The roll and the riser both stop dead at the drop. A 3 ms release keeps that from being a click without
        // making the stop any less abrupt musically.
        fadeOutTail(filtered)
        Splice.addInPlace(out, filtered, dstOffset = g, gain = null, srcOffset = 0, frames = min(geo.roll, filtered.frames))
        ctx.progress(0.55)

        // 4. The riser, over the roll only (it is cut by the drop or by the gap).
        val riser = Riser.render(sr, geo.roll, riserSpec(p, ctx.seed), ch)
        val riserGain = Curves.dbToLinear(p.double(P.riserDb)).toFloat()
        for (c in 0 until ch) { val x = riser[c]; for (i in x.indices) x[i] *= riserGain }
        fadeOutTail(riser)
        Splice.addInPlace(out, riser, dstOffset = g, gain = null, srcOffset = 0, frames = geo.roll)
        ctx.progress(0.75)

        // 5. B, dropping in on the downbeat with a 5 ms declick, dry from then on.
        val bLane = Lane(LANE_B).add(geo.dropOut.toLong(), 0.0, FadeLaw.EQUAL_POWER).add((geo.dropOut + B_FADE_FRAMES).toLong(), 1.0)
        Splice.addInPlace(out, input.bAudio, dstOffset = geo.dropOut, gain = bLane, srcOffset = 0, frames = input.bAudio.frames)
        ctx.progress(0.85)

        val fin = RenderReports.finalize(out, ctx)
        val markers = ArrayList<Marker>()
        markers += Marker(g.toLong(), MARKER_ROLL)
        var at = g.toLong()
        for ((i, len) in geo.loopLengths.withIndex()) { at += len; if (i < geo.loopLengths.size - 1) markers += Marker(at, "roll step ${i + 2}") }
        if (geo.gap > 0) markers += Marker((g + geo.roll).toLong(), MARKER_GAP)
        markers += Marker(geo.dropOut.toLong(), MARKER_DROP)
        val metrics = mapOf(
            "rollFrames" to geo.roll.toDouble(),
            "rollSegments" to geo.loopLengths.size.toDouble(),
            "gapFrames" to geo.gap.toDouble(),
            "dropOutputFrame" to geo.dropOut.toDouble(),
            "riserFrames" to geo.roll.toDouble(),
            "limited" to (if (fin.limited) 1.0 else 0.0),
            "gainReductionDb" to fin.gainReductionDb,
        )
        val report = RenderReports.build(out, (System.nanoTime() - t0) / 1_000_000, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        return RenderedTransition(plan, out, markers, report)
    }

    /**
     * High-pass with an exponential cutoff ramp 20 Hz → [toHz] across the roll. The filter is first warmed up on
     * the [warmup] frames of A that precede the roll — the roll starts with exactly those samples' continuation,
     * so the state is already correct when the ramp begins and the seam stays inaudible.
     */
    private fun highPassRamp(aAudio: AudioBuffer, roll: AudioBuffer, toHz: Double, warmup: Int): AudioBuffer {
        val ch = roll.channelCount
        val sr = roll.sampleRate
        val svf = StateVariableFilter(sr, ch, HPF_FROM_HZ, HPF_Q)
        svf.mode = SvfMode.HIGH_PASS
        val inBlock = Array(ch) { FloatArray(FILTER_BLOCK) }
        val outBlock = Array(ch) { FloatArray(FILTER_BLOCK) }
        var done = 0
        while (done < warmup) {
            val k = min(FILTER_BLOCK, warmup - done)
            for (c in 0 until ch) System.arraycopy(aAudio[c], done, inBlock[c], 0, k)
            svf.process(inBlock, outBlock, k)
            done += k
        }
        val n = roll.frames
        val out = Array(ch) { FloatArray(n) }
        svf.setCutoffRamp(HPF_FROM_HZ, toHz, n.coerceAtLeast(1), exponential = true)
        done = 0
        while (done < n) {
            val k = min(FILTER_BLOCK, n - done)
            for (c in 0 until ch) System.arraycopy(roll[c], done, inBlock[c], 0, k)
            svf.process(inBlock, outBlock, k)
            for (c in 0 until ch) System.arraycopy(outBlock[c], 0, out[c], done, k)
            done += k
        }
        return AudioBuffer(sr, out)
    }

    /** Linear release over the last [frames] frames of [buf], in place (a declick, not a musical fade). */
    private fun fadeOutTail(buf: AudioBuffer, frames: Int = TAIL_FADE_FRAMES) {
        val n = min(frames, buf.frames)
        if (n <= 1) return
        for (c in 0 until buf.channelCount) {
            val x = buf[c]
            val from = buf.frames - n
            for (i in 0 until n) x[from + i] *= (1f - (i + 1).toFloat() / n)
        }
    }

    /** Riser spec from the params; the seed makes the noise layer deterministic per (input, seed). */
    private fun riserSpec(p: Params, seed: Long): RiserSpec = RiserSpec(
        noiseStartHz = RISER_FROM_HZ,
        noiseEndHz = p.double(P.riserToHz),
        noiseQ = 2.0,
        noiseLevel = 0.5,
        toneStartHz = RISER_TONE_FROM_HZ,
        toneEndHz = RISER_TONE_TO_HZ,
        toneLevel = 0.25,
        toneWaveform = Waveform.SINE,
        buildCurveK = curveK(p.choice(P.riserCurve)),
        ending = RiserEnding.CUT,
        seed = (seed and 0xFFFF).toInt(),
    )

    /** Build curvature `k` of [Curves.exponential] behind each named curve (LINEAR is the k → 0 limit). */
    private fun curveK(name: String): Double = when (name.uppercase()) {
        "LINEAR" -> 0.05
        "S_CURVE" -> 1.2
        else -> 3.0
    }

    // ---------------------------------------------------------------------------------------------- geometry

    /** Frames and beats shared by [plan] and [render]. */
    private class Geometry(
        val aExit: Long, val rollStartA: Long, val rollBeat: Int,
        val bStart: Long, val bBeat: Int, val bBeatKind: String, val bEntry: Long,
        val roll: Int, val gap: Int, val dropOut: Int, val outFrames: Int,
        val loopLengths: IntArray, val beatFramesA: Double,
    )

    private fun geometry(a: TrackAnalysis, b: TrackAnalysis, p: Params, prefs: TransitionPrefs): Geometry {
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES
        val scaleA = sr / a.sampleRate.toDouble()
        val scaleB = sr / b.sampleRate.toDouble()
        fun fa(f: Long) = Math.round(f * scaleA)
        val aTrimStart = fa(a.trimStartFrame)
        val aTrimEnd = fa(a.trimEndFrame)
        val bpmA = if (a.grid.bpm > 0) a.grid.bpm else a.tempo.bpm
        val bpb = max(1, a.grid.beatsPerBar)
        val beatFramesA = if (bpmA > 0) 60.0 * sr / bpmA else sr / 2.0
        val roll = Math.round(p.int(P.rollBars) * bpb * beatFramesA).toInt().coerceAtLeast(MIN_ROLL_FRAMES)

        // The roll starts on the LAST downbeat of A that leaves a bar of material for the loop and a pre-roll.
        val grid = a.grid
        var rollBeat = -1
        if (!grid.isEmpty) {
            val lastBeat = floor(grid.beatAtFrame(Math.round(aTrimEnd / scaleA))).toInt().coerceAtLeast(0)
            fun fits(beat: Int): Boolean {
                val f = fa(grid.frameOfBeat(beat.toDouble()))
                return f - g >= aTrimStart && f + roll <= aTrimEnd
            }
            var d = grid.previousDownbeat(lastBeat.toDouble())
            while (d > 0 && !fits(d)) d = grid.previousDownbeat((d - 1).toDouble())
            if (d >= 0 && fits(d)) rollBeat = d
        }
        val rollStartA = if (rollBeat >= 0) fa(grid.frameOfBeat(rollBeat.toDouble())) else max(aTrimStart + g, aTrimEnd - roll)
        val aExit = rollStartA - g

        // Halving loop lengths that sum EXACTLY to the roll: total/2, total/4, ..., with the shortest repeated.
        val steps = p.int(P.rollSteps)
        val lengths = halvingLengths(roll, steps)

        val gap = Math.round(p.double(P.gapBeats) * beatFramesA).toInt()
        val dropOut = g + roll + gap

        // B's entry: its drop cue when it is within 4 bars of the mix-in cue, else the mix-in cue.
        val bBpb = max(1, b.grid.beatsPerBar)
        val mixIn = b.cues.mixInBeat
        val drop = b.cues.dropBeat
        var bBeat = -1
        var kind = "trim start"
        if (!b.grid.isEmpty) {
            if (p.bool(P.useDropBeat) && drop >= 0 && (mixIn < 0 || abs(drop - mixIn) <= DROP_WINDOW_BARS * bBpb)) {
                bBeat = drop; kind = "drop cue"
            } else if (mixIn >= 0) {
                bBeat = mixIn; kind = "mix-in cue"
            } else {
                bBeat = if (b.cues.firstDownbeat >= 0) b.cues.firstDownbeat else b.grid.nextDownbeat(b.grid.beatAtFrame(b.trimStartFrame))
                kind = "first downbeat"
            }
        }
        val bStart = if (bBeat >= 0) Math.round(b.grid.frameOfBeat(bBeat.toDouble()) * scaleB).coerceAtLeast(0L)
        else Math.round(b.trimStartFrame * scaleB).coerceAtLeast(0L)

        val outFrames = dropOut + B_FADE_FRAMES + g
        val bEntry = bStart + (outFrames - dropOut)
        return Geometry(
            aExit = aExit, rollStartA = rollStartA, rollBeat = rollBeat,
            bStart = bStart, bBeat = bBeat, bBeatKind = kind, bEntry = bEntry,
            roll = roll, gap = gap, dropOut = dropOut, outFrames = outFrames,
            loopLengths = lengths, beatFramesA = beatFramesA,
        )
    }

    /**
     * `[total/2, total/4, …, total/2^(steps-1), total/2^(steps-1)]` rounded so the entries sum to exactly [total]:
     * the accelerating stutter that fills the roll and hands the next downbeat to B.
     */
    private fun halvingLengths(total: Int, steps: Int): IntArray {
        val n = max(2, steps)
        val out = IntArray(n)
        var remaining = total
        for (i in 0 until n - 1) {
            val len = (total shr (i + 1)).coerceAtLeast(MIN_LOOP_FRAMES)
            if (len >= remaining) { // no room left for the remaining halvings: give what is left to this loop
                out[i] = remaining
                for (j in i + 1 until n) out[j] = 0
                remaining = 0
                break
            }
            out[i] = len
            remaining -= len
        }
        if (remaining > 0) out[n - 1] = remaining
        return out.filter { it > 0 }.toIntArray()
    }

    /** Step boundaries of the roll: `value` is the loop length in ms, `outputSec` where that loop starts. */
    private fun rollStepLane(geo: Geometry, sr: Int): AutomationLane {
        val points = ArrayList<LanePoint>(geo.loopLengths.size)
        var at = Splice.GUARD_FRAMES.toLong()
        for (len in geo.loopLengths) {
            points += LanePoint(at.toDouble() / sr, len * 1000.0 / sr)
            at += len
        }
        return AutomationLane(LANE_ROLL, points)
    }

    /** The riser's build curve in dB, sampled for the Lab. */
    private fun riserLane(geo: Geometry, p: Params, sr: Int): AutomationLane {
        val g = Splice.GUARD_FRAMES
        val k = curveK(p.choice(P.riserCurve))
        val top = p.double(P.riserDb)
        val points = ArrayList<LanePoint>(RISER_LANE_POINTS + 1)
        for (i in 0..RISER_LANE_POINTS) {
            val t = i.toDouble() / RISER_LANE_POINTS
            val lin = Curves.exponential(t, k)
            points += LanePoint((g + t * geo.roll) / sr, top + Curves.linearToDb(lin.coerceAtLeast(1e-4)))
        }
        return AutomationLane(LANE_RISER, points)
    }

    /** One point per A beat inside the segment: `value` is A's absolute beat index, `outputSec` where it lands. */
    private fun masterBeatLane(a: TrackAnalysis, geo: Geometry, sr: Int): AutomationLane {
        val points = ArrayList<LanePoint>()
        if (!a.grid.isEmpty) {
            val scaleA = sr / a.sampleRate.toDouble()
            var beat = max(0.0, kotlin.math.ceil(a.grid.beatAtFrame(Math.round(geo.aExit / scaleA)))).toInt()
            // Beyond the roll the master beat keeps running at A's tempo (the drop lands on one of these beats).
            while (points.size <= MAX_LANE_POINTS) {
                val f = Math.round(a.grid.frameOfBeat(beat.toDouble()) * scaleA)
                val outFrame = f - geo.aExit
                if (outFrame > geo.outFrames) break
                points += LanePoint(outFrame.toDouble() / sr, beat.toDouble())
                beat++
            }
        }
        return AutomationLane(LANE_MASTER_BEAT, points)
    }

    companion object {
        const val ID = "loopRollRiser"
        const val MIN_GRID_CONFIDENCE = 0.5

        /** Showpiece: the planner's energy preference must be at least this high. */
        const val MIN_ENERGY = 0.4

        /** How adventurous this strategy is (DESIGN §5.3 `energyPref`). */
        const val AMBITION = 0.9

        const val MARKER_ROLL = "roll starts"
        const val MARKER_GAP = "gap"
        const val MARKER_DROP = "drop: B enters"
        const val LANE_ROLL = "rollStep"
        const val LANE_RISER = "riserDb"
        const val LANE_B = "gainB"
        const val LANE_MASTER_BEAT = "masterBeat"

        const val HPF_FROM_HZ = 20.0
        const val RISER_FROM_HZ = 200.0

        private const val HPF_Q = 0.7071067811865476
        private const val RISER_TONE_FROM_HZ = 110.0
        private const val RISER_TONE_TO_HZ = 440.0
        private const val ROLL_CROSSFADE_MS = 3.0
        private const val TAIL_FADE_FRAMES = 128
        private const val FILTER_BLOCK = 256
        private const val B_FADE_FRAMES = 220
        private const val MIN_ROLL_FRAMES = 4096
        private const val MIN_LOOP_FRAMES = 256
        private const val DROP_WINDOW_BARS = 4
        private const val RISER_LANE_POINTS = 16
        private const val MAX_LANE_POINTS = 4096
    }
}
