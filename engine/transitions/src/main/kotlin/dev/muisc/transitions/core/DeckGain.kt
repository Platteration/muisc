package dev.muisc.transitions.core

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.gain.Curves
import dev.muisc.transitions.TransitionPrefs

/**
 * Deck gain: the one static gain each deck carries everywhere.
 *
 * `of(analysis, prefs) = clamp(prefs.targetLufs - analysis.loudness.integratedLufs, -12, +6)` dB, 0 dB when
 * `targetLufs` is NaN (loudness matching off) or the analysis has no usable loudness.
 *
 * **Who applies it.** The player applies the deck gain to every `Segment.Body`; the renderer applies the *identical*
 * gain to both decoded windows before calling `TransitionStrategy.render`, so `TransitionInput.aAudio` / `bAudio`
 * already carry it. "Unity gain" in the splice contract of `TransitionPlan` therefore means "deck gain applied,
 * nothing else": a strategy copies the input samples verbatim into its pre-roll / post-roll and never performs
 * loudness matching of its own — both decks are already matched. In `PlaybackContext.ALBUM` all tracks of an album
 * share one gain (that of the loudest track) so album dynamics are preserved; that choice is made by the program
 * builder, not here.
 */
object DeckGain {
    const val MIN_DB: Float = -12f
    const val MAX_DB: Float = 6f

    /** Deck gain in dB for [analysis] under [prefs] (see the object doc). */
    fun of(analysis: TrackAnalysis, prefs: TransitionPrefs): Float = of(analysis.loudness.integratedLufs.toDouble(), prefs.targetLufs)

    /** Deck gain in dB from a measured integrated loudness and a target (NaN target or non-finite loudness = 0 dB). */
    fun of(integratedLufs: Double, targetLufs: Double): Float {
        if (targetLufs.isNaN() || !integratedLufs.isFinite()) return 0f
        return (targetLufs - integratedLufs).toFloat().coerceIn(MIN_DB, MAX_DB)
    }

    /** Linear factor of a gain in dB (exactly 1.0 for 0 dB). */
    fun linear(db: Float): Float = if (db == 0f) 1f else Curves.dbToLinear(db)

    /** Multiplies every sample of [buffer] by `linear(db)` in place (a no-op for 0 dB). Returns the buffer. */
    fun applyInPlace(buffer: AudioBuffer, db: Float): AudioBuffer {
        if (db == 0f) return buffer
        return buffer.applyGainInPlace(linear(db))
    }
}
