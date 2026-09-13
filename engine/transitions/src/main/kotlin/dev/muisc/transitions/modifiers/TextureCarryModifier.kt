package dev.muisc.transitions.modifiers

import dev.muisc.analysis.features.SpectrumAnalyzer
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.fft.Istft
import dev.muisc.dsp.fft.Stft
import dev.muisc.dsp.fft.StftFrameSink
import dev.muisc.dsp.filter.Biquad
import dev.muisc.dsp.filter.BiquadFilter
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
import dev.muisc.dsp.gain.Curves
import dev.muisc.dsp.resample.Resampler
import dev.muisc.dsp.stems.PseudoStemSeparator
import dev.muisc.dsp.texture.Granulator
import dev.muisc.dsp.texture.SpectralFreeze
import dev.muisc.dsp.texture.ThirdOctaveBands
import dev.muisc.dsp.window.Window
import dev.muisc.transitions.FadeLaw
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
import dev.muisc.transitions.core.CrossfadeLaw
import dev.muisc.transitions.core.Lane
import dev.muisc.transitions.core.RenderReports
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.sdk.ParamSet
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * `textureCarry` — carry the outgoing song's noise / texture across the seam (DESIGN §4, modifiers).
 *
 * **What it does musically.** Every recording has a "floor": tape hiss, room tone, vinyl crackle, a reverb
 * wash, a synth bed. When a mix cuts from one song to the next that floor changes abruptly and the seam is
 * audible even when the notes line up. This modifier synthesises a bed with A's texture, fades it up under A's
 * last [P.riseBars] bars, holds it across the seam region and lets it fall away *as B's own energy rises*: the
 * release gain is `g(t) = clamp(1 − E_B(t) / E_ref, 0, 1)`, where `E_B(t)` is B's measured per-bar energy
 * below 4 kHz (sub + bass + mid) inside the segment and `E_ref` is B at full level (each measured bar scaled
 * up by `b.bars.energy`, so a segment that only sees B's quiet intro does not mistake it for B's loudest
 * moment) — the bed literally drops off as the new song picks up, and it is always gone before the dry B
 * post-roll.
 *
 * **Bed modes** ([P.mode]):
 *  - `NOISE` (default): seeded white noise → STFT (22.05 kHz, frame 1024, hop 256, Hann — the analysis
 *    geometry) → multiplied per bin by A's per-bin median magnitude (`TrackAnalysis.textureMagnitude`; when
 *    the analysis has none it is measured, with the same geometry, from the last 15 s of A inside the decoded
 *    window with [SpectrumAnalyzer.textureMagnitude]) → ISTFT → resampled to the engine rate. The stationary
 *    part of A's outro without its notes.
 *  - `FREEZE`: a Paulstretch-style [SpectralFreeze] of A's last 400 ms — the chord itself, sustained.
 *  - `OTHER`: A's `other` pseudo-stem (pads, ambience) granulated with 120–300 ms Tukey grains, 6 voices
 *    ([Granulator]) — moving texture rather than a static bed.
 * With [P.morphToB] the bed's third-octave envelope morphs from A's outro LTAS toward B's intro LTAS over the
 * segment (band gains clamped to ±[P.morphMaxDb] so bands where A is silent are not blown up);
 * [P.darkenHz] > 0 low-passes the bed. The level is [P.textureDb] relative to the RMS of A's last 4 s.
 *
 * **Contract.** The bed's gain lane is exactly 0 over the first and last `Splice.GUARD_FRAMES` frames, so the
 * dry pre-roll and post-roll are bit-identical to the base render and `SpliceCheck` still passes. The whole
 * segment is re-finalised (true-peak limiter, guard regions protected) after the bed is added.
 *
 * **Applicability** rises with A's texture level (from `textureMagnitude`, else from its outro LTAS) and with
 * low `spectralSimilarity` (the bed glues dissimilar spectra); 0 below [LEVEL_GATE_DB] (clean outros would just
 * gain hiss) and 0 for `ambientBridge` (it has its own bed); halved for `spectralFreezeBridge`.
 *
 * **Failure modes.** On very quiet or clean outros the bed is audible as added hiss (hence the gate); the
 * energy-following release measures B from its decoded window, which is exact for unstretched strategies and
 * approximate (bar-coarse) for beat-domain ones; `OTHER` on a mostly-percussive A yields a thin bed.
 */
class TextureCarryModifier : TransitionModifier {
    enum class Mode { NOISE, FREEZE, OTHER }

    /** Parameters. */
    object P : ParamSet(ID) {
        val mode = choice("mode", "Bed mode", Mode.NOISE, Mode.entries, "NOISE = shaped noise from A's texture spectrum, FREEZE = spectral freeze of A's last 400 ms, OTHER = granulated 'other' stem")
        val textureDb = double("textureDb", "Bed level", -18.0, -40.0, 0.0, "dB", "Level of the bed relative to A's last 4 s")
        val riseBars = int("riseBars", "Rise", 4, 1, 16, "bars", "Bars of A's tempo over which the bed fades up at the start of the segment")
        val releaseCurve = choice("releaseCurve", "Release curve", FadeLaw.EXP, listOf(FadeLaw.EXP, FadeLaw.LINEAR, FadeLaw.S_CURVE, FadeLaw.EQUAL_POWER), "Shape between the per-bar release points")
        val morphToB = bool("morphToB", "Morph to B", true, "Morph the bed's third-octave envelope toward B's intro spectrum over the segment")
        val morphMaxDb = double("morphMaxDb", "Morph limit", 12.0, 0.0, 24.0, "dB", "Clamp of the per-band morph gain")
        val darkenHz = double("darkenHz", "Darken", 0.0, 0.0, 20000.0, "Hz", "Low-pass on the bed (0 = off)")
        val minReleaseBars = int("minReleaseBars", "Minimum release", 1, 1, 8, "bars", "The bed always fades to 0 over at least this many bars of B before the post-roll")
    }

    override val id: String get() = ID
    override val displayName: String get() = "Texture carry"
    override val params: List<ParamSpec> get() = P.specs

    // ------------------------------------------------------------------------------------------ applicability

    override fun applicability(features: PairFeatures, a: TrackAnalysis, b: TrackAnalysis, base: TransitionStrategy, prefs: TransitionPrefs): Double {
        if (base.id == "ambientBridge") return 0.0
        val level = textureLevelDb(a)
        if (level <= LEVEL_GATE_DB) return 0.0
        val sLevel = ((level - LEVEL_GATE_DB) / 25.0).coerceIn(0.0, 1.0)
        val sSim = ((0.9 - features.spectralSimilarity) / 0.4).coerceIn(0.0, 1.0)
        val score = 0.55 * sLevel + 0.45 * sSim
        return (if (base.id == "spectralFreezeBridge") score * 0.5 else score).coerceIn(0.0, 1.0)
    }

    override fun adjustPlan(plan: TransitionPlan, a: TrackAnalysis, b: TrackAnalysis, features: PairFeatures, params: Params, prefs: TransitionPrefs): TransitionPlan {
        val p = P.resolve(params, prefs)
        val note = "textureCarry: ${p.choice(P.mode)} bed at ${"%.0f".format(p.double(P.textureDb))} dB, rises over ${p.int(P.riseBars)} A bars, releases as B's energy rises (curve ${p.choice(P.releaseCurve)})" +
            (if (p.bool(P.morphToB)) ", morphing toward B's spectrum" else "") + (if (a.textureMagnitude.size == SpectrumAnalyzer.TEXTURE_BINS) "" else "; texture measured from the decoded A window")
        return plan.copy(modifiers = if (ID in plan.modifiers) plan.modifiers else plan.modifiers + ID, notes = plan.notes + note)
    }

    // ------------------------------------------------------------------------------------------------- apply

    override fun apply(rendered: RenderedTransition, input: TransitionInput, params: Params, ctx: RenderContext): RenderedTransition {
        val t0 = System.nanoTime()
        val p = P.resolve(params)
        val plan = rendered.plan
        val sr = ctx.sampleRate
        val g = Splice.GUARD_FRAMES
        val out = rendered.audio.copy()
        val frames = out.frames
        if (frames <= 2 * g + 1024) return rendered
        val ch = out.channelCount
        val a = input.aAnalysis; val b = input.bAnalysis
        val mode = Mode.entries.first { it.name == p.choice(P.mode) }
        // Source region for the bed and for its level reference: A's own material inside the decoded window, up to
        // the last frame of A the strategy can use (its trimmed end when that falls inside the window, else the whole
        // window — never the zero padding a strategy may have asked for past A's end). Taking the *whole* A window,
        // not just the dry pre-roll, matters: a crossfade's pre-roll is only GUARD_FRAMES long (93 ms), far too
        // little to estimate a spectrum from, while the window holds the seconds of outro the fade consumes.
        val aTrimEnd = Math.round(a.trimEndFrame * sr / a.sampleRate.toDouble()) - plan.aWindow.start
        val aDryEnd = aTrimEnd.coerceIn(1L, input.aAudio.frames.toLong()).toInt()

        // --- bed ---------------------------------------------------------------------------------------
        val morph = if (p.bool(P.morphToB)) morphGainsDb(a, b, p.double(P.morphMaxDb)) else null
        val seed = (ctx.seed xor 0x7e57).toInt()
        val bed = when (mode) {
            Mode.NOISE -> noiseBed(input.aAudio, aDryEnd, a, frames, ch, sr, seed, morph)
            Mode.FREEZE -> SpectralFreeze(sr, seed = seed).render(input.aAudio.slice(max(0, aDryEnd - Math.round(FREEZE_SOURCE_SEC * sr).toInt()), aDryEnd), frames, morph)
            Mode.OTHER -> otherBed(input.aAudio, aDryEnd, frames, sr, seed)
        }
        if (p.double(P.darkenHz) > 0.0) {
            val lp = StateVariableFilter(sr, ch, p.double(P.darkenHz), 0.7071)
            lp.mode = SvfMode.LOW_PASS
            lp.processInPlace(bed.channels, frames)
        }
        val aRef = rms(input.aAudio, max(0, aDryEnd - Math.round(LEVEL_REF_SEC * sr).toInt()), aDryEnd)
        val bedRms = bed.rms()
        val gain = if (bedRms > 1e-7f && aRef > 1e-7f) (aRef / bedRms * Curves.dbToLinear(p.double(P.textureDb).toFloat())).coerceAtMost(MAX_GAIN) else 0f
        bed.applyGainInPlace(gain)
        ctx.progress(0.6)

        // --- envelope: rise over A's bars, hold, release following B's energy ---------------------------------
        val lane = envelopeLane(input, p, sr, frames)
        Splice.addInPlace(out, bed, 0, lane)
        val fin = RenderReports.finalize(out, ctx)
        val metrics = HashMap(rendered.report.metrics)
        metrics["textureBedGain"] = gain.toDouble(); metrics["textureBedRms"] = (bedRms * gain).toDouble(); metrics["textureRefRms"] = aRef.toDouble()
        metrics["textureLimited"] = if (fin.limited) 1.0 else 0.0
        val report = RenderReports.build(out, rendered.report.renderMillis + (System.nanoTime() - t0) / 1_000_000, rendered.report.ratioTrace, metrics, fin.warnings, rendered.report.renderKey)
        val pts = lane.points
        val holdStart = pts.firstOrNull { it.value >= 0.999 }?.frame ?: g.toLong()
        val markers = rendered.markers + Marker(g.toLong(), MARKER_IN) + Marker(holdStart, MARKER_HOLD) + Marker((frames - g).toLong(), MARKER_OUT)
        val laneOut = lane.toAutomationLane(sr)
        ctx.progress(1.0)
        return RenderedTransition(plan.copy(lanes = plan.lanes.filter { it.id != laneOut.id } + laneOut), out, markers, report)
    }

    // -------------------------------------------------------------------------------------------- envelope

    /**
     * The bed's gain lane in output frames: 0 through the pre-roll, an S-curve rise over `riseBars` of A's
     * tempo, then per-bar release points `clamp(1 − E_B / E_ref)` on B's bar boundaries (monotone, never rising
     * again), and a guaranteed fade to 0 over the last `minReleaseBars` of B before the post-roll.
     *
     * `E_B` is measured on the decoded B window (mono, low-passed at 4 kHz = sub + bass + mid) over each bar;
     * `E_ref` is B's energy AT FULL LEVEL, estimated by scaling each measured bar up with `b.bars.energy`
     * (B's own per-bar energy normalised to its loudest bar) — see the code.
     */
    private fun envelopeLane(input: TransitionInput, p: Params, sr: Int, frames: Int): Lane {
        val g = Splice.GUARD_FRAMES.toLong()
        val plan = input.plan
        val a = input.aAnalysis; val b = input.bAnalysis
        val curve = CrossfadeLaw.parse(p.choice(P.releaseCurve)) ?: FadeLaw.EXP
        val bpmA = when { a.grid.bpm > 0 -> a.grid.bpm; a.tempo.bpm > 0 -> a.tempo.bpm; else -> 120.0 }
        val bpmB = when { b.grid.bpm > 0 -> b.grid.bpm; b.tempo.bpm > 0 -> b.tempo.bpm; else -> 120.0 }
        val barA = a.grid.beatsPerBar.coerceAtLeast(1) * 60.0 * sr / bpmA
        val barB = b.grid.beatsPerBar.coerceAtLeast(1) * 60.0 * sr / bpmB
        val guardEnd = frames - g
        val minRelease = Math.round(p.int(P.minReleaseBars) * barB).coerceAtLeast(256L)
        val releaseStart = max(g + 256, guardEnd - minRelease)
        val riseEnd = min(g + Math.round(p.int(P.riseBars) * barA), releaseStart - 1)

        // B's bar boundaries in output frames (B window frame j plays at output frame frames - bEntryOffset + j).
        val bShift = frames - plan.bEntryOffset
        val gridB = b.grid
        val bBars = ArrayList<Pair<Long, Int>>()   // (output frame, B's own bar index; -1 when B has no grid)
        if (gridB.beatCount >= 2) {
            val scaleB = sr / b.sampleRate.toDouble()
            val first = gridB.previousDownbeat(gridB.beatAtFrame(Math.round((plan.bWindow.start + (riseEnd - bShift).coerceAtLeast(0L)) / scaleB)))
            var beat = first
            var n = 0
            while (true) {
                val f = Math.round(gridB.frameOfBeat(beat.toDouble()) * scaleB) - plan.bWindow.start + bShift
                if (f > guardEnd) break
                if (f >= riseEnd) bBars += f to gridB.barOfBeat(beat)
                beat += gridB.beatsPerBar; n++
                if (n > 4096) break
            }
        } else {
            var f = riseEnd
            while (f <= guardEnd) { bBars += f to -1; f += Math.round(barB) }
        }
        // B's energy below 4 kHz per bar, measured from the decoded B window.
        val energies = DoubleArray(bBars.size)
        if (bBars.isNotEmpty()) {
            val low = lowBandMono(input.bAudio, sr)
            for (i in bBars.indices) {
                val start = (bBars[i].first - bShift).toInt(); val end = (if (i + 1 < bBars.size) bBars[i + 1].first - bShift else Math.round(bBars[i].first - bShift + barB)).toInt()
                energies[i] = energy(low, start, end)
            }
        }
        // E_ref is B AT FULL LEVEL, not simply the loudest bar inside this window: a segment that only ever sees B's
        // quiet intro would otherwise put E_B/E_ref at 1 on its loudest intro bar and kill the bed right at the seam.
        // `b.bars.energy` is B's per-bar RMS normalised to its own loudest bar, so each measured bar extrapolates to
        // full level as E_meas / energy[bar]; the largest such estimate is the reference. Without bar features (or
        // without a grid) it falls back to the loudest measured bar.
        val relative = b.bars.energy
        var ref = 0.0
        for (i in bBars.indices) {
            val bar = bBars[i].second
            val rel = if (bar in relative.indices) relative[bar].toDouble() else 1.0
            val full = if (rel > FULL_LEVEL_FLOOR) energies[i] / rel else energies[i] / FULL_LEVEL_FLOOR
            if (full > ref) ref = full
        }
        val lane = Lane(LANE_BED).add(0L, 0.0).add(g, 0.0, FadeLaw.S_CURVE).add(riseEnd, 1.0, curve)
        var value = 1.0
        for (i in bBars.indices) {
            val f = bBars[i].first
            if (f <= riseEnd || f >= releaseStart) continue
            val gB = if (ref > 0.0) (1.0 - energies[i] / ref).coerceIn(0.0, 1.0) else 1.0
            value = min(value, gB)
            lane.add(f, value, curve)
        }
        val atRelease = lane.valueAt(releaseStart)
        lane.add(releaseStart, min(value, atRelease), curve).add(guardEnd, 0.0).add(frames.toLong(), 0.0)
        return lane
    }

    // ------------------------------------------------------------------------------------------------ beds

    /** NOISE bed: seeded noise → STFT → × target magnitude (× optional morph) → ISTFT at 22.05 kHz, resampled to [sr]. */
    private fun noiseBed(aAudio: AudioBuffer, aDryEnd: Int, a: TrackAnalysis, frames: Int, ch: Int, sr: Int, seed: Int, morphDb: FloatArray?): AudioBuffer {
        val target = if (a.textureMagnitude.size == SpectrumAnalyzer.TEXTURE_BINS && a.textureMagnitude.any { it > 0f }) a.textureMagnitude
        else measuredTexture(aAudio, max(0, aDryEnd - Math.round(TEXTURE_SOURCE_SEC * sr).toInt()), aDryEnd)
        val fs = SpectrumAnalyzer.TEXTURE_SAMPLE_RATE
        val n = SpectrumAnalyzer.TEXTURE_FRAME; val hop = SpectrumAnalyzer.TEXTURE_HOP; val bins = SpectrumAnalyzer.TEXTURE_BINS
        // `frames.toDouble()` first: `frames * fs` overflows Int for segments longer than ~1.6 min at 22.05 kHz.
        val len22 = ceil(frames.toDouble() * fs / sr).toInt() + n
        val morphBins = morphDb?.let { ThirdOctaveBands.interpolateToBins(it, n, fs) }
        val resampler = Resampler()
        val out = Array(ch) { c ->
            val rnd = Random(seed + 7919 * c)
            val noise = FloatArray(len22) { rnd.nextFloat() * 2f - 1f }
            val window = Window.hann(n)
            val stft = Stft(n, hop, window, center = true)
            val istft = Istft(n, hop, window, center = true)
            val buf = FloatArray(len22 + 2 * n)
            var pos = 0
            val re = FloatArray(bins); val im = FloatArray(bins)
            val total = stft.frameCount(len22).coerceAtLeast(1)
            val sink = StftFrameSink { t, r, i ->
                val frac = (t.toDouble() / total).coerceIn(0.0, 1.0)
                for (k in 0 until bins) {
                    var m = target[k]
                    if (morphBins != null) m *= 10.0.pow(morphBins[k] * frac / 20.0).toFloat()
                    re[k] = r[k] * m; im[k] = i[k] * m
                }
                pos += istft.pushFrame(re, im, buf, pos)
            }
            stft.process(noise, 0, len22, sink)
            stft.flush(sink)
            pos += istft.flush(buf, pos)
            val bed22 = buf.copyOf(min(len22, pos))
            val up = resampler.resample(bed22, fs, sr)
            if (up.size == frames) up else FloatArray(frames) { if (it < up.size) up[it] else 0f }
        }
        return AudioBuffer(sr, out)
    }

    /** OTHER bed: the `other` pseudo-stem of A's last seconds, granulated. */
    private fun otherBed(aAudio: AudioBuffer, aDryEnd: Int, frames: Int, sr: Int, seed: Int): AudioBuffer {
        val excerpt = aAudio.slice(max(0, aDryEnd - Math.round(OTHER_SOURCE_SEC * sr).toInt()), aDryEnd)
        val other = PseudoStemSeparator().separate(excerpt).other
        return Granulator(sr, seed = seed, minGrainMs = 120.0, maxGrainMs = 300.0, voices = 6).render(other, frames)
    }

    private fun rms(x: AudioBuffer, start: Int, end: Int): Float {
        val s = start.coerceIn(0, x.frames); val e = end.coerceIn(s, x.frames)
        if (e <= s) return 0f
        var acc = 0.0
        for (c in 0 until x.channelCount) { val v = x[c]; for (i in s until e) acc += v[i].toDouble() * v[i] }
        return sqrt(acc / ((e - s).toDouble() * x.channelCount)).toFloat()
    }

    private fun energy(x: FloatArray, start: Int, end: Int): Double {
        val s = start.coerceIn(0, x.size); val e = end.coerceIn(s, x.size)
        if (e <= s) return 0.0
        var acc = 0.0
        for (i in s until e) acc += x[i].toDouble() * x[i]
        return acc / (e - s)
    }

    /** Mono mix low-passed at 4 kHz (two Butterworth biquads = 24 dB/oct) — the sub + bass + mid share of B. */
    private fun lowBandMono(x: AudioBuffer, sr: Int): FloatArray {
        val m = x.mono().copyOf()
        val f = BiquadFilter(1, Biquad.lowPass(LOW_BAND_HZ, sr.toDouble()))
        f.process(m, m, m.size)
        f.reset()
        f.process(m, m, m.size)
        return m
    }

    companion object {
        const val ID = "textureCarry"
        const val LEVEL_GATE_DB = -45.0
        const val LEVEL_REF_SEC = 4.0
        const val TEXTURE_SOURCE_SEC = 15.0
        const val FREEZE_SOURCE_SEC = 0.4
        const val OTHER_SOURCE_SEC = 4.0
        const val LOW_BAND_HZ = 4000.0
        const val MAX_GAIN = 100f
        /** Smallest relative per-bar energy of B used to extrapolate a measured bar to B's full level. */
        const val FULL_LEVEL_FLOOR = 0.1

        const val LANE_BED = "textureBed"
        const val MARKER_IN = "texture bed in"
        const val MARKER_HOLD = "texture bed holds"
        const val MARKER_OUT = "texture bed gone"

        /** Per-bin median magnitude of `[start, end)` with the analysis geometry (513 bins, 22.05 kHz). */
        fun measuredTexture(audio: AudioBuffer, start: Int, end: Int): FloatArray = SpectrumAnalyzer().textureMagnitude(audio, start.toLong(), end.toLong())

        /**
         * A's texture level in dBFS: the RMS implied by `textureMagnitude` under the analysis STFT convention
         * (unscaled FFT of a 1024-point Hann frame: `rms² = Σ_k c_k M_k² / (N Σw²)`), else the total power of
         * A's outro LTAS, else [LEVEL_GATE_DB] + 15 (unknown: moderately textured).
         */
        fun textureLevelDb(a: TrackAnalysis): Double {
            val m = a.textureMagnitude
            if (m.size == SpectrumAnalyzer.TEXTURE_BINS) {
                val n = SpectrumAnalyzer.TEXTURE_FRAME
                var acc = 0.0
                for (k in m.indices) { val c = if (k == 0 || k == m.size - 1) 1.0 else 2.0; acc += c * m[k].toDouble() * m[k] }
                val rms = sqrt(acc / (n * 0.375 * n))
                return if (rms > 1e-9) 20.0 * log10(rms) else -160.0
            }
            val ltas = a.outroLtasDb.takeIf { it.isNotEmpty() } ?: a.ltasDb
            if (ltas.isNotEmpty()) {
                var pw = 0.0
                for (v in ltas) pw += 10.0.pow(v / 10.0)
                return if (pw > 1e-16) 10.0 * log10(pw) else -160.0
            }
            return LEVEL_GATE_DB + 15.0
        }

        /** Per-band morph gains (dB): B's intro LTAS minus A's outro LTAS, clamped to ±[maxDb]; null when either is missing. */
        fun morphGainsDb(a: TrackAnalysis, b: TrackAnalysis, maxDb: Double): FloatArray? {
            val la = a.outroLtasDb.takeIf { it.size == ThirdOctaveBands.BANDS } ?: a.ltasDb.takeIf { it.size == ThirdOctaveBands.BANDS } ?: return null
            val lb = b.introLtasDb.takeIf { it.size == ThirdOctaveBands.BANDS } ?: b.ltasDb.takeIf { it.size == ThirdOctaveBands.BANDS } ?: return null
            return FloatArray(ThirdOctaveBands.BANDS) { (lb[it] - la[it]).toDouble().coerceIn(-maxDb, maxDb).toFloat() }
        }
    }
}
