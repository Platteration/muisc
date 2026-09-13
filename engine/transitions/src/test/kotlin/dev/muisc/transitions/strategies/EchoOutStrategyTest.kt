package dev.muisc.transitions.strategies

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.gain.Curves
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
 * `echoOut` from a 120 BPM C major track into a 126 BPM A minor one and into a half-time 63 BPM one (B is never
 * stretched, so both are fine), plus an A whose grid is not trusted (blocked).
 *
 * Musical assertions: the tail really repeats at the delay period (the envelope's autocorrelation peaks there and
 * nowhere else among the musically plausible lags), and the tail is contained — released to digital silence well
 * before the dry post-roll of B.
 */
class EchoOutStrategyTest {
    private val sr = 44100
    private val loader = SyntheticTrackLoader()
    private val strategy = EchoOutStrategy()

    private val a: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 120.0, tonic = 0, mode = Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4), "a120C")
    }
    private val b: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 24, introBars = 4, outroBars = 4), "b126Am")
    }
    private val bHalf: SyntheticTrack by lazy {
        loader.register(SyntheticSong(bpm = 63.0, tonic = 7, mode = Mode.MAJOR, bars = 24, introBars = 4, outroBars = 4), "b63G24")
    }

    private val prefs: TransitionPrefs by lazy {
        TransitionPrefs(targetLufs = minOf(a.analysis.loudness.integratedLufs, b.analysis.loudness.integratedLufs).toDouble() - 3.0, sampleRate = sr, channels = 2)
    }
    private val features: PairFeatures by lazy { DefaultPairAnalyzer().features(a.analysis, b.analysis, prefs) }
    private val stems = object : StemProvider {
        override fun aTail(): Stems = throw UnsupportedOperationException("no stems needed")
        override fun bHead(): Stems = throw UnsupportedOperationException("no stems needed")
    }

    private fun input(plan: TransitionPlan, other: SyntheticTrack = b, f: PairFeatures = features, silenceB: Boolean = false): TransitionInput {
        val aa = loader.load(a.trackRef, plan.aWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(a.analysis, prefs)) }
        val bb = loader.load(other.trackRef, plan.bWindow, prefs).also { DeckGain.applyInPlace(it, DeckGain.of(other.analysis, prefs)) }
        return TransitionInput(
            plan, a.trackRef, other.trackRef, f, aa,
            if (silenceB) AudioBuffer.silence(sr, bb.channelCount, bb.frames) else bb,
            stems,
        )
    }

    private fun rms(x: AudioBuffer, from: Int, to: Int): Double {
        var s = 0.0
        val n = (to - from).coerceAtLeast(1)
        for (c in 0 until x.channelCount) { val ch = x[c]; for (i in from until to) s += ch[i].toDouble() * ch[i] }
        return sqrt(s / (n * x.channelCount))
    }

    /** RMS envelope of the mono mix on a [hop]-frame grid. */
    private fun envelope(x: AudioBuffer, from: Int, to: Int, hop: Int): DoubleArray {
        val m = x.mono()
        val n = (to - from) / hop
        return DoubleArray(n) { k ->
            var s = 0.0
            for (i in from + k * hop until from + (k + 1) * hop) s += m[i].toDouble() * m[i]
            sqrt(s / hop)
        }
    }

    /**
     * The envelope in dB with its linear trend removed: a feedback delay's tail decays exponentially (a straight
     * line in dB), so what is left after the fit is purely the repeat structure — which is what the lag test below
     * is about. Without this every lag correlates well simply because everything is getting quieter.
     */
    private fun detrendedDb(env: DoubleArray): DoubleArray {
        val db = DoubleArray(env.size) { 20.0 * kotlin.math.log10(env[it] + 1e-12) }
        val n = db.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) { sx += i; sy += db[i]; sxx += i.toDouble() * i; sxy += i.toDouble() * db[i] }
        val slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
        val intercept = (sy - slope * sx) / n
        return DoubleArray(n) { db[it] - (intercept + slope * it) }
    }

    /** Normalised autocorrelation of [v] (mean removed) at `lag` samples of the envelope grid. */
    private fun autocorrelation(v: DoubleArray, lag: Int): Double {
        val mean = v.average()
        var num = 0.0
        var d0 = 0.0
        var d1 = 0.0
        for (i in 0 until v.size - lag) {
            val x = v[i] - mean
            val y = v[i + lag] - mean
            num += x * y; d0 += x * x; d1 += y * y
        }
        return if (d0 <= 0.0 || d1 <= 0.0) 0.0 else num / sqrt(d0 * d1)
    }

    private fun cutOut(): Int = Splice.GUARD_FRAMES + (4 * 60.0 / a.analysis.grid.bpm * sr).toInt()

    @Test
    fun applicabilityWorksAtAnyTempoAndNeedsAConfidentGridOnA() {
        val app = strategy.applicability(features, a.analysis, b.analysis, prefs)
        assertTrue(app.applicable, "${app.blockers}")
        assertTrue(app.score > 0.5, "score ${app.score}")

        val halfFeatures = DefaultPairAnalyzer().features(a.analysis, bHalf.analysis, prefs)
        val half = strategy.applicability(halfFeatures, a.analysis, bHalf.analysis, prefs)
        assertTrue(half.applicable, "half-time is fine, B is never stretched: ${half.blockers}")

        val shaky = a.analysis.copy(grid = a.analysis.grid.copy(confidence = 0.3f))
        val blocked = strategy.applicability(features.copy(gridConfidenceA = 0.3), shaky, b.analysis, prefs)
        assertTrue(!blocked.applicable && blocked.blockers.any { it.contains("confidence") }, "$blocked")

        assertEquals("echoOut", strategy.id)
        assertEquals(
            listOf("delayBeats", "feedback", "dampHz", "tailBars", "bEnterOnBeat", "wetRampBeats", "preRollBars", "wetDb"),
            strategy.params.map { it.id },
        )
    }

    @Test
    fun renderHonoursTheSpliceContractAndIsClean() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        assertEquals(0, plan.aExitOffset)
        assertTrue(plan.bEntryOffset >= Splice.GUARD_FRAMES)
        val input = input(plan)
        val rendered = strategy.render(input, RenderContext(prefs, SEED))
        assertEquals(plan.expectedOutputFrames, rendered.audio.frames)
        assertEquals(emptyList(), SpliceCheck.verify(rendered, input))
        val artifacts = ArtifactDetector(sr).analyze(rendered.audio)
        assertTrue(artifacts.clicks.isEmpty(), "clicks: ${artifacts.clicks}")
        assertTrue(rendered.audio.peak() <= 1.0f, "peak ${rendered.audio.peak()}")
        assertEquals(listOf("A cut into the echo", "B enters under the tail", "tail released"), rendered.markers.map { it.label })
        assertTrue(plan.lanes.any { it.id == "masterBeat" && it.points.size > 8 })
        // B enters one bar of A after the cut (the first downbeat strictly after it).
        val barA = (4 * 60.0 / a.analysis.grid.bpm * sr).toInt()
        assertEquals((cutOut() + barA).toDouble(), rendered.markers[1].frame.toDouble(), 2.0)

        val again = strategy.render(input(plan), RenderContext(prefs, SEED))
        for (c in 0 until rendered.audio.channelCount) assertTrue(rendered.audio[c].contentEquals(again.audio[c]), "renders are bit-identical")
    }

    /** Musical assertion 1: the repeats are spaced at exactly the delay period. */
    @Test
    fun theEchoesAreSpacedAtTheDelayPeriod() {
        for (beats in listOf("0.5", "0.75", "1.0")) {
            val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("delayBeats", beats), prefs, SEED)
            val delay = Math.round(beats.toDouble() * 60.0 / a.analysis.grid.bpm * sr).toInt()
            assertEquals(delay.toDouble(), plan.params.values["delayBeats"]!!.toDouble() * 60.0 / a.analysis.grid.bpm * sr, 1.0)
            val onlyTail = strategy.render(input(plan, silenceB = true), RenderContext(prefs, SEED)).audio
            val cut = cutOut()
            val hop = 64
            // Two delay periods after the cut the loop is purely recirculating: its envelope has period `delay`.
            val env = detrendedDb(envelope(onlyTail, cut + 2 * delay, cut + 2 * delay + 8 * delay, hop))
            val atDelay = autocorrelation(env, delay / hop)
            for (f in listOf(0.4, 0.6, 0.8, 1.3, 1.6)) {
                val other = autocorrelation(env, (delay * f).toInt() / hop)
                assertTrue(atDelay > other + 0.15, "$beats beats: correlation at the delay ($atDelay) must beat lag x$f ($other)")
            }
            assertTrue(atDelay > 0.6, "$beats beats: the tail repeats itself at the delay period ($atDelay)")
        }
    }

    /** Musical assertion 2: the tail decays and is released to silence before the dry post-roll. */
    @Test
    fun theTailDecaysAndIsContainedInsideTheSegment() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val cut = cutOut()
        val tail = plan.expectedOutputFrames - Splice.GUARD_FRAMES - cut
        val onlyTail = strategy.render(input(plan, silenceB = true), RenderContext(prefs, SEED)).audio
        val quarter = tail / 4
        val e = DoubleArray(4) { rms(onlyTail, cut + it * quarter, cut + (it + 1) * quarter) }
        assertTrue(e[0] > 1e-3, "the tail is audible right after the cut (${e[0]})")
        for (i in 1 until 4) assertTrue(e[i] < e[i - 1], "the tail decays monotonically (${e.joinToString()})")
        val reference = rms(onlyTail, cut - 8192, cut)
        assertTrue(Curves.linearToDb(e[3] / reference) < -60.0, "the tail is below -60 dB in its last quarter (${Curves.linearToDb(e[3] / reference)} dB)")
        assertEquals(0.0, rms(onlyTail, plan.expectedOutputFrames - Splice.GUARD_FRAMES, plan.expectedOutputFrames), 1e-9, "nothing of A in B's dry post-roll")

        // More feedback means a longer tail; less means a shorter one.
        val long = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("feedback", 0.85), prefs, SEED)
        val short = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY.with("feedback", 0.4), prefs, SEED)
        assertTrue(long.expectedOutputFrames > plan.expectedOutputFrames, "0.85 feedback rings longer")
        assertTrue(short.expectedOutputFrames < plan.expectedOutputFrames, "0.4 feedback rings shorter")
        val shortInput = input(short)
        assertEquals(emptyList(), SpliceCheck.verify(strategy.render(shortInput, RenderContext(prefs, SEED)), shortInput))
    }

    @Test
    fun theCutIsAPhraseStartAndTheHalfTimePairRendersCleanly() {
        val plan = strategy.plan(a.analysis, b.analysis, features, Params.EMPTY, prefs, SEED)
        val cutFrameA = plan.aExitFrame + cutOut()
        val beat = a.analysis.grid.beatAtFrame(cutFrameA)
        assertEquals(beat, Math.round(beat).toDouble(), 1e-6, "the cut is on an exact A beat ($beat)")
        assertTrue(a.analysis.grid.isPhraseStart(Math.round(beat).toInt()), "beat $beat is a phrase start of A")

        val halfFeatures = DefaultPairAnalyzer().features(a.analysis, bHalf.analysis, prefs)
        val halfPlan = strategy.plan(a.analysis, bHalf.analysis, halfFeatures, Params.EMPTY, prefs, SEED)
        val halfInput = input(halfPlan, other = bHalf, f = halfFeatures)
        val halfRender = strategy.render(halfInput, RenderContext(prefs, SEED))
        assertEquals(halfPlan.expectedOutputFrames, halfRender.audio.frames)
        assertEquals(emptyList(), SpliceCheck.verify(halfRender, halfInput))
        assertTrue(ArtifactDetector(sr).analyze(halfRender.audio).clicks.isEmpty())
        assertTrue(abs(halfRender.audio.peak()) <= 1.0f)
        // The echo is locked to A's tempo, never to B's.
        assertTrue(halfPlan.notes.any { it.contains("120") }, halfPlan.notes.toString())
    }

    private companion object { const val SEED = 5L }
}
