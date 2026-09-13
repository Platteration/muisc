package dev.muisc.transitions.modifiers

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.AutomationLane
import dev.muisc.transitions.LanePoint
import dev.muisc.transitions.Marker
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionModifier
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.core.GlideCurve
import dev.muisc.transitions.core.MasterGrid
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.sdk.ParamSet
import dev.muisc.transitions.strategies.BeatDomain
import kotlin.math.min

/**
 * `tempoGlide` — the user's "slowly change tempo".
 *
 * **What it does musically.** Instead of running the whole overlap at A's tempo and letting B ride back to its own
 * tempo alone afterwards, the master grid itself glides from A's tempo to B's (matched) tempo over [P.glideBars]
 * bars along [P.curve] while both decks play — the DJ nudging the pitch fader a little every bar. Each beat only
 * moves a fraction of a percent, so a pair 10–16 % apart (twice `prefs.maxStretchPercent`) can still be
 * beat-mixed. The grid then holds B's tempo for [P.holdBars] bars before the seam, so B ends at stretch ratio 1.0.
 *
 * **How it works.** [adjustPlan] rewrites the `grid.*` params (`grid.mode = glide`, `grid.preBars = 0`,
 * `grid.glideBars`, `grid.holdBars`, `grid.curve`, `grid.stretchMode`) and, for plans that publish the
 * [BeatDomain] geometry (`geom.*`), rebuilds every frame position through [BeatDomain.resolveLayout], so windows
 * and `expectedOutputFrames` follow the new grid. The base strategy's `PhaseLockedDeck`s read the same grid via
 * [MasterGrid.fromPlan] and follow it; nothing else changes. [apply] only adds the `masterBpm` lane (if missing)
 * and "glide start" / "glide end" markers — the audio is already rendered on the glide. B plays on every master beat,
 * so a glide longer than B's music after its entry would render silence: [adjustPlan] shortens `glideBars` to what B
 * has room for and says so in the notes.
 *
 * **Applicability.** > 0 only for the beat-domain strategies ([BeatDomain.BEAT_DOMAIN_STRATEGY_IDS]) and
 * `0.5 % < stretchPercent <= 2 x prefs.maxStretchPercent`; rises linearly from 0 at 0.5 % to 1 at the plain
 * stretch limit and stays at 1 beyond it (that is where a glide is the only way to beat-mix), scaled by the
 * smaller grid confidence.
 *
 * **Params.** `glideBars` 16 (8–64): longer = gentler per-beat change and a longer segment (a 32-bar glide at
 * 100 BPM is 77 s; the device's `EngineLimits` cap it); `curve` S_CURVE | LINEAR | EXP (EXP moves late, so most of
 * the change happens after A is nearly gone); `holdBars` 2 (1–8); `mode` keyLock (WSOLA, pitch preserved) or
 * vinyl (resampling, pitch follows tempo — the honest turntable sound, pitch drifts with the glide).
 *
 * **Failure modes.** WSOLA phasiness on sustained material when the instantaneous ratio exceeds ~8 %; the planner
 * caps the total change at 16 % and prefers longer glides for bigger changes. A base plan without `geom.*` keeps
 * its windows: only the grid params are written and a note says so.
 */
class TempoGlideModifier : TransitionModifier {
    /** Parameters. */
    object P : ParamSet(ID) {
        val glideBars = int("glideBars", "Glide", 16, 8, 64, "bars", "Bars over which the tempo moves from A's to B's")
        val curve = choice("curve", "Curve", GlideCurve.S_CURVE, listOf(GlideCurve.S_CURVE, GlideCurve.LINEAR, GlideCurve.EXP), "Shape of the tempo change")
        val holdBars = int("holdBars", "Hold", 2, 1, 8, "bars", "Bars held at B's tempo before the seam")
        val mode = choice("mode", "Stretch mode", BeatDomain.STRETCH_KEY_LOCK, listOf(BeatDomain.STRETCH_KEY_LOCK, BeatDomain.STRETCH_VINYL), "keyLock preserves pitch (WSOLA); vinyl lets the pitch follow the tempo (resampling)")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Tempo glide"
    override val params: List<ParamSpec> get() = P.specs

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, base: TransitionStrategy, prefs: TransitionPrefs): Double {
        if (base.id !in BeatDomain.BEAT_DOMAIN_STRATEGY_IDS) return 0.0
        val stretch = features.stretchPercent
        val limit = prefs.maxStretchPercent
        if (stretch <= MIN_STRETCH_PERCENT || stretch > 2.0 * limit) return 0.0
        if (!features.beatMatchable) return 0.0
        val ramp = ((stretch - MIN_STRETCH_PERCENT) / (limit - MIN_STRETCH_PERCENT).coerceAtLeast(0.1)).coerceIn(0.0, 1.0)
        return (ramp * min(features.gridConfidenceA, features.gridConfidenceB)).coerceIn(0.0, 1.0)
    }

    override fun adjustPlan(plan: TransitionPlan, a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs): TransitionPlan {
        val p = P.resolve(params, prefs)
        val holdBars = p.int(P.holdBars)
        val curve = p.choice(P.curve)
        val extraNotes = ArrayList<String>()
        // A glide grid of `glideBars + holdBars` bars must fit inside B's remaining music: B plays on EVERY master
        // beat, so a glide longer than B's head would render silence into the segment's tail.
        val glideBars = fitToB(p.int(P.glideBars), holdBars, plan, b, features, extraNotes)
        val gridParams = plan.params
            .with(MasterGrid.PARAM_MODE, MasterGrid.MODE_GLIDE)
            .with(MasterGrid.PARAM_PRE_BARS, 0)
            .with(MasterGrid.PARAM_GLIDE_BARS, glideBars)
            .with(MasterGrid.PARAM_HOLD_BARS, holdBars)
            .with(MasterGrid.PARAM_CURVE, curve)
            .with(BeatDomain.PARAM_STRETCH_MODE, p.choice(P.mode))
        val modifiers = if (ID in plan.modifiers) plan.modifiers else plan.modifiers + ID
        val bpmA = BeatDomain.bpmOf(a)
        val bpmB = bpmA * features.tempoRatio
        val note = "tempo glide ${"%.1f".format(bpmA)} -> ${"%.1f".format(bpmB)} BPM over $glideBars bars ($curve), hold $holdBars bars at B's tempo, ${p.choice(P.mode)}"
        val withGrid = plan.copy(params = gridParams, modifiers = modifiers)
        if (!BeatDomain.hasGeometry(plan)) {
            return withGrid.copy(notes = plan.notes + note + extraNotes + "base plan publishes no beat-domain geometry: windows unchanged")
        }
        val layout = BeatDomain.resolveLayout(withGrid, a, b, features, prefs)
        // The base strategy re-derives its own lanes at render time from the plan; only the grid lanes are known here.
        val kept = plan.lanes.filter { it.id != BeatDomain.LANE_MASTER_BEAT && it.id != BeatDomain.LANE_MASTER_BPM }
        return layout.applyTo(withGrid, kept).copy(notes = plan.notes + note + extraNotes + "windows and length re-derived for the glide grid: ${layout.describe()}")
    }

    /**
     * [glideBars] shortened so that `glideBars + holdBars` bars of master grid still fit in B's music after its start
     * beat (B is audible on every master beat). Plans without the [BeatDomain] geometry keep the requested value.
     */
    private fun fitToB(glideBars: Int, holdBars: Int, plan: TransitionPlan, b: TrackAnalysis, features: PairFeatures, notes: MutableList<String>): Int {
        val bStart = plan.params[BeatDomain.PARAM_B_START_BEAT]?.toIntOrNull() ?: return glideBars
        val matched = BeatDomain.matchedGrid(b.grid, features.tempoRelation)
        val bpb = matched.beatsPerBar.coerceAtLeast(1)
        val availableBars = (BeatDomain.lastUsableBeat(b, matched) - bStart) / bpb
        if (glideBars + holdBars <= availableBars) return glideBars
        val fitted = (availableBars - holdBars).coerceAtLeast(1)
        notes += "glide shortened to $fitted bars: B has only $availableBars bars after its entry (hold $holdBars)"
        return fitted
    }

    override fun apply(rendered: RenderedTransition, input: TransitionInput, params: Params, ctx: RenderContext): RenderedTransition {
        val plan = rendered.plan
        val grid = MasterGrid.fromPlan(plan, input.aAnalysis, input.bAnalysis, input.features, ctx.prefs)
        val g = Splice.GUARD_FRAMES
        val sr = ctx.sampleRate
        val bpb = grid.beatsPerBar
        val preBeats = (plan.params[MasterGrid.PARAM_PRE_BARS]?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 0) * bpb
        val glideBeats = (plan.params[MasterGrid.PARAM_GLIDE_BARS]?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 0) * bpb
        val lanes = if (plan.lanes.any { it.id == BeatDomain.LANE_MASTER_BPM }) plan.lanes else plan.lanes + AutomationLane(
            BeatDomain.LANE_MASTER_BPM,
            (0 until grid.beatCount).map { LanePoint((g + grid.beatFrames[it]).toDouble() / sr, grid.bpmPerBeat[it]) } +
                LanePoint((g + grid.endFrame).toDouble() / sr, grid.bpmEnd),
        )
        val markers = rendered.markers +
            Marker(g + grid.beatFrames[preBeats.coerceIn(0, grid.beatCount)], MARKER_GLIDE_START) +
            Marker(g + grid.beatFrames[(preBeats + glideBeats).coerceIn(0, grid.beatCount)], MARKER_GLIDE_END)
        return RenderedTransition(plan.copy(lanes = lanes), rendered.audio, markers, rendered.report)
    }

    companion object {
        const val ID = "tempoGlide"
        /** Below this stretch a glide is pointless (the resampler handles it at ratio ~1). */
        const val MIN_STRETCH_PERCENT = 0.5
        const val MARKER_GLIDE_START = "glide start"
        const val MARKER_GLIDE_END = "glide end"
    }
}
