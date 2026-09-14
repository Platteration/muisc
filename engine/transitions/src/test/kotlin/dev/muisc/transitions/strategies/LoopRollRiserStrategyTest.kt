package dev.muisc.transitions.strategies

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.filter.Biquad
import dev.muisc.dsp.filter.BiquadFilter
import dev.muisc.dsp.qa.ArtifactDetector
import dev.muisc.dsp.stems.Stems
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.StemProvider
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.core.Splice
import dev.muisc.transitions.core.SpliceCheck
import dev.muisc.transitions.synthetic.SyntheticTrack
import dev.muisc.transitions.synthetic.SyntheticTrackLoader
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `loopRollRiser` from a 120 BPM C major track into a cold-starting 126 BPM A minor one, with the low-energy
 * preference (blocked) as the counter-case.
 *
 * Musical assertions: the roll really is a halving stutter that fills exactly one bar, the riser's spectrum
 * climbs across it (isolated by rendering the same plan at two riser levels and subtracting), and B's downbeat
 * lands exactly on the planned drop frame.
 */
class LoopRollRiserStrategyTest {
    private val sr = 44100
    private val loader = SyntheticTrackLoader()
    private val strategy = LoopRollRiserStrategy()

    private val a: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 120.0, tonic = 0, mode = Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4), "a120C")
    }
    private val b: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 24, introBars = 0, outroBars = 4), "bCold126Am")
    }

    private val prefs: TransitionPrefs by lazy {
        TransitionPrefs(
            targetLufs = minOf(a.analysis.loudness.integratedLufs, b.analysis.loudness.integratedLufs).toDouble() - 3.0,
            energy = 0.8, sampleRate = sr, channels = 2,
        )
    }
    private val features: PairFeatures by lazy { DefaultPairAnalyzer().features(a.analysis, b.analysis, prefs) }
    private val stems = object : StemProvider {
        override fun aTail(): Stems = throw UnsupportedOperationException("no stems needed")
        override fun bHead(): Stems = throw UnsupportedOperationException("no stems needed")
    }

    private fun input(plan: TransitionPlan, silenceB: Boolean = false): TransitionInput {
        val aa = loader.load(a.trackRef, plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
        val bb = loader.load(b.trackRef, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(b.analysis, prefs)) }
        return TransitionInput(
            plan, a.trackRef, b.trackRef, features, aa,
            if (silenceB) AudioBuffer.silence(sr, bb.channelCount, bb.frames) else bb,
            stems,
        )
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from).coerceAtLeast(1))
    }

    private fun band(x: FloatArray, highPassHz: Double): FloatArray {
        val m = x.copyOf()
        repeat(2) { BiquadFilter(1, Biquad.highPass(highPassHz, sr.toDouble())).process(m, m, m.size) }
        return m
    }

    private fun barFrames(): Int = (4 * 60.0 / a.analysis.grid.bpm * sr).toInt()

    @Test
    fun applicabilityWantsEnergyAndADropToLandOn() {
        val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
        assertTrue(app.applicable, "${app.blockers}")
        assertTrue(app.score > 0.6, "score ${app.score}")
        assertTrue(app.reasons.any { it.contains("COLD_START") }, app.reasons.toString())

        val safe = prefs.copy(energy = 0.2)
        val blocked = strategy.applicability(features, a.analysis, b.analysis, safe)
        assertTrue(!blocked.applicable && blocked.blockers.any { it.contains("energy preference") }, "$blocked")

        val shaky = a.analysis.copy(grid = a.analysis.grid.copy(confidence = 0.3f))
        val offGrid = strategy.applicability(features.copy(gridConfidenceA = 0.3), shaky, b.analysis, prefs)
        assertTrue(!offGrid.applicable, "an unconfident grid must block: $offGrid")

        assertEquals("loopRollRiser", strategy.id)
        assertEquals(
            listOf("rollBars", "rollSteps", "hpfToHz", "riserDb", "riserCurve", "riserToHz", "gapBeats", "useDropBeat"),
            strategy.params.map { it.id },
        )
    }

    @Test
    fun renderHonoursTheSpliceContractAndIsClean() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val g = Splice.GUARD_FRAMES
        assertEquals(g + barFrames() + 220 + g, plan.expectedOutputFrames, "pre-roll + one bar of roll + declick + post-roll")
        assertEquals(0, plan.aExitOffset)
        assertTrue(plan.bEntryOffset >= g)
        val input = input(plan)
        val rendered = strategy.render(input, RenderContext(prefs, SEED))
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames)
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input))
        val artifacts = ArtifactDetector(sr).analyze(rendered.audio)
        assertTrue(artifacts.clicks.isEmpty(), "clicks: ${artifacts.clicks}")
        assertTrue(rendered.audio.peak() <= 1.0f, "peak ${rendered.audio.peak()}")
        assertEquals("roll starts", rendered.markers.first().label)
        assertEquals("drop: B enters", rendered.markers.last().label)
        assertTrue(plan.lanes.any { it.id == "beatsA" && it.points.size > 4 })

        val again = strategy.render(input(plan), RenderContext(prefs, SEED))
        for (c in 0 until rendered.audio.channelCount) assertTrue(rendered.audio[c].contentEquals(again.audio[c]), "renders are bit-identical")
    }

    /** Musical assertion 1: the roll is a halving stutter that fills the bar exactly. */
    @Test
    fun theRollIsAHalvingStutterThatFillsTheBar() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val lane = plan.lanes.first { it.id == "rollStep" }
        assertEquals(5, lane.points.size, "five loops by default (1/2, 1/4, 1/8, 1/16, 1/16 of the bar)")
        val lengths = lane.points.map { Math.round(it.value / 1000.0 * sr).toInt() }
        val bar = barFrames()
        assertEquals(bar.toDouble(), lengths.sum().toDouble(), 2.0, "the loops fill the bar")
        for (i in 1 until 4) assertEquals((lengths[i - 1] / 2).toDouble(), lengths[i].toDouble(), 1.0, "loop $i is half of loop ${i - 1}")
        assertEquals(lengths[3].toDouble(), lengths[4].toDouble(), 1.0, "the shortest loop is played twice so the sum is exact")
        // Each loop starts where the previous one ended, and the first one starts at the end of the dry pre-roll.
        var at = Splice.GUARD_FRAMES.toDouble() / sr
        for ((i, pt) in lane.points.withIndex()) {
            assertEquals(at, pt.outputSec, 1e-6, "loop $i starts where loop ${i - 1} ended")
            at += lengths[i].toDouble() / sr
        }
        // Every loop replays the same slice of A: the roll's second loop is the head of its first.
        val onlyA = strategy.render(input(plan, silenceB = true), RenderContext(prefs, SEED)).audio
        val g = Splice.GUARD_FRAMES
        val second = g + lengths[0]
        val n = lengths[1] / 2
        var diff = 0.0
        for (c in 0 until onlyA.channelCount) for (i in 0 until n) {
            diff += abs(onlyA[c][g + i] - onlyA[c][second + i]).toDouble()
        }
        // Not bit-identical (the high-pass keeps climbing across the roll) but unmistakably the same material.
        val ref = rms(onlyA.mono(), g, g + n)
        assertTrue(diff / (n * onlyA.channelCount) < ref, "the second loop repeats the first (mean |diff| ${diff / (n * onlyA.channelCount)} vs rms $ref)")

        // Two bars of roll really are twice as long.
        val two = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("rollBars", 2), prefs, SEED)
        assertEquals((plan.expectedOutputFrames + bar).toDouble(), two.expectedOutputFrames.toDouble(), 2.0)
        assertEquals(3, strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("rollSteps", 3), prefs, SEED)
            .lanes.first { it.id == "rollStep" }.points.size)
    }

    /** Musical assertion 2: the riser's spectrum climbs across the roll. */
    @Test
    fun theRiserClimbsAcrossTheRoll() {
        val loudPlan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("riserDb", -8.0), prefs, SEED)
        val quietPlan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("riserDb", -30.0), prefs, SEED)
        val loud = strategy.render(input(loudPlan, silenceB = true), RenderContext(prefs, SEED)).audio
        val quiet = strategy.render(input(quietPlan, silenceB = true), RenderContext(prefs, SEED)).audio
        // Same seed, same plan geometry: the difference of the two renders IS the riser (scaled by the level delta).
        val riser = FloatArray(loud.frames) { i -> loud[0][i] - quiet[0][i] }
        val g = Splice.GUARD_FRAMES
        val roll = barFrames()
        val third = roll / 3
        val total = DoubleArray(3) { rms(riser, g + it * third, g + (it + 1) * third) }
        assertTrue(total[0] > 1e-5, "the riser is actually there (${total[0]})")
        assertTrue(total[1] > total[0] && total[2] > total[1], "the riser builds (${total.joinToString()})")

        val high = band(riser, 2000.0)
        val bright = DoubleArray(3) { rms(high, g + it * third, g + (it + 1) * third) / total[it] }
        assertTrue(bright[1] > bright[0] && bright[2] > bright[1], "its spectrum climbs: share above 2 kHz ${bright.joinToString()}")
        assertTrue(bright[2] > bright[0] * 3.0, "by a lot (${bright[0]} -> ${bright[2]})")
        // Nothing of the riser survives into B's dry post-roll.
        assertEquals(0.0, rms(riser, loud.frames - g, loud.frames), 1e-9)
    }

    /** Musical assertion 3: B's downbeat lands exactly on the planned drop frame, after the optional gap. */
    @Test
    fun bDropsOnThePlannedFrameAfterTheGap() {
        val gapBeat = Math.round(60.0 / a.analysis.grid.bpm * sr).toInt()
        for (gap in doubleArrayOf(0.0, 1.0)) {
            val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("gapBeats", gap), prefs, SEED)
            val g = Splice.GUARD_FRAMES
            val drop = g + barFrames() + (if (gap > 0) gapBeat else 0)
            assertEquals((drop + 220 + g).toDouble(), plan.expectedOutputFrames.toDouble(), 2.0, "gap $gap")
            val input = input(plan)
            val rendered = strategy.render(input, RenderContext(prefs, SEED))
            assertEquals(emptyList(), SpliceCheck.verify(rendered, input), "gap $gap")
            // B's window starts on one of B's downbeats (its drop cue) and that frame plays at the drop.
            val bBeat = b.analysis.grid.beatAtFrame(plan.bWindow.start)
            assertEquals(bBeat, Math.round(bBeat).toDouble(), 1e-6)
            assertTrue(b.analysis.grid.isDownbeat(Math.round(bBeat).toInt()), "B drops on one of its downbeats")
            for (c in 0 until rendered.audio.channelCount) {
                for (i in 220 until input.bAudio.frames) {
                    assertEquals(input.bAudio[c][i], rendered.audio[c][drop + i], 1e-6f, "B is dry from the drop (gap $gap, channel $c, frame $i)")
                }
            }
            // The gap really is silence: nothing of A, nothing of the riser, nothing of B.
            if (gap > 0) {
                val onlyA = strategy.render(input(plan, silenceB = true), RenderContext(prefs, SEED)).audio
                assertEquals(0.0, rms(onlyA.mono(), g + barFrames() + 256, drop), 1e-9, "silence in the gap")
            }
        }
    }

    private companion object { const val SEED = 31L }
}
