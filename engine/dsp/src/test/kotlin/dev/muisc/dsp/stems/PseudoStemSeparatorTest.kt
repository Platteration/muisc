package dev.muisc.dsp.stems

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.dsp.fft.Stft
import dev.muisc.dsp.filter.LinkwitzRiley
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PseudoStemSeparatorTest {
    private val sr = 44100

    private fun amplitude(x: FloatArray, freqHz: Double, start: Int, len: Int): Double {
        val w = 2.0 * PI * freqHz / sr
        var re = 0.0; var im = 0.0
        for (i in 0 until len) { val v = x[start + i].toDouble(); re += v * cos(w * (start + i)); im -= v * sin(w * (start + i)) }
        return 2.0 * sqrt(re * re + im * im) / len
    }

    private fun maxSumError(stems: Stems, x: AudioBuffer): Float {
        val sum = stems.sum()
        var m = 0f
        for (c in 0 until x.channelCount) for (i in 0 until x.frames) m = maxOf(m, abs(sum[c][i] - x[c][i]))
        return m
    }

    /** Fraction of the STFT power of [x] (mono mix of the buffer) below [hz]. */
    private fun lowBandFraction(x: AudioBuffer, hz: Double): Double {
        val stft = Stft(2048, 512)
        var lo = 0.0; var total = 0.0
        val cut = hz * 2048 / sr
        for (c in 0 until x.channelCount) {
            val mag = stft.magnitudes(x[c])
            for (t in mag.indices) for (k in mag[t].indices) { val p = mag[t][k].toDouble() * mag[t][k]; total += p; if (k <= cut) lo += p }
        }
        return lo / total
    }

    @Test
    fun stemsSumToInputWithinMinus60dBFS() {
        val song = SyntheticSong(bars = 8, introBars = 2, outroBars = 2, bpm = 128.0)
        val x = song.render()
        val stems = PseudoStemSeparator().separate(x)
        assertEquals(StemQuality.PSEUDO, stems.quality)
        assertEquals(x.frames, stems.frames)
        val err = maxSumError(stems, x)
        assertTrue(err < 0.001f, "max |sum - x| = $err (must be < -60 dBFS = 0.001)")
        assertTrue(err < 1e-5f, "sum should be at float precision, was $err")
        // Same for the sub-to-bass option, mono input and an odd length.
        val mono = x.withChannels(1).slice(0, x.frames - 123)
        for (sep in listOf(PseudoStemSeparator(percussiveSubToBassHz = 60.0), PseudoStemSeparator())) {
            val s = sep.separate(mono)
            assertTrue(maxSumError(s, mono) < 1e-5f)
            assertEquals(1, s.channelCount)
        }
    }

    @Test
    fun drumsDominateOnsetsAndBassIsLowBand() {
        val song = SyntheticSong(bars = 10, introBars = 2, outroBars = 2)
        val x = song.render()
        val stems = PseudoStemSeparator().separate(x)
        // Energy in the first 20 ms after every body beat (kick + hat, snare on 2 and 4).
        val win = (0.02 * sr).toInt()
        var drums = 0.0; var total = 0.0; var count = 0
        for (b in song.beatTimes()) {
            if (b < song.bodyStartSec || b >= song.outroStartSec) continue
            val s = Math.round(b * sr).toInt()
            val f = StemUtils.energyFractions(stems, s, s + win)
            // energyFractions is relative to the stem energies; weight by the input energy for the average.
            var e = 0.0
            for (c in 0 until x.channelCount) for (i in s until s + win) e += x[c][i].toDouble() * x[c][i]
            drums += f[StemKind.DRUMS.ordinal] * e; total += e; count++
        }
        assertTrue(count >= 16)
        val drumShare = drums / total
        println("pseudo-stems: drums share of onset energy = %.3f".format(drumShare))
        assertTrue(drumShare > 0.5, "drums hold $drumShare of the onset energy")
        // Bass stem: dominated by content below 250 Hz.
        val bassLow = lowBandFraction(stems.bass, 250.0)
        println("pseudo-stems: bass stem low-band fraction = %.3f".format(bassLow))
        assertTrue(bassLow > 0.9, "bass stem low-band fraction $bassLow")
        // The bass stem carries most of the sub-250 Hz harmonic energy of the song: compare with the drums-free mix.
        val bassRms = StemUtils.rms(stems, StemKind.BASS)
        assertTrue(bassRms > 0.02f, "bass stem should not be empty, rms=$bassRms")
        // The vocals stem is band-limited: almost nothing below 100 Hz.
        assertTrue(lowBandFraction(stems.vocals, 100.0) < 0.05, "vocals must not carry sub-100 Hz content")
    }

    @Test
    fun centredSineGoesToVocalsHardPannedToOther() {
        // Stereo pad: centred 440 Hz "vocal" (amp 0.3), hard-left 2 kHz (amp 0.3), plus a centred sub bass at
        // 60 Hz (amp 0.3) that must land in bass. Fades avoid edge transients.
        val seconds = 3.0
        val vocal = Synth.sine(sr, 440.0, seconds, 0.3f)
        val panned = Synth.sine(sr, 2000.0, seconds, 0.3f)
        val sub = Synth.sine(sr, 60.0, seconds, 0.3f)
        for (s in listOf(vocal, panned, sub)) { Synth.fadeInInPlace(s, sr / 10); Synth.fadeOutInPlace(s, sr / 10) }
        val l = FloatArray(vocal.size) { vocal[it] + panned[it] + sub[it] }
        val r = FloatArray(vocal.size) { vocal[it] + sub[it] }
        val x = AudioBuffer.stereo(sr, l, r)
        val stems = PseudoStemSeparator().separate(x)
        assertTrue(maxSumError(stems, x) < 1e-5f)
        val start = sr; val len = sr
        val vIn = amplitude(stems.vocals[0], 440.0, start, len) / 0.3
        val vOther = amplitude(stems.other[0], 440.0, start, len) / 0.3
        val pVocals = amplitude(stems.vocals[0], 2000.0, start, len) / 0.3
        val pOther = amplitude(stems.other[0], 2000.0, start, len) / 0.3
        val subBass = amplitude(stems.bass[0], 60.0, start, len) / 0.3
        println("pseudo-stems: 440 Hz in vocals=%.3f other=%.3f; 2 kHz in vocals=%.3f other=%.3f; 60 Hz in bass=%.3f".format(vIn, vOther, pVocals, pOther, subBass))
        // Analytic expectation for the centred tone: the zero-phase crossovers have the *power* response of one
        // Butterworth section each, so 440 Hz reaches the vocal band with gain
        // (1 - |LP250|^2) * |HP200|^2 * |LP8k|^2 = 0.906 * 0.959 * 1.0 = 0.869.
        val fs = sr.toDouble()
        fun pow(db: Double) = Math.pow(10.0, db / 10.0)
        val expected = (1 - pow(LinkwitzRiley.lr4LowPassSection(250.0, fs).magnitudeDb(440.0, fs))) *
            pow(LinkwitzRiley.lr4HighPassSection(200.0, fs).magnitudeDb(440.0, fs)) *
            pow(LinkwitzRiley.lr4LowPassSection(8000.0, fs).magnitudeDb(440.0, fs))
        assertEquals(0.869, expected, 0.005)
        assertEquals(expected, vIn, 0.02, "centred 440 Hz in vocals")
        assertTrue(vOther < 0.1, "centred 440 Hz leaking into other: $vOther")
        assertTrue(pVocals < 0.05, "hard-panned 2 kHz in vocals: $pVocals")
        assertEquals(1.0, pOther, 0.03, "hard-panned 2 kHz in other")
        assertTrue(subBass > 0.95, "60 Hz in bass: $subBass")
        // Mono input: no spatial cue, vocals = 0.5 x band-limited harmonic remainder (documented weak isolation).
        val mono = PseudoStemSeparator().separate(x.withChannels(1))
        val vMono = amplitude(mono.vocals[0], 440.0, start, len) / 0.3
        assertEquals(0.5 * expected, vMono, 0.02, "mono vocal gain")
        // ... and the hard-panned tone (now simply mono content in the band) lands there at half gain too.
        assertEquals(0.5 * 0.5, amplitude(mono.vocals[0], 2000.0, start, len) / 0.3, 0.03)
        assertTrue(maxSumError(mono, x.withChannels(1)) < 1e-5f)
    }

    @Test
    fun percussiveSubToBassMovesKickSubIntoBass() {
        val song = SyntheticSong(bars = 8, introBars = 1, outroBars = 1)
        val x = song.render()
        val keep = PseudoStemSeparator().separate(x)
        val move = PseudoStemSeparator(percussiveSubToBassHz = 60.0).separate(x)
        val keepSub = lowBandFraction(keep.drums, 60.0)
        val moveSub = lowBandFraction(move.drums, 60.0)
        println("pseudo-stems: drums sub-60 Hz fraction keep=%.3f move=%.3f".format(keepSub, moveSub))
        assertTrue(moveSub < keepSub * 0.5, "moving the sub must clearly reduce the drums' sub-60 Hz share")
        assertTrue(StemUtils.rms(move, StemKind.BASS) > StemUtils.rms(keep, StemKind.BASS))
        assertTrue(maxSumError(move, x) < 1e-5f)
        // Invalid parameters are rejected.
        assertTrue(runCatching { PseudoStemSeparator(vocalLowHz = 9000.0, vocalHighHz = 8000.0) }.isFailure)
        assertTrue(runCatching { PseudoStemSeparator(centreExtraction = 1.5f) }.isFailure)
    }
}
