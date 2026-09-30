package dev.muisc.transitions.strategies

import dev.muisc.analysis.model.Mode
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.strategies.BeatDomainTestSupport as T
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BassSwapStrategyTest {
    private val strategy = BassSwapStrategy()
    private val a120 = T.song(120.0, 0, Mode.MAJOR)
    private val b126 = T.song(126.0, 9, Mode.MINOR)
    private val pair by lazy { T.pair(a120, b126) }
    /** 8-bar overlap, swap at bar 4 (master beat 16) over one beat. */
    private val short = Params.EMPTY.with("overlapBars", 8).with("swapBar", 4)

    private fun render(p: T.Pair, params: Params, seed: Long = 1L) = run {
        val plan = strategy.plan(p.a.analysis, p.b.analysis, p.features, params, p.prefs, seed)
        val input = p.input(plan)
        Triple(plan, input, strategy.render(input, RenderContext(p.prefs, seed)))
    }

    @Test
    fun applicabilityGatesAndScores() {
        assertEquals("bassSwap", strategy.id)
        assertEquals(listOf("overlapBars", "swapBar", "swapBeats", "lowHz", "highHz", "midHighLaw", "swapLaw", "entryOffsetBars", "settleBars", "holdBars", "detectOnsets"), strategy.params.map { it.id })
        val ok = strategy.applicability(pair.features, pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(ok.applicable && ok.score > 0.6, "120 -> 126: $ok")
        assertTrue(ok.reasons.any { it.startsWith("low-end share") } && ok.reasons.any { it.startsWith("render only") }, ok.reasons.toString())
        val near = T.pair(a120, T.song(121.0, 9, Mode.MINOR))
        val live = strategy.applicability(near.features, near.a.analysis, near.b.analysis, near.prefs)
        assertTrue(live.reasons.any { it.startsWith("live-capable") }, live.reasons.toString())
        val far = T.pair(a120, T.song(150.0, 2, Mode.MAJOR))
        val blocked = strategy.applicability(far.features, far.a.analysis, far.b.analysis, far.prefs)
        assertTrue(!blocked.applicable && blocked.blockers.any { it.contains("exceeds") }, blocked.toString())
        val shaky = strategy.applicability(pair.features.copy(gridConfidenceA = 0.2), pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(!shaky.applicable)
        // No low end on one side: lower score, still applicable.
        val thin = strategy.applicability(pair.features.copy(lowEndShareB = 0.02), pair.a.analysis, pair.b.analysis, pair.prefs)
        assertTrue(thin.applicable && thin.score < ok.score - 0.1, "thin low end scores lower: ${thin.score} vs ${ok.score}")
    }

    @Test
    fun renderIsCleanDeterministicAndBeatLocked() {
        val (plan, input, rendered) = render(pair, short, seed = 5)
        T.assertContract("bassSwap", plan, input, rendered)
        T.assertBitIdentical(rendered, render(pair, short, seed = 5).third)
        assertEquals(listOf(BassSwapStrategy.MARKER_B_ENTERS, BassSwapStrategy.MARKER_BASS_SWAP, BassSwapStrategy.MARKER_SWAP_DONE, BassSwapStrategy.MARKER_A_GONE, BassSwapStrategy.MARKER_HOLD), rendered.markers.map { it.label })
        val beats = T.masterBeatFrames(rendered)
        assertEquals(beats[16], rendered.markers[1].frame, "swap at the downbeat of bar 4")
        assertEquals(beats[17], rendered.markers[2].frame, "one-beat swap")
        assertEquals(beats[32], rendered.markers[3].frame, "A gone after the 8-bar overlap")
        assertTrue(plan.lanes.map { it.id }.containsAll(listOf("masterBeat", "masterBpm", "lowA", "lowB", "midHighA", "midHighB")))
        assertEquals(1.0, rendered.report.ratioTrace.last().toDouble(), 0.002, "B at ratio 1.0 before the seam")
        // Kicks of both decks sit on the master beats. The 3-band EQ is zero-phase, so the budgets are those of
        // beatMatchedBlend's dry decks (they were 6 ms while the causal LR4 crossover delayed both decks by ~2.5 ms).
        T.assertAligned("B kicks", T.kickAlignment(rendered, 0 until 48, pair.b.audio, T.beatFrames(pair.b, 16, 80), toleranceMs = 3.0), maxMedianMs = 2.0)
        T.assertAligned("A kicks", T.kickAlignment(rendered, 0 until 32, pair.a.audio, T.beatFrames(pair.a, 16, 80), toleranceMs = 3.0), maxMedianMs = 2.0)
        // swapBeats stretches the handover.
        val slow = strategy.plan(pair.a.analysis, pair.b.analysis, pair.features, short.with("swapBeats", 4), pair.prefs, 1)
        val slowRender = strategy.render(pair.input(slow), RenderContext(pair.prefs, 1))
        val sb = T.masterBeatFrames(slowRender)
        assertEquals(sb[20], slowRender.markers[2].frame, "4-beat swap ends at beat 20")
        assertEquals(emptyList(), dev.muisc.transitions.core.SpliceCheck.verify(slowRender, pair.input(slow)))
    }

    @Test
    fun lowBandIsHandedOverAtTheSwapBeat() {
        val (_, input, _) = render(pair, short)
        val aOnly = strategy.render(T.silenced(input, silenceA = false, silenceB = true), RenderContext(pair.prefs, 1))
        val bOnly = strategy.render(T.silenced(input, silenceA = true, silenceB = false), RenderContext(pair.prefs, 1))
        val beats = T.masterBeatFrames(aOnly)
        val cutoff = 120.0 // well inside the low band: the LR4 mid band leaks < -18 dB here
        val aBefore = T.bandRmsDb(aOnly.audio, beats[12], beats[16], cutoff)
        val aAfter = T.bandRmsDb(aOnly.audio, beats[20], beats[24], cutoff)
        val bBefore = T.bandRmsDb(bOnly.audio, beats[12], beats[16], cutoff)
        val bAfter = T.bandRmsDb(bOnly.audio, beats[20], beats[24], cutoff)
        assertTrue(aAfter < aBefore - 20.0, "A's lows drop ${"%.1f".format(aBefore - aAfter)} dB after the swap (${"%.1f".format(aBefore)} -> ${"%.1f".format(aAfter)} dBFS)")
        assertTrue(bAfter > bBefore + 20.0, "B's lows rise ${"%.1f".format(bAfter - bBefore)} dB after the swap (${"%.1f".format(bBefore)} -> ${"%.1f".format(bAfter)} dBFS)")
        // Mids/highs follow the long equal-power fade instead: A's high band is still clearly present after the swap.
        val aHighBefore = T.bandRmsDb(aOnly.audio, beats[12], beats[16], 2000.0, lowPass = false)
        val aHighAfter = T.bandRmsDb(aOnly.audio, beats[20], beats[24], 2000.0, lowPass = false)
        // Equal-power over 8 bars: bar 3.5 sits at -2.2 dB and bar 5.5 at -6.6 dB, i.e. the fade alone accounts for ~4.4 dB.
        assertTrue(aHighBefore - aHighAfter in 2.5..6.5, "A's highs only follow the crossfade (${"%.1f".format(aHighBefore - aHighAfter)} dB)")
        // The sum of both contributions is the mix (linear processing): same length, close to the full render.
        val full = strategy.render(input, RenderContext(pair.prefs, 1))
        val mid = beats[18]
        var diff = 0.0; var ref = 0.0
        for (i in 0 until 4096) { val s = aOnly.audio[0][mid.toInt() + i] + bOnly.audio[0][mid.toInt() + i]; val d = s - full.audio[0][mid.toInt() + i]; diff += d * d; ref += s * s }
        assertTrue(diff < ref * 1e-4, "A + B contributions add up to the mix (rel. error ${diff / ref})")
    }

    /**
     * The 3-band EQ must not move either deck in time. With an all-pass-compensated LR4 crossover (the bands sum to
     * `AP(lowHz) AP(highHz) x`) both decks came out ~2.5 ms (≈ 111 frames) late against the phase-locked deck and
     * the dry guard regions, and the kick's attack was rotated into a ramp. With the zero-phase split the bands sum
     * to the deck itself: where a deck's band gains are equal the render IS the phase-locked deck.
     */
    @Test
    fun theEqMixKeepsBothDecksOnTheirPhaseLockedTiming() {
        val (plan, loud, _) = render(pair, short)
        // 12 dB down so the true-peak limiter never engages and a sample comparison is meaningful (the render is linear).
        fun quiet(x: dev.muisc.audio.AudioBuffer) = x.copy().also { q -> for (c in q.channels) for (i in c.indices) c[i] *= 0.25f }
        val input = dev.muisc.transitions.TransitionInput(loud.plan, loud.a, loud.b, loud.features, quiet(loud.aAudio), quiet(loud.bAudio), loud.stems)
        val ctx = RenderContext(pair.prefs, 1)
        val aOnly = strategy.render(T.silenced(input, silenceA = false, silenceB = true), ctx)
        val bOnly = strategy.render(T.silenced(input, silenceA = true, silenceB = false), ctx)
        val layout = BeatDomain.resolveLayout(plan, input.aAnalysis, input.bAnalysis, input.features, pair.prefs)
        val decks = BeatDomain.Render(input, ctx, layout)
        val dryA = decks.renderA()
        val dryB = decks.renderB()
        val g = layout.g
        val beats = T.masterBeatFrames(bOnly)
        assertEquals(0.0, aOnly.report.metrics["limited"], "no limiter in the way of a sample comparison")
        assertEquals(0.0, bOnly.report.metrics["limited"], "no limiter in the way of a sample comparison")

        // Before the swap A's low band is at unity and its mids/highs fade: A's kicks must still be where the
        // phase-locked deck puts them (cross-correlation lag of 50 ms windows at each master beat).
        val lags = IntArray(layout.aBeats / 2) { k -> lagFrames(dryA[0], aOnly.audio[0], beats[k].toInt() - g, g, 2205, 400) }
        assertTrue(lags.all { abs(it) <= 1 }, "A through the EQ lags its phase-locked deck by ${lags.toList()} frames")
        val lagsB = IntArray(layout.totalBeats - layout.aBeats) { k -> lagFrames(dryB[0], bOnly.audio[0], beats[layout.aBeats + k].toInt() - g, g, 2205, 400) }
        assertTrue(lagsB.all { abs(it) <= 1 }, "B through the EQ lags its phase-locked deck by ${lagsB.toList()} frames")

        // After A is gone every band of B is at unity: the render is B's phase-locked deck, sample for sample
        // (up to the tail's seam blend into the dry post-roll).
        val from = beats[layout.aBeats].toInt(); val to = beats[layout.totalBeats].toInt() - BeatDomain.SEAM_BLEND_FRAMES
        var worst = 0.0
        for (c in 0 until 2) for (i in from until to) worst = max(worst, abs((bOnly.audio[c][i] - dryB[c][i - g]).toDouble()))
        assertTrue(worst < 1e-4, "B after the swap differs from its phase-locked deck by up to $worst")
    }

    /** Lag (frames, positive = [y] later) that best aligns `y[at + offset ...]` with `x[at ...]` over [win] frames. */
    private fun lagFrames(x: FloatArray, y: FloatArray, at: Int, offset: Int, win: Int, maxLag: Int): Int {
        var best = Double.NEGATIVE_INFINITY; var bestLag = 0
        for (lag in -maxLag..maxLag) {
            var s = 0.0
            for (i in at until at + win) { val j = i + offset + lag; if (i in x.indices && j in y.indices) s += x[i].toDouble() * y[j] }
            if (s > best) { best = s; bestLag = lag }
        }
        return bestLag
    }
}
