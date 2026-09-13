package dev.muisc.transitions.synthetic

import dev.muisc.analysis.model.GridKind
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.SectionLabel
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyntheticTracksTest {
    private val song = SyntheticSong(bpm = 120.0, tonic = 0, mode = dev.muisc.audio.synth.Mode.MAJOR, bars = 32, introBars = 4, outroBars = 4, leadingSilenceSec = 0.5, trailingSilenceSec = 0.25)

    @Test
    fun gridMatchesGroundTruthBeats() {
        val t = SyntheticTracks.trackRef(song)
        val a = t.analysis
        val grid = a.grid
        assertEquals(GridKind.RIGID, grid.kind)
        assertEquals(song.bars * song.beatsPerBar, grid.beatCount)
        assertEquals(120.0, grid.bpm)
        assertEquals(0, grid.downbeatPhase)
        assertEquals(0, grid.phraseStartBeat)
        assertEquals(8, grid.phraseBars)
        assertEquals(0.95f, grid.confidence)
        val truth = song.beatTimes()
        for (i in truth.indices) {
            val expected = Math.round(truth[i] * a.sampleRate)
            assertTrue(abs(grid.beatFrames[i] - expected) <= 1, "beat $i: ${grid.beatFrames[i]} vs $expected")
        }
        assertEquals(Math.round(0.5 * 44100), a.trimStartFrame)
        assertEquals(Math.round(song.musicEndSec * 44100), a.trimEndFrame)
        assertEquals(t.audio.frames.toLong(), a.totalFrames)
        assertEquals(44100, a.sampleRate)
        assertEquals(2, t.audio.channelCount)
        // Onsets: one per beat of every body bar (kicks).
        assertEquals((song.bars - song.introBars - song.outroBars) * song.beatsPerBar, a.onsetFrames.size)
        assertEquals(grid.beatFrames[song.introBars * 4], a.onsetFrames[0])
    }

    @Test
    fun tempoKeyCuesAndSections() {
        val t = SyntheticTracks.trackRef(song, id = "a", albumId = "alb")
        val a = t.analysis
        assertEquals("a", t.trackRef.id)
        assertEquals("alb", t.trackRef.albumId)
        assertEquals(a.sourceId, t.trackRef.source.value)
        assertTrue(a.sourceId.startsWith("synthetic:bpm=120,tonic=0,mode=MAJOR,bars=32,intro=4,outro=4,"), a.sourceId)
        assertEquals(16, a.fingerprint.length)
        assertTrue(a.fingerprint.all { it in "0123456789abcdef" })

        assertEquals(120.0, a.tempo.bpm)
        assertEquals(0.95f, a.tempo.confidence)
        assertEquals(setOf(60.0, 240.0), a.tempo.alternates.map { it.bpm }.toSet())
        assertTrue(a.tempo.alternates.all { it.score < 0.95f })

        assertEquals(MusicalKey(0, Mode.MAJOR), a.key.key)
        assertEquals(0.9f, a.key.strength)
        assertEquals(MusicalKey(9, Mode.MINOR), a.key.secondBest)
        assertEquals(12, a.key.chroma.size)
        assertEquals(1.0, a.key.chroma.sumOf { it.toDouble() }, 1e-4)
        assertEquals(0, a.key.chroma.indices.maxByOrNull { a.key.chroma[it] })
        assertNull(a.outroKey); assertNull(a.introKey)

        assertEquals(IntroType.AMBIENT_INTRO, a.intro)
        assertEquals(OutroType.AMBIENT_OUTRO, a.outro)
        assertEquals(0, a.cues.firstDownbeat)
        assertEquals(31 * 4, a.cues.lastDownbeat)
        assertEquals(16, a.cues.mixInBeat)
        assertEquals(16, a.cues.dropBeat)
        assertEquals((32 - 4 - 8) * 4, a.cues.mixOutBeat)

        assertEquals(listOf(SectionLabel.INTRO, SectionLabel.DROP, SectionLabel.OUTRO), a.sections.map { it.label })
        assertEquals(0, a.sections[0].startBeat); assertEquals(16, a.sections[0].endBeat)
        assertEquals(16, a.sections[1].startBeat); assertEquals(112, a.sections[1].endBeat)
        assertEquals(112, a.sections[2].startBeat); assertEquals(128, a.sections[2].endBeat)
        assertTrue(a.sections[1].energy > a.sections[0].energy)
        assertEquals(1f, a.sections[1].drums); assertEquals(0f, a.sections[0].drums)
    }

    @Test
    fun edgeTypeVariants() {
        val cold = SyntheticTracks.analysis(SyntheticSong(bars = 16, introBars = 0, outroBars = 4))
        assertEquals(IntroType.COLD_START, cold.intro)
        assertEquals(0, cold.cues.mixInBeat)
        assertEquals(listOf(SectionLabel.DROP, SectionLabel.OUTRO), cold.sections.map { it.label })

        val hard = SyntheticTracks.analysis(SyntheticSong(bars = 16, introBars = 4, outroBars = 0))
        assertEquals(OutroType.HARD_STOP, hard.outro)
        assertEquals((16 - 8) * 4, hard.cues.mixOutBeat)
        assertEquals(listOf(SectionLabel.INTRO, SectionLabel.DROP), hard.sections.map { it.label })

        val fade = SyntheticTracks.analysis(SyntheticSong(bars = 16, introBars = 4, outroBars = 4, outroFade = true))
        assertEquals(OutroType.FADE_OUT, fade.outro)
        // The fade makes the last bar quieter than the first outro bar.
        assertTrue(fade.bars.energy[15] < fade.bars.energy[12] * 0.5, "fade: ${fade.bars.energy[12]} -> ${fade.bars.energy[15]}")

        val shortOutro = SyntheticTracks.analysis(SyntheticSong(bars = 8, introBars = 0, outroBars = 4))
        assertEquals(0, shortOutro.cues.mixOutBeat)
    }

    @Test
    fun loudnessIsMeasured() {
        val t = SyntheticTracks.trackRef(song)
        val l = t.analysis.loudness
        assertTrue(l.integratedLufs in -35f..-5f, "integrated ${l.integratedLufs}")
        // The synth soft-clips at 0.95 FS, so inter-sample peaks land slightly above 0 dBTP.
        assertTrue(l.truePeakDbtp in -20f..3f, "true peak ${l.truePeakDbtp}")
        assertEquals(0.1f, l.shortTermHopSec)
        val subBlocks = (t.audio.frames / (t.audio.sampleRate / 10))
        assertTrue(abs(l.shortTermLufs.size - (subBlocks - 29)) <= 1, "short-term curve has ${l.shortTermLufs.size} values for $subBlocks sub-blocks")
        // The intro (pad only) is quieter than the body.
        val introIdx = 10 // 1 s in (window 3 s ending at 4 s: fully inside the 0.5 s silence + 8 s intro)
        val bodyIdx = 200 // 20 s in
        assertTrue(l.shortTermLufs[bodyIdx] > l.shortTermLufs[introIdx] + 3f, "intro ${l.shortTermLufs[introIdx]} vs body ${l.shortTermLufs[bodyIdx]}")
        assertTrue(l.loudnessRangeLu > 0f)
    }

    @Test
    fun barFeaturesReflectArrangement() {
        val a = SyntheticTracks.analysis(song)
        val b = a.bars
        assertEquals(32, b.barCount)
        assertEquals(1f, b.energy.max())
        fun mean(x: FloatArray, from: Int, to: Int) = (from until to).sumOf { x[it].toDouble() } / (to - from)
        val introEnergy = mean(b.energy, 0, 4)
        val bodyEnergy = mean(b.energy, 4, 28)
        val outroEnergy = mean(b.energy, 28, 32)
        assertTrue(bodyEnergy > introEnergy * 1.5, "intro $introEnergy body $bodyEnergy")
        assertTrue(bodyEnergy > outroEnergy, "outro $outroEnergy body $bodyEnergy")
        for (bar in 0 until 32) {
            val sum = b.sub[bar] + b.bass[bar] + b.mid[bar] + b.high[bar]
            assertEquals(1.0, sum.toDouble(), 0.02, "bar $bar band shares sum to $sum")
            val expectedPerc = if (bar < 4 || bar >= 28) 0.1f else 0.7f
            assertEquals(expectedPerc, b.percussiveness[bar])
            assertEquals(0f, b.vocalActivity[bar])
        }
        // Body bars carry the kick and bass: far more low end than the pad-only intro.
        val introLow = mean(b.sub, 0, 4) + mean(b.bass, 0, 4)
        val bodyLow = mean(b.sub, 4, 28) + mean(b.bass, 4, 28)
        assertTrue(bodyLow > introLow + 0.2, "intro low-end $introLow body low-end $bodyLow")
        // Hats add high-frequency content in the body.
        assertTrue(mean(b.high, 4, 28) > mean(b.high, 0, 4))
    }

    @Test
    fun spectrumAndBrightness() {
        val a = SyntheticTracks.analysis(song)
        assertEquals(31, a.ltasDb.size)
        assertEquals(31, a.introLtasDb.size)
        assertEquals(31, a.outroLtasDb.size)
        assertTrue(a.ltasDb.all { it.isFinite() })
        assertTrue(a.brightnessHz in 100f..6000f, "brightness ${a.brightnessHz}")
        // Loudest third-octave band lies in the bass/low-mid region (kick + bass + pad).
        val peakBand = a.ltasDb.indices.maxByOrNull { a.ltasDb[it] }!!
        assertTrue(peakBand in 2..15, "peak band $peakBand")
    }

    @Test
    fun resampledToEngineFormat() {
        val prefs = TransitionPrefs(sampleRate = 48000, channels = 2)
        val t = SyntheticTracks.trackRef(song, prefs = prefs)
        assertEquals(48000, t.audio.sampleRate)
        assertEquals(2, t.audio.channelCount)
        assertEquals(48000, t.analysis.sampleRate)
        assertTrue(abs(t.audio.frames - Math.round(song.durationSec * 48000)) <= 1)
        val truth = song.beatTimes()
        for (i in truth.indices) assertTrue(abs(t.analysis.grid.beatFrames[i] - Math.round(truth[i] * 48000)) <= 1)
        assertEquals(Math.round(0.5 * 48000), t.analysis.trimStartFrame)
        assertEquals(32, t.analysis.bars.barCount)
        // Same content, different rate: loudness agrees within a fraction of a dB.
        val ref = SyntheticTracks.analysis(song)
        assertEquals(ref.loudness.integratedLufs.toDouble(), t.analysis.loudness.integratedLufs.toDouble(), 0.5)
    }

    @Test
    fun monoEngineFormat() {
        val short = SyntheticSong(bars = 8, introBars = 2, outroBars = 2)
        val mono = SyntheticTracks.trackRef(short, prefs = TransitionPrefs(channels = 1))
        assertEquals(1, mono.audio.channelCount)
        assertEquals(44100, mono.audio.sampleRate)
        val stereo = SyntheticTracks.trackRef(short)
        assertEquals(stereo.audio.frames, mono.audio.frames)
        // Mono is the average of the two channels.
        for (i in 0 until mono.audio.frames step 997) assertEquals((stereo.audio[0][i] + stereo.audio[1][i]) * 0.5f, mono.audio[0][i], 1e-6f)
        assertEquals(8, mono.analysis.bars.barCount)
        assertTrue(mono.analysis.loudness.integratedLufs > -40f)
    }

    @Test
    fun deterministicAndCached() {
        val t1 = SyntheticTracks.trackRef(song)
        val t2 = SyntheticTracks.trackRef(song, id = "other")
        assertSame(t1.audio, t2.audio)
        assertSame(t1.analysis, t2.analysis)

        val other = SyntheticTracks.trackRef(song.copy(seed = 8))
        assertNotEquals(t1.analysis.fingerprint, other.analysis.fingerprint)
        assertNotEquals(t1.analysis.sourceId, other.analysis.sourceId)
        assertTrue(!t1.audio[0].contentEquals(other.audio[0]))

        SyntheticTracks.clearCache()
        val t3 = SyntheticTracks.trackRef(song)
        assertTrue(t3.audio !== t1.audio)
        assertTrue(t3.audio[0].contentEquals(t1.audio[0]))
        assertTrue(t3.audio[1].contentEquals(t1.audio[1]))
        assertTrue(t3.analysis.bars.energy.contentEquals(t1.analysis.bars.energy))
        assertTrue(t3.analysis.ltasDb.contentEquals(t1.analysis.ltasDb))
        assertEquals(t1.analysis.loudness.integratedLufs, t3.analysis.loudness.integratedLufs)
        assertEquals(t1.analysis.fingerprint, t3.analysis.fingerprint)
        assertTrue(t3.analysis.grid.beatFrames.contentEquals(t1.analysis.grid.beatFrames))
    }
}
