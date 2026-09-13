package dev.muisc.analysis.features

import dev.muisc.analysis.model.LoudnessInfo
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.loudness.LoudnessMeter
import dev.muisc.dsp.loudness.TruePeak

/**
 * Programme loudness for [dev.muisc.analysis.model.TrackAnalysis.loudness]: wraps the stage-1
 * [LoudnessMeter] (ITU-R BS.1770-4 integrated loudness with absolute/relative gating, EBU Tech 3342
 * loudness range) and [TruePeak] (4x oversampled true peak).
 *
 * Output conventions of the [LoudnessInfo] produced here:
 *  - `integratedLufs` / `truePeakDbtp`: LUFS / dBTP; digital silence reads [FeatureRate.SILENCE_DB] (-120)
 *    instead of -inf;
 *  - `shortTermLufs[k]` is the 3 s short-term loudness of the window that **starts** at `k * shortTermHopSec`
 *    seconds (i.e. ends at `k * 0.1 + 3` s) relative to the analysed range — the same array the meter
 *    produces (first value covers 0..3 s). It is empty for ranges shorter than 3 s;
 *  - `shortTermHopSec` = 0.1;
 *  - `loudnessRangeLu`: LRA in LU (0 when fewer than two short-term blocks pass the gates).
 * Runs at the engine rate on all channels (BS.1770 channel weights); never throws on short / silent input.
 */
class LoudnessAnalyzer {
    /** Loudness of engine frames `[startFrame, endFrame)` (clamped) of [audio]. */
    fun analyze(audio: AudioBuffer, startFrame: Long = 0L, endFrame: Long = audio.frames.toLong()): LoudnessInfo {
        val (start, end) = FeatureRate.clampRange(audio, startFrame, endFrame)
        val frames = end - start
        if (frames <= 0) return LoudnessInfo(FeatureRate.SILENCE_DB, FeatureRate.SILENCE_DB, FloatArray(0), HOP_SEC, 0f)
        val meter = LoudnessMeter(audio.sampleRate, audio.channelCount, initialCapacitySec = frames.toDouble() / audio.sampleRate)
        meter.process(audio.channels, frames, start)
        val result = meter.result()
        val tp = TruePeak(audio.channelCount)
        tp.process(audio.channels, frames, start)
        tp.flush()
        val shortTerm = FloatArray(result.shortTermLufs.size) { clampDb(result.shortTermLufs[it]) }
        return LoudnessInfo(
            integratedLufs = clampDb(result.integratedLufs),
            truePeakDbtp = clampDb(tp.peakDbtp),
            shortTermLufs = shortTerm,
            shortTermHopSec = HOP_SEC,
            loudnessRangeLu = result.loudnessRangeLu.toFloat(),
        )
    }

    private fun clampDb(v: Double): Float =
        if (v.isNaN() || v == Double.NEGATIVE_INFINITY || v < FeatureRate.SILENCE_DB) FeatureRate.SILENCE_DB else v.toFloat()

    companion object {
        /** Grid of the short-term curve, seconds (fixed by the BS.1770 100 ms sub-block). */
        const val HOP_SEC = 0.1f
    }
}
