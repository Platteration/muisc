package dev.muisc.analysis.features

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessAnalyzerTest {
    @Test
    fun stereoSineAtMinus23dBFS_readsMinus23Lufs() {
        for (sr in intArrayOf(44100, 48000)) {
            val amp = 10.0.pow(-23.0 / 20.0).toFloat()
            val l = Synth.sine(sr, 1000.0, 10.0, amp)
            val info = LoudnessAnalyzer().analyze(AudioBuffer.stereo(sr, l, l.copyOf()))
            println("sr $sr: I=%.2f LUFS, TP=%.2f dBTP, LRA=%.2f LU, shortTerm n=%d".format(info.integratedLufs, info.truePeakDbtp, info.loudnessRangeLu, info.shortTermLufs.size))
            assertEquals(-23.0f, info.integratedLufs, 0.1f)
            assertTrue(abs(info.truePeakDbtp - (-23.0f)) <= 0.2f, "true peak ${info.truePeakDbtp}")
            assertEquals(0.1f, info.shortTermHopSec)
            // 10 s -> 100 sub-blocks -> 71 short-term windows, each ~-23 LUFS.
            assertEquals(71, info.shortTermLufs.size)
            for (v in info.shortTermLufs) assertEquals(-23.0f, v, 0.2f)
            assertTrue(info.loudnessRangeLu < 0.5f)
        }
    }

    @Test
    fun rangeSelection_andSilence() {
        val sr = 44100
        val amp = 10.0.pow(-23.0 / 20.0).toFloat()
        val loud = Synth.sine(sr, 1000.0, 5.0, amp)
        val x = FloatArray(sr * 10)
        System.arraycopy(loud, 0, x, sr * 5, loud.size) // 5 s silence then 5 s tone
        val audio = AudioBuffer.mono(sr, x)
        val all = LoudnessAnalyzer().analyze(audio)
        val tail = LoudnessAnalyzer().analyze(audio, (sr * 5).toLong(), audio.frames.toLong())
        // Gating removes the silence: both read the mono tone (-23 dBFS mono = -26 LUFS).
        assertEquals(-26.0f, all.integratedLufs, 0.15f)
        assertEquals(-26.0f, tail.integratedLufs, 0.15f)
        assertEquals(21, tail.shortTermLufs.size)

        val silent = LoudnessAnalyzer().analyze(AudioBuffer.silence(sr, 2, sr * 4))
        assertEquals(FeatureRate.SILENCE_DB, silent.integratedLufs)
        assertEquals(FeatureRate.SILENCE_DB, silent.truePeakDbtp)
        assertTrue(silent.shortTermLufs.all { it == FeatureRate.SILENCE_DB })
        val empty = LoudnessAnalyzer().analyze(AudioBuffer.silence(sr, 1, 0))
        assertEquals(0, empty.shortTermLufs.size)
    }
}
