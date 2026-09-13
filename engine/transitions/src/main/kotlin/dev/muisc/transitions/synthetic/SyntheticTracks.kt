package dev.muisc.transitions.synthetic

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
import dev.muisc.analysis.model.TempoCandidate
import dev.muisc.analysis.model.TempoEstimate
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.fft.RealFft
import dev.muisc.dsp.filter.MultibandCrossover
import dev.muisc.dsp.loudness.LoudnessMeter
import dev.muisc.dsp.loudness.TruePeak
import dev.muisc.dsp.resample.Resampler
import dev.muisc.dsp.texture.ThirdOctaveBands
import dev.muisc.dsp.window.Window
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A [SyntheticSong] turned into what the transition engine consumes: a ground-truth [TrackRef] (whose
 * [TrackAnalysis] was derived from the song's known arrangement, not from the analysis pipeline) plus the
 * rendered audio at the engine format the track was built for ([sampleRate] / [channels]).
 *
 * Instances are produced and cached by [SyntheticTracks.trackRef]; the audio is served to strategies by
 * [SyntheticTrackLoader].
 */
class SyntheticTrack(
    val song: SyntheticSong,
    val trackRef: TrackRef,
    /** The rendered song at the engine format; frame positions in [analysis] index into this buffer. */
    val audio: AudioBuffer,
) {
    val id: String get() = trackRef.id
    val analysis: TrackAnalysis get() = trackRef.analysis
    val sampleRate: Int get() = audio.sampleRate
    val channels: Int get() = audio.channelCount
    override fun toString(): String = "SyntheticTrack(${trackRef.id}, ${audio})"
}

/**
 * Builds fully populated ground-truth [TrackRef]s from [SyntheticSong]s WITHOUT running the analysis pipeline.
 *
 * Everything structural (beat grid, tempo, key, sections, edge types, cues, trims) comes straight from the
 * song's parameters; the level-type features (loudness, true peak, per-bar energy and band shares, LTAS,
 * brightness) are measured on the rendered audio with the `dsp` primitives so they are consistent with what a
 * real analysis would report for the same signal. Results are cached per (song, engine rate, channels), so
 * repeated calls in a test suite are free after the first one.
 *
 * Ground-truth conventions (all beat indices are on the rigid grid whose beat 0 is the first beat after the
 * leading silence, `beatsPerBar` beats per bar, downbeat phase 0, phrases of 8 bars starting at beat 0):
 *  - `tempo`: the song's bpm at confidence 0.95, with bpm/2 and bpm*2 as lower-scored alternates.
 *  - `key`: the song's tonic/mode at strength 0.9, chroma = a Krumhansl-Kessler profile rotated to the tonic
 *    (normalised to sum 1); `secondBest` is the relative key.
 *  - `bars`: one entry per bar; `energy` = per-bar RMS normalised to the loudest bar; `sub/bass/mid/high` =
 *    the bar's power share in the 4 bands of a 60 / 250 / 4000 Hz LR4 crossover (sum to 1 for non-silent
 *    bars); `percussiveness` = 0.7 in body bars (drums present) and 0.1 in intro/outro bars; `vocalActivity` = 0.
 *  - `sections`: INTRO `[0, introBars*bpb)` (when introBars > 0), DROP `[introBars*bpb, (bars-outroBars)*bpb)`,
 *    OUTRO `[(bars-outroBars)*bpb, bars*bpb)` (when outroBars > 0).
 *  - `intro` = AMBIENT_INTRO, or COLD_START when introBars = 0; `outro` = FADE_OUT when `outroFade` (and
 *    outroBars > 0), HARD_STOP when outroBars = 0, else AMBIENT_OUTRO.
 *  - `cues`: firstDownbeat 0, lastDownbeat (bars-1)*bpb, mixInBeat = dropBeat = introBars*bpb,
 *    mixOutBeat = max(0, bars - outroBars - 8) * bpb.
 *  - `trimStartFrame` / `trimEndFrame`: end of the leading silence / start of the trailing silence.
 *  - `sourceId` = "synthetic:<params>" ([sourceId]), `fingerprint` = a stable hash of the parameters ([fingerprint]).
 */
object SyntheticTracks {
    /** Crossover frequencies of the band split used for [BarFeatures] (sub | bass | mid | high). */
    val BAND_EDGES_HZ: DoubleArray = doubleArrayOf(60.0, 250.0, 4000.0)

    const val GRID_CONFIDENCE = 0.95f
    const val TEMPO_CONFIDENCE = 0.95f
    const val KEY_STRENGTH = 0.9f
    const val PERCUSSIVENESS_BODY = 0.7f
    const val PERCUSSIVENESS_EDGE = 0.1f
    /** Bars before the outro at which `mixOutBeat` is placed. */
    const val MIX_OUT_BARS_BEFORE_OUTRO = 8

    private data class Key(val song: SyntheticSong, val sampleRate: Int, val channels: Int)
    private class Built(val audio: AudioBuffer, val analysis: TrackAnalysis)

    private val cache = ConcurrentHashMap<Key, Built>()

    /**
     * Ground-truth track for [song] at `prefs.sampleRate` / `prefs.channels`. [id] defaults to [sourceId];
     * the audio and analysis are cached per (song, rate, channels), the [TrackRef] wrapper is cheap.
     */
    fun trackRef(song: SyntheticSong, id: String = sourceId(song), albumId: String? = null, prefs: TransitionPrefs = TransitionPrefs()): SyntheticTrack {
        val built = cache.computeIfAbsent(Key(song, prefs.sampleRate, prefs.channels)) { build(song, prefs.sampleRate, prefs.channels) }
        val key = MusicalKey(song.tonic, mode(song))
        val ref = TrackRef(
            id = id,
            source = AudioSourceId(built.analysis.sourceId),
            analysis = built.analysis,
            albumId = albumId,
            title = "Synthetic ${fmt(song.bpm)} BPM ${key.shortName}",
            artist = "Muisc Synth",
        )
        return SyntheticTrack(song, ref, built.audio)
    }

    /** The rendered audio of [song] at the given engine format (cached). */
    fun audio(song: SyntheticSong, prefs: TransitionPrefs = TransitionPrefs()): AudioBuffer =
        cache.computeIfAbsent(Key(song, prefs.sampleRate, prefs.channels)) { build(song, prefs.sampleRate, prefs.channels) }.audio

    /** The ground-truth analysis of [song] at the given engine format (cached). */
    fun analysis(song: SyntheticSong, prefs: TransitionPrefs = TransitionPrefs()): TrackAnalysis =
        cache.computeIfAbsent(Key(song, prefs.sampleRate, prefs.channels)) { build(song, prefs.sampleRate, prefs.channels) }.analysis

    /** Drops every cached render/analysis. */
    fun clearCache() = cache.clear()

    /** `"synthetic:bpm=120,tonic=0,mode=MAJOR,bars=32,intro=4,outro=4,fade=false,bpb=4,sr=44100,stereo=true,seed=7,lead=0,trail=0"`. */
    fun sourceId(song: SyntheticSong): String = buildString {
        append("synthetic:")
        append("bpm=").append(fmt(song.bpm))
        append(",tonic=").append(song.tonic)
        append(",mode=").append(song.mode.name)
        append(",bars=").append(song.bars)
        append(",intro=").append(song.introBars)
        append(",outro=").append(song.outroBars)
        append(",fade=").append(song.outroFade)
        append(",bpb=").append(song.beatsPerBar)
        append(",sr=").append(song.sampleRate)
        append(",stereo=").append(song.stereo)
        append(",seed=").append(song.seed)
        append(",lead=").append(fmt(song.leadingSilenceSec))
        append(",trail=").append(fmt(song.trailingSilenceSec))
    }

    /** Stable 16-hex-digit fingerprint of the song parameters (SHA-256 of [sourceId], truncated). */
    fun fingerprint(song: SyntheticSong): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sourceId(song).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append(String.format("%02x", digest[i].toInt() and 0xFF))
        return sb.toString()
    }

    /** Maps the synth's mode enum onto the analysis model's. */
    fun mode(song: SyntheticSong): Mode = if (song.mode == dev.muisc.audio.synth.Mode.MAJOR) Mode.MAJOR else Mode.MINOR

    private fun fmt(v: Double): String = if (v == Math.rint(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    // ------------------------------------------------------------------------------------------------ building

    private fun build(song: SyntheticSong, sampleRate: Int, channels: Int): Built {
        val native = song.render()
        val audio = Resampler().resample(native, sampleRate).withChannels(channels)
        return Built(audio, analyse(song, audio))
    }

    private fun analyse(song: SyntheticSong, audio: AudioBuffer): TrackAnalysis {
        val sr = audio.sampleRate
        val bpb = song.beatsPerBar
        val beats = song.bars * bpb
        val period = 60.0 * sr / song.bpm
        val firstBeatFrame = Math.round(song.leadingSilenceSec * sr)
        // endFrame half a period past the last beat -> exactly `beats` beats.
        val grid = BeatGrid.rigid(
            bpm = song.bpm, sampleRate = sr, firstBeatFrame = firstBeatFrame,
            endFrame = Math.round(firstBeatFrame + (beats - 1) * period + period / 2),
            downbeatPhase = 0, beatsPerBar = bpb, confidence = GRID_CONFIDENCE, phraseStartBeat = 0, phraseBars = 8,
        )
        check(grid.beatCount == beats) { "rigid grid has ${grid.beatCount} beats, expected $beats" }

        val tempo = TempoEstimate(
            bpm = song.bpm, confidence = TEMPO_CONFIDENCE,
            alternates = listOf(TempoCandidate(song.bpm / 2, 0.55f), TempoCandidate(song.bpm * 2, 0.45f)),
        )
        val mode = mode(song)
        val key = MusicalKey(song.tonic, mode)
        val relative = if (mode == Mode.MAJOR) MusicalKey(Math.floorMod(song.tonic - 3, 12), Mode.MINOR) else MusicalKey(Math.floorMod(song.tonic + 3, 12), Mode.MAJOR)
        val keyEstimate = KeyEstimate(key, KEY_STRENGTH, secondBest = relative, secondStrength = 0.72f, chroma = chromaProfile(song.tonic, mode))

        val loudness = measureLoudness(audio)
        val bars = barFeatures(song, audio, grid)

        val musicStart = firstBeatFrame
        val musicEnd = min(audio.frames.toLong(), Math.round(song.musicEndSec * sr))
        val introEnd = song.introBars * bpb
        val outroStart = (song.bars - song.outroBars) * bpb
        val sections = ArrayList<Section>(3)
        if (song.introBars > 0) sections += Section(0, introEnd, SectionLabel.INTRO, meanEnergy(bars, 0, song.introBars), drums = 0f)
        if (outroStart > introEnd) sections += Section(introEnd, outroStart, SectionLabel.DROP, meanEnergy(bars, song.introBars, song.bars - song.outroBars), drums = 1f)
        if (song.outroBars > 0) sections += Section(outroStart, beats, SectionLabel.OUTRO, meanEnergy(bars, song.bars - song.outroBars, song.bars), drums = 0f)

        val intro = if (song.introBars == 0) IntroType.COLD_START else IntroType.AMBIENT_INTRO
        val outro = when {
            song.outroBars == 0 -> OutroType.HARD_STOP
            song.outroFade -> OutroType.FADE_OUT
            else -> OutroType.AMBIENT_OUTRO
        }
        val cues = Cues(
            mixOutBeat = max(0, song.bars - song.outroBars - MIX_OUT_BARS_BEFORE_OUTRO) * bpb,
            mixInBeat = introEnd,
            firstDownbeat = 0,
            lastDownbeat = (song.bars - 1) * bpb,
            dropBeat = introEnd,
        )

        val mono = audio.mono()
        val ltas = ThirdOctaveBands.measureDb(mono, sr)
        val introRegionEnd = if (song.introBars > 0) grid.frameOfBeat(introEnd.toDouble()) else grid.frameOfBeat(min(beats, 8 * bpb).toDouble())
        val outroRegionStart = if (song.outroBars > 0) grid.frameOfBeat(outroStart.toDouble()) else grid.frameOfBeat(max(0, beats - 8 * bpb).toDouble())
        val introLtas = ThirdOctaveBands.measureDb(mono.copyOfRange(musicStart.toInt(), introRegionEnd.toInt().coerceIn(musicStart.toInt(), mono.size)), sr)
        val outroLtas = ThirdOctaveBands.measureDb(mono.copyOfRange(outroRegionStart.toInt().coerceIn(0, musicEnd.toInt()), musicEnd.toInt()), sr)

        return TrackAnalysis(
            sourceId = sourceId(song),
            fingerprint = fingerprint(song),
            sampleRate = sr,
            totalFrames = audio.frames.toLong(),
            trimStartFrame = musicStart,
            trimEndFrame = musicEnd,
            tempo = tempo,
            grid = grid,
            key = keyEstimate,
            loudness = loudness,
            bars = bars,
            ltasDb = ltas,
            brightnessHz = spectralCentroidHz(mono, sr),
            sections = sections,
            intro = intro,
            outro = outro,
            cues = cues,
            outroKey = null,
            introKey = null,
            outroLtasDb = outroLtas,
            introLtasDb = introLtas,
            tuningCents = 0f,
            onsetFrames = onsetFrames(song, grid),
            extra = mapOf("synthetic" to 1.0, "introBars" to song.introBars.toDouble(), "outroBars" to song.outroBars.toDouble()),
        )
    }

    /** Beats carrying a kick (body bars) are the ground-truth transient positions. */
    private fun onsetFrames(song: SyntheticSong, grid: BeatGrid): LongArray {
        val bpb = song.beatsPerBar
        val out = ArrayList<Long>()
        for (bar in song.introBars until song.bars - song.outroBars) for (b in 0 until bpb) out += grid.beatFrames[bar * bpb + b]
        return out.toLongArray()
    }

    private fun meanEnergy(bars: BarFeatures, from: Int, to: Int): Float {
        var s = 0.0; var n = 0
        for (i in max(0, from) until min(to, bars.barCount)) { s += bars.energy[i]; n++ }
        return if (n == 0) 0f else (s / n).toFloat()
    }

    private fun measureLoudness(audio: AudioBuffer): LoudnessInfo {
        val r = LoudnessMeter.measure(audio)
        val tp = TruePeak.measureDbtp(audio)
        return LoudnessInfo(
            integratedLufs = clampLufs(r.integratedLufs),
            truePeakDbtp = if (tp.isFinite()) tp.toFloat() else -120f,
            shortTermLufs = FloatArray(r.shortTermLufs.size) { clampLufs(r.shortTermLufs[it]) },
            shortTermHopSec = r.gridSec.toFloat(),
            loudnessRangeLu = r.loudnessRangeLu.toFloat(),
        )
    }

    private fun clampLufs(v: Double): Float = if (v.isFinite()) max(-120.0, v).toFloat() else -120f

    /** Per-bar RMS energy (normalised), band shares from the LR4 crossover, arrangement-derived percussiveness. */
    private fun barFeatures(song: SyntheticSong, audio: AudioBuffer, grid: BeatGrid): BarFeatures {
        val n = song.bars
        val bpb = song.beatsPerBar
        val ch = audio.channelCount
        val rms = FloatArray(n)
        val sub = FloatArray(n); val bass = FloatArray(n); val mid = FloatArray(n); val high = FloatArray(n)
        val perc = FloatArray(n); val vocal = FloatArray(n)

        val crossover = MultibandCrossover(audio.sampleRate, ch, BAND_EDGES_HZ)
        var maxLen = 0
        for (bar in 0 until n) maxLen = max(maxLen, (barEnd(grid, bar, bpb, audio.frames) - barStart(grid, bar, audio.frames)).toInt())
        val input = Array(ch) { FloatArray(maxLen) }
        val bands = Array(crossover.bands) { Array(ch) { FloatArray(maxLen) } }
        val bandPower = DoubleArray(crossover.bands)

        for (bar in 0 until n) {
            val start = barStart(grid, bar, audio.frames).toInt()
            val end = barEnd(grid, bar, bpb, audio.frames).toInt()
            val len = end - start
            perc[bar] = if (bar < song.introBars || bar >= n - song.outroBars) PERCUSSIVENESS_EDGE else PERCUSSIVENESS_BODY
            if (len <= 0) continue
            var acc = 0.0
            for (c in 0 until ch) {
                val src = audio.channels[c]
                val dst = input[c]
                System.arraycopy(src, start, dst, 0, len)
                for (i in 0 until len) { val v = dst[i].toDouble(); acc += v * v }
            }
            rms[bar] = sqrt(acc / (len.toDouble() * ch)).toFloat()
            // The crossover streams: filter state carries over bar to bar, so the split is seamless.
            crossover.process(input, bands, len)
            bandPower.fill(0.0)
            for (b in 0 until crossover.bands) {
                var p = 0.0
                for (c in 0 until ch) {
                    val x = bands[b][c]
                    for (i in 0 until len) { val v = x[i].toDouble(); p += v * v }
                }
                bandPower[b] = p
            }
            val total = bandPower.sum()
            if (total > 1e-12) {
                sub[bar] = (bandPower[0] / total).toFloat()
                bass[bar] = (bandPower[1] / total).toFloat()
                mid[bar] = (bandPower[2] / total).toFloat()
                high[bar] = (bandPower[3] / total).toFloat()
            }
        }
        val peak = rms.maxOrNull() ?: 0f
        val energy = if (peak > 0f) FloatArray(n) { rms[it] / peak } else FloatArray(n)
        return BarFeatures(energy, sub, bass, mid, high, perc, vocal)
    }

    private fun barStart(grid: BeatGrid, bar: Int, frames: Int): Long = grid.frameOfBeat((bar * grid.beatsPerBar).toDouble()).coerceIn(0L, frames.toLong())
    private fun barEnd(grid: BeatGrid, bar: Int, bpb: Int, frames: Int): Long = grid.frameOfBeat(((bar + 1) * bpb).toDouble()).coerceIn(0L, frames.toLong())

    /** Power-weighted spectral centroid over Hann frames of 4096 samples, hop 2048. */
    internal fun spectralCentroidHz(x: FloatArray, sampleRate: Int, frameSize: Int = 4096): Float {
        if (x.isEmpty()) return 0f
        val n = frameSize
        val hop = n / 2
        val window = Window.hann(n)
        val fft = RealFft(n)
        val bins = fft.bins
        val frame = FloatArray(n)
        val re = FloatArray(bins)
        val im = FloatArray(bins)
        val binHz = sampleRate.toDouble() / n
        var num = 0.0
        var den = 0.0
        var start = 0
        val last = max(0, x.size - n)
        while (true) {
            for (i in 0 until n) {
                val idx = start + i
                frame[i] = if (idx < x.size) x[idx] * window[i] else 0f
            }
            fft.forward(frame, re, im)
            for (k in 1 until bins) {
                val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                num += p * k * binHz
                den += p
            }
            if (start >= last) break
            start = min(start + hop, last)
        }
        return if (den <= 0.0) 0f else (num / den).toFloat()
    }

    /** Krumhansl-Kessler key profile rotated so that index 0 = C, normalised to sum 1. */
    internal fun chromaProfile(tonic: Int, mode: Mode): FloatArray {
        val profile = if (mode == Mode.MAJOR) KK_MAJOR else KK_MINOR
        val out = FloatArray(12)
        var sum = 0.0
        for (pc in 0 until 12) {
            val degree = Math.floorMod(pc - tonic, 12)
            out[pc] = profile[degree].toFloat()
            sum += profile[degree]
        }
        for (pc in 0 until 12) out[pc] = (out[pc] / sum).toFloat()
        return out
    }

    private val KK_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val KK_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
}
