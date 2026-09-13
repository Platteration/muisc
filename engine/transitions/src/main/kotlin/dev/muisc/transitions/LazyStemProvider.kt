package dev.muisc.transitions

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.stems.StemSeparator
import dev.muisc.dsp.stems.Stems

/**
 * [StemProvider] over the two decoded windows that separates each side on first use and caches the result, so a
 * strategy that never asks for stems never pays for them and one that asks twice pays once. [precompute]
 * separates what the plan's [StemNeed] declares up front (the renderer calls it between its cancellable stages).
 *
 * The windows are separated *as handed to the strategy* (deck gain applied), so `stems.sum()` reproduces
 * `aAudio` / `bAudio` within the [Stems] invariant. Thread-safe per side (a request from a second thread waits for
 * the first separation); the [separator] itself may hold streaming state and is only ever driven by one call at a
 * time.
 */
class LazyStemProvider(
    val aAudio: AudioBuffer,
    val bAudio: AudioBuffer,
    val separator: StemSeparator,
    val need: StemNeed = StemNeed.BOTH,
) : StemProvider {
    private val lock = Any()
    @Volatile private var a: Stems? = null
    @Volatile private var b: Stems? = null

    /** Whether A's stems have been computed yet. */
    val aComputed: Boolean get() = a != null
    /** Whether B's stems have been computed yet. */
    val bComputed: Boolean get() = b != null

    override fun aTail(): Stems = a ?: synchronized(lock) { a ?: separator.separate(aAudio).also { a = it } }

    override fun bHead(): Stems = b ?: synchronized(lock) { b ?: separator.separate(bAudio).also { b = it } }

    /** Separates the sides declared by [need] now (no-op for [StemNeed.NONE] or sides already computed). */
    fun precompute() {
        when (need) {
            StemNeed.NONE -> Unit
            StemNeed.A_TAIL -> aTail()
            StemNeed.B_HEAD -> bHead()
            StemNeed.BOTH -> { aTail(); bHead() }
        }
    }
}
