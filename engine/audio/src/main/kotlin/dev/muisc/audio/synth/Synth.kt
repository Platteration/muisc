package dev.muisc.audio.synth

import dev.muisc.audio.AudioBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Basic deterministic signal generators used by tests, the CLI `synth` command and the Transition Lab. */
object Synth {
    fun sine(sampleRate: Int, freqHz: Double, seconds: Double, amp: Float = 0.5f, phase: Double = 0.0): FloatArray {
        val n = Math.round(seconds * sampleRate).toInt()
        val out = FloatArray(n)
        val w = 2.0 * PI * freqHz / sampleRate
        for (i in 0 until n) out[i] = (amp * sin(w * i + phase)).toFloat()
        return out
    }

    fun whiteNoise(sampleRate: Int, seconds: Double, amp: Float = 0.5f, seed: Int = 1): FloatArray {
        val n = Math.round(seconds * sampleRate).toInt()
        val rnd = Random(seed)
        return FloatArray(n) { amp * (rnd.nextFloat() * 2f - 1f) }
    }

    /**
     * Impulsive click track: a short decaying burst on every beat, louder on the downbeat.
     * Beat k is at exactly `k * 60 / bpm` seconds (plus [offsetSec]). Ground truth for beat-tracker tests.
     */
    fun clickTrack(sampleRate: Int, bpm: Double, seconds: Double, beatsPerBar: Int = 4, offsetSec: Double = 0.0, amp: Float = 0.8f): FloatArray {
        val n = Math.round(seconds * sampleRate).toInt()
        val out = FloatArray(n)
        val beatLen = 60.0 / bpm
        var k = 0
        while (true) {
            val t = offsetSec + k * beatLen
            val start = Math.round(t * sampleRate).toInt()
            if (start >= n) break
            if (start >= 0) {
                val a = if (k % beatsPerBar == 0) amp else amp * 0.6f
                val freq = if (k % beatsPerBar == 0) 1500.0 else 1000.0
                addBurst(out, sampleRate, start, freq, 0.02, a)
            }
            k++
        }
        return out
    }

    /** Adds an exponentially decaying sine burst at [start] (used for clicks / hats). */
    fun addBurst(dst: FloatArray, sampleRate: Int, start: Int, freqHz: Double, decaySec: Double, amp: Float) {
        val len = Math.round(decaySec * 6 * sampleRate).toInt()
        val w = 2.0 * PI * freqHz / sampleRate
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= dst.size) break
            val env = exp(-i / (decaySec * sampleRate))
            dst[idx] += (amp * env * sin(w * i)).toFloat()
        }
    }

    fun midiToHz(midi: Double): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)

    /** Linear fade applied in place over [fadeFrames] at the end of [x]. */
    fun fadeOutInPlace(x: FloatArray, fadeFrames: Int) {
        val start = (x.size - fadeFrames).coerceAtLeast(0)
        for (i in start until x.size) x[i] *= (x.size - i).toFloat() / (x.size - start).coerceAtLeast(1)
    }

    fun fadeInInPlace(x: FloatArray, fadeFrames: Int) {
        val end = fadeFrames.coerceAtMost(x.size)
        for (i in 0 until end) x[i] *= i.toFloat() / end.coerceAtLeast(1)
    }
}

enum class Mode { MAJOR, MINOR }

/**
 * A deterministic "synthetic song" with known tempo, key, beat grid and structure, used as ground truth for
 * analysis tests and as a fast way to audition transition strategies without real music.
 *
 * Arrangement (in bars): [introBars] pad-only intro → body (kick/hat/bass/pad) → [outroBars] outro
 * (drums drop out, optional fade). Chord progression: I–V–vi–IV (major) or i–VI–III–VII (minor), one chord per bar.
 */
data class SyntheticSong(
    val bpm: Double = 120.0,
    val tonic: Int = 0,           // pitch class 0=C .. 11=B
    val mode: Mode = Mode.MAJOR,
    val bars: Int = 32,
    val introBars: Int = 4,
    val outroBars: Int = 4,
    val outroFade: Boolean = false,
    val beatsPerBar: Int = 4,
    val sampleRate: Int = 44100,
    val stereo: Boolean = true,
    val seed: Int = 7,
    val leadingSilenceSec: Double = 0.0,
    val trailingSilenceSec: Double = 0.0,
) {
    val beatSec: Double get() = 60.0 / bpm
    val barSec: Double get() = beatSec * beatsPerBar
    val bodyStartSec: Double get() = leadingSilenceSec + introBars * barSec
    val outroStartSec: Double get() = leadingSilenceSec + (bars - outroBars) * barSec
    val musicEndSec: Double get() = leadingSilenceSec + bars * barSec
    val durationSec: Double get() = musicEndSec + trailingSilenceSec

    /** Beat times in seconds (ground truth), including the intro. */
    fun beatTimes(): DoubleArray = DoubleArray(bars * beatsPerBar) { leadingSilenceSec + it * beatSec }
    fun downbeatTimes(): DoubleArray = DoubleArray(bars) { leadingSilenceSec + it * barSec }

    /** Scale degrees (semitones from tonic) for the mode. */
    private fun scale(): IntArray = if (mode == Mode.MAJOR) intArrayOf(0, 2, 4, 5, 7, 9, 11) else intArrayOf(0, 2, 3, 5, 7, 8, 10)

    /** Root degree index (0-based) of the chord for a bar and whether it's minor. */
    fun chordForBar(bar: Int): Pair<Int, Boolean> {
        val prog = if (mode == Mode.MAJOR) listOf(0 to false, 4 to false, 5 to true, 3 to false) else listOf(0 to true, 5 to false, 2 to false, 6 to false)
        return prog[bar % prog.size]
    }

    private fun chordMidi(bar: Int, octave: Int): IntArray {
        val sc = scale()
        val (deg, _) = chordForBar(bar)
        // Triad built from scale degrees deg, deg+2, deg+4 (diatonic), so quality follows the scale.
        val root = 12 * octave + tonic
        return intArrayOf(root + sc[deg % 7] + 12 * (deg / 7), root + sc[(deg + 2) % 7] + 12 * ((deg + 2) / 7), root + sc[(deg + 4) % 7] + 12 * ((deg + 4) / 7))
    }

    fun render(): AudioBuffer {
        val sr = sampleRate
        val n = Math.round(durationSec * sr).toInt()
        val left = FloatArray(n)
        val right = FloatArray(n)
        val rnd = Random(seed)
        val beatFrames = beatSec * sr

        for (bar in 0 until bars) {
            val barStart = leadingSilenceSec + bar * barSec
            val inIntro = bar < introBars
            val inOutro = bar >= bars - outroBars
            val chord = chordMidi(bar, 4) // pad around C4
            val bassMidi = chordMidi(bar, 2)[0] // bass root around C2

            // Pad: sustained triad, gentle attack/release, slightly detuned stereo.
            renderPad(left, right, sr, barStart, barSec, chord)

            if (!inIntro) {
                // Bass: root note, one per beat, short decay.
                for (b in 0 until beatsPerBar) {
                    val t = barStart + b * beatSec
                    renderBass(left, right, sr, t, beatSec * 0.9, Synth.midiToHz(bassMidi.toDouble()))
                }
            }
            val drums = !inIntro && !(inOutro && outroBars > 0)
            if (drums) {
                for (b in 0 until beatsPerBar) {
                    val t = barStart + b * beatSec
                    val s = Math.round(t * sr).toInt()
                    renderKick(left, right, sr, s, if (b == 0) 0.9f else 0.75f)
                    // hats on 8ths, velocity jitter for realism
                    for (h in 0 until 2) {
                        val hs = Math.round((t + h * beatSec / 2) * sr).toInt()
                        val v = 0.18f + 0.05f * rnd.nextFloat()
                        addNoiseBurst(left, right, sr, hs, 0.02, v, rnd)
                    }
                    if (b == 1 || b == 3) renderSnare(left, right, sr, s, 0.5f, rnd)
                }
            }
        }
        if (outroFade && outroBars > 0) {
            val fadeStart = Math.round(outroStartSec * sr).toInt()
            val fadeEnd = Math.round(musicEndSec * sr).toInt()
            for (i in fadeStart until minOf(fadeEnd, n)) {
                val g = 1f - (i - fadeStart).toFloat() / (fadeEnd - fadeStart)
                left[i] *= g; right[i] *= g
            }
        }
        // Soft clip safety
        for (i in 0 until n) { left[i] = softClip(left[i]); right[i] = softClip(right[i]) }
        return if (stereo) AudioBuffer.stereo(sr, left, right) else AudioBuffer.mono(sr, FloatArray(n) { (left[it] + right[it]) * 0.5f })
    }

    private fun softClip(x: Float): Float = if (x > 0.95f) 0.95f + (x - 0.95f) * 0.1f else if (x < -0.95f) -0.95f + (x + 0.95f) * 0.1f else x

    private fun renderPad(l: FloatArray, r: FloatArray, sr: Int, startSec: Double, lenSec: Double, midi: IntArray) {
        val start = Math.round(startSec * sr).toInt()
        val len = Math.round(lenSec * sr).toInt()
        val attack = (0.05 * sr).toInt()
        val release = (0.08 * sr).toInt()
        for (m in midi) {
            val f = Synth.midiToHz(m.toDouble())
            val wl = 2.0 * PI * f * 0.998 / sr
            val wr = 2.0 * PI * f * 1.002 / sr
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= l.size) break
                val env = when {
                    i < attack -> i.toFloat() / attack
                    i > len - release -> (len - i).toFloat() / release
                    else -> 1f
                }
                val a = 0.08f * env
                l[idx] += (a * (sin(wl * i) + 0.3 * sin(2 * wl * i))).toFloat()
                r[idx] += (a * (sin(wr * i) + 0.3 * sin(2 * wr * i))).toFloat()
            }
        }
    }

    private fun renderBass(l: FloatArray, r: FloatArray, sr: Int, startSec: Double, lenSec: Double, f: Double) {
        val start = Math.round(startSec * sr).toInt()
        val len = Math.round(lenSec * sr).toInt()
        val w = 2.0 * PI * f / sr
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= l.size) break
            val env = exp(-i / (0.25 * sr)) * (if (i < 200) i / 200.0 else 1.0)
            // saw-ish via first 4 harmonics
            var s = 0.0
            for (h in 1..4) s += sin(h * w * i) / h
            val v = (0.22 * env * s).toFloat()
            l[idx] += v; r[idx] += v
        }
    }

    private fun renderKick(l: FloatArray, r: FloatArray, sr: Int, start: Int, amp: Float) {
        val len = (0.35 * sr).toInt()
        var phase = 0.0
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= l.size) break
            val t = i.toDouble() / sr
            val f = 45.0 + 110.0 * exp(-t * 28.0)
            phase += 2.0 * PI * f / sr
            val env = exp(-t * 9.0)
            val v = (amp * env * sin(phase)).toFloat()
            l[idx] += v; r[idx] += v
        }
    }

    private fun renderSnare(l: FloatArray, r: FloatArray, sr: Int, start: Int, amp: Float, rnd: Random) {
        val len = (0.18 * sr).toInt()
        val w = 2.0 * PI * 190.0 / sr
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= l.size) break
            val t = i.toDouble() / sr
            val env = exp(-t * 22.0)
            val v = (amp * env * (0.5 * sin(w * i) + 0.5 * (rnd.nextDouble() * 2 - 1))).toFloat()
            l[idx] += v * 0.9f; r[idx] += v
        }
    }

    private fun addNoiseBurst(l: FloatArray, r: FloatArray, sr: Int, start: Int, decaySec: Double, amp: Float, rnd: Random) {
        val len = (decaySec * 5 * sr).toInt()
        var hp = 0f
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= l.size) break
            val env = exp(-i / (decaySec * sr)).toFloat()
            val white = rnd.nextFloat() * 2f - 1f
            // crude one-pole high-pass to make it hat-like
            val out = white - hp; hp += 0.6f * (white - hp)
            l[idx] += amp * env * out; r[idx] += amp * env * out
        }
    }
}
