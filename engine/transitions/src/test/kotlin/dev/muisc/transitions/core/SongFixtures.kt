package dev.muisc.transitions.core

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Cues
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
import dev.muisc.dsp.filter.Biquad
import dev.muisc.dsp.filter.BiquadFilter
import dev.muisc.dsp.loudness.LoudnessMeter
import dev.muisc.dsp.loudness.TruePeak
import dev.muisc.dsp.stems.Stems
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.StemProvider
import dev.muisc.transitions.TempoRelation
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ground-truth fixtures for the core / strategy tests: a [SyntheticSong] rendered once plus a [TrackAnalysis] built
 * from its known tempo, key, beat times and structure (stand-in for the analyser, which is another work package).
 */
internal object SongFixtures {
    const val SR = 44100

    class Song(val song: SyntheticSong, val audio: AudioBuffer, val analysis: TrackAnalysis) {
        val ref: TrackRef get() = TrackRef(analysis.sourceId, AudioSourceId(analysis.sourceId), analysis, title = analysis.sourceId)
        val grid: BeatGrid get() = analysis.grid
    }

    private val cache = HashMap<String, Song>()

    /** Cached synthetic song with a rigid grid at frame 0 (beat k at `round(k * period)`), measured loudness and cues. */
    fun song(id: String, bpm: Double, tonic: Int = 0, mode: Mode = Mode.MAJOR, bars: Int = 32, introBars: Int = 4, outroBars: Int = 4, seed: Int = 7): Song =
        synchronized(cache) {
            cache.getOrPut("$id/$bpm/$tonic/$mode/$bars/$introBars/$outroBars/$seed") {
                val s = SyntheticSong(bpm = bpm, tonic = tonic, mode = if (mode == Mode.MAJOR) dev.muisc.audio.synth.Mode.MAJOR else dev.muisc.audio.synth.Mode.MINOR,
                    bars = bars, introBars = introBars, outroBars = outroBars, sampleRate = SR, seed = seed)
                val audio = s.render()
                val beats = bars * s.beatsPerBar
                val period = 60.0 * SR / bpm
                val grid = BeatGrid.rigid(bpm, SR, 0L, Math.round((beats - 1) * period) + 1, confidence = 1f, phraseStartBeat = 0)
                check(grid.beatCount == beats) { "rigid grid has ${grid.beatCount} beats, expected $beats" }
                val trimEnd = Math.round(s.musicEndSec * SR)
                val lufs = LoudnessMeter.integratedLufs(audio)
                val tp = TruePeak.measureDbtp(audio)
                val analysis = TrackAnalysis(
                    sourceId = id, fingerprint = "fp-$id", sampleRate = SR, totalFrames = audio.frames.toLong(),
                    trimStartFrame = 0L, trimEndFrame = trimEnd,
                    tempo = TempoEstimate(bpm, 1f), grid = grid,
                    key = KeyEstimate(MusicalKey(tonic, mode), 0.9f),
                    loudness = LoudnessInfo(lufs.toFloat(), tp.toFloat()),
                    intro = if (introBars > 0) IntroType.AMBIENT_INTRO else IntroType.BEAT_INTRO,
                    outro = if (outroBars > 0) OutroType.AMBIENT_OUTRO else OutroType.BEAT_OUTRO,
                    cues = Cues(mixOutBeat = (bars - outroBars) * s.beatsPerBar, mixInBeat = introBars * s.beatsPerBar, firstDownbeat = 0, lastDownbeat = (bars - 1) * s.beatsPerBar),
                    onsetFrames = grid.beatFrames.copyOfRange(introBars * s.beatsPerBar, (bars - outroBars) * s.beatsPerBar),
                )
                Song(s, audio, analysis)
            }
        }

    /** Pair features from the two ground truths (what DefaultPairAnalyzer would compute, simplified). */
    fun features(a: Song, b: Song): PairFeatures {
        val ratio = b.analysis.tempo.bpm / a.analysis.tempo.bpm
        val cd = a.analysis.key.camelot.distanceTo(b.analysis.key.camelot)
        return PairFeatures(
            tempoRatio = ratio, tempoRelation = TempoRelation.SAME, stretchPercent = abs(ratio - 1.0) * 100.0,
            camelotDistance = cd, bestPitchShiftSemitones = 0, camelotDistanceAfterShift = cd,
            loudnessDeltaLu = (b.analysis.loudness.integratedLufs - a.analysis.loudness.integratedLufs).toDouble(),
            energyDelta = 0.0, vocalClash = 0.0, spectralSimilarity = 0.8,
            outro = a.analysis.outro, intro = b.analysis.intro,
            outroBeatsAvailable = a.song.outroBars * 4, introBeatsAvailable = b.song.introBars * 4,
            gridConfidenceA = 1.0, gridConfidenceB = 1.0, keyStrengthA = 0.9, keyStrengthB = 0.9,
            lowEndShareA = 0.3, lowEndShareB = 0.3,
        )
    }

    /**
     * Prefs whose target loudness is 3 LU below the quieter song, so both decks are attenuated (SyntheticSong's soft
     * clipper lets its sample peak reach ~1.03, i.e. +1.4 dBTP; after the deck gain the dry material sits below 0 dBFS).
     */
    fun prefs(a: Song, b: Song): TransitionPrefs =
        TransitionPrefs(targetLufs = minOf(a.analysis.loudness.integratedLufs, b.analysis.loudness.integratedLufs).toDouble() - 3.0, sampleRate = SR, channels = 2)

    /** What the renderer hands a strategy: both windows decoded (zero-padded past the ends) with the deck gain applied. */
    fun input(plan: TransitionPlan, a: Song, b: Song, prefs: TransitionPrefs, features: PairFeatures = features(a, b)): TransitionInput {
        val aAudio = a.audio.slice(plan.aWindow.start.toInt(), plan.aWindow.end.toInt())
        val bAudio = b.audio.slice(plan.bWindow.start.toInt(), plan.bWindow.end.toInt())
        DeckGain.applyInPlace(aAudio, DeckGain.of(a.analysis, prefs))
        DeckGain.applyInPlace(bAudio, DeckGain.of(b.analysis, prefs))
        val stems = object : StemProvider {
            override fun aTail(): Stems = throw UnsupportedOperationException("no stems in fixtures")
            override fun bHead(): Stems = throw UnsupportedOperationException("no stems in fixtures")
        }
        return TransitionInput(plan, a.ref, b.ref, features, aAudio, bAudio, stems)
    }

    // ---- numeric helpers ----

    /** Signal-to-noise ratio in dB of [y] against the reference [x] (same length). */
    fun snrDb(x: FloatArray, y: FloatArray): Double {
        var s = 0.0; var e = 0.0
        for (i in x.indices) { val d = (x[i] - y[i]).toDouble(); s += x[i].toDouble() * x[i]; e += d * d }
        return if (e == 0.0) Double.POSITIVE_INFINITY else 10.0 * kotlin.math.log10(s / e)
    }

    /**
     * Kick onset detector: zero-phase (forward-backward) 4th-order low-pass at [cutoffHz] on the mono mix, 3 ms RMS
     * envelope; within ±[searchMs] of each expected frame the onset is the first frame whose envelope reaches
     * [threshold] of the window's maximum (a rise-time trigger, immune to the amplitude beating between the
     * synthetic kick's chirp and the bass that follows the attack). Returns `found - expected` in frames; the
     * detector's own small lag cancels when the same measurement is made on the source (see the deck tests).
     */
    fun kickOffsets(audio: AudioBuffer, expectedFrames: LongArray, cutoffHz: Double = 200.0, searchMs: Double = 40.0, threshold: Float = 0.3f): IntArray {
        val sr = audio.sampleRate
        val x = audio.mono().copyOf()
        fun lp(sig: FloatArray) {
            val f = BiquadFilter(1, Biquad.lowPass(cutoffHz, sr.toDouble()))
            f.process(sig, sig, sig.size)
            val f2 = BiquadFilter(1, Biquad.lowPass(cutoffHz, sr.toDouble()))
            f2.process(sig, sig, sig.size)
        }
        lp(x); x.reverse(); lp(x); x.reverse()
        val w = Math.round(0.003 * sr).toInt()
        val cum = DoubleArray(x.size + 1)
        for (i in x.indices) cum[i + 1] = cum[i] + x[i].toDouble() * x[i]
        val env = FloatArray(x.size)
        for (i in x.indices) { val a = maxOf(0, i - w + 1); env[i] = sqrt((cum[i + 1] - cum[a]) / (i + 1 - a)).toFloat() }
        val search = Math.round(searchMs / 1000.0 * sr).toInt()
        return IntArray(expectedFrames.size) { k ->
            val e = expectedFrames[k].toInt()
            val lo = maxOf(0, e - search); val hi = minOf(x.size - 1, e + search)
            var m = 0f
            for (i in lo..hi) if (env[i] > m) m = env[i]
            var found = lo
            for (i in lo..hi) if (env[i] >= threshold * m) { found = i; break }
            found - e
        }
    }

    fun median(values: IntArray): Double {
        val s = values.sortedArray()
        return if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }
}
