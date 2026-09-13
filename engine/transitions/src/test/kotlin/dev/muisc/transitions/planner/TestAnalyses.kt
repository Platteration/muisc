package dev.muisc.transitions.planner

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
import dev.muisc.audio.AudioSourceId
import dev.muisc.transitions.TrackRef
import kotlin.random.Random

/** Random but internally consistent [TrackAnalysis] objects for planner property tests (no audio involved). */
internal object TestAnalyses {
    const val SR = 44100

    /** A plausible analysis drawn from [rnd]: 60–180 BPM, 60–300 s, rigid grid, random key/level/edges/cues. */
    fun random(rnd: Random, id: String, sampleRate: Int = SR): TrackAnalysis {
        val bpm = 60.0 + rnd.nextDouble() * 120.0
        val durationSec = 60.0 + rnd.nextDouble() * 240.0
        val total = Math.round(durationSec * sampleRate)
        val lead = Math.round(rnd.nextDouble() * 2.0 * sampleRate)
        val trail = Math.round(rnd.nextDouble() * 2.0 * sampleRate)
        val trimStart = lead
        val trimEnd = total - trail
        val confidence = if (rnd.nextBoolean()) 0.5f + rnd.nextFloat() * 0.5f else rnd.nextFloat() * 0.5f
        val grid = BeatGrid.rigid(bpm, sampleRate, trimStart, trimEnd, confidence = confidence, phraseStartBeat = 0)
        val beats = grid.beatCount
        val bars = beats / 4
        val key = KeyEstimate(MusicalKey(rnd.nextInt(12), if (rnd.nextBoolean()) Mode.MAJOR else Mode.MINOR), 0.3f + rnd.nextFloat() * 0.7f)
        val outroKey = if (rnd.nextInt(4) == 0) KeyEstimate(MusicalKey(rnd.nextInt(12), Mode.MINOR), 0.3f + rnd.nextFloat() * 0.7f) else null
        val introKey = if (rnd.nextInt(4) == 0) KeyEstimate(MusicalKey(rnd.nextInt(12), Mode.MAJOR), 0.3f + rnd.nextFloat() * 0.7f) else null
        val lufs = -20f + rnd.nextFloat() * 14f
        val n = bars.coerceAtLeast(1)
        fun arr(lo: Float = 0f, hi: Float = 1f) = FloatArray(n) { lo + rnd.nextFloat() * (hi - lo) }
        val barFeatures = BarFeatures(energy = arr(0.2f), sub = arr(0f, 0.3f), bass = arr(0f, 0.3f), mid = arr(0.2f, 0.6f), high = arr(0f, 0.3f), percussiveness = arr(), vocalActivity = arr())
        val introBeats = (rnd.nextInt(0, 9) * 4).coerceAtMost(beats / 2)
        val outroBeats = (rnd.nextInt(0, 9) * 4).coerceAtMost(beats / 2)
        val sections = ArrayList<Section>()
        if (introBeats > 0) sections += Section(0, introBeats, SectionLabel.INTRO, 0.4f)
        sections += Section(introBeats, beats - outroBeats, SectionLabel.CHORUS, 0.9f, drums = 1f)
        if (outroBeats > 0) sections += Section(beats - outroBeats, beats, SectionLabel.OUTRO, 0.4f)
        val lastDownbeat = if (beats > 0) grid.previousDownbeat((beats - 1).toDouble()).coerceAtLeast(0) else -1
        val mixOut = if (rnd.nextInt(5) == 0) -1 else grid.previousPhraseStart((beats - outroBeats - 1).coerceAtLeast(0).toDouble()).coerceAtLeast(0)
        val mixIn = if (rnd.nextInt(5) == 0) -1 else introBeats
        val cues = Cues(mixOutBeat = mixOut, mixInBeat = mixIn, firstDownbeat = 0, lastDownbeat = lastDownbeat, dropBeat = if (rnd.nextBoolean()) introBeats else -1)
        val ltas = FloatArray(31) { -60f + rnd.nextFloat() * 60f }
        return TrackAnalysis(
            sourceId = id, fingerprint = "fp-$id-${rnd.nextLong()}", sampleRate = sampleRate, totalFrames = total,
            trimStartFrame = trimStart, trimEndFrame = trimEnd,
            tempo = TempoEstimate(bpm, 0.5f + rnd.nextFloat() * 0.5f), grid = grid, key = key,
            loudness = LoudnessInfo(lufs, -1f), bars = barFeatures, ltasDb = ltas, sections = sections,
            intro = IntroType.entries[rnd.nextInt(IntroType.entries.size)], outro = OutroType.entries[rnd.nextInt(OutroType.entries.size)],
            cues = cues, outroKey = outroKey, introKey = introKey,
            outroLtasDb = FloatArray(31) { -60f + rnd.nextFloat() * 60f }, introLtasDb = FloatArray(31) { -60f + rnd.nextFloat() * 60f },
        )
    }

    fun ref(analysis: TrackAnalysis, albumId: String? = null): TrackRef = TrackRef(analysis.sourceId, AudioSourceId(analysis.sourceId), analysis, albumId = albumId)

    /** A plain 120 BPM C-major analysis of [seconds] with full-confidence grid (deterministic). */
    fun simple(id: String, seconds: Double = 60.0, bpm: Double = 120.0, lufs: Float = -14f, sampleRate: Int = SR, confidence: Float = 0.95f): TrackAnalysis {
        val total = Math.round(seconds * sampleRate)
        val trimStart = sampleRate / 2L
        val trimEnd = total - sampleRate / 2L
        val grid = BeatGrid.rigid(bpm, sampleRate, trimStart, trimEnd, confidence = confidence, phraseStartBeat = 0)
        val beats = grid.beatCount
        val n = (beats / 4).coerceAtLeast(1)
        val bars = BarFeatures(FloatArray(n) { 0.8f }, FloatArray(n) { 0.1f }, FloatArray(n) { 0.2f }, FloatArray(n) { 0.5f }, FloatArray(n) { 0.2f }, FloatArray(n) { 0.7f }, FloatArray(n))
        return TrackAnalysis(
            sourceId = id, fingerprint = "fp-$id", sampleRate = sampleRate, totalFrames = total, trimStartFrame = trimStart, trimEndFrame = trimEnd,
            tempo = TempoEstimate(bpm, 0.9f), grid = grid, key = KeyEstimate(MusicalKey(0, Mode.MAJOR), 0.9f), loudness = LoudnessInfo(lufs, -1f), bars = bars,
            intro = IntroType.BEAT_INTRO, outro = OutroType.BEAT_OUTRO,
            cues = Cues(mixOutBeat = (beats - 32).coerceAtLeast(0) / 32 * 32, mixInBeat = 32.coerceAtMost(beats), firstDownbeat = 0, lastDownbeat = (beats - 1) / 4 * 4),
        )
    }
}
