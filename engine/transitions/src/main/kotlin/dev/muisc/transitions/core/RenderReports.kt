package dev.muisc.transitions.core

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.loudness.LoudnessMeter
import dev.muisc.dsp.loudness.TruePeak
import dev.muisc.dsp.loudness.TruePeakLimiter
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderReport
import kotlin.math.abs

/**
 * Builds `RenderReport`s and finalises segment audio.
 *
 * [build] runs the `dsp` [ArtifactDetector] (clicks, sustained level jumps, clipping runs → warnings), the BS.1770
 * [LoudnessMeter] (integrated LUFS) and the [TruePeak] meter (dBTP) over the segment.
 *
 * [finalize] applies the `dsp` [TruePeakLimiter] at [DEFAULT_CEILING_DBTP] **only if the segment's true peak exceeds
 * the ceiling** — otherwise the audio is untouched (bit-transparent), which is what keeps the splice contract exact
 * for segments that never clip. The limiter's look-ahead delay is compensated (time-aligned one-shot), and the dry
 * guard regions at both ends are protected: they are restored verbatim if the limiter's gain reached into them, with
 * a short blend just inside the segment so no step is introduced (a warning is added; a peak inside a guard region
 * is source material the player plays at that level anyway).
 */
object RenderReports {
    const val DEFAULT_CEILING_DBTP: Double = -1.0
    const val GUARD_FRAMES: Int = Splice.GUARD_FRAMES
    const val GUARD_TOLERANCE: Float = 1e-3f
    private const val RESTORE_BLEND_FRAMES = 256

    /** Outcome of [finalize]. */
    class Finalized(
        /** Whether the limiter ran at all. */
        val limited: Boolean,
        /** True peak before finalisation, dBTP. */
        val truePeakBeforeDbtp: Double,
        /** Maximum gain reduction applied, dB (0 when bypassed). */
        val gainReductionDb: Double,
        val warnings: List<String>,
    )

    /**
     * Report for [audio]: peak, true peak, integrated loudness, artifact warnings, plus `metrics` entries
     * (`peak`, `truePeakDbtp`, `integratedLufs`, `clicks`, `levelJumps`, `clipRuns`, `frames`, `durationSec`) merged
     * with [metrics]. [warnings] are prepended to the detector's.
     */
    fun build(
        audio: AudioBuffer, renderMillis: Long, ratioTrace: FloatArray = FloatArray(0), metrics: Map<String, Double> = emptyMap(),
        warnings: List<String> = emptyList(), renderKey: String = "",
    ): RenderReport {
        val w = ArrayList(warnings)
        val m = LinkedHashMap<String, Double>()
        val peak = audio.peak()
        val tp = if (audio.frames == 0) Double.NEGATIVE_INFINITY else TruePeak.measureDbtp(audio)
        val lufs = if (audio.frames == 0) Double.NEGATIVE_INFINITY else LoudnessMeter.integratedLufs(audio)
        var clicks = 0; var jumps = 0; var clips = 0
        if (audio.frames > 0) {
            val report = ArtifactDetector(audio.sampleRate).analyze(audio)
            clicks = report.clicks.size; jumps = report.levelJumps.size; clips = report.clipRuns.size
            for (c in report.clicks.take(10)) w += "click at frame ${c.frame} (ch ${c.channel}, ${"%.3f".format(c.magnitude)}, x${"%.1f".format(c.ratio)})"
            if (report.clicks.size > 10) w += "${report.clicks.size - 10} more clicks"
            for (j in report.levelJumps.take(10)) w += "level jump at frame ${j.frame} (ch ${j.channel}): ${"%.1f".format(j.fromDb)} -> ${"%.1f".format(j.toDb)} dBFS"
            for (r in report.clipRuns.take(10)) w += "clipping at frame ${r.start} (ch ${r.channel}, ${r.length} samples)"
            if (report.clipRuns.size > 10) w += "${report.clipRuns.size - 10} more clipping runs"
            for (ch in report.dcOffsetExceeded) w += "DC offset on channel $ch: ${"%.4f".format(report.dcOffset[ch])}"
        }
        m["peak"] = peak.toDouble()
        m["truePeakDbtp"] = tp
        m["integratedLufs"] = lufs
        m["clicks"] = clicks.toDouble()
        m["levelJumps"] = jumps.toDouble()
        m["clipRuns"] = clips.toDouble()
        m["frames"] = audio.frames.toDouble()
        m["durationSec"] = audio.durationSec
        m["renderMillis"] = renderMillis.toDouble()
        m.putAll(metrics)
        return RenderReport(
            renderMillis = renderMillis, peak = peak, truePeakDbtp = tp.toFloat(), integratedLufs = lufs.toFloat(),
            warnings = w, renderKey = renderKey, metrics = m, ratioTrace = ratioTrace,
        )
    }

    /**
     * Finalises [audio] in place (see the object doc): true-peak limiting at [ceilingDbtp] only when needed, guard
     * regions of [guardFrames] frames at both ends preserved within [GUARD_TOLERANCE].
     */
    fun finalize(audio: AudioBuffer, ctx: RenderContext, ceilingDbtp: Double = DEFAULT_CEILING_DBTP, guardFrames: Int = GUARD_FRAMES): Finalized {
        if (audio.frames == 0) return Finalized(false, Double.NEGATIVE_INFINITY, 0.0, emptyList())
        val before = TruePeak.measureDbtp(audio)
        if (before <= ceilingDbtp) return Finalized(false, before, 0.0, emptyList())
        val warnings = ArrayList<String>()
        val ch = audio.channelCount
        val g = minOf(guardFrames, audio.frames / 2).coerceAtLeast(0)
        val blend = minOf(RESTORE_BLEND_FRAMES, (audio.frames - 2 * g) / 2).coerceAtLeast(0)
        // Snapshot guard + blend region at both ends so a touched guard can be restored and blended back in.
        val head = Array(ch) { audio[it].copyOfRange(0, g + blend) }
        val tail = Array(ch) { audio[it].copyOfRange(audio.frames - g - blend, audio.frames) }
        val reduction = TruePeakLimiter.processInPlace(audio, ceilingDbtp)
        if (g > 0) {
            if (maxAbsDiff(audio, 0, head, g) > GUARD_TOLERANCE) {
                restoreHead(audio, head, g, blend)
                warnings += "limiter reached into the $g-frame pre-roll; restored verbatim (blend of $blend frames after it)"
            }
            if (maxAbsDiff(audio, audio.frames - g, tail, g, blend) > GUARD_TOLERANCE) {
                restoreTail(audio, tail, g, blend)
                warnings += "limiter reached into the $g-frame post-roll; restored verbatim (blend of $blend frames before it)"
            }
        }
        return Finalized(true, before, reduction, warnings)
    }

    private fun maxAbsDiff(audio: AudioBuffer, offset: Int, ref: Array<FloatArray>, n: Int, refOffset: Int = 0): Float {
        var m = 0f
        for (c in ref.indices) { val x = audio[c]; val r = ref[c]; for (i in 0 until n) { val d = abs(x[offset + i] - r[refOffset + i]); if (d > m) m = d } }
        return m
    }

    /** Restores `[0, g)` verbatim and blends linearly (coherent material) from original to limited over `[g, g + blend)`. */
    private fun restoreHead(audio: AudioBuffer, ref: Array<FloatArray>, g: Int, blend: Int) {
        for (c in ref.indices) {
            val x = audio[c]; val r = ref[c]
            System.arraycopy(r, 0, x, 0, g)
            for (i in 0 until blend) { val t = (i + 1).toFloat() / (blend + 1); x[g + i] = r[g + i] * (1f - t) + x[g + i] * t }
        }
    }

    /** Restores the last `g` frames verbatim and blends from limited to original over the `blend` frames before them. */
    private fun restoreTail(audio: AudioBuffer, ref: Array<FloatArray>, g: Int, blend: Int) {
        val n = audio.frames
        for (c in ref.indices) {
            val x = audio[c]; val r = ref[c]
            System.arraycopy(r, blend, x, n - g, g)
            for (i in 0 until blend) { val t = (i + 1).toFloat() / (blend + 1); val k = n - g - 1 - i; x[k] = r[blend - 1 - i] * (1f - t) + x[k] * t }
        }
    }
}
