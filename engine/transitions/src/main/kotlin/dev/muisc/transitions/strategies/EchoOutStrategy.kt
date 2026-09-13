package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fx.Delay
import dev.muisc.dsp.gain.Curves
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * `echoOut` — the **beat-synced echo tail**: at the end of A's last phrase the dry signal is pulled out and what
 * is left of the track keeps bouncing around a feedback delay locked to A's own tempo, while B walks in on the
 * next downbeat underneath the decaying repeats.
 *
 * ## What it sounds like
 * The last stab of A repeats — dotted eighths by default, so the repeats fall *between* the beats and pull the ear
 * forward — each one a little quieter and a little duller than the last (a low-pass in the feedback loop), until
 * they dissolve behind the track that has quietly started. It is the single most useful "get me out of here" move
 * a DJ has: it works at any tempo relation, needs nothing from B but a downbeat, and the tail is a musical object
 * in its own right, so the listener never feels a hole.
 *
 * ## How the frames are chosen
 * The cut is A's last phrase start that still leaves [P.preRollBars] bars of material to feed the delay (the
 * repeats have to be echoes of *something*). One beat before it the wet send ramps up so that the tail is already
 * ringing when the dry stops, which is why there is no step at the cut. The tail's length is computed from the
 * feedback: `feedback^n = -60 dB` gives `n` audible repeats, and the tail is at least [P.tailBars] bars long; its
 * last quarter is an explicit release to digital silence, so the tail can never bleed into B's body. B enters on
 * the first downbeat of A's grid after the cut plus [P.bEnterOnBeat] beats.
 *
 * ## Parameters
 *  - [P.delayBeats] — 0.5 (eighths: driving), 0.75 (dotted eighth: the classic, syncopated pull) or 1.0 (quarter:
 *    spacious, but below ~80 BPM a whole beat starts to sound like a slap-back on its own).
 *  - [P.feedback] — how many repeats. 0.6 is three or four, 0.85 is a wash that outlives B's first bar.
 *  - [P.dampHz] — one-pole low-pass inside the loop: the repeats get darker as they decay. 2 kHz is a dub echo,
 *    16 kHz keeps the hats crisp (and the tail busier). A fixed 120 Hz high-pass keeps the sub out of the loop so
 *    the tail never fights B's kick.
 *  - [P.tailBars] — musical minimum length of the tail; the decay computation can make it longer, never shorter.
 *  - [P.bEnterOnBeat] — beats after the cut at which B is allowed in, rounded up to A's next downbeat. 0 means the
 *    first downbeat strictly after the cut (one bar of pure tail).
 *  - [P.wetRampBeats] — how long the send takes to open before the cut. Long ramps (2 beats) sound like the echo
 *    was always there; 0 is an abrupt FX slam (still click-free, but you hear the effect arrive).
 *  - [P.wetDb] — level of the whole tail against A. Above 0 dB the repeats are louder than the track was, which is
 *    a deliberate (and very 1990s) effect; below −6 dB the tail becomes a polite hand-over.
 *  - [P.preRollBars] — bars of A inside the segment before the cut. This is also what feeds the delay line, so it
 *    must be at least one delay time long; more bars simply mean more of A is heard before the effect.
 *
 * ## Failure modes
 *  - Very low BPM with `delayBeats = 1.0` sounds like a broken slap-back; the planner should prefer 0.5 below
 *    ~80 BPM (this strategy only reports it in the notes).
 *  - A dense, loud A produces a muddy tail: the 120 Hz high-pass and the damping help, but on a wall-of-sound
 *    outro the repeats smear. Lower [P.feedback].
 *  - If A's last phrase start is wrong the cut lands mid-phrase and the repeats fall on the wrong side of the
 *    beat — gated on `gridConfidenceA`.
 *  - B arrives at full level under a tail that has already decayed, so the artifact detector reports a level jump
 *    at its entry. That jump is the transition: it is the downbeat everything has been waiting for, and only the
 *    5 ms declick stands between the tail and B's first frame.
 */
class EchoOutStrategy : TransitionStrategy {

    /** Tunables (see the class doc for what each one does musically). */
    object P : ParamSet(ID) {
        val delayBeats = choice("delayBeats", "Delay", "0.75", listOf("0.5", "0.75", "1.0"), "Echo spacing in beats of A: eighth, dotted eighth (default) or quarter")
        val feedback = double("feedback", "Feedback", 0.72, 0.3, 0.9, "", "Loop gain: how many repeats survive")
        val dampHz = double("dampHz", "Damping", 4000.0, 500.0, 16000.0, "Hz", "Low-pass inside the feedback loop: the repeats get darker as they decay")
        val tailBars = int("tailBars", "Tail", 2, 0, 8, "bars", "Musical minimum length of the tail (the decay to −60 dB can make it longer)")
        val bEnterOnBeat = int("bEnterOnBeat", "B enters", 0, 0, 16, "beats", "Beats after the cut before B may enter, rounded up to A's next downbeat")
        val wetRampBeats = double("wetRampBeats", "Send ramp", 1.0, 0.0, 4.0, "beats", "How long the echo send takes to open before the cut")
        val preRollBars = int("preRollBars", "Pre-roll", 1, 1, 4, "bars", "Bars of A played (and fed to the delay) before the cut")
        val wetDb = double("wetDb", "Tail level", 0.0, -18.0, 6.0, "dB", "Level of the echo tail relative to A")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Echo out"
    override val description: String get() = "Cuts A at a phrase end into a tempo-locked feedback delay and brings B in on the next downbeat under the decaying repeats."
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val blockers = ArrayList<String>()
        if (a.grid.isEmpty) blockers += "A has no beat grid: the echo cannot be locked to its tempo"
        else if (features.gridConfidenceA < MIN_GRID_CONFIDENCE) {
            blockers += "A's grid confidence ${"%.2f".format(features.gridConfidenceA)} < $MIN_GRID_CONFIDENCE: the repeats would fall off the beat"
        }
        val bpmA = if (a.grid.bpm > 0) a.grid.bpm else a.tempo.bpm
        if (bpmA <= 0) blockers += "A has no tempo: the delay has no beat length"
        if (blockers.isNotEmpty()) return Applicability.blocked(*blockers.toTypedArray())

        val reasons = ArrayList<String>()
        // Any tempo relation works — B is never stretched — so an unmatched pair is a reason FOR the echo.
        val incompatible = !features.beatMatchable || features.stretchPercent > prefs.maxStretchPercent
        val sTempo = if (incompatible) 1.0 else 0.75
        reasons += if (incompatible) {
            "tempos not matchable (${"%.1f".format(features.stretchPercent)} % stretch): the tail covers the change"
        } else {
            "tempos ${"%.1f".format(features.stretchPercent)} % apart"
        }
        val sStruct = introScore(features.intro)
        reasons += "B's intro is ${features.intro} (structure ${"%.2f".format(sStruct)})"
        val sGrid = features.gridConfidenceA
        // A dense, very loud A smears; a tail over a quiet intro is cleanest.
        val sEnergy = 1.0 - 0.4 * max(0.0, features.energyDelta)
        val sVocal = 1.0 - 0.5 * features.vocalClash
        // Below ~80 BPM a whole-beat echo is a slap-back; a dotted eighth stays musical much lower.
        val sTempoRange = if (bpmA < SLOW_BPM) 0.8 else 1.0
        if (bpmA < SLOW_BPM) reasons += "A is slow (${"%.0f".format(bpmA)} BPM): prefer delayBeats 0.5"
        val score = (0.30 * sTempo + 0.25 * sStruct + 0.20 * sGrid + 0.15 * sEnergy + 0.10 * sVocal) * sTempoRange
        reasons.add(0, "beat-synced echo tail (score ${"%.2f".format(score)})")
        return Applicability.of(score, *reasons.toTypedArray())
    }

    /** How well B's head sits under a decaying tail. */
    private fun introScore(intro: IntroType): Double = when (intro) {
        IntroType.BEAT_INTRO -> 1.0
        IntroType.COLD_START -> 0.9
        IntroType.AMBIENT_INTRO -> 0.8
        IntroType.VOCAL_INTRO -> 0.6
        IntroType.UNKNOWN -> 0.6
        IntroType.SILENCE -> 0.5
    }

    // -------------------------------------------------------------------------------------------------- plan

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val geo = geometry(a, b, p, prefs)
        val notes = ArrayList<String>()
        notes += "cut on A's ${geo.cutKind} ${geo.cutBeat} after ${p.int(P.preRollBars)} bar(s) of A"
        notes += "${p.choice(P.delayBeats)}-beat echo = ${"%.1f".format(geo.delayFrames * 1000.0 / sr)} ms at ${"%.1f".format(geo.bpmA)} BPM, " +
            "feedback ${"%.2f".format(p.double(P.feedback))}, damping ${"%.0f".format(p.double(P.dampHz))} Hz, 120 Hz high-pass in the loop"
        notes += "tail ${"%.2f".format(geo.tail / sr.toDouble())} s = ${geo.echoes} repeats to −60 dB (minimum ${p.int(P.tailBars)} bars), " +
            "released to silence over its last quarter so nothing spills into B"
        notes += "B enters on A's downbeat ${geo.bEnterBeat} (${"%.2f".format((geo.bEnterOut - geo.cutOut).toDouble() / sr)} s after the cut) at its beat ${geo.bBeat}"
        if (geo.bpmA < SLOW_BPM && p.choice(P.delayBeats) == "1.0") notes += "a 1-beat echo at ${"%.0f".format(geo.bpmA)} BPM will sound like a slap-back"
        val lanes = ArrayList<AutomationLane>()
        val (dry, wet, bGain) = lanes(geo)
        lanes += dry.toAutomationLane(sr)
        lanes += wet.toAutomationLane(sr)
        lanes += bGain.toAutomationLane(sr)
        lanes += masterBeatLane(a, geo, sr)
        return TransitionPlan(
            strategyId = ID,
            params = p,
            aExitFrame = geo.aExit,
            bEntryFrame = geo.bEntry,
            aWindow = FrameRange(geo.aExit, geo.cutA),
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
        val (dryLane, wetLane, bLane) = lanes(geo)

        val out = AudioBuffer.silence(sr, ch, geo.outFrames)
        // 1. A dry up to the cut (the pre-roll is untouched: the dry lane is 1.0 there).
        Splice.addInPlace(out, input.aAudio, dstOffset = 0, gain = dryLane, srcOffset = 0, frames = min(input.aAudio.frames, geo.cutOut))
        ctx.progress(0.2)

        // 2. The echo: A's dry signal through the delay, cut at the same frame, ringing on for the tail.
        val delay = Delay(sr, ch, maxDelaySeconds = max(2.0, geo.delayFrames / sr.toDouble() * 2.0))
        delay.setDelayFrames(geo.delayFrames.toDouble(), immediate = true)
        delay.feedback = p.double(P.feedback)
        delay.mix = 1.0 // wet only: the dry path above is the one the listener hears before the cut
        delay.setLowPass(p.double(P.dampHz))
        delay.setHighPass(LOOP_HIGH_PASS_HZ)
        val feed = min(input.aAudio.frames, geo.cutOut)
        val echo = delay.echoOut(sliceOf(input.aAudio, feed), feed, geo.tail)
        Splice.addInPlace(out, echo, dstOffset = 0, gain = wetLane, srcOffset = 0, frames = echo.frames)
        ctx.progress(0.7)

        // 3. B from its downbeat, with a short declick, dry from then on.
        Splice.addInPlace(out, input.bAudio, dstOffset = geo.bEnterOut, gain = bLane, srcOffset = 0, frames = input.bAudio.frames)
        ctx.progress(0.85)

        val fin = RenderReports.finalize(out, ctx)
        val markers = listOf(
            Marker(geo.cutOut.toLong(), MARKER_CUT),
            Marker(geo.bEnterOut.toLong(), MARKER_B_ENTERS),
            Marker((geo.cutOut + geo.tail).toLong(), MARKER_TAIL_END),
        )
        val metrics = mapOf(
            "delayFrames" to geo.delayFrames.toDouble(),
            "echoes" to geo.echoes.toDouble(),
            "tailFrames" to geo.tail.toDouble(),
            "cutOutputFrame" to geo.cutOut.toDouble(),
            "bEnterOutputFrame" to geo.bEnterOut.toDouble(),
            "limited" to (if (fin.limited) 1.0 else 0.0),
            "gainReductionDb" to fin.gainReductionDb,
        )
        val report = RenderReports.build(out, (System.nanoTime() - t0) / 1_000_000, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        return RenderedTransition(plan, out, markers, report)
    }

    /** The first [frames] frames of [src] as a buffer (the delay is fed exactly A's contribution up to the cut). */
    private fun sliceOf(src: AudioBuffer, frames: Int): AudioBuffer =
        if (frames == src.frames) src else AudioBuffer(src.sampleRate, Array(src.channelCount) { src[it].copyOfRange(0, frames) })

    // ---------------------------------------------------------------------------------------------- geometry

    /** Frames and beats shared by [plan] and [render]. */
    private class Geometry(
        val aExit: Long, val cutA: Long, val cutBeat: Int, val cutKind: String,
        val bStart: Long, val bBeat: Int, val bEntry: Long, val bEnterBeat: Int,
        val cutOut: Int, val bEnterOut: Int, val outFrames: Int,
        val delayFrames: Int, val tail: Int, val echoes: Int, val wetRamp: Int, val wetLevel: Double,
        val bpmA: Double, val beatFramesA: Double,
    )

    private fun geometry(a: TrackAnalysis, b: TrackAnalysis, p: Params, prefs: TransitionPrefs): Geometry {
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES
        val scaleA = sr / a.sampleRate.toDouble()
        val scaleB = sr / b.sampleRate.toDouble()
        fun fa(f: Long) = Math.round(f * scaleA)
        fun fbFrame(f: Long) = Math.round(f * scaleB)
        val aTrimStart = fa(a.trimStartFrame)
        val aTrimEnd = fa(a.trimEndFrame)
        val bpmA = if (a.grid.bpm > 0) a.grid.bpm else a.tempo.bpm
        val bpb = max(1, a.grid.beatsPerBar)
        val beatFramesA = if (bpmA > 0) 60.0 * sr / bpmA else sr / 2.0
        val barA = beatFramesA * bpb
        val pre = Math.round(p.int(P.preRollBars) * barA).toInt()
        val need = (g + pre).toLong()

        // Cut: A's LAST phrase start that still has `pre` bars of material in front of it.
        val grid = a.grid
        var cutBeat = -1
        var cutKind = "phrase start"
        if (!grid.isEmpty) {
            val lastBeat = floor(grid.beatAtFrame(Math.round(aTrimEnd / scaleA))).toInt().coerceAtLeast(0)
            fun fits(beat: Int): Boolean {
                val f = fa(grid.frameOfBeat(beat.toDouble()))
                return f - need >= aTrimStart && f <= aTrimEnd
            }
            var c = grid.previousPhraseStart(lastBeat.toDouble())
            while (c > 0 && !fits(c)) c = grid.previousPhraseStart((c - 1).toDouble())
            if (c >= 0 && fits(c)) cutBeat = c
            if (cutBeat < 0) {
                var d = grid.previousDownbeat(lastBeat.toDouble())
                while (d > 0 && !fits(d)) d = grid.previousDownbeat((d - 1).toDouble())
                if (d >= 0 && fits(d)) { cutBeat = d; cutKind = "downbeat" }
            }
        }
        val cutA = if (cutBeat >= 0) fa(grid.frameOfBeat(cutBeat.toDouble())) else max(aTrimStart + need, aTrimEnd)
        if (cutBeat < 0) cutKind = "trim end"
        val aExit = cutA - need
        val cutOut = g + pre

        // Delay time and tail length.
        val beats = p.choice(P.delayBeats).toDoubleOrNull() ?: 0.75
        val delayFrames = Math.round(beats * beatFramesA).toInt().coerceAtLeast(Delay.MIN_DELAY)
        val fb = p.double(P.feedback)
        // feedback^n = 10^(-60/20) -> n = 3 / -log10(feedback); the loop filters make the real decay faster.
        val echoes = ceil(3.0 / -log10(fb.coerceIn(0.01, 0.99))).toInt().coerceIn(1, MAX_ECHOES)
        val decay = echoes.toLong() * delayFrames
        val tail = max(Math.round(p.int(P.tailBars) * barA), decay).coerceAtMost(Math.round(MAX_TAIL_SEC * sr)).toInt()

        // B enters on A's next downbeat at or after cut + bEnterOnBeat beats (strictly after the cut).
        val enterBeat = if (cutBeat >= 0 && !grid.isEmpty) {
            val from = cutBeat + max(1, p.int(P.bEnterOnBeat))
            grid.nextDownbeat(from.toDouble())
        } else {
            -1
        }
        val enterOffset = if (enterBeat >= 0) (fa(grid.frameOfBeat(enterBeat.toDouble())) - cutA).toInt() else Math.round(barA).toInt()
        val bEnterOut = cutOut + max(MIN_B_OFFSET, enterOffset)

        // B's own entry point: its mix-in cue (a downbeat), else its first downbeat / trim start.
        val bBeat: Int
        val bStart: Long
        if (!b.grid.isEmpty && b.cues.mixInBeat >= 0) {
            bBeat = b.cues.mixInBeat
            bStart = fbFrame(b.grid.frameOfBeat(bBeat.toDouble()))
        } else if (!b.grid.isEmpty) {
            bBeat = if (b.cues.firstDownbeat >= 0) b.cues.firstDownbeat else b.grid.nextDownbeat(b.grid.beatAtFrame(b.trimStartFrame))
            bStart = fbFrame(b.grid.frameOfBeat(bBeat.toDouble()))
        } else {
            bBeat = -1
            bStart = max(0L, fbFrame(b.trimStartFrame))
        }

        val outFrames = max(cutOut + tail, bEnterOut + B_FADE_FRAMES) + g
        val bEntry = bStart + (outFrames - bEnterOut)
        val wetRamp = Math.round(p.double(P.wetRampBeats) * beatFramesA).toInt().coerceIn(0, pre)
        return Geometry(
            aExit = aExit, cutA = cutA, cutBeat = cutBeat, cutKind = cutKind,
            bStart = bStart, bBeat = bBeat, bEntry = bEntry, bEnterBeat = enterBeat,
            cutOut = cutOut, bEnterOut = bEnterOut, outFrames = outFrames,
            delayFrames = delayFrames, tail = tail, echoes = echoes, wetRamp = wetRamp,
            wetLevel = Curves.dbToLinear(p.double(P.wetDb)), bpmA = bpmA, beatFramesA = beatFramesA,
        )
    }

    /**
     * Dry A (unity, then cut with a short declick), the wet send (up over the last beats before the cut, held
     * through the tail, released to exactly 0), and B's gain (a declick fade at its entry, then unity).
     * All three are sampled in OUTPUT frames.
     */
    private fun lanes(geo: Geometry): Triple<Lane, Lane, Lane> {
        val cut = geo.cutOut.toLong()
        val dry = Lane(LANE_DRY).add(0L, 1.0).add(cut, 1.0, FadeLaw.LINEAR).add(cut + CUT_FADE_FRAMES, 0.0)
        val wetStart = (cut - geo.wetRamp).coerceAtLeast(Splice.GUARD_FRAMES.toLong())
        val release = (geo.tail / 4).coerceAtLeast(1)
        val level = geo.wetLevel
        val wet = Lane(LANE_WET)
            .add(wetStart, 0.0, FadeLaw.EQUAL_POWER)
            .add(cut, level)
            .add(cut + geo.tail - release, level, FadeLaw.EXP)
            .add(cut + geo.tail, 0.0)
        val b = Lane(LANE_B).add(geo.bEnterOut.toLong(), 0.0, FadeLaw.EQUAL_POWER).add((geo.bEnterOut + B_FADE_FRAMES).toLong(), 1.0)
        return Triple(dry, wet, b)
    }

    /** One point per A beat inside the segment: `value` is A's absolute beat index, `outputSec` where it lands. */
    private fun masterBeatLane(a: TrackAnalysis, geo: Geometry, sr: Int): AutomationLane {
        val points = ArrayList<LanePoint>()
        if (!a.grid.isEmpty) {
            val scaleA = sr / a.sampleRate.toDouble()
            var beat = max(0.0, ceil(a.grid.beatAtFrame(Math.round(geo.aExit / scaleA)))).toInt()
            while (points.size <= MAX_LANE_POINTS) {
                val f = Math.round(a.grid.frameOfBeat(beat.toDouble()) * scaleA)
                val outSec = (f - geo.aExit).toDouble() / sr
                if (outSec * sr > geo.outFrames) break
                points += LanePoint(outSec, beat.toDouble())
                beat++
            }
        }
        return AutomationLane(LANE_MASTER_BEAT, points)
    }

    companion object {
        const val ID = "echoOut"
        const val MIN_GRID_CONFIDENCE = 0.5
        const val MARKER_CUT = "A cut into the echo"
        const val MARKER_B_ENTERS = "B enters under the tail"
        const val MARKER_TAIL_END = "tail released"
        const val LANE_DRY = "gainA"
        const val LANE_WET = "echoTail"
        const val LANE_B = "gainB"
        const val LANE_MASTER_BEAT = "masterBeat"

        /** High-pass inside the feedback loop: keeps A's sub out of the tail so it never fights B's kick. */
        const val LOOP_HIGH_PASS_HZ = 120.0

        /** Below this tempo a whole-beat echo degenerates into a slap-back. */
        const val SLOW_BPM = 80.0

        /** 10 ms linear declick on the dry cut (the wet is a delayed copy of it: coherent material). */
        private const val CUT_FADE_FRAMES = 441L

        /** 5 ms equal-power declick on B's entry. */
        private const val B_FADE_FRAMES = 220

        private const val MIN_B_OFFSET = 2048
        private const val MAX_ECHOES = 64
        private const val MAX_TAIL_SEC = 20.0
        private const val MAX_LANE_POINTS = 4096
    }
}
