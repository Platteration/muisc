package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fx.FdnReverb
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
import dev.muisc.dsp.gain.Curves
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * `phraseCut` — the **hard edit**: A is cut dead on a phrase boundary and B's downbeat starts on the very same
 * frame, joined by a 16 ms micro-blend that nobody can hear.
 *
 * ## What it sounds like
 * The last bar of A's phrase plays, optionally dipping a little ([P.preDuckDb]) as if someone reached for the
 * fader, and then — on the "one" — the next track is simply *there*. With [P.tailMs] > 0 a reverb of A's last
 * moment hangs over B's first bar and evaporates, which is the trick that makes a cut sound deliberate instead of
 * abrupt. This is the move for a track that ends on a stab going into one that starts cold, and for pairs whose
 * tempos are too far apart to beat-match: nothing is stretched, nothing is filtered, so there is no clash to hear.
 *
 * ## How the frames are chosen
 * The cut is the FIRST phrase start of A at or after `cues.mixOutBeat` (the last phrase where drums are still
 * playing) that leaves room for the pre-roll; later phrase starts, then plain downbeats, then `trimEnd` are the
 * fallbacks, each noted in `plan.notes`. B starts at `cues.mixInBeat` — a downbeat by construction — so in the
 * rendered segment A's phrase start and B's downbeat are the same output frame. One beat before the cut the
 * reverb send ramps up (and the optional duck happens), which is why the dry pre-roll stays bit-exact.
 *
 * ## Parameters
 *  - [P.tailMs] — length of A's reverb tail under B. 0 = a bone-dry butt-splice; 400 ms = a natural room; 1500 ms
 *    = a cavern that covers a whole bar of B. The tail is always forced to zero at its end so it cannot spill into
 *    B's body.
 *  - [P.tailDb] — level of that tail. −6 dB is present but polite; above −3 dB it starts to fight B's intro.
 *  - [P.tailDampHz] — darkness of the tail (one-pole damping in the FDN). Low values (2 kHz) sound like a distant
 *    hall, high values keep A's cymbals ringing.
 *  - [P.preDuckDb] — how far A dips over the beat before the cut. 0 = none (default: a cut should surprise), 3–6 dB
 *    = the classic "pull the fader down then cut".
 *  - [P.blendMs] — length of the equal-power micro-blend across the cut. 16 ms is inaudible; above ~50 ms it stops
 *    being a cut and starts being a very short crossfade.
 *  - [P.cutOnPhrase] — off restricts the search to plain downbeats (useful when phrase detection is wrong and the
 *    cut lands 8 bars too early).
 *
 * ## Failure modes
 *  - A wrong downbeat phase in A's grid cuts on beat 3: it sounds like a stumble. Gated on `gridConfidenceA ≥ 0.5`
 *    and weighted by it.
 *  - If B's `mixInBeat` is not actually a downbeat (bad analysis) the two grids meet out of phase; nothing here can
 *    detect that.
 *  - A cut into an ambient intro after a long fade-out sounds like a gap — that pair belongs to `outroIntroMinimal`,
 *    and the structural score below reflects it.
 */
class PhraseCutStrategy : TransitionStrategy {

    /** Tunables (see the class doc for what each one does musically). */
    object P : ParamSet(ID) {
        val tailMs = double("tailMs", "Reverb tail", 400.0, 0.0, 1500.0, "ms", "Length of A's reverb tail under B's first bar (0 = dry cut)")
        val tailDb = double("tailDb", "Tail level", -6.0, -30.0, 0.0, "dB", "Level of the reverb tail relative to A")
        val tailDampHz = double("tailDampHz", "Tail damping", 5000.0, 500.0, 16000.0, "Hz", "Damping of the FDN reverb: lower = darker, more distant tail")
        val preDuckDb = double("preDuckDb", "Pre-cut duck", 0.0, 0.0, 12.0, "dB", "How far A dips over the beat before the cut")
        val blendMs = double("blendMs", "Micro-blend", 16.0, 2.0, 60.0, "ms", "Equal-power blend across the cut; keeps the edit click-free")
        val cutOnPhrase = bool("cutOnPhrase", "Cut on a phrase", true, "Cut on a phrase start (off: any downbeat)")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Phrase cut"
    override val description: String get() = "Cuts A dead on a phrase start and drops B's downbeat on the same frame, with an optional reverb tail of A."
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val blockers = ArrayList<String>()
        if (a.grid.isEmpty) blockers += "A has no beat grid: there is no phrase to cut on"
        else if (features.gridConfidenceA < MIN_GRID_CONFIDENCE) {
            blockers += "A's grid confidence ${"%.2f".format(features.gridConfidenceA)} < $MIN_GRID_CONFIDENCE: the cut would land off the beat"
        }
        if (b.grid.isEmpty && b.cues.mixInBeat < 0) blockers += "B has neither a grid nor a mix-in cue: nothing to land on"
        if (blockers.isNotEmpty()) return Applicability.blocked(*blockers.toTypedArray())

        val reasons = ArrayList<String>()
        val sStruct = introScore(features.intro) * outroScore(features.outro)
        reasons += "intro ${features.intro} after outro ${features.outro} (structure ${"%.2f".format(sStruct)})"
        // A cut does not care about tempo — which is exactly why it wins when nothing can be beat-matched.
        val incompatible = !features.beatMatchable || features.stretchPercent > prefs.maxStretchPercent
        val sTempo = if (incompatible) 1.0 else 0.55
        reasons += if (incompatible) {
            "tempos not matchable (${"%.1f".format(features.stretchPercent)} % stretch): a cut avoids the clash"
        } else {
            "tempos would also blend (${"%.1f".format(features.stretchPercent)} % stretch), a cut is the safe choice"
        }
        val sGrid = features.gridConfidenceA
        val sVocal = 1.0 - features.vocalClash
        val score = 0.40 * sStruct + 0.25 * sTempo + 0.20 * sGrid + 0.15 * sVocal
        reasons.add(0, "phrase-aligned cut (score ${"%.2f".format(score)})")
        return Applicability.of(score, *reasons.toTypedArray())
    }

    /** How well B's head survives being started cold on a downbeat. */
    private fun introScore(intro: IntroType): Double = when (intro) {
        IntroType.COLD_START -> 1.0
        IntroType.BEAT_INTRO -> 0.95
        IntroType.VOCAL_INTRO -> 0.7
        IntroType.UNKNOWN -> 0.6
        IntroType.AMBIENT_INTRO -> 0.5
        IntroType.SILENCE -> 0.35
    }

    /** How well A's tail survives being cut off. */
    private fun outroScore(outro: OutroType): Double = when (outro) {
        OutroType.HARD_STOP -> 1.0
        OutroType.BEAT_OUTRO -> 0.95
        OutroType.UNKNOWN -> 0.75
        OutroType.VOCAL_OUTRO -> 0.7
        OutroType.AMBIENT_OUTRO -> 0.7
        OutroType.FADE_OUT -> 0.55
    }

    // -------------------------------------------------------------------------------------------------- plan

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val geo = geometry(a, b, p, prefs)
        val notes = ArrayList<String>()
        notes += "cut on A ${geo.cutKind} ${geo.cutBeat} (${"%.2f".format(geo.cutA / sr.toDouble())} s), " +
            "${"%.0f".format(p.double(P.blendMs))} ms equal-power micro-blend into B beat ${geo.bBeat}"
        notes += "B's downbeat lands on A's phrase start: both are output frame ${geo.cutOut}"
        notes += if (geo.tail > 0) {
            "A's last ${"%.2f".format(geo.preFrames / sr.toDouble())} s feed an FDN reverb; its ${"%.0f".format(p.double(P.tailMs))} ms tail " +
                "at ${"%.1f".format(p.double(P.tailDb))} dB runs under B and is forced to silence before the segment ends"
        } else {
            "no reverb tail (tailMs = 0): a dry butt-splice"
        }
        if (p.double(P.preDuckDb) > 0.0) notes += "A ducks ${"%.1f".format(p.double(P.preDuckDb))} dB over the beat before the cut"
        notes += geo.cutNote
        val lanes = ArrayList<AutomationLane>()
        val (laneA, laneB, laneWet) = lanes(geo)
        lanes += laneA.toAutomationLane(sr)
        lanes += laneB.toAutomationLane(sr)
        if (geo.tail > 0) lanes += laneWet.toAutomationLane(sr)
        lanes += masterBeatLane(a, geo, sr)
        return TransitionPlan(
            strategyId = ID,
            params = p,
            aExitFrame = geo.aExit,
            bEntryFrame = geo.bEntry,
            aWindow = FrameRange(geo.aExit, geo.cutA + geo.blend),
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
        val (laneA, laneB, laneWet) = lanes(geo)

        // 1. A, dry, up to the cut plus the micro-blend, with the optional pre-cut duck on top.
        val out = AudioBuffer.silence(sr, ch, geo.outFrames)
        Splice.addInPlace(out, input.aAudio, dstOffset = 0, gain = laneA, srcOffset = 0, frames = min(input.aAudio.frames, geo.cutOut + geo.blend))
        ctx.progress(0.3)

        // 2. A's reverb tail: the same (ducked) A signal through an FDN, wet only, mixed in after the cut.
        if (geo.tail > 0) {
            val wet = reverbTail(input.aAudio, laneA, geo, p, sr, ch)
            Splice.addInPlace(out, wet, dstOffset = geo.cutOut, gain = laneWet, srcOffset = 0, frames = geo.tail)
        }
        ctx.progress(0.7)

        // 3. B from its downbeat, fading in across the micro-blend only.
        Splice.addInPlace(out, input.bAudio, dstOffset = geo.cutOut, gain = laneB, srcOffset = 0, frames = input.bAudio.frames)
        ctx.progress(0.85)

        val fin = RenderReports.finalize(out, ctx)
        val markers = ArrayList<Marker>()
        if (geo.duckFrames > 0) markers += Marker((geo.cutOut - geo.duckFrames).toLong(), MARKER_PRE_CUT)
        markers += Marker(geo.cutOut.toLong(), MARKER_CUT)
        if (geo.tail > 0) markers += Marker((geo.cutOut + geo.tail).toLong(), MARKER_TAIL_END)
        val metrics = mapOf(
            "cutFrameA" to geo.cutA.toDouble(),
            "cutOutputFrame" to geo.cutOut.toDouble(),
            "cutBeat" to geo.cutBeat.toDouble(),
            "tailFrames" to geo.tail.toDouble(),
            "blendFrames" to geo.blend.toDouble(),
            "limited" to (if (fin.limited) 1.0 else 0.0),
            "gainReductionDb" to fin.gainReductionDb,
        )
        val report = RenderReports.build(out, (System.nanoTime() - t0) / 1_000_000, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        return RenderedTransition(plan, out, markers, report)
    }

    /**
     * Wet-only FDN reverb of A's contribution: the signal up to the cut is fed in (so the tail is a true reverb of
     * what was playing), then [Geometry.tail] frames of silence are pushed through and returned. The result is
     * enveloped by the wet lane in [render], which forces it to zero at the end of the tail.
     */
    private fun reverbTail(aAudio: AudioBuffer, laneA: Lane, geo: Geometry, p: Params, sr: Int, ch: Int): AudioBuffer {
        val reverb = FdnReverb(sr, ch)
        reverb.decaySeconds = max(0.05, geo.tail.toDouble() / sr)
        reverb.dampingHz = p.double(P.tailDampHz)
        reverb.mix = 1.0
        val feed = min(aAudio.frames, geo.cutOut)
        val block = 2048
        val dryBlock = Array(ch) { FloatArray(block) }
        val sink = Array(ch) { FloatArray(block) }
        val gains = FloatArray(block)
        var done = 0
        while (done < feed) {
            val n = min(block, feed - done)
            laneA.fillGains(gains, done.toLong(), n, 0)
            for (c in 0 until ch) {
                val src = aAudio[c]
                val dst = dryBlock[c]
                for (i in 0 until n) dst[i] = src[done + i] * gains[i]
            }
            reverb.process(dryBlock, sink, n)
            done += n
        }
        val tail = Array(ch) { FloatArray(geo.tail) }
        val silence = Array(ch) { FloatArray(block) }
        done = 0
        while (done < geo.tail) {
            val n = min(block, geo.tail - done)
            reverb.process(silence, tail, n, 0, done)
            done += n
        }
        val gain = Curves.dbToLinear(p.double(P.tailDb)).toFloat()
        for (c in 0 until ch) { val x = tail[c]; for (i in x.indices) x[i] *= gain }
        return AudioBuffer(sr, tail)
    }

    // ---------------------------------------------------------------------------------------------- geometry

    /** Frames and beats shared by [plan] and [render]; derived only from the analyses, params and prefs. */
    private class Geometry(
        val aExit: Long, val cutA: Long, val cutBeat: Int, val cutKind: String, val cutNote: String,
        val bStart: Long, val bBeat: Int, val bEntry: Long,
        val preFrames: Int, val duckFrames: Int, val blend: Int, val tail: Int, val outFrames: Int, val duckDb: Double,
    ) {
        /** Output frame of the cut: dry pre-roll + the bar of A that plays inside the segment. */
        val cutOut: Int get() = Splice.GUARD_FRAMES + preFrames
    }

    private fun geometry(a: TrackAnalysis, b: TrackAnalysis, p: Params, prefs: TransitionPrefs): Geometry {
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES.toLong()
        val scaleA = sr / a.sampleRate.toDouble()
        val scaleB = sr / b.sampleRate.toDouble()
        fun fa(f: Long) = Math.round(f * scaleA)
        val aTrimStart = fa(a.trimStartFrame)
        val aTrimEnd = fa(a.trimEndFrame)
        val blend = Math.round(p.double(P.blendMs) / 1000.0 * sr).toInt().coerceAtLeast(2)
        val beatFramesA = beatFrames(a, sr)
        val bpb = max(1, a.grid.beatsPerBar)
        // One bar of A plays inside the segment before the cut (the listener hears A lead into it and the reverb
        // has something to work with); the duck and the reverb send ramp over its LAST beat only.
        val preFrames = (beatFramesA * bpb).toInt().coerceIn(MIN_PRE_FRAMES, MAX_PRE_FRAMES)
        val duckFrames = beatFramesA.toInt().coerceIn(1, preFrames)

        // --- the cut point on A ---
        val need = g + preFrames
        var cutBeat = -1
        var cutKind = "phrase start"
        var cutNote = ""
        val grid = a.grid
        if (!grid.isEmpty) {
            val lastBeat = floor(grid.beatAtFrame(Math.round(aTrimEnd / scaleA))).toInt().coerceAtLeast(0)
            val fromBeat = if (a.cues.mixOutBeat >= 0) a.cues.mixOutBeat else max(0, lastBeat - DEFAULT_MIX_OUT_BARS * grid.beatsPerBar)
            val onPhrase = p.bool(P.cutOnPhrase)
            fun fits(beat: Int): Boolean {
                val f = fa(grid.frameOfBeat(beat.toDouble()))
                return f - need >= aTrimStart && f <= aTrimEnd
            }
            // First phrase start (or downbeat) at or after the mix-out cue that leaves room for the pre-roll.
            var c = if (onPhrase) grid.nextPhraseStart(fromBeat.toDouble()) else grid.nextDownbeat(fromBeat.toDouble())
            while (c <= lastBeat && !fits(c)) c = if (onPhrase) grid.nextPhraseStart((c + 1).toDouble()) else grid.nextDownbeat((c + 1).toDouble())
            if (c <= lastBeat) cutBeat = c
            if (cutBeat < 0 && onPhrase) {
                // No phrase start fits: fall back to plain downbeats, latest first.
                var d = grid.previousDownbeat(lastBeat.toDouble())
                while (d > 0 && !fits(d)) d = grid.previousDownbeat((d - 1).toDouble())
                if (d >= 0 && fits(d)) { cutBeat = d; cutKind = "downbeat"; cutNote = "no phrase start left room for the pre-roll: cut on downbeat $d" }
            }
            if (cutBeat < 0) {
                // Nothing on the grid fits at all (a very short track): cut where the music ends.
                cutKind = "trim end"
                cutNote = "A's grid offers no usable cut point: cutting at its trim end"
            } else if (cutNote.isEmpty()) {
                cutNote = "cut is the first $cutKind at or after A's mix-out cue ${a.cues.mixOutBeat}"
            }
        } else {
            cutKind = "trim end"
            cutNote = "A has no grid: cutting at its trim end"
        }
        val cutA = if (cutBeat >= 0) fa(grid.frameOfBeat(cutBeat.toDouble())) else max(aTrimStart + need, aTrimEnd)

        // --- B's entry ---
        val mixIn = b.cues.mixInBeat
        val bBeat: Int
        val bStart: Long
        if (mixIn >= 0 && !b.grid.isEmpty) {
            bBeat = mixIn
            bStart = Math.round(b.grid.frameOfBeat(mixIn.toDouble()) * scaleB).coerceAtLeast(0L)
        } else if (!b.grid.isEmpty) {
            bBeat = if (b.cues.firstDownbeat >= 0) b.cues.firstDownbeat else b.grid.nextDownbeat(b.grid.beatAtFrame(b.trimStartFrame))
            bStart = Math.round(b.grid.frameOfBeat(bBeat.toDouble()) * scaleB).coerceAtLeast(0L)
        } else {
            bBeat = -1
            bStart = Math.round(b.trimStartFrame * scaleB).coerceAtLeast(0L)
        }

        val tail = Math.round(p.double(P.tailMs) / 1000.0 * sr).toInt()
        val aExit = cutA - need
        val cutOut = Splice.GUARD_FRAMES + preFrames
        val outFrames = cutOut + tail + Splice.GUARD_FRAMES
        val bEntry = bStart + (outFrames - cutOut)
        return Geometry(
            aExit = aExit, cutA = cutA, cutBeat = cutBeat, cutKind = cutKind, cutNote = cutNote,
            bStart = bStart, bBeat = bBeat, bEntry = bEntry,
            preFrames = preFrames, duckFrames = duckFrames, blend = blend, tail = tail, outFrames = outFrames,
            duckDb = p.double(P.preDuckDb),
        )
    }

    /** Beat length of A in engine frames (from the grid's bpm, falling back to the tempo estimate). */
    private fun beatFrames(a: TrackAnalysis, sr: Int): Double {
        val bpm = if (a.grid.bpm > 0) a.grid.bpm else a.tempo.bpm
        return if (bpm > 0) 60.0 * sr / bpm else sr / 2.0
    }

    /**
     * A's gain (unity, optional duck, equal-power out across the blend), B's gain (equal-power in across the blend)
     * and the reverb send / tail envelope (up over the pre-cut beat, then forced to zero at the end of the tail).
     */
    private fun lanes(geo: Geometry): Triple<Lane, Lane, Lane> {
        val g = Splice.GUARD_FRAMES.toLong()
        val cut = geo.cutOut.toLong()
        val duck = Curves.dbToLinear(-geo.duckDb)
        val duckStart = (cut - geo.duckFrames).coerceAtLeast(g)
        val a = Lane(LANE_A).add(0L, 1.0).add(duckStart, 1.0, FadeLaw.S_CURVE).add(cut, duck, FadeLaw.EQUAL_POWER).add(cut + geo.blend, 0.0)
        val b = Lane(LANE_B).add(cut, 0.0, FadeLaw.EQUAL_POWER).add(cut + geo.blend, 1.0)
        // The wet lane is sampled in OUTPUT frames (Splice.addInPlace samples gains in destination frames): the send
        // opens over the last beat before the cut (so the tail is already ringing when the dry stops) and its last
        // quarter is an explicit release to exactly 0.0, which is what contains the tail inside the segment.
        val release = (geo.tail / 4).coerceAtLeast(1)
        val wet = Lane(LANE_TAIL)
            .add(duckStart, 0.0, FadeLaw.EQUAL_POWER)
            .add(cut, 1.0)
            .add(cut + geo.tail - release, 1.0, FadeLaw.EXP)
            .add(cut + geo.tail, 0.0)
        return Triple(a, b, wet)
    }

    /** One point per A beat inside the segment: `value` is A's absolute beat index, `outputSec` where it lands. */
    private fun masterBeatLane(a: TrackAnalysis, geo: Geometry, sr: Int): AutomationLane {
        val points = ArrayList<LanePoint>()
        if (!a.grid.isEmpty) {
            val scaleA = sr / a.sampleRate.toDouble()
            val first = kotlin.math.ceil(a.grid.beatAtFrame(Math.round(geo.aExit / scaleA))).toInt()
            var beat = max(0, first)
            while (true) {
                val f = Math.round(a.grid.frameOfBeat(beat.toDouble()) * scaleA)
                if (f > geo.cutA) break
                points += LanePoint((f - geo.aExit).toDouble() / sr, beat.toDouble())
                beat++
                if (points.size > MAX_LANE_POINTS) break
            }
        }
        return AutomationLane(LANE_MASTER_BEAT, points)
    }

    companion object {
        const val ID = "phraseCut"
        const val MIN_GRID_CONFIDENCE = 0.5
        const val MARKER_PRE_CUT = "pre-cut (duck / reverb send)"
        const val MARKER_CUT = "cut: B enters"
        const val MARKER_TAIL_END = "reverb tail ends"
        const val LANE_A = "gainA"
        const val LANE_B = "gainB"
        const val LANE_TAIL = "reverbTail"
        const val LANE_MASTER_BEAT = "masterBeat"

        /** Bars before the end of A used as the mix-out point when the analysis has no cue. */
        private const val DEFAULT_MIX_OUT_BARS = 8

        /** The bar of A that plays inside the segment before the cut, clamped to 0.1 s .. 4 s. */
        private const val MIN_PRE_FRAMES = 4410
        private const val MAX_PRE_FRAMES = 176400
        private const val MAX_LANE_POINTS = 4096
    }
}
