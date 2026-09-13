package dev.muisc.analysis.structure

import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.Section
import dev.muisc.analysis.model.SectionLabel
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.SyntheticSong
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ground-truth tests against [SyntheticSong] (44.1 kHz stereo input → 22.05 kHz analysis path): 32 bars,
 * 4 pad-only intro bars, 4 drum-less outro bars. Grids are [BeatGrid.rigid] from the song's known tempo so the
 * tests do not depend on the beat tracker. Prints a boundary table per song.
 */
class StructureAnalyzerTest {
    private val sr = 44100

    private fun grid(song: SyntheticSong, audio: AudioBuffer, phraseStartBeat: Int = song.introBars * song.beatsPerBar): BeatGrid =
        BeatGrid.rigid(song.bpm, sr, Math.round(song.leadingSilenceSec * sr), audio.frames.toLong(), phraseStartBeat = phraseStartBeat)

    private fun run(song: SyntheticSong, fadeDetected: Boolean = false, hardStop: Boolean = false, bars: BarFeatures? = null, phraseStartBeat: Int = song.introBars * song.beatsPerBar): Pair<StructureResult, BeatGrid> {
        val audio = song.render()
        val g = grid(song, audio, phraseStartBeat)
        val r = TrackStructureAnalyzer().analyze(audio, g, 0L, audio.frames.toLong(), bars, fadeDetected, hardStop)
        return r to g
    }

    private fun printTable(name: String, song: SyntheticSong, r: StructureResult, g: BeatGrid) {
        val bpb = song.beatsPerBar
        println("== $name (${song.bpm} BPM, ${g.beatCount} beats) ==")
        println("  expected boundaries: bar ${song.introBars} (beat ${song.introBars * bpb}), bar ${song.bars - song.outroBars} (beat ${(song.bars - song.outroBars) * bpb})")
        for (s in r.sections) {
            println("  section beats %4d..%4d  bar %5.1f..%5.1f  %-9s energy=%.2f drums=%.2f vocals=%.2f".format(
                s.startBeat, s.endBeat, s.startBeat / bpb.toDouble(), s.endBeat / bpb.toDouble(), s.label, s.energy, s.drums, s.vocals))
        }
        println("  intro=${r.intro} outro=${r.outro} cues=${r.cues}")
        val nov = r.novelty
        if (nov.isNotEmpty()) {
            val top = nov.indices.sortedByDescending { nov[it] }.take(6).sorted()
            println("  top novelty beats: " + top.joinToString { "$it:%.2f".format(nov[it]) })
        }
        val a = r.activity
        println("  activity per bar (energy/drums): " + (0 until g.beatCount / bpb).joinToString(" ") { b ->
            "%d:%.2f/%.2f".format(b, a.meanEnergy(b * bpb, (b + 1) * bpb), a.meanDrums(b * bpb, (b + 1) * bpb)) })
    }

    private fun hasBoundaryNear(sections: List<Section>, beat: Int, tolBeats: Int): Boolean =
        sections.any { abs(it.startBeat - beat) <= tolBeats }

    @Test
    fun sectionBoundaries_at100And128Bpm_withinOneBarOfIntroAndOutro() {
        for (bpm in doubleArrayOf(100.0, 128.0)) {
            val song = SyntheticSong(bpm = bpm)
            val (r, g) = run(song)
            printTable("boundaries", song, r, g)
            val bpb = song.beatsPerBar
            assertTrue(hasBoundaryNear(r.sections, song.introBars * bpb, bpb), "$bpm BPM: no boundary within 1 bar of bar ${song.introBars}: ${r.sections.map { it.startBeat }}")
            assertTrue(hasBoundaryNear(r.sections, (song.bars - song.outroBars) * bpb, bpb), "$bpm BPM: no boundary within 1 bar of bar ${song.bars - song.outroBars}: ${r.sections.map { it.startBeat }}")
            assertEquals(0, r.sections.first().startBeat)
            assertEquals(g.beatCount, r.sections.last().endBeat)
            assertTrue(r.sections.all { it.startBeat == 0 || g.isDownbeat(it.startBeat) }, "boundaries must sit on downbeats")
            assertTrue(r.sections.zipWithNext().all { (a, b) -> a.endBeat == b.startBeat }, "sections must be contiguous")
            assertEquals(SectionLabel.INTRO, r.sections.first().label)
            assertEquals(SectionLabel.OUTRO, r.sections.last().label)
            assertTrue(r.sections.size <= 8, "too many sections (${r.sections.size}) for a 3-part song")
        }
    }

    @Test
    fun introIsAmbient_outroIsAmbient_andFlagPrecedence() {
        val song = SyntheticSong(bpm = 120.0)
        val (r, g) = run(song)
        printTable("default 120", song, r, g)
        assertEquals(IntroType.AMBIENT_INTRO, r.intro)
        assertEquals(OutroType.AMBIENT_OUTRO, r.outro)

        // Precedence table: FADE_OUT > HARD_STOP > content.
        val (hard, _) = run(song, hardStop = true)
        assertEquals(OutroType.HARD_STOP, hard.outro)
        val fadeSong = SyntheticSong(bpm = 120.0, outroFade = true)
        val (fade, _) = run(fadeSong, fadeDetected = true)
        assertEquals(OutroType.FADE_OUT, fade.outro)
        val (fadeAndHard, _) = run(fadeSong, fadeDetected = true, hardStop = true)
        assertEquals(OutroType.FADE_OUT, fadeAndHard.outro, "fade flag must beat hard-stop flag")
        assertEquals(IntroType.AMBIENT_INTRO, fade.intro)
    }

    @Test
    fun noIntroBars_isColdStartOrBeatIntro() {
        val song = SyntheticSong(bpm = 120.0, introBars = 0)
        val (r, g) = run(song, phraseStartBeat = 0)
        printTable("introBars=0", song, r, g)
        println("  introBars=0 classified as ${r.intro}")
        assertTrue(r.intro == IntroType.COLD_START || r.intro == IntroType.BEAT_INTRO, "got ${r.intro}")
        assertEquals(0, r.cues.mixInBeat, "drums from beat 0 → mix in at the first phrase start")
    }

    @Test
    fun cuesMatchGroundTruth() {
        val song = SyntheticSong(bpm = 120.0)
        val (r, g) = run(song)
        val bpb = song.beatsPerBar
        val c = r.cues
        println("cues: $c")
        assertEquals(0, c.firstDownbeat)
        assertEquals(31 * bpb, c.lastDownbeat, "last downbeat = bar 31")
        assertTrue(abs(c.mixInBeat - song.introBars * bpb) <= bpb, "mixIn ${c.mixInBeat} not within a bar of ${song.introBars * bpb}")
        val expectedOut = (song.bars - song.outroBars - 8) * bpb
        assertTrue(abs(c.mixOutBeat - expectedOut) <= bpb, "mixOut ${c.mixOutBeat} not within a bar of $expectedOut")
        assertTrue(g.isPhraseStart(c.mixInBeat) && g.isPhraseStart(c.mixOutBeat), "mix points must be phrase starts")
        assertTrue(abs(c.dropBeat - song.introBars * bpb) <= bpb, "drop ${c.dropBeat} expected at the body entry ${song.introBars * bpb}")
    }

    @Test
    fun barFeaturesPath_usesGivenEnergyDrumsAndVocals() {
        val song = SyntheticSong(bpm = 120.0)
        val bars = song.bars
        val energy = FloatArray(bars) { b -> if (b < song.introBars) 0.3f else if (b >= bars - song.outroBars) 0.4f else 1f }
        val perc = FloatArray(bars) { b -> if (b < song.introBars || b >= bars - song.outroBars) 0.05f else 0.5f }
        val vocals = FloatArray(bars) { b -> if (b < song.introBars) 0.9f else if (b >= bars - song.outroBars) 0.8f else 0.2f }
        val bf = BarFeatures(energy = energy, sub = FloatArray(bars), bass = FloatArray(bars), mid = FloatArray(bars), high = FloatArray(bars), percussiveness = perc, vocalActivity = vocals)
        val (r, g) = run(song, bars = bf)
        printTable("bar features", song, r, g)
        assertEquals(BeatActivity.Source.BAR_FEATURES, r.activity.source)
        assertEquals(IntroType.VOCAL_INTRO, r.intro)
        assertEquals(OutroType.VOCAL_OUTRO, r.outro)
        assertTrue(r.sections.first().vocals > 0.8f && r.sections.first().drums < 0.1f)
        assertTrue(r.sections.any { it.label == SectionLabel.DROP || it.label == SectionLabel.CHORUS })
        assertEquals(16, r.cues.mixInBeat)
    }

    @Test
    fun deterministic_sameInputSameOutput() {
        val song = SyntheticSong(bpm = 120.0, seed = 3)
        val (a, _) = run(song)
        val (b, _) = run(song)
        assertEquals(a.sections, b.sections)
        assertEquals(a.cues, b.cues)
        assertEquals(a.intro, b.intro)
        assertEquals(a.outro, b.outro)
        assertTrue(a.novelty.contentEquals(b.novelty))
    }

    @Test
    fun silenceShortAndEmptyInputs_neverThrow() {
        val an = TrackStructureAnalyzer()
        // 10 s of digital silence with a nominal grid
        val silence = AudioBuffer.silence(sr, 2, sr * 10)
        val g = BeatGrid.rigid(120.0, sr, 0L, silence.frames.toLong())
        val r = an.analyze(silence, g, 0L, silence.frames.toLong())
        assertEquals(IntroType.UNKNOWN, r.intro)
        assertTrue(r.sections.all { it.energy == 0f })
        assertEquals(OutroType.HARD_STOP, an.analyze(silence, g, hardStop = true).outro)
        // empty grid
        val e = an.analyze(SyntheticSong(bars = 2, introBars = 0, outroBars = 0).render(), BeatGrid.EMPTY)
        assertTrue(e.sections.isEmpty()); assertEquals(IntroType.UNKNOWN, e.intro); assertEquals(OutroType.UNKNOWN, e.outro); assertEquals(-1, e.cues.firstDownbeat)
        // empty audio
        val z = an.analyze(AudioBuffer.silence(sr, 1, 0), g)
        assertTrue(z.sections.isEmpty())
        // very short audio (less than one beat) and a grid of one beat
        val short = AudioBuffer.silence(sr, 1, 100)
        val g1 = BeatGrid.rigid(120.0, sr, 0L, 100L)
        val s = an.analyze(short, g1)
        assertNotNull(s)
        // empty trim region
        val t = an.analyze(silence, g, 5000L, 5000L)
        assertTrue(t.sections.isEmpty())
        // component-level: features / activity on empty input
        val f = BeatSyncFeatureExtractor().extract(AudioBuffer.silence(sr, 1, 0), BeatGrid.EMPTY)
        assertEquals(0, f.beatCount)
        assertTrue(StructureAnalyzer().analyze(f, BeatGrid.EMPTY, BeatActivity.empty()).isEmpty())
    }

    @Test
    fun beatSyncFeatures_chromaFollowsChords_fluxFollowsDrums() {
        val song = SyntheticSong(bpm = 120.0, tonic = 0) // C major: bar 0 = C E G, bar 1 = G B D
        val audio = song.render()
        val g = grid(song, audio)
        val f = BeatSyncFeatureExtractor().extract(audio, g)
        assertEquals(g.beatCount, f.beatCount)
        assertEquals(12 + 20 + 4 + 1, f.vectors[0].size)
        assertTrue(f.isValid(0) && !f.isValid(f.beatCount - 1), "last zero-length beat must be invalid")
        // unit chroma
        var n = 0f; for (v in f.chroma[1]) n += v * v
        assertEquals(1f, n, 1e-3f)
        // bar 0 (intro, pad only): C E G dominate
        val c0 = FloatArray(12); for (b in 0 until 4) for (i in 0 until 12) c0[i] += f.chroma[b][i]
        val top0 = c0.indices.sortedByDescending { c0[it] }.take(3).toSet()
        println("intro bar 0 chroma top pitch classes: $top0 (expect C=0, E=4, G=7)")
        assertTrue(top0.containsAll(setOf(0, 4, 7)), "bar 0 chroma top3 $top0")
        val introFlux = (0 until 16).map { f.hfFlux[it] }.average()
        val bodyFlux = (16 until 112).map { f.hfFlux[it] }.average()
        val outroFlux = (112 until 128).map { f.hfFlux[it] }.average()
        println("hfFlux intro=%.3f body=%.3f outro=%.3f  rms intro=%.3f body=%.3f".format(introFlux, bodyFlux, outroFlux, (0 until 16).map { f.rms[it] }.average(), (16 until 112).map { f.rms[it] }.average()))
        assertTrue(bodyFlux > 3 * introFlux && bodyFlux > 3 * outroFlux, "drum flux must dominate")
        assertTrue(f.rms[20] > f.rms[2], "body louder than intro")
    }
}
