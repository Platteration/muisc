package dev.muisc.analysis.rhythm

import dev.muisc.audio.AudioBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Two-beat grooves for the metrical-level tests: something low on beats 1 and 3, something else on 2 and 4, and
 * optionally hi-hats on every eighth. The kick, bass, snare and hat are [dev.muisc.audio.synth.SyntheticSong]'s
 * synthesis (copied, they are private there); the clap is the high-passed noise of
 * `RhythmAnalyzerTest.backbeatWithEighthNoteHats_keepsItsTempo`; the chord is a decaying piano-like triad (C4 E4 G4)
 * and the brush a short burst of unfiltered noise.
 */
object Grooves {
    /** What sounds on beats 2 and 4. */
    enum class Back { SNARE, CLAP, CHORD_BRUSH, CHORD }

    /** What sounds on beats 1 and 3. */
    enum class Low { KICK_BASS, BASS }

    data class Groove(
        val name: String,
        val bpm: Double,
        val low: Low,
        val back: Back,
        /** Level of the snare / clap / chord on 2 and 4. */
        val backAmp: Float,
        /** Level of the eighth-note hats (0: none). */
        val hatAmp: Float,
        val kickAmp: Float = 0.8f,
        /** Pitch of the snare's body (SyntheticSong's is 190 Hz, which leaks into the low band below ~150 Hz). */
        val snareHz: Double = 190.0,
        val seconds: Double = 30.0,
        val seed: Int = 5,
    ) {
        override fun toString(): String = "$name ${bpm.toInt()} BPM"
    }

    fun render(g: Groove, sr: Int = 44100): AudioBuffer {
        val x = FloatArray(Math.round(g.seconds * sr).toInt())
        val rnd = Random(g.seed)
        val beat = 60.0 / g.bpm
        var k = 0
        while ((k + 1) * beat < g.seconds) {
            val s = Math.round(k * beat * sr).toInt()
            if (k % 2 == 0) {
                if (g.low == Low.KICK_BASS) kick(x, sr, s, g.kickAmp)
                bass(x, sr, s, beat * 0.9, 65.41)
            } else when (g.back) {
                Back.SNARE -> snare(x, sr, s, g.backAmp, g.snareHz, rnd)
                Back.CLAP -> clap(x, sr, s, g.backAmp, rnd)
                Back.CHORD_BRUSH -> { chord(x, sr, s, beat * 0.9, g.backAmp); brush(x, sr, s, g.backAmp * 0.5f, rnd) }
                Back.CHORD -> chord(x, sr, s, beat * 0.9, g.backAmp)
            }
            if (g.hatAmp > 0f) for (h in 0 until 2) {
                hat(x, sr, Math.round((k * beat + h * beat / 2) * sr).toInt(), g.hatAmp * (1f + 0.25f * rnd.nextFloat()), rnd)
            }
            k++
        }
        return AudioBuffer.mono(sr, x)
    }

    private fun kick(x: FloatArray, sr: Int, start: Int, amp: Float) {
        var phase = 0.0
        for (i in 0 until (0.35 * sr).toInt()) {
            if (start + i >= x.size) break
            val t = i.toDouble() / sr
            phase += 2.0 * PI * (45.0 + 110.0 * exp(-t * 28.0)) / sr
            x[start + i] += (amp * exp(-t * 9.0) * sin(phase)).toFloat()
        }
    }

    private fun bass(x: FloatArray, sr: Int, start: Int, lenSec: Double, f: Double) {
        val w = 2.0 * PI * f / sr
        for (i in 0 until Math.round(lenSec * sr).toInt()) {
            if (start + i >= x.size) break
            val env = exp(-i / (0.25 * sr)) * (if (i < 200) i / 200.0 else 1.0)
            var s = 0.0
            for (h in 1..4) s += sin(h * w * i) / h
            x[start + i] += (0.22 * env * s).toFloat()
        }
    }

    private fun snare(x: FloatArray, sr: Int, start: Int, amp: Float, bodyHz: Double, rnd: Random) {
        val w = 2.0 * PI * bodyHz / sr
        for (i in 0 until (0.18 * sr).toInt()) {
            if (start + i >= x.size) break
            val env = exp(-i * 22.0 / sr)
            x[start + i] += (amp * env * (0.5 * sin(w * i) + 0.5 * (rnd.nextDouble() * 2 - 1))).toFloat()
        }
    }

    private fun clap(x: FloatArray, sr: Int, start: Int, amp: Float, rnd: Random) {
        var lp = 0f
        for (i in 0 until (0.18 * sr).toInt()) {
            if (start + i >= x.size) break
            val white = rnd.nextFloat() * 2f - 1f
            val out = white - lp; lp += 0.6f * (white - lp)
            x[start + i] += amp * exp(-i * 22.0 / sr).toFloat() * out
        }
    }

    private fun hat(x: FloatArray, sr: Int, start: Int, amp: Float, rnd: Random) {
        var lp = 0f
        for (i in 0 until (0.1 * sr).toInt()) {
            if (start + i >= x.size) break
            val white = rnd.nextFloat() * 2f - 1f
            val out = white - lp; lp += 0.6f * (white - lp)
            x[start + i] += amp * exp(-i / (0.02 * sr)).toFloat() * out
        }
    }

    private fun chord(x: FloatArray, sr: Int, start: Int, lenSec: Double, amp: Float) {
        val len = Math.round(lenSec * sr).toInt()
        for (f in doubleArrayOf(261.63, 329.63, 392.0)) {
            val w = 2.0 * PI * f / sr
            for (i in 0 until len) {
                if (start + i >= x.size) break
                val attack = if (i < 0.004 * sr) i / (0.004 * sr) else 1.0
                val release = if (i > len - 0.02 * sr) (len - i) / (0.02 * sr) else 1.0
                val env = attack * release * exp(-i * 5.0 / sr)
                x[start + i] += (amp * env * (sin(w * i) + 0.4 * sin(2 * w * i) + 0.15 * sin(3 * w * i))).toFloat()
            }
        }
    }

    private fun brush(x: FloatArray, sr: Int, start: Int, amp: Float, rnd: Random) {
        for (i in 0 until (0.15 * sr).toInt()) {
            if (start + i >= x.size) break
            val attack = if (i < 0.008 * sr) i / (0.008 * sr) else 1.0
            x[start + i] += (amp * attack * exp(-i * 25.0 / sr) * (rnd.nextDouble() * 2 - 1)).toFloat()
        }
    }
}
