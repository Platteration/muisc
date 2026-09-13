package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fx.FdnReverb
import dev.muisc.dsp.gain.Curves
import dev.muisc.dsp.synth.Pad
import dev.muisc.dsp.synth.PadSpec
import dev.muisc.dsp.synth.Waveform
import dev.muisc.dsp.texture.ShapedNoise
import dev.muisc.dsp.texture.ThirdOctaveBands
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
import dev.muisc.transitions.core.Lane
import dev.muisc.transitions.core.RenderReports
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.sdk.ParamSet
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * `ambientBridge` — a generated bridge for pairs that cannot be mixed (DESIGN §4 #13).
 *
 * **What it sounds like.** A plays to its end; over its last [P.swellSec] a long reverb swells up under it and
 * catches the final chord. At A's end the reverb is *frozen* (the `dsp` [FdnReverb] with lossless feedback,
 * damping bypassed, input muted — the chord hangs in the air indefinitely), a texture bed of [ShapedNoise]
 * shaped like A's outro spectrum (optionally morphing toward B's intro spectrum) sits under it, and a low
 * synth pad ([Pad]: detuned saws through a low-pass plus a sine an octave below) sounds a note common to both
 * keys. After [P.bridgeSec] B's mix-in downbeat enters with a [P.bFadeBars]-bar fade while the reverb, bed
 * and pad fade away. No stretching, no beat-matching — it is the honest musical floor: a click-free,
 * tonally sensible pause between two songs that do not belong together.
 *
 * **When the planner picks it.** Always applicable, never blocked: score floor [FLOOR_SCORE], rising to
 * [INCOMPATIBLE_SCORE] as the pair gets less mixable (stretch beyond `prefs.maxStretchPercent` toward 12 %,
 * Camelot distance ≥ 3 after the best shift, no usable grids, or a `HARD_STOP` into an `AMBIENT_INTRO`).
 *
 * **Segment layout** (see [Splice]): `[0, G)` dry A pre-roll, `[G, tA)` A's last `swellSec` with the reverb
 * and bed rising, `[tA, tB)` the bridge (frozen reverb + bed + pad), `[tB, tEnd)` B fades in while the bridge
 * fades out, `[tEnd, tEnd + G)` dry B post-roll. Every generated layer ends at exactly 0 before the post-roll.
 *
 * **Parameters.** `bridgeSec` length of the generated pause; `reverbSize` reverberation time before the
 * freeze (0.1 = small room, 1 = cathedral); `reverbDb` level of the frozen tail relative to A's last second;
 * `textureDb` level of the noise bed relative to A's own spectrum level (−20 = subtle, −6 = obvious);
 * `padDb` / `padOctave` / `padCutoffHz` level, register and brightness of the pad (`padDb` −80 effectively
 * removes it); `morphToB` morph the bed's spectrum toward B's intro; `swellSec` how early the reverb starts
 * rising under A; `bFadeBars` B's fade-in (bars of B's grid).
 *
 * **Failure modes.** A wrong key detection puts the pad on a wrong note (the pad is omitted when both key
 * strengths are below [KEY_STRENGTH_MIN]); a fade-out A leaves little for the reverb to catch, so the bridge
 * is mostly bed and pad; it is a pause, not a mix — the planner only prefers it when nothing else fits.
 */
class AmbientBridgeStrategy : TransitionStrategy {
    /** Parameters. */
    object P : ParamSet(ID) {
        val bridgeSec = double("bridgeSec", "Bridge", 8.0, 3.0, 16.0, "s", "Length of the generated bridge between A's end and B's entry")
        val padDb = double("padDb", "Pad level", -18.0, -80.0, 0.0, "dB", "Level of the synth pad (dBFS-ish; -80 removes it)")
        val padOctave = int("padOctave", "Pad octave", 2, 1, 4, "", "Octave of the pad's root note (2 = C2 for C)")
        val padCutoffHz = double("padCutoffHz", "Pad cutoff", 900.0, 200.0, 8000.0, "Hz", "Low-pass cutoff of the saw pad")
        val reverbSize = double("reverbSize", "Reverb size", 0.8, 0.1, 1.0, "", "Reverberation time before the freeze: RT60 = 1 + 9 × size seconds")
        val reverbDb = double("reverbDb", "Reverb level", -6.0, -80.0, 6.0, "dB", "Level of the frozen reverb tail relative to A's last second")
        val textureDb = double("textureDb", "Texture level", -20.0, -80.0, 0.0, "dB", "Level of the noise bed relative to A's outro spectrum")
        val morphToB = bool("morphToB", "Morph bed to B", true, "Morph the bed's third-octave envelope from A's outro toward B's intro over the bridge")
        val swellSec = double("swellSec", "Reverb swell", 1.0, 0.2, 4.0, "s", "Seconds before A's end over which the reverb and bed rise")
        val bFadeBars = int("bFadeBars", "B fade-in", 2, 1, 8, "bars", "B's fade-in length in bars of B's grid")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Ambient bridge"
    override val description: String get() = "Frozen reverb, texture bed and a pad bridge two songs that cannot be mixed."
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, prefs: TransitionPrefs): Applicability {
        val reasons = ArrayList<String>()
        reasons += "always applicable (floor $FLOOR_SCORE), no tempo or key gate"
        val maxS = prefs.maxStretchPercent
        val tempoBad = when {
            !features.beatMatchable -> 1.0.also { reasons += "not beat-matchable (grid confidence ${"%.2f".format(features.gridConfidenceA)}/${"%.2f".format(features.gridConfidenceB)})" }
            features.stretchPercent > maxS -> ((features.stretchPercent - maxS) / (INCOMPATIBLE_STRETCH - maxS).coerceAtLeast(0.5)).coerceIn(0.0, 1.0).also { reasons += "tempo ${"%.1f".format(features.stretchPercent)} % stretch beyond the ${"%.0f".format(maxS)} % limit" }
            else -> 0.0
        }
        val keyBad = when (features.camelotDistanceAfterShift) { 0, 1 -> 0.0; 2 -> 0.5; else -> 1.0 }
        if (keyBad > 0) reasons += "keys ${(a.outroKey ?: a.key).camelot.code}→${(b.introKey ?: b.key).camelot.code} distance ${features.camelotDistanceAfterShift} after a ${features.bestPitchShiftSemitones}-semitone shift"
        // Only the textbook edge combination (a hard stop into an ambient/silent intro, prior >= STRUCT_CALLS_FOR_BRIDGE)
        // argues *for* a bridge on its own; a merely soft pair of edges must not pull the score up.
        val structGood = structurePrior(features.outro, features.intro)
        val structBad = ((structGood - STRUCT_CALLS_FOR_BRIDGE) / (1.0 - STRUCT_CALLS_FOR_BRIDGE)).coerceIn(0.0, 1.0)
        if (structBad > 0.0) reasons += "structure ${features.outro}→${features.intro} suits a bridge (prior ${"%.2f".format(structGood)})"
        val incompatible = max(tempoBad, max(keyBad, structBad))
        val score = FLOOR_SCORE + (INCOMPATIBLE_SCORE - FLOOR_SCORE) * incompatible
        return Applicability.of(max(score, FLOOR_SCORE), *reasons.toTypedArray())
    }

    // -------------------------------------------------------------------------------------------------- plan

    override fun plan(a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs, seed: Long): TransitionPlan {
        val p = P.resolve(params, prefs)
        val sr = prefs.sampleRate
        val notes = ArrayList<String>()
        val geo = geometry(p, b, sr)
        val g = geo.g
        val aEnd = Math.round(a.trimEndFrame * sr / a.sampleRate.toDouble())
        val aExit = (aEnd - g - geo.swell).coerceAtLeast(0L)
        val aWindow = FrameRange((aExit - geo.feed).coerceAtLeast(0L), max(aEnd, aExit + g))
        val bMixIn = bMixInFrame(b, sr / b.sampleRate.toDouble(), notes)
        val bEntry = bMixIn + geo.bFade + g
        val bWindow = FrameRange(bMixIn, bEntry)
        val expected = (geo.tEnd + g).toInt()
        val note = padNote(a, b, features, p.int(P.padOctave))
        val lanes = lanes(geo, p).map { it.toAutomationLane(sr) }
        notes.add(0, "${"%.1f".format(p.double(P.bridgeSec))} s ambient bridge: reverb (RT60 ${"%.1f".format(rt60(p))} s) swells over A's last ${"%.1f".format(p.double(P.swellSec))} s and freezes at A's end, bed at ${"%.0f".format(p.double(P.textureDb))} dB${if (p.bool(P.morphToB)) " morphing toward B" else ""}")
        notes += if (note != null) "pad on ${MusicalKey.NAMES[note.pitchClass]}${p.int(P.padOctave)} (${note.reason}) at ${"%.0f".format(p.double(P.padDb))} dB" else "pad omitted: ${padOmissionReason(a, b, features, p)}"
        notes += "B enters after the bridge with a ${p.int(P.bFadeBars)}-bar fade (${"%.1f".format(geo.bFade / sr.toDouble())} s at ${"%.1f".format(geo.bpmB)} BPM); no stretch"
        return TransitionPlan(
            strategyId = ID, params = p, aExitFrame = aExit, bEntryFrame = bEntry, aWindow = aWindow, bWindow = bWindow,
            expectedOutputFrames = expected, lanes = lanes, notes = notes,
        )
    }

    private class Geometry(val g: Long, val swell: Long, val feed: Long, val tA: Long, val bridge: Long, val tB: Long, val bFade: Long, val tEnd: Long, val bpmB: Double)

    private fun geometry(p: Params, b: TrackAnalysis, sr: Int): Geometry {
        val g = Splice.GUARD_FRAMES.toLong()
        val swell = Math.round(p.double(P.swellSec) * sr)
        val feed = Math.round(REVERB_FEED_SEC * sr)
        val tA = g + swell
        val bridge = Math.round(p.double(P.bridgeSec) * sr)
        val tB = tA + bridge
        val bpmB = when { b.grid.bpm > 0 -> b.grid.bpm; b.tempo.bpm > 0 -> b.tempo.bpm; else -> FALLBACK_BPM }
        val bFade = Math.round(p.int(P.bFadeBars) * b.grid.beatsPerBar.coerceAtLeast(1) * 60.0 * sr / bpmB)
        return Geometry(g, swell, feed, tA, bridge, tB, bFade, tB + bFade, bpmB)
    }

    private fun bMixInFrame(b: TrackAnalysis, scaleB: Double, notes: MutableList<String>): Long = when {
        b.cues.mixInBeat >= 0 && !b.grid.isEmpty -> Math.round(b.grid.frameOfBeat(b.cues.mixInBeat.toDouble()) * scaleB).also { notes += "B starts at its mixInBeat ${b.cues.mixInBeat}" }
        b.cues.firstDownbeat >= 0 && !b.grid.isEmpty -> Math.round(b.grid.frameOfBeat(b.cues.firstDownbeat.toDouble()) * scaleB).also { notes += "B starts at its first downbeat ${b.cues.firstDownbeat}" }
        else -> Math.round(b.trimStartFrame * scaleB).also { notes += "B starts at its trim start (no cues)" }
    }.coerceAtLeast(0L)

    /** Lanes in output frames: reverb, bed, pad envelope (for the Lab), B. */
    private fun lanes(geo: Geometry, p: Params): List<Lane> {
        val reverb = Lane(LANE_REVERB).add(geo.g, 0.0, FadeLaw.S_CURVE).add(geo.tA, 1.0).add(geo.tB, 1.0, FadeLaw.EQUAL_POWER).add(geo.tEnd, 0.0)
        val bed = Lane(LANE_BED).add(geo.g, 0.0, FadeLaw.S_CURVE).add(geo.tA, 1.0).add(geo.tB, 1.0, FadeLaw.EQUAL_POWER).add(geo.tEnd, 0.0)
        val pad = Lane(LANE_PAD).add(geo.g, 0.0).add(geo.tA, 1.0).add(geo.tB, 1.0).add(geo.tEnd, 0.0)
        val b = Lane(LANE_B).add(geo.tB, 0.0, FadeLaw.EQUAL_POWER).add(geo.tEnd, 1.0)
        return listOf(reverb, bed, pad, b)
    }

    // ------------------------------------------------------------------------------------------------ render

    override fun render(input: TransitionInput, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val plan = input.plan
        val p = P.resolve(plan.params)
        val sr = ctx.sampleRate
        val a = input.aAnalysis; val b = input.bAnalysis
        val geo = geometry(p, b, sr)
        val g = geo.g.toInt()
        val ch = input.aAudio.channelCount
        val total = (geo.tEnd + geo.g).toInt()
        val aExitOff = plan.aExitOffset
        val aEndOff = min(input.aAudio.frames, aExitOff + (geo.tA).toInt())        // window offset of A's end
        val genLen = (geo.tEnd - geo.g).toInt()                                    // generated layers span [G, tEnd)
        val lanes = lanes(geo, p)
        val out = AudioBuffer.silence(sr, ch, total)
        // A: dry from aExit to its end (unity: the reverb is added on top).
        Splice.addInPlace(out, input.aAudio, 0, null, srcOffset = aExitOff, frames = aEndOff - aExitOff)

        // --- reverb: fed with the whole A window, frozen at A's end ------------------------------------------
        val reverb = FdnReverb(sr, ch)
        reverb.decaySeconds = rt60(p)
        reverb.mix = 1.0
        reverb.dampingHz = REVERB_DAMPING_HZ
        val wetA = Array(ch) { FloatArray(aEndOff) }
        reverb.process(input.aAudio.channels, wetA, aEndOff)
        reverb.freeze = true
        val tailLen = (geo.tEnd - geo.tA).toInt()
        val silence = Array(ch) { FloatArray(tailLen) }
        val wetTail = Array(ch) { FloatArray(tailLen) }
        reverb.process(silence, wetTail, tailLen)
        val wet = AudioBuffer(sr, Array(ch) { c -> FloatArray(genLen).also { dst ->
            val fromA = (aEndOff - aExitOff - g).coerceAtLeast(0)                   // wet frames between G and tA
            System.arraycopy(wetA[c], aExitOff + g, dst, 0, min(fromA, genLen))
            System.arraycopy(wetTail[c], 0, dst, fromA, min(tailLen, genLen - fromA))
        } })
        val aRef = rms(input.aAudio, max(0, aEndOff - sr), aEndOff)
        val wetRef = rms(wet, (geo.tA - geo.g).toInt(), min(genLen, (geo.tA - geo.g).toInt() + sr))
        val reverbGain = if (wetRef > 1e-6f && aRef > 1e-6f) (aRef / wetRef * Curves.dbToLinear(p.double(P.reverbDb).toFloat())).coerceAtMost(MAX_LAYER_GAIN) else 0f
        wet.applyGainInPlace(reverbGain)
        Splice.addInPlace(out, wet, g, lanes[0])
        ctx.progress(0.4)

        // --- texture bed from A's outro spectrum, optionally morphing toward B's intro ----------------------
        val envA = (a.outroLtasDb.takeIf { it.size == ThirdOctaveBands.BANDS } ?: a.ltasDb.takeIf { it.size == ThirdOctaveBands.BANDS })
        var bedRms = 0f
        if (envA != null && p.double(P.textureDb) > SILENT_DB) {
            val envB = if (p.bool(P.morphToB)) (b.introLtasDb.takeIf { it.size == ThirdOctaveBands.BANDS } ?: b.ltasDb.takeIf { it.size == ThirdOctaveBands.BANDS }) else null
            val bed = ShapedNoise(sr, seed = (ctx.seed xor 0x7a11).toInt()).render(genLen, envA, envB, ch)
            bed.applyGainInPlace(Curves.dbToLinear(p.double(P.textureDb).toFloat()))
            bedRms = bed.rms()
            Splice.addInPlace(out, bed, g, lanes[1])
        }
        ctx.progress(0.6)

        // --- pad on a pitch class shared by both keys ----------------------------------------------------
        val note = padNote(a, b, input.features, p.int(P.padOctave))
        if (note != null && p.double(P.padDb) > SILENT_DB) {
            val level = Curves.dbToLinear(p.double(P.padDb))
            val attack = geo.swell / sr.toDouble(); val release = geo.bFade / sr.toDouble()
            val saw = Pad.render(sr, genLen, intArrayOf(note.midi), PadSpec(Waveform.SAW, unison = 3, detuneCents = 8.0, cutoffHz = p.double(P.padCutoffHz), attackSec = attack, releaseSec = release, level = level * 0.7, seed = (ctx.seed xor 0x9ad).toInt()), ch)
            val sine = Pad.render(sr, genLen, intArrayOf(note.midi), PadSpec(Waveform.SINE, unison = 1, detuneCents = 0.0, cutoffHz = 4000.0, attackSec = attack, releaseSec = release, level = level * 0.7, stereoSpread = 0.0, seed = (ctx.seed xor 0x3c1).toInt()), ch)
            Splice.addInPlace(out, saw, g)
            Splice.addInPlace(out, sine, g)
        }
        ctx.progress(0.75)

        // --- B ------------------------------------------------------------------------------------------
        val bEntryOff = plan.bEntryOffset
        val bLen = geo.bFade.toInt() + g
        Splice.addInPlace(out, input.bAudio, geo.tB.toInt(), lanes[3], srcOffset = bEntryOff - bLen, frames = bLen)
        val fin = RenderReports.finalize(out, ctx)
        val markers = listOf(Marker(geo.g, MARKER_SWELL), Marker(geo.tA, MARKER_FREEZE), Marker(geo.tB, MARKER_B_ENTERS), Marker(geo.tEnd, MARKER_B_FULL))
        val metrics = mapOf(
            "bridgeFrames" to geo.bridge.toDouble(), "reverbGain" to reverbGain.toDouble(), "aRefRms" to aRef.toDouble(), "bedRms" to bedRms.toDouble(),
            "padMidi" to (note?.midi?.toDouble() ?: -1.0), "limited" to (if (fin.limited) 1.0 else 0.0), "gainReductionDb" to fin.gainReductionDb,
        )
        val report = RenderReports.build(out, (System.nanoTime() - t0) / 1_000_000, metrics = metrics, warnings = fin.warnings)
        ctx.progress(1.0)
        val renderLanes = lanes.map { it.toAutomationLane(sr) }
        val ids = renderLanes.map { it.id }.toSet()
        return RenderedTransition(plan.copy(lanes = plan.lanes.filter { it.id !in ids } + renderLanes), out, markers, report)
    }

    private fun rms(x: AudioBuffer, start: Int, end: Int): Float {
        val s = start.coerceIn(0, x.frames); val e = end.coerceIn(s, x.frames)
        if (e <= s) return 0f
        var acc = 0.0
        for (c in 0 until x.channelCount) { val ch = x[c]; for (i in s until e) acc += ch[i].toDouble() * ch[i] }
        return sqrt(acc / ((e - s).toDouble() * x.channelCount)).toFloat()
    }

    private fun rt60(p: Params): Double = 1.0 + 9.0 * p.double(P.reverbSize)

    private fun padOmissionReason(a: TrackAnalysis, b: TrackAnalysis, f: PairFeatures, p: Params): String = when {
        p.double(P.padDb) <= SILENT_DB -> "padDb at silence"
        else -> "both key strengths below $KEY_STRENGTH_MIN (${"%.2f".format(f.keyStrengthA)} / ${"%.2f".format(f.keyStrengthB)})"
    }

    /** The pad's note: pitch class, MIDI number and why it was chosen. */
    class PadNote(val pitchClass: Int, val midi: Int, val reason: String)

    companion object {
        const val ID = "ambientBridge"
        const val FLOOR_SCORE = 0.15
        const val INCOMPATIBLE_SCORE = 0.6
        /** Stretch at which the tempo term saturates. */
        const val INCOMPATIBLE_STRETCH = 12.0
        /** Structure prior from which the edge types alone start calling for a bridge (HARD_STOP → AMBIENT_INTRO = 0.9). */
        const val STRUCT_CALLS_FOR_BRIDGE = 0.7
        const val KEY_STRENGTH_MIN = 0.5
        const val FALLBACK_BPM = 120.0
        /** Seconds of A before `aExitFrame` fed into the reverb so its tail is charged with A's last chord. */
        const val REVERB_FEED_SEC = 2.0
        const val REVERB_DAMPING_HZ = 6000.0
        const val SILENT_DB = -79.0
        const val MAX_LAYER_GAIN = 100f
        const val MARKER_SWELL = "reverb swell"
        const val MARKER_FREEZE = "A ends, reverb frozen"
        const val MARKER_B_ENTERS = "B enters"
        const val MARKER_B_FULL = "B full"
        const val LANE_REVERB = "gainReverb"
        const val LANE_BED = "gainBed"
        const val LANE_PAD = "padEnvelope"
        const val LANE_B = "gainB"

        /** Structure prior of §5.2 for this strategy (HARD_STOP → AMBIENT_INTRO = 0.9 is the textbook case). */
        fun structurePrior(outro: OutroType, intro: IntroType): Double {
            val o = when (outro) { OutroType.HARD_STOP -> 1.0; OutroType.AMBIENT_OUTRO, OutroType.VOCAL_OUTRO -> 0.7; OutroType.FADE_OUT -> 0.6; OutroType.UNKNOWN -> 0.4; OutroType.BEAT_OUTRO -> 0.2 }
            val i = when (intro) { IntroType.AMBIENT_INTRO -> 0.9; IntroType.SILENCE -> 0.8; IntroType.VOCAL_INTRO -> 0.6; IntroType.COLD_START -> 0.5; IntroType.UNKNOWN -> 0.4; IntroType.BEAT_INTRO -> 0.2 }
            return o * i
        }

        /** Pitch classes of a key's diatonic scale. */
        fun scale(key: MusicalKey): Set<Int> {
            val steps = if (key.mode == Mode.MAJOR) intArrayOf(0, 2, 4, 5, 7, 9, 11) else intArrayOf(0, 2, 3, 5, 7, 8, 10)
            return steps.map { Math.floorMod(key.tonic + it, 12) }.toSet()
        }

        /**
         * The pad note: a pitch class common to A's outro key and B's intro key, tried in the order B's tonic,
         * A's tonic, B's fifth, A's fifth, then B's scale degrees by harmonic weight; B's tonic when nothing is
         * shared; null when both key strengths are below [KEY_STRENGTH_MIN].
         */
        fun padNote(a: TrackAnalysis, b: TrackAnalysis, f: PairFeatures, octave: Int): PadNote? {
            if (f.keyStrengthA < KEY_STRENGTH_MIN && f.keyStrengthB < KEY_STRENGTH_MIN) return null
            val keyA: KeyEstimate = a.outroKey ?: a.key
            val keyB: KeyEstimate = b.introKey ?: b.key
            val sA = scale(keyA.key); val sB = scale(keyB.key)
            val candidates = listOf(
                keyB.key.tonic to "B's tonic, in ${keyA.key.shortName}", keyA.key.tonic to "A's tonic, in ${keyB.key.shortName}",
                Math.floorMod(keyB.key.tonic + 7, 12) to "B's fifth, in ${keyA.key.shortName}", Math.floorMod(keyA.key.tonic + 7, 12) to "A's fifth, in ${keyB.key.shortName}",
            ) + intArrayOf(5, 2, 9, 4).map { Math.floorMod(keyB.key.tonic + it, 12) to "shared scale degree of ${keyB.key.shortName} and ${keyA.key.shortName}" }
            val pick = candidates.firstOrNull { (pc, _) -> pc in sA && pc in sB } ?: (keyB.key.tonic to "B's tonic (keys share no strong pitch class)")
            return PadNote(pick.first, 12 * (octave + 1) + pick.first, pick.second)
        }
    }
}
