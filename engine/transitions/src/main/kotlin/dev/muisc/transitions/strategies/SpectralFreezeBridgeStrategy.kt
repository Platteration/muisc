package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
import dev.muisc.dsp.gain.Curves
import dev.muisc.dsp.texture.SpectralFreeze
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * `spectralFreezeBridge` — freeze A's last chord and let B start underneath it (DESIGN §4 #12).
 *
 * **What it sounds like.** A plays to its freeze point; over [P.freezeFadeMs] its live signal crossfades into
 * a Paulstretch-style spectral freeze of its last [P.freezeSourceMs] (the `dsp` [SpectralFreeze]: the
 * magnitude spectrum of that moment held with random phases, i.e. the chord sustains as a stationary pad).
 * The pad holds for [P.holdBeats] beats of B's tempo while a low-pass darkens it from 20 kHz down to
 * [P.darkenToHz], reaching that cutoff exactly at the end of the hold and keeping it through the release
 * (at 20 kHz the filter is bypassed entirely); B's mix-in downbeat lands [P.bEnterBeat] beats into the hold with a [P.bFadeBars]-bar
 * fade-in, and the frozen chord releases over [P.releaseBars] bars of B's grid while B fills the space.
 * No time-stretching and no beat-matching: A's grid is irrelevant, B is untouched.
 *
 * **When the planner picks it.** A ends on something sustained: `AMBIENT_OUTRO`, `VOCAL_OUTRO` or a
 * `HARD_STOP` whose last bars are harmonic (bar percussiveness gives a harmonic share > [HARMONIC_MIN];
 * without bar features the gate is open) and with few onsets in the last 2 s (a soft score term). Any tempo
 * relation; B's tempo only times the hold and the release.
 *
 * **Segment layout** (see [Splice]): `[0, G)` dry A pre-roll, `[G, tF)` A fades into the freeze,
 * `[tF, tF + hold)` the frozen chord (B enters inside it), `[.., tEnd)` release, `[tEnd, tEnd + G)` dry B
 * post-roll. The freeze is generated for exactly its lane's span and ends at 0, so no tail leaks into B's body.
 *
 * **Parameters.** `holdBeats` how long the chord stands still (longer = more suspense); `releaseBars` how
 * fast it dissolves once B is in; `darkenToHz` how dark the freeze gets (20 kHz = no darkening, 500 Hz = a
 * distant rumble by the end); `freezeDb` level of the frozen chord relative to A's last moment; `bEnterBeat`
 * when B's downbeat lands inside the hold (0 = together with the freeze, holdBeats = only after it);
 * `bFadeBars` B's fade-in; `freezeSourceMs` how much of A's ending is analysed (shorter = purer chord, longer
 * = more of the outro's motion averaged in); `freezeFadeMs` the A → freeze crossfade; `freezeBeforeEndSec`
 * moves the freeze point earlier than A's trimmed end (useful when A's last second is a decaying tail).
 *
 * **Failure modes.** A noisy or percussive last moment freezes into hiss (hence the harmonic gate); a very
 * long hold on a dissonant chord under B's harmony can clash (keys are scored, not gated); a freeze taken from
 * a fade-out tail is quiet and `freezeDb` may need boosting.
 */
class SpectralFreezeBridgeStrategy : TransitionStrategy {
    /** Parameters. */
    object P : ParamSet(ID) {
        val holdBeats = int("holdBeats", "Hold", 8, 1, 64, "beats", "Beats of B's tempo the frozen chord holds at full level")
        val releaseBars = int("releaseBars", "Release", 2, 1, 8, "bars", "Bars of B's grid over which the freeze fades out")
        val darkenToHz = double("darkenToHz", "Darken to", 2000.0, 100.0, 20000.0, "Hz", "Low-pass cutoff the freeze reaches at the END of the hold and keeps through the release (20 kHz = no darkening at all, filter bypassed)")
        val freezeDb = double("freezeDb", "Freeze level", -6.0, -30.0, 6.0, "dB", "Level of the frozen chord relative to A's last moment")
        val bEnterBeat = int("bEnterBeat", "B enters at", 4, 0, 64, "beats", "Beats into the hold at which B's mix-in downbeat lands (clamped to holdBeats)")
        val bFadeBars = int("bFadeBars", "B fade-in", 1, 0, 4, "bars", "Length of B's fade-in")
        val freezeSourceMs = double("freezeSourceMs", "Freeze source", 400.0, 100.0, 2000.0, "ms", "Length of A's ending that is spectrally averaged into the freeze")
        val freezeFadeMs = double("freezeFadeMs", "Freeze fade", 200.0, 20.0, 1000.0, "ms", "Crossfade from A's live signal into the freeze")
        val freezeBeforeEndSec = double("freezeBeforeEndSec", "Freeze before end", 0.0, 0.0, 10.0, "s", "Freeze point this many seconds before A's trimmed end")
        val releaseLaw = choice("releaseLaw", "Release law", FadeLaw.EQUAL_POWER, listOf(FadeLaw.EQUAL_POWER, FadeLaw.EXP, FadeLaw.LINEAR, FadeLaw.S_CURVE), "Gain law of the freeze release")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Spectral freeze bridge"
    override val description: String get() = "A's last chord is frozen into a pad that B starts underneath."
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val blockers = ArrayList<String>()
        val reasons = ArrayList<String>()
        val harmonic = harmonicShare(a)
        if (!harmonic.isNaN() && harmonic <= HARMONIC_MIN) blockers += "A's last bars are percussive (harmonic share ${"%.2f".format(harmonic)} ≤ $HARMONIC_MIN): a freeze would be hiss"
        val density = tailOnsetDensity(a)
        val sOnset = if (density.isNaN()) 0.7 else (1.0 - density / 2.0).coerceIn(0.0, 1.0)
        val sHarm = if (harmonic.isNaN()) 0.7 else harmonic.coerceIn(0.0, 1.0)
        val sStruct = structurePrior(features.outro, features.intro)
        val sKey = StemSwapStrategy.keyScore(features)
        val sEnergy = exp(-(features.loudnessDeltaLu / 6.0).let { it * it })
        val fit = 0.35 * sStruct + 0.20 * sHarm + 0.15 * sOnset + 0.15 * sKey + 0.15 * sEnergy
        reasons += "structure ${features.outro}→${features.intro} (s_struct ${"%.2f".format(sStruct)})"
        reasons += "A's ending: harmonic share ${if (harmonic.isNaN()) "unknown" else "%.2f".format(harmonic)}, ${if (density.isNaN()) "onset density unknown" else "%.1f onsets/s in the last 2 s".format(density)}"
        reasons += "keys ${(a.outroKey ?: a.key).camelot.code}→${(b.introKey ?: b.key).camelot.code} distance ${features.camelotDistanceAfterShift} (s_key ${"%.2f".format(sKey)}); tempo irrelevant (${"%.1f".format(features.stretchPercent)} % apart, B unstretched)"
        return if (blockers.isEmpty()) Applicability.of(fit, *reasons.toTypedArray()) else Applicability(0.0, reasons, blockers)
    }

    // -------------------------------------------------------------------------------------------------- plan

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val g = Splice.GUARD_FRAMES.toLong()
        val notes = ArrayList<String>()
        val scaleA = sr / a.sampleRate.toDouble(); val scaleB = sr / b.sampleRate.toDouble()
        val geo = geometry(p, a, b, sr)
        val aFreeze = Math.round(a.trimEndFrame * scaleA) - Math.round(p.double(P.freezeBeforeEndSec) * sr)
        val aExit = (aFreeze - g - geo.fade).coerceAtLeast(0L)
        val aWindow = FrameRange(aExit, aFreeze.coerceAtLeast(aExit + g))
        val bMixIn = bMixInFrame(b, scaleB, notes)
        val bEntry = bMixIn + (geo.tEnd - geo.tB) + g
        val bWindow = FrameRange(bMixIn, bEntry)
        val expected = (geo.tEnd + g).toInt()
        val lanes = lanes(geo, p, sr).map { it.toAutomationLane(sr) }
        notes.add(0, "freeze of A's last ${"%.0f".format(p.double(P.freezeSourceMs))} ms at frame $aFreeze (${"%.0f".format(p.double(P.freezeFadeMs))} ms fade), held ${p.int(P.holdBeats)} beats at ${"%.1f".format(geo.bpmB)} BPM, released over ${p.int(P.releaseBars)} bars, darkened to ${"%.0f".format(p.double(P.darkenToHz))} Hz")
        notes += "B enters ${geo.bEnterBeats} beats into the hold with a ${p.int(P.bFadeBars)}-bar fade; no stretch"
        notes += "${Splice.GUARD_FRAMES}-frame dry pre/post-roll"
        return TransitionPlan(
            strategyId = ID, params = p, aExitFrame = aExit, bEntryFrame = bEntry, aWindow = aWindow, bWindow = bWindow,
            expectedOutputFrames = expected, lanes = lanes, notes = notes,
        )
    }

    /** Output-frame geometry shared by plan and render (all derived from the resolved params and B's tempo). */
    private class Geometry(val g: Long, val fade: Long, val tF: Long, val hold: Long, val release: Long, val tB: Long, val bFade: Long, val tEnd: Long, val bpmB: Double, val bEnterBeats: Int, val source: Long)

    private fun geometry(p: Params, a: TrackAnalysis, b: TrackAnalysis, sr: Int): Geometry {
        val g = Splice.GUARD_FRAMES.toLong()
        val bpmB = when { b.grid.bpm > 0 -> b.grid.bpm; b.tempo.bpm > 0 -> b.tempo.bpm; else -> FALLBACK_BPM }
        val period = 60.0 * sr / bpmB
        val bpb = b.grid.beatsPerBar.coerceAtLeast(1)
        val fade = Math.round(p.double(P.freezeFadeMs) / 1000.0 * sr).coerceAtLeast(64L)
        val tF = g + fade
        val hold = Math.round(p.int(P.holdBeats) * period)
        val release = Math.round(p.int(P.releaseBars) * bpb * period)
        val bEnter = min(p.int(P.bEnterBeat), p.int(P.holdBeats))
        val tB = tF + Math.round(bEnter * period)
        val bFade = Math.round(p.int(P.bFadeBars) * bpb * period)
        val tEnd = max(tF + hold + release, tB + bFade)
        val source = Math.round(p.double(P.freezeSourceMs) / 1000.0 * sr)
        return Geometry(g, fade, tF, hold, release, tB, bFade, tEnd, bpmB, bEnter, source)
    }

    private fun bMixInFrame(b: TrackAnalysis, scaleB: Double, notes: MutableList<String>): Long = when {
        b.cues.mixInBeat >= 0 && !b.grid.isEmpty -> Math.round(b.grid.frameOfBeat(b.cues.mixInBeat.toDouble()) * scaleB).also { notes += "B starts at its mixInBeat ${b.cues.mixInBeat}" }
        b.cues.firstDownbeat >= 0 && !b.grid.isEmpty -> Math.round(b.grid.frameOfBeat(b.cues.firstDownbeat.toDouble()) * scaleB).also { notes += "B starts at its first downbeat ${b.cues.firstDownbeat}" }
        else -> Math.round(b.trimStartFrame * scaleB).also { notes += "B starts at its trim start (no cues)" }
    }.coerceAtLeast(0L)

    /** Gain lanes in output frames: A (dry → 0 over the fade), freeze (0 → freezeDb, hold, release → 0), B (0 → 1). */
    private fun lanes(geo: Geometry, p: Params, sr: Int): List<Lane> {
        val law = CrossfadeLaw.parse(p.choice(P.releaseLaw)) ?: FadeLaw.EQUAL_POWER
        val level = Curves.dbToLinear(p.double(P.freezeDb))
        val a = Lane(LANE_A).add(0L, 1.0).add(geo.g, 1.0, FadeLaw.EQUAL_POWER).add(geo.tF, 0.0)
        val freeze = Lane(LANE_FREEZE).add(geo.g, 0.0, FadeLaw.EQUAL_POWER).add(geo.tF, level).add(geo.tF + geo.hold, level, law).add(geo.tF + geo.hold + geo.release, 0.0)
        val b = if (geo.bFade > 0) Lane(LANE_B).add(geo.tB, 0.0, FadeLaw.EQUAL_POWER).add(geo.tB + geo.bFade, 1.0) else Lane(LANE_B).add(geo.tB - 1, 0.0).add(geo.tB, 1.0)
        // Cutoff lane: flat at 20 kHz until the freeze is up, geometric ramp to darkenToHz over the HOLD, then held
        // through the release (so the darkening is complete exactly when the chord starts dissolving).
        val lpf = Lane(LANE_LPF).add(geo.g, LPF_START_HZ).add(geo.tF, LPF_START_HZ)
        val target = p.double(P.darkenToHz)
        if (target < LPF_START_HZ && geo.hold > 0) {
            val steps = 8
            for (i in 1..steps) { val t = i.toDouble() / steps; lpf.add(geo.tF + Math.round(geo.hold * t), LPF_START_HZ * (target / LPF_START_HZ).pow(t)) }
            lpf.add(geo.tF + geo.hold + geo.release, target)
        }
        return listOf(a, freeze, b, lpf)
    }

    // ------------------------------------------------------------------------------------------------ render

    override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val plan = input.plan
        val p = P.resolve(plan.params)
        val sr = ctx.sampleRate
        val geo = geometry(p, input.aAnalysis, input.bAnalysis, sr)
        val g = geo.g.toInt()
        val total = (geo.tEnd + geo.g).toInt()
        val ch = input.aAudio.channelCount
        val aExitOff = plan.aExitOffset
        val aFreezeOff = aExitOff + g + geo.fade.toInt()      // window offset of the freeze point
        require(aFreezeOff <= input.aAudio.frames) { "A window (${input.aAudio.frames} frames) ends before the freeze point ($aFreezeOff)" }

        // --- the frozen chord: analysed from A's last `source` frames before the freeze point -----------------
        val srcStart = (aFreezeOff - geo.source.toInt()).coerceAtLeast(0)
        val excerpt = input.aAudio.slice(srcStart, aFreezeOff)
        val freezeLen = (geo.tF + geo.hold + geo.release - geo.g).toInt()
        val freeze = SpectralFreeze(sr, seed = (ctx.seed xor 0x5f3e).toInt()).render(excerpt, freezeLen)
        ctx.progress(0.4)
        // Darkening: bypassed at 20 kHz (the SVF would colour the top octave near Nyquist for nothing); otherwise
        // the cutoff is flat through the A → freeze fade, ramps geometrically down over the HOLD and stays there
        // for the release, mirroring the LANE_LPF lane exactly.
        val darkenTo = p.double(P.darkenToHz)
        val fadeFrames = geo.fade.toInt()
        if (darkenTo < LPF_START_HZ) {
            val svf = StateVariableFilter(sr, ch, LPF_START_HZ, LPF_Q)
            svf.mode = SvfMode.LOW_PASS
            svf.setCutoff(LPF_START_HZ)
            val head = Array(ch) { freeze[it] }
            svf.process(head, head, min(fadeFrames, freezeLen))
            val rampLen = min(geo.hold.toInt(), freezeLen - fadeFrames)
            if (rampLen > 0) {
                val ramp = Array(ch) { freeze[it].copyOfRange(fadeFrames, fadeFrames + rampLen) }
                svf.setCutoffRamp(LPF_START_HZ, darkenTo, rampLen, exponential = true)
                // The SVF interpolates its integrator gain `tan(pi f / fs)` linearly WITHIN one block, which is
                // wildly non-linear in Hz over a multi-second sweep: the ramp must be fed in short blocks.
                var done = 0
                val slice = Array(ch) { FloatArray(RAMP_BLOCK) }
                while (done < rampLen) {
                    val n = min(RAMP_BLOCK, rampLen - done)
                    for (c in 0 until ch) System.arraycopy(ramp[c], done, slice[c], 0, n)
                    svf.process(slice, slice, n)
                    for (c in 0 until ch) System.arraycopy(slice[c], 0, ramp[c], done, n)
                    done += n
                }
                for (c in 0 until ch) System.arraycopy(ramp[c], 0, freeze[c], fadeFrames, rampLen)
            }
            val tailFrom = fadeFrames + maxOf(rampLen, 0)
            if (freezeLen > tailFrom) {
                val tail = Array(ch) { freeze[it].copyOfRange(tailFrom, freezeLen) }
                svf.setCutoff(darkenTo)
                svf.process(tail, tail, freezeLen - tailFrom)
                for (c in 0 until ch) System.arraycopy(tail[c], 0, freeze[c], tailFrom, freezeLen - tailFrom)
            }
        }
        ctx.progress(0.6)

        // --- mix ---------------------------------------------------------------------------------------
        val lanes = lanes(geo, p, sr)
        val out = AudioBuffer.silence(sr, ch, total)
        Splice.addInPlace(out, input.aAudio, 0, lanes[0], srcOffset = aExitOff, frames = aFreezeOff - aExitOff)
        Splice.addInPlace(out, freeze, g, lanes[1])
        val bEntryOff = plan.bEntryOffset
        val bLen = (geo.tEnd - geo.tB).toInt() + g
        Splice.addInPlace(out, input.bAudio, geo.tB.toInt(), lanes[2], srcOffset = bEntryOff - bLen, frames = bLen)
        ctx.progress(0.8)
        val fin = RenderReports.finalize(out, ctx)
        val markers = listOf(
            Marker(geo.tF, MARKER_FREEZE), Marker(geo.tB, MARKER_B_ENTERS), Marker(geo.tF + geo.hold, MARKER_RELEASE), Marker(geo.tEnd, MARKER_FREEZE_GONE),
        )
        val metrics = mapOf(
            "freezeFrames" to freezeLen.toDouble(), "holdFrames" to geo.hold.toDouble(), "releaseFrames" to geo.release.toDouble(),
            "excerptRms" to excerpt.rms().toDouble(), "limited" to (if (fin.limited) 1.0 else 0.0), "gainReductionDb" to fin.gainReductionDb,
        )
        val report = RenderReports.build(out, (System.nanoTime() - t0) / 1_000_000, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        val renderLanes = lanes.map { it.toAutomationLane(sr) }
        val ids = renderLanes.map { it.id }.toSet()
        return RenderedTransition(plan.copy(lanes = plan.lanes.filter { it.id !in ids } + renderLanes), out, markers, report)
    }

    companion object {
        const val ID = "spectralFreezeBridge"
        const val HARMONIC_MIN = 0.6
        const val FALLBACK_BPM = 120.0
        const val LPF_START_HZ = 20000.0
        const val LPF_Q = 0.7071067811865476
        /** Block length the darkening sweep is fed to the SVF in (see the render: the filter interpolates per block). */
        const val RAMP_BLOCK = 256
        const val MARKER_FREEZE = "freeze"
        const val MARKER_B_ENTERS = "B enters"
        const val MARKER_RELEASE = "freeze release"
        const val MARKER_FREEZE_GONE = "freeze gone"
        const val LANE_A = "gainA"
        const val LANE_FREEZE = "gainFreeze"
        const val LANE_B = "gainB"
        const val LANE_LPF = "freezeLpfHz"

        /** `1 - mean percussiveness` of A's last two bars with features; NaN without bar features. */
        fun harmonicShare(a: TrackAnalysis): Double {
            val perc = a.bars.percussiveness
            if (perc.isEmpty()) return Double.NaN
            val n = perc.size
            return 1.0 - (max(0, n - 2) until n).map { perc[it].toDouble() }.average()
        }

        /** Onsets per second in the last 2 s before A's trimmed end; NaN when A has no onset list. */
        fun tailOnsetDensity(a: TrackAnalysis): Double {
            if (a.onsetFrames.isEmpty()) return Double.NaN
            val end = a.trimEndFrame
            val start = end - 2L * a.sampleRate
            return a.onsetFrames.count { it in start until end } / 2.0
        }

        /** Structure prior: sustained outros freeze well; a beat intro underneath is fine, an ambient one is best. */
        fun structurePrior(outro: OutroType, intro: IntroType): Double {
            val o = when (outro) { OutroType.AMBIENT_OUTRO -> 1.0; OutroType.VOCAL_OUTRO -> 0.8; OutroType.HARD_STOP -> 0.7; OutroType.UNKNOWN -> 0.5; OutroType.FADE_OUT -> 0.4; OutroType.BEAT_OUTRO -> 0.2 }
            val i = when (intro) { IntroType.AMBIENT_INTRO -> 1.0; IntroType.VOCAL_INTRO -> 0.8; IntroType.BEAT_INTRO -> 0.7; IntroType.COLD_START, IntroType.UNKNOWN -> 0.6; IntroType.SILENCE -> 0.4 }
            return o * 0.7 + i * 0.3
        }
    }
}
