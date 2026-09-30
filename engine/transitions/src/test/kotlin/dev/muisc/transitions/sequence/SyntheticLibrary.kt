package dev.muisc.transitions.sequence

import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Cues
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.KeyEstimate
import dev.muisc.analysis.model.LoudnessInfo
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.Section
import dev.muisc.analysis.model.SectionLabel
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.analysis.model.TrackAnalysis
import kotlin.random.Random

/**
 * A synthetic library of analyses (no audio) shaped like a real collection: tempo clusters (hip-hop ~92, house
 * ~124, drum & bass ~173, plus a spread), random keys, mostly confident grids with some weak ones, a mix of
 * outro/intro types and cue room, several songs per artist. Deterministic for a seed.
 */
internal object SyntheticLibrary {
    const val SR = 44100

    fun items(n: Int, seed: Long, songsPerArtist: Int = 6, seconds: IntRange = 150..300): List<SequenceItem> {
        val rnd = Random(seed)
        val artists = (n / songsPerArtist).coerceAtLeast(1)
        return List(n) { i -> SequenceItem("t$i", analysis(rnd, "t$i", seconds), artist = "artist-${rnd.nextInt(artists)}") }
    }

    fun analysis(rnd: Random, id: String, seconds: IntRange = 150..300): TrackAnalysis {
        val bpm = when (rnd.nextInt(20)) {
            in 0..4 -> 86.0 + rnd.nextDouble() * 12.0
            in 5..13 -> 118.0 + rnd.nextDouble() * 12.0
            in 14..16 -> 170.0 + rnd.nextDouble() * 6.0
            else -> 70.0 + rnd.nextDouble() * 110.0
        }
        val durationSec = seconds.first + rnd.nextDouble() * (seconds.last - seconds.first)
        val total = Math.round(durationSec * SR)
        val trimStart = Math.round(rnd.nextDouble() * 0.5 * SR)
        val trimEnd = total - Math.round(rnd.nextDouble() * 0.5 * SR)
        val confidence = if (rnd.nextInt(100) < 85) 0.6f + rnd.nextFloat() * 0.38f else 0.1f + rnd.nextFloat() * 0.35f
        val grid = BeatGrid.rigid(bpm, SR, trimStart, trimEnd, confidence = confidence, phraseStartBeat = 0)
        val beats = grid.beatCount
        val nBars = (beats / 4).coerceAtLeast(1)
        val key = KeyEstimate(MusicalKey(rnd.nextInt(12), if (rnd.nextBoolean()) Mode.MAJOR else Mode.MINOR), 0.4f + rnd.nextFloat() * 0.55f)
        val lufs = -16f + rnd.nextFloat() * 9f
        val baseEnergy = 0.3f + rnd.nextFloat() * 0.6f
        val basePerc = rnd.nextFloat()
        val baseVocal = rnd.nextFloat() * 0.8f
        fun around(base: Float, spread: Float) = FloatArray(nBars) { (base + (rnd.nextFloat() - 0.5f) * spread).coerceIn(0f, 1f) }
        val bars = BarFeatures(
            energy = around(baseEnergy, 0.3f), sub = around(0.15f, 0.2f), bass = around(0.2f, 0.2f), mid = around(0.4f, 0.2f),
            high = around(0.2f, 0.2f), percussiveness = around(basePerc, 0.3f), vocalActivity = around(baseVocal, 0.4f),
        )
        val introBeats = if (rnd.nextInt(100) < 20) 4 * rnd.nextInt(1, 4) else 4 * rnd.nextInt(8, 17)
        val outroBeats = if (rnd.nextInt(100) < 20) 4 * rnd.nextInt(1, 4) else 4 * rnd.nextInt(8, 17)
        val sections = listOf(
            Section(0, introBeats, SectionLabel.INTRO, 0.4f),
            Section(introBeats, beats - outroBeats, SectionLabel.CHORUS, 0.9f, drums = 1f),
            Section(beats - outroBeats, beats, SectionLabel.OUTRO, 0.4f),
        )
        val lastDownbeat = grid.previousDownbeat((beats - 1).toDouble()).coerceAtLeast(0)
        val cues = Cues(mixOutBeat = (lastDownbeat - outroBeats).coerceAtLeast(0), mixInBeat = introBeats, firstDownbeat = 0, lastDownbeat = lastDownbeat)
        return TrackAnalysis(
            sourceId = id, fingerprint = "fp-$id", sampleRate = SR, totalFrames = total, trimStartFrame = trimStart, trimEndFrame = trimEnd,
            tempo = TempoEstimate(bpm, 0.8f), grid = grid, key = key, loudness = LoudnessInfo(lufs, -1f), bars = bars,
            ltasDb = FloatArray(31) { -50f + rnd.nextFloat() * 40f }, sections = sections,
            intro = INTROS[rnd.nextInt(INTROS.size)], outro = OUTROS[rnd.nextInt(OUTROS.size)], cues = cues,
        )
    }

    private val OUTROS = listOf(
        OutroType.BEAT_OUTRO, OutroType.BEAT_OUTRO, OutroType.BEAT_OUTRO, OutroType.FADE_OUT, OutroType.FADE_OUT,
        OutroType.HARD_STOP, OutroType.AMBIENT_OUTRO, OutroType.VOCAL_OUTRO,
    )
    private val INTROS = listOf(
        IntroType.BEAT_INTRO, IntroType.BEAT_INTRO, IntroType.BEAT_INTRO, IntroType.AMBIENT_INTRO, IntroType.VOCAL_INTRO,
        IntroType.COLD_START, IntroType.COLD_START, IntroType.UNKNOWN,
    )
}
