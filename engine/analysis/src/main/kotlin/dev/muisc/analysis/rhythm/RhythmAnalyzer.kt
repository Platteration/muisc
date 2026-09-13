package dev.muisc.analysis.rhythm

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.GridKind
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.resample.Resampler

/**
 * Everything the rhythm analysis knows about a track. Frame positions in [grid] are at the caller's engine
 * sample rate; times are seconds from the start of the *whole* buffer (not the trimmed region).
 *
 * [odf] starts at track time 0 with [odfHopSec] seconds per frame (frames before the trim start are zero, the
 * array ends at the trim end), so `odf[k]` describes the onset strength at `k * odfHopSec` seconds — the
 * time-stretcher's transient protection can index it directly.
 */
class RhythmResult(
    val tempo: TempoEstimate,
    val grid: BeatGrid,
    val onsetTimesSec: DoubleArray,
    val odf: FloatArray,
    val odfHopSec: Double,
    /** 0..1 confidence of the chosen downbeat phase. */
    val downbeatConfidence: Float = 0f,
    /** RMS residual of the tracked beats against a constant-tempo line, ms (0 when nothing was tracked). */
    val gridResidualMs: Double = 0.0,
    /** Number of beats tracked before grid fitting. */
    val trackedBeats: Int = 0,
) {
    companion object {
        fun empty(odf: FloatArray = FloatArray(0), hopSec: Double = 256.0 / RhythmAnalyzer.ANALYSIS_SAMPLE_RATE): RhythmResult =
            RhythmResult(TempoEstimate(0.0, 0f), BeatGrid.EMPTY, DoubleArray(0), odf, hopSec)
    }
}

/**
 * Facade of the rhythm pipeline: mono downmix → resample to [ANALYSIS_SAMPLE_RATE] ([Resampler]) →
 * [OnsetDetector] → [TempoEstimator] → [BeatTracker] on [OnsetFeatures.trackingOdf] (broadband flux with the
 * kick / bass band emphasised), transient re-alignment → [GridFitter] → [DownbeatEstimator] → [BeatGrid] in
 * engine-rate frames.
 *
 * Only `[trimStartFrame, trimEndFrame)` is analysed; all outputs are expressed in whole-buffer coordinates.
 * Short or silent input never throws: the result carries [BeatGrid.EMPTY] with confidence 0 (the ODF and onset
 * list are still filled when at least one STFT frame exists).
 */
class RhythmAnalyzer(
    val onsetDetector: OnsetDetector = OnsetDetector(),
    val tempoEstimator: TempoEstimator = TempoEstimator(),
    val beatTracker: BeatTracker = BeatTracker(),
    val gridFitter: GridFitter = GridFitter(),
    val downbeatEstimator: DownbeatEstimator = DownbeatEstimator(),
    val resampler: Resampler = Resampler(),
    /** Whether tracked beats are re-aligned to waveform transients (sub-frame accuracy on percussive beats). */
    val alignTransients: Boolean = true,
    /** Minimum trimmed duration (seconds) for tempo / beat estimation. */
    val minDurationSec: Double = 3.0,
) {
    init { require(onsetDetector.sampleRate == ANALYSIS_SAMPLE_RATE) { "OnsetDetector must run at $ANALYSIS_SAMPLE_RATE Hz" } }

    fun analyze(audio: AudioBuffer, trimStartFrame: Long = 0L, trimEndFrame: Long = audio.frames.toLong()): RhythmResult {
        val engineRate = audio.sampleRate
        val start = trimStartFrame.coerceIn(0L, audio.frames.toLong()).toInt()
        val end = trimEndFrame.coerceIn(start.toLong(), audio.frames.toLong()).toInt()
        val hopSec = onsetDetector.hopSeconds
        if (end - start <= 0) return RhythmResult.empty(FloatArray(0), hopSec)

        // 1. mono + resample to the analysis rate; sample n of `mono` is at (start + n * engineRate / 22050) engine frames.
        val monoFull = audio.mono()
        val region = if (start == 0 && end == audio.frames) monoFull else monoFull.copyOfRange(start, end)
        val mono = if (engineRate == ANALYSIS_SAMPLE_RATE) region else resampler.resample(region, engineRate, ANALYSIS_SAMPLE_RATE)
        val startSec = start.toDouble() / engineRate
        val regionSec = mono.size.toDouble() / ANALYSIS_SAMPLE_RATE

        // 2. onsets
        val features = onsetDetector.analyze(mono)
        val leadFrames = Math.round(startSec / hopSec).toInt()
        val odfOut = FloatArray(leadFrames + features.frames)
        System.arraycopy(features.odf, 0, odfOut, leadFrames, features.frames)
        val onsets = features.onsetTimes().also { for (i in it.indices) it[i] += startSec }
        if (regionSec < minDurationSec) return RhythmResult(TempoEstimate(0.0, 0f), BeatGrid.EMPTY, onsets, odfOut, hopSec)

        // 3. tempo
        val tempo = tempoEstimator.estimate(features)
        if (tempo.periodFrames <= 0.0 || tempo.bpm <= 0.0) {
            return RhythmResult(tempo.estimate, BeatGrid.EMPTY, onsets, odfOut, hopSec)
        }

        // 4. beats
        val periods = tempo.tempogram.periodCurve(features.frames, tempo.periodFrames)
        val trackingOdf = features.trackingOdf()
        val beatFrames = beatTracker.track(trackingOdf, periods)
        if (beatFrames.size < 2) return RhythmResult(tempo.estimate, BeatGrid.EMPTY, onsets, odfOut, hopSec)
        var beatTimes = DoubleArray(beatFrames.size) { features.frameTime(beatFrames[it]) }
        if (alignTransients) beatTimes = TransientAligner(mono, ANALYSIS_SAMPLE_RATE).alignAll(beatTimes)

        // 5. grid
        val fit = gridFitter.fit(beatTimes, trackingOdf, hopSec, tempo.confidence, regionSec)
        if (fit.beatCount < 2 || fit.bpm <= 0.0) return RhythmResult(tempo.estimate, BeatGrid.EMPTY, onsets, odfOut, hopSec)

        // 6. downbeats (on the final beat positions, analysis time base)
        val downbeat = downbeatEstimator.estimate(fit.beatTimesSec, features)

        // 7. engine-rate grid
        val grid = when (fit.kind) {
            GridKind.RIGID -> BeatGrid.rigid(
                bpm = fit.bpm,
                sampleRate = engineRate,
                firstBeatFrame = Math.round((startSec + fit.beatTimesSec[0]) * engineRate),
                endFrame = end.toLong(),
                downbeatPhase = downbeat.phase,
                beatsPerBar = downbeatEstimator.beatsPerBar,
                confidence = fit.confidence,
                phraseStartBeat = downbeat.phraseStartBeat,
                phraseBars = downbeatEstimator.phraseBars,
            )
            GridKind.FLEX -> {
                val frames = LongArray(fit.beatCount) { Math.round((startSec + fit.beatTimesSec[it]) * engineRate) }
                for (i in 1 until frames.size) if (frames[i] <= frames[i - 1]) frames[i] = frames[i - 1] + 1
                BeatGrid(GridKind.FLEX, fit.bpm, frames, downbeat.phase, downbeatEstimator.beatsPerBar, fit.confidence, downbeat.phraseStartBeat, downbeatEstimator.phraseBars)
            }
        }
        // The least-squares tempo of a RIGID grid is more precise than the autocorrelation peak.
        val tempoEstimate = if (fit.kind == GridKind.RIGID) tempo.estimate.copy(bpm = fit.bpm, alternates = rescaleAlternates(tempo.estimate, fit.bpm)) else tempo.estimate
        return RhythmResult(tempoEstimate, grid, onsets, odfOut, hopSec, downbeat.confidence, fit.residualMs, beatFrames.size)
    }

    /** Keeps the half / double alternates consistent with a refined main tempo. */
    private fun rescaleAlternates(t: TempoEstimate, bpm: Double): List<dev.muisc.analysis.model.TempoCandidate> =
        t.alternates.map { c ->
            val r = c.bpm / t.bpm
            if (kotlin.math.abs(r - 0.5) < 1e-9 || kotlin.math.abs(r - 2.0) < 1e-9) c.copy(bpm = bpm * r) else c
        }

    companion object {
        /** All rhythm analysis runs on a mono downmix at this rate. */
        const val ANALYSIS_SAMPLE_RATE = 22050
    }
}
