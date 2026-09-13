package dev.muisc.dsp.synth

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Parameters of a [Pad]. Each MIDI note is played by [unison] voices detuned evenly across ±[detuneCents] and
 * spread across the stereo field by [stereoSpread] (0 = centre, 1 = hard left/right); the sum goes through a
 * state-variable low-pass at [cutoffHz] / [resonance] and a slow attack / release envelope.
 */
data class PadSpec(
    val waveform: Waveform = Waveform.SAW,
    val unison: Int = 3,
    val detuneCents: Double = 8.0,
    val cutoffHz: Double = 1800.0,
    val resonance: Double = 0.7071067811865476,
    val attackSec: Double = 1.0,
    val releaseSec: Double = 1.0,
    val level: Double = 0.25,
    val stereoSpread: Double = 0.8,
    val seed: Int = 1,
)

/**
 * Chord pad generator ("ambient bridge" bed): a chord of detuned band-limited saws (or sines/squares) through a
 * low-pass with slow attack and release, rendered to an exact length.
 *
 * Voices start at seeded random phases (no phase-coherent "click" at the start), the envelope is a linear
 * attack over [PadSpec.attackSec], sustain, and a linear release over the final [PadSpec.releaseSec] (the last
 * frame is exactly silent). Output level is `level / sqrt(notes * unison)` per voice (voices are decorrelated
 * by the detune, so their powers add). With `channels = 1` the stereo image is summed to mono.
 */
object Pad {
    /** Renders exactly [frames] frames of the chord [midiNotes] (MIDI note numbers). */
    fun render(sampleRate: Int, frames: Int, midiNotes: IntArray, spec: PadSpec = PadSpec(), channels: Int = 2): AudioBuffer {
        require(sampleRate > 0 && frames >= 0 && channels > 0) { "invalid sampleRate/frames/channels" }
        require(midiNotes.isNotEmpty() && spec.unison >= 1) { "need at least one note and one unison voice" }
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val rnd = Random(spec.seed)
        val voiceGain = (spec.level / sqrt((midiNotes.size * spec.unison).toDouble())).toFloat()
        var voiceIndex = 0
        for (note in midiNotes) {
            val base = Synth.midiToHz(note.toDouble())
            for (u in 0 until spec.unison) {
                val pos = if (spec.unison == 1) 0.0 else 2.0 * u / (spec.unison - 1) - 1.0   // -1..1
                val cents = spec.detuneCents * pos
                val f = base * 2.0.pow(cents / 1200.0)
                val pan = if (voiceIndex % 2 == 0) pos else -pos                            // alternate per note
                val theta = PI / 4 * (1.0 + spec.stereoSpread.coerceIn(0.0, 1.0) * pan)
                val gl = (cos(theta) * voiceGain).toFloat()
                val gr = (sin(theta) * voiceGain).toFloat()
                val osc = Oscillator(sampleRate, spec.waveform)
                osc.setFrequency(f)
                osc.reset(rnd.nextDouble())
                for (i in 0 until frames) {
                    val s = osc.nextSample()
                    left[i] += s * gl
                    right[i] += s * gr
                }
                voiceIndex++
            }
        }
        val lp = StateVariableFilter(sampleRate, 2, spec.cutoffHz, spec.resonance)
        lp.mode = SvfMode.LOW_PASS
        val buf = arrayOf(left, right)
        if (frames > 0) lp.process(buf, buf, frames)
        // Envelope: linear attack, sustain, linear release ending at exactly zero.
        val attack = Math.round(spec.attackSec * sampleRate).toInt().coerceIn(0, frames)
        val release = Math.round(spec.releaseSec * sampleRate).toInt().coerceIn(0, frames)
        for (i in 0 until frames) {
            var g = 1f
            if (attack > 0 && i < attack) g = i.toFloat() / attack
            if (release > 0 && i >= frames - release) g *= (frames - 1 - i).toFloat() / release
            left[i] *= g; right[i] *= g
        }
        return when (channels) {
            1 -> AudioBuffer(sampleRate, arrayOf(FloatArray(frames) { 0.5f * (buf[0][it] + buf[1][it]) }))
            else -> AudioBuffer(sampleRate, Array(channels) { c -> if (c == 0) left else if (c == 1) right else FloatArray(frames) { 0.5f * (left[it] + right[it]) } })
        }
    }
}
