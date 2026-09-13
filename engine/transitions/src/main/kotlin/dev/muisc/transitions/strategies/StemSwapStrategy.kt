package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.gain.Curves
import dev.muisc.dsp.stems.PseudoStemSeparator
import dev.muisc.dsp.stems.Stems
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
import dev.muisc.transitions.StemNeed
import dev.muisc.transitions.TempoRelation
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionStrategy
import dev.muisc.transitions.core.CrossfadeLaw
import dev.muisc.transitions.core.Lane
import dev.muisc.transitions.core.MasterGrid
import dev.muisc.transitions.core.PhaseLockedDeck
import dev.muisc.transitions.core.RenderReports
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.core.frameOfBeatExact
import dev.muisc.transitions.core.relativeTo
import dev.muisc.transitions.sdk.ParamSet
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * `stemSwap` — stem-by-stem handover between two beat-matched decks (DESIGN §4 #6).
 *
 * **What it sounds like.** Both songs run in lock-step on one master beat grid (A's tempo, B time-stretched onto
 * it). Over [P.overlapBars] bars the new song takes over one instrument group at a time, the way a DJ with a
 * stem controller works: at [P.drumsSwapBar] B's drums replace A's drums in a [P.swapBeats]-beat crossfade
 * (the groove flips on one downbeat), at [P.bassSwapBar] the bass follows (a one-beat low-end swap, like
 * `bassSwap` on the bass stems only), and over the last [P.vocalCrossBars] bars A's "other" (pads, chords) and
 * vocals fade out while B's fade in. Whenever B's per-bar vocal activity exceeds [P.duckThreshold], A's vocal
 * stem is ducked by [P.duckDb] so two lead vocals never fight. The last [P.settleBars] bars of the grid glide
 * from A's tempo to B's so B ends the segment at stretch ratio 1.0 and its body continues without a tempo jump.
 *
 * **When the planner picks it.** Both grids confident (`beatMatchable`), stretch within
 * `prefs.maxStretchPercent`, at least 8 bars of room on both sides. The score follows DESIGN §5.2 (tempo, key,
 * energy, vocal clash, grid confidence, structure prior, room, stem quality); pseudo-stems score × 0.8 because
 * they leak — with them the result is honestly closer to a staggered 3-band EQ mix than to a true stem swap.
 *
 * **How it is rendered.** The master grid is built from A's own beat frames (so A's part is an identity time
 * map — bit-exact at ratio 1) plus an optional settle glide at the end; when the tempo-glide modifier wrote
 * `grid.*` params the grid comes from [MasterGrid.fromPlan] instead. Each deck is a [PhaseLockedDeck]; the
 * *aligned* decks are then separated with the `dsp` [PseudoStemSeparator] ("stretch first, separate second":
 * one stretch per deck keeps the four stems sample-coherent, and the separator's output is exactly what is
 * heard; `input.stems` — stems of the unstretched windows — is therefore not used). Per-stem gain lanes are
 * applied and summed. B is cued so that its `mixInBeat` (the first phrase with drums) lands on the drum-swap bar
 * ([P.cueBAtDrumSwap]); it is only rendered from there on.
 *
 * **Segment layout** (see [Splice]): `[0, G)` dry A pre-roll, `[G, G + body)` the beat-domain body,
 * `[G + body, 2G + body)` dry B post-roll, `G = Splice.GUARD_FRAMES`. Short linear seam fades (23 ms) join the
 * dry pre/post-roll to the stretched decks so WSOLA's re-synthesis never steps at the guard edges.
 *
 * **Parameters.** `overlapBars` total length (clamped to the room both songs have, see plan notes);
 * `drumsSwapBar` / `bassSwapBar` swap positions in bars from the start of the overlap; `swapBeats` length of the
 * drum and bass crossfades (1 = the classic hard flip, 4 = a smoother blend); `vocalCrossBars` the length of the
 * final "rest" crossfade; `duckDb` / `duckThreshold` the vocal duck; `settleBars` tempo settle length (0 keeps
 * A bit-exact and lets B's body jump back to its own tempo at the seam); `law` the crossfade law of every
 * stem crossfade (EQUAL_POWER between two different songs); `cueBAtDrumSwap` cue B's mix-in on the drum swap
 * (else on the first bar of the overlap, with its drums muted until the swap).
 *
 * **Failure modes.** Pseudo-stems leak (kick bodies into bass, pads into vocals), so the swaps are never
 * surgical; WSOLA smears sustained material above ~6 % stretch; a wrong downbeat on either track puts the
 * swaps on a weak beat; overlaps longer than the songs' room are shortened silently (a note says so).
 */
class StemSwapStrategy : TransitionStrategy {
    /** Parameters. */
    object P : ParamSet(ID) {
        val overlapBars = int("overlapBars", "Overlap", 24, 4, 64, "bars", "Total length of the stem handover in master bars (shortened to the room the songs have)")
        val drumsSwapBar = int("drumsSwapBar", "Drum swap bar", 4, 0, 48, "bars", "Bar of the overlap at which B's drums replace A's drums")
        val bassSwapBar = int("bassSwapBar", "Bass swap bar", 12, 0, 60, "bars", "Bar of the overlap at which B's bass replaces A's bass (never before the drum swap)")
        val vocalCrossBars = int("vocalCrossBars", "Rest crossfade", 8, 1, 32, "bars", "Length of the final crossfade of the 'other' and vocal stems, ending at the end of the overlap")
        val swapBeats = int("swapBeats", "Swap length", 1, 1, 8, "beats", "Length of the drum and bass stem crossfades")
        val duckDb = double("duckDb", "Vocal duck", 9.0, 0.0, 24.0, "dB", "Attenuation of A's vocal stem while B's vocal activity is above the threshold")
        val duckThreshold = double("duckThreshold", "Duck threshold", 0.5, 0.0, 1.0, "", "B per-bar vocal activity above which A's vocals are ducked")
        val settleBars = int("settleBars", "Tempo settle", 1, 0, 4, "bars", "Bars at the end over which the master tempo glides from A's to B's so B leaves at ratio 1.0")
        val law = choice("law", "Crossfade law", FadeLaw.EQUAL_POWER, listOf(FadeLaw.EQUAL_POWER, FadeLaw.LINEAR, FadeLaw.S_CURVE), "Gain law of the per-stem crossfades")
        val cueBAtDrumSwap = bool("cueBAtDrumSwap", "Cue B on drum swap", true, "Put B's mixInBeat on the drum-swap bar (else on bar 0 of the overlap)")
        val wsolaFrameMs = double("wsolaFrameMs", "WSOLA frame", 40.0, 20.0, 80.0, "ms", "Frame length of the key-lock stretcher (longer = smoother sustains, more transient smearing; kicks are pinned anyway)")
        val wsolaToleranceMs = double("wsolaToleranceMs", "WSOLA tolerance", 10.0, 2.0, 20.0, "ms", "Search range of the key-lock stretcher's waveform alignment")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Stem swap"
    override val description: String get() = "Beat-matched handover one stem at a time: drums first, then bass, then the rest."
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val blockers = ArrayList<String>()
        val reasons = ArrayList<String>()
        if (a.grid.beatCount < 2 || b.grid.beatCount < 2) blockers += "a beat grid is missing (A ${a.grid.beatCount} beats, B ${b.grid.beatCount} beats)"
        if (!features.beatMatchable) blockers += "not beat-matchable (grid confidence ${"%.2f".format(features.gridConfidenceA)}/${"%.2f".format(features.gridConfidenceB)} < 0.5)"
        if (features.stretchPercent > prefs.maxStretchPercent) blockers += "tempo ${"%.1f".format(features.stretchPercent)} % stretch > ${"%.1f".format(prefs.maxStretchPercent)} % limit"

        val sigma = prefs.maxStretchPercent / 1.5
        var sTempo = exp(-(features.stretchPercent / sigma).let { it * it })
        if (features.tempoRelation != TempoRelation.SAME) sTempo *= 0.85
        val sKey = keyScore(features)
        val sEnergy = exp(-(features.loudnessDeltaLu / 6.0).let { it * it }) * (1.0 - 0.5 * abs(features.energyDelta))
        val sVocal = 1.0 - features.vocalClash.coerceIn(0.0, 1.0)
        val sGrid = min(features.gridConfidenceA, features.gridConfidenceB).coerceIn(0.0, 1.0)
        val sStruct = structurePrior(features.outro, features.intro)
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val need = ROOM_BARS * bpb
        val roomB = introRoomBeats(b)
        val sRoom = min(1.0, features.outroBeatsAvailable / need.toDouble()) * min(1.0, roomB / need.toDouble())
        val sStems = STEM_QUALITY_PSEUDO
        if (sRoom < 0.5) blockers += "not enough room: A has ${features.outroBeatsAvailable} beats after its mix-out cue, B $roomB beats after its mix-in cue (need $need each)"

        val fit = 0.25 * sTempo + 0.15 * sKey + 0.10 * sEnergy + 0.10 * sVocal + 0.15 * sGrid + 0.10 * sStruct + 0.05 * sRoom + 0.10 * sStems
        reasons += "tempo ${"%.1f".format(features.stretchPercent)} % stretch${if (features.tempoRelation != TempoRelation.SAME) " (${features.tempoRelation.name.lowercase()}-time)" else ""} (s_tempo ${"%.2f".format(sTempo)})"
        reasons += "keys ${keyName(a)}→${keyName(b)} Camelot distance ${features.camelotDistanceAfterShift} (s_key ${"%.2f".format(sKey)})"
        reasons += "loudness Δ ${"%.1f".format(features.loudnessDeltaLu)} LU, energy Δ ${"%.2f".format(features.energyDelta)} (s_energy ${"%.2f".format(sEnergy)})"
        reasons += "vocal clash ${"%.2f".format(features.vocalClash)}, structure ${features.outro}→${features.intro} (s_struct ${"%.2f".format(sStruct)})"
        reasons += "room ${features.outroBeatsAvailable}/$roomB beats (s_room ${"%.2f".format(sRoom)}), pseudo-stems (× $STEM_QUALITY_PSEUDO)"
        return if (blockers.isEmpty()) Applicability.of(fit, *reasons.toTypedArray()) else Applicability(0.0, reasons, blockers)
    }

    // -------------------------------------------------------------------------------------------------- plan

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p0 = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES.toLong()
        val notes = ArrayList<String>()
        val gridA = scaledGrid(a.grid, sr / a.sampleRate.toDouble())
        val gridB = regrid(scaledGrid(b.grid, sr / b.sampleRate.toDouble()), features.tempoRelation)
        require(gridA.beatCount >= 2 && gridB.beatCount >= 2) { "stemSwap needs beat grids on both tracks" }
        val bpb = gridA.beatsPerBar.coerceAtLeast(1)
        val law = CrossfadeLaw.parse(p0.choice(P.law)) ?: FadeLaw.EQUAL_POWER

        // --- A: the overlap starts on the phrase start at or before the mix-out cue ---------------------------
        val trimEndA = Math.round(a.trimEndFrame * sr / a.sampleRate.toDouble())
        val lastBeatA = floor(gridA.beatAtFrame(trimEndA)).toInt().coerceAtMost(gridA.beatCount - 1 + 4 * bpb)
        val mixOut = if (a.cues.mixOutBeat >= 0) a.cues.mixOutBeat else max(0, lastBeatA + 1 - p0.int(P.overlapBars) * bpb)
        var aStart = gridA.previousPhraseStart(mixOut.toDouble())
        if (aStart < 0) aStart = gridA.nextDownbeat(0.0)
        val roomA = (lastBeatA + 1 - aStart).coerceAtLeast(0)

        // --- B: mix-in cue (converted to the half/double-time grid) ----------------------------------------
        val trimStartB = Math.round(b.trimStartFrame * sr / b.sampleRate.toDouble())
        val mixInRaw = when {
            b.cues.mixInBeat >= 0 -> b.cues.mixInBeat
            b.cues.firstDownbeat >= 0 -> b.cues.firstDownbeat
            else -> -1
        }
        val bStart = if (mixInRaw >= 0) beatIndexInRegrid(mixInRaw, b.grid, features.tempoRelation) else gridB.nextDownbeat(gridB.beatAtFrame(trimStartB))
        val trimEndB = Math.round(b.trimEndFrame * sr / b.sampleRate.toDouble())
        val roomB = (floor(gridB.beatAtFrame(trimEndB)).toInt() + 1 - bStart).coerceAtLeast(0)

        // --- length and swap positions ------------------------------------------------------------------
        val cueAtDrums = p0.bool(P.cueBAtDrumSwap)
        var bars = p0.int(P.overlapBars)
        val drumsBar = min(p0.int(P.drumsSwapBar), bars - 1).coerceAtLeast(0)
        val bEnterBar = if (cueAtDrums) drumsBar else 0
        val roomBars = min(roomA / bpb, roomB / bpb + bEnterBar)
        if (roomBars < bars) { notes += "overlap shortened from $bars to $roomBars bars: A has ${roomA / bpb} bars after its phrase start, B ${roomB / bpb} bars after its mix-in"; bars = roomBars }
        bars = bars.coerceAtLeast(max(2, bEnterBar + 1))
        val bodyBeats = bars * bpb
        val swapBeats = min(p0.int(P.swapBeats), bodyBeats)
        val drumsSwapBeat = min(drumsBar * bpb, bodyBeats - swapBeats).coerceAtLeast(0)
        val bassSwapBeat = min(p0.int(P.bassSwapBar) * bpb, bodyBeats - swapBeats).coerceAtLeast(drumsSwapBeat)
        val crossStartBeat = max(bodyBeats - p0.int(P.vocalCrossBars) * bpb, 0)
        val bEnterBeat = if (cueAtDrums) drumsSwapBeat else 0
        val settleBeats = if (features.stretchPercent > SETTLE_MIN_PERCENT) min(p0.int(P.settleBars) * bpb, bodyBeats) else 0
        val bpmEnd = gridA.bpm * features.tempoRatio

        // --- geometry in frames -----------------------------------------------------------------------
        val aBodyStart = gridA.frameOfBeat(aStart.toDouble())
        val aExit = aBodyStart - g
        val aWindow = FrameRange(aExit - LEAD_FRAMES, gridA.frameOfBeat((aStart + bodyBeats).toDouble()) + PAD_FRAMES)
        val master = ownMaster(sr, gridA, aStart, bodyBeats, settleBeats, bpmEnd, bpb)
        val bEndBeat = bStart + (bodyBeats - bEnterBeat)
        val bEntry = gridB.frameOfBeat(bEndBeat.toDouble()) + g
        val bWindow = FrameRange(gridB.frameOfBeat(bStart.toDouble()) - PAD_FRAMES, bEntry + PAD_FRAMES)
        val expected = (2 * g + master.totalFrames).toInt()

        val p = p0.with(K_A_START, aStart).with(K_B_START, bStart).with(K_B_ENTER, bEnterBeat).with(K_BODY_BEATS, bodyBeats)
            .with(K_SETTLE, settleBeats).with(K_DRUMS, drumsSwapBeat).with(K_BASS, bassSwapBeat).with(K_CROSS, crossStartBeat).with(K_SWAP, swapBeats)
        val lanes = stemLanes(master, g, bEnterBeat, drumsSwapBeat, bassSwapBeat, crossStartBeat, swapBeats, law).map { it.toAutomationLane(sr) } +
            duckLane(master, g, bStart, bEnterBeat, b, features.tempoRelation, p0.double(P.duckDb), p0.double(P.duckThreshold)).toAutomationLane(sr)
        notes.add(0, "$bars-bar stem swap on A's grid from A beat $aStart (bar ${gridA.barOfBeat(aStart)}): drums at bar ${drumsSwapBeat / bpb}, bass at bar ${bassSwapBeat / bpb}, rest over bars ${crossStartBeat / bpb}–$bars, $swapBeats-beat swaps")
        notes += "B beat $bStart (${if (mixInRaw >= 0) "mixInBeat $mixInRaw" else "first downbeat after trim"}${if (features.tempoRelation != TempoRelation.SAME) ", ${features.tempoRelation.name.lowercase()}-time grid" else ""}) lands on master beat $bEnterBeat; B stretched ${"%.2f".format(features.stretchPercent)} % (${"%.2f".format(gridA.bpm)} → ${"%.2f".format(bpmEnd)} BPM matched)"
        notes += if (settleBeats > 0) "last $settleBeats beats glide to B's tempo so B leaves at ratio 1.0" else "no tempo settle: B's body resumes at its own tempo at the seam"
        notes += "${Splice.GUARD_FRAMES}-frame dry pre/post-roll, ${LEAD_FRAMES}/${PAD_FRAMES}-frame window padding for the stretchers"
        return TransitionPlan(
            strategyId = ID, params = p, aExitFrame = aExit, bEntryFrame = bEntry, aWindow = aWindow, bWindow = bWindow,
            expectedOutputFrames = expected, stemNeed = StemNeed.BOTH, lanes = lanes, notes = notes,
        )
    }

    // ------------------------------------------------------------------------------------------------ render

    override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val plan = input.plan
        val p = P.resolve(plan.params)
        val sr = ctx.sampleRate
        val g = Splice.GUARD_FRAMES
        val law = CrossfadeLaw.parse(p.choice(P.law)) ?: FadeLaw.EQUAL_POWER
        val a = input.aAnalysis; val b = input.bAnalysis; val f = input.features
        val aStart = geom(plan, K_A_START); val bStart = geom(plan, K_B_START)
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val gridA = scaledGrid(a.grid, sr / a.sampleRate.toDouble()).relativeTo(plan.aWindow.start)
        val gridB = regrid(scaledGrid(b.grid, sr / b.sampleRate.toDouble()), f.tempoRelation).relativeTo(plan.bWindow.start)
        val glide = plan.params[MasterGrid.PARAM_MODE]?.equals(MasterGrid.MODE_GLIDE, ignoreCase = true) == true
        val master = if (glide) MasterGrid.fromPlan(plan, a, b, f, ctx.prefs)
        else ownMaster(sr, gridA, aStart, geom(plan, K_BODY_BEATS), geom(plan, K_SETTLE), gridA.bpm * f.tempoRatio, bpb)
        val bodyBeats = master.beatCount
        val bodyFrames = master.totalFrames.toInt()
        val bEnterBeat = min(geom(plan, K_B_ENTER), bodyBeats - 1)
        val swapBeats = min(geom(plan, K_SWAP), bodyBeats)
        val drumsSwapBeat = min(geom(plan, K_DRUMS), bodyBeats - swapBeats).coerceAtLeast(0)
        val bassSwapBeat = min(geom(plan, K_BASS), bodyBeats - swapBeats).coerceAtLeast(drumsSwapBeat)
        val crossStartBeat = min(geom(plan, K_CROSS), bodyBeats - 1).coerceAtLeast(0)

        // --- decks onto the master grid -------------------------------------------------------------------
        val options = PhaseLockedDeck.Options(keyLock = ctx.prefs.keyLock, wsolaFrameMs = p.double(P.wsolaFrameMs), wsolaToleranceMs = p.double(P.wsolaToleranceMs))
        val onsetsA = scaledOnsets(a, sr).relativeTo(plan.aWindow.start, plan.aWindow.length.toLong())
        val onsetsB = scaledOnsets(b, sr).relativeTo(plan.bWindow.start, plan.bWindow.length.toLong())
        val deckA = PhaseLockedDeck(input.aAudio, gridA, aStart.toDouble(), onsetsA, sampleRate = sr, options = options)
        val renderedA = deckA.render(master, 0, bodyBeats)
        ctx.progress(0.2)
        val deckB = PhaseLockedDeck(input.bAudio, gridB, bStart.toDouble(), onsetsB, sampleRate = sr, options = options)
        val renderedB = deckB.render(master, bEnterBeat, bodyBeats - bEnterBeat)
        ctx.progress(0.4)
        val stemsA = PseudoStemSeparator().separate(renderedA)
        ctx.progress(0.6)
        val stemsB = PseudoStemSeparator().separate(renderedB)
        ctx.progress(0.75)

        // --- gains ---------------------------------------------------------------------------------------
        val lanes = stemLanes(master, g.toLong(), bEnterBeat, drumsSwapBeat, bassSwapBeat, crossStartBeat, swapBeats, law)
        val duck = duckLane(master, g.toLong(), bStart, bEnterBeat, b, f.tempoRelation, p.double(P.duckDb), p.double(P.duckThreshold))
        val out = AudioBuffer.silence(sr, input.aAudio.channelCount, g + bodyFrames + g)
        val aExitOff = plan.aExitOffset
        for (c in 0 until out.channelCount) System.arraycopy(input.aAudio[c], aExitOff, out[c], 0, g)
        for (c in 0 until stemsA.channelCount) duck.applyInPlace(stemsA.vocals[c], g.toLong())
        addStems(out, stemsA, g, lanes, aSide = true)
        val bOffset = g + master.beatFrames[bEnterBeat].toInt()
        addStems(out, stemsB, bOffset, lanes, aSide = false)
        seamFadeIn(out, input.aAudio, aExitOff + g, g)
        val bEntryOff = plan.bEntryOffset
        seamFadeOut(out, input.bAudio, bEntryOff - g, g + bodyFrames)
        for (c in 0 until out.channelCount) System.arraycopy(input.bAudio[c], bEntryOff - g, out[c], g + bodyFrames, g)
        ctx.progress(0.85)

        val fin = RenderReports.finalize(out, ctx)
        val markers = listOf(
            Marker(g.toLong(), MARKER_OVERLAP),
            Marker(g + master.beatFrames[drumsSwapBeat], MARKER_DRUMS),
            Marker(g + master.beatFrames[bassSwapBeat], MARKER_BASS),
            Marker(g + master.beatFrames[crossStartBeat], MARKER_REST),
            Marker((g + bodyFrames).toLong(), MARKER_B_FULL),
        )
        val beatLane = AutomationLane(LANE_MASTER_BEAT, (0..bodyBeats).map { LanePoint((g + master.beatFrames[it]) / sr.toDouble(), it.toDouble()) })
        val bpmLane = AutomationLane(LANE_MASTER_BPM, (0 until bodyBeats).map { LanePoint((g + master.beatFrames[it]) / sr.toDouble(), master.bpmPerBeat[it]) })
        val ratio = FloatArray(bodyBeats) { k -> if (k >= bEnterBeat) deckB.ratioTrace[k - bEnterBeat].toFloat() else deckA.ratioTrace[k].toFloat() }
        val metrics = mapOf(
            "bodyBeats" to bodyBeats.toDouble(), "bodyFrames" to bodyFrames.toDouble(), "guardFrames" to g.toDouble(),
            "stretchModeB" to (deckB.modeUsed?.ordinal?.toDouble() ?: -1.0), "stretchModeA" to (deckA.modeUsed?.ordinal?.toDouble() ?: -1.0),
            "maxRatioB" to (deckB.ratioTrace.maxOrNull() ?: 1.0), "limited" to (if (fin.limited) 1.0 else 0.0), "gainReductionDb" to fin.gainReductionDb,
        )
        val report = RenderReports.build(out, (System.nanoTime() - t0) / 1_000_000, ratioTrace = ratio, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        val renderLanes = lanes.map { it.toAutomationLane(sr) } + duck.toAutomationLane(sr) + beatLane + bpmLane
        val ids = renderLanes.map { it.id }.toSet()
        val planOut = plan.copy(lanes = plan.lanes.filter { it.id !in ids } + renderLanes)
        return RenderedTransition(planOut, out, markers, report)
    }

    // ------------------------------------------------------------------------------------------- lanes / mix

    /** Six gain lanes in OUTPUT frames (body starts at [g]): A/B × drums/bass/rest. */
    private fun stemLanes(master: MasterGrid, g: Long, bEnterBeat: Int, drumsSwapBeat: Int, bassSwapBeat: Int, crossStartBeat: Int, swapBeats: Int, law: FadeLaw): List<Lane> {
        val n = master.beatCount
        fun fr(beat: Int) = g + master.beatFrames[beat.coerceIn(0, n)]
        val end = fr(n)
        val aDrums = Lane(LANE_A_DRUMS).add(g, 1.0).add(fr(drumsSwapBeat), 1.0, law).add(fr(drumsSwapBeat + swapBeats), 0.0)
        val bDrums = Lane(LANE_B_DRUMS).add(fr(bEnterBeat), 0.0).add(fr(drumsSwapBeat), 0.0, law).add(fr(drumsSwapBeat + swapBeats), 1.0)
        val aBass = Lane(LANE_A_BASS).add(g, 1.0).add(fr(bassSwapBeat), 1.0, law).add(fr(bassSwapBeat + swapBeats), 0.0)
        val bBass = Lane(LANE_B_BASS).add(fr(bEnterBeat), 0.0).add(fr(bassSwapBeat), 0.0, law).add(fr(bassSwapBeat + swapBeats), 1.0)
        val aRest = Lane(LANE_A_REST).add(g, 1.0).add(fr(crossStartBeat), 1.0, law).add(end, 0.0)
        val bRest = Lane(LANE_B_REST).add(fr(bEnterBeat), 0.0).add(fr(crossStartBeat), 0.0, law).add(end, 1.0)
        return listOf(aDrums, bDrums, aBass, bBass, aRest, bRest)
    }

    /**
     * Duck lane for A's vocal stem: 1.0, or `-duckDb` during master bars whose B bar (from B's `bars.vocalActivity`)
     * carries vocal activity above [threshold]; values are set at bar boundaries and reached with one-bar linear
     * ramps, and the lane always starts at 1.0 so the dry pre-roll is never touched.
     */
    private fun duckLane(master: MasterGrid, g: Long, bStart: Int, bEnterBeat: Int, b: TrackAnalysis, relation: TempoRelation, duckDb: Double, threshold: Double): Lane {
        val lane = Lane(LANE_DUCK).add(g, 1.0)
        val act = b.bars.vocalActivity
        if (act.isEmpty() || duckDb <= 0.0) return lane
        val bpb = master.beatsPerBar
        val duck = Curves.dbToLinear(-duckDb)
        var beat = bpb
        while (beat <= master.beatCount) {
            val bBeat = bStart + (beat - bEnterBeat)
            val bar = barInOriginal(bBeat, b.grid, relation)
            val active = bar in act.indices && act[bar] > threshold
            lane.add(g + master.beatFrames[beat], if (active) duck else 1.0)
            beat += bpb
        }
        return lane
    }

    private fun addStems(out: AudioBuffer, stems: Stems, offset: Int, lanes: List<Lane>, aSide: Boolean) {
        val drums = lanes[if (aSide) 0 else 1]; val bass = lanes[if (aSide) 2 else 3]; val rest = lanes[if (aSide) 4 else 5]
        Splice.addInPlace(out, stems.drums, offset, drums)
        Splice.addInPlace(out, stems.bass, offset, bass)
        Splice.addInPlace(out, stems.vocals, offset, rest)
        Splice.addInPlace(out, stems.other, offset, rest)
    }

    private fun geom(plan: TransitionPlan, key: String): Int =
        plan.params[key]?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: throw IllegalArgumentException("stemSwap plan lacks geometry key '$key' (plan() must produce it)")

    companion object {
        const val ID = "stemSwap"
        const val ROOM_BARS = 8
        const val STEM_QUALITY_PSEUDO = 0.8
        /** Extra frames decoded before `aExitFrame` (WSOLA warm-up) and after both bodies (stretcher look-ahead). */
        const val LEAD_FRAMES = 4096L
        const val PAD_FRAMES = 8192L
        /** Length of the linear fades joining the dry guard regions to the stretched decks. */
        const val SEAM_FADE_FRAMES = 1024
        /** Below this stretch the settle glide is skipped (the tempos are the same for all practical purposes). */
        const val SETTLE_MIN_PERCENT = 0.05
        const val MARKER_OVERLAP = "overlap starts"
        const val MARKER_DRUMS = "drum swap"
        const val MARKER_BASS = "bass swap"
        const val MARKER_REST = "rest crossfade"
        const val MARKER_B_FULL = "B full"
        const val LANE_A_DRUMS = "gainA.drums"; const val LANE_B_DRUMS = "gainB.drums"
        const val LANE_A_BASS = "gainA.bass"; const val LANE_B_BASS = "gainB.bass"
        const val LANE_A_REST = "gainA.rest"; const val LANE_B_REST = "gainB.rest"
        const val LANE_DUCK = "duckA.vocals"
        const val LANE_MASTER_BEAT = "masterBeat"
        const val LANE_MASTER_BPM = "masterBpm"
        private const val K_A_START = "geom.aStartBeat"; private const val K_B_START = "geom.bStartBeat"; private const val K_B_ENTER = "geom.bEnterBeat"
        private const val K_BODY_BEATS = "geom.bodyBeats"; private const val K_SETTLE = "geom.settleBeats"; private const val K_DRUMS = "geom.drumsSwapBeat"
        private const val K_BASS = "geom.bassSwapBeat"; private const val K_CROSS = "geom.crossStartBeat"; private const val K_SWAP = "geom.swapBeats"

        /** §5.2 key score blended toward 0.6 by the weaker key strength. */
        fun keyScore(f: PairFeatures): Double {
            val table = when (f.camelotDistanceAfterShift) { 0 -> 1.0; 1 -> 0.85; 2 -> 0.5; 3 -> 0.35; else -> 0.1 }
            val w = min(f.keyStrengthA, f.keyStrengthB).coerceIn(0.0, 1.0)
            return table * w + 0.6 * (1.0 - w)
        }

        private fun keyName(t: TrackAnalysis) = (t.outroKey ?: t.key).camelot.code

        /** Structure prior for a stem handover: strongest when both edges carry a beat. */
        fun structurePrior(outro: OutroType, intro: IntroType): Double {
            val o = when (outro) { OutroType.BEAT_OUTRO -> 1.0; OutroType.AMBIENT_OUTRO, OutroType.VOCAL_OUTRO -> 0.7; OutroType.UNKNOWN -> 0.6; OutroType.HARD_STOP -> 0.5; OutroType.FADE_OUT -> 0.4 }
            val i = when (intro) { IntroType.BEAT_INTRO -> 1.0; IntroType.AMBIENT_INTRO, IntroType.VOCAL_INTRO -> 0.7; IntroType.UNKNOWN -> 0.6; IntroType.COLD_START -> 0.5; IntroType.SILENCE -> 0.3 }
            return min(o, i) * 0.5 + (o * i) * 0.5
        }

        /** Beats B offers after its mix-in cue (the analyser's `introBeatsAvailable` measures the intro before it). */
        fun introRoomBeats(b: TrackAnalysis): Int {
            if (b.grid.isEmpty) return 0
            val mixIn = when { b.cues.mixInBeat >= 0 -> b.cues.mixInBeat; b.cues.firstDownbeat >= 0 -> b.cues.firstDownbeat; else -> 0 }
            return (b.grid.beatCount - mixIn).coerceAtLeast(0)
        }

        /** Grid with frames rescaled (analysis rate → engine rate); identity for scale 1. */
        fun scaledGrid(grid: BeatGrid, scale: Double): BeatGrid =
            if (scale == 1.0 || grid.isEmpty) grid else grid.copy(beatFrames = LongArray(grid.beatCount) { Math.round(grid.beatFrames[it] * scale) })

        /** Onset frames at the engine rate. */
        fun scaledOnsets(t: TrackAnalysis, sr: Int): LongArray {
            val scale = sr / t.sampleRate.toDouble()
            return if (scale == 1.0) t.onsetFrames else LongArray(t.onsetFrames.size) { Math.round(t.onsetFrames[it] * scale) }
        }

        /**
         * B's grid read in half or double time so that one grid beat corresponds to one master beat. DOUBLE
         * (`2 * B.bpm / A.bpm` closest to 1: B's beats are counted twice as fast) inserts a beat halfway between
         * every pair; HALF (B twice as fast as A) keeps every second beat from the downbeat phase.
         */
        fun regrid(grid: BeatGrid, relation: TempoRelation): BeatGrid = when {
            grid.beatCount < 2 -> grid
            relation == TempoRelation.SAME -> grid
            relation == TempoRelation.HALF -> {
                val phase = grid.downbeatPhase.coerceIn(0, grid.beatCount - 1)
                val frames = (phase until grid.beatCount step 2).map { grid.beatFrames[it] }.toLongArray()
                val phrase = if (grid.phraseStartBeat >= 0) Math.floorDiv(grid.phraseStartBeat - phase, 2).coerceAtLeast(0) else -1
                grid.copy(bpm = grid.bpm / 2, beatFrames = frames, downbeatPhase = 0, phraseStartBeat = phrase)
            }
            else -> {
                val n = grid.beatCount
                val frames = LongArray(2 * n - 1)
                for (i in 0 until n) { frames[2 * i] = grid.beatFrames[i]; if (i < n - 1) frames[2 * i + 1] = Math.round((grid.beatFrames[i] + grid.beatFrames[i + 1]) / 2.0) }
                grid.copy(bpm = grid.bpm * 2, beatFrames = frames, downbeatPhase = grid.downbeatPhase * 2, phraseStartBeat = if (grid.phraseStartBeat >= 0) grid.phraseStartBeat * 2 else -1)
            }
        }

        /** Beat index of the original grid → index in [regrid]'s grid. */
        fun beatIndexInRegrid(beat: Int, grid: BeatGrid, relation: TempoRelation): Int = when (relation) {
            TempoRelation.SAME -> beat
            TempoRelation.HALF -> Math.floorDiv(beat - grid.downbeatPhase.coerceIn(0, max(0, grid.beatCount - 1)), 2)
            TempoRelation.DOUBLE -> beat * 2
        }

        /** Bar index (of the original grid's bar features) of a regridded beat. */
        fun barInOriginal(regridBeat: Int, grid: BeatGrid, relation: TempoRelation): Int {
            val original = when (relation) {
                TempoRelation.SAME -> regridBeat
                TempoRelation.HALF -> regridBeat * 2 + grid.downbeatPhase
                TempoRelation.DOUBLE -> Math.floorDiv(regridBeat, 2)
            }
            return if (grid.isEmpty) original / 4 else grid.barOfBeat(original)
        }

        /**
         * Master grid built from A's own (window-relative) beat frames from beat [aStart] — A's time map is then
         * the identity — with the last [settleBeats] beats gliding (S-curve) from A's tempo to [bpmEnd], the last
         * beat exactly at [bpmEnd] so the incoming deck leaves at ratio 1.0. Frame 0 = A's beat [aStart].
         */
        fun ownMaster(sr: Int, gridA: BeatGrid, aStart: Int, bodyBeats: Int, settleBeats: Int, bpmEnd: Double, bpb: Int): MasterGrid {
            require(bodyBeats >= 1) { "bodyBeats must be >= 1" }
            val f0 = gridA.frameOfBeatExact(aStart.toDouble(), sr)
            val frames = LongArray(bodyBeats + 1)
            val bpm = DoubleArray(bodyBeats)
            val plain = bodyBeats - settleBeats.coerceIn(0, bodyBeats)
            for (k in 0..plain) frames[k] = Math.round(gridA.frameOfBeatExact((aStart + k).toDouble(), sr) - f0)
            for (k in 0 until plain) bpm[k] = 60.0 * sr / (frames[k + 1] - frames[k]).toDouble()
            val settle = bodyBeats - plain
            if (settle > 0) {
                val bpmA = if (plain > 0) bpm[plain - 1] else 60.0 * sr / (gridA.frameOfBeatExact((aStart + 1).toDouble(), sr) - f0)
                var acc = frames[plain].toDouble()
                for (j in 0 until settle) {
                    val t = if (settle > 1) j.toDouble() / (settle - 1) else 1.0
                    val v = bpmA + (bpmEnd - bpmA) * Curves.sCurve(t)
                    bpm[plain + j] = v
                    acc += 60.0 * sr / v
                    frames[plain + j + 1] = Math.round(acc)
                }
            }
            return MasterGrid(sr, frames, bpm, bpb)
        }

        /** `out[start + i] = out * t + dry[dryOffset + i] * (1 - t)` over [frames] frames (linear: near-coherent material). */
        fun seamFadeIn(out: AudioBuffer, dry: AudioBuffer, dryOffset: Int, start: Int, frames: Int = SEAM_FADE_FRAMES) {
            val n = min(frames, min(out.frames - start, dry.frames - dryOffset)).coerceAtLeast(0)
            for (c in 0 until out.channelCount) {
                val o = out[c]; val d = dry[c]
                for (i in 0 until n) { val t = i.toFloat() / n; o[start + i] = o[start + i] * t + d[dryOffset + i] * (1f - t) }
            }
        }

        /** Mirror of [seamFadeIn]: the [frames] output frames ending at [end] blend into the dry samples ending at [dryEnd]. */
        fun seamFadeOut(out: AudioBuffer, dry: AudioBuffer, dryEnd: Int, end: Int, frames: Int = SEAM_FADE_FRAMES) {
            val n = min(frames, min(end, dryEnd)).coerceAtLeast(0)
            for (c in 0 until out.channelCount) {
                val o = out[c]; val d = dry[c]
                for (i in 0 until n) { val t = (i + 1).toFloat() / n; val k = end - n + i; o[k] = o[k] * (1f - t) + d[dryEnd - n + i] * t }
            }
        }
    }
}
