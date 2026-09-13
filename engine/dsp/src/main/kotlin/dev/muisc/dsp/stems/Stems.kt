package dev.muisc.dsp.stems

import dev.muisc.audio.AudioBuffer

enum class StemKind { DRUMS, BASS, VOCALS, OTHER }

enum class StemQuality {
    /** Signal-processing approximation (crossover bands + harmonic/percussive separation). Leaks between stems. */
    PSEUDO,
    /** Neural source separation (e.g. Demucs via ONNX Runtime on Android). */
    ML,
}

/**
 * Separated stems of one buffer. Invariant: `drums + bass + vocals + other == original` within -60 dBFS,
 * so any strategy can rebuild the full mix by summing and can "remove" a stem by subtraction.
 */
class Stems(
    val sampleRate: Int,
    val drums: AudioBuffer,
    val bass: AudioBuffer,
    val vocals: AudioBuffer,
    val other: AudioBuffer,
    val quality: StemQuality,
) {
    init {
        val f = drums.frames
        require(listOf(bass, vocals, other).all { it.frames == f && it.channelCount == drums.channelCount }) { "stems must have identical shapes" }
    }

    val frames: Int get() = drums.frames
    val channelCount: Int get() = drums.channelCount

    operator fun get(kind: StemKind): AudioBuffer = when (kind) {
        StemKind.DRUMS -> drums; StemKind.BASS -> bass; StemKind.VOCALS -> vocals; StemKind.OTHER -> other
    }

    /** Sum of the given stems (all four reconstructs the original). */
    fun mix(vararg kinds: StemKind): AudioBuffer {
        val out = AudioBuffer.silence(sampleRate, channelCount, frames)
        for (k in kinds) {
            val s = this[k]
            for (c in 0 until channelCount) { val o = out[c]; val i = s[c]; for (n in 0 until frames) o[n] += i[n] }
        }
        return out
    }

    fun sum(): AudioBuffer = mix(StemKind.DRUMS, StemKind.BASS, StemKind.VOCALS, StemKind.OTHER)
}

/**
 * Splits audio into stems. Implementations:
 *  - `PseudoStemSeparator` (this module): Linkwitz-Riley crossovers + HPSS median masks; always available.
 *  - An ML separator in the Android app (ONNX Runtime) implementing the same interface.
 */
interface StemSeparator {
    val quality: StemQuality
    fun separate(audio: AudioBuffer): Stems
}

/**
 * Integration point for neural source separation (Android: ONNX Runtime + a Demucs-class model chosen by the user).
 * Contract: input any length up to ~120 s at the engine rate; output four stems of identical shape; `other` MUST be
 * computed as original - (drums + bass + vocals) so the [Stems] invariant holds regardless of model residual.
 */
interface MlStemSeparator : StemSeparator {
    val modelId: String
    /** False when the model/runtime is not installed; callers then fall back to a PSEUDO separator. */
    val available: Boolean
}
