package dev.muisc.analysis.features

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.texture.ThirdOctaveBands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectrumAnalyzerTest {
    private val sr = 44100

    @Test
    fun centroid_whiteAboveLowPassedNoise() {
        val white = Synth.whiteNoise(sr, 5.0, seed = 2)
        // "Pink-ish": one-pole low-pass of the same noise.
        val pink = FloatArray(white.size)
        var y = 0f
        for (i in white.indices) { y += 0.05f * (white[i] - y); pink[i] = y }
        val analyzer = SpectrumAnalyzer()
        val cw = analyzer.centroidHz(AudioBuffer.mono(sr, white))
        val cp = analyzer.centroidHz(AudioBuffer.mono(sr, pink))
        println("centroid white %.0f Hz, low-passed %.0f Hz".format(cw, cp))
        assertTrue(cw > cp, "white $cw should be brighter than low-passed $cp")
        assertTrue(cw > 8000f && cw < 14000f, "white noise centroid $cw")
    }

    @Test
    fun sineAt1kHz_ltasPeaksIn1kHzBand_textureMagnitudePeaksAtBin46() {
        val x = Synth.sine(sr, 1000.0, 20.0, 0.5f)
        val audio = AudioBuffer.stereo(sr, x, x.copyOf())
        val f = SpectrumAnalyzer().analyze(audio)
        assertEquals(ThirdOctaveBands.BANDS, f.ltasDb.size)
        val band = f.ltasDb.indices.maxByOrNull { f.ltasDb[it] }!!
        assertEquals(17, band, "1 kHz band")
        assertEquals(-9.03f, f.ltasDb[17], 0.3f) // amplitude 0.5 sine: 10 log10(0.125)
        assertEquals(1000f, f.brightnessHz, 15f)
        // Short buffer: intro / outro regions equal the whole range.
        assertTrue(f.introLtasDb.contentEquals(f.ltasDb) && f.outroLtasDb.contentEquals(f.ltasDb))

        assertEquals(SpectrumAnalyzer.TEXTURE_BINS, f.textureMagnitude.size)
        assertEquals(513, f.textureMagnitude.size)
        val peak = f.textureMagnitude.indices.maxByOrNull { f.textureMagnitude[it] }!!
        assertEquals(Math.round(1000.0 * 1024 / 22050).toInt(), peak)
        assertEquals(46, peak)
        // Unscaled Hann STFT: amplitude A on/near a bin centre reads ~A * 256 (bin 46 is 1.4 % off centre).
        assertTrue(f.textureMagnitude[46] > 0.5f * 256 * 0.6f && f.textureMagnitude[46] <= 0.5f * 256 * 1.05f, "peak magnitude ${f.textureMagnitude[46]}")
        assertTrue(f.textureMagnitude[200] < f.textureMagnitude[46] * 1e-2f)
    }

    @Test
    fun regions_longTrack_outroDiffersFromIntro() {
        val a = Synth.sine(sr, 200.0, 35.0, 0.4f)
        val b = Synth.sine(sr, 3000.0, 35.0, 0.4f)
        val audio = AudioBuffer.mono(sr, a).concat(AudioBuffer.mono(sr, b))
        val f = SpectrumAnalyzer().analyze(audio)
        assertEquals(ThirdOctaveBands.bandOf(200.0), f.introLtasDb.indices.maxByOrNull { f.introLtasDb[it] })
        assertEquals(ThirdOctaveBands.bandOf(3000.0), f.outroLtasDb.indices.maxByOrNull { f.outroLtasDb[it] })
        assertTrue(f.brightnessHz > 1000f && f.brightnessHz < 2500f, "centroid ${f.brightnessHz}")
        val peak = f.textureMagnitude.indices.maxByOrNull { f.textureMagnitude[it] }!!
        assertEquals(Math.round(3000.0 * 1024 / 22050).toInt(), peak)
    }

    @Test
    fun silenceAndEmpty_floorValuesWithoutThrowing() {
        val analyzer = SpectrumAnalyzer()
        val s = analyzer.analyze(AudioBuffer.silence(sr, 2, sr * 3))
        assertTrue(s.ltasDb.all { it == ThirdOctaveBands.FLOOR_DB })
        assertEquals(0f, s.brightnessHz)
        assertTrue(s.textureMagnitude.all { it == 0f })
        assertEquals(513, s.textureMagnitude.size)
        val e = analyzer.analyze(AudioBuffer.silence(sr, 1, 0))
        assertEquals(31, e.ltasDb.size)
        assertEquals(513, e.textureMagnitude.size)
        val tiny = analyzer.analyze(AudioBuffer.mono(sr, FloatArray(100) { 0.2f }))
        assertEquals(31, tiny.ltasDb.size)
        val reversed = analyzer.analyze(AudioBuffer.silence(sr, 1, sr), 500L, 10L)
        assertEquals(0f, reversed.brightnessHz)
    }
}
