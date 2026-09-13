package dev.muisc.transitions.live

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The live ladder (DESIGN §5.4): derives a real DJ move from the analyses alone and **never fails** — the last rung
 * is a plain crossfade that needs nothing. Mirrors the render ladder for the moments no render is ready (skip,
 * deadline miss, queue edit):
 *
 * 1. `bassSwap` — `features != null`, `beatMatchable`, `|tempoRatio − 1| ≤` [MAX_LIVE_TEMPO_DEVIATION] and A's
 *    grid usable: B is nudged to A's tempo by a [LiveNode.Rate] that settles to 1.0 one bar before the seam, the
 *    low band hands over on an A downbeat in the middle of the segment ([LiveNode.LowSwap]) and the mids/highs cross
 *    equal-power over the whole segment.
 * 2. `echoOut` / `filterSweep` — A has a confident grid: `echoOut` when B is cold (`COLD_START` / `BEAT_INTRO`,
 *    B's drums arrive at once so A is cut on a downbeat and rings out), else `filterSweep` (A is swept out with a
 *    resonant high-pass while B opens under it).
 * 3. `phraseCut` — A's grid is confident and B is cold but A has less than a bar left after its next downbeat: hard
 *    cut (16 ms equal-power) at A's next phrase start (or last downbeat) into B.
 * 4. `crossfade` — always: `fadeSecOverride` or [DEFAULT_LIVE_FADE_SEC], equal-power, from `aNowFrame` itself.
 *
 * **Where the segment starts.** For the grid-based rungs `aFromFrame` is the *planned point*: the first A downbeat
 * (phrase start for `phraseCut`) at or after `aNowFrame`; the player keeps playing A's body until then (at most one
 * bar, the way a DJ waits for the one) and B's mix-in downbeat lands on it at output frame 0. The crossfade starts at
 * `aNowFrame` itself ("now"). `bFromFrame` is B's `mixInBeat` frame (else its trim start), `outputFrames` is whole
 * bars of A's grid, `aToFrame = min(aFromFrame + outputFrames, A.totalFrames)`.
 *
 * Every rung has a room requirement in A (bars left after the planned point); when it is not met the ladder moves
 * on, so a track that is about to end always gets a cut or a crossfade. Deterministic: same inputs, same plan.
 * Malformed analyses (empty grids, zero tempo, frames outside the track, NaN features) are tolerated: any rung whose
 * inputs are unusable is skipped, never thrown from.
 */
class DefaultLivePlanFactory : LivePlanFactory {

    override fun plan(a: TrackRef, b: TrackRef, features: PairFeatures?, aNowFrame: Long, prefs: TransitionPrefs, fadeSecOverride: Double?): LivePlan {
        val sr = if (prefs.sampleRate > 0) prefs.sampleRate else 44100
        val aa = a.analysis
        val aTotal = if (aa.totalFrames > 0) aa.totalFrames else 0L
        val now = aNowFrame.coerceIn(0L, aTotal)
        val bFrom = bStartFrame(b.analysis)
        val grid = AGrid.of(aa, sr)
        val intro = features?.intro ?: b.analysis.intro
        val cold = intro == IntroType.COLD_START || intro == IntroType.BEAT_INTRO
        val maxOut = (MAX_SEGMENT_SEC.toLong() * sr).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        fun sane(frames: Int): Boolean = frames in 1..maxOut

        val db = grid?.nextDownbeatIndex(now) ?: 0
        val dbFrame = grid?.beatFrame(db) ?: 0L
        // A pathological grid (a last beat period of a few frames extrapolated over minutes) can fail to reach `now`
        // within the search bound; then nothing can be scheduled on it and the ladder falls through to the crossfade.
        if (grid != null && dbFrame >= now) {
            // 1. bassSwap
            if (features != null && features.beatMatchable && abs(features.tempoRatio - 1.0) <= MAX_LIVE_TEMPO_DEVIATION) {
                val wanted = (prefs.preferredOverlapBars / 4).coerceIn(MIN_BASS_SWAP_BARS, MAX_BASS_SWAP_BARS)
                val bars = minOf(wanted, grid.barsAvailable(db, wanted))
                if (bars >= MIN_BASS_SWAP_BARS) {
                    val out = grid.framesBetween(db, db + bars * grid.bpb)
                    if (sane(out)) {
                        val swapAt = grid.framesBetween(db, db + (bars / 2) * grid.bpb)
                        val settle = grid.framesBetween(db, db + (bars - 1) * grid.bpb)
                        val beat = grid.framesBetween(db, db + 1)
                        val ratio = (1.0 / features.tempoRatio).coerceIn(1.0 - MAX_LIVE_TEMPO_DEVIATION, 1.0 + MAX_LIVE_TEMPO_DEVIATION)
                        return LivePlanBuilders.bassSwap(dbFrame, aTotal, bFrom, out, swapAt, beat.coerceAtLeast(1), ratio, settle)
                    }
                }
            }

            // 2. echoOut / filterSweep
            if (cold) {
                val cut = grid.framesBetween(db, db + grid.bpb)
                if (sane(cut) && dbFrame + cut <= grid.trimEnd) {
                    val beat = grid.framesBetween(db, db + 1).coerceAtLeast(1)
                    val delay = Math.round(ECHO_DELAY_BEATS * beat).toInt().coerceAtLeast(1)
                    val decay = LivePlanBuilders.echoTailFrames(delay, ECHO_FEEDBACK)
                    var tailBars = ceil(decay.toDouble() / cut).toInt().coerceIn(1, MAX_ECHO_TAIL_BARS)
                    var tail = grid.framesBetween(db + grid.bpb, db + (1 + tailBars) * grid.bpb)
                    while (!sane(tail) && tailBars > 1) { tailBars--; tail = grid.framesBetween(db + grid.bpb, db + (1 + tailBars) * grid.bpb) }
                    if (sane(tail) && cut.toLong() + tail <= maxOut) return LivePlanBuilders.echoOut(dbFrame, aTotal, bFrom, cut, delay, tail, ECHO_FEEDBACK, ECHO_DAMP_HZ)
                }
            } else {
                val wanted = (prefs.preferredOverlapBars / 4).coerceIn(MIN_SWEEP_BARS, MAX_SWEEP_BARS)
                val bars = minOf(wanted, grid.barsAvailable(db, wanted))
                if (bars >= MIN_SWEEP_BARS) {
                    val out = grid.framesBetween(db, db + bars * grid.bpb)
                    if (sane(out)) return LivePlanBuilders.filterSweep(dbFrame, aTotal, bFrom, out, sr)
                }
            }

            // 3. phraseCut
            if (cold) {
                val phrase = grid.beatFrame(grid.nextPhraseIndex(now))
                val cutFrame = when {
                    phrase >= now && phrase <= grid.trimEnd -> phrase
                    dbFrame <= grid.trimEnd -> dbFrame
                    else -> -1L
                }
                if (cutFrame >= 0) return LivePlanBuilders.phraseCut(cutFrame, aTotal, bFrom, Math.round(CUT_MS / 1000.0 * sr).toInt().coerceAtLeast(2))
            }
        }

        // 4. crossfade
        val fadeSec = fadeSecOverride?.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(MIN_FADE_SEC, MAX_FADE_SEC) ?: DEFAULT_LIVE_FADE_SEC
        return LivePlanBuilders.crossfade(now, aTotal, bFrom, Math.round(fadeSec * sr).toInt().coerceAtLeast(2))
    }

    /** B's mix-in cue frame when its grid can place it, else its trim start; always inside the track. */
    private fun bStartFrame(b: TrackAnalysis): Long {
        val total = if (b.totalFrames > 0) b.totalFrames else 0L
        val last = (total - 1).coerceAtLeast(0L)
        val g = b.grid
        val fromCue = if (!g.isEmpty && b.cues.mixInBeat >= 0) g.frameOfBeat(b.cues.mixInBeat.toDouble()) else -1L
        val start = if (fromCue >= 0) fromCue else b.trimStartFrame
        return start.coerceIn(0L, last)
    }

    /**
     * A's grid, validated for live use: at least two beats, confidence ≥ [MIN_GRID_CONFIDENCE], a tempo between 40
     * and 300 BPM both by `bpm` and by the beat array, and a positive track length. Beat indices may exceed the tracked
     * beats (the grid extrapolates with its last period); [trimEnd] bounds where A still has music.
     */
    private class AGrid(val grid: BeatGrid, val trimEnd: Long) {
        val bpb: Int = grid.beatsPerBar

        fun beatFrame(index: Int): Long = grid.frameOfBeat(index.toDouble())

        /** Frames from beat [from] to beat [to] (> 0 for to > from on a strictly increasing grid). */
        fun framesBetween(from: Int, to: Int): Int = (beatFrame(to) - beatFrame(from)).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        /**
         * Index of the first downbeat whose frame is ≥ [frame]. `beatAtFrame` inverts `frameOfBeat`, so the search
         * normally lands immediately; the bounded walk only covers rounding and grids whose extrapolation period
         * differs from the tracked one. An index that never reaches [frame] within [SEARCH_STEPS] is returned as is
         * and rejected by the caller.
         */
        fun nextDownbeatIndex(frame: Long): Int {
            var i = grid.nextDownbeat(grid.beatAtFrame(frame))
            var steps = 0
            while (beatFrame(i) < frame && steps++ < SEARCH_STEPS) i += bpb
            return i
        }

        /** Index of the first phrase start whose frame is ≥ [frame] (same bounded search as [nextDownbeatIndex]). */
        fun nextPhraseIndex(frame: Long): Int {
            val phraseBeats = (if (grid.phraseStartBeat < 0) 1 else grid.phraseBars.coerceAtLeast(1)) * bpb
            var i = grid.nextPhraseStart(grid.beatAtFrame(frame))
            var steps = 0
            while (beatFrame(i) < frame && steps++ < SEARCH_STEPS) i += phraseBeats
            return i
        }

        /** Whole bars from beat [fromBeat] that end at or before [trimEnd], at most [max]. */
        fun barsAvailable(fromBeat: Int, max: Int): Int {
            var k = 0
            while (k < max && beatFrame(fromBeat + (k + 1) * bpb) <= trimEnd) k++
            return k
        }

        companion object {
            /** Bound of the beat-index searches (a bar of the longest tolerated phrase, with room to spare). */
            const val SEARCH_STEPS = 256

            fun of(a: TrackAnalysis, sampleRate: Int): AGrid? {
                val g = a.grid
                if (g.beatCount < 2 || g.confidence.isNaN() || g.confidence < MIN_GRID_CONFIDENCE || g.beatsPerBar < 1) return null
                val minPeriod = sampleRate * 60.0 / MAX_BPM
                val maxPeriod = sampleRate * 60.0 / MIN_BPM
                val p = g.periodFrames(sampleRate)
                if (!p.isFinite() || p < minPeriod || p > maxPeriod) return null
                val avg = (g.beatFrames.last() - g.beatFrames.first()).toDouble() / (g.beatCount - 1)
                if (!avg.isFinite() || avg < minPeriod || avg > maxPeriod) return null
                if (a.totalFrames <= 0) return null
                val trimEnd = if (a.trimEndFrame in 1..a.totalFrames) a.trimEndFrame else a.totalFrames
                return AGrid(g, trimEnd)
            }
        }
    }

    companion object {
        /** Live crossfade length when the caller gives no override (DESIGN §7.4: "1.5 s default"). */
        const val DEFAULT_LIVE_FADE_SEC = 1.5
        const val MIN_FADE_SEC = 0.05
        const val MAX_FADE_SEC = 30.0
        /** Largest |tempoRatio − 1| the cheap live resampler is allowed to bridge (DESIGN §0: "sub-2 %"). */
        const val MAX_LIVE_TEMPO_DEVIATION = 0.02
        /** Grid confidence below which A's grid is not trusted for a live move. */
        const val MIN_GRID_CONFIDENCE = 0.5f
        const val MIN_BPM = 40.0
        const val MAX_BPM = 300.0
        /** Bars a bass swap spans: `preferredOverlapBars / 4` clamped to this range, shrunk to what A has left. */
        const val MIN_BASS_SWAP_BARS = 2
        const val MAX_BASS_SWAP_BARS = 8
        const val MIN_SWEEP_BARS = 2
        const val MAX_SWEEP_BARS = 8
        /** Echo-out: dotted-eighth delay, feedback and loop damping (DESIGN §4.9 defaults, feedback at the calm end). */
        const val ECHO_DELAY_BEATS = 0.75
        const val ECHO_FEEDBACK = 0.6f
        const val ECHO_DAMP_HZ = 4000.0
        const val MAX_ECHO_TAIL_BARS = 4
        /** Length of the equal-power gains of a phrase cut. */
        const val CUT_MS = 16.0
        /** Longest live segment a grid-based rung may produce (guards against absurd grids); longer → next rung. */
        const val MAX_SEGMENT_SEC = 120
    }
}
