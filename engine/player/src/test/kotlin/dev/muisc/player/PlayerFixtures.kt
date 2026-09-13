package dev.muisc.player

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.stems.Stems
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.FrameRange
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.Params
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.StemProvider
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.strategies.CrossfadeStrategy
import dev.muisc.transitions.synthetic.SyntheticTrack
import dev.muisc.transitions.synthetic.SyntheticTrackLoader
import dev.muisc.transitions.synthetic.SyntheticTracks
import kotlin.math.abs

/** Shared ground-truth material for the player tests: short synthetic songs at the engine format. */
internal object PlayerFixtures {
    const val SR = 44100
    val basePrefs = TransitionPrefs(sampleRate = SR, channels = 2)

    fun song(bpm: Double, tonic: Int, bars: Int = 8, intro: Int = 2, outro: Int = 2, seed: Int = 7): SyntheticSong =
        SyntheticSong(bpm = bpm, tonic = tonic, bars = bars, introBars = intro, outroBars = outro, seed = seed)

    /** Cached ground-truth track (44.1 kHz stereo). */
    fun track(song: SyntheticSong, id: String, albumId: String? = null): SyntheticTrack = SyntheticTracks.trackRef(song, id, albumId, basePrefs)

    /** Target loudness 6 LU under the quietest track: every deck sits at −6 dB or lower, so even equal-power sums stay under the −1 dBTP ceiling. */
    fun prefs(vararg tracks: SyntheticTrack): TransitionPrefs =
        basePrefs.copy(targetLufs = tracks.minOf { it.analysis.loudness.integratedLufs }.toDouble() - 6.0)

    fun streams(vararg tracks: SyntheticTrack): MemoryEngineStreamFactory {
        val f = MemoryEngineStreamFactory()
        for (t in tracks) f[t.trackRef.source] = t.audio
        return f
    }

    fun features(a: SyntheticTrack, b: SyntheticTrack, prefs: TransitionPrefs): PairFeatures = DefaultPairAnalyzer().features(a.analysis, b.analysis, prefs)

    /** What the renderer hands a strategy: both windows (zero-padded) with the deck gain applied. */
    fun input(plan: TransitionPlan, a: SyntheticTrack, b: SyntheticTrack, prefs: TransitionPrefs, f: PairFeatures = features(a, b, prefs)): TransitionInput {
        val loader = SyntheticTrackLoader(listOf(a, b))
        val aAudio = loader.load(a.trackRef, plan.aWindow, prefs)
        val bAudio = loader.load(b.trackRef, plan.bWindow, prefs)
        DeckGain.applyInPlace(aAudio, DeckGain.of(a.analysis, prefs))
        DeckGain.applyInPlace(bAudio, DeckGain.of(b.analysis, prefs))
        return TransitionInput(plan, a.trackRef, b.trackRef, f, aAudio, bAudio, NoStems)
    }

    /** A real crossfade render between two synthetic tracks. */
    fun crossfadeRender(a: SyntheticTrack, b: SyntheticTrack, prefs: TransitionPrefs, fadeSec: Double = 3.0, seed: Long = 1L): RenderedTransition {
        val strategy = CrossfadeStrategy()
        val f = features(a, b, prefs)
        val plan = strategy.plan(a.analysis, b.analysis, f, Params.EMPTY.with("fadeSec", fadeSec), prefs, seed)
        return strategy.render(input(plan, a, b, prefs, f), RenderContext(prefs, seed))
    }

    fun gain(t: SyntheticTrack, prefs: TransitionPrefs): Float = DeckGain.linear(DeckGain.of(t.analysis, prefs))

    /** `audio[from, to) * gain` as a new buffer. */
    fun slice(audio: AudioBuffer, from: Long, to: Long, gain: Float = 1f): AudioBuffer {
        val out = audio.slice(from.toInt(), to.toInt())
        if (gain != 1f) for (c in 0 until out.channelCount) { val x = out[c]; for (i in x.indices) x[i] *= gain }
        return out
    }

    fun concat(vararg parts: AudioBuffer): AudioBuffer = parts.reduce { acc, b -> acc.concat(b) }

    fun scaled(audio: AudioBuffer, k: Float): AudioBuffer = audio.copy().applyGainInPlace(k)

    fun maxDiff(x: AudioBuffer, y: AudioBuffer, from: Int = 0, to: Int = minOf(x.frames, y.frames)): Float {
        var m = 0f
        for (c in 0 until x.channelCount) { val a = x[c]; val b = y[c]; for (i in from until to) { val d = abs(a[i] - b[i]); if (d > m) m = d } }
        return m
    }

    fun exactlyEqual(x: AudioBuffer, y: AudioBuffer): Boolean =
        x.frames == y.frames && x.channelCount == y.channelCount && (0 until x.channelCount).all { x[it].contentEquals(y[it]) }

    fun source(id: String): AudioSourceId = AudioSourceId(id)

    fun rangeOf(from: Long, to: Long) = FrameRange(from, to)

    private object NoStems : StemProvider {
        override fun aTail(): Stems = throw UnsupportedOperationException()
        override fun bHead(): Stems = throw UnsupportedOperationException()
    }
}
