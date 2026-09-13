package dev.muisc.player

/**
 * Memory / real-time budget of the engine on one device class.
 *
 * @property maxRenderedSec longest rendered transition segment the coordinator accepts.
 * @property maxRenderedAlive rendered segments kept alive (the last one is retained so A→B→A does not re-render).
 * @property ringSec seconds of decoded audio each body deck buffers ahead of the audio thread.
 * @property blockFrames frames per audio-thread block.
 * @property seamFadeFrames linear micro-fade at every segment seam (240 = 5 ms at 48 kHz; a no-op on identical signals).
 * @property inexactSeamFadeFrames seam fade after an inexact seek or a user skip (960 = 20 ms at 48 kHz).
 */
data class EngineLimits(
    val maxRenderedSec: Double,
    val maxRenderedAlive: Int,
    val ringSec: Double,
    val blockFrames: Int,
    val seamFadeFrames: Int,
    val inexactSeamFadeFrames: Int,
) {
    init {
        require(maxRenderedSec > 0 && maxRenderedAlive >= 1 && ringSec > 0 && blockFrames > 0)
        require(seamFadeFrames >= 0 && inexactSeamFadeFrames >= 0)
    }

    companion object {
        val DESKTOP = EngineLimits(90.0, 3, 4.0, 1024, 240, 960)
        val PHONE = EngineLimits(48.0, 2, 2.0, 1024, 240, 960)
        val LOW_RAM = EngineLimits(20.0, 1, 1.5, 1024, 240, 960)
    }
}
