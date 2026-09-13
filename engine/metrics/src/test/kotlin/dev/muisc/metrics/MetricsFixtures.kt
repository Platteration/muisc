package dev.muisc.metrics

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Mode
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.stems.PseudoStemSeparator
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.LazyStemProvider
import dev.muisc.transitions.Marker
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderReport
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.StemNeed
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.strategies.CrossfadeStrategy
import dev.muisc.transitions.synthetic.SyntheticTrack
import dev.muisc.transitions.synthetic.SyntheticTrackLoader
import dev.muisc.transitions.synthetic.SyntheticTracks
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Deterministic inputs for the metrics tests: a real [CrossfadeStrategy] render of two ground-truth
 * [SyntheticTrack]s (the same path the renderer takes — load window, apply the deck gain, render), plus
 * mutation helpers that inject exactly one defect into a render so a metric's verdict can be shown to flip.
 */
internal object MetricsFixtures {
    const val SR = 44100

    private val songA = SyntheticSong(bpm = 120.0, tonic = 0, mode = Mode.MAJOR, bars = 16, introBars = 2, outroBars = 2, sampleRate = SR, seed = 11)
    private val songB = SyntheticSong(bpm = 126.0, tonic = 9, mode = Mode.MINOR, bars = 16, introBars = 2, outroBars = 2, sampleRate = SR, seed = 23)

    /** Target 3 LU below the quieter deck, so both decks are attenuated and the render has headroom. */
    val prefs: TransitionPrefs by lazy {
        val base = TransitionPrefs(sampleRate = SR, channels = 2)
        val la = SyntheticTracks.analysis(songA, base).loudness.integratedLufs
        val lb = SyntheticTracks.analysis(songB, base).loudness.integratedLufs
        base.copy(targetLufs = minOf(la, lb).toDouble() - 3.0)
    }

    val trackA: SyntheticTrack by lazy { SyntheticTracks.trackRef(songA, "A120", prefs = prefs) }
    val trackB: SyntheticTrack by lazy { SyntheticTracks.trackRef(songB, "B126", prefs = prefs) }
    val loader: SyntheticTrackLoader by lazy { SyntheticTrackLoader(listOf(trackA, trackB)) }
    val features: PairFeatures by lazy { DefaultPairAnalyzer().features(trackA.analysis, trackB.analysis, prefs) }

    /** A planned, rendered transition together with the input the strategy saw. */
    class Case(val input: TransitionInput, val rendered: RenderedTransition) {
        val plan: TransitionPlan get() = rendered.plan
        val audio: AudioBuffer get() = rendered.audio
    }

    private val cache = HashMap<String, Case>()

    /** A crossfade render of A into B (cached per parameter set). */
    fun crossfade(params: Params = Params.EMPTY, seed: Long = 3L): Case = synchronized(cache) {
        cache.getOrPut("$params/$seed") {
            val strategy = CrossfadeStrategy()
            val plan = strategy.plan(trackA.analysis, trackB.analysis, features, params, prefs, seed)
            val input = input(plan)
            Case(input, strategy.render(input, RenderContext(prefs, seed)))
        }
    }

    /** The decoded windows with the deck gains applied, exactly as `DefaultTransitionRenderer` prepares them. */
    fun input(plan: TransitionPlan): TransitionInput {
        val aAudio = loader.load(trackA.trackRef, plan.aWindow, prefs)
        val bAudio = loader.load(trackB.trackRef, plan.bWindow, prefs)
        DeckGain.applyInPlace(aAudio, DeckGain.of(trackA.analysis, prefs))
        DeckGain.applyInPlace(bAudio, DeckGain.of(trackB.analysis, prefs))
        val stems = LazyStemProvider(aAudio, bAudio, PseudoStemSeparator(), StemNeed.NONE)
        return TransitionInput(plan, trackA.trackRef, trackB.trackRef, features, aAudio, bAudio, stems)
    }

    /** [rendered] with its audio replaced by [audio] (plan, markers and report are kept). */
    fun withAudio(rendered: RenderedTransition, audio: AudioBuffer): RenderedTransition =
        RenderedTransition(rendered.plan, audio, rendered.markers, rendered.report)

    /** [rendered] with a copy of its audio mutated by [mutate]. */
    fun mutated(rendered: RenderedTransition, mutate: (AudioBuffer) -> Unit): RenderedTransition {
        val copy = rendered.audio.copy()
        mutate(copy)
        return withAudio(rendered, copy)
    }

    /**
     * A frame near [around] that is at least [guardMs] away from every source onset, so a defect injected there
     * is not excused by the "explained by a source onset" rule.
     */
    fun onsetFreeFrame(case: Case, around: Int = case.audio.frames / 2, guardMs: Double = 20.0): Int {
        val onsets = ArtifactMetrics.sourceOnsetsInOutput(case.rendered, case.input)
        val guard = Signals.msFrames(guardMs, case.audio.sampleRate)
        var f = around
        while (f < case.audio.frames - guard && ArtifactMetrics.nearAny(onsets, f, guard)) f += guard
        return f
    }

    // ---- synthetic signals ------------------------------------------------------------------------------

    /**
     * A click track: [beats] short decaying 1 kHz bursts (2 ms) at exactly `k * 60 / bpm` seconds, over silence
     * plus a very quiet noise floor (so the ODF has something to normalise against). Returns the audio and the
     * ground-truth beat times in seconds.
     */
    fun clickTrack(bpm: Double = 120.0, beats: Int = 16, sampleRate: Int = SR, offsetSec: Double = 0.25): Pair<AudioBuffer, DoubleArray> {
        val period = 60.0 / bpm
        val times = DoubleArray(beats) { offsetSec + it * period }
        val frames = Math.round((times.last() + period) * sampleRate).toInt()
        val left = FloatArray(frames)
        val burst = Math.round(0.002 * sampleRate).toInt()
        for (t in times) {
            val start = Math.round(t * sampleRate).toInt()
            for (i in 0 until burst) {
                val idx = start + i
                if (idx >= frames) break
                val env = exp(-6.0 * i / burst).toFloat()
                left[idx] += (0.8 * env * sin(2.0 * PI * 1000.0 * i / sampleRate)).toFloat()
            }
        }
        // Deterministic dither so silent stretches are not bit-zero (the ODF floor is relative).
        var state = 12345L
        for (i in 0 until frames) {
            state = state * 6364136223846793005L + 1442695040888963407L
            left[i] += ((state ushr 40).toInt() % 2001 - 1000) * 1e-7f
        }
        val right = left.copyOf()
        return AudioBuffer.stereo(sampleRate, left, right) to times
    }

    /** A minimal plan for a standalone buffer (whole buffer is the segment; no real windows). */
    fun plainPlan(frames: Int, lanes: List<dev.muisc.transitions.AutomationLane> = emptyList()): TransitionPlan =
        TransitionPlan(
            strategyId = "test",
            params = Params.EMPTY,
            aExitFrame = 0L,
            bEntryFrame = frames.toLong(),
            aWindow = FrameRange(0L, frames.toLong()),
            bWindow = FrameRange(0L, frames.toLong()),
            expectedOutputFrames = frames,
            lanes = lanes,
        )

    /** A [RenderedTransition] wrapping [audio] with a [plainPlan] (no sources: `input` is null in the metrics). */
    fun standalone(audio: AudioBuffer, lanes: List<dev.muisc.transitions.AutomationLane> = emptyList()): RenderedTransition =
        RenderedTransition(
            plainPlan(audio.frames, lanes),
            audio,
            listOf(Marker(0L, "start")),
            RenderReport(renderMillis = 0L, peak = audio.peak(), truePeakDbtp = 0f, integratedLufs = -14f),
        )
}
