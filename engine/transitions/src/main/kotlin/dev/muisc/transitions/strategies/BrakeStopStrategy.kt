package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
import dev.muisc.dsp.fx.BrakeCurve
import dev.muisc.dsp.fx.VinylBrake
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * `brakeStop` — **kill the power**: A is dragged to a standstill like a turntable whose motor was switched off,
 * there is a breath of silence, and B starts cold.
 *
 * ## What it sounds like
 * Pitch and speed fall together (this is a real variable-rate resample, not a pitch shift), the low end blooms and
 * then smears as everything slides down through the [P.lpfMinHz] low-pass that tracks the platter, and the last
 * revolution grinds into silence. After [P.gapMs] of nothing, B's first beat lands with no preparation at all. With
 * [P.spinback] the record is yanked backwards instead — the same deceleration applied to A played in reverse.
 *
 * ## How the frames are chosen
 * The brake starts on the last downbeat of A that leaves room for it (any downbeat will do — a brake sounds
 * deliberate wherever it starts, as long as it starts *on* a beat), and B starts at its `mixInBeat`. Nothing from
 * A's grid is needed beyond that; tempos are irrelevant because A stops before B begins.
 *
 * ## Parameters
 *  - [P.brakeSec] — 0.8 s is a snap, 1.5 s (default) is the classic "power off", 3 s is a slow, agonising drag
 *    that needs a track with something sustained left to drag.
 *  - [P.curve] — `POWER` (heavy platter: speed `(1−t)^exponent`, most of the movement early) or `EXPONENTIAL`
 *    (fast initial drop with a long crawl at the end). Both reach exactly zero.
 *  - [P.exponent] — heaviness of the `POWER` platter: 1 = constant deceleration, 2 = a heavy Technics, 4 = a
 *    flywheel that refuses to stop.
 *  - [P.gapMs] — the silence before B. 0 = B lands on the brake's last frame, 120 ms (default) is a beat of air,
 *    400 ms+ is a dramatic pause that only works when the room is with you.
 *  - [P.lpfMinHz] — where the low-pass ends up as the platter stops. It tracks the speed, so the track gets duller
 *    exactly as it gets slower; 200 Hz is a dying rumble, 2 kHz keeps it bright and mechanical.
 *  - [P.spinback] — reverse the record into the stop instead of just slowing it.
 *  - [P.cooldown] — **advisory**: strategies cannot see `previousStrategyId`, so the PLANNER enforces this (no
 *    second brake within this many transitions). It is declared here so the value lives with the strategy and the
 *    Lab can show it.
 *
 * ## Failure modes
 *  - Fatigue: a brake is an exclamation mark, and two in a row is a joke. The planner's cooldown and variety
 *    penalty are the only defence.
 *  - Silly on ballads — gated here on A's percussiveness (energy-weighted over its last 16 bars) > 0.4 and on
 *    `prefs.energy ≥ 0.5`.
 *  - Into an ambient or vocal intro the cold start sounds like a mistake: gated to `COLD_START` / `BEAT_INTRO`.
 *  - The resampler's pitch drop is faithful, which means a track with a long reverb tail smears into a growl; that
 *    is the effect, not a bug, but it is why [P.brakeSec] above ~2 s needs care.
 */
class BrakeStopStrategy : TransitionStrategy {

    /** Tunables (see the class doc for what each one does musically). */
    object P : ParamSet(ID) {
        val brakeSec = double("brakeSec", "Brake", 1.5, 0.8, 3.0, "s", "How long the platter takes to stop")
        val curve = choice("curve", "Curve", BrakeCurve.POWER, BrakeCurve.entries.toList(), "Shape of the deceleration: POWER (heavy platter) or EXPONENTIAL (fast drop, long crawl)")
        val exponent = double("exponent", "Platter weight", 2.0, 0.5, 4.0, "", "Exponent of the POWER curve: 1 = constant deceleration, 4 = a flywheel")
        val gapMs = double("gapMs", "Gap", 120.0, 0.0, 1000.0, "ms", "Silence between the stop and B's first frame")
        val lpfMinHz = double("lpfMinHz", "Low-pass floor", 400.0, 100.0, 8000.0, "Hz", "Where the speed-tracking low-pass ends up when the platter has stopped")
        val spinback = bool("spinback", "Spinback", false, "Yank the record backwards into the stop instead of slowing it forwards")
        val cooldown = int("cooldown", "Cooldown", 6, 1, 32, "transitions", "Advisory: the planner must not pick a brake again within this many transitions")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Brake stop"
    override val description: String get() = "Drags A to a dead stop like a turntable losing power, then starts B cold after a short gap."
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val blockers = ArrayList<String>()
        if (features.intro != IntroType.COLD_START && features.intro != IntroType.BEAT_INTRO) {
            blockers += "B's intro is ${features.intro}: a cold start after a stop needs COLD_START or BEAT_INTRO"
        }
        if (prefs.energy < MIN_ENERGY) blockers += "energy preference ${"%.2f".format(prefs.energy)} < $MIN_ENERGY: no showpieces"
        val perc = percussiveness(a)
        if (perc <= MIN_PERCUSSIVENESS) {
            blockers += "A's percussiveness ${"%.2f".format(perc)} ≤ $MIN_PERCUSSIVENESS: a brake on a ballad is silly"
        }
        val defaultBrake = P.defaults().double(P.brakeSec)
        val roomSec = (a.trimEndFrame - a.trimStartFrame).toDouble() / max(1, a.sampleRate)
        if (roomSec < defaultBrake + 1.0) blockers += "A has only ${"%.1f".format(roomSec)} s of audio: no room to brake"
        if (blockers.isNotEmpty()) return Applicability.blocked(*blockers.toTypedArray())

        val reasons = ArrayList<String>()
        reasons += "A's percussiveness ${"%.2f".format(perc)} (energy-weighted over its last $TAIL_BARS bars)"
        val sStruct = if (features.intro == IntroType.COLD_START) 1.0 else 0.85
        reasons += "B's intro is ${features.intro} (structure ${"%.2f".format(sStruct)})"
        // Tempo and key are irrelevant: A has stopped before B starts. That is the whole point.
        val sTempo = 1.0
        val sPerc = ((perc - MIN_PERCUSSIVENESS) / (1.0 - MIN_PERCUSSIVENESS)).coerceIn(0.0, 1.0)
        // A brake asks B to arrive with authority.
        val sEnergy = (0.5 + 0.5 * ((features.energyDelta + 1.0) / 2.0)).coerceIn(0.0, 1.0)
        val sAmbition = 1.0 - kotlin.math.abs(prefs.energy - AMBITION)
        val sGrid = 0.5 + 0.5 * features.gridConfidenceA // a grid is nice (the brake starts on a downbeat), not required
        val score = 0.30 * sStruct + 0.22 * sPerc + 0.18 * sEnergy + 0.15 * sAmbition + 0.10 * sGrid + 0.05 * sTempo
        reasons.add(0, "vinyl brake into a cold open (score ${"%.2f".format(score)}; the planner enforces the ${P.defaults().int(P.cooldown)}-transition cooldown)")
        return Applicability.of(score, *reasons.toTypedArray())
    }

    /**
     * A's percussiveness over its last [TAIL_BARS] bars, weighted by each bar's energy so that near-silent intro /
     * outro bars (which carry no drums by definition) cannot drag a perfectly punchy track below the gate.
     * Falls back to 1.0 when the analysis has no bar features (nothing to gate on).
     */
    private fun percussiveness(a: TrackAnalysis): Double {
        val bars = a.bars
        val n = bars.percussiveness.size
        if (n == 0 || bars.energy.size != n) return 1.0
        var num = 0.0
        var den = 0.0
        for (i in max(0, n - TAIL_BARS) until n) {
            val w = max(bars.energy[i].toDouble(), 1e-3)
            num += w * bars.percussiveness[i]
            den += w
        }
        return if (den <= 0.0) 1.0 else num / den
    }

    // -------------------------------------------------------------------------------------------------- plan

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val geo = geometry(a, b, p, prefs)
        val notes = ArrayList<String>()
        notes += "${"%.2f".format(geo.brake / sr.toDouble())} s ${p.choice(P.curve)} brake" +
            (if (p.choice(P.curve) == BrakeCurve.POWER.name) " (exponent ${"%.1f".format(p.double(P.exponent))})" else "") +
            (if (geo.spinback) ", spinback: A is played backwards into the stop" else "")
        notes += "brake starts at A ${geo.startKind} ${geo.startBeat} (${"%.2f".format(geo.brakeStartA / sr.toDouble())} s); " +
            "low-pass tracks the speed down to ${"%.0f".format(p.double(P.lpfMinHz))} Hz"
        notes += "${"%.0f".format(geo.gap * 1000.0 / sr)} ms of silence, then B enters cold at its beat ${geo.bBeat}"
        val lanes = listOf(
            speedLane(geo, p, sr),
            cutoffLane(geo, p, sr),
            beatsALane(a, geo, sr),
        )
        return TransitionPlan(
            strategyId = ID,
            params = p,
            aExitFrame = geo.aExit,
            bEntryFrame = geo.bEntry,
            aWindow = FrameRange(geo.aExit - geo.preSource, geo.brakeStartA + geo.brake),
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
        val brake = VinylBrake(
            curve = BrakeCurve.entries.firstOrNull { it.name == p.choice(P.curve) } ?: BrakeCurve.POWER,
            exponent = p.double(P.exponent),
        )

        // 1. A: dry pre-roll, then the platter stops. `VinylBrake.brake` copies [0, startFrame) verbatim, which is
        //    exactly the dry pre-roll the splice contract wants. The window may carry extra frames BEFORE the
        //    segment (a spinback's reverse material), so everything is indexed from `aExitOffset`.
        val aOff = plan.aExitOffset
        val aSeg = if (aOff == 0) input.aAudio else input.aAudio.slice(aOff, input.aAudio.frames)
        val braked = if (geo.spinback) spinback(input.aAudio, aSeg, brake, aOff + g, g, geo.brake)
        else brake.brake(aSeg, g, geo.brake, g + geo.brake)
        // 2. The low-pass tracks the platter: cutoff = max(lpfMin, 20 kHz * speed), applied over the brake only.
        trackingLowPass(braked, brake, g, geo.brake, p.double(P.lpfMinHz), sr, ch)
        ctx.progress(0.5)

        val out = AudioBuffer.silence(sr, ch, geo.outFrames)
        Splice.addInPlace(out, braked, dstOffset = 0, gain = null, srcOffset = 0, frames = min(braked.frames, g + geo.brake))
        ctx.progress(0.7)

        // 3. B, cold, after the gap, with a 5 ms declick; dry from then on.
        val bLane = Lane(LANE_B).add(geo.bEnterOut.toLong(), 0.0, FadeLaw.EQUAL_POWER).add((geo.bEnterOut + B_FADE_FRAMES).toLong(), 1.0)
        Splice.addInPlace(out, input.bAudio, dstOffset = geo.bEnterOut, gain = bLane, srcOffset = 0, frames = input.bAudio.frames)
        ctx.progress(0.85)

        val fin = RenderReports.finalize(out, ctx)
        val markers = listOf(
            Marker(g.toLong(), if (geo.spinback) MARKER_SPINBACK else MARKER_BRAKE),
            Marker((g + geo.brake).toLong(), MARKER_STOPPED),
            Marker(geo.bEnterOut.toLong(), MARKER_B_ENTERS),
        )
        val metrics = mapOf(
            "brakeFrames" to geo.brake.toDouble(),
            "gapFrames" to geo.gap.toDouble(),
            "stopOutputFrame" to (g + geo.brake).toDouble(),
            "bEnterOutputFrame" to geo.bEnterOut.toDouble(),
            "spinback" to (if (geo.spinback) 1.0 else 0.0),
            "limited" to (if (fin.limited) 1.0 else 0.0),
            "gainReductionDb" to fin.gainReductionDb,
        )
        val report = RenderReports.build(out, (System.nanoTime() - t0) / 1_000_000, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        return RenderedTransition(plan, out, markers, report)
    }

    /**
     * Spinback: the same deceleration applied to A *reversed* from the brake point. The reversed buffer starts on
     * the very sample the pre-roll ended on, so the junction is continuous in value (only the slope flips); a
     * [REVERSE_BLEND_FRAMES] linear blend removes the last trace of it.
     */
    private fun spinback(aWindowAudio: AudioBuffer, aSeg: AudioBuffer, brake: VinylBrake, reverseEnd: Int, preRoll: Int, brakeFrames: Int): AudioBuffer {
        val ch = aWindowAudio.channelCount
        val n = min(reverseEnd, aWindowAudio.frames)
        val reversed = Array(ch) { c ->
            val src = aWindowAudio[c]
            FloatArray(n) { i -> src[n - 1 - i] }
        }
        val backwards = brake.brake(AudioBuffer(aWindowAudio.sampleRate, reversed), 0, brakeFrames, brakeFrames)
        val out = Array(ch) { c ->
            val x = FloatArray(preRoll + brakeFrames)
            System.arraycopy(aSeg[c], 0, x, 0, min(preRoll, aSeg.frames))
            System.arraycopy(backwards[c], 0, x, preRoll, brakeFrames)
            val blend = min(REVERSE_BLEND_FRAMES, brakeFrames)
            for (i in 0 until blend) {
                val t = (i + 1).toFloat() / (blend + 1)
                val idx = preRoll + i
                val forward = if (idx < aSeg.frames) aSeg[c][idx] else 0f
                x[idx] = forward * (1f - t) + x[idx] * t
            }
            x
        }
        return AudioBuffer(aWindowAudio.sampleRate, out)
    }

    /**
     * Low-pass whose cutoff follows the platter: `max(lpfMinHz, 20 kHz × speed(t))`, updated every
     * [FILTER_BLOCK] frames (the SVF interpolates its coefficients inside the block, so there is no zipper).
     * Applied in place to the brake region only — the dry pre-roll is never touched.
     */
    private fun trackingLowPass(buffer: AudioBuffer, brake: VinylBrake, from: Int, frames: Int, lpfMinHz: Double, sr: Int, ch: Int) {
        if (frames <= 0) return
        val svf = StateVariableFilter(sr, ch, LPF_TOP_HZ, LPF_Q)
        svf.mode = SvfMode.LOW_PASS
        val inBlock = Array(ch) { FloatArray(FILTER_BLOCK) }
        val outBlock = Array(ch) { FloatArray(FILTER_BLOCK) }
        // Warm-up on the dry pre-roll (output discarded): the filter state is then already correct at full speed,
        // so the first braked sample continues the pre-roll instead of starting from a zeroed filter.
        var warm = 0
        while (warm < from) {
            val k = min(FILTER_BLOCK, from - warm)
            for (c in 0 until ch) System.arraycopy(buffer[c], warm, inBlock[c], 0, k)
            svf.process(inBlock, outBlock, k)
            warm += k
        }
        var done = 0
        while (done < frames) {
            val k = min(FILTER_BLOCK, frames - done)
            val t = (done + k).toDouble() / frames
            svf.setCutoff(max(lpfMinHz, LPF_TOP_HZ * brake.speedAt(t)))
            for (c in 0 until ch) System.arraycopy(buffer[c], from + done, inBlock[c], 0, k)
            svf.process(inBlock, outBlock, k)
            for (c in 0 until ch) System.arraycopy(outBlock[c], 0, buffer[c], from + done, k)
            done += k
        }
    }

    // ---------------------------------------------------------------------------------------------- geometry

    /** Frames and beats shared by [plan] and [render]. */
    private class Geometry(
        val aExit: Long, val brakeStartA: Long, val startBeat: Int, val startKind: String,
        val bStart: Long, val bBeat: Int, val bEntry: Long,
        val brake: Int, val gap: Int, val bEnterOut: Int, val outFrames: Int, val spinback: Boolean,
        /**
         * Extra frames of A decoded BEFORE `aExit`. A spinback plays A backwards through the same deceleration,
         * so it needs material from before the brake point: the resampler consumes `∫ speed` source frames, at
         * most 2/3 of the brake for the lightest platter, and one full brake length is decoded to be safe.
         */
        val preSource: Int,
    )

    private fun geometry(a: TrackAnalysis, b: TrackAnalysis, p: Params, prefs: TransitionPrefs): Geometry {
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES
        val scaleA = sr / a.sampleRate.toDouble()
        val scaleB = sr / b.sampleRate.toDouble()
        fun fa(f: Long) = Math.round(f * scaleA)
        val aTrimStart = fa(a.trimStartFrame)
        val aTrimEnd = fa(a.trimEndFrame)
        val brake = Math.round(p.double(P.brakeSec) * sr).toInt()

        // The brake starts on the last downbeat of A that leaves the pre-roll and the brake's source material.
        val grid = a.grid
        var startBeat = -1
        var startKind = "downbeat"
        if (!grid.isEmpty) {
            val lastBeat = floor(grid.beatAtFrame(Math.round(aTrimEnd / scaleA))).toInt().coerceAtLeast(0)
            fun fits(beat: Int): Boolean {
                val f = fa(grid.frameOfBeat(beat.toDouble()))
                return f - g >= aTrimStart && f + brake <= aTrimEnd
            }
            var d = grid.previousDownbeat(lastBeat.toDouble())
            while (d > 0 && !fits(d)) d = grid.previousDownbeat((d - 1).toDouble())
            if (d >= 0 && fits(d)) startBeat = d
        }
        val brakeStartA = if (startBeat >= 0) fa(grid.frameOfBeat(startBeat.toDouble())) else max(aTrimStart + g, aTrimEnd - brake)
        if (startBeat < 0) startKind = "trim end − brake"
        val aExit = brakeStartA - g

        // B's cold entry.
        val bBeat: Int
        val bStart: Long
        if (!b.grid.isEmpty && b.cues.mixInBeat >= 0) {
            bBeat = b.cues.mixInBeat
            bStart = Math.round(b.grid.frameOfBeat(bBeat.toDouble()) * scaleB).coerceAtLeast(0L)
        } else if (!b.grid.isEmpty) {
            bBeat = if (b.cues.firstDownbeat >= 0) b.cues.firstDownbeat else b.grid.nextDownbeat(b.grid.beatAtFrame(b.trimStartFrame))
            bStart = Math.round(b.grid.frameOfBeat(bBeat.toDouble()) * scaleB).coerceAtLeast(0L)
        } else {
            bBeat = -1
            bStart = Math.round(b.trimStartFrame * scaleB).coerceAtLeast(0L)
        }

        val spinback = p.bool(P.spinback)
        val preSource = if (spinback) min(brake, max(0L, aExit).toInt()) else 0
        val gap = Math.round(p.double(P.gapMs) / 1000.0 * sr).toInt()
        val bEnterOut = g + brake + gap
        val outFrames = bEnterOut + B_FADE_FRAMES + g
        val bEntry = bStart + (outFrames - bEnterOut)
        return Geometry(
            aExit = aExit, brakeStartA = brakeStartA, startBeat = startBeat, startKind = startKind,
            bStart = bStart, bBeat = bBeat, bEntry = bEntry,
            brake = brake, gap = gap, bEnterOut = bEnterOut, outFrames = outFrames, spinback = spinback,
            preSource = preSource,
        )
    }

    /** Platter speed (1 → 0) against output seconds, for the Lab. */
    private fun speedLane(geo: Geometry, p: Params, sr: Int): AutomationLane {
        val brake = VinylBrake(
            curve = BrakeCurve.entries.firstOrNull { it.name == p.choice(P.curve) } ?: BrakeCurve.POWER,
            exponent = p.double(P.exponent),
        )
        val g = Splice.GUARD_FRAMES
        val points = ArrayList<LanePoint>(SPEED_LANE_POINTS + 1)
        for (i in 0..SPEED_LANE_POINTS) {
            val t = i.toDouble() / SPEED_LANE_POINTS
            points += LanePoint((g + t * geo.brake) / sr, brake.speedAt(t) * (if (geo.spinback) -1.0 else 1.0))
        }
        points += LanePoint((geo.bEnterOut).toDouble() / sr, 0.0)
        return AutomationLane(LANE_SPEED, points)
    }

    /** The speed-tracking cutoff in Hz against output seconds, for the Lab. */
    private fun cutoffLane(geo: Geometry, p: Params, sr: Int): AutomationLane {
        val brake = VinylBrake(
            curve = BrakeCurve.entries.firstOrNull { it.name == p.choice(P.curve) } ?: BrakeCurve.POWER,
            exponent = p.double(P.exponent),
        )
        val g = Splice.GUARD_FRAMES
        val floorHz = p.double(P.lpfMinHz)
        val points = ArrayList<LanePoint>(SPEED_LANE_POINTS + 1)
        for (i in 0..SPEED_LANE_POINTS) {
            val t = i.toDouble() / SPEED_LANE_POINTS
            points += LanePoint((g + t * geo.brake) / sr, max(floorHz, LPF_TOP_HZ * brake.speedAt(t)))
        }
        return AutomationLane(LANE_LPF, points)
    }

    /** One point per A beat inside the segment (before the brake only: after it there is no tempo left). */
    private fun beatsALane(a: TrackAnalysis, geo: Geometry, sr: Int): AutomationLane {
        val points = ArrayList<LanePoint>()
        if (!a.grid.isEmpty) {
            val scaleA = sr / a.sampleRate.toDouble()
            var beat = max(0.0, kotlin.math.ceil(a.grid.beatAtFrame(Math.round(geo.aExit / scaleA)))).toInt()
            while (points.size <= MAX_LANE_POINTS) {
                val f = Math.round(a.grid.frameOfBeat(beat.toDouble()) * scaleA)
                if (f > geo.brakeStartA) break
                points += LanePoint((f - geo.aExit).toDouble() / sr, beat.toDouble())
                beat++
            }
        }
        return AutomationLane(LANE_BEATS_A, points)
    }

    companion object {
        const val ID = "brakeStop"

        /** `prefs.energy` gate: below this the planner is in "safe" mode and a brake is out of the question. */
        const val MIN_ENERGY = 0.5

        /** A's energy-weighted percussiveness over its last bars must exceed this (no brakes on ballads). */
        const val MIN_PERCUSSIVENESS = 0.4

        /** How adventurous this strategy is (DESIGN §5.3 `energyPref`). */
        const val AMBITION = 0.9

        const val MARKER_BRAKE = "brake"
        const val MARKER_SPINBACK = "spinback"
        const val MARKER_STOPPED = "stopped"
        const val MARKER_B_ENTERS = "B enters cold"
        const val LANE_SPEED = "platterSpeed"
        const val LANE_LPF = "lpfA"
        const val LANE_B = "gainB"
                /**
         * Informational lane: one point per beat of **A's own grid** inside the segment, for the Lab's ruler.
         *
         * It is deliberately not called `masterBeat`. This strategy is not beat-domain - no deck is slaved to a
         * [dev.muisc.transitions.core.MasterGrid], B is never stretched - so there is no grid that the whole
         * render is supposed to land on, and `ArtifactMetrics.beatAlignment*` (which asks exactly that question)
         * must not be computed for it.
         */
        const val LANE_BEATS_A = "beatsA"

        /** Cutoff of the speed-tracking low-pass at full speed. */
        const val LPF_TOP_HZ = 18000.0

        /** Bars of A whose percussiveness is inspected by the gate. */
        private const val TAIL_BARS = 16

        private const val LPF_Q = 0.7071067811865476
        private const val FILTER_BLOCK = 128
        private const val B_FADE_FRAMES = 220
        private const val REVERSE_BLEND_FRAMES = 64
        private const val SPEED_LANE_POINTS = 24
        private const val MAX_LANE_POINTS = 4096
    }
}
