package dev.muisc.analysis.features

import dev.muisc.audio.AudioBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the audible programme starts and ends, plus how it ends. All frames are engine-rate frames of the
 * analysed buffer.
 *
 * - `[trimStartFrame, trimEndFrame)` is the non-silent range; for an entirely silent buffer both are 0 and
 *   [silent] is true.
 * - [hardStop]: the level of the final [SilenceTrimmer.hardStopWindowSec] before the trim end is within
 *   [SilenceTrimmer.hardStopToleranceDb] of the preceding [SilenceTrimmer.hardStopReferenceSec] — the music
 *   stops abruptly. [endLevelDeltaDb] is that level difference (≤ 0 typically; [FeatureRate.SILENCE_DB] when
 *   not measurable).
 * - [fadeOut]: the short-term level over the last [SilenceTrimmer.fadeWindowSec] falls steadily
 *   ([fadeSlopeDbPerSec] below the slope threshold, [fadeRangeDb] above the range threshold);
 *   [fadeStartFrame] is where the descent begins (-1 when there is no fade).
 * A track can in principle satisfy both flags (a fade cut off abruptly); consumers should test [fadeOut] first.
 */
data class SilenceInfo(
    val trimStartFrame: Long,
    val trimEndFrame: Long,
    val silent: Boolean,
    val hardStop: Boolean,
    val fadeOut: Boolean,
    val fadeStartFrame: Long = -1L,
    val endLevelDeltaDb: Float = FeatureRate.SILENCE_DB,
    val fadeSlopeDbPerSec: Float = 0f,
    val fadeRangeDb: Float = 0f,
) {
    val trimmedFrames: Long get() = trimEndFrame - trimStartFrame
}

/**
 * Leading / trailing silence trimming and ending classification.
 *
 * **Trimming.** The mono mix is cut into [windowSec] (10 ms) windows and each window's RMS in dBFS is
 * compared with a Schmitt trigger: a window is *sound* once the level reaches [thresholdDb] (-60 dBFS);
 * sound is *left* when the level drops below [thresholdDb] again, and after such a drop it must reach
 * [reenterDb] (-54 dBFS) to re-enter (hysteresis against the boundary chatter of a decaying tail). Runs of
 * sound shorter than [minSoundSec] (50 ms — isolated clicks, dropout noise) are ignored. The trim range spans
 * the first to the last remaining run; positions are window-aligned, i.e. within [windowSec] of the true
 * boundary.
 *
 * **Hard stop.** Mean power of the last [hardStopWindowSec] (100 ms) before the trim end versus the
 * *median* of the 100 ms block powers of the preceding [hardStopReferenceSec] (2 s — the median is the
 * typical level, unaffected by a single loud hit or note attack in the reference): a difference of at most
 * [hardStopToleranceDb] (8 dB) means the music was still at its running level when it ended. 8 dB rather
 * than a tighter 6 dB so that a cut with a short (≤ 100 ms) note release still counts — a sustained pad with
 * an 80 ms release measures about -6 dB — while a ring-out or fade is 10–20 dB down and never qualifies.
 *
 * **Fade-out.** A short-term level curve (RMS over [levelWindowSec] = 400 ms windows every [levelHopSec] =
 * 100 ms, in dBFS, floored at -80 dB) over the last [fadeWindowSec] (12 s) before the trim end is fitted with
 * a least-squares line; slope below [fadeSlopeThresholdDbPerSec] (-1.5 dB/s) and a max–min range above
 * [fadeRangeThresholdDb] (10 dB) classify a fade. The fade start is the centre of the last level window
 * that is still within 3 dB of the curve's maximum (everything after it is the descent).
 *
 * Works at the engine rate (no resampling); never throws on empty / short / silent input.
 */
class SilenceTrimmer(
    val windowSec: Double = 0.010,
    val thresholdDb: Float = -60f,
    val reenterDb: Float = -54f,
    val minSoundSec: Double = 0.050,
    val hardStopWindowSec: Double = 0.1,
    val hardStopReferenceSec: Double = 2.0,
    val hardStopToleranceDb: Float = 8f,
    val fadeWindowSec: Double = 12.0,
    val fadeSlopeThresholdDbPerSec: Float = -1.5f,
    val fadeRangeThresholdDb: Float = 10f,
    val levelWindowSec: Double = 0.4,
    val levelHopSec: Double = 0.1,
) {
    init {
        require(windowSec > 0 && minSoundSec >= 0 && hardStopWindowSec > 0 && hardStopReferenceSec > 0)
        require(fadeWindowSec > 0 && levelWindowSec > 0 && levelHopSec > 0)
        require(reenterDb >= thresholdDb) { "reenterDb must not be below thresholdDb" }
    }

    fun analyze(audio: AudioBuffer): SilenceInfo {
        val sr = audio.sampleRate
        val n = audio.frames
        if (n == 0) return SilenceInfo(0, 0, silent = true, hardStop = false, fadeOut = false)
        val mono = audio.mono()
        val win = Math.round(windowSec * sr).toInt().coerceAtLeast(1)
        val windows = (n + win - 1) / win
        val levelDb = FloatArray(windows)
        for (w in 0 until windows) {
            val s = w * win
            val e = min(n, s + win)
            var acc = 0.0
            for (i in s until e) { val v = mono[i].toDouble(); acc += v * v }
            levelDb[w] = FeatureRate.amplitudeDb(sqrt(acc / (e - s)))
        }
        // Schmitt trigger.
        val sound = BooleanArray(windows)
        var inSound = false
        var droppedOnce = false
        for (w in 0 until windows) {
            val l = levelDb[w]
            if (inSound) {
                if (l < thresholdDb) { inSound = false; droppedOnce = true }
            } else {
                val enter = if (droppedOnce) reenterDb else thresholdDb
                if (l >= enter) inSound = true
            }
            sound[w] = inSound
        }
        // Runs of sound, ignoring blips.
        val minRun = Math.round(minSoundSec / windowSec).toInt().coerceAtLeast(1)
        var firstStart = -1
        var lastEnd = -1
        var w = 0
        while (w < windows) {
            if (!sound[w]) { w++; continue }
            var e = w
            while (e < windows && sound[e]) e++
            if (e - w >= minRun) {
                if (firstStart < 0) firstStart = w
                lastEnd = e
            }
            w = e
        }
        if (firstStart < 0) return SilenceInfo(0, 0, silent = true, hardStop = false, fadeOut = false)
        val trimStart = firstStart.toLong() * win
        val trimEnd = min(n.toLong(), lastEnd.toLong() * win)

        // Hard stop.
        val hsWin = Math.round(hardStopWindowSec * sr).toInt()
        val hsRef = Math.round(hardStopReferenceSec * sr).toInt()
        val lastStart = max(trimStart, trimEnd - hsWin).toInt()
        val refStart = max(trimStart, lastStart.toLong() - hsRef).toInt()
        var endDelta = FeatureRate.SILENCE_DB
        var hardStop = false
        if (lastStart - refStart >= hsWin && trimEnd - lastStart > 0) {
            val eLast = meanSquare(mono, lastStart, trimEnd.toInt())
            // Reference = median of the 100 ms block energies of the preceding 2 s (robust to single hits).
            val blocks = (lastStart - refStart) / hsWin
            val ref = DoubleArray(blocks) { meanSquare(mono, lastStart - (it + 1) * hsWin, lastStart - it * hsWin) }
            java.util.Arrays.sort(ref)
            val eRef = ref[blocks / 2]
            if (eLast > 0.0 && eRef > 0.0) {
                endDelta = FeatureRate.powerDb(eLast / eRef)
                hardStop = endDelta >= -hardStopToleranceDb
            }
        }

        // Fade-out.
        val lvWin = Math.round(levelWindowSec * sr).toInt().coerceAtLeast(1)
        val lvHop = Math.round(levelHopSec * sr).toInt().coerceAtLeast(1)
        val fadeStart = max(trimStart, trimEnd - Math.round(fadeWindowSec * sr)).toInt()
        val points = if (trimEnd - fadeStart >= lvWin) ((trimEnd - fadeStart - lvWin) / lvHop).toInt() + 1 else 0
        var fadeOut = false
        var fadeStartFrame = -1L
        var slope = 0f
        var range = 0f
        if (points >= 5) {
            val curve = DoubleArray(points)
            var maxIdx = 0
            for (k in 0 until points) {
                val s = fadeStart + k * lvHop
                curve[k] = FeatureRate.amplitudeDb(sqrt(meanSquare(mono, s, s + lvWin))).coerceAtLeast(LEVEL_FLOOR_DB).toDouble()
                if (curve[k] > curve[maxIdx]) maxIdx = k
            }
            // Least-squares line over time (seconds).
            val tMean = (points - 1) * 0.5 * levelHopSec
            var lMean = 0.0
            for (v in curve) lMean += v
            lMean /= points
            var num = 0.0; var den = 0.0
            for (k in 0 until points) {
                val dt = k * levelHopSec - tMean
                num += dt * (curve[k] - lMean); den += dt * dt
            }
            slope = (if (den > 0.0) num / den else 0.0).toFloat()
            var mn = curve[0]; var mx = curve[0]
            for (v in curve) { if (v < mn) mn = v; if (v > mx) mx = v }
            range = (mx - mn).toFloat()
            fadeOut = slope < fadeSlopeThresholdDbPerSec && range > fadeRangeThresholdDb
            if (fadeOut) {
                // The descent begins after the last level window that is still within 3 dB of the maximum
                // (beat-level ripple before that point is ignored); report that window's centre.
                var k = points - 1
                while (k > maxIdx && curve[k] < mx - FADE_START_DROP_DB) k--
                fadeStartFrame = (fadeStart + k * lvHop + lvWin / 2).toLong()
            }
        }
        return SilenceInfo(trimStart, trimEnd, false, hardStop, fadeOut, fadeStartFrame, endDelta, slope, range)
    }

    private fun meanSquare(x: FloatArray, from: Int, to: Int): Double {
        val a = from.coerceAtLeast(0); val b = to.coerceAtMost(x.size)
        if (b <= a) return 0.0
        var acc = 0.0
        for (i in a until b) { val v = x[i].toDouble(); acc += v * v }
        return acc / (b - a)
    }

    companion object {
        /** Floor of the fade level curve (dBFS). */
        const val LEVEL_FLOOR_DB = -80f
        /** A fade "starts" where the level first drops this far below the window maximum. */
        const val FADE_START_DROP_DB = 3.0
    }
}
