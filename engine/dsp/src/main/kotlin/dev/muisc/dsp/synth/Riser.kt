package dev.muisc.dsp.synth

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.filter.StateVariableFilter
import dev.muisc.dsp.filter.SvfMode
import dev.muisc.dsp.gain.Curves
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** How a [Riser] ends. */
enum class RiserEnding {
    /** The build runs to the last frame and stops dead (the drop / next track starts right after). */
    CUT,
    /** The build ends [RiserSpec.hitMs] before the end and a kick-like hit with a noise burst fills the rest. */
    HIT,
}

/**
 * Parameters of a [Riser]. The noise layer is white noise through a resonant band-pass whose centre glides
 * exponentially from [noiseStartHz] to [noiseEndHz]; the tone layer is a [toneWaveform] oscillator gliding
 * from [toneStartHz] to [toneEndHz]. Both are shaped by the exponential build curve
 * `(e^{k t} - 1) / (e^k - 1)` with `k` = [buildCurveK] (see [Curves.exponential]).
 */
data class RiserSpec(
    val noiseStartHz: Double = 200.0,
    val noiseEndHz: Double = 6000.0,
    val noiseQ: Double = 2.0,
    val noiseLevel: Double = 0.5,
    val toneStartHz: Double = 110.0,
    val toneEndHz: Double = 440.0,
    val toneLevel: Double = 0.25,
    val toneWaveform: Waveform = Waveform.SAW,
    val buildCurveK: Double = 3.0,
    val ending: RiserEnding = RiserEnding.CUT,
    val hitMs: Double = 250.0,
    val seed: Int = 1,
)

/**
 * Riser / uplifter generator for transitions: band-passed noise with a rising centre frequency plus a
 * pitch-gliding tone, with an exponential build, over an exact number of frames, ending on a cut or a hit.
 */
object Riser {
    /** Renders exactly [frames] frames ([channels] copies of the same mono signal). */
    fun render(sampleRate: Int, frames: Int, spec: RiserSpec = RiserSpec(), channels: Int = 1): AudioBuffer {
        require(sampleRate > 0 && frames >= 0 && channels > 0) { "invalid sampleRate/frames/channels" }
        val mono = FloatArray(frames)
        val hitFrames = if (spec.ending == RiserEnding.HIT) minOf(frames, Math.round(spec.hitMs * sampleRate / 1000.0).toInt()) else 0
        val rise = frames - hitFrames
        if (rise > 0) {
            val rnd = Random(spec.seed)
            val noise = FloatArray(rise) { rnd.nextFloat() * 2f - 1f }
            val bp = StateVariableFilter(sampleRate, 1, spec.noiseStartHz, spec.noiseQ)
            bp.mode = SvfMode.BAND_PASS
            bp.setCutoffRamp(spec.noiseStartHz, spec.noiseEndHz, rise, exponential = true)
            // The SVF processes from offset 0 of its arrays, so the ramp is run block by block through scratch buffers.
            val block = 256
            val scratchIn = FloatArray(block)
            val scratchOut = FloatArray(block)
            var done = 0
            while (done < rise) {
                val n = minOf(block, rise - done)
                System.arraycopy(noise, done, scratchIn, 0, n)
                bp.process(scratchIn, scratchOut, n)
                System.arraycopy(scratchOut, 0, mono, done, n)
                done += n
            }
            val osc = Oscillator(sampleRate, spec.toneWaveform)
            osc.setGlide(spec.toneStartHz, spec.toneEndHz, rise, exponential = true)
            val nl = spec.noiseLevel.toFloat()
            val tl = spec.toneLevel.toFloat()
            val inv = 1.0 / rise
            for (i in 0 until rise) {
                val g = Curves.exponential(i * inv, spec.buildCurveK).toFloat()
                mono[i] = (nl * mono[i] + tl * osc.nextSample()) * g
            }
        }
        if (hitFrames > 0) {
            val rnd = Random(spec.seed + 1)
            var phase = 0.0
            for (i in 0 until hitFrames) {
                val t = i.toDouble() / sampleRate
                val f = 50.0 + 120.0 * exp(-t * 30.0)
                phase += 2.0 * PI * f / sampleRate
                val kick = 0.8 * exp(-t * 12.0) * sin(phase)
                val burst = 0.4 * exp(-t * 25.0) * (rnd.nextDouble() * 2.0 - 1.0)
                mono[rise + i] = (kick + burst).toFloat()
            }
        }
        return AudioBuffer(sampleRate, Array(channels) { if (it == 0) mono else mono.copyOf() })
    }
}
