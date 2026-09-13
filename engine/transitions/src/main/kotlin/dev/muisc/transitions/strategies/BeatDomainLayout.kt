package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.GridKind
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.analysis.rhythm.OnsetDetector
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.Biquad
import dev.muisc.dsp.filter.BiquadCoefficients
import dev.muisc.dsp.filter.BiquadFilter
import dev.muisc.dsp.gain.Curves
import dev.muisc.transitions.AutomationLane
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.LanePoint
import dev.muisc.transitions.Marker
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderReport
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TempoRelation
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.Lane
import dev.muisc.transitions.core.MasterGrid
import dev.muisc.transitions.core.PhaseLockedDeck
import dev.muisc.transitions.core.RenderReports
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.core.StretchMode
import dev.muisc.transitions.core.relativeTo
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry, scoring and DSP glue shared by the beat-domain strategies ([BeatMatchedBlendStrategy],
 * [BassSwapStrategy], [HarmonicBlendStrategy]) and the `tempoGlide` modifier. Everything a beat-domain segment
 * needs to know about *where* things are is derived from a handful of plan params, so a modifier can move the
 * master grid and every strategy re-derives the same frames.
 *
 * ### Segment layout (output frames, `G = Splice.GUARD_FRAMES`)
 * ```
 * [0, G)                dry A pre-roll: A's window from aExitOffset, verbatim (deck gain only)
 * [G, G + grid.total)   the beat domain: master beat k starts at G + grid.beatFrames[k]
 *     master beats [0, aBeats)         A audible (PhaseLockedDeck A, fading out)
 *     master beats [0, K)              B audible (PhaseLockedDeck B, B's start beat on master beat 0)
 * [R, R + G), R = G + grid.total       dry B post-roll: B's window up to bEntryOffset, verbatim
 * ```
 * The master grid is always built by [MasterGrid.fromPlan] from `grid.*` params. Without the tempo-glide modifier
 * a base strategy writes `grid.mode = glide, grid.preBars = overlapBars, grid.glideBars = settleBars,
 * grid.holdBars = holdBars`: the overlap runs at A's tempo, then B alone "rides the pitch fader back" to its own
 * tempo over `settleBars` (S-curve) and holds it for `holdBars`, so the last bars and the post-roll are B at
 * stretch ratio 1.0 as the splice contract demands. The `tempoGlide` modifier overwrites those keys
 * (`preBars = 0`, its own `glideBars`/`holdBars`/`curve`) so the glide happens while both decks play.
 *
 * ### Plan params written by the strategies (prefix `geom.`)
 * - [PARAM_A_START_BEAT]: A's beat index (A's own grid) that sits on master beat 0 — a phrase start at or before
 *   A's mix-out cue.
 * - [PARAM_B_START_BEAT]: B's beat index in the *matched* grid ([matchedGrid]: B's grid read in half/double time
 *   when [PairFeatures.tempoRelation] says so) that sits on master beat 0 — B's mix-in cue (a downbeat) plus the
 *   strategy's `entryOffsetBars`.
 * - [PARAM_OVERLAP_BEATS]: master beats during which A is audible (clamped to the grid minus the hold bars).
 *
 * ### Seams
 * The rendered decks meet the dry guard regions with a [SEAM_BLEND_FRAMES]-frame LINEAR blend (coherent material:
 * the stretcher at ratio 1.0 is a near-identity, an all-pass-compensated crossover or a 0 dB shelf is identical
 * apart from phase) so the guard regions stay verbatim and no step is introduced.
 */
object BeatDomain {
    const val PARAM_A_START_BEAT = "geom.aStartBeat"
    const val PARAM_B_START_BEAT = "geom.bStartBeat"
    const val PARAM_OVERLAP_BEATS = "geom.overlapBeats"

    /** `grid.stretchMode` = [STRETCH_KEY_LOCK] | [STRETCH_VINYL] (written by the tempo-glide modifier; absent = prefs.keyLock). */
    const val PARAM_STRETCH_MODE = "grid.stretchMode"
    const val STRETCH_KEY_LOCK = "keyLock"
    const val STRETCH_VINYL = "vinyl"

    /** Extra frames decoded on each side of a window so WSOLA look-ahead / warm-up and resampler taps never read zeros. */
    const val WINDOW_PAD = 8192

    /** Linear blend between a dry guard region and the rendered deck (23 ms at 44.1 kHz). */
    const val SEAM_BLEND_FRAMES = 1024

    /** Lane ids every beat-domain plan carries. */
    const val LANE_MASTER_BEAT = "masterBeat"
    const val LANE_MASTER_BPM = "masterBpm"

    /** Default hold (bars at B's tempo before the seam) when the plan says nothing — mirrors [MasterGrid.fromPlan]. */
    const val DEFAULT_HOLD_BARS = 2

    /** Strategies that render on a [MasterGrid] (the tempo-glide modifier applies to these only). */
    val BEAT_DOMAIN_STRATEGY_IDS: Set<String> = setOf("beatMatchedBlend", "bassSwap", "stemSwap", "drumBreakBridge", "harmonicBlend")

    // ------------------------------------------------------------------------------------------ tempo helpers

    /** Tempo of a track: the grid's bpm, else the tempo estimate. */
    fun bpmOf(t: TrackAnalysis): Double = if (t.grid.bpm > 0) t.grid.bpm else t.tempo.bpm

    /**
     * B's grid read so that ONE matched beat = ONE master beat: unchanged for [TempoRelation.SAME]; every second beat
     * (from the downbeat phase) for [TempoRelation.HALF] (B is twice as fast as A); beats with inserted midpoints for
     * [TempoRelation.DOUBLE] (B is half as fast). Beats per bar stay 4, so a matched bar is 2 B bars (HALF) or half a
     * B bar (DOUBLE); phrase information is dropped for HALF (downbeats remain phrase starts).
     */
    fun matchedGrid(grid: BeatGrid, relation: TempoRelation): BeatGrid = when (relation) {
        TempoRelation.SAME -> grid
        TempoRelation.HALF -> {
            val i0 = Math.floorMod(grid.downbeatPhase, 2)
            val n = if (grid.beatCount > i0) (grid.beatCount - i0 + 1) / 2 else 0
            val frames = LongArray(n) { grid.beatFrames[i0 + 2 * it] }
            grid.copy(bpm = grid.bpm / 2.0, beatFrames = frames, downbeatPhase = (grid.downbeatPhase - i0) / 2, phraseStartBeat = -1)
        }
        TempoRelation.DOUBLE -> {
            val n = grid.beatCount
            if (n < 2) grid else {
                val frames = LongArray(2 * n)
                for (i in 0 until n) {
                    frames[2 * i] = grid.beatFrames[i]
                    val next = if (i + 1 < n) grid.beatFrames[i + 1] else grid.beatFrames[i] + (grid.beatFrames[i] - grid.beatFrames[i - 1])
                    frames[2 * i + 1] = Math.round((grid.beatFrames[i] + next) / 2.0)
                }
                grid.copy(bpm = grid.bpm * 2.0, beatFrames = frames, downbeatPhase = grid.downbeatPhase * 2, phraseStartBeat = if (grid.phraseStartBeat >= 0) grid.phraseStartBeat * 2 else -1, phraseBars = grid.phraseBars * 2)
            }
        }
    }

    /** Index of B's beat [beat] in [matchedGrid] (rounded down to the kept beat for HALF). */
    fun matchedBeatIndex(beat: Int, grid: BeatGrid, relation: TempoRelation): Int = when (relation) {
        TempoRelation.SAME -> beat
        TempoRelation.HALF -> Math.floorDiv(beat - Math.floorMod(grid.downbeatPhase, 2), 2)
        TempoRelation.DOUBLE -> beat * 2
    }

    /** Inverse of [matchedBeatIndex]: B's own beat index of a matched beat (for bar features). */
    fun originalBeatIndex(matchedBeat: Int, grid: BeatGrid, relation: TempoRelation): Int = when (relation) {
        TempoRelation.SAME -> matchedBeat
        TempoRelation.HALF -> Math.floorMod(grid.downbeatPhase, 2) + 2 * matchedBeat
        TempoRelation.DOUBLE -> Math.floorDiv(matchedBeat, 2)
    }

    /** `true` when the decks should preserve pitch (key lock); the tempo-glide modifier's `vinyl` mode turns it off. */
    fun keyLockOf(params: Params, prefs: TransitionPrefs): Boolean = when (params[PARAM_STRETCH_MODE]?.trim()?.lowercase()) {
        STRETCH_VINYL.lowercase() -> false
        STRETCH_KEY_LOCK.lowercase() -> true
        else -> prefs.keyLock
    }

    fun holdBarsOf(params: Params): Int = params[MasterGrid.PARAM_HOLD_BARS]?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: DEFAULT_HOLD_BARS

    // ------------------------------------------------------------------------------------------ cue choice

    /** Last A beat that still has music: the beat at `trimEndFrame` (at most the grid's beat count). */
    fun lastUsableBeat(a: TrackAnalysis): Int = floor(a.grid.beatAtFrame(a.trimEndFrame)).toInt().coerceIn(1, a.grid.beatCount)

    /**
     * A's beat that sits on master beat 0: the mix-out cue if it is a phrase start, else the phrase start before it;
     * without a cue, the phrase start [overlapBeats] before the last usable beat. Pulled earlier (to a phrase start)
     * when the overlap would run past A's music; clamped to beat 0. The returned overlap may be shorter than
     * requested (whole bars, at least one) when A is too short for it.
     */
    fun chooseAStart(a: TrackAnalysis, overlapBeats: Int, notes: MutableList<String>): Pair<Int, Int> {
        val grid = a.grid
        require(grid.beatCount >= 2) { "a beat-domain transition needs a beat grid on A (${a.sourceId} has ${grid.beatCount} beats)" }
        val bpb = grid.beatsPerBar
        val last = lastUsableBeat(a)
        val mixOut = a.cues.mixOutBeat
        var start = when {
            mixOut >= 0 && grid.isPhraseStart(mixOut) -> mixOut
            mixOut >= 0 -> grid.previousPhraseStart(mixOut.toDouble())
            else -> grid.previousPhraseStart((last - overlapBeats).toDouble())
        }
        if (mixOut < 0) notes += "A has no mixOutBeat cue: overlap starts at the phrase start $start before A's last beat $last"
        if (start + overlapBeats > last) {
            val pulled = grid.previousPhraseStart((last - overlapBeats).toDouble())
            notes += "A's exit at beat $start leaves only ${last - start} beats for a $overlapBeats-beat overlap: pulled to phrase start $pulled"
            start = pulled
        }
        if (start < 0) { notes += "A is shorter than the overlap: A starts at its first beat"; start = 0 }
        var overlap = overlapBeats
        if (start + overlap > last) {
            overlap = max(bpb, (last - start) / bpb * bpb)
            notes += "overlap shortened to ${overlap / bpb} bars: A has only ${last - start} beats after beat $start"
        }
        return start to overlap
    }

    /**
     * B's beat (in the matched grid) that sits on master beat 0: the mix-in cue (fallback: first downbeat, then the
     * first downbeat after the trim start) snapped up to a downbeat, plus [entryOffsetBars] bars (negative = B enters
     * earlier, into its intro). Never negative.
     */
    fun chooseBStart(b: TrackAnalysis, matched: BeatGrid, relation: TempoRelation, entryOffsetBars: Int, notes: MutableList<String>): Int {
        require(matched.beatCount >= 2) { "a beat-domain transition needs a beat grid on B (${b.sourceId} has ${b.grid.beatCount} beats)" }
        val cue = when {
            b.cues.mixInBeat >= 0 -> b.cues.mixInBeat.also { notes += "B enters at its mixInBeat $it" }
            b.cues.firstDownbeat >= 0 -> b.cues.firstDownbeat.also { notes += "B has no mixInBeat cue: enters at its first downbeat $it" }
            else -> b.grid.nextDownbeat(b.grid.beatAtFrame(b.trimStartFrame)).also { notes += "B has no cues: enters at the first downbeat after its trim start ($it)" }
        }
        var beat = matchedBeatIndex(cue, b.grid, relation)
        if (!matched.isDownbeat(beat)) beat = matched.nextDownbeat(beat.toDouble())
        if (relation != TempoRelation.SAME) notes += "B read in ${relation.name.lowercase()} time: matched beat $beat"
        if (entryOffsetBars != 0) { beat += entryOffsetBars * matched.beatsPerBar; notes += "entry offset $entryOffsetBars bars: B starts at matched beat $beat" }
        if (beat < 0) { notes += "entry offset would start before B's first beat: clamped to beat 0"; beat = 0 }
        return beat
    }

    /** Last matched B beat with music. */
    fun lastUsableBeat(b: TrackAnalysis, matched: BeatGrid): Int = floor(matched.beatAtFrame(b.trimEndFrame)).toInt().coerceIn(1, matched.beatCount)

    // ------------------------------------------------------------------------------------------ layout

    /**
     * The resolved geometry of a beat-domain plan: grids, start beats and every frame position. Built by
     * [resolveLayout] from the plan params so that plan(), render() and the tempo-glide modifier agree exactly.
     */
    class Layout(
        val a: TrackAnalysis,
        val b: TrackAnalysis,
        val relation: TempoRelation,
        /** B's grid in matched beats (see [matchedGrid]). */
        val bGrid: BeatGrid,
        val aStartBeat: Int,
        val bStartBeat: Int,
        /** Requested audible beats of A (may exceed what the grid can hold, see [aBeats]). */
        val overlapBeats: Int,
        val grid: MasterGrid,
        val holdBars: Int,
        val sampleRate: Int,
    ) {
        val g: Int get() = Splice.GUARD_FRAMES
        val beatsPerBar: Int get() = grid.beatsPerBar
        /** Master beats K. */
        val totalBeats: Int get() = grid.beatCount
        val holdBeats: Int get() = min(holdBars * beatsPerBar, totalBeats)
        /** Master beats during which A is audible: the overlap, but never into the hold bars. */
        val aBeats: Int get() = min(overlapBeats, totalBeats - holdBeats).coerceAtLeast(1)
        /** Output frame of master beat [k] (`k` may equal K). */
        fun outFrame(k: Int): Long = g + grid.beatFrames[k.coerceIn(0, totalBeats)]
        fun outFrame(beat: Double): Long = g + grid.frameOfBeat(beat)
        /** First output frame of the post-roll. */
        val endFrame: Long get() = g + grid.totalFrames
        val expectedOutputFrames: Int get() = (2L * g + grid.totalFrames).toInt()

        val aStartFrame: Long = a.grid.frameOfBeat(aStartBeat.toDouble())
        val aEndFrame: Long = a.grid.frameOfBeat((aStartBeat + aBeats).toDouble())
        val aExitFrame: Long = aStartFrame - g
        val aWindow: FrameRange = FrameRange(max(0L, aExitFrame - WINDOW_PAD), aEndFrame + WINDOW_PAD)
        val bStartFrame: Long = bGrid.frameOfBeat(bStartBeat.toDouble())
        val bEndFrame: Long = bGrid.frameOfBeat((bStartBeat + totalBeats).toDouble())
        val bEntryFrame: Long = bEndFrame + g
        val bWindow: FrameRange = FrameRange(max(0L, bStartFrame - WINDOW_PAD), bEntryFrame + WINDOW_PAD)

        /** Stretch ratio (master period / deck period) B needs at master beat 0 and at the end (informational). */
        val bRatioStart: Double get() = grid.periodFrames(0).toDouble() / (bGrid.frameOfBeat(bStartBeat + 1.0) - bGrid.frameOfBeat(bStartBeat.toDouble()))

        /** Lane `masterBeat`: one point (output seconds, beat index) per master beat, plus the end of the last beat. */
        fun masterBeatLane(): AutomationLane = AutomationLane(LANE_MASTER_BEAT, (0..totalBeats).map { LanePoint(outFrame(it).toDouble() / sampleRate, it.toDouble()) })

        /** Lane `masterBpm`: the tempo of every master beat. */
        fun masterBpmLane(): AutomationLane = AutomationLane(LANE_MASTER_BPM, (0 until totalBeats).map { LanePoint(outFrame(it).toDouble() / sampleRate, grid.bpmPerBeat[it]) } + LanePoint(outFrame(totalBeats).toDouble() / sampleRate, grid.bpmEnd))

        /** The geometry params a plan must carry so that [resolveLayout] rebuilds this layout. */
        fun params(base: Params): Params = base.with(PARAM_A_START_BEAT, aStartBeat).with(PARAM_B_START_BEAT, bStartBeat).with(PARAM_OVERLAP_BEATS, overlapBeats)

        /** Applies this geometry to a plan (frames, windows, expected length, the two grid lanes). */
        fun applyTo(plan: TransitionPlan, otherLanes: List<AutomationLane>): TransitionPlan = plan.copy(
            params = params(plan.params), aExitFrame = aExitFrame, bEntryFrame = bEntryFrame, aWindow = aWindow, bWindow = bWindow,
            expectedOutputFrames = expectedOutputFrames, lanes = listOf(masterBeatLane(), masterBpmLane()) + otherLanes,
        )

        fun describe(): String = "master grid ${totalBeats / beatsPerBar} bars (${"%.1f".format(grid.bpmStart)} -> ${"%.1f".format(grid.bpmEnd)} BPM), A beat $aStartBeat on master beat 0 for ${aBeats / beatsPerBar} bars, B matched beat $bStartBeat on master beat 0, hold $holdBars bars at B's tempo, ${"%.2f".format(expectedOutputFrames / sampleRate.toDouble())} s segment"
    }

    /** Whether [plan] carries the geometry params (i.e. was planned by a strategy built on this object). */
    fun hasGeometry(plan: TransitionPlan): Boolean = plan.params[PARAM_A_START_BEAT] != null && plan.params[PARAM_B_START_BEAT] != null && plan.params[PARAM_OVERLAP_BEATS] != null

    /**
     * Rebuilds the [Layout] of a plan from its `geom.*` / `grid.*` params (the grid via [MasterGrid.fromPlan]).
     * Requires the analyses to be at the engine rate (positions are engine-rate frames everywhere).
     */
    fun resolveLayout(plan: TransitionPlan, a: TrackAnalysis, b: TrackAnalysis, f: PairFeatures, prefs: TransitionPrefs): Layout {
        require(a.sampleRate == prefs.sampleRate && b.sampleRate == prefs.sampleRate) { "analyses must be at the engine rate ${prefs.sampleRate} (A ${a.sampleRate}, B ${b.sampleRate}); re-analyse" }
        require(hasGeometry(plan)) { "plan ${plan.strategyId} carries no beat-domain geometry params" }
        val p = plan.params
        val aStart = p[PARAM_A_START_BEAT]!!.toInt()
        val bStart = p[PARAM_B_START_BEAT]!!.toInt()
        val overlap = p[PARAM_OVERLAP_BEATS]!!.toInt()
        val grid = MasterGrid.fromPlan(plan, a, b, f, prefs)
        val matched = matchedGrid(b.grid, f.tempoRelation)
        return Layout(a, b, f.tempoRelation, matched, aStart, bStart, overlap, grid, holdBarsOf(p), prefs.sampleRate)
    }

    /** Grid params of the base layout: the overlap at A's tempo, a settle glide to B's tempo, then a hold. */
    fun baseGridParams(params: Params, overlapBars: Int, settleBars: Int, holdBars: Int): Params = params
        .with(MasterGrid.PARAM_MODE, MasterGrid.MODE_GLIDE)
        .with(MasterGrid.PARAM_PRE_BARS, overlapBars)
        .with(MasterGrid.PARAM_GLIDE_BARS, settleBars)
        .with(MasterGrid.PARAM_HOLD_BARS, holdBars)
        .with(MasterGrid.PARAM_CURVE, "S_CURVE")

    /** A syntactically valid throw-away plan carrying [params], for [resolveLayout] before the frames are known. */
    fun provisionalPlan(strategyId: String, params: Params): TransitionPlan =
        TransitionPlan(strategyId, params, 0L, 0L, FrameRange(0L, 1L), FrameRange(0L, 0L), expectedOutputFrames = 0)

    // ------------------------------------------------------------------------------------------ scoring (§5.2)

    /** Compatibility sub-scores (all 0..1) of a pair for a beat-domain strategy needing [neededBeats] beats on each side. */
    class SubScores(f: PairFeatures, prefs: TransitionPrefs, neededBeats: Int, structPrior: (OutroType, IntroType) -> Double) {
        /** `exp(-(stretch / sigma)^2)`, sigma = maxStretch / 1.5, x 0.85 for half/double time. */
        val tempo: Double = run {
            val sigma = max(0.5, prefs.maxStretchPercent / 1.5)
            val s = exp(-(f.stretchPercent / sigma) * (f.stretchPercent / sigma))
            if (f.tempoRelation == TempoRelation.SAME) s else s * 0.85
        }
        /** Camelot distance after the best shift, pulled toward 0.6 by weak key estimates. */
        val key: Double = keyScore(f.camelotDistanceAfterShift, f.keyStrengthA, f.keyStrengthB)
        /** Loudness delta and energy delta. */
        val energy: Double = exp(-(f.loudnessDeltaLu / 6.0) * (f.loudnessDeltaLu / 6.0)) * (1.0 - 0.5 * abs(f.energyDelta))
        val vocal: Double = (1.0 - f.vocalClash).coerceIn(0.0, 1.0)
        val grid: Double = min(f.gridConfidenceA, f.gridConfidenceB).coerceIn(0.0, 1.0)
        val struct: Double = structPrior(f.outro, f.intro)
        val room: Double = min(1.0, f.outroBeatsAvailable / neededBeats.toDouble()) * min(1.0, f.introBeatsAvailable / neededBeats.toDouble())
    }

    /** `{0: 1.0, 1: 0.85, 2: 0.5, 3: 0.35, >= 4: 0.1}` blended toward 0.6 by `1 - min(strengthA, strengthB)`. */
    fun keyScore(distance: Int, strengthA: Double, strengthB: Double): Double {
        val raw = when (distance) { 0 -> 1.0; 1 -> 0.85; 2 -> 0.5; 3 -> 0.35; else -> 0.1 }
        val w = (1.0 - min(strengthA, strengthB)).coerceIn(0.0, 1.0)
        return raw * (1.0 - w) + 0.6 * w
    }

    /** Generic beat-domain structural prior: beat outro into beat intro is ideal, cold starts and silence are not. */
    fun structPrior(outro: OutroType, intro: IntroType): Double {
        val o = when (outro) {
            OutroType.BEAT_OUTRO -> 1.0; OutroType.UNKNOWN -> 0.8; OutroType.AMBIENT_OUTRO -> 0.7; OutroType.VOCAL_OUTRO -> 0.6
            OutroType.FADE_OUT -> 0.6; OutroType.HARD_STOP -> 0.5
        }
        val i = when (intro) {
            IntroType.BEAT_INTRO -> 1.0; IntroType.UNKNOWN -> 0.8; IntroType.AMBIENT_INTRO -> 0.7; IntroType.VOCAL_INTRO -> 0.5
            IntroType.COLD_START -> 0.45; IntroType.SILENCE -> 0.3
        }
        return o * i
    }

    /**
     * The two keys the pair was scored on, as the planner sees them (`A.outroKey ?: A.key` into `B.introKey ?: B.key`),
     * in Camelot notation: `"8B→12B"`. Empty when an analysis is missing (the caller then omits the codes).
     */
    fun keyLabel(a: TrackAnalysis?, b: TrackAnalysis?): String {
        val ka = (a?.outroKey ?: a?.key)?.camelot?.code ?: return ""
        val kb = (b?.introKey ?: b?.key)?.camelot?.code ?: return ""
        return "$ka→$kb"
    }

    /** Human-readable pair summary lines shared by the applicability reasons. */
    fun describePair(f: PairFeatures, a: TrackAnalysis? = null, b: TrackAnalysis? = null): List<String> = listOf(
        "tempo ${"%.1f".format(f.stretchPercent)} % stretch (ratio ${"%.4f".format(f.tempoRatio)}, ${f.tempoRelation.name.lowercase()} time)",
        "keys " + keyLabel(a, b).let { if (it.isEmpty()) "" else "$it, " } + "Camelot distance ${f.camelotDistance}" +
            (if (f.bestPitchShiftSemitones != 0) " (${f.camelotDistanceAfterShift} after ${"%+d".format(f.bestPitchShiftSemitones)} st)" else ""),
        "loudness ${"%+.1f".format(f.loudnessDeltaLu)} LU, energy ${"%+.2f".format(f.energyDelta)}, vocal clash ${"%.2f".format(f.vocalClash)}",
        "room ${f.outroBeatsAvailable} outro beats / ${f.introBeatsAvailable} intro beats, grid confidence ${"%.2f".format(f.gridConfidenceA)}/${"%.2f".format(f.gridConfidenceB)}",
    )

    // ------------------------------------------------------------------------------------------ lanes

    /** Equal-power-style pair of gain lanes: A unity through the pre-roll then out over `[from, to)`, B in over the same span. */
    fun crossfadeLanes(from: Long, to: Long, law: FadeLaw, idA: String = "gainA", idB: String = "gainB"): Pair<Lane, Lane> {
        val a = Lane(idA).add(0L, 1.0).add(from, 1.0, law).add(to, 0.0)
        val b = Lane(idB).add(from, 0.0, law).add(to, 1.0)
        return a to b
    }

    /**
     * A's vocal-aware duck (dB, <= 0): for each master bar of the overlap, `-duckDb * clamp((v_B - 0.2) / 0.8)` where
     * `v_B` is B's `vocalActivity` in the bar B plays there; the value is reached over the beat before the bar.
     */
    fun vocalDuckLane(layout: Layout, duckDb: Double, id: String = "duckA"): Lane {
        val lane = Lane(id)
        val bpb = layout.beatsPerBar
        val bars = layout.aBeats / bpb
        var prev = 0.0
        lane.add(0L, 0.0, FadeLaw.S_CURVE)
        if (duckDb <= 0.0 || layout.b.bars.vocalActivity.isEmpty()) return lane
        for (m in 0 until bars) {
            val matchedBeat = layout.bStartBeat + m * bpb
            val bBar = layout.b.grid.barOfBeat(originalBeatIndex(matchedBeat, layout.b.grid, layout.relation))
            val activity = layout.b.bars.vocalActivity.getOrNull(bBar)?.toDouble() ?: 0.0
            val v = -duckDb * ((activity - 0.2) / 0.8).coerceIn(0.0, 1.0)
            if (v != prev) {
                val at = m * bpb
                lane.add(layout.outFrame(max(0, at - 1)), prev, FadeLaw.S_CURVE)
                lane.add(layout.outFrame(if (at == 0) 1 else at), v, FadeLaw.S_CURVE)
                prev = v
            }
        }
        return lane
    }

    /** Frame-domain lane → plan lane in seconds. */
    fun toPlanLane(lane: Lane, sampleRate: Int): AutomationLane = lane.toAutomationLane(sampleRate)

    // ------------------------------------------------------------------------------------------ DSP glue

    /** Multiplies `audio` in place by `10^(lane/20)` where the dB [lane] is sampled at output frames `frameOffset + i`. */
    fun applyDbLaneInPlace(audio: AudioBuffer, lane: Lane, frameOffset: Long) {
        if (lane.isEmpty || lane.points.all { it.value == 0.0 }) return
        val block = FloatArray(4096)
        var done = 0
        val n = audio.frames
        while (done < n) {
            val m = min(block.size, n - done)
            lane.fillGains(block, frameOffset + done, m, 0)
            for (i in 0 until m) block[i] = Curves.dbToLinear(block[i])
            for (c in 0 until audio.channelCount) { val x = audio[c]; for (i in 0 until m) x[done + i] *= block[i] }
            done += m
        }
    }

    /**
     * Block-ramped RBJ shelving EQ (a DJ mixer's low / high band): the dB [lane] (sampled at output frames
     * `frameOffset + i`) sets the shelf gain, coefficients are interpolated over every [block] frames by the `dsp`
     * [BiquadFilter] (zipper-free). At 0 dB the shelf is the identity, so a lane that starts at 0 dB leaves the
     * seam with the dry pre-roll untouched. [warmup] (the dry frames preceding the buffer) primes the filter state.
     */
    fun applyShelfInPlace(audio: AudioBuffer, lane: Lane, frameOffset: Long, low: Boolean, cornerHz: Double, warmup: Array<FloatArray>? = null, block: Int = 256) {
        if (lane.isEmpty || lane.points.all { it.value == 0.0 }) return
        val sr = audio.sampleRate.toDouble()
        val ch = audio.channelCount
        fun coeff(db: Double): BiquadCoefficients = if (low) Biquad.lowShelf(cornerHz, sr, db) else Biquad.highShelf(cornerHz, sr, db)
        val filter = BiquadFilter(ch, coeff(lane.valueAt(frameOffset)))
        if (warmup != null && warmup[0].isNotEmpty()) {
            val scratch = Array(ch) { FloatArray(warmup[0].size) }
            filter.process(warmup, scratch, warmup[0].size)
        }
        val inBlk = Array(ch) { FloatArray(block) }
        val outBlk = Array(ch) { FloatArray(block) }
        var done = 0
        val n = audio.frames
        while (done < n) {
            val m = min(block, n - done)
            for (c in 0 until ch) System.arraycopy(audio[c], done, inBlk[c], 0, m)
            filter.setCoefficients(coeff(lane.valueAt(frameOffset + done + m)))
            filter.process(inBlk, outBlk, m)
            for (c in 0 until ch) System.arraycopy(outBlk[c], 0, audio[c], done, m)
            done += m
        }
    }

    /** Pins closer than this to the previous one are dropped (10 ms). */
    const val MIN_PIN_GAP_MS = 10.0

    /**
     * Transient pins for a decoded deck window: the analysis' onsets (window-relative) merged, when [detect] is on,
     * with onsets detected on the window itself by the analysis module's SuperFlux [OnsetDetector] (frame 2048 /
     * hop 512 at the engine rate: the same 46 / 12 ms as the analyser's 22.05 kHz defaults). WSOLA copies a pinned
     * transient exactly once at its nominal time; without pins near off-beat hats or ghost notes it may duplicate
     * their onset at a segment join (audible as a tick). Pins closer than [MIN_PIN_GAP_MS] are merged.
     */
    fun transientPins(window: AudioBuffer, analysisOnsets: LongArray, detect: Boolean): LongArray {
        if (!detect) return analysisOnsets
        val sr = window.sampleRate
        val merged = java.util.TreeSet<Long>()
        for (f in analysisOnsets) merged.add(f)
        val times = OnsetDetector(sampleRate = sr, frameSize = 2048, hop = 512).analyze(window.mono()).onsetTimes()
        for (t in times) { val f = Math.round(t * sr); if (f >= 0 && f < window.frames) merged.add(f) }
        val gap = Math.round(MIN_PIN_GAP_MS / 1000.0 * sr)
        val out = ArrayList<Long>(merged.size)
        var last = Long.MIN_VALUE / 2
        for (f in merged) if (f - last >= gap) { out += f; last = f }
        return out.toLongArray()
    }

    /**
     * Per-render helper: renders the two decks on the layout's grid, blends the seams, assembles the segment and
     * builds the report. Create one per [render] call. With [detectOnsets] the decks' WSOLA pins come from
     * [transientPins] (analysis onsets plus detected ones), else from the analysis onsets alone.
     */
    class Render(val input: TransitionInput, val ctx: RenderContext, val layout: Layout, val detectOnsets: Boolean = true) {
        val sr: Int get() = ctx.sampleRate
        val g: Int get() = layout.g
        private val keyLock = keyLockOf(input.plan.params, ctx.prefs)
        val aGridRel: BeatGrid = layout.a.grid.relativeTo(layout.aWindow.start)
        val bGridRel: BeatGrid = layout.bGrid.relativeTo(layout.bWindow.start)
        val aOnsetsRel: LongArray = layout.a.onsetFrames.relativeTo(layout.aWindow.start, layout.aWindow.length.toLong())
        val bOnsetsRel: LongArray = layout.b.onsetFrames.relativeTo(layout.bWindow.start, layout.bWindow.length.toLong())
        var deckA: PhaseLockedDeck? = null; private set
        var deckB: PhaseLockedDeck? = null; private set

        init {
            require(input.plan.aExitOffset + g <= input.aAudio.frames) { "A window too short for the pre-roll" }
            require(input.plan.bEntryOffset - g >= 0 && input.plan.bEntryOffset <= input.bAudio.frames) { "B window too short for the post-roll" }
        }

        fun options(): PhaseLockedDeck.Options = PhaseLockedDeck.Options(keyLock = keyLock)

        /** A over master beats `[0, aBeats)`. */
        fun renderA(): AudioBuffer {
            val d = PhaseLockedDeck(input.aAudio, aGridRel, layout.aStartBeat.toDouble(), transientPins(input.aAudio, aOnsetsRel, detectOnsets), sampleRate = sr, options = options())
            deckA = d
            return d.render(layout.grid, 0, layout.aBeats)
        }

        /** B (or a pre-processed copy of B's window with a matching grid and mapped onsets) over all K master beats. */
        fun renderB(deck: AudioBuffer = input.bAudio, deckGrid: BeatGrid = bGridRel, onsets: LongArray = bOnsetsRel): AudioBuffer {
            val d = PhaseLockedDeck(deck, deckGrid, layout.bStartBeat.toDouble(), transientPins(deck, onsets, detectOnsets), sampleRate = sr, options = options())
            deckB = d
            return d.render(layout.grid, 0, layout.totalBeats)
        }

        /** The dry pre-roll frames (A's window from aExitOffset, [g] frames): what output `[0, g)` is, and a filter warm-up. */
        fun dryPreRoll(): Array<FloatArray> = Array(input.aAudio.channelCount) { input.aAudio[it].copyOfRange(input.plan.aExitOffset, input.plan.aExitOffset + g) }

        /** Linear blend from dry A (continuing the pre-roll) into the first [SEAM_BLEND_FRAMES] of [rendered] (in place). */
        fun blendHead(rendered: AudioBuffer) {
            val n = min(SEAM_BLEND_FRAMES, rendered.frames)
            val off = input.plan.aExitOffset + g
            for (c in 0 until rendered.channelCount) {
                val dry = input.aAudio[c]; val x = rendered[c]
                for (i in 0 until n) { val t = (i + 1).toFloat() / (n + 1); x[i] = dry[off + i] * (1f - t) + x[i] * t }
            }
        }

        /** Linear blend from the last [SEAM_BLEND_FRAMES] of [rendered] into dry B (leading into the post-roll), in place. */
        fun blendTail(rendered: AudioBuffer) {
            val n = min(SEAM_BLEND_FRAMES, rendered.frames)
            val end = input.plan.bEntryOffset - g
            for (c in 0 until rendered.channelCount) {
                val dry = input.bAudio[c]; val x = rendered[c]
                val x0 = rendered.frames - n
                for (i in 0 until n) { val t = (i + 1).toFloat() / (n + 1); x[x0 + i] = x[x0 + i] * (1f - t) + dry[end - n + i] * t }
            }
        }

        /**
         * The segment: verbatim pre-roll, `a * gainA` and `b * gainB` (lanes in output frames, null = unity) from
         * output frame [g], verbatim post-roll.
         */
        fun assemble(a: AudioBuffer?, gainA: Lane?, b: AudioBuffer, gainB: Lane?): AudioBuffer {
            val ch = input.aAudio.channelCount
            val out = AudioBuffer.silence(sr, ch, layout.expectedOutputFrames)
            val aOff = input.plan.aExitOffset
            val bOff = input.plan.bEntryOffset
            val end = layout.endFrame.toInt()
            for (c in 0 until ch) {
                System.arraycopy(input.aAudio[c], aOff, out[c], 0, g)
                System.arraycopy(input.bAudio[c], bOff - g, out[c], end, g)
            }
            if (a != null) Splice.addInPlace(out, a, g, gainA)
            Splice.addInPlace(out, b, g, gainB)
            return out
        }

        /** Finalises (limiter when needed), reports, and returns the transition with the plan's lanes refreshed. */
        fun finish(audio: AudioBuffer, t0Nanos: Long, markers: List<Marker>, lanes: List<AutomationLane>, metrics: Map<String, Double>, warnings: List<String> = emptyList()): RenderedTransition {
            val fin = RenderReports.finalize(audio, ctx)
            val m = LinkedHashMap<String, Double>()
            m["masterBeats"] = layout.totalBeats.toDouble()
            m["overlapBeats"] = layout.aBeats.toDouble()
            m["masterBpmStart"] = layout.grid.bpmStart
            m["masterBpmEnd"] = layout.grid.bpmEnd
            deckA?.let { d -> m["aMaxStretchPercent"] = d.ratioTrace.maxOfOrNull { abs(it - 1.0) * 100.0 } ?: 0.0; m["aWsola"] = if (d.modeUsed == StretchMode.WSOLA) 1.0 else 0.0 }
            deckB?.let { d -> m["bMaxStretchPercent"] = d.ratioTrace.maxOfOrNull { abs(it - 1.0) * 100.0 } ?: 0.0; m["bWsola"] = if (d.modeUsed == StretchMode.WSOLA) 1.0 else 0.0; m["bRatioEnd"] = d.ratioTrace.lastOrNull() ?: 1.0 }
            m["limited"] = if (fin.limited) 1.0 else 0.0
            m["gainReductionDb"] = fin.gainReductionDb
            m["truePeakBeforeDbtp"] = fin.truePeakBeforeDbtp
            m.putAll(metrics)
            val trace = deckB?.ratioTrace?.let { t -> FloatArray(t.size) { t[it].toFloat() } } ?: FloatArray(0)
            val report: RenderReport = RenderReports.build(audio, (System.nanoTime() - t0Nanos) / 1_000_000, trace, m, warnings + fin.warnings)
            val plan = input.plan.copy(lanes = listOf(layout.masterBeatLane(), layout.masterBpmLane()) + lanes)
            return RenderedTransition(plan, audio, markers, report)
        }
    }

    /** A FLEX grid with the given frames and everything else copied from [like] (for warped deck copies). */
    fun gridWithFrames(like: BeatGrid, frames: LongArray): BeatGrid = like.copy(kind = GridKind.FLEX, beatFrames = frames)
}
