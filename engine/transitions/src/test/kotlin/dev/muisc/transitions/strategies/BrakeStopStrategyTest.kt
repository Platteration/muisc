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
 * `brakeStop` from a 120 BPM C major track into a cold-starting 126 BPM A minor one, with three counter-cases:
 * an ambient intro, a low energy preference and a ballad (no percussion), all of which must block.
 *
 * Musical assertions: the pitch of A's bass really falls monotonically (measured as the zero-crossing rate of the
 * low band, which is what "the platter is slowing down" means), the gap is exactly as long as asked and digitally
 * silent, and B starts bit-exactly dry on the other side of it.
 */
class BrakeStopStrategyTest {
    private val sr = 44100
    private val loader = SyntheticTrackLoader()
    private val strategy = BrakeStopStrategy()

    private val a: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 120.0, tonic = 0, mode = Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4), "a120C")
    }
    private val b: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 24, introBars = 0, outroBars = 4), "bCold126Am")
    }
    /** Same B but with an ambient intro: a stop into a pad is a mistake, not a move. */
    private val bAmbient: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 24, introBars = 4, outroBars = 4), "b126Am")
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

    /** 4th-order low-pass of the mono mix: the bass note whose pitch the brake is dragging down. */
    private fun lowBand(x: AudioBuffer, hz: Double = 400.0): FloatArray {
        val m = x.mono()
        repeat(2) { BiquadFilter(1, Biquad.lowPass(hz, sr.toDouble())).process(m, m, m.size) }
        return m
    }

    /** Zero crossings per second in `[from, to)`, ignoring crossings of near-silent material. */
    private fun zeroCrossingRate(x: FloatArray, from: Int, to: Int, floor: Float = 3e-3f): Double {
        var crossings = 0
        var prev = 0f
        for (i in from until to) {
            val v = x[i]
            if (abs(v) < floor) continue
            if (prev != 0f && (v > 0f) != (prev > 0f)) crossings++
            prev = v
        }
        return crossings * sr.toDouble() / (to - from)
    }

    private fun brakeFrames(sec: Double = 1.5): Int = Math.round(sec * sr).toInt()

    @Test
    fun applicabilityNeedsAColdStartEnergyAndPercussion() {
        val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
        assertTrue(app.applicable, "${app.blockers}")
        assertTrue(app.score > 0.6, "score ${app.score}")
        assertTrue(app.reasons.any { it.contains("cooldown") }, "the cooldown is advertised: ${app.reasons}")

        val ambientFeatures = DefaultPairAnalyzer().features(a.analysis, bAmbient.analysis, prefs)
        val ambient = strategy.applicability(ambientFeatures, a.analysis, bAmbient.analysis, prefs)
        assertTrue(!ambient.applicable && ambient.blockers.any { it.contains("AMBIENT_INTRO") }, "$ambient")

        val safe = strategy.applicability(features, a.analysis, b.analysis, prefs.copy(energy = 0.3))
        assertTrue(!safe.applicable && safe.blockers.any { it.contains("energy preference") }, "$safe")

        val ballad = a.analysis.copy(bars = a.analysis.bars.copy(percussiveness = FloatArray(a.analysis.bars.barCount) { 0.1f }))
        val quiet = strategy.applicability(features, ballad, b.analysis, prefs)
        assertTrue(!quiet.applicable && quiet.blockers.any { it.contains("percussiveness") }, "$quiet")

        assertEquals("brakeStop", strategy.id)
        assertEquals(listOf("brakeSec", "curve", "exponent", "gapMs", "lpfMinHz", "spinback", "cooldown"), strategy.params.map { it.id })
    }

    @Test
    fun renderHonoursTheSpliceContractAndIsClean() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val g = Splice.GUARD_FRAMES
        val gap = Math.round(0.12 * sr).toInt()
        assertEquals(g + brakeFrames() + gap + 220 + g, plan.expectedOutputFrames)
        assertEquals(0, plan.aExitOffset, "no spinback: the window starts at the segment")
        assertTrue(plan.bEntryOffset >= g)
        val input = input(plan)
        val rendered = strategy.render(input, RenderContext(prefs, SEED))
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames)
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input))
        val artifacts = ArtifactDetector(sr).analyze(rendered.audio)
        assertTrue(artifacts.clicks.isEmpty(), "clicks: ${artifacts.clicks}")
        assertTrue(rendered.audio.peak() <= 1.0f, "peak ${rendered.audio.peak()}")
        assertEquals(listOf("brake", "stopped", "B enters cold"), rendered.markers.map { it.label })
        assertTrue(plan.lanes.any { it.id == "platterSpeed" } && plan.lanes.any { it.id == "lpfA" })
        assertTrue(plan.lanes.any { it.id == "beatsA" && it.points.isNotEmpty() })
        // The brake starts on a downbeat of A.
        val beat = a.analysis.grid.beatAtFrame(plan.aExitFrame + g)
        assertEquals(beat, Math.round(beat).toDouble(), 1e-6)
        assertTrue(a.analysis.grid.isDownbeat(Math.round(beat).toInt()), "the brake starts on a downbeat ($beat)")

        val again = strategy.render(input(plan), RenderContext(prefs, SEED))
        for (c in 0 until rendered.audio.channelCount) assertTrue(rendered.audio[c].contentEquals(again.audio[c]), "renders are bit-identical")
    }

    /** Musical assertion 1: the pitch of A's bass falls monotonically through the brake. */
    @Test
    fun thePitchFallsMonotonicallyThroughTheBrake() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val g = Splice.GUARD_FRAMES
        val brake = brakeFrames()
        val onlyA = strategy.render(input(plan, silenceB = true), RenderContext(prefs, SEED)).audio
        val low = lowBand(onlyA)
        val windows = 6
        val step = brake / windows
        val zcr = DoubleArray(windows) { zeroCrossingRate(low, g + it * step, g + (it + 1) * step) }
        val before = zeroCrossingRate(low, 0, g) // the dry pre-roll: A at full speed
        assertTrue(zcr[0] <= before * 1.1, "the brake starts at normal speed (${zcr[0]} against $before)")
        for (i in 1 until windows) {
            assertTrue(zcr[i] < zcr[i - 1], "the pitch must keep falling: window $i (${zcr.joinToString { "%.1f".format(it) }})")
        }
        assertTrue(zcr.last() < zcr.first() * 0.25, "it ends far below where it started (${zcr.first()} -> ${zcr.last()})")
        // The platter really stops: the last frames of the brake are digital silence.
        assertEquals(0.0, rms(low, g + brake - 64, g + brake), 1e-6)

        // A longer brake takes proportionally longer to get there.
        val slow = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("brakeSec", 3.0), prefs, SEED)
        assertEquals(brakeFrames(3.0).toDouble(), (slow.expectedOutputFrames - plan.expectedOutputFrames + brake).toDouble(), 2.0)
    }

    /** Musical assertion 2: the gap is exactly as long as asked and digitally silent, and B starts dry after it. */
    @Test
    fun theGapIsSilentAndBStartsDryAfterIt() {
        for (gapMs in doubleArrayOf(0.0, 120.0, 400.0)) {
            val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("gapMs", gapMs), prefs, SEED)
            val g = Splice.GUARD_FRAMES
            val brake = brakeFrames()
            val gap = Math.round(gapMs / 1000.0 * sr).toInt()
            assertEquals(g + brake + gap + 220 + g, plan.expectedOutputFrames, "gapMs $gapMs")
            val input = input(plan)
            val rendered = strategy.render(input, RenderContext(prefs, SEED))
            assertEquals(emptyList(), SpliceCheck.verify(rendered, input), "gapMs $gapMs")
            val enter = g + brake + gap
            if (gap > 0) {
                val mono = rendered.audio.mono()
                assertEquals(0.0, rms(mono, g + brake, enter), 1e-9, "the gap is digital silence (gapMs $gapMs)")
            }
            // B is the input, sample for sample, from the end of its 5 ms declick.
            for (c in 0 until rendered.audio.channelCount) {
                for (i in 220 until input.bAudio.frames) {
                    assertEquals(input.bAudio[c][i], rendered.audio[c][enter + i], 1e-6f, "B is dry (gapMs $gapMs, channel $c, frame $i)")
                }
            }
        }
    }

    @Test
    fun spinbackReversesTheRecordAndStillHonoursTheContract() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("spinback", true), prefs, SEED)
        val g = Splice.GUARD_FRAMES
        val brake = brakeFrames()
        assertTrue(plan.aExitOffset > 0, "a spinback decodes material from before the brake point (${plan.aExitOffset})")
        assertEquals(brake, plan.aExitOffset, "one brake length of reverse material")
        val input = input(plan)
        val rendered = strategy.render(input, RenderContext(prefs, SEED))
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames)
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input))
        assertTrue(ArtifactDetector(sr).analyze(rendered.audio).clicks.isEmpty())
        assertTrue(rendered.audio.peak() <= 1.0f)
        assertEquals("spinback", rendered.markers.first().label)

        // Backwards: the first moments of the spinback replay what was just heard, mirrored in time.
        val forward = rendered.audio.mono()
        var best = 0.0
        val n = 2000
        for (i in 0 until n) best += abs(forward[g + i] - forward[g - 1 - i]).toDouble()
        val reference = rms(forward, g - n, g)
        assertTrue(best / n < reference, "the reversed material mirrors the pre-roll (mean |diff| ${best / n} against rms $reference)")
        assertEquals(0.0, rms(forward, g + brake - 64, g + brake), 1e-6, "a spinback also ends in silence")
    }

    private companion object { const val SEED = 77L }
}
