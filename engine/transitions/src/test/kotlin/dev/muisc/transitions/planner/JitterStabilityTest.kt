package dev.muisc.transitions.planner

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The planner's jitter must stay stable across releases: "same pair, same seed, same plan" is a promise users rely on
 * when they pin or compare transitions. The digest input is the four fields joined by single 0x00 bytes; this test
 * rebuilds those bytes independently of the planner's source text, so any change to the separator, the field order
 * or the encoding turns it red.
 */
class JitterStabilityTest {
    private fun reference(a: String, b: String, seed: Long, id: String): Double {
        val bytes = ByteArrayOutputStream().apply {
            write(a.toByteArray(Charsets.UTF_8)); write(0)
            write(b.toByteArray(Charsets.UTF_8)); write(0)
            write(seed.toString().toByteArray(Charsets.UTF_8)); write(0)
            write(id.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        var bits = 0L
        for (i in 0 until 8) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
        val unit = (bits ushr 11).toDouble() / (1L shl 53).toDouble()
        return 1.0 + DefaultTransitionPlanner.JITTER * (unit * 2.0 - 1.0)
    }

    @Test
    fun jitterHashesTheFieldsJoinedByNulBytes() {
        val cases = listOf(
            listOf("fpA", "fpB", "0", "crossfade"),
            listOf("b04af862048a9443df9719bb", "c1d2", "7", "bassSwap"),
            listOf("", "", "-1", "recipe:club-swap"),
            listOf("ünïcode", "日本", "42", "echoOut"),
        )
        for ((a, b, seed, id) in cases) {
            assertEquals(reference(a, b, seed.toLong(), id), DefaultTransitionPlanner.jitter(a, b, seed.toLong(), id), 0.0, "$a/$b/$seed/$id")
        }
        // The separator matters: without it "ab"+"c" and "a"+"bc" would collide.
        assertNotEquals(DefaultTransitionPlanner.jitter("ab", "c", 0, "x"), DefaultTransitionPlanner.jitter("a", "bc", 0, "x"))
    }
}
