package dev.muisc.metrics

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.loudness.LoudnessMeter
import dev.muisc.dsp.loudness.TruePeak
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.core.MasterGrid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The numeric quality checks every rendered transition is judged by (DESIGN.md §9, "Artifact metrics").
 *
 * Three entry points, cheapest first:
 *  - [installChecks] — what the phone runs before splicing a render into the program (clicks, level jump, true
 *    peak, NaN/Inf, silence gaps, seam identity). A few tens of milliseconds for a 10 s segment.
 *  - [evaluate] — everything, for tests, the CLI `check` command and the Transition Lab.
 *  - [evaluateProgramOutput] — the player's own output around known seam frames.
 *
 * Conventions. All measurements are made on the mono mix except `clipping`, `nanInf`, `dcOffsetDb`,
 * `truePeakDbtp` and `stereoCorrelationMin`, which are inherently per-channel. Every block grid is
 * non-overlapping and anchored at frame 0 of the render, so the numbers are deterministic. A metric that cannot
 * be computed (no `input`, no master beats, mono audio...) is simply absent from the report — callers use
 * [MetricsReport.value] / [MetricsReport.verdict], which return null, rather than assuming a fixed column set.
 *
 * "Explained by a source onset". A crossfade of two real tracks is full of legitimate discontinuities in the
 * *sources* (drum attacks). Both the click and the level-jump check therefore ignore positions near an onset of
 * either source window ([ONSET_GUARD_MS] for clicks, the wider [LEVEL_ONSET_GUARD_MS] for level steps, whose
 * statistic spans several windows), detected with [Signals.onsetFrames] and mapped into output frames through
 * the splice contract: output frame `o` is A's frame `plan.aExitOffset + o` and B's frame
 * `plan.bEntryOffset - outputFrames + o`. That mapping is exact in the dry guard regions and approximate in a
 * time-stretched middle, which is all the exclusion needs. Without sources the level check falls back to the
 * render's own onsets (see [levelOnsets]); the click check needs no exclusion at all, its peak-versus-local-RMS
 * criterion is conservative enough to report zero clicks on undamaged material.
 */
object ArtifactMetrics {

    // ---- metric ids -------------------------------------------------------------------------------------

    const val CLICKS = "clicks"
    const val LEVEL_JUMP_DB = "levelJumpDb"
    const val TRUE_PEAK_DBTP = "truePeakDbtp"
    const val CLIPPING = "clipping"
    const val DC_OFFSET_DB = "dcOffsetDb"
    const val NAN_INF = "nanInf"
    const val SILENCE_GAP_MS = "silenceGapMs"
    const val SEAM_IDENTITY = "seamIdentity"
    const val SEAM_CORRELATION = "seamCorrelation"
    const val BEAT_ALIGNMENT_MS = "beatAlignmentMs"
    const val BEAT_ALIGNMENT_MAX_MS = "beatAlignmentMaxMs"
    const val LOUDNESS_SMOOTHNESS = "loudnessSmoothness"
    const val STEREO_CORRELATION_MIN = "stereoCorrelationMin"
    const val TAIL_CONTAINED_DB = "tailContainedDb"
    const val LENGTH_ERROR_PCT = "lengthErrorPct"
    /** Informational metric of [evaluateProgramOutput]: how many seams were inspected. */
    const val SEAMS = "seams"

    /** Automation lane whose points carry the master beat times of the render (seconds from its first frame). */
    const val MASTER_BEAT_LANE = "masterBeat"

    // ---- thresholds (DESIGN.md §9) ----------------------------------------------------------------------

    /** Any click at all is a failure. */
    const val CLICKS_FAIL = 0.0
    const val LEVEL_JUMP_WARN_DB = 4.0
    const val LEVEL_JUMP_FAIL_DB = 6.0
    /** Renders are limited to -1 dBTP; -0.5 dBTP means the limiter was overrun. */
    const val TRUE_PEAK_WARN_DBTP = -1.0
    const val TRUE_PEAK_FAIL_DBTP = -0.5
    const val CLIPPING_FAIL = 0.0
    const val DC_OFFSET_FAIL_DB = -50.0
    const val NAN_INF_FAIL = 0.0
    const val SILENCE_GAP_WARN_MS = 50.0
    const val SEAM_DIFF_WARN = 1e-4
    const val SEAM_DIFF_FAIL = 1e-3
    const val SEAM_CORRELATION_WARN = 0.9999
    const val SEAM_CORRELATION_FAIL = 0.999
    const val BEAT_ALIGNMENT_WARN_MS = 5.0
    const val BEAT_ALIGNMENT_FAIL_MS = 12.0
    const val LOUDNESS_SMOOTHNESS_WARN = 3.0
    /**
     * Span, in 100 ms grid steps, over which the curvature of the short-term loudness is measured. Short-term
     * LUFS is a 3 s sliding window and therefore ripples at the beat rate (~0.7 LU peak-to-peak on a 120 BPM
     * kick pattern); a second difference taken over one 100 ms step measures that ripple (tens of LU/s²), not
     * the loudness ride. Over half a second the ripple averages out while a real level move — a 20 dB dip, a
     * botched fade — still shows up well above the 3 LU/s² threshold.
     */
    const val LOUDNESS_SPAN_STEPS = 5
    const val STEREO_CORRELATION_WARN = -0.3
    const val TAIL_CONTAINED_WARN_DB = -60.0
    const val TAIL_CONTAINED_FAIL_DB = -40.0
    const val LENGTH_ERROR_WARN_PCT = 1.0

    // ---- analysis constants -----------------------------------------------------------------------------

    /** High-pass corner of the click detector. */
    const val CLICK_HPF_HZ = 8000.0
    /**
     * A click is a 1 ms window whose largest step is this many dB above the local 50 ms RMS of the high-passed
     * first difference. The window is represented by its peak rather than its own RMS on purpose: a
     * discontinuity is energy concentrated in one or two samples, and a 1 ms RMS would dilute it by sqrt(44),
     * putting a plainly audible single-sample step below the high-frequency noise of ordinary percussive
     * material (measured on a `SyntheticSong` crossfade: peak-to-local-RMS tops out at 17 dB with no defect,
     * while a 0.2 full-scale step reads 31 dB).
     */
    const val CLICK_RISE_DB = 20.0
    /**
     * Absolute floor on the step height a click must have. Truncated notes in real material (and in
     * `SyntheticSong`, whose bass/kick notes end without a release) produce genuine steps up to ~0.09 that are
     * inaudible in context; the artificial discontinuities this detector exists for are an order of magnitude
     * larger. Same calibration as `dsp`'s `ArtifactDetector.clickMinMagnitude`.
     */
    const val CLICK_MIN_STEP = 0.1
    const val CLICK_WINDOW_MS = 1.0
    const val CLICK_LOCAL_MS = 50.0
    /** Half-width of the "not a click, there is an onset here" exclusion window (DESIGN.md §9: 5 ms). */
    const val ONSET_GUARD_MS = 5.0
    const val LEVEL_WINDOW_MS = 10.0
    /** Windows on each side that must hold the new level for a step to count as a jump. */
    const val LEVEL_HOLD_WINDOWS = 3
    /** Steps whose quiet side is below this are fades in/out of silence, not jumps. */
    const val LEVEL_SILENCE_DB = -60.0
    /** Steps whose loud side is below this are inaudible (the tail of a fade drops arbitrarily fast in dB). */
    const val LEVEL_FLOOR_DB = -40.0
    /**
     * Onset exclusion half-width of the level check. The jump statistic at a window boundary looks at
     * [LEVEL_HOLD_WINDOWS] windows on each side, so an onset anywhere in that span (plus one window of slack)
     * is what the step measures; 5 ms would leave every drum attack's decay counted as a drop.
     */
    const val LEVEL_ONSET_GUARD_MS = LEVEL_WINDOW_MS * (LEVEL_HOLD_WINDOWS + 1)
    const val SILENCE_BLOCK_MS = 5.0
    const val SILENCE_DB = -70.0
    /** Frames compared on each side of the segment for the splice contract. */
    const val SEAM_FRAMES = 2048
    const val TAIL_MS = 100.0
    const val STEREO_WINDOW_MS = 400.0
    /** Beats farther than this from any detected onset are counted as unmatched instead of as a huge error. */
    const val BEAT_MATCH_WINDOW_MS = 100.0
    /** Half-width of the window [evaluateProgramOutput] inspects around a program seam. */
    const val SEAM_WINDOW_MS = 50.0

    // ---- entry points -----------------------------------------------------------------------------------

    /**
     * Every metric that can be computed from [rendered], its [input] (optional: without it the seam, tail and
     * onset-exclusion checks are skipped or relaxed) and the master beat grid.
     *
     * [masterBeats] are beat times in **seconds from the first frame of the rendered segment**. When null, the
     * `"masterBeat"` [dev.muisc.transitions.AutomationLane] of the plan is used if the strategy published one;
     * when neither exists the two `beatAlignment*` metrics are absent.
     */
    fun evaluate(rendered: RenderedTransition, input: TransitionInput?, masterBeats: DoubleArray? = null): MetricsReport {
        val audio = rendered.audio
        val onsets = sourceOnsetsInOutput(rendered, input)
        val out = ArrayList<Metric>(16)
        out += clicks(audio, onsets)
        out += levelJump(audio, levelOnsets(audio, input, onsets))
        out += truePeak(audio)
        out += clipping(audio)
        out += dcOffset(audio)
        out += nanInf(audio)
        out += silenceGap(audio)
        seamMetrics(rendered, input).forEach { out += it }
        beatAlignment(rendered, masterBeats ?: planMasterBeats(rendered)).forEach { out += it }
        out += loudnessSmoothness(audio)
        stereoCorrelation(audio)?.let { out += it }
        out += tailContained(rendered, input)
        out += lengthError(rendered)
        return MetricsReport(out)
    }

    /** [evaluate] with the beat times taken from a [MasterGrid] (its beat frames are relative to its own start). */
    fun evaluate(rendered: RenderedTransition, input: TransitionInput?, grid: MasterGrid): MetricsReport =
        evaluate(rendered, input, beatsOf(grid))

    /**
     * The cheap subset run on the phone before a render is installed (DESIGN.md §2.7 `RenderGate`):
     * clicks, level jump, true peak, NaN/Inf, silence gaps and the seam identity against the sources.
     */
    fun installChecks(rendered: RenderedTransition, input: TransitionInput): MetricsReport {
        val audio = rendered.audio
        val onsets = sourceOnsetsInOutput(rendered, input)
        val out = ArrayList<Metric>(7)
        out += clicks(audio, onsets)
        out += levelJump(audio, onsets)  // installChecks always has the sources
        out += truePeak(audio)
        out += nanInf(audio)
        out += silenceGap(audio)
        seamMetrics(rendered, input).forEach { out += it }
        return MetricsReport(out)
    }

    /**
     * Metrics of a whole program render (`ProgramPlayer` tests, DESIGN.md §9): clicks and level jumps are only
     * looked for around each seam frame (±50 ms), plus the global peak, clipping and NaN/Inf checks.
     *
     * [seams] are frame positions in [pcm] where one segment gives way to the next.
     */
    fun evaluateProgramOutput(pcm: AudioBuffer, seams: List<Long>): MetricsReport {
        val sr = pcm.sampleRate
        val half = Signals.msFrames(SEAM_WINDOW_MS, sr)
        var clicks = 0.0
        var jump = 0.0
        for (seam in seams) {
            val from = max(0L, seam - half).toInt()
            val to = min(pcm.frames.toLong(), seam + half).toInt()
            if (to - from < 4) continue
            val region = pcm.slice(from, to)
            clicks += clickCount(region, IntArray(0))
            jump = max(jump, levelJumpDb(region, Signals.onsetFrames(region)))
        }
        return MetricsReport(
            listOf(
                Metric.upper(CLICKS, clicks, "count", CLICKS_FAIL, CLICKS_FAIL),
                Metric.upper(LEVEL_JUMP_DB, jump, "dB", LEVEL_JUMP_WARN_DB, LEVEL_JUMP_FAIL_DB),
                truePeak(pcm),
                clipping(pcm),
                nanInf(pcm),
                Metric.info(SEAMS, seams.size.toDouble(), "count"),
            ),
        )
    }

    // ---- individual metrics -----------------------------------------------------------------------------

    /**
     * Clicks: the mono mix is high-passed at [CLICK_HPF_HZ] and differentiated; a 1 ms window whose largest
     * step reaches [CLICK_MIN_STEP] and stands [CLICK_RISE_DB] above the RMS of the surrounding 50 ms (that
     * window excluded) is a click, unless an [excludedFrames] onset sits within [ONSET_GUARD_MS] of it.
     */
    fun clicks(audio: AudioBuffer, excludedFrames: IntArray = IntArray(0)): Metric =
        Metric.upper(CLICKS, clickCount(audio, excludedFrames).toDouble(), "count", CLICKS_FAIL, CLICKS_FAIL)

    fun clickCount(audio: AudioBuffer, excludedFrames: IntArray): Int {
        val sr = audio.sampleRate
        if (audio.frames < 8) return 0
        val x = Signals.mono(audio)
        val d = Signals.firstDifference(Signals.highPass(x, sr, CLICK_HPF_HZ))
        val w = Signals.msFrames(CLICK_WINDOW_MS, sr)
        val nw = Signals.blockCount(d.size, w)
        if (nw < 3) return 0
        val prefix = Signals.energyPrefix(d)
        val local = Signals.msFrames(CLICK_LOCAL_MS, sr) / 2
        val guard = Signals.msFrames(ONSET_GUARD_MS, sr)
        val riseRatio = Math.pow(10.0, CLICK_RISE_DB / 20.0)
        var count = 0
        var lastClickBlock = -3
        for (k in 0 until nw) {
            val from = k * w
            val to = from + w
            var step = 0.0
            for (i in from until to) { val a = abs(d[i]).toDouble(); if (a > step) step = a }
            if (step < CLICK_MIN_STEP) continue
            val lo = max(0, from - local)
            val hi = min(d.size, to + local)
            val energyAround = (prefix[hi] - prefix[lo]) - (prefix[to] - prefix[from])
            val framesAround = (hi - lo) - w
            val localRms = if (framesAround > 0) Math.sqrt(max(0.0, energyAround) / framesAround) else 0.0
            if (localRms > 0.0 && step < riseRatio * localRms) continue
            if (nearAny(excludedFrames, from + w / 2, guard)) continue
            if (k - lastClickBlock < 3) continue // one click is at most a couple of 1 ms windows wide
            count++
            lastClickBlock = k
        }
        return count
    }

    /**
     * Largest sustained short-term level step in dB: 10 ms RMS windows, a step counts only when the new level
     * holds for [LEVEL_HOLD_WINDOWS] windows on both sides (so decaying drum hits do not register), the quiet
     * side is above [LEVEL_SILENCE_DB] (fades into silence are not jumps), the loud side above
     * [LEVEL_FLOOR_DB], and no source onset sits within [LEVEL_ONSET_GUARD_MS] of the boundary.
     *
     * The step is measured between the *loudest* window before the boundary and the *quietest* after it (and
     * symmetrically for a drop), so the number reported is the part of the step that exceeds the material's own
     * variation over the hold span — a conservative estimate: a 6 dB step in busy material reads ~4.7 dB, a
     * 10 dB step ~8.7 dB.
     */
    fun levelJump(audio: AudioBuffer, excludedFrames: IntArray = IntArray(0)): Metric =
        Metric.upper(LEVEL_JUMP_DB, levelJumpDb(audio, excludedFrames), "dB", LEVEL_JUMP_WARN_DB, LEVEL_JUMP_FAIL_DB)

    fun levelJumpDb(audio: AudioBuffer, excludedFrames: IntArray): Double {
        val sr = audio.sampleRate
        val w = Signals.msFrames(LEVEL_WINDOW_MS, sr)
        val db = Signals.blockRmsDb(Signals.mono(audio), w)
        val hold = LEVEL_HOLD_WINDOWS
        if (db.size < 2 * hold + 1) return 0.0
        val guard = Signals.msFrames(LEVEL_ONSET_GUARD_MS, sr)
        var worst = 0.0
        for (k in hold..db.size - hold) {
            if (nearAny(excludedFrames, k * w, guard)) continue
            var preMin = Double.MAX_VALUE; var preMax = -Double.MAX_VALUE
            for (j in k - hold until k) { val v = db[j]; if (v < preMin) preMin = v; if (v > preMax) preMax = v }
            var postMin = Double.MAX_VALUE; var postMax = -Double.MAX_VALUE
            for (j in k until k + hold) { val v = db[j]; if (v < postMin) postMin = v; if (v > postMax) postMax = v }
            val rise = if (preMax > LEVEL_SILENCE_DB && postMin > LEVEL_FLOOR_DB) postMin - preMax else 0.0
            val drop = if (postMax > LEVEL_SILENCE_DB && preMin > LEVEL_FLOOR_DB) preMin - postMax else 0.0
            val jump = max(rise, drop)
            if (jump > worst) worst = jump
        }
        return worst
    }

    fun truePeak(audio: AudioBuffer): Metric {
        val dbtp = if (audio.frames == 0) Double.NEGATIVE_INFINITY else TruePeak.measureDbtp(audio)
        return Metric.upper(TRUE_PEAK_DBTP, dbtp, "dBTP", TRUE_PEAK_WARN_DBTP, TRUE_PEAK_FAIL_DBTP)
    }

    /** Number of samples with `|x| >= 1.0` over all channels. */
    fun clipping(audio: AudioBuffer): Metric {
        var n = 0L
        for (ch in audio.channels) for (v in ch) if (v >= 1.0f || v <= -1.0f) n++
        return Metric.upper(CLIPPING, n.toDouble(), "count", CLIPPING_FAIL, CLIPPING_FAIL)
    }

    /** Largest per-channel DC offset in dBFS. */
    fun dcOffset(audio: AudioBuffer): Metric {
        var worst = 0.0
        for (ch in audio.channels) {
            if (ch.isEmpty()) continue
            var acc = 0.0
            for (v in ch) acc += v
            val mean = abs(acc / ch.size)
            if (mean > worst) worst = mean
        }
        return Metric.upper(DC_OFFSET_DB, Signals.db(worst), "dBFS", Double.NaN, DC_OFFSET_FAIL_DB)
    }

    /** Number of non-finite samples. */
    fun nanInf(audio: AudioBuffer): Metric {
        var n = 0L
        for (ch in audio.channels) for (v in ch) if (!v.isFinite()) n++
        return Metric.upper(NAN_INF, n.toDouble(), "count", NAN_INF_FAIL, NAN_INF_FAIL)
    }

    /** Longest interior run below [SILENCE_DB], in milliseconds (runs touching either end are not gaps). */
    fun silenceGap(audio: AudioBuffer): Metric =
        Metric.upper(SILENCE_GAP_MS, silenceGapMs(audio), "ms", SILENCE_GAP_WARN_MS, Double.NaN)

    fun silenceGapMs(audio: AudioBuffer): Double {
        val sr = audio.sampleRate
        val block = Signals.msFrames(SILENCE_BLOCK_MS, sr)
        val db = Signals.blockRmsDb(Signals.mono(audio), block)
        if (db.size < 3) return 0.0
        var longest = 0
        var run = 0
        var runStart = 0
        for (k in db.indices) {
            if (db[k] < SILENCE_DB) {
                if (run == 0) runStart = k
                run++
            } else {
                if (run > 0 && runStart > 0 && run > longest) longest = run
                run = 0
            }
        }
        // A trailing run touches the end of the segment: not an interior gap.
        return longest * block * 1000.0 / sr
    }

    /**
     * The splice contract (DESIGN.md §2.4): the first [SEAM_FRAMES] frames of the render must BE A's audio from
     * `plan.aExitFrame`, the last ones B's audio up to `plan.bEntryFrame`, at unity gain and ratio 1.0. Reported
     * as the worse of the two max-abs differences and the lower of the two zero-lag cross-correlations.
     */
    fun seamMetrics(rendered: RenderedTransition, input: TransitionInput?): List<Metric> {
        if (input == null) return emptyList()
        val plan = rendered.plan
        val out = rendered.audio
        val headN = minOf(SEAM_FRAMES, out.frames, input.aAudio.frames - plan.aExitOffset)
        val tailN = minOf(SEAM_FRAMES, out.frames, plan.bEntryOffset)
        var diff = 0.0
        var corr = 1.0
        if (headN > 0) {
            diff = max(diff, Signals.maxAbsDiff(out, 0, input.aAudio, plan.aExitOffset, headN))
            corr = min(corr, channelCorrelation(out, 0, input.aAudio, plan.aExitOffset, headN))
        }
        if (tailN > 0) {
            diff = max(diff, Signals.maxAbsDiff(out, out.frames - tailN, input.bAudio, plan.bEntryOffset - tailN, tailN))
            corr = min(corr, channelCorrelation(out, out.frames - tailN, input.bAudio, plan.bEntryOffset - tailN, tailN))
        }
        if (headN <= 0 && tailN <= 0) return emptyList()
        return listOf(
            Metric.upper(SEAM_IDENTITY, diff, "", SEAM_DIFF_WARN, SEAM_DIFF_FAIL),
            Metric.lower(SEAM_CORRELATION, corr, "", SEAM_CORRELATION_WARN, SEAM_CORRELATION_FAIL),
        )
    }

    /**
     * Onsets of the render (1 ms ODF with parabolic sub-block interpolation) matched to the master beat times:
     * median and maximum absolute distance in milliseconds. Beats with no onset within
     * [BEAT_MATCH_WINDOW_MS] are not counted; when nothing matches, no metric is produced.
     */
    fun beatAlignment(rendered: RenderedTransition, masterBeats: DoubleArray?): List<Metric> {
        if (masterBeats == null || masterBeats.isEmpty()) return emptyList()
        val audio = rendered.audio
        val onsets = Signals.onsetTimesSec(audio)
        if (onsets.isEmpty()) return emptyList()
        val duration = audio.durationSec
        val window = BEAT_MATCH_WINDOW_MS / 1000.0
        val errors = ArrayList<Double>(masterBeats.size)
        for (t in masterBeats) {
            if (t < 0.0 || t > duration) continue
            var best = Double.MAX_VALUE
            for (o in onsets) { val d = abs(o - t); if (d < best) best = d }
            if (best <= window) errors += best * 1000.0
        }
        if (errors.isEmpty()) return emptyList()
        val arr = DoubleArray(errors.size) { errors[it] }
        val median = Signals.median(arr)
        var worst = 0.0
        for (v in arr) if (v > worst) worst = v
        return listOf(
            Metric.upper(BEAT_ALIGNMENT_MS, median, "ms", BEAT_ALIGNMENT_WARN_MS, Double.NaN),
            Metric.upper(BEAT_ALIGNMENT_MAX_MS, worst, "ms", BEAT_ALIGNMENT_WARN_MS, BEAT_ALIGNMENT_FAIL_MS),
        )
    }

    /**
     * Smoothness of the loudness ride: the largest second derivative of short-term LUFS on the meter's 100 ms
     * grid, in LU/s². A well-behaved transition rides the level; a botched one steps it.
     */
    fun loudnessSmoothness(audio: AudioBuffer): Metric =
        Metric.upper(LOUDNESS_SMOOTHNESS, loudnessSmoothnessLuPerSec2(audio), "LU/s²", LOUDNESS_SMOOTHNESS_WARN, Double.NaN)

    fun loudnessSmoothnessLuPerSec2(audio: AudioBuffer): Double {
        if (audio.frames < audio.sampleRate / 2) return 0.0
        val result = LoudnessMeter.measure(audio)
        val curve = if (result.shortTermLufs.size >= 3) result.shortTermLufs else result.momentaryLufs
        if (curve.size < 3) return 0.0
        val h = LOUDNESS_SPAN_STEPS
        if (curve.size < 2 * h + 1) return 0.0
        val span = result.gridSec * h
        val scale = 1.0 / (span * span)
        var worst = 0.0
        for (i in h until curve.size - h) {
            val a = curve[i - h]; val b = curve[i]; val c = curve[i + h]
            if (!a.isFinite() || !b.isFinite() || !c.isFinite()) continue
            val d2 = abs(a - 2.0 * b + c) * scale
            if (d2 > worst) worst = d2
        }
        return worst
    }

    /**
     * Minimum L/R correlation over 400 ms windows (hop half a window); windows below -60 dBFS are ignored.
     * Strongly negative values mean the mix collapses in mono. Absent for mono renders.
     */
    fun stereoCorrelation(audio: AudioBuffer): Metric? {
        if (audio.channelCount < 2) return null
        val sr = audio.sampleRate
        val w = Signals.msFrames(STEREO_WINDOW_MS, sr)
        if (audio.frames < w) return null
        val l = audio[0]; val r = audio[1]
        val hop = max(1, w / 2)
        val floor = Math.pow(10.0, -60.0 / 20.0)
        var worst = 1.0
        var any = false
        var pos = 0
        while (pos + w <= audio.frames) {
            var energy = 0.0
            for (i in pos until pos + w) { energy += l[i].toDouble() * l[i] + r[i].toDouble() * r[i] }
            val rms = Math.sqrt(energy / (2.0 * w))
            if (rms > floor) {
                val c = Signals.correlation(l, pos, r, pos, w)
                if (c < worst) worst = c
                any = true
            }
            pos += hop
        }
        if (!any) return null
        return Metric.lower(STEREO_CORRELATION_MIN, worst, "", STEREO_CORRELATION_WARN, Double.NaN)
    }

    /**
     * Tail containment: the last [TAIL_MS] of the render must be B alone (no reverb, echo or A residue leaking
     * past the seam). Reported as the residual-to-B energy ratio in dB over that window; -inf when the tail is
     * bit-identical to B. PASS without an [input] to compare against.
     */
    fun tailContained(rendered: RenderedTransition, input: TransitionInput?): Metric {
        val value = tailContainedDb(rendered, input)
        return Metric.upper(TAIL_CONTAINED_DB, value, "dB", TAIL_CONTAINED_WARN_DB, TAIL_CONTAINED_FAIL_DB)
    }

    fun tailContainedDb(rendered: RenderedTransition, input: TransitionInput?): Double {
        if (input == null) return Double.NEGATIVE_INFINITY
        val out = rendered.audio
        val plan = rendered.plan
        val n = minOf(Signals.msFrames(TAIL_MS, out.sampleRate), out.frames, plan.bEntryOffset)
        if (n <= 0) return Double.NEGATIVE_INFINITY
        val b = input.bAudio
        val channels = min(out.channelCount, b.channelCount)
        var residual = 0.0
        var reference = 0.0
        for (c in 0 until channels) {
            val x = out[c]; val y = b[c]
            val xo = out.frames - n; val yo = plan.bEntryOffset - n
            for (i in 0 until n) {
                val d = (x[xo + i] - y[yo + i]).toDouble()
                residual += d * d
                reference += y[yo + i].toDouble() * y[yo + i]
            }
        }
        if (residual <= 0.0) return Double.NEGATIVE_INFINITY
        if (reference <= 0.0) return 0.0
        return Signals.ratioDb(residual / reference)
    }

    /** `|frames - plan.expectedOutputFrames| / expected`, in percent. */
    fun lengthError(rendered: RenderedTransition): Metric {
        val expected = rendered.plan.expectedOutputFrames
        val pct = if (expected <= 0) 0.0 else abs(rendered.audio.frames - expected) * 100.0 / expected
        return Metric.upper(LENGTH_ERROR_PCT, pct, "%", LENGTH_ERROR_WARN_PCT, Double.NaN)
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    /** Master beat times (seconds from the render start) published by a strategy in the [MASTER_BEAT_LANE]. */
    fun planMasterBeats(rendered: RenderedTransition): DoubleArray? {
        val lane = rendered.plan.lanes.firstOrNull { it.id == MASTER_BEAT_LANE } ?: return null
        if (lane.points.isEmpty()) return null
        return DoubleArray(lane.points.size) { lane.points[it].outputSec }
    }

    /** Beat times of [grid] in seconds from its own first beat. */
    fun beatsOf(grid: MasterGrid): DoubleArray {
        val start = grid.startFrame
        return DoubleArray(grid.beatFrames.size) { (grid.beatFrames[it] - start).toDouble() / grid.sampleRate }
    }

    /**
     * Onsets of both source windows expressed in output frames (see the object doc). Sorted ascending; empty
     * when no [input] is available, which simply makes the click and level checks stricter.
     */
    fun sourceOnsetsInOutput(rendered: RenderedTransition, input: TransitionInput?): IntArray {
        if (input == null) return IntArray(0)
        val plan = rendered.plan
        val frames = rendered.audio.frames
        val acc = ArrayList<Int>()
        for (f in Signals.onsetFrames(input.aAudio)) {
            val o = f - plan.aExitOffset
            if (o in 0 until frames) acc += o
        }
        for (f in Signals.onsetFrames(input.bAudio)) {
            val o = f + frames - plan.bEntryOffset
            if (o in 0 until frames) acc += o
        }
        val out = IntArray(acc.size)
        for (i in acc.indices) out[i] = acc[i]
        out.sort()
        return out
    }

    /**
     * Exclusions for the level check: the source onsets when the sources are there, otherwise the render's own
     * onsets.
     *
     * The fallback matters because a transition is normally spliced on a downbeat: the last 100 ms before it are
     * the quietest moment of the bar and the kick that follows is a 40 dB step, which the metric would report as
     * a catastrophic level jump. Judging a render's own attacks by themselves is weaker than judging them
     * against the sources — a gain step that happens to sit exactly on an attack is excused — so `evaluate` and
     * `installChecks` should always be given the [TransitionInput] when one exists.
     */
    fun levelOnsets(audio: AudioBuffer, input: TransitionInput?, sourceOnsets: IntArray): IntArray =
        if (input != null) sourceOnsets else Signals.onsetFrames(audio)

    /** True when [sorted] contains a value within [tolerance] frames of [frame] (binary search). */
    fun nearAny(sorted: IntArray, frame: Int, tolerance: Int): Boolean {
        if (sorted.isEmpty()) return false
        var lo = 0
        var hi = sorted.size - 1
        var best = Int.MAX_VALUE
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val d = sorted[mid] - frame
            val a = abs(d)
            if (a < best) best = a
            if (d == 0) return true
            if (d < 0) lo = mid + 1 else hi = mid - 1
        }
        return best <= tolerance
    }

    private fun channelCorrelation(x: AudioBuffer, xOff: Int, y: AudioBuffer, yOff: Int, n: Int): Double {
        var worst = 1.0
        val channels = min(x.channelCount, y.channelCount)
        for (c in 0 until channels) {
            val v = Signals.correlation(x[c], xOff, y[c], yOff, n)
            if (v < worst) worst = v
        }
        return worst
    }
}
