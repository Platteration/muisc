package dev.muisc.transitions.live

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Cues
import dev.muisc.analysis.model.GridKind
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.LoudnessInfo
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.filter.LinkwitzRiley
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TempoRelation
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.core.DeckGain
import dev.muisc.transitions.synthetic.SyntheticTrack
import dev.muisc.transitions.synthetic.SyntheticTracks
import kotlin.math.log10
import kotlin.math.max
import kotlin.random.Random

/** Ground-truth synthetic pairs and measurement helpers shared by the live tests. */
internal object LiveFixtures {
    const val SR = 44100
    val prefs = TransitionPrefs(sampleRate = SR, channels = 2)
    private val analyzer = DefaultPairAnalyzer()

    /** 120 BPM, 32 bars, 4-bar ambient intro / outro: the outgoing deck of every pair. */
    val a120: SyntheticTrack by lazy { SyntheticTracks.trackRef(SyntheticSong(bpm = 120.0, tonic = 0, bars = 32, introBars = 4, outroBars = 4, sampleRate = SR, seed = 7), prefs = prefs) }
    /** 121 BPM (ratio 1.0083): bass-swappable live. */
    val b121: SyntheticTrack by lazy { SyntheticTracks.trackRef(SyntheticSong(bpm = 121.0, tonic = 7, bars = 24, introBars = 4, outroBars = 2, sampleRate = SR, seed = 11), prefs = prefs) }
    /** 140 BPM, no intro: COLD_START. */
    val b140cold: SyntheticTrack by lazy { SyntheticTracks.trackRef(SyntheticSong(bpm = 140.0, tonic = 2, bars = 24, introBars = 0, outroBars = 2, sampleRate = SR, seed = 5), prefs = prefs) }
    /** 140 BPM with a pad intro: AMBIENT_INTRO. */
    val b140amb: SyntheticTrack by lazy { SyntheticTracks.trackRef(SyntheticSong(bpm = 140.0, tonic = 9, mode = dev.muisc.audio.synth.Mode.MINOR, bars = 24, introBars = 4, outroBars = 2, sampleRate = SR, seed = 3), prefs = prefs) }

    fun features(a: SyntheticTrack, b: SyntheticTrack): PairFeatures = analyzer.features(a.analysis, b.analysis, prefs)

    /** Frame of A's bar [bar] (beat `bar * 4`). */
    fun barFrame(t: SyntheticTrack, bar: Int): Long = t.analysis.grid.frameOfBeat((bar * t.analysis.grid.beatsPerBar).toDouble())

    /** Prefs whose target sits 3 LU under the quieter deck so the sum of two decks stays below full scale. */
    fun gainPrefs(a: SyntheticTrack, b: SyntheticTrack): TransitionPrefs =
        prefs.copy(targetLufs = minOf(a.analysis.loudness.integratedLufs, b.analysis.loudness.integratedLufs).toDouble() - 3.0)

    /** The executor's inputs for [plan] cut from the two tracks, with the deck gains applied. */
    fun inputs(plan: LivePlan, a: SyntheticTrack, b: SyntheticTrack): Pair<AudioBuffer, AudioBuffer> {
        val p = gainPrefs(a, b)
        val (aIn, bIn) = LiveOffline.sliceInputs(plan, a.audio, b.audio)
        DeckGain.applyInPlace(aIn, DeckGain.of(a.analysis, p))
        DeckGain.applyInPlace(bIn, DeckGain.of(b.analysis, p))
        return aIn to bIn
    }

    fun silence(frames: Int, channels: Int = 2): AudioBuffer = AudioBuffer.silence(SR, channels, frames)

    /** Mean power of the LR4 low band (< [hz]) of the mono mix over frames `[from, to)`. */
    fun lowBandPower(x: AudioBuffer, hz: Double, from: Int, to: Int): Double = lowBandPower(x.mono(), x.sampleRate, hz, from, to)

    fun lowBandPower(mono: FloatArray, sampleRate: Int, hz: Double, from: Int, to: Int): Double {
        val y = FloatArray(mono.size)
        LinkwitzRiley.lowPass(hz, sampleRate.toDouble(), 1).process(mono, y, mono.size)
        var e = 0.0
        for (i in from until to) e += y[i].toDouble() * y[i]
        return e / max(1, to - from)
    }

    /** Mean power of the LR4 high band (> [hz]) of the mono mix over frames `[from, to)`. */
    fun highBandPower(x: AudioBuffer, hz: Double, from: Int, to: Int): Double = highBandPower(x.mono(), x.sampleRate, hz, from, to)

    fun highBandPower(mono: FloatArray, sampleRate: Int, hz: Double, from: Int, to: Int): Double {
        val y = FloatArray(mono.size)
        LinkwitzRiley.highPass(hz, sampleRate.toDouble(), 1).process(mono, y, mono.size)
        var e = 0.0
        for (i in from until to) e += y[i].toDouble() * y[i]
        return e / max(1, to - from)
    }

    fun power(mono: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += mono[i].toDouble() * mono[i]
        return e / max(1, to - from)
    }

    fun db(power: Double): Double = 10.0 * log10(max(power, 1e-20))

    // ---- random analyses for the never-throws property ----

    fun randomAnalysis(rnd: Random, id: String): TrackAnalysis {
        val total = when (rnd.nextInt(6)) { 0 -> 0L; 1 -> 1L; 2 -> 500L; 3 -> 44100L; else -> rnd.nextLong(1L, 30_000_000L) }
        val grid = when (rnd.nextInt(6)) {
            0 -> BeatGrid.EMPTY
            1 -> BeatGrid(GridKind.FLEX, if (rnd.nextBoolean()) 0.0 else rnd.nextDouble(20.0, 300.0), longArrayOf(rnd.nextLong(0L, 100_000L)), 0, 4, rnd.nextFloat())
            2 -> BeatGrid.rigid(rnd.nextDouble(0.5, 900.0), SR, rnd.nextLong(0L, 50_000L), rnd.nextLong(0L, 400_000L), rnd.nextInt(0, 4), rnd.nextInt(1, 8), rnd.nextFloat(), if (rnd.nextBoolean()) -1 else rnd.nextInt(0, 64), rnd.nextInt(1, 17))
            3 -> {
                val n = rnd.nextInt(2, 400)
                var f = rnd.nextLong(0L, 50_000L)
                val frames = LongArray(n) { f.also { f += rnd.nextLong(1L, 60_000L) } }
                BeatGrid(GridKind.FLEX, if (rnd.nextBoolean()) 0.0 else rnd.nextDouble(20.0, 300.0), frames, rnd.nextInt(0, 4), rnd.nextInt(1, 8), rnd.nextFloat(), if (rnd.nextBoolean()) -1 else rnd.nextInt(0, 64), rnd.nextInt(1, 17))
            }
            else -> BeatGrid.rigid(rnd.nextDouble(60.0, 180.0), SR, rnd.nextLong(0L, 50_000L), max(0L, total - rnd.nextLong(0L, 100_000L)), 0, 4, 0.5f + rnd.nextFloat() / 2, 0, 8)
        }
        return TrackAnalysis(
            sourceId = id, fingerprint = "fp-$id", sampleRate = SR, totalFrames = total,
            trimStartFrame = rnd.nextLong(-100L, total + 100L), trimEndFrame = rnd.nextLong(-100L, total + 100L),
            tempo = TempoEstimate(if (rnd.nextInt(5) == 0) 0.0 else rnd.nextDouble(20.0, 300.0), rnd.nextFloat()),
            grid = grid,
            key = KeyEstimate(MusicalKey(rnd.nextInt(12), if (rnd.nextBoolean()) Mode.MAJOR else Mode.MINOR), rnd.nextFloat()),
            loudness = LoudnessInfo(if (rnd.nextInt(6) == 0) Float.NEGATIVE_INFINITY else -40f + 30f * rnd.nextFloat(), -1f),
            intro = IntroType.entries[rnd.nextInt(IntroType.entries.size)],
            outro = OutroType.entries[rnd.nextInt(OutroType.entries.size)],
            cues = Cues(mixOutBeat = rnd.nextInt(-1, 600), mixInBeat = rnd.nextInt(-1, 600), firstDownbeat = rnd.nextInt(-1, 8), lastDownbeat = rnd.nextInt(-1, 600)),
        )
    }

    fun randomRef(rnd: Random, id: String): TrackRef = TrackRef(id, AudioSourceId(id), randomAnalysis(rnd, id))

    fun randomFeatures(rnd: Random, a: TrackAnalysis, b: TrackAnalysis): PairFeatures? {
        if (rnd.nextInt(4) == 0) return null
        val ratio = when (rnd.nextInt(5)) { 0 -> Double.NaN; 1 -> Double.POSITIVE_INFINITY; 2 -> 1.0 + rnd.nextDouble(-0.02, 0.02); else -> rnd.nextDouble(0.4, 2.5) }
        return PairFeatures(
            tempoRatio = ratio, tempoRelation = TempoRelation.entries[rnd.nextInt(3)], stretchPercent = rnd.nextDouble(0.0, 60.0),
            camelotDistance = rnd.nextInt(0, 7), bestPitchShiftSemitones = rnd.nextInt(-6, 7), camelotDistanceAfterShift = rnd.nextInt(0, 7),
            loudnessDeltaLu = rnd.nextDouble(-20.0, 20.0), energyDelta = rnd.nextDouble(-1.0, 1.0), vocalClash = rnd.nextDouble(), spectralSimilarity = rnd.nextDouble(),
            outro = a.outro, intro = if (rnd.nextBoolean()) b.intro else IntroType.entries[rnd.nextInt(IntroType.entries.size)],
            outroBeatsAvailable = rnd.nextInt(0, 200), introBeatsAvailable = rnd.nextInt(0, 200),
            gridConfidenceA = rnd.nextDouble(), gridConfidenceB = rnd.nextDouble(), keyStrengthA = rnd.nextDouble(), keyStrengthB = rnd.nextDouble(),
            lowEndShareA = rnd.nextDouble(), lowEndShareB = rnd.nextDouble(),
        )
    }
}
