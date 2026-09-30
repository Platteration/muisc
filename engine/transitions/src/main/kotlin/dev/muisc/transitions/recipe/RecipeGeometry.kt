package dev.muisc.transitions.recipe

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.AutomationLane
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.LanePoint
import dev.muisc.transitions.Marker
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.StemNeed
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.MasterGrid
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.strategies.BeatDomain
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a recipe's bars are in the rendered segment: the mapping between the recipe timeline (bars from the start
 * of the transition, see [TransitionRecipe]) and OUTPUT frames, shared by [RecipeGeometry.plan] and
 * [RecipeRenderer] so the plan's lanes and markers and the rendered audio agree exactly.
 *
 * Every segment has the same layout (`G` = [Splice.GUARD_FRAMES]):
 * ```
 * [0, G)                 A verbatim (the dry pre-roll)
 * [G, endFrame)          the timeline: bar 0 at output frame G, bar `total` at endFrame
 *     [G, aEndFrame)     deck A audible (the overlap)
 *     [G, endFrame)      deck B audible
 * [endFrame, endFrame+G) B verbatim (the dry post-roll)
 * ```
 */
internal sealed class RecipeTimeline(val sampleRate: Int, val beatsPerBar: Int) {
    val g: Int get() = Splice.GUARD_FRAMES

    /** First output frame of the post-roll (the end of the timeline). */
    abstract val endFrame: Long

    /** Output frame at which deck A's rendered audio ends (the end of the overlap). */
    abstract val aEndFrame: Long

    val expectedOutputFrames: Int get() = (endFrame + g).toInt()

    /** Bars of timeline actually rendered (may differ from the recipe's `total` by rounding, see the plan notes). */
    val timelineBars: Double get() = barAtOutFrame(endFrame)

    /** Bars during which A is rendered. */
    val overlapBars: Double get() = barAtOutFrame(aEndFrame)

    /** Exact (fractional) output frame of timeline bar [bar]. */
    abstract fun outFrameOfBar(bar: Double): Double

    /** Timeline bar (fractional) at output frame [frame]. */
    abstract fun barAtOutFrame(frame: Long): Double

    /** Length of the master beat in effect at output frame [frame], in frames (the echo's time base). */
    abstract fun beatPeriodAt(frame: Long): Double

    /** Output seconds of timeline bar [bar]. */
    fun outSec(bar: Double): Double = outFrameOfBar(bar) / sampleRate

    /**
     * `match` / `glide`: the beat-domain [BeatDomain.Layout] — bar `x` is master beat `x * beatsPerBar`, whatever
     * tempo the master grid has there.
     */
    class Beat(val layout: BeatDomain.Layout) : RecipeTimeline(layout.sampleRate, layout.beatsPerBar) {
        override val endFrame: Long get() = layout.endFrame
        override val aEndFrame: Long get() = layout.outFrame(layout.aBeats)
        override fun outFrameOfBar(bar: Double): Double = g + layout.grid.frameOfBeatExact(bar * beatsPerBar)
        override fun barAtOutFrame(frame: Long): Double = layout.grid.beatAtFrame(frame - g) / beatsPerBar
        override fun beatPeriodAt(frame: Long): Double {
            val grid = layout.grid
            val k = floor(grid.beatAtFrame(frame - g)).toInt().coerceIn(0, grid.beatCount - 1)
            return grid.periodFrames(k).toDouble()
        }
    }

    /**
     * `none`: A's grid defines the timeline — every bar is [barFrames] output frames long (A's bar at A's tempo) —
     * and neither deck is stretched. Deck A plays its window from [aStartSource]; deck B plays its window so that
     * B's source frame [bSourceAtStart] sits at output frame G (bar 0).
     */
    class Linear(
        sampleRate: Int, beatsPerBar: Int,
        val barFrames: Double,
        val timelineFrames: Long,
        val aSpanFrames: Long,
        /** A's track frame at bar 0 (= `aExitFrame + G`). */
        val aStartSource: Long,
        /** B's track frame at bar 0 (may be negative when B enters before its first frame: silence there). */
        val bSourceAtStart: Long,
    ) : RecipeTimeline(sampleRate, beatsPerBar) {
        override val endFrame: Long get() = g + timelineFrames
        override val aEndFrame: Long get() = g + aSpanFrames
        override fun outFrameOfBar(bar: Double): Double = g + bar * barFrames
        override fun barAtOutFrame(frame: Long): Double = (frame - g) / barFrames
        override fun beatPeriodAt(frame: Long): Double = barFrames / beatsPerBar
    }
}

/**
 * Plan-time geometry of a recipe: frame choices for each tempo mode, the Lab lanes and the markers.
 *
 * - `match` / `glide` reuse [BeatDomain] exactly like `bassSwap`: B's start beat from [BeatDomain.chooseBStart]
 *   (mix-in cue + `bEntryOffsetBars`), A's start from [chooseAStart] (phrase: [BeatDomain.chooseAStart]; downbeat:
 *   the downbeat at or before the mix-out cue), overlap = `round(lengthBars)` bars. `match` writes the base grid
 *   (overlap at A's tempo, S-curve settle to B's tempo over `settleBars`, hold `holdBars`); `glide` writes a glide
 *   grid (A's tempo to B's across the overlap, then B's tempo for `settleBars + holdBars`). Settle and hold are
 *   rounded to whole bars and the hold is at least one bar, so the seam is always at stretch ratio 1.0.
 * - `none`: A's grid defines the timeline; A plays unstretched from its chosen start; B plays unstretched with its
 *   entry downbeat (mix-in cue + `bEntryOffsetBars` of B's own bars) on timeline bar `bEntersAtBar`.
 */
internal object RecipeGeometry {
    /** Decoded padding on each side of a `none`-mode window (the same as the beat-domain windows). */
    const val WINDOW_PAD: Int = BeatDomain.WINDOW_PAD

    /** Lane id prefix of deck A / B in the plan (e.g. `a.level`, `b.echo.send`). */
    const val DECK_A = "a"
    const val DECK_B = "b"

    /** Informational lane of `none` recipes: one point per beat of A's own grid (never `masterBeat`, see FilterSweepStrategy.LANE_BEATS_A). */
    const val LANE_BEATS_A = "beatsA"

    const val MARKER_B_ENTERS = "B enters"
    const val MARKER_A_GONE = "A gone"

    // ------------------------------------------------------------------------------------------ plan

    /** The plan of [recipe] for a pair (throws [RecipeException] when the recipe does not resolve with [params]). */
    fun plan(strategyId: String, recipe: TransitionRecipe, a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs): TransitionPlan {
        require(a.sampleRate == prefs.sampleRate && b.sampleRate == prefs.sampleRate) { "analyses must be at the engine rate ${prefs.sampleRate} (A ${a.sampleRate}, B ${b.sampleRate}); re-analyse" }
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val resolved = RecipeResolver.resolve(recipe, params, bpb)
        val base = varParams(resolved)
        val notes = ArrayList<String>()
        notes += "recipe '${recipe.name}' (${recipe.id} v${recipe.version}), tempo mode ${resolved.tempo.name.lowercase()}, align ${resolved.align.name.lowercase()}: " +
            "${fmt(resolved.lengthBars)}-bar overlap, timeline ${fmt(resolved.totalBars)} bars"
        if (resolved.vars.isNotEmpty()) notes += "variables: " + resolved.vars.entries.joinToString(", ") { (k, v) -> "$k = ${fmt(v)}" }
        val stemNeed = stemNeed(resolved)
        val plan = when (resolved.tempo) {
            RecipeTempo.MATCH, RecipeTempo.GLIDE -> planBeatDomain(strategyId, resolved, a, b, features, base, prefs, notes)
            RecipeTempo.NONE -> planNone(strategyId, resolved, a, b, base, prefs, notes)
        }
        val timeline = timeline(plan, resolved, a, b, features, prefs)
        notes += describeLanes(resolved)
        notes += describeEffects(resolved, timeline)
        if (stemNeed != StemNeed.NONE) notes += "stem lanes on ${deckNames(resolved)}: stems are separated from the decoded window(s) (pseudo-stems unless an ML separator is installed), applied, and summed back before the EQ"
        notes += boundaryIssues(resolved, timeline).map { "warning: $it" }
        val lanes = timelineLanes(timeline, a) + recipeLanes(resolved, timeline)
        return plan.copy(stemNeed = stemNeed, lanes = lanes, notes = notes)
    }

    /** Plan params: the recipe's resolved variables (integers printed without a decimal point). */
    fun varParams(resolved: ResolvedRecipe): Params = Params(resolved.vars.mapValues { (_, v) -> Expr.formatNumber(v) })

    fun stemNeed(resolved: ResolvedRecipe): StemNeed = when {
        resolved.a.usesStems && resolved.b.usesStems -> StemNeed.BOTH
        resolved.a.usesStems -> StemNeed.A_TAIL
        resolved.b.usesStems -> StemNeed.B_HEAD
        else -> StemNeed.NONE
    }

    private fun deckNames(r: ResolvedRecipe): String = listOfNotNull("A".takeIf { r.a.usesStems }, "B".takeIf { r.b.usesStems }).joinToString(" and ")

    private fun planBeatDomain(
        strategyId: String, r: ResolvedRecipe, a: TrackAnalysis, b: TrackAnalysis, f: PairFeatures, base: Params, prefs: TransitionPrefs, notes: MutableList<String>,
    ): TransitionPlan {
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val matched = BeatDomain.matchedGrid(b.grid, f.tempoRelation)
        val settle = r.settleBars.roundToInt().coerceAtLeast(0)
        val hold = r.holdBars.roundToInt().coerceAtLeast(1)
        if (settle.toDouble() != r.settleBars) notes += "settle ${fmt(r.settleBars)} bars rounded to $settle (the master grid moves in whole bars)"
        if (hold.toDouble() != r.holdBars) notes += "hold ${fmt(r.holdBars)} bars -> $hold (whole bars, at least one at B's own tempo before the seam)"
        val bStart = BeatDomain.chooseBStart(b, matched, f.tempoRelation, r.bEntryOffsetBars, notes)
        var overlapBars = r.lengthBars.roundToInt().coerceAtLeast(1)
        if (overlapBars.toDouble() != r.lengthBars) notes += "overlap ${fmt(r.lengthBars)} bars rounded to $overlapBars"
        val bRoomBars = (BeatDomain.lastUsableBeat(b, matched) - bStart) / bpb
        if (overlapBars + settle + hold > bRoomBars) {
            overlapBars = (bRoomBars - settle - hold).coerceAtLeast(1)
            notes += "overlap shortened to $overlapBars bars: B has only $bRoomBars bars after matched beat $bStart"
        }
        val (aStart, overlapBeats) = chooseAStart(a, overlapBars * bpb, r.align, notes)
        overlapBars = overlapBeats / bpb
        if (overlapBars < r.lengthBars) notes += "A is audible for $overlapBars bars; lanes after bar $overlapBars of deck A are not heard"
        val gridParams = when (r.tempo) {
            RecipeTempo.GLIDE -> base
                .with(MasterGrid.PARAM_MODE, MasterGrid.MODE_GLIDE)
                .with(MasterGrid.PARAM_PRE_BARS, 0)
                .with(MasterGrid.PARAM_GLIDE_BARS, overlapBars)
                .with(MasterGrid.PARAM_HOLD_BARS, settle + hold)
                .with(MasterGrid.PARAM_CURVE, "S_CURVE")
            else -> BeatDomain.baseGridParams(base, overlapBars, settle, hold)
        }
        val provisional = BeatDomain.provisionalPlan(
            strategyId,
            gridParams.with(BeatDomain.PARAM_A_START_BEAT, aStart).with(BeatDomain.PARAM_B_START_BEAT, bStart).with(BeatDomain.PARAM_OVERLAP_BEATS, overlapBeats),
        )
        val layout = BeatDomain.resolveLayout(provisional, a, b, f, prefs)
        notes += if (r.tempo == RecipeTempo.GLIDE) {
            "glide: the master tempo moves from A's ${"%.1f".format(layout.grid.bpmStart)} BPM to B's ${"%.1f".format(layout.grid.bpmEnd)} BPM across the $overlapBars-bar overlap (S-curve), then holds B's tempo for ${settle + hold} bars"
        } else {
            "match: the overlap runs at A's ${"%.1f".format(layout.grid.bpmStart)} BPM with B time-stretched onto it; B then rides back to its own ${"%.1f".format(layout.grid.bpmEnd)} BPM over $settle bar(s) and holds it for $hold"
        }
        notes += layout.describe()
        notes += "B stretch at entry ${"%.2f".format((layout.bRatioStart - 1.0) * 100.0)} %, ratio 1.0 at the seam"
        return layout.applyTo(provisional, emptyList())
    }

    private fun planNone(strategyId: String, r: ResolvedRecipe, a: TrackAnalysis, b: TrackAnalysis, base: Params, prefs: TransitionPrefs, notes: MutableList<String>): TransitionPlan {
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES.toLong()
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val bpmA = BeatDomain.bpmOf(a)
        require(bpmA > 0) { "track A (${a.sourceId}) has no tempo: a recipe's bars need one" }
        val barFrames = 60.0 * sr / bpmA * bpb
        val (aStart, _) = chooseAStart(a, ceil(r.lengthBars).toInt().coerceAtLeast(1) * bpb, r.align, notes)
        val aStartSource = a.grid.frameOfBeat(aStart.toDouble())
        val aSpan = Math.round(r.lengthBars * barFrames)
        val timelineFrames = Math.round(r.totalBars * barFrames)
        val (bLandSource, landNote) = chooseBLanding(b, r.bEntryOffsetBars)
        notes += landNote
        val landOut = Math.round(r.bEntersAtBar * barFrames)
        val bSourceAtStart = bLandSource - landOut
        val aExit = aStartSource - g
        val bEntry = bSourceAtStart + timelineFrames + g
        notes += "no stretching: the timeline follows A's bars (${"%.1f".format(bpmA)} BPM, ${"%.3f".format(barFrames / sr)} s a bar); A plays from its beat $aStart for ${fmt(r.lengthBars)} bars"
        val bpmB = BeatDomain.bpmOf(b)
        notes += "B plays at its own tempo" + (if (bpmB > 0) " (${"%.1f".format(bpmB)} BPM)" else "") +
            " with its entry downbeat on timeline bar ${fmt(r.bEntersAtBar)}" + (if (bSourceAtStart < 0) "; B's file starts ${"%.2f".format(-bSourceAtStart / sr.toDouble())} s after bar 0 (silence before)" else "")
        if (aStartSource + aSpan > a.trimEndFrame) notes += "A's music ends ${"%.2f".format((aStartSource + aSpan - a.trimEndFrame) / sr.toDouble())} s before the end of the overlap"
        val aWindow = FrameRange(max(0L, aExit - WINDOW_PAD), aStartSource + aSpan + WINDOW_PAD)
        val bWindow = FrameRange(max(0L, bSourceAtStart - WINDOW_PAD), max(0L, bEntry + WINDOW_PAD))
        return TransitionPlan(
            strategyId = strategyId, params = base, aExitFrame = aExit, bEntryFrame = bEntry, aWindow = aWindow, bWindow = bWindow,
            expectedOutputFrames = (timelineFrames + 2 * g).toInt(),
        )
    }

    /**
     * A's beat on timeline bar 0 and the overlap in beats (possibly shortened). `phrase`: [BeatDomain.chooseAStart].
     * `downbeat`: the downbeat at or before A's mix-out cue (without a cue, the downbeat [overlapBeats] before A's
     * last beat), pulled earlier when the overlap would run past A's music, pushed later when the dry pre-roll would
     * not fit before it — the same rules as the phrase variant with downbeats instead of phrase starts.
     */
    fun chooseAStart(a: TrackAnalysis, overlapBeats: Int, align: RecipeAlign, notes: MutableList<String>): Pair<Int, Int> {
        if (align == RecipeAlign.PHRASE) return BeatDomain.chooseAStart(a, overlapBeats, notes)
        val grid = a.grid
        require(grid.beatCount >= 2) { "a recipe needs a beat grid on A (${a.sourceId} has ${grid.beatCount} beats)" }
        val bpb = grid.beatsPerBar
        val last = BeatDomain.lastUsableBeat(a)
        val mixOut = a.cues.mixOutBeat
        var start = if (mixOut >= 0) grid.previousDownbeat(mixOut.toDouble()) else grid.previousDownbeat((last - overlapBeats).toDouble())
        notes += if (mixOut >= 0) "A starts on its downbeat $start, the nearest at or before its mixOutBeat $mixOut" else "A has no mixOutBeat cue: overlap starts on the downbeat $start before A's last beat $last"
        if (start + overlapBeats > last) {
            val pulled = grid.previousDownbeat((last - overlapBeats).toDouble())
            notes += "A's exit at beat $start leaves only ${last - start} beats for a $overlapBeats-beat overlap: pulled to downbeat $pulled"
            start = pulled
        }
        if (start < 0) { start = grid.nextDownbeat(0.0); notes += "A is shorter than the overlap: A starts at its first downbeat $start" }
        if (grid.frameOfBeat(start.toDouble()) < Splice.GUARD_FRAMES) {
            var pushed = start
            while (pushed <= last && grid.frameOfBeat(pushed.toDouble()) < Splice.GUARD_FRAMES) pushed++
            while (pushed <= last && !grid.isDownbeat(pushed)) pushed++
            require(last - pushed >= bpb) { "A (${a.sourceId}) is too short: only ${last - pushed} beats left once the ${Splice.GUARD_FRAMES}-frame dry guard fits" }
            notes += "A's overlap moved to beat $pushed so the ${Splice.GUARD_FRAMES}-frame dry guard fits before it"
            start = pushed
        }
        var overlap = overlapBeats
        if (start + overlap > last) {
            overlap = max(bpb, (last - start) / bpb * bpb)
            notes += "overlap shortened to ${overlap / bpb} bars: A has only ${last - start} beats after beat $start"
        }
        return start to overlap
    }

    /** `none` mode: B's track frame of its entry downbeat (mix-in cue + [entryOffsetBars] of B's bars) and a note. */
    fun chooseBLanding(b: TrackAnalysis, entryOffsetBars: Int): Pair<Long, String> {
        val grid: BeatGrid = b.grid
        if (grid.beatCount < 2) return b.trimStartFrame to "B has no beat grid: it enters at its trim start (entry offset ignored)"
        val (cue, what) = when {
            b.cues.mixInBeat >= 0 -> b.cues.mixInBeat to "its mixInBeat"
            b.cues.firstDownbeat >= 0 -> b.cues.firstDownbeat to "its first downbeat (no mixInBeat cue)"
            else -> grid.nextDownbeat(grid.beatAtFrame(b.trimStartFrame)) to "the first downbeat after its trim start (no cues)"
        }
        var beat = if (grid.isDownbeat(cue)) cue else grid.nextDownbeat(cue.toDouble())
        beat += entryOffsetBars * grid.beatsPerBar
        var note = "B's entry downbeat is beat $beat ($what" + (if (entryOffsetBars != 0) " ${"%+d".format(entryOffsetBars)} bars" else "") + ")"
        if (beat < 0) { beat = grid.nextDownbeat(0.0); note += "; clamped to its first downbeat $beat" }
        return grid.frameOfBeat(beat.toDouble()) to note
    }

    // ------------------------------------------------------------------------------------------ timeline

    /** The timeline of a plan made by [plan] (render time: from the plan's own frames and params). */
    fun timeline(plan: TransitionPlan, r: ResolvedRecipe, a: TrackAnalysis, b: TrackAnalysis, f: PairFeatures, prefs: TransitionPrefs): RecipeTimeline = when (r.tempo) {
        RecipeTempo.MATCH, RecipeTempo.GLIDE -> RecipeTimeline.Beat(BeatDomain.resolveLayout(plan, a, b, f, prefs))
        RecipeTempo.NONE -> {
            val sr = prefs.sampleRate
            val g = Splice.GUARD_FRAMES.toLong()
            val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
            val bpmA = BeatDomain.bpmOf(a)
            require(bpmA > 0) { "track A (${a.sourceId}) has no tempo" }
            val barFrames = 60.0 * sr / bpmA * bpb
            val timelineFrames = Math.round(r.totalBars * barFrames)
            require(plan.expectedOutputFrames.toLong() == timelineFrames + 2 * g) {
                "plan length ${plan.expectedOutputFrames} does not match the recipe's ${fmt(r.totalBars)}-bar timeline (${timelineFrames + 2 * g} frames): re-plan after changing the recipe or its variables"
            }
            RecipeTimeline.Linear(sr, bpb, barFrames, timelineFrames, Math.round(r.lengthBars * barFrames), plan.aExitFrame + g, plan.bEntryFrame - g - timelineFrames)
        }
    }

    /** `masterBeat` + `masterBpm` for beat-domain recipes (a claim of beat alignment); `beatsA` for `none`. */
    fun timelineLanes(t: RecipeTimeline, a: TrackAnalysis): List<AutomationLane> = when (t) {
        is RecipeTimeline.Beat -> listOf(t.layout.masterBeatLane(), t.layout.masterBpmLane())
        is RecipeTimeline.Linear -> listOf(beatsALane(t, a))
    }

    private fun beatsALane(t: RecipeTimeline.Linear, a: TrackAnalysis): AutomationLane {
        val points = ArrayList<LanePoint>()
        val grid = a.grid
        if (grid.beatCount >= 2) {
            val aExit = t.aStartSource - t.g
            val end = aExit + t.aEndFrame
            var beat = max(0.0, ceil(grid.beatAtFrame(aExit))).toInt()
            while (points.size <= MAX_LANE_POINTS) {
                val fr = grid.frameOfBeat(beat.toDouble())
                if (fr > end) break
                points += LanePoint((fr - aExit).toDouble() / t.sampleRate, beat.toDouble())
                beat++
            }
        }
        return AutomationLane(LANE_BEATS_A, points)
    }

    // ------------------------------------------------------------------------------------------ lanes

    /** Every lane of a deck with its plan id (`a.level`, `b.echo.send`, `a.stems.drums`, ...). */
    fun deckLanes(deck: ResolvedDeck, prefix: String): List<Pair<String, ResolvedLane>> = deck.lanes().map { (name, lane) -> "$prefix.$name" to lane }

    /** The recipe's non-neutral lanes in output seconds (curved segments densified so the Lab plots the real shape). */
    fun recipeLanes(r: ResolvedRecipe, t: RecipeTimeline): List<AutomationLane> =
        (deckLanes(r.a, DECK_A) + deckLanes(r.b, DECK_B)).filter { !it.second.isNeutral }.map { (id, lane) -> toAutomationLane(id, lane, t) }

    fun toAutomationLane(id: String, lane: ResolvedLane, t: RecipeTimeline): AutomationLane {
        val out = ArrayList<LanePoint>()
        val pts = lane.points
        for (i in pts.indices) {
            val p = pts[i]
            out += LanePoint(t.outSec(p.bar), p.value)
            if (i < pts.size - 1) {
                val q = pts[i + 1]
                val span = q.bar - p.bar
                if (span > 0.0) when (p.curve) {
                    RecipeCurve.LINEAR -> Unit
                    RecipeCurve.STEP -> out += LanePoint(t.outSec(q.bar), p.value)
                    else -> for (j in 1 until CURVE_POINTS) {
                        val bar = p.bar + span * j / CURVE_POINTS
                        out += LanePoint(t.outSec(bar), lane.valueAt(bar))
                    }
                }
            }
        }
        return AutomationLane(id, out)
    }

    /**
     * Markers for the Lab: [MARKER_B_ENTERS] (first bar at which B's level is above zero), [MARKER_A_GONE] (the bar
     * at which A's level reaches zero for good, else the end of the overlap), then one marker per lane point where
     * a non-neutral lane changes shape (`"a.low off"`, `"b.hpf 20 Hz"`, ...), in timeline order.
     */
    fun markers(r: ResolvedRecipe, t: RecipeTimeline): List<Marker> {
        val out = ArrayList<Marker>()
        val end = t.barAtOutFrame(t.endFrame)
        out += Marker(frame(t, bEntersBar(r)), MARKER_B_ENTERS)
        out += Marker(frame(t, aGoneBar(r, t)), MARKER_A_GONE)
        val shape = ArrayList<Marker>()
        for ((id, lane) in deckLanes(r.a, DECK_A) + deckLanes(r.b, DECK_B)) {
            if (lane.isNeutral) continue
            for (p in lane.points) {
                if (p.bar < 0.0 || p.bar > end) continue
                shape += Marker(frame(t, p.bar), "$id ${formatValue(lane.kind, p.value)}")
            }
        }
        return out + shape.distinct().sortedBy { it.frame }
    }

    private fun frame(t: RecipeTimeline, bar: Double): Long = Math.round(t.outFrameOfBar(bar))

    /** First bar in `[0, total]` at which B's level is above zero (bar 0 when it starts audible). */
    fun bEntersBar(r: ResolvedRecipe): Double {
        val lane = r.b.level
        if (lane.valueAt(0.0) > 0.0) return 0.0
        for (p in lane.points) if (p.bar >= 0.0 && p.value > 0.0) {
            // B rises from the point before this one.
            val before = lane.points.lastOrNull { it.bar < p.bar && it.value <= 0.0 }
            return max(0.0, before?.bar ?: p.bar)
        }
        return 0.0
    }

    /** Bar at which A's level reaches zero for good (the end of the overlap when it never does). */
    fun aGoneBar(r: ResolvedRecipe, t: RecipeTimeline): Double {
        val overlap = t.overlapBars
        val lane = r.a.level
        if (lane.points.isEmpty() || lane.points.last().value > 0.0) return overlap
        var bar = lane.points.last().bar
        for (i in lane.points.indices.reversed()) { if (lane.points[i].value <= 0.0) bar = lane.points[i].bar else break }
        return bar.coerceIn(0.0, overlap)
    }

    // ------------------------------------------------------------------------------------------ explanation

    /** One line per non-neutral lane: its points in words. */
    fun describeLanes(r: ResolvedRecipe): List<String> =
        (deckLanes(r.a, DECK_A) + deckLanes(r.b, DECK_B)).filter { !it.second.isNeutral }.map { (id, lane) ->
            "$id: " + lane.points.joinToString(", ") { p -> "${formatValue(lane.kind, p.value)} at bar ${fmt(p.bar)}" + (if (p.curve != RecipeCurve.LINEAR && p !== lane.points.last()) " (${p.curve.name.lowercase()})" else "") }
        }

    fun describeEffects(r: ResolvedRecipe, t: RecipeTimeline): List<String> {
        val out = ArrayList<String>()
        val periodMs = t.beatPeriodAt(t.g.toLong()) * 1000.0 / t.sampleRate
        for ((name, deck) in listOf("A" to r.a, "B" to r.b)) {
            if (deck.usesEcho) {
                val e = deck.echo!!
                out += "$name echo: ${fmt(e.beats)}-beat delay (${"%.0f".format(e.beats * periodMs)} ms at the opening tempo), feedback ${fmt(e.feedback)}, damping ${fmt(e.dampHz)} Hz, return x${fmt(e.returnLevel)}; send taken before the level fader"
            }
            if (deck.usesReverb) {
                val v = deck.reverb!!
                out += "$name reverb: RT60 ${fmt(v.decaySec)} s, damping ${fmt(v.dampHz)} Hz, return x${fmt(v.returnLevel)}" +
                    (if (!v.freeze.isNeutral) ", freeze lane holds the tail while >= 0.5" else "") + "; send taken before the level fader"
            }
            if (deck.usesEq) out += "$name EQ: 3 bands split at ${fmt(r.lowHz)} / ${fmt(r.highHz)} Hz (Linkwitz-Riley, all-pass compensated)"
            if (deck.usesFilters) out += "$name filters: resonance Q ${fmt(deck.resonance)}, cutoff interpolated on a log scale"
        }
        if (r.a.usesEcho || r.a.usesReverb || r.b.usesEcho || r.b.usesReverb) {
            out += "effect returns are released over the last bar before the post-roll, so no tail reaches B's body"
        }
        return out
    }

    /**
     * Boundary-rule problems (see [TransitionRecipe]): deck A not neutral at bar 0, deck B not neutral at the end
     * of the timeline, A still audible at the end of the overlap, sends still open in the last bar. Empty = clean.
     * The renderer copes with each of them (see [RecipeRenderer]); these strings become warnings.
     */
    fun boundaryIssues(r: ResolvedRecipe, t: RecipeTimeline): List<String> {
        val out = ArrayList<String>()
        for ((id, lane) in deckLanes(r.a, DECK_A)) {
            val v = lane.valueAt(0.0)
            if (near(v, lane.kind.neutral, lane.kind)) continue
            out += "boundary rule: $id is ${formatValue(lane.kind, v)} at bar 0 (neutral is ${formatValue(lane.kind, lane.kind.neutral)}); " +
                if (lane.kind.isEffect) "the send feed fades in over ${RecipeRenderer.FADE_IN_FRAMES} frames" else "A is blended into the processed signal over ${BeatDomain.SEAM_BLEND_FRAMES} frames"
        }
        val endBar = t.timelineBars
        // B's sends and freezes are covered by the last-bar check below (effect tails are released, not blended).
        for ((id, lane) in deckLanes(r.b, DECK_B).filter { !it.second.kind.isEffect }) {
            val v = lane.valueAt(endBar)
            if (!near(v, lane.kind.neutral, lane.kind)) out += "boundary rule: $id is ${formatValue(lane.kind, v)} at the end of the timeline (bar ${fmt(endBar)}); B is blended back to its dry signal over the last ${BeatDomain.SEAM_BLEND_FRAMES} frames"
        }
        val aEnd = t.overlapBars
        val levelAtEnd = r.a.level.valueAt(aEnd)
        if (levelAtEnd > LEVEL_SILENT) out += "boundary rule: A's level is ${fmt(levelAtEnd)} at the end of the overlap (bar ${fmt(aEnd)}); A is declicked with a ${DECLICK_MS.toInt()} ms fade"
        val lastBar = max(0.0, endBar - 1.0)
        for ((id, lane) in (deckLanes(r.a, DECK_A) + deckLanes(r.b, DECK_B)).filter { it.second.kind.isEffect }) {
            val threshold = if (lane.kind == LaneKind.FREEZE) 0.5 else 1e-6
            if (maxOver(lane, lastBar, endBar) >= threshold) out += "boundary rule: $id is still ${if (lane.kind == LaneKind.FREEZE) "on" else "open"} in the last bar before the post-roll; effect tails are released over that bar"
        }
        return out
    }

    private val LaneKind.isEffect: Boolean get() = this == LaneKind.SEND || this == LaneKind.FREEZE

    /** Largest value of [lane] over `[from, to]` (its points inside plus both ends). */
    fun maxOver(lane: ResolvedLane, from: Double, to: Double): Double {
        var m = max(lane.valueAt(from), lane.valueAt(to))
        for (p in lane.points) if (p.bar in from..to) m = max(m, p.value)
        return m
    }

    private fun near(v: Double, neutral: Double, kind: LaneKind): Boolean =
        if (kind.logScale) abs(kotlin.math.ln(v.coerceAtLeast(1e-9) / neutral)) < 1e-6 else abs(v - neutral) < 1e-9

    fun formatValue(kind: LaneKind, v: Double): String = when (kind) {
        LaneKind.DB -> if (v <= RecipeResolver.OFF_DB) "off" else "${fmt(v)} dB"
        LaneKind.HPF, LaneKind.LPF -> "${fmt(v)} Hz"
        LaneKind.LEVEL -> "x${fmt(v)}"
        LaneKind.SEND -> fmt(v)
        LaneKind.FREEZE -> if (v >= 0.5) "frozen" else "released"
    }

    fun fmt(v: Double): String = if (v == Math.rint(v) && abs(v) < 1e9) v.toLong().toString() else "%.3f".format(v).trimEnd('0').trimEnd('.')

    /** A's level at or below this (−80 dB) counts as gone. */
    const val LEVEL_SILENT = 1e-4
    const val DECLICK_MS = 10.0
    private const val CURVE_POINTS = 8
    private const val MAX_LANE_POINTS = 4096
}
