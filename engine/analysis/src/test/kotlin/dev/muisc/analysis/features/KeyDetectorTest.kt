package dev.muisc.analysis.features

import dev.muisc.analysis.model.Mode
import dev.muisc.analysis.model.MusicalKey
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.resample.Resampler
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import dev.muisc.audio.synth.Mode as SynthMode

/** Key detection against [SyntheticSong] ground truth (44.1 kHz stereo input, so the resample path is used). */
class KeyDetectorTest {
    private val sr = 44100

    private fun song(tonic: Int, mode: SynthMode, seed: Int = 7, bars: Int = 16): SyntheticSong =
        SyntheticSong(bpm = 120.0, tonic = tonic, mode = mode, bars = bars, sampleRate = sr, seed = seed)

    private fun truth(tonic: Int, mode: SynthMode) = MusicalKey(tonic, if (mode == SynthMode.MAJOR) Mode.MAJOR else Mode.MINOR)

    @Test
    fun fourteenSyntheticSongs_exactKeyForAtLeast11_relativeOrExactForAll() {
        val detector = KeyDetector()
        val tonics = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val table = StringBuilder()
        table.append(String.format("%-10s %-10s %-8s %-10s %-8s %-7s %-7s %s%n", "truth", "detected", "r", "second", "margin", "tuning", "result", "chroma (C..B)"))
        var exact = 0
        var related = 0
        var total = 0
        for (mode in SynthMode.values()) for (t in tonics) {
            val s = song(t, mode, seed = 7 + t)
            val audio = s.render()
            val d = detector.detect(audio)
            val expected = truth(t, mode)
            val got = d.key.key
            val isExact = got == expected
            val isRelated = isExact || got.camelot.distanceTo(expected.camelot) <= 1
            if (isExact) exact++
            if (isRelated) related++
            total++
            val chroma = d.key.chroma.joinToString(" ") { String.format("%.2f", it) }
            table.append(String.format("%-10s %-10s %-8.3f %-10s %-8.3f %-7.1f %-7s %s%n", expected.shortName, got.shortName, d.correlation, d.key.secondBest?.shortName ?: "-", d.margin, d.tuning.cents, if (isExact) "exact" else if (isRelated) "related" else "WRONG", chroma))
            assertTrue(d.key.strength in 0f..1f)
            assertTrue(abs(d.tuning.cents) < 10f, "tuning of an A440 song: ${d.tuning.cents} cents")
        }
        println("Key detection, 14 synthetic songs (16 bars, 120 BPM, 44.1 kHz stereo):")
        println(table)
        println("exact $exact/$total, exact-or-relative $related/$total")
        assertEquals(14, total)
        assertTrue(exact >= 11, "exact keys: $exact/14")
        assertEquals(14, related, "exact-or-relative keys: $related/14")
    }

    @Test
    fun temperleyProfiles_alsoDetectMajorSong() {
        val audio = song(7, SynthMode.MAJOR).render()
        val d = KeyDetector(profiles = KeyProfileSet.TEMPERLEY).detect(audio)
        assertTrue(d.key.key == MusicalKey(7, Mode.MAJOR) || d.key.key.camelot.distanceTo(MusicalKey(7, Mode.MAJOR).camelot) <= 1, "got ${d.key.key.name}")
    }

    @Test
    fun detunedSong_tuningWithin8Cents_keyStillCorrect() {
        val s = song(0, SynthMode.MAJOR)
        val original = s.render()
        // Shift every pitch up by ~30 cents: resample the whole song by 2^(-30/1200) and relabel it at the
        // original rate (this also speeds the tempo up by 1.75 %, which does not matter here).
        val outRate = Math.round(sr * 2.0.pow(-30.0 / 1200.0)).toInt()
        val actualCents = 1200.0 * ln(sr.toDouble() / outRate) / ln(2.0)
        val resampler = Resampler()
        val detuned = AudioBuffer(sr, Array(original.channelCount) { resampler.resample(original[it], sr, outRate) })

        val detector = KeyDetector()
        val ref = detector.detect(original)
        val d = detector.detect(detuned)
        println("tuning: original %.1f cents (conf %.2f), detuned by %.1f -> estimated %.1f cents (conf %.2f); key %s -> %s".format(
            ref.tuning.cents, ref.tuning.confidence, actualCents, d.tuning.cents, d.tuning.confidence, ref.key.key.shortName, d.key.key.shortName))
        assertTrue(abs(ref.tuning.cents) <= 8f, "reference tuning ${ref.tuning.cents}")
        assertTrue(abs(d.tuning.cents - actualCents) <= 8.0, "estimated ${d.tuning.cents} vs $actualCents cents")
        assertEquals(MusicalKey(0, Mode.MAJOR), d.key.key)
        assertTrue(d.tuning.confidence > 0.3f, "tuning confidence ${d.tuning.confidence}")
    }

    @Test
    fun sectionKeys_concatenatedSongsWithDifferentTonics_introAndOutroMatchTheirParts() {
        val a = song(0, SynthMode.MAJOR, seed = 3)
        val b = song(6, SynthMode.MAJOR, seed = 5)
        val audio = a.render().concat(b.render())
        val detector = KeyDetector()
        val whole = detector.detect(audio).key
        val sections = detector.sectionKeys(audio, 0L, audio.frames.toLong(), whole)
        val intro = sections.introKey ?: whole
        val outro = sections.outroKey ?: whole
        println("sections: whole=${whole.key.shortName} intro=${sections.introKey?.key?.shortName ?: "(same)"} outro=${sections.outroKey?.key?.shortName ?: "(same)"}")
        assertEquals(MusicalKey(0, Mode.MAJOR), intro.key, "intro key")
        assertEquals(MusicalKey(6, Mode.MAJOR), outro.key, "outro key")
        assertTrue(sections.introKey != null || sections.outroKey != null, "intro and outro cannot both equal the whole-track key")
        // Direct range queries agree.
        val half = a.render().frames.toLong()
        assertEquals(MusicalKey(0, Mode.MAJOR), detector.keyForRange(audio, 0L, half).key)
        assertEquals(MusicalKey(6, Mode.MAJOR), detector.keyForRange(audio, half, audio.frames.toLong()).key)
        // A short track (not longer than the region) reports no section keys.
        val short = a.render().slice(0, sr * 20)
        val none = detector.sectionKeys(short, 0L, short.frames.toLong(), detector.detect(short).key)
        assertEquals(null, none.introKey); assertEquals(null, none.outroKey)
    }

    @Test
    fun silenceAndShortInput_lowStrengthWithoutThrowing() {
        val detector = KeyDetector()
        val silent = detector.detect(AudioBuffer.silence(sr, 2, sr * 5))
        assertEquals(0f, silent.key.strength)
        assertEquals(0f, silent.correlation)
        assertTrue(silent.key.chroma.all { it == 0f })
        assertEquals(TuningEstimate.NONE, silent.tuning)
        val empty = detector.detect(AudioBuffer.silence(sr, 1, 0))
        assertEquals(0f, empty.key.strength)
        val tiny = detector.detect(AudioBuffer.mono(sr, FloatArray(50) { 0.3f }))
        assertTrue(tiny.key.strength in 0f..1f)
        val reversed = detector.detectRange(AudioBuffer.silence(sr, 1, sr), 1000L, 10L)
        assertEquals(0f, reversed.key.strength)
    }

    @Test
    fun fromChroma_tonicTriadsMapToTheirKeys() {
        val detector = KeyDetector()
        val cMajor = FloatArray(12).also { it[0] = 1f; it[4] = 0.6f; it[7] = 0.8f }
        val d = detector.fromChroma(cMajor)
        assertEquals(MusicalKey(0, Mode.MAJOR), d.key.key)
        assertTrue(d.key.strength > 0.5f && d.key.strength <= 1f)
        assertNotNull(d.key.secondBest)
        assertTrue(d.key.secondStrength <= d.key.strength)
        assertEquals(1f, d.key.chroma.sum(), 1e-4f)
        val aMinor = FloatArray(12).also { it[9] = 1f; it[0] = 0.6f; it[4] = 0.8f }
        assertEquals(MusicalKey(9, Mode.MINOR), detector.fromChroma(aMinor).key.key)
        val flat = FloatArray(12) { 1f }
        assertEquals(0f, detector.fromChroma(flat).key.strength)
    }
}
