package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
import dev.muisc.dsp.stems.PseudoStemSeparator
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
import dev.muisc.transitions.core.relativeTo
import dev.muisc.transitions.sdk.ParamSet
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * `drumBreakBridge` — a drums-only bridge between two beat-matched songs (DESIGN §4 #7).
 *
 * **What it sounds like.** A's melodic content (bass, pads, vocals: everything that is not the drum stem) fades
 * away over [P.breakBars], leaving A's drums playing alone for [P.soloBars] — a classic breakdown. During the
 * solo an optional high-pass ramp thins A's kick out from 20 Hz to [P.hpfToHz] so the ear expects something new.
 * Then B's drums, phase-locked to the same master grid, take over from A's drums in a [P.drumCrossBars]-bar
 * crossfade, and B's remaining stems fade in over [P.bFillBars] bars. The last [P.settleBars] bars glide the
 * tempo from A's to B's so B leaves the segment at stretch ratio 1.0.
 *
 * **When the planner picks it.** Beat-matchable pairs within `prefs.maxStretchPercent`, preferably A with a
 * `BEAT_OUTRO` or B with a `BEAT_INTRO`. A's tail must be percussive (mean bar percussiveness over the solo
 * region > [PERCUSSIVE_MIN]) or the "break" is thin; pairs without bar features are allowed with a lower score.
 * Pseudo-stems are fine here: the drum stem is the cleanest pseudo-stem (HPSS percussive component).
 *
 * **How it is rendered.** Same substrate as [StemSwapStrategy]: master grid from A's own beats (+ settle
 * glide, or [MasterGrid.fromPlan] under the tempo-glide modifier), [PhaseLockedDeck] per deck, then the
 * *aligned* decks are separated with [PseudoStemSeparator]. A is only rendered up to the end of the drum
 * crossfade, B only from the start of it (B's `mixInBeat` lands exactly there). The HPF is a state-variable
 * high-pass with an exponential cutoff ramp over the solo, held during the crossfade.
 *
 * **Segment layout** (see [Splice]): `[0, G)` dry A pre-roll, body of `breakBars + soloBars + drumCrossBars +
 * bFillBars` master bars, `[.., +G)` dry B post-roll. 23 ms linear seam fades join the dry guards to the
 * stretched decks.
 *
 * **Parameters.** `breakBars` how quickly A's non-drum stems leave; `soloBars` length of the drum solo
 * (0 = straight into the handover); `drumCrossBars` length of the drum handover; `bFillBars` how quickly B's
 * other stems arrive after its drums (0 = B enters complete); `hpfToHz` the HPF target (20 = off); `settleBars`
 * tempo settle; `law` crossfade law; `alignToPhrase` start the break on an A phrase start (else on the bar that
 * makes A's drums end exactly at the handover).
 *
 * **Failure modes.** Harmonic residue in the drum stem (tonal percussion, kick tails bleeding into bass) makes
 * the "solo" less clean than a real stem; a wrong downbeat on A puts the break on beat 3; if A's bar features
 * are missing the strategy assumes drums run to A's last downbeat.
 */
class DrumBreakBridgeStrategy : TransitionStrategy {
    /** Parameters. */
    object P : ParamSet(ID) {
        val breakBars = int("breakBars", "Break", 4, 1, 16, "bars", "Bars over which A's non-drum stems fade out")
        val soloBars = int("soloBars", "Drum solo", 4, 0, 16, "bars", "Bars of A's drums alone before B's drums take over")
        val drumCrossBars = int("drumCrossBars", "Drum handover", 1, 1, 4, "bars", "Length of the A drums → B drums crossfade")
        val bFillBars = int("bFillBars", "B fill-in", 4, 0, 16, "bars", "Bars over which B's non-drum stems fade in after the handover")
        val hpfToHz = double("hpfToHz", "HPF ramp to", 400.0, 20.0, 4000.0, "Hz", "High-pass ramp on A's drums during the solo, 20 Hz → this (20 = off)")
        val hpfQ = double("hpfQ", "HPF resonance", 0.7071, 0.5, 4.0, "", "Q of the drum high-pass")
        val settleBars = int("settleBars", "Tempo settle", 1, 0, 4, "bars", "Bars at the end over which the master tempo glides from A's to B's")
        val law = choice("law", "Crossfade law", FadeLaw.EQUAL_POWER, listOf(FadeLaw.EQUAL_POWER, FadeLaw.LINEAR, FadeLaw.S_CURVE), "Gain law of the stem fades")
        val alignToPhrase = bool("alignToPhrase", "Align to phrase", true, "Start the break on an A phrase start (else on the bar that ends A's drums at the handover)")
        val wsolaFrameMs = double("wsolaFrameMs", "WSOLA frame", 40.0, 20.0, 80.0, "ms", "Frame length of the key-lock stretcher (longer = smoother sustains, more transient smearing; kicks are pinned anyway)")
        val wsolaToleranceMs = double("wsolaToleranceMs", "WSOLA tolerance", 10.0, 2.0, 20.0, "ms", "Search range of the key-lock stretcher's waveform alignment")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Drum break bridge"
    override val description: String get() = "A's drums alone bridge into B's drums, then B fills in."
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val blockers = ArrayList<String>()
        val reasons = ArrayList<String>()
        if (a.grid.beatCount < 2 || b.grid.beatCount < 2) blockers += "a beat grid is missing (A ${a.grid.beatCount} beats, B ${b.grid.beatCount} beats)"
        if (!features.beatMatchable) blockers += "not beat-matchable (grid confidence ${"%.2f".format(features.gridConfidenceA)}/${"%.2f".format(features.gridConfidenceB)} < 0.5)"
        if (features.stretchPercent > prefs.maxStretchPercent) blockers += "tempo ${"%.1f".format(features.stretchPercent)} % stretch > ${"%.1f".format(prefs.maxStretchPercent)} % limit"
        val percA = tailPercussiveness(a)
        if (!percA.isNaN() && percA <= PERCUSSIVE_MIN) blockers += "A's tail is not percussive enough (${"%.2f".format(percA)} ≤ $PERCUSSIVE_MIN): the drum break would be thin"
        val percB = headPercussiveness(b)

        val sigma = prefs.maxStretchPercent / 1.5
        var sTempo = exp(-(features.stretchPercent / sigma).let { it * it })
        if (features.tempoRelation != TempoRelation.SAME) sTempo *= 0.85
        val sKey = StemSwapStrategy.keyScore(features)
        val sEnergy = exp(-(features.loudnessDeltaLu / 6.0).let { it * it }) * (1.0 - 0.5 * abs(features.energyDelta))
        val sVocal = 1.0 - features.vocalClash.coerceIn(0.0, 1.0)
        val sGrid = min(features.gridConfidenceA, features.gridConfidenceB).coerceIn(0.0, 1.0)
        val sStruct = structurePrior(features.outro, features.intro)
        val sPerc = (if (percA.isNaN()) 0.6 else percA.coerceIn(0.0, 1.0)) * 0.7 + (if (percB.isNaN()) 0.6 else percB.coerceIn(0.0, 1.0)) * 0.3
        val bpb = a.grid.beatsPerBar.coerceAtLeast(1)
        val need = ROOM_BARS * bpb
        val roomB = StemSwapStrategy.introRoomBeats(b)
        val sRoom = min(1.0, features.outroBeatsAvailable / need.toDouble()) * min(1.0, roomB / need.toDouble())
        if (sRoom < 0.5) blockers += "not enough room: A has ${features.outroBeatsAvailable} beats after its mix-out cue, B $roomB beats after its mix-in cue (need $need each)"
        val fit = 0.20 * sTempo + 0.10 * sKey + 0.10 * sEnergy + 0.05 * sVocal + 0.15 * sGrid + 0.15 * sStruct + 0.05 * sRoom + 0.20 * sPerc
        reasons += "tempo ${"%.1f".format(features.stretchPercent)} % stretch${if (features.tempoRelation != TempoRelation.SAME) " (${features.tempoRelation.name.lowercase()}-time)" else ""} (s_tempo ${"%.2f".format(sTempo)})"
        reasons += "keys ${(a.outroKey ?: a.key).camelot.code}→${(b.introKey ?: b.key).camelot.code} distance ${features.camelotDistanceAfterShift} (s_key ${"%.2f".format(sKey)})"
        reasons += "structure ${features.outro}→${features.intro} (s_struct ${"%.2f".format(sStruct)}), percussiveness A ${fmtOrUnknown(percA)} / B ${fmtOrUnknown(percB)} (s_perc ${"%.2f".format(sPerc)})"
        reasons += "loudness Δ ${"%.1f".format(features.loudnessDeltaLu)} LU, vocal clash ${"%.2f".format(features.vocalClash)}, room ${features.outroBeatsAvailable}/$roomB beats (s_room ${"%.2f".format(sRoom)})"
        return if (blockers.isEmpty()) Applicability.of(fit, *reasons.toTypedArray()) else Applicability(0.0, reasons, blockers)
    }

    // -------------------------------------------------------------------------------------------------- plan

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p0 = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES.toLong()
        val notes = ArrayList<String>()
        val gridA = StemSwapStrategy.scaledGrid(a.grid, sr / a.sampleRate.toDouble())
        val gridB = StemSwapStrategy.regrid(StemSwapStrategy.scaledGrid(b.grid, sr / b.sampleRate.toDouble()), features.tempoRelation)
        require(gridA.beatCount >= 2 && gridB.beatCount >= 2) { "drumBreakBridge needs beat grids on both tracks" }
        val bpb = gridA.beatsPerBar.coerceAtLeast(1)

        // --- where A's drums end -----------------------------------------------------------------------------
        val trimEndA = Math.round(a.trimEndFrame * sr / a.sampleRate.toDouble())
        val lastBeatA = floor(gridA.beatAtFrame(trimEndA)).toInt()
        val lastDrumBar = lastPercussiveBar(a) ?: gridA.barOfBeat(if (a.cues.lastDownbeat >= 0) a.cues.lastDownbeat else lastBeatA)
        val drumsEndBeat = min(gridA.beatOfBar(lastDrumBar + 1), lastBeatA + 1)
        var breakBars = p0.int(P.breakBars); var soloBars = p0.int(P.soloBars); val crossBars = p0.int(P.drumCrossBars); var fillBars = p0.int(P.bFillBars)
        // shrink solo, then break, until A has room before its drums end
        val firstBeatA = gridA.nextDownbeat(0.0)
        while (drumsEndBeat - (breakBars + soloBars + crossBars) * bpb < firstBeatA && soloBars > 0) soloBars--
        while (drumsEndBeat - (breakBars + soloBars + crossBars) * bpb < firstBeatA && breakBars > 1) breakBars--
        var aStart = drumsEndBeat - (breakBars + soloBars + crossBars) * bpb
        if (p0.bool(P.alignToPhrase)) {
            val ph = gridA.previousPhraseStart(aStart.toDouble())
            if (ph >= firstBeatA) { if (ph != aStart) notes += "break moved from A beat $aStart back to phrase start $ph"; aStart = ph }
        }
        aStart = aStart.coerceAtLeast(firstBeatA)
        if (breakBars != p0.int(P.breakBars) || soloBars != p0.int(P.soloBars)) notes += "break/solo shortened to $breakBars/$soloBars bars: A's drums end at beat $drumsEndBeat"

        // --- B: mix-in cue lands on the handover ----------------------------------------------------------
        val mixInRaw = when { b.cues.mixInBeat >= 0 -> b.cues.mixInBeat; b.cues.firstDownbeat >= 0 -> b.cues.firstDownbeat; else -> -1 }
        val trimStartB = Math.round(b.trimStartFrame * sr / b.sampleRate.toDouble())
        val bStart = if (mixInRaw >= 0) StemSwapStrategy.beatIndexInRegrid(mixInRaw, b.grid, features.tempoRelation) else gridB.nextDownbeat(gridB.beatAtFrame(trimStartB))
        val trimEndB = Math.round(b.trimEndFrame * sr / b.sampleRate.toDouble())
        val roomB = (floor(gridB.beatAtFrame(trimEndB)).toInt() + 1 - bStart) / bpb
        if (roomB < crossBars + fillBars) { fillBars = (roomB - crossBars).coerceAtLeast(0); notes += "B fill shortened to $fillBars bars: B has $roomB bars after its mix-in" }

        val handoverBeat = (breakBars + soloBars) * bpb
        val handoverEndBeat = handoverBeat + crossBars * bpb
        val bodyBeats = handoverEndBeat + fillBars * bpb
        val settleBeats = if (features.stretchPercent > StemSwapStrategy.SETTLE_MIN_PERCENT) min(p0.int(P.settleBars) * bpb, bodyBeats) else 0
        val bpmEnd = gridA.bpm * features.tempoRatio
        val master = StemSwapStrategy.ownMaster(sr, gridA, aStart, bodyBeats, settleBeats, bpmEnd, bpb)

        val aExit = gridA.frameOfBeat(aStart.toDouble()) - g
        val aWindow = FrameRange(aExit - StemSwapStrategy.LEAD_FRAMES, gridA.frameOfBeat((aStart + handoverEndBeat).toDouble()) + StemSwapStrategy.PAD_FRAMES)
        val bEndBeat = bStart + (bodyBeats - handoverBeat)
        val bEntry = gridB.frameOfBeat(bEndBeat.toDouble()) + g
        val bWindow = FrameRange(gridB.frameOfBeat(bStart.toDouble()) - StemSwapStrategy.PAD_FRAMES, bEntry + StemSwapStrategy.PAD_FRAMES)
        val expected = (2 * g + master.totalFrames).toInt()
        val p = p0.with(K_A_START, aStart).with(K_B_START, bStart).with(K_BREAK, breakBars * bpb).with(K_HANDOVER, handoverBeat)
            .with(K_HANDOVER_END, handoverEndBeat).with(K_BODY_BEATS, bodyBeats).with(K_SETTLE, settleBeats)
        val lanes = gainLanes(master, g, breakBars * bpb, handoverBeat, handoverEndBeat, CrossfadeLaw.parse(p0.choice(P.law)) ?: FadeLaw.EQUAL_POWER).map { it.toAutomationLane(sr) } +
            hpfLane(master, g, breakBars * bpb, handoverBeat, handoverEndBeat, p0.double(P.hpfToHz)).toAutomationLane(sr)
        notes.add(0, "drum break from A beat $aStart (bar ${gridA.barOfBeat(aStart)}): $breakBars-bar break, $soloBars-bar drum solo${if (p0.double(P.hpfToHz) > HPF_OFF_HZ) " with HPF 20 → ${"%.0f".format(p0.double(P.hpfToHz))} Hz" else ""}, $crossBars-bar drum handover, $fillBars-bar B fill (${bodyBeats / bpb} bars total)")
        notes += "B beat $bStart (${if (mixInRaw >= 0) "mixInBeat $mixInRaw" else "first downbeat after trim"}${if (features.tempoRelation != TempoRelation.SAME) ", ${features.tempoRelation.name.lowercase()}-time grid" else ""}) lands on master beat $handoverBeat; B stretched ${"%.2f".format(features.stretchPercent)} %"
        notes += if (settleBeats > 0) "last $settleBeats beats glide to B's tempo so B leaves at ratio 1.0" else "no tempo settle"
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
        val gridA = StemSwapStrategy.scaledGrid(a.grid, sr / a.sampleRate.toDouble()).relativeTo(plan.aWindow.start)
        val gridB = StemSwapStrategy.regrid(StemSwapStrategy.scaledGrid(b.grid, sr / b.sampleRate.toDouble()), f.tempoRelation).relativeTo(plan.bWindow.start)
        val glide = plan.params[MasterGrid.PARAM_MODE]?.equals(MasterGrid.MODE_GLIDE, ignoreCase = true) == true
        val master = if (glide) MasterGrid.fromPlan(plan, a, b, f, ctx.prefs)
        else StemSwapStrategy.ownMaster(sr, gridA, aStart, geom(plan, K_BODY_BEATS), geom(plan, K_SETTLE), gridA.bpm * f.tempoRatio, bpb)
        val bodyBeats = master.beatCount
        val bodyFrames = master.totalFrames.toInt()
        val breakBeats = min(geom(plan, K_BREAK), bodyBeats)
        val handoverBeat = min(geom(plan, K_HANDOVER), bodyBeats - 1)
        val handoverEnd = min(geom(plan, K_HANDOVER_END), bodyBeats).coerceAtLeast(handoverBeat + 1)

        val options = PhaseLockedDeck.Options(keyLock = ctx.prefs.keyLock, wsolaFrameMs = p.double(P.wsolaFrameMs), wsolaToleranceMs = p.double(P.wsolaToleranceMs))
        val deckA = PhaseLockedDeck(input.aAudio, gridA, aStart.toDouble(), StemSwapStrategy.scaledOnsets(a, sr).relativeTo(plan.aWindow.start, plan.aWindow.length.toLong()), sampleRate = sr, options = options)
        val renderedA = deckA.render(master, 0, handoverEnd)
        ctx.progress(0.2)
        val deckB = PhaseLockedDeck(input.bAudio, gridB, bStart.toDouble(), StemSwapStrategy.scaledOnsets(b, sr).relativeTo(plan.bWindow.start, plan.bWindow.length.toLong()), sampleRate = sr, options = options)
        val renderedB = deckB.render(master, handoverBeat, bodyBeats - handoverBeat)
        ctx.progress(0.4)
        val stemsA = PseudoStemSeparator().separate(renderedA)
        val stemsB = PseudoStemSeparator().separate(renderedB)
        ctx.progress(0.7)

        // HPF ramp on A's drums over the solo (from the end of the break to the handover), held through the handover.
        val hpfTo = p.double(P.hpfToHz)
        if (hpfTo > HPF_OFF_HZ) {
            val from = master.beatFrames[breakBeats].toInt(); val soloEnd = master.beatFrames[handoverBeat].toInt(); val to = renderedA.frames
            val n = to - from
            if (n > 0) {
                val ch = stemsA.channelCount
                val tmp = Array(ch) { stemsA.drums[it].copyOfRange(from, to) }
                val svf = StateVariableFilter(sr, ch, HPF_START_HZ, p.double(P.hpfQ))
                svf.mode = SvfMode.HIGH_PASS
                svf.setCutoffRamp(HPF_START_HZ, hpfTo, (soloEnd - from).coerceAtLeast(1), exponential = true)
                // The SVF interpolates its integrator gain `tan(pi f / fs)` linearly within one block, so a sweep
                // that spans seconds must be fed in short blocks to actually follow the geometric curve.
                var done = 0
                val slice = Array(ch) { FloatArray(RAMP_BLOCK) }
                while (done < n) {
                    val m = min(RAMP_BLOCK, n - done)
                    for (c in 0 until ch) System.arraycopy(tmp[c], done, slice[c], 0, m)
                    svf.process(slice, slice, m)
                    for (c in 0 until ch) System.arraycopy(slice[c], 0, tmp[c], done, m)
                    done += m
                }
                for (c in 0 until ch) System.arraycopy(tmp[c], 0, stemsA.drums[c], from, n)
            }
        }

        val lanes = gainLanes(master, g.toLong(), breakBeats, handoverBeat, handoverEnd, law)
        val out = AudioBuffer.silence(sr, input.aAudio.channelCount, g + bodyFrames + g)
        val aExitOff = plan.aExitOffset
        for (c in 0 until out.channelCount) System.arraycopy(input.aAudio[c], aExitOff, out[c], 0, g)
        Splice.addInPlace(out, stemsA.drums, g, lanes[0])
        for (s in listOf(stemsA.bass, stemsA.vocals, stemsA.other)) Splice.addInPlace(out, s, g, lanes[1])
        val bOffset = g + master.beatFrames[handoverBeat].toInt()
        Splice.addInPlace(out, stemsB.drums, bOffset, lanes[2])
        for (s in listOf(stemsB.bass, stemsB.vocals, stemsB.other)) Splice.addInPlace(out, s, bOffset, lanes[3])
        StemSwapStrategy.seamFadeIn(out, input.aAudio, aExitOff + g, g)
        val bEntryOff = plan.bEntryOffset
        StemSwapStrategy.seamFadeOut(out, input.bAudio, bEntryOff - g, g + bodyFrames)
        for (c in 0 until out.channelCount) System.arraycopy(input.bAudio[c], bEntryOff - g, out[c], g + bodyFrames, g)
        ctx.progress(0.85)

        val fin = RenderReports.finalize(out, ctx)
        val markers = listOf(
            Marker(g.toLong(), MARKER_BREAK),
            Marker(g + master.beatFrames[breakBeats], MARKER_SOLO),
            Marker(g + master.beatFrames[handoverBeat], MARKER_B_DRUMS),
            Marker(g + master.beatFrames[handoverEnd], MARKER_A_GONE),
            Marker((g + bodyFrames).toLong(), MARKER_B_FULL),
        )
        val beatLane = AutomationLane(StemSwapStrategy.LANE_MASTER_BEAT, (0..bodyBeats).map { LanePoint((g + master.beatFrames[it]) / sr.toDouble(), it.toDouble()) })
        val bpmLane = AutomationLane(StemSwapStrategy.LANE_MASTER_BPM, (0 until bodyBeats).map { LanePoint((g + master.beatFrames[it]) / sr.toDouble(), master.bpmPerBeat[it]) })
        val ratio = FloatArray(bodyBeats) { k -> if (k >= handoverBeat) deckB.ratioTrace[k - handoverBeat].toFloat() else deckA.ratioTrace[k].toFloat() }
        val metrics = mapOf(
            "bodyBeats" to bodyBeats.toDouble(), "bodyFrames" to bodyFrames.toDouble(), "guardFrames" to g.toDouble(),
            "stretchModeB" to (deckB.modeUsed?.ordinal?.toDouble() ?: -1.0), "maxRatioB" to (deckB.ratioTrace.maxOrNull() ?: 1.0),
            "limited" to (if (fin.limited) 1.0 else 0.0), "gainReductionDb" to fin.gainReductionDb,
        )
        val report = RenderReports.build(out, (System.nanoTime() - t0) / 1_000_000, ratioTrace = ratio, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        val renderLanes = lanes.map { it.toAutomationLane(sr) } + hpfLane(master, g.toLong(), breakBeats, handoverBeat, handoverEnd, hpfTo).toAutomationLane(sr) + beatLane + bpmLane
        val ids = renderLanes.map { it.id }.toSet()
        return RenderedTransition(plan.copy(lanes = plan.lanes.filter { it.id !in ids } + renderLanes), out, markers, report)
    }

    /** Four gain lanes in output frames: A drums, A rest, B drums, B rest. */
    private fun gainLanes(master: MasterGrid, g: Long, breakBeats: Int, handoverBeat: Int, handoverEnd: Int, law: FadeLaw): List<Lane> {
        val n = master.beatCount
        fun fr(beat: Int) = g + master.beatFrames[beat.coerceIn(0, n)]
        val end = fr(n)
        val aDrums = Lane(LANE_A_DRUMS).add(g, 1.0).add(fr(handoverBeat), 1.0, law).add(fr(handoverEnd), 0.0)
        val aRest = Lane(LANE_A_REST).add(g, 1.0, law).add(fr(breakBeats), 0.0)
        val bDrums = Lane(LANE_B_DRUMS).add(fr(handoverBeat), 0.0, law).add(fr(handoverEnd), 1.0)
        val bRest = if (end > fr(handoverEnd)) Lane(LANE_B_REST).add(fr(handoverBeat), 0.0).add(fr(handoverEnd), 0.0, law).add(end, 1.0)
        else Lane(LANE_B_REST).add(fr(handoverBeat), 0.0, law).add(fr(handoverEnd), 1.0)
        return listOf(aDrums, aRest, bDrums, bRest)
    }

    /** The HPF cutoff (Hz) on A's drums as a lane: 20 Hz until the solo, geometric ramp to [hpfTo] over the solo, held through the handover. */
    private fun hpfLane(master: MasterGrid, g: Long, breakBeats: Int, handoverBeat: Int, handoverEnd: Int, hpfTo: Double): Lane {
        val lane = Lane(LANE_HPF).add(g, HPF_START_HZ)
        if (hpfTo <= HPF_OFF_HZ) return lane
        val f0 = g + master.beatFrames[breakBeats]; val f1 = g + master.beatFrames[handoverBeat]
        lane.add(f0, HPF_START_HZ)
        val steps = 8
        for (i in 1..steps) { val t = i.toDouble() / steps; lane.add(f0 + Math.round((f1 - f0) * t), HPF_START_HZ * (hpfTo / HPF_START_HZ).pow(t)) }
        lane.add(g + master.beatFrames[handoverEnd], hpfTo)
        return lane
    }

    private fun geom(plan: TransitionPlan, key: String): Int =
        plan.params[key]?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: throw IllegalArgumentException("drumBreakBridge plan lacks geometry key '$key' (plan() must produce it)")

    companion object {
        const val ID = "drumBreakBridge"
        const val ROOM_BARS = 8
        const val PERCUSSIVE_MIN = 0.5
        const val HPF_START_HZ = 20.0
        const val HPF_OFF_HZ = 20.0
        /** Block length the HPF sweep is fed to the SVF in (the filter interpolates its gain per block). */
        const val RAMP_BLOCK = 256
        const val MARKER_BREAK = "break starts"
        const val MARKER_SOLO = "drum solo"
        const val MARKER_B_DRUMS = "B drums in"
        const val MARKER_A_GONE = "A drums out"
        const val MARKER_B_FULL = "B full"
        const val LANE_A_DRUMS = "gainA.drums"; const val LANE_A_REST = "gainA.rest"
        const val LANE_B_DRUMS = "gainB.drums"; const val LANE_B_REST = "gainB.rest"
        const val LANE_HPF = "hpfA.drumsHz"
        private const val K_A_START = "geom.aStartBeat"; private const val K_B_START = "geom.bStartBeat"; private const val K_BREAK = "geom.breakBeats"
        private const val K_HANDOVER = "geom.handoverBeat"; private const val K_HANDOVER_END = "geom.handoverEndBeat"; private const val K_BODY_BEATS = "geom.bodyBeats"
        private const val K_SETTLE = "geom.settleBeats"

        /** Structure prior: a beat on either edge is what this bridge wants. */
        fun structurePrior(outro: OutroType, intro: IntroType): Double {
            val o = when (outro) { OutroType.BEAT_OUTRO -> 1.0; OutroType.UNKNOWN -> 0.6; OutroType.AMBIENT_OUTRO, OutroType.VOCAL_OUTRO -> 0.5; OutroType.HARD_STOP -> 0.5; OutroType.FADE_OUT -> 0.3 }
            val i = when (intro) { IntroType.BEAT_INTRO -> 1.0; IntroType.COLD_START -> 0.7; IntroType.UNKNOWN -> 0.6; IntroType.AMBIENT_INTRO, IntroType.VOCAL_INTRO -> 0.5; IntroType.SILENCE -> 0.3 }
            return max(o, i) * 0.6 + min(o, i) * 0.4
        }

        /** Mean percussiveness of the 8 bars before A's mix-out cue (or its last 8 bars); NaN without bar features. */
        fun tailPercussiveness(a: TrackAnalysis): Double {
            val perc = a.bars.percussiveness
            if (perc.isEmpty()) return Double.NaN
            val end = if (a.cues.mixOutBeat >= 0 && !a.grid.isEmpty) a.grid.barOfBeat(a.cues.mixOutBeat).coerceIn(1, perc.size) else perc.size
            val start = max(0, end - 8)
            return (start until end).map { perc[it].toDouble() }.average()
        }

        /** Mean percussiveness of the 8 bars from B's mix-in cue; NaN without bar features. */
        fun headPercussiveness(b: TrackAnalysis): Double {
            val perc = b.bars.percussiveness
            if (perc.isEmpty()) return Double.NaN
            val start = if (b.cues.mixInBeat >= 0 && !b.grid.isEmpty) b.grid.barOfBeat(b.cues.mixInBeat).coerceIn(0, perc.size - 1) else 0
            return (start until min(perc.size, start + 8)).map { perc[it].toDouble() }.average()
        }

        /** Last bar whose percussiveness exceeds [PERCUSSIVE_MIN], or null without bar features / no such bar. */
        fun lastPercussiveBar(a: TrackAnalysis): Int? {
            val perc = a.bars.percussiveness
            if (perc.isEmpty()) return null
            for (i in perc.indices.reversed()) if (perc[i] > PERCUSSIVE_MIN) return i
            return null
        }

        private fun fmtOrUnknown(v: Double) = if (v.isNaN()) "unknown" else "%.2f".format(v)
    }
}
