package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
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
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * `filterSweep` — the **resonant sweep out, filter open in**: A is lifted out of the mix by a rising high-pass
 * while B arrives from behind a low-pass that opens over the same bars, both decks crossfading equal-power. It is
 * the one blend that works at *any* tempo relation, because the filters remove exactly the bands where two
 * unmatched rhythms fight (the kick and the hats).
 *
 * ## What it sounds like
 * A gets thinner and thinner — first the sub, then the bass, then the body — until only a resonant whistle of its
 * top end is left, while B rises out of a muffled rumble and snaps into focus exactly on the downbeat where A
 * disappears. With [P.resonance] around 2 the sweep sings; at 5–6 it screams like a DJ mixer's filter knob.
 *
 * ## How the frames are chosen
 * The sweep ENDS on A's last phrase start (or downbeat) that still leaves [P.sweepBars] bars of room, and B is
 * positioned so that one of **its** downbeats lands on that exact output frame: B enters `sweepBars` of A's bars
 * earlier, already playing but filtered down to [P.bLpfFromHz]. So the two tracks agree on precisely one instant —
 * the moment the sweep resolves — which is what the ear latches onto. B is never stretched.
 *
 * ## Parameters
 *  - [P.sweepBars] — length of the sweep in bars of A. 2 bars is a whoosh, 8 bars is a long journey, 16 bars needs
 *    a track with a patient outro.
 *  - [P.hpfToHz] — where A's high-pass ends up. 1 kHz still leaves a recognisable track; 4 kHz (default) leaves a
 *    ghost; 8 kHz+ leaves only air.
 *  - [P.resonance] — Q of A's high-pass. 0.7 is a clean removal, 2 is the classic DJ filter, 6 is a howl (clamped
 *    there: above it the resonant peak eats the limiter's headroom).
 *  - [P.bLpfFromHz] — how muffled B starts. 300 Hz (default) lets its kick and bass through from the beginning,
 *    so the groove is established before the reveal; 100 Hz makes B's arrival a real surprise.
 *  - [P.hpfFromHz] — where A's sweep starts (20 Hz = "no filter yet").
 *  - [P.cutOnPhrase] — end the sweep on a phrase start (default) or on any downbeat.
 *
 * ## Failure modes
 *  - High resonance over loud low end overshoots; the true-peak limiter catches it (and the report says so), but
 *    the sweep loses some punch. Q is clamped to 6.
 *  - The filters mask a key clash but NOT a rhythmic one: at very different tempos the two grooves are still
 *    audible against each other in the mid band during the overlap. Shorten [P.sweepBars] for those pairs.
 *  - If A's grid is wrong the sweep resolves off the beat, which is far more audible than a wrong downbeat in a
 *    long blend — hence the hard gate on `gridConfidenceA`.
 */
class FilterSweepStrategy : TransitionStrategy {

    /** Tunables (see the class doc for what each one does musically). */
    object P : ParamSet(ID) {
        val sweepBars = int("sweepBars", "Sweep length", 8, 2, 16, "bars", "Length of the sweep in bars of A's grid")
        val hpfToHz = double("hpfToHz", "A high-pass end", 4000.0, 200.0, 12000.0, "Hz", "Where A's high-pass sweep ends: higher = less of A is left")
        val resonance = double("resonance", "Resonance", 2.0, 0.7, 6.0, "Q", "Q of A's high-pass: 0.7 clean, 2 classic DJ filter, 6 a howl")
        val bLpfFromHz = double("bLpfFromHz", "B low-pass start", 300.0, 60.0, 2000.0, "Hz", "How muffled B starts before its low-pass opens to 20 kHz")
        val hpfFromHz = double("hpfFromHz", "A high-pass start", 20.0, 20.0, 500.0, "Hz", "Where A's high-pass starts (20 Hz = unfiltered)")
        val cutOnPhrase = bool("cutOnPhrase", "Resolve on a phrase", true, "End the sweep on a phrase start (off: any downbeat)")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Filter sweep"
    override val description: String get() = "Sweeps A out with a resonant high-pass while B's low-pass opens, resolving on a shared downbeat. Works at any tempo."
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val blockers = ArrayList<String>()
        if (a.grid.isEmpty) blockers += "A has no beat grid: the sweep has nothing to resolve on"
        else if (features.gridConfidenceA < MIN_GRID_CONFIDENCE) {
            blockers += "A's grid confidence ${"%.2f".format(features.gridConfidenceA)} < $MIN_GRID_CONFIDENCE: the sweep would resolve off the beat"
        }
        if (b.grid.isEmpty) blockers += "B has no beat grid: B cannot be landed on a downbeat"
        val defaultBars = P.defaults().int(P.sweepBars)
        val bpb = max(1, a.grid.beatsPerBar)
        if (features.outroBeatsAvailable in 0 until bpb) blockers += "A has ${features.outroBeatsAvailable} beats left: no room for a sweep"
        if (blockers.isNotEmpty()) return Applicability.blocked(*blockers.toTypedArray())

        val reasons = ArrayList<String>()
        // Room: the sweep wants sweepBars on each side, but it degrades gracefully by shortening itself.
        val needed = defaultBars * bpb
        val sRoom = min(1.0, max(0.0, features.outroBeatsAvailable.toDouble()) / needed) *
            min(1.0, max(0.0, features.introBeatsAvailable.toDouble()) / needed)
        if (sRoom < 1.0) reasons += "room for ${features.outroBeatsAvailable}/${features.introBeatsAvailable} beats against $needed wanted"
        // Tempo: a sweep is the go-to when the pair cannot be beat-matched, and merely good when it can.
        val incompatible = !features.beatMatchable || features.stretchPercent > prefs.maxStretchPercent
        val sTempo = if (incompatible) 1.0 else 0.7
        reasons += if (incompatible) {
            "tempos not matchable (${"%.1f".format(features.stretchPercent)} % stretch): filters hide the clash"
        } else {
            "tempos ${"%.1f".format(features.stretchPercent)} % apart; a blend would also work"
        }
        // Spectral similarity: the sweep is at its best when the two tracks do NOT occupy the same bands.
        val sSpectral = 1.0 - 0.4 * features.spectralSimilarity
        val sLow = 1.0 - 0.5 * max(0.0, features.lowEndShareA + features.lowEndShareB - 1.0)
        val sGrid = features.gridConfidenceA
        val sVocal = 1.0 - 0.5 * features.vocalClash
        val score = 0.28 * sTempo + 0.22 * sRoom + 0.18 * sGrid + 0.14 * sSpectral + 0.10 * sLow + 0.08 * sVocal
        reasons.add(0, "resonant sweep out / open in (score ${"%.2f".format(score)})")
        return Applicability.of(score, *reasons.toTypedArray())
    }

    // -------------------------------------------------------------------------------------------------- plan

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES.toLong()
        val geo = geometry(a, b, p, prefs)
        val notes = ArrayList<String>()
        notes += "${"%.2f".format(geo.sweep / sr.toDouble())} s sweep (${geo.bars} bars of A at ${"%.1f".format(geo.bpmA)} BPM)"
        notes += "A high-pass ${"%.0f".format(p.double(P.hpfFromHz))} → ${"%.0f".format(p.double(P.hpfToHz))} Hz " +
            "(exponential, Q ${"%.1f".format(p.double(P.resonance))}); B low-pass ${"%.0f".format(p.double(P.bLpfFromHz))} Hz → 20 kHz"
        notes += "the sweep resolves on A's ${geo.cutKind} ${geo.cutBeat} and B's downbeat ${geo.bLandBeat} at the same output frame"
        notes += geo.roomNote
        val lanes = ArrayList<AutomationLane>()
        val (laneA, laneB) = gainLanes(geo)
        lanes += laneA.toAutomationLane(sr)
        lanes += laneB.toAutomationLane(sr)
        lanes += cutoffLane(LANE_HPF, p.double(P.hpfFromHz), p.double(P.hpfToHz), geo, sr)
        lanes += cutoffLane(LANE_LPF, p.double(P.bLpfFromHz), B_LPF_TO_HZ, geo, sr)
        lanes += masterBeatLane(a, geo, sr)
        return TransitionPlan(
            strategyId = ID,
            params = p,
            aExitFrame = geo.aExit,
            bEntryFrame = geo.bEntry,
            aWindow = FrameRange(geo.aExit, geo.aExit + g + geo.sweep),
            bWindow = FrameRange(geo.bStart, geo.bEntry),
            expectedOutputFrames = (geo.sweep + 2 * g).toInt(),
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
        val g = Splice.GUARD_FRAMES
        val sweep = geo.sweep.toInt()
        val ch = input.aAudio.channelCount

        // A: high-pass warmed up on the dry pre-roll (so the sweep starts from a settled state), then swept.
        val aProc = sweepFiltered(
            input.aAudio, SvfMode.HIGH_PASS, q = p.double(P.resonance),
            staticHz = p.double(P.hpfFromHz), warmup = g,
            fromHz = p.double(P.hpfFromHz), toHz = p.double(P.hpfToHz), rampFrames = sweep,
        )
        // The pre-roll must stay bit-exact: keep A dry there and blend into the filtered signal over BLEND_FRAMES.
        blendIn(aProc, input.aAudio, g, min(BLEND_FRAMES, sweep))
        ctx.progress(0.35)

        // B: low-pass opening over the sweep, then dry (the filter is at 20 kHz by then, so the blend is a formality).
        val bProc = sweepFiltered(
            input.bAudio, SvfMode.LOW_PASS, q = B_LPF_Q,
            staticHz = p.double(P.bLpfFromHz), warmup = 0,
            fromHz = p.double(P.bLpfFromHz), toHz = B_LPF_TO_HZ, rampFrames = sweep,
        )
        blendOut(bProc, input.bAudio, sweep, min(BLEND_FRAMES, sweep))
        ctx.progress(0.7)

        val (laneA, laneB) = gainLanes(geo)
        val out = AudioBuffer.silence(sr, ch, plan.expectedOutputFrames)
        Splice.addInPlace(out, AudioBuffer(sr, aProc), dstOffset = 0, gain = laneA)
        Splice.addInPlace(out, AudioBuffer(sr, bProc), dstOffset = g, gain = laneB)
        ctx.progress(0.85)

        val fin = RenderReports.finalize(out, ctx)
        val markers = listOf(
            Marker(g.toLong(), MARKER_SWEEP_START),
            Marker((g + sweep).toLong(), MARKER_RESOLVE),
        )
        val metrics = mapOf(
            "sweepFrames" to sweep.toDouble(),
            "sweepBars" to geo.bars.toDouble(),
            "resolveOutputFrame" to (g + sweep).toDouble(),
            "cutBeatA" to geo.cutBeat.toDouble(),
            "landBeatB" to geo.bLandBeat.toDouble(),
            "limited" to (if (fin.limited) 1.0 else 0.0),
            "gainReductionDb" to fin.gainReductionDb,
        )
        val report = RenderReports.build(out, (System.nanoTime() - t0) / 1_000_000, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        return RenderedTransition(plan, out, markers, report)
    }

    // ------------------------------------------------------------------------------------------------- DSP

    /**
     * Runs [src] through a [StateVariableFilter] in [mode]: the first [warmup] frames at the static cutoff
     * [staticHz] (so the filter's state is settled before the sweep starts and there is no start-up transient),
     * then an exponential cutoff ramp [fromHz] → [toHz] over [rampFrames] frames, then the rest at [toHz].
     */
    private fun sweepFiltered(
        src: AudioBuffer, mode: SvfMode, q: Double, staticHz: Double, warmup: Int,
        fromHz: Double, toHz: Double, rampFrames: Int,
    ): Array<FloatArray> {
        val ch = src.channelCount
        val n = src.frames
        val svf = StateVariableFilter(src.sampleRate, ch, staticHz, q)
        svf.mode = mode
        val out = Array(ch) { FloatArray(n) }
        val inBlock = Array(ch) { FloatArray(FILTER_BLOCK) }
        val outBlock = Array(ch) { FloatArray(FILTER_BLOCK) }
        var done = 0
        while (done < n) {
            if (done == warmup && rampFrames > 0) svf.setCutoffRamp(fromHz, toHz, rampFrames, exponential = true)
            val limit = if (done < warmup) warmup else n
            val k = min(FILTER_BLOCK, limit - done)
            for (c in 0 until ch) System.arraycopy(src[c], done, inBlock[c], 0, k)
            svf.process(inBlock, outBlock, k)
            for (c in 0 until ch) System.arraycopy(outBlock[c], 0, out[c], done, k)
            done += k
        }
        return out
    }

    /** Linearly blends [dry] into [proc] over `[at, at + frames)` (coherent material) and keeps `[0, at)` dry. */
    private fun blendIn(proc: Array<FloatArray>, dry: AudioBuffer, at: Int, frames: Int) {
        for (c in proc.indices) {
            val x = proc[c]; val d = dry[c]
            System.arraycopy(d, 0, x, 0, min(at, x.size))
            for (i in 0 until frames) {
                val idx = at + i
                if (idx >= x.size) break
                val t = (i + 1).toFloat() / (frames + 1)
                x[idx] = d[idx] * (1f - t) + x[idx] * t
            }
        }
    }

    /** Blends [proc] back to [dry] over `[at - frames, at)` and keeps `[at, end)` dry (bit-exact post-roll). */
    private fun blendOut(proc: Array<FloatArray>, dry: AudioBuffer, at: Int, frames: Int) {
        for (c in proc.indices) {
            val x = proc[c]; val d = dry[c]
            for (i in 0 until frames) {
                val idx = at - frames + i
                if (idx < 0 || idx >= x.size) continue
                val t = (i + 1).toFloat() / (frames + 1)
                x[idx] = x[idx] * (1f - t) + d[idx] * t
            }
            if (at < x.size) System.arraycopy(d, at, x, at, x.size - at)
        }
    }

    // ---------------------------------------------------------------------------------------------- geometry

    /** Frames and beats shared by [plan] and [render]. */
    private class Geometry(
        val aExit: Long, val sweepStartA: Long, val cutA: Long, val cutBeat: Int, val cutKind: String,
        val bStart: Long, val bLandBeat: Int, val bEntry: Long,
        val sweep: Long, val bars: Int, val bpmA: Double, val roomNote: String,
    )

    private fun geometry(a: TrackAnalysis, b: TrackAnalysis, p: Params, prefs: TransitionPrefs): Geometry {
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES.toLong()
        val scaleA = sr / a.sampleRate.toDouble()
        val scaleB = sr / b.sampleRate.toDouble()
        fun fa(f: Long) = Math.round(f * scaleA)
        fun fb(f: Long) = Math.round(f * scaleB)
        val aTrimStart = fa(a.trimStartFrame)
        val aTrimEnd = fa(a.trimEndFrame)
        val bpmA = if (a.grid.bpm > 0) a.grid.bpm else a.tempo.bpm
        val bpb = max(1, a.grid.beatsPerBar)
        val barA = if (bpmA > 0) 60.0 * sr / bpmA * bpb else sr.toDouble()

        // Sweep length, shortened bar by bar until it fits between A's trim points.
        var bars = p.int(P.sweepBars)
        var roomNote = "${bars} bars fit between A's trim points"
        val room = aTrimEnd - aTrimStart - g
        while (bars > 1 && Math.round(bars * barA) > room) bars--
        if (bars != p.int(P.sweepBars)) roomNote = "sweep shortened from ${p.int(P.sweepBars)} to $bars bars: A has only ${"%.1f".format(room / sr.toDouble())} s left"
        val sweep = max(MIN_SWEEP_FRAMES, min(Math.round(bars * barA), max(MIN_SWEEP_FRAMES, room)))

        // Where the sweep resolves on A: the LAST phrase start (or downbeat) that still leaves the sweep room.
        val grid = a.grid
        var cutBeat = -1
        var cutKind = if (p.bool(P.cutOnPhrase)) "phrase start" else "downbeat"
        if (!grid.isEmpty) {
            val lastBeat = floor(grid.beatAtFrame(Math.round(aTrimEnd / scaleA))).toInt().coerceAtLeast(0)
            fun fits(beat: Int): Boolean {
                val f = fa(grid.frameOfBeat(beat.toDouble()))
                return f - sweep - g >= aTrimStart && f <= aTrimEnd
            }
            var c = if (p.bool(P.cutOnPhrase)) grid.previousPhraseStart(lastBeat.toDouble()) else grid.previousDownbeat(lastBeat.toDouble())
            while (c > 0 && !fits(c)) c = if (p.bool(P.cutOnPhrase)) grid.previousPhraseStart((c - 1).toDouble()) else grid.previousDownbeat((c - 1).toDouble())
            if (c >= 0 && fits(c)) cutBeat = c
            if (cutBeat < 0 && p.bool(P.cutOnPhrase)) {
                var d = grid.previousDownbeat(lastBeat.toDouble())
                while (d > 0 && !fits(d)) d = grid.previousDownbeat((d - 1).toDouble())
                if (d >= 0 && fits(d)) { cutBeat = d; cutKind = "downbeat" }
            }
        }
        val cutA = if (cutBeat >= 0) fa(grid.frameOfBeat(cutBeat.toDouble())) else max(aTrimStart + sweep + g, aTrimEnd)
        if (cutBeat < 0) roomNote += "; no grid point left room, the sweep ends at A's trim end"
        val sweepStartA = cutA - sweep
        val aExit = sweepStartA - g

        // B: the first downbeat at or after its mix-in cue that leaves `sweep` frames of B before it.
        val bTrimStart = fb(b.trimStartFrame)
        var landBeat = -1
        var bStart: Long
        if (!b.grid.isEmpty) {
            val from = max(if (b.cues.mixInBeat >= 0) b.cues.mixInBeat else 0, b.grid.nextDownbeat(b.grid.beatAtFrame(Math.round((bTrimStart + sweep) / scaleB))))
            landBeat = b.grid.nextDownbeat(from.toDouble())
            bStart = fb(b.grid.frameOfBeat(landBeat.toDouble())) - sweep
            if (bStart < 0) { bStart = 0; landBeat = -1 }
        } else {
            bStart = max(0L, bTrimStart)
        }
        val bEntry = bStart + sweep + g
        return Geometry(aExit, sweepStartA, cutA, cutBeat, cutKind, bStart, landBeat, bEntry, sweep, bars, bpmA, roomNote)
    }

    /** Equal-power crossfade across the sweep; A unity through the pre-roll, B unity through the post-roll. */
    private fun gainLanes(geo: Geometry): Pair<Lane, Lane> {
        val g = Splice.GUARD_FRAMES.toLong()
        val a = Lane(LANE_A).add(0L, 1.0).add(g, 1.0, FadeLaw.EQUAL_POWER).add(g + geo.sweep, 0.0)
        val b = Lane(LANE_B).add(g, 0.0, FadeLaw.EQUAL_POWER).add(g + geo.sweep, 1.0)
        return a to b
    }

    /** The exponential cutoff ramp, sampled for the Lab (Hz against output seconds). */
    private fun cutoffLane(id: String, fromHz: Double, toHz: Double, geo: Geometry, sr: Int): AutomationLane {
        val g = Splice.GUARD_FRAMES.toLong()
        val points = ArrayList<LanePoint>(CUTOFF_LANE_POINTS + 1)
        for (i in 0..CUTOFF_LANE_POINTS) {
            val t = i.toDouble() / CUTOFF_LANE_POINTS
            val hz = fromHz * exp(ln(toHz / fromHz) * t)
            points += LanePoint((g + t * geo.sweep) / sr, hz)
        }
        return AutomationLane(id, points)
    }

    /** One point per A beat inside the segment: `value` is A's absolute beat index, `outputSec` where it lands. */
    private fun masterBeatLane(a: TrackAnalysis, geo: Geometry, sr: Int): AutomationLane {
        val points = ArrayList<LanePoint>()
        val grid: BeatGrid = a.grid
        if (!grid.isEmpty) {
            val scaleA = sr / a.sampleRate.toDouble()
            var beat = max(0.0, kotlin.math.ceil(grid.beatAtFrame(Math.round(geo.aExit / scaleA)))).toInt()
            while (points.size <= MAX_LANE_POINTS) {
                val f = Math.round(grid.frameOfBeat(beat.toDouble()) * scaleA)
                if (f > geo.cutA) break
                points += LanePoint((f - geo.aExit).toDouble() / sr, beat.toDouble())
                beat++
            }
        }
        return AutomationLane(LANE_MASTER_BEAT, points)
    }

    companion object {
        const val ID = "filterSweep"
        const val MIN_GRID_CONFIDENCE = 0.5
        const val MARKER_SWEEP_START = "sweep starts, B enters filtered"
        const val MARKER_RESOLVE = "sweep resolves: B open, A gone"
        const val LANE_A = "gainA"
        const val LANE_B = "gainB"
        const val LANE_HPF = "hpfA"
        const val LANE_LPF = "lpfB"
        const val LANE_MASTER_BEAT = "masterBeat"

        /** B's low-pass opens to the top of the audible band; the SVF clamps it to 0.49 × fs internally. */
        const val B_LPF_TO_HZ = 20000.0

        /** B's low-pass is a clean Butterworth-ish removal, never resonant: it must not colour the incoming track. */
        private const val B_LPF_Q = 0.7071067811865476

        private const val FILTER_BLOCK = 256
        private const val BLEND_FRAMES = 256
        private const val MIN_SWEEP_FRAMES = 8192L
        private const val CUTOFF_LANE_POINTS = 16
        private const val MAX_LANE_POINTS = 4096
    }
}
