package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * `outroIntroMinimal` — **respect the producers**: the two tracks already end and begin the way their makers
 * intended, so the only thing this strategy does is decide *when* B starts and let A's own fade-out finish
 * underneath it.
 *
 * ## What it sounds like
 * A's outro plays completely untouched (no EQ, no stretch, no filter). Somewhere in its dying seconds — the moment
 * A's short-term loudness drops through [P.thresholdLufs], i.e. the point where the producer's fade has effectively
 * done its job — B's intro slides in under a short ([P.bFadeBars]-bar) fade. A keeps going for [P.overlapSec]
 * seconds, its last whisper dissolving behind B's first pad or vocal. Done well nobody can name the moment the
 * songs changed; it sounds like a radio edit, not like a DJ.
 *
 * ## How the frames are chosen
 * `loudness.shortTermLufs[k]` is the BS.1770 3 s window ending at `(k + 30) * shortTermHopSec`; we take the window
 * *centre* (`(k + 15) * hop`) as the instant the music actually reached that level, and use the LAST index that is
 * still at or above the threshold — the crossing. B enters [P.holdAfterSec] after it (negative = before it). The
 * overlap is clamped so it fits between A's trim points, which is the honest failure mode below.
 *
 * ## Parameters
 *  - [P.overlapSec] — how long both tracks are audible. Short (2 s) = almost a butt-splice; long (6–8 s) = the two
 *    atmospheres properly dissolve into each other. A is faded out across exactly this window.
 *  - [P.thresholdLufs] — how quiet A must get before B is allowed in. Raise it (−20) to start B while A is still
 *    clearly audible (busier, more "DJ"), lower it (−40) to wait for near-silence (more respectful, risks a gap).
 *  - [P.bFadeBars] — length of B's fade-in in bars of B's own grid. 0 gives a 5 ms declick only (B starts dry).
 *  - [P.holdAfterSec] — nudge B's entry later (+) or earlier (−) than the crossing.
 *  - [P.aFadeLaw] — law of A's fade across the overlap. `S_CURVE` (default) is the gentlest on an already-fading
 *    tail; `EQUAL_POWER` keeps the sum louder; `LINEAR` is the most transparent when A is nearly silent anyway.
 *  - [P.bStartAtMixIn] — start B at its `mixInBeat` cue (default) or at its trim start (the very first sound).
 *
 * ## Failure modes
 *  - A long fade-out into a *loud cold* intro sounds like a hole: blocked here (`COLD_START`), the planner steers
 *    those pairs to `phraseCut`.
 *  - When A has no short-term loudness curve the entry falls back to `trimEnd − overlap`, which for a track that
 *    does not actually fade means B enters over full-level material — musically this is then just a short crossfade.
 *  - Beats are ignored on purpose. If both tracks are rhythmic the overlap can clash; that is what
 *    `beatMatchedBlend` is for, and why the structural score below dominates.
 */
class OutroIntroMinimalStrategy : TransitionStrategy {

    /** Tunables (see the class doc for what each one does musically). */
    object P : ParamSet(ID) {
        val overlapSec = double("overlapSec", "Overlap", 4.0, 1.0, 8.0, "s", "Seconds during which both tracks are audible; A is faded out across exactly this window")
        val thresholdLufs = double("thresholdLufs", "Entry threshold", -30.0, -60.0, -10.0, "LUFS", "B enters when A's short-term loudness has fallen through this level")
        val bFadeBars = int("bFadeBars", "B fade-in", 1, 0, 4, "bars", "Length of B's fade-in in bars of B's grid (0 = 5 ms declick only)")
        val holdAfterSec = double("holdAfterSec", "Hold after crossing", 0.0, -4.0, 4.0, "s", "Delay (+) or advance (−) of B's entry relative to the loudness crossing")
        val aFadeLaw = choice("aFadeLaw", "A fade law", FadeLaw.S_CURVE, listOf(FadeLaw.S_CURVE, FadeLaw.LINEAR, FadeLaw.EQUAL_POWER, FadeLaw.EXP), "Gain law of A's fade across the overlap")
        val bStartAtMixIn = bool("bStartAtMixIn", "Start B at mix-in cue", true, "Start B at its mixInBeat cue when known, else at its trim start")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Outro / intro"
    override val description: String get() = "Lets A's own fade-out finish while B's intro enters underneath it: no EQ, no stretch, no beat-matching."
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val blockers = ArrayList<String>()
        if (features.outro == OutroType.HARD_STOP) blockers += "A stops hard: there is no outro to hide B under"
        if (features.intro == IntroType.COLD_START) blockers += "B starts cold: an overlap would sound like a mistake (use phraseCut)"
        val defaultOverlap = P.defaults().double(P.overlapSec)
        val roomSec = (a.trimEndFrame - a.trimStartFrame).toDouble() / max(1, a.sampleRate)
        if (roomSec < defaultOverlap) blockers += "A has only ${"%.1f".format(roomSec)} s of audio, less than the ${"%.0f".format(defaultOverlap)} s overlap"
        if (blockers.isNotEmpty()) return Applicability.blocked(*blockers.toTypedArray())

        val reasons = ArrayList<String>()
        val sStruct = outroScore(features.outro) * introScore(features.intro)
        reasons += "outro ${features.outro} into intro ${features.intro} (structure ${"%.2f".format(sStruct)})"
        val sEnergy = exp(-square(features.loudnessDeltaLu / 6.0)) * (1.0 - 0.5 * abs(features.energyDelta))
        if (abs(features.loudnessDeltaLu) > 3.0) reasons += "B is ${"%.1f".format(features.loudnessDeltaLu)} LU ${if (features.loudnessDeltaLu > 0) "louder" else "quieter"}"
        val sVocal = 1.0 - features.vocalClash
        if (features.vocalClash > 0.3) reasons += "vocals overlap (clash ${"%.2f".format(features.vocalClash)})"
        // Room: B only needs its fade-in, A only needs the overlap; both are seconds, not bars, so this is generous.
        val sRoom = min(1.0, features.introBeatsAvailable.coerceAtLeast(0) / 4.0)
        if (features.introBeatsAvailable in 0..3) reasons += "B's intro is only ${features.introBeatsAvailable} beats long"
        val hasCurve = a.loudness.shortTermLufs.isNotEmpty()
        if (!hasCurve) reasons += "A has no short-term loudness curve: entry falls back to trimEnd − overlap"
        val score = (0.50 * sStruct + 0.20 * sEnergy + 0.15 * sVocal + 0.15 * sRoom) * (if (hasCurve) 1.0 else 0.8)
        reasons.add(0, "producers' edges respected (score ${"%.2f".format(score)})")
        return Applicability.of(score, *reasons.toTypedArray())
    }

    /** How much of an outro A leaves to work with. */
    private fun outroScore(outro: OutroType): Double = when (outro) {
        OutroType.FADE_OUT -> 1.0
        OutroType.AMBIENT_OUTRO -> 0.95
        OutroType.VOCAL_OUTRO -> 0.7
        OutroType.BEAT_OUTRO -> 0.4
        OutroType.UNKNOWN -> 0.5
        OutroType.HARD_STOP -> 0.0
    }

    /** How gracefully B's head accepts being faded up under something else. */
    private fun introScore(intro: IntroType): Double = when (intro) {
        IntroType.AMBIENT_INTRO -> 1.0
        IntroType.SILENCE -> 0.95
        IntroType.VOCAL_INTRO -> 0.85
        IntroType.BEAT_INTRO -> 0.45
        IntroType.UNKNOWN -> 0.5
        IntroType.COLD_START -> 0.0
    }

    private fun square(x: Double) = x * x

    // -------------------------------------------------------------------------------------------------- plan

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES.toLong()
        val notes = ArrayList<String>()
        val geo = geometry(a, b, p, prefs)
        notes += "${"%.2f".format(geo.overlap / sr.toDouble())} s overlap, A faded out with ${geo.law}, " +
            "B fades in over ${"%.2f".format(geo.bFade / sr.toDouble())} s"
        notes += geo.entryNote
        notes += "B starts at ${geo.bStartNote}"
        if (geo.clamped) notes += "entry clamped so the overlap fits inside A's trim points"
        val lanes = lanes(geo)
        return TransitionPlan(
            strategyId = ID,
            params = p,
            aExitFrame = geo.aExit,
            bEntryFrame = geo.bEntry,
            aWindow = FrameRange(geo.aExit, geo.aExit + g + geo.overlap),
            bWindow = FrameRange(geo.bStart, geo.bEntry),
            expectedOutputFrames = (geo.overlap + 2 * g).toInt(),
            lanes = listOf(lanes.first.toAutomationLane(sr), lanes.second.toAutomationLane(sr)),
            notes = notes,
        )
    }

    // ------------------------------------------------------------------------------------------------ render

    override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val plan = input.plan
        val p = P.resolve(plan.params)
        val geo = geometry(input.aAnalysis, input.bAnalysis, p, ctx.prefs)
        val g = Splice.GUARD_FRAMES
        val out = plan.expectedOutputFrames
        val (laneA, laneB) = lanes(geo)
        ctx.progress(0.1)
        val audio = Splice.mix(input.aAudio, laneA, input.bAudio, laneB, bOffsetFrames = g, outFrames = out)
        ctx.progress(0.7)
        val fin = RenderReports.finalize(audio, ctx)
        val markers = listOf(
            Marker(g.toLong(), MARKER_B_ENTERS),
            Marker((g + geo.bFade).toLong(), MARKER_B_FULL),
            Marker((g + geo.overlap).toLong(), MARKER_A_GONE),
        )
        val metrics = mapOf(
            "overlapFrames" to geo.overlap.toDouble(),
            "bFadeFrames" to geo.bFade.toDouble(),
            "entryFrameA" to geo.bIn.toDouble(),
            "crossingFrameA" to (geo.crossing ?: -1L).toDouble(),
            "limited" to (if (fin.limited) 1.0 else 0.0),
            "gainReductionDb" to fin.gainReductionDb,
        )
        val report = RenderReports.build(audio, (System.nanoTime() - t0) / 1_000_000, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        return RenderedTransition(plan, audio, markers, report)
    }

    // ---------------------------------------------------------------------------------------------- geometry

    /**
     * Everything both [plan] and [render] need, derived only from the analyses, the resolved params and the prefs,
     * so the two can never disagree. All frames are engine-rate frames of the respective track.
     */
    private class Geometry(
        val aExit: Long, val bIn: Long, val bStart: Long, val bEntry: Long,
        val overlap: Long, val bFade: Int, val law: FadeLaw,
        val crossing: Long?, val clamped: Boolean, val entryNote: String, val bStartNote: String,
    )

    private fun geometry(a: TrackAnalysis, b: TrackAnalysis, p: Params, prefs: TransitionPrefs): Geometry {
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES.toLong()
        val scaleA = sr / a.sampleRate.toDouble()
        val scaleB = sr / b.sampleRate.toDouble()
        val aTrimStart = Math.round(a.trimStartFrame * scaleA)
        val aTrimEnd = Math.round(a.trimEndFrame * scaleA)
        val law = CrossfadeLaw.parse(p.choice(P.aFadeLaw)) ?: FadeLaw.S_CURVE

        // Overlap, shortened when A simply has not got that much audio left.
        val wanted = Math.round(p.double(P.overlapSec) * sr)
        val available = max(MIN_OVERLAP_FRAMES, aTrimEnd - aTrimStart - g)
        val overlap = min(wanted, available)

        // Entry: the last short-term window still at or above the threshold (window centre), else trimEnd − overlap.
        val crossing = crossingFrame(a, p.double(P.thresholdLufs), sr)
        val entryNote: String
        var bIn = if (crossing != null) {
            entryNote = "A's short-term loudness falls through ${"%.1f".format(p.double(P.thresholdLufs))} LUFS at " +
                "${"%.2f".format(crossing / sr.toDouble())} s; B enters ${"%.2f".format(p.double(P.holdAfterSec))} s later"
            crossing + Math.round(p.double(P.holdAfterSec) * sr)
        } else {
            entryNote = "no short-term loudness curve (or it never crosses the threshold): B enters one overlap before A's trim end"
            aTrimEnd - overlap
        }
        val lo = aTrimStart + g
        val hi = max(lo, aTrimEnd - overlap)
        val clamped = bIn < lo || bIn > hi
        bIn = bIn.coerceIn(lo, hi)

        // B's start frame.
        val bTrimStart = Math.round(b.trimStartFrame * scaleB)
        val mixIn = b.cues.mixInBeat
        val bStartNote: String
        val bStart = if (p.bool(P.bStartAtMixIn) && mixIn >= 0 && !b.grid.isEmpty) {
            bStartNote = "its mixInBeat cue (beat $mixIn)"
            Math.round(b.grid.frameOfBeat(mixIn.toDouble()) * scaleB).coerceAtLeast(0L)
        } else {
            bStartNote = "its trim start"
            bTrimStart.coerceAtLeast(0L)
        }

        // B's fade-in: bFadeBars bars of B's own grid, never longer than the overlap.
        val bBpm = if (b.grid.bpm > 0) b.grid.bpm else b.tempo.bpm
        val barFrames = if (bBpm > 0) 60.0 * sr / bBpm * b.grid.beatsPerBar.coerceAtLeast(1) else sr.toDouble()
        val bFade = min(overlap, max(MIN_FADE_FRAMES.toLong(), Math.round(p.int(P.bFadeBars) * barFrames))).toInt()

        val aExit = bIn - g
        val bEntry = bStart + overlap + g
        return Geometry(aExit, bIn, bStart, bEntry, overlap, bFade, law, crossing, clamped, entryNote, bStartNote)
    }

    /**
     * Frame (at [sr]) at which A's short-term loudness last leaves [thresholdLufs] behind, or null when there is no
     * curve or it never gets that quiet. `shortTermLufs[k]` measures the 3 s window ending at `(k + 30) * hop`, so
     * the level is attributed to the window's centre, `(k + 15) * hop`.
     */
    private fun crossingFrame(a: TrackAnalysis, thresholdLufs: Double, sr: Int): Long? {
        val curve = a.loudness.shortTermLufs
        if (curve.isEmpty()) return null
        val hop = a.loudness.shortTermHopSec.toDouble().takeIf { it > 0.0 } ?: return null
        var last = -1
        for (k in curve.indices) if (curve[k] >= thresholdLufs) last = k
        if (last < 0 || last >= curve.size - 1) return null // never loud, or never quiet again
        val centreSec = (last + 1 + SHORT_TERM_WINDOW_BLOCKS / 2.0) * hop
        return Math.round(centreSec * sr)
    }

    /** A: unity through the pre-roll, then out across the overlap. B: in over its fade, then unity. */
    private fun lanes(geo: Geometry): Pair<Lane, Lane> {
        val g = Splice.GUARD_FRAMES.toLong()
        val a = Lane(LANE_A).add(0L, 1.0).add(g, 1.0, geo.law).add(g + geo.overlap, 0.0)
        val b = Lane(LANE_B).add(g, 0.0, FadeLaw.EQUAL_POWER).add(g + geo.bFade, 1.0)
        return a to b
    }

    companion object {
        const val ID = "outroIntroMinimal"
        const val MARKER_B_ENTERS = "B enters"
        const val MARKER_B_FULL = "B at full level"
        const val MARKER_A_GONE = "A gone"
        const val LANE_A = "gainA"
        const val LANE_B = "gainB"

        /** 3 s short-term window = 30 blocks of the 100 ms grid (BS.1770). */
        private const val SHORT_TERM_WINDOW_BLOCKS = 30

        /** A 5 ms declick is the shortest fade B ever gets (`bFadeBars = 0`). */
        private const val MIN_FADE_FRAMES = 220

        private const val MIN_OVERLAP_FRAMES = 2048L
    }
}
