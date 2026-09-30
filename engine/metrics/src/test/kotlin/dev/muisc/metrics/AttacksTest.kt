package dev.muisc.metrics

import dev.muisc.audio.AudioBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The attack function behind `beatAlignment*`: [Signals.analyticEnvelope] and [Signals.attacks]. */
class AttacksTest {
    private val sr = MetricsFixtures.SR

    /** A sinusoid's analytic envelope is its amplitude away from the buffer's ends, also across the FFT segments' seams (the buffer spans four). */
    @Test
    fun theAnalyticEnvelopeOfASineIsFlat() {
        val n = 200_000
        val x = FloatArray(n) { (0.5 * sin(2.0 * PI * 55.0 * it / sr)).toFloat() }
        val env = Signals.analyticEnvelope(x)
        var worst = 0.0
        for (i in 44100 until n - 44100) worst = maxOf(worst, abs(env[i] - 0.5).toDouble())
        assertTrue(worst < 2e-3, "envelope deviates from 0.5 by up to $worst")
        assertEquals(0, Signals.analyticEnvelope(FloatArray(0)).size)
    }

    /**
     * Four 45-155 Hz kicks over a sustained 55 Hz bass: the strongest attack near each kick is the kick, within 2 ms,
     * and between the kicks there is none - the waveform's own half-cycles, which a 1 ms RMS difference reports as
     * rises every few milliseconds, are not attacks.
     */
    @Test
    fun lowKicksHaveOneAttackEachAndTheirHalfCyclesNone() {
        val n = sr * 3
        val x = FloatArray(n) { (0.2 * sin(2.0 * PI * 55.0 * it / sr)).toFloat() }
        val kicks = doubleArrayOf(0.5, 1.0, 1.5, 2.0)
        for (t0 in kicks) {
            val s = Math.round(t0 * sr).toInt()
            var phase = 0.0
            for (i in 0 until (0.35 * sr).toInt()) {
                val t = i.toDouble() / sr
                phase += 2.0 * PI * (45.0 + 110.0 * exp(-t * 28.0)) / sr
                x[s + i] += (0.7 * exp(-t * 9.0) * sin(phase)).toFloat()
            }
        }
        val attacks = Signals.attacks(AudioBuffer.mono(sr, x))
        for (t0 in kicks) {
            val found = attacks.strongestNear(t0, 0.05)
            assertTrue(abs(found - t0) < 0.002, "kick at $t0 s found at $found s")
        }
        // Between the kicks nothing rises: the bass's own waveform is no attack.
        assertTrue(attacks.strongestNear(0.75, 0.2).isNaN(), "attack between kicks at ${attacks.strongestNear(0.75, 0.2)}")
    }
}
