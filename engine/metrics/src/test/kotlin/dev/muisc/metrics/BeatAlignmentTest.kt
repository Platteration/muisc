package dev.muisc.metrics

import dev.muisc.transitions.AutomationLane
import dev.muisc.transitions.LanePoint
import dev.muisc.transitions.core.MasterGrid
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `beatAlignmentMs` / `beatAlignmentP90Ms` on a click track with a known beat list: the render's attacks (analytic
 * envelope on 1 ms blocks, parabolic sub-block interpolation) must land on the master beats to well under a
 * millisecond, and a deliberately dragged render must be graded WARN / FAIL by the 5 ms / 12 ms thresholds. On real
 * beat-domain renders, one deck deliberately played late must still FAIL.
 */
class BeatAlignmentTest {

    private val sr = MetricsFixtures.SR

    @Test
    fun onsetsOfAClickTrackLandOnItsBeats() {
        val (audio, beats) = MetricsFixtures.clickTrack(bpm = 120.0, beats = 16)
        val rendered = MetricsFixtures.standalone(audio)
        val metrics = ArtifactMetrics.beatAlignment(rendered, beats)
        assertEquals(2, metrics.size)
        val median = metrics[0]
        val worst = metrics[1]
        assertEquals(ArtifactMetrics.BEAT_ALIGNMENT_MS, median.id)
        assertEquals(ArtifactMetrics.BEAT_ALIGNMENT_P90_MS, worst.id)
        assertTrue(median.value < 2.0, "median error ${median.value} ms")
        assertTrue(worst.value < 5.0, "max error ${worst.value} ms")
        assertEquals(Verdict.PASS, median.verdict)
        assertEquals(Verdict.PASS, worst.verdict)
        // Every beat is articulated: the attack function has an attack within 1 ms of each of the 16 clicks.
        val attacks = Signals.attacks(audio)
        assertEquals(beats.size, beats.count { abs(attacks.strongestNear(it, 0.05) - it) < 0.001 })
    }

    @Test
    fun draggedBeatsAreGradedByTheThresholds() {
        val (audio, beats) = MetricsFixtures.clickTrack(bpm = 120.0, beats = 16)
        val rendered = MetricsFixtures.standalone(audio)
        fun shifted(ms: Double) = DoubleArray(beats.size) { beats[it] + ms / 1000.0 }

        val warn = ArtifactMetrics.beatAlignment(rendered, shifted(8.0))
        assertEquals(Verdict.WARN, warn[0].verdict, "8 ms off: ${warn[0]}")
        assertEquals(Verdict.WARN, warn[1].verdict)
        assertEquals(8.0, warn[0].value, 1.0)

        val fail = ArtifactMetrics.beatAlignment(rendered, shifted(15.0))
        assertEquals(Verdict.FAIL, fail[1].verdict, "15 ms off: ${fail[1]}")
        assertEquals(Verdict.WARN, fail[0].verdict, "the median only ever warns")
    }

    @Test
    fun beatsComeFromThePlanLaneOrTheMasterGrid() {
        val (audio, beats) = MetricsFixtures.clickTrack(bpm = 120.0, beats = 8)
        val lane = AutomationLane(ArtifactMetrics.MASTER_BEAT_LANE, beats.mapIndexed { i, t -> LanePoint(t, i.toDouble()) })
        val rendered = MetricsFixtures.standalone(audio, listOf(lane))
        val fromLane = ArtifactMetrics.evaluate(rendered, null)
        val median = assertNotNull(fromLane.metric(ArtifactMetrics.BEAT_ALIGNMENT_MS), fromLane.summary())
        assertTrue(median.value < 2.0, "$median")
        assertEquals(beats.toList(), ArtifactMetrics.planMasterBeats(rendered)!!.toList())

        // The same beats expressed as a MasterGrid (frames from its own start) give the same answer.
        val grid = MasterGrid.constant(sr, 120.0, beats.size)
        val shifted = MetricsFixtures.standalone(audio.slice(Math.round(beats[0] * sr).toInt(), audio.frames))
        val fromGrid = ArtifactMetrics.evaluate(shifted, null, grid)
        assertTrue(fromGrid.value(ArtifactMetrics.BEAT_ALIGNMENT_MS)!! < 2.0, fromGrid.summary())
        assertEquals(0.0, ArtifactMetrics.beatsOf(grid)[0])
        assertEquals(0.5, ArtifactMetrics.beatsOf(grid)[1], 1e-6)
    }

    /**
     * `bassSwap` renders the same decks on the same kind of master grid as `beatMatchedBlend`, only through a 3-band
     * EQ. Its median used to read 7.2 ms on this pair (14.3 ms on `t120C → t126Am`) against 0.87 ms for the blend:
     * the all-pass-compensated LR4 crossover delayed both decks by ~2.5 ms and rotated each kick's attack into a
     * ramp, so the render's strongest rise landed on a later half-cycle of the kick. The EQ is zero-phase now.
     */
    @Test
    fun bassSwapIsAsWellAlignedAsTheDryBlend() {
        val f = MetricsFixtures
        val strategy = dev.muisc.transitions.strategies.BassSwapStrategy()
        val plan = strategy.plan(f.trackA.analysis, f.trackB.analysis, f.features, dev.muisc.transitions.Params.EMPTY, f.prefs, 3L)
        val rendered = strategy.render(f.input(plan), dev.muisc.transitions.RenderContext(f.prefs, 3L))
        val median = assertNotNull(ArtifactMetrics.evaluate(rendered, f.input(plan)).metric(ArtifactMetrics.BEAT_ALIGNMENT_MS))
        val blend = assertNotNull(ArtifactMetrics.evaluate(f.beatMatchedBlend().rendered, f.beatMatchedBlend().input).metric(ArtifactMetrics.BEAT_ALIGNMENT_MS))
        assertTrue(median.value < 2.0, "bassSwap median $median (beatMatchedBlend: $blend)")
        assertEquals(Verdict.PASS, median.verdict)
    }

    /**
     * Beats with no attack near them are not counted. The previous metric paired each beat with the nearest onset up
     * to 100 ms away, so four extra master beats 70 ms after four of the clicks read as 70 ms errors (maximum 70,
     * FAIL); now nothing within 50 ms of them rises, so they are skipped and the verdicts are those of the clicks.
     */
    @Test
    fun beatsTheRenderDoesNotArticulateAreNotCounted() {
        val (audio, beats) = MetricsFixtures.clickTrack(bpm = 120.0, beats = 16)
        val rendered = MetricsFixtures.standalone(audio)
        val withGaps = (beats.toList() + listOf(1, 5, 9, 13).map { beats[it] + 0.070 }).sorted().toDoubleArray()
        val clean = ArtifactMetrics.beatAlignment(rendered, beats)
        val gapped = ArtifactMetrics.beatAlignment(rendered, withGaps)
        assertEquals(clean.map { it.value }, gapped.map { it.value })
        assertEquals(Verdict.PASS, gapped[1].verdict, "${gapped[1]}")
    }

    /**
     * The metric must not go blind: a real render whose B deck, or whose A deck, is played 15, 20 or 40 ms late (the
     * render's audio only; the plan, the grids and the sources are untouched) FAILs and reads the offset it was given
     * (90th percentile within 1.5 ms of it); the same render with both decks on time passes. Checked on
     * `beatMatchedBlend`, `bassSwap` and the beat-matched recipes `smooth-blend`, `long-glide` and `club-bass-swap`.
     *
     * When only the strongest full-band attack per beat was scored, A 20 ms late read 11.05 ms (WARN) on smooth-blend
     * and 9.66 ms (WARN) on long-glide, and A 40 ms late read 17.95-37.52 ms: on a beat where both decks play, the
     * on-time deck's attack comes first, and the late deck's attack, a rise over the on-time deck's still-loud kick, is
     * the weaker one.
     */
    @Test
    fun oneDeckPlayedLateStillFails() {
        val f = MetricsFixtures
        val recipes = dev.muisc.transitions.recipe.RecipeCatalog.registry(
            dev.muisc.transitions.DefaultStrategyRegistry.default(), dev.muisc.transitions.recipe.RecipeLibrary(userDir = null),
        )
        val strategies = listOf(dev.muisc.transitions.strategies.BeatMatchedBlendStrategy(), dev.muisc.transitions.strategies.BassSwapStrategy()) +
            listOf("smooth-blend", "long-glide", "club-bass-swap").map { assertNotNull(recipes.strategy("recipe:$it"), it) }
        val problems = ArrayList<String>()
        for (strategy in strategies) {
            val plan = strategy.plan(f.trackA.analysis, f.trackB.analysis, f.features, dev.muisc.transitions.Params.EMPTY, f.prefs, 3L)
            val input = f.input(plan)
            assertTrue(plan.lanes.any { it.id == ArtifactMetrics.MASTER_BEAT_LANE }, "${strategy.id} is a beat-domain render")
            fun only(keepA: Boolean): dev.muisc.transitions.RenderedTransition {
                val silent = { x: dev.muisc.audio.AudioBuffer -> dev.muisc.audio.AudioBuffer.silence(x.sampleRate, x.channelCount, x.frames) }
                val aAudio = if (keepA) input.aAudio else silent(input.aAudio)
                val bAudio = if (keepA) silent(input.bAudio) else input.bAudio
                val stems = dev.muisc.transitions.LazyStemProvider(aAudio, bAudio, dev.muisc.dsp.stems.PseudoStemSeparator(), dev.muisc.transitions.StemNeed.NONE)
                val one = dev.muisc.transitions.TransitionInput(plan, input.a, input.b, input.features, aAudio, bAudio, stems)
                return strategy.render(one, dev.muisc.transitions.RenderContext(f.prefs, 3L))
            }
            val aOnly = only(keepA = true)
            val bOnly = only(keepA = false)
            fun mix(lateA: Double, lateB: Double): dev.muisc.transitions.RenderedTransition {
                val da = Math.round(lateA / 1000.0 * sr).toInt(); val db = Math.round(lateB / 1000.0 * sr).toInt()
                return MetricsFixtures.mutated(aOnly) { buf ->
                    for (c in 0 until buf.channelCount) {
                        val a = aOnly.audio[c]; val b = bOnly.audio[c]; val y = buf[c]
                        for (i in y.indices) y[i] = (if (i - da >= 0) a[i - da] else 0f) + (if (i - db >= 0) b[i - db] else 0f)
                    }
                }
            }
            fun p90(r: dev.muisc.transitions.RenderedTransition) = assertNotNull(ArtifactMetrics.evaluate(r, input).metric(ArtifactMetrics.BEAT_ALIGNMENT_P90_MS))
            val onTime = p90(mix(0.0, 0.0))
            if (onTime.verdict != Verdict.PASS) problems += "${strategy.id} on time: $onTime"
            for (late in listOf(15.0, 20.0, 40.0)) {
                for ((deck, r) in listOf("B" to mix(0.0, late), "A" to mix(late, 0.0))) {
                    val m = p90(r)
                    if (m.verdict != Verdict.FAIL || abs(m.value - late) > 1.5) problems += "${strategy.id} with $deck $late ms late: $m"
                }
            }
        }
        // Every case is checked before failing, so the message lists all of them.
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * The high band counts only when it is off on many beats ([ArtifactMetrics.BEAT_HIGH_BAND_MIN_SHARE], at least
     * [ArtifactMetrics.BEAT_HIGH_BAND_MIN_BEATS]). Over eight master beats of a real `beatMatchedBlend` render (so the
     * 90th percentile is the maximum), one hat-like burst 25 ms after one beat, which no source has, changes nothing;
     * the same burst after two beats is read as what it is.
     */
    @Test
    fun anOddHighBandAttackOnOneBeatIsNotCounted() {
        val case = MetricsFixtures.beatMatchedBlend()
        val lane = assertNotNull(ArtifactMetrics.planMasterBeats(case.rendered))
        val beats = lane.copyOfRange(lane.size / 2 - 4, lane.size / 2 + 4)
        fun withBursts(vararg at: Int) = MetricsFixtures.mutated(case.rendered) { buf ->
            val rnd = java.util.Random(5)
            for (k in at) {
                val start = Math.round((beats[k] + 0.025) * sr).toInt()
                var prev = 0f
                for (i in 0 until Math.round(0.006 * sr).toInt()) {
                    val white = (rnd.nextFloat() * 2f - 1f)
                    val v = (white - prev) * 0.25f * kotlin.math.exp(-i / (0.0015 * sr)).toFloat() // first difference: high-band noise
                    prev = white
                    for (c in 0 until buf.channelCount) buf[c][start + i] += v
                }
            }
        }
        val clean = ArtifactMetrics.beatAlignment(case.rendered, beats, case.input)
        assertEquals(Verdict.PASS, clean[1].verdict, "${clean[1]}")
        val one = ArtifactMetrics.beatAlignment(withBursts(3), beats, case.input)
        // The burst nudges the full band's sub-millisecond interpolation (by 4e-8 ms), nothing more.
        for (i in clean.indices) assertEquals(clean[i].value, one[i].value, 0.01, "one beat of eight: ${one[i]}")
        val two = ArtifactMetrics.beatAlignment(withBursts(3, 5), beats, case.input)
        assertEquals(25.0, two[1].value, 2.0, "two beats of eight: ${two[1]}")
        assertEquals(Verdict.FAIL, two[1].verdict)
    }

    @Test
    fun noBeatsOrNoOnsetsMeansNoMetric() {
        val (audio, beats) = MetricsFixtures.clickTrack(beats = 4)
        val rendered = MetricsFixtures.standalone(audio)
        assertEquals(emptyList(), ArtifactMetrics.beatAlignment(rendered, null))
        assertEquals(emptyList(), ArtifactMetrics.beatAlignment(rendered, DoubleArray(0)))
        // Beats far outside the segment match nothing and are not reported as a huge error.
        assertEquals(emptyList(), ArtifactMetrics.beatAlignment(rendered, DoubleArray(beats.size) { 1000.0 + it }))
    }
}
