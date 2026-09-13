package dev.muisc.analysis

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.SectionLabel
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.BufferPcmStream
import dev.muisc.audio.synth.SyntheticSong
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.muisc.audio.synth.Mode as SynthMode

/**
 * End-to-end validation of [DefaultTrackAnalyzer] against [SyntheticSong] ground truth (44.1 kHz stereo input,
 * 32 bars), plus serialisation, streaming equivalence, robustness and timing. Prints a summary table.
 */
class DefaultTrackAnalyzerTest {
    private class Case(val name: String, val song: SyntheticSong, val expectIntro: Set<IntroType>, val expectOutro: Set<OutroType>)

    companion object {
        private const val SR = 44100

        private val cases = listOf(
            Case(
                "100 BPM D major, 1 s lead-in, fade", SyntheticSong(bpm = 100.0, tonic = 2, mode = SynthMode.MAJOR, bars = 32, introBars = 4, outroBars = 4, outroFade = true, sampleRate = SR, leadingSilenceSec = 1.0, trailingSilenceSec = 0.5, seed = 11),
                setOf(IntroType.AMBIENT_INTRO), setOf(OutroType.FADE_OUT),
            ),
            Case(
                "128 BPM A minor", SyntheticSong(bpm = 128.0, tonic = 9, mode = SynthMode.MINOR, bars = 32, introBars = 4, outroBars = 4, sampleRate = SR, seed = 12),
                setOf(IntroType.AMBIENT_INTRO), setOf(OutroType.AMBIENT_OUTRO, OutroType.HARD_STOP),
            ),
            Case(
                "140 BPM F# major, cold start / hard stop", SyntheticSong(bpm = 140.0, tonic = 6, mode = SynthMode.MAJOR, bars = 32, introBars = 0, outroBars = 0, sampleRate = SR, seed = 13),
                // drums to the last beat: HARD_STOP when the silence stage flags it, else the content says BEAT_OUTRO
                setOf(IntroType.COLD_START, IntroType.BEAT_INTRO), setOf(OutroType.HARD_STOP, OutroType.BEAT_OUTRO),
            ),
        )

        /** Analysed once for the whole class. */
        private val results: List<Pair<Case, TrackAnalysis>> by lazy {
            val analyzer = DefaultTrackAnalyzer()
            cases.map { c ->
                val audio = c.song.render()
                c to analyzer.analyze(audio, "synthetic:${c.name}", Fingerprint.ofBuffer(audio))
            }
        }

        fun truthKey(song: SyntheticSong) = MusicalKey(song.tonic, if (song.mode == SynthMode.MAJOR) Mode.MAJOR else Mode.MINOR)

        /** Deep field-by-field comparison (the model's `equals` only compares identity fields). */
        fun assertDeepEquals(a: TrackAnalysis, b: TrackAnalysis) {
            assertEquals(a.version, b.version); assertEquals(a.sourceId, b.sourceId); assertEquals(a.fingerprint, b.fingerprint)
            assertEquals(a.sampleRate, b.sampleRate); assertEquals(a.totalFrames, b.totalFrames)
            assertEquals(a.trimStartFrame, b.trimStartFrame); assertEquals(a.trimEndFrame, b.trimEndFrame)
            assertEquals(a.tempo, b.tempo)
            assertEquals(a.grid, b.grid)
            assertEquals(a.key, b.key); assertEquals(a.key.secondStrength, b.key.secondStrength)
            assertTrue(a.key.chroma.contentEquals(b.key.chroma), "key chroma")
            assertEquals(a.loudness, b.loudness)
            assertTrue(a.loudness.shortTermLufs.contentEquals(b.loudness.shortTermLufs), "shortTermLufs")
            assertEquals(a.loudness.loudnessRangeLu, b.loudness.loudnessRangeLu)
            assertEquals(a.bars, b.bars)
            assertTrue(a.bars.percussiveness.contentEquals(b.bars.percussiveness) && a.bars.vocalActivity.contentEquals(b.bars.vocalActivity) && a.bars.sub.contentEquals(b.bars.sub) && a.bars.high.contentEquals(b.bars.high), "bar features")
            assertTrue(a.ltasDb.contentEquals(b.ltasDb), "ltasDb"); assertTrue(a.introLtasDb.contentEquals(b.introLtasDb), "introLtasDb"); assertTrue(a.outroLtasDb.contentEquals(b.outroLtasDb), "outroLtasDb")
            assertEquals(a.brightnessHz, b.brightnessHz)
            assertEquals(a.sections, b.sections); assertEquals(a.intro, b.intro); assertEquals(a.outro, b.outro); assertEquals(a.cues, b.cues)
            assertEquals(a.outroKey, b.outroKey); assertEquals(a.introKey, b.introKey)
            assertEquals(a.tuningCents, b.tuningCents)
            assertTrue(a.onsetFrames.contentEquals(b.onsetFrames), "onsetFrames")
            assertTrue(a.textureMagnitude.contentEquals(b.textureMagnitude), "textureMagnitude")
            assertEquals(a.extra, b.extra)
        }
    }

    private fun beatSeconds(grid: BeatGrid, sr: Int) = DoubleArray(grid.beatCount) { grid.beatFrames[it].toDouble() / sr }

    private fun hitRate(est: DoubleArray, truth: List<Double>, tolSec: Double): Double =
        if (truth.isEmpty()) 0.0 else truth.count { t -> est.any { abs(it - t) <= tolSec } }.toDouble() / truth.size

    private fun downbeatsCorrect(grid: BeatGrid, est: DoubleArray, downbeats: List<Double>): Int {
        var ok = 0
        for (d in downbeats) {
            var best = 0
            for (i in est.indices) if (abs(est[i] - d) < abs(est[best] - d)) best = i
            if (abs(est[best] - d) <= 0.025 && grid.isDownbeat(best)) ok++
        }
        return ok
    }

    @Test
    fun threeSyntheticSongs_endToEndAgainstGroundTruth() {
        val table = StringBuilder()
        table.append(String.format("%-42s %-8s %-9s %-6s %-6s %-7s %-16s %-14s %-14s %-9s %-14s %-14s %-7s %-6s%n",
            "song", "bpm", "est bpm", "err%", "hits", "phase", "key", "trimStart", "trimEnd", "intro", "outro", "mixIn", "onsets", "ms"))
        var phaseOk = 0
        var keyExact = 0
        var keyRelated = 0
        val checks = ArrayList<() -> Unit>()
        for ((c, a) in results) {
            val song = c.song
            val g = a.grid
            val est = beatSeconds(g, SR)
            val bodyBeats = song.beatTimes().filter { it >= song.bodyStartSec && it < song.outroStartSec }
            val hits = hitRate(est, bodyBeats, 0.025)
            val bodyDownbeats = song.downbeatTimes().filter { it >= song.bodyStartSec && it < song.outroStartSec }
            val dbOk = downbeatsCorrect(g, est, bodyDownbeats)
            val phase = dbOk == bodyDownbeats.size
            if (phase) phaseOk++
            val errPct = abs(a.tempo.bpm - song.bpm) / song.bpm * 100
            val truth = truthKey(song)
            val exact = a.key.key == truth
            val related = exact || a.key.key.camelot.distanceTo(truth.camelot) <= 1
            if (exact) keyExact++
            if (related) keyRelated++
            val trimStartSec = a.trimStartFrame.toDouble() / SR
            val trimEndSec = a.trimEndFrame.toDouble() / SR
            val bpb = song.beatsPerBar
            table.append(String.format("%-42s %-8.0f %-9.3f %-6.2f %-6.3f %-7s %-16s %-14s %-14s %-9s %-14s %-14s %-7d %-6d%n",
                c.name, song.bpm, a.tempo.bpm, errPct, hits, "$dbOk/${bodyDownbeats.size}", a.key.key.shortName + (if (exact) " =" else if (related) " ~" else " x"),
                "%.3f/%.3f".format(trimStartSec, song.leadingSilenceSec), "%.3f/%.3f".format(trimEndSec, song.musicEndSec),
                a.intro, a.outro, "${a.cues.mixInBeat}/${song.introBars * bpb}", a.onsetFrames.size, a.analysisMillis))

            checks.add {
            assertEquals(TrackAnalysis.CURRENT_VERSION, a.version)
            assertEquals(SR, a.sampleRate)
            assertEquals(song.render().frames.toLong(), a.totalFrames)
            assertTrue(errPct <= 1.0, "${c.name}: bpm ${a.tempo.bpm} vs ${song.bpm}")
            assertTrue(hits >= 0.9, "${c.name}: body beat hit rate $hits")
            assertTrue(g.confidence > 0.5f, "${c.name}: grid confidence ${g.confidence}")
            assertTrue(related, "${c.name}: key ${a.key.key.name} vs ${truth.name}")
            assertTrue(abs(trimStartSec - song.leadingSilenceSec) <= 0.050, "${c.name}: trim start $trimStartSec vs ${song.leadingSilenceSec}")
            if (song.outroFade) {
                // The tail of a linear fade sits below -60 dBFS: by definition (TrackAnalysis.trimEndFrame excludes fades below
                // -60 dBFS) the trim end lands up to a few hundred ms before the nominal music end, never after it.
                assertTrue(trimEndSec <= song.musicEndSec + 0.050 && trimEndSec >= song.musicEndSec - 0.300, "${c.name}: trim end $trimEndSec vs ${song.musicEndSec}")
            } else {
                assertTrue(abs(trimEndSec - song.musicEndSec) <= 0.050, "${c.name}: trim end $trimEndSec vs ${song.musicEndSec}")
            }
            assertTrue(a.intro in c.expectIntro, "${c.name}: intro ${a.intro} not in ${c.expectIntro}")
            assertTrue(a.outro in c.expectOutro, "${c.name}: outro ${a.outro} not in ${c.expectOutro}")
            assertTrue(abs(a.cues.mixInBeat - song.introBars * bpb) <= bpb, "${c.name}: mixIn ${a.cues.mixInBeat} vs ${song.introBars * bpb}")
            assertTrue(a.cues.firstDownbeat >= 0 && a.cues.lastDownbeat > a.cues.firstDownbeat && a.cues.mixOutBeat in a.cues.firstDownbeat..a.cues.lastDownbeat, "${c.name}: cues ${a.cues}")
            assertTrue(a.onsetFrames.isNotEmpty(), "${c.name}: no onsets")
            assertTrue(a.onsetFrames.all { it in a.trimStartFrame..a.trimEndFrame }, "${c.name}: onsets outside the trim range")
            assertTrue((1 until a.onsetFrames.size).all { a.onsetFrames[it] > a.onsetFrames[it - 1] }, "${c.name}: onsets not ascending")
            assertEquals(513, a.textureMagnitude.size, "${c.name}: texture bins")
            assertTrue(a.textureMagnitude.any { it > 0f }, "${c.name}: texture all zero")
            assertEquals(31, a.ltasDb.size, "${c.name}: ltas bands"); assertEquals(31, a.introLtasDb.size); assertEquals(31, a.outroLtasDb.size)
            assertTrue(a.brightnessHz > 0f)
            assertTrue(a.sections.isNotEmpty(), "${c.name}: no sections")
            assertEquals(0, a.sections.first().startBeat); assertEquals(g.beatCount, a.sections.last().endBeat)
            if (song.introBars > 0) assertEquals(SectionLabel.INTRO, a.sections.first().label, "${c.name}: first section ${a.sections.first().label}")
            assertTrue(a.bars.barCount >= song.bars - 1 && a.bars.barCount <= song.bars + 1, "${c.name}: ${a.bars.barCount} bars for ${song.bars}")
            assertTrue(a.loudness.integratedLufs > -40f && a.loudness.integratedLufs < 0f, "${c.name}: loudness ${a.loudness.integratedLufs}")
            assertTrue(a.loudness.truePeakDbtp < 3f && a.loudness.shortTermLufs.isNotEmpty())
            assertTrue(abs(a.tuningCents) < 10f, "${c.name}: tuning ${a.tuningCents}")
            assertTrue(a.analysisMillis >= 0)
            assertTrue(a.extra.containsKey("downbeatConfidence") && a.extra.containsKey("leadingSilenceMs"))
            }
        }
        println("DefaultTrackAnalyzer end-to-end (44.1 kHz stereo, 32 bars; hits = body beats within 25 ms; key: = exact, ~ relative):")
        println(table)
        println("downbeat phase correct on $phaseOk/3, key exact $keyExact/3, exact-or-relative $keyRelated/3")
        for ((c, a) in results) println("  ${c.name}: sections=${a.sections.map { "${it.label}@${it.startBeat}" }} cues=${a.cues} tuning=%.1f ct loudness=%.1f LUFS tp=%.1f dBTP brightness=%.0f Hz introKey=${a.introKey?.key?.shortName} outroKey=${a.outroKey?.key?.shortName} bars=${a.bars.barCount}".format(a.tuningCents, a.loudness.integratedLufs, a.loudness.truePeakDbtp, a.brightnessHz))
        for ((c, a) in results) println("  ${c.name}: perc=${a.bars.percussiveness.joinToString(" ") { "%.2f".format(it) }}\n    vocal=${a.bars.vocalActivity.joinToString(" ") { "%.2f".format(it) }}\n    energy=${a.bars.energy.joinToString(" ") { "%.2f".format(it) }}")
        checks.forEach { it() }
        assertTrue(phaseOk >= 2, "downbeat phase correct on only $phaseOk of 3")
        assertTrue(keyExact >= 2, "exact key on only $keyExact of 3")
        assertEquals(3, keyRelated, "exact-or-relative key")
    }

    @Test
    fun jsonRoundTrip_equalsOriginal() {
        for ((_, a) in results) {
            val json = a.toJson()
            val back = TrackAnalysis.fromJson(json)
            assertDeepEquals(a, back)
            assertEquals(a.analysisMillis, back.analysisMillis)
            assertEquals(json, back.toJson())
        }
    }

    @Test
    fun streamingOverload_equalsBufferOverload() {
        val song = SyntheticSong(bpm = 124.0, tonic = 4, bars = 16, introBars = 2, outroBars = 2, sampleRate = SR, seed = 5)
        val audio = song.render()
        val fp = Fingerprint.ofBuffer(audio)
        val analyzer = DefaultTrackAnalyzer()
        val stages = ArrayList<String>()
        val fromBuffer = analyzer.analyze(audio, "s", fp)
        val fromStream = analyzer.analyze(BufferPcmStream(audio), "s", fp) { stage, f -> stages.add(stage); assertTrue(f in 0.0..1.0) }
        assertDeepEquals(fromBuffer, fromStream)
        assertEquals(DefaultTrackAnalyzer.STAGES, stages, "progress stages in order")
        // an unknown-length stream takes the growth path and must give the same result
        val unknownLength = object : dev.muisc.audio.PcmStream by BufferPcmStream(audio) { override val totalFrames: Long get() = -1L }
        assertDeepEquals(fromBuffer, analyzer.analyze(unknownLength, "s", fp))
        // determinism
        assertDeepEquals(fromBuffer, analyzer.analyze(audio.copy(), "s", fp))
    }

    @Test
    fun robustness_silenceShortMonoAnd48k_neverThrow() {
        val analyzer = DefaultTrackAnalyzer()
        val silence = analyzer.analyze(AudioBuffer.silence(SR, 2, SR * 10), "silence", "fp")
        assertTrue(silence.grid.isEmpty); assertEquals(0f, silence.grid.confidence); assertEquals(0f, silence.tempo.confidence)
        assertEquals(0f, silence.key.strength); assertEquals(IntroType.UNKNOWN, silence.intro)
        assertEquals(0, silence.bars.barCount); assertTrue(silence.sections.isEmpty()); assertEquals(-1, silence.cues.mixInBeat)
        assertTrue(silence.onsetFrames.isEmpty()); assertEquals(31, silence.ltasDb.size)
        assertEquals(0L, silence.trimStartFrame); assertEquals(0L, silence.trimEndFrame)

        val empty = analyzer.analyze(AudioBuffer.silence(SR, 1, 0), "empty", "fp")
        assertTrue(empty.grid.isEmpty); assertEquals(0L, empty.totalFrames)

        val short = analyzer.analyze(SyntheticSong(bpm = 120.0, bars = 1, introBars = 0, outroBars = 0, sampleRate = SR).render().slice(0, (1.5 * SR).toInt()), "short", "fp")
        assertTrue(short.grid.isEmpty, "1.5 s input must not produce a grid"); assertTrue(short.trimEndFrame > short.trimStartFrame)
        assertTrue(short.key.strength in 0f..1f)

        val mono = analyzer.analyze(SyntheticSong(bpm = 120.0, bars = 12, introBars = 2, outroBars = 2, sampleRate = SR, stereo = false, seed = 9).render(), "mono", "fp")
        assertEquals(120.0, mono.tempo.bpm, 1.2); assertTrue(mono.grid.confidence > 0.5f)

        val src48 = SyntheticSong(bpm = 120.0, tonic = 7, bars = 12, introBars = 2, outroBars = 2, sampleRate = 48000, seed = 9)
        val a48 = analyzer.analyze(src48.render(), "48k", "fp")
        assertEquals(48000, a48.sampleRate)
        assertEquals(120.0, a48.tempo.bpm, 1.2)
        assertTrue(a48.grid.confidence > 0.5f)
        val body48 = src48.beatTimes().filter { it >= src48.bodyStartSec && it < src48.outroStartSec }
        assertTrue(hitRate(beatSeconds(a48.grid, 48000), body48, 0.025) >= 0.9, "48 kHz beats on the 0.5 s grid")
        assertEquals(31, a48.ltasDb.size); assertEquals(513, a48.textureMagnitude.size)
        assertEquals(MusicalKey(7, Mode.MAJOR), a48.key.key)

        // maxSeconds truncation and disabled optional stages
        val capped = DefaultTrackAnalyzer(AnalysisOptions(maxSeconds = 8.0, computeStructure = false, computeTexture = false))
        val songAudio = SyntheticSong(bpm = 120.0, bars = 16, sampleRate = SR).render()
        val t = capped.analyze(songAudio, "capped", "fp")
        assertEquals(8L * SR, t.totalFrames); assertEquals(1.0, t.extra["truncated"])
        assertTrue(t.sections.isEmpty()); assertEquals(IntroType.UNKNOWN, t.intro); assertEquals(0, t.textureMagnitude.size); assertEquals(31, t.ltasDb.size)
        assertTrue(t.grid.beatCount > 0)
        val ts = capped.analyze(BufferPcmStream(songAudio), "capped", "fp")
        assertEquals(8L * SR, ts.totalFrames)
    }

    @Test
    fun gridConversion_keepsBeatCountAndTiming() {
        val g22 = BeatGrid.rigid(128.0, 22050, 22050L, 22050L * 30, downbeatPhase = 1, phraseStartBeat = 5)
        val g44 = DefaultTrackAnalyzer.toEngineGrid(g22, 22050, 44100)
        assertEquals(g22.beatCount, g44.beatCount); assertEquals(g22.kind, g44.kind); assertEquals(1, g44.downbeatPhase); assertEquals(5, g44.phraseStartBeat)
        for (i in 0 until g22.beatCount) assertTrue(abs(g44.beatFrames[i] - 2 * g22.beatFrames[i]) <= 1, "beat $i")
        val g48 = DefaultTrackAnalyzer.toEngineGrid(g22, 22050, 48000)
        assertEquals(g22.beatCount, g48.beatCount)
        for (i in 0 until g22.beatCount) assertTrue(abs(g48.beatFrames[i] - g22.beatFrames[i] * 48000.0 / 22050) <= 2.0, "beat $i at 48k") // source grid rounding (0.5 frame) scaled by 2.18
    }

    @Test
    fun threeMinuteSong_analysesInReasonableTime() {
        val song = SyntheticSong(bpm = 128.0, tonic = 5, bars = 96, introBars = 8, outroBars = 8, sampleRate = SR, seed = 3) // 180 s
        val audio = song.render()
        val analyzer = DefaultTrackAnalyzer()
        val stageTimes = LinkedHashMap<String, Long>()
        var last = System.nanoTime()
        var lastStage = "start"
        val t0 = System.nanoTime()
        val a = analyzer.analyze(audio, "3min", Fingerprint.ofBuffer(audio)) { stage, _ ->
            val now = System.nanoTime(); stageTimes[lastStage] = (now - last) / 1_000_000; last = now; lastStage = stage
        }
        val sec = (System.nanoTime() - t0) / 1e9
        println("3-minute song (%.1f s of audio): analysed in %.2f s (%.3fx real time); stage ms: %s".format(audio.durationSec, sec, sec / audio.durationSec, stageTimes))
        assertEquals(128.0, a.tempo.bpm, 1.28)
        assertTrue(a.grid.confidence > 0.5f)
        assertTrue(sec < 30.0, "analysis took $sec s")
    }
}
