package dev.muisc.transitions.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExprTest {
    private val scope = mapOf("bars" to 16.0, "swapBar" to 8.0, "beat" to 0.25, "off" to -120.0)
    private fun ev(s: String) = Expr(s).eval { scope[it] }

    @Test fun numbersAndPrecedence() {
        assertEquals(8.0, ev("8"))
        assertEquals(-3.5, ev("-3.5"))
        assertEquals(1e3, ev("1e3"))
        assertEquals(14.0, ev("2 + 3 * 4"))
        assertEquals(20.0, ev("(2 + 3) * 4"))
        assertEquals(-4.0, ev("-(2 + 2)"))
        assertEquals(2.0, ev("8 / 4"))
    }

    @Test fun namesAndFunctions() {
        assertEquals(8.25, ev("swapBar + beat"))
        assertEquals(15.0, ev("bars - 1"))
        assertEquals(8.0, ev("max(4, bars / 2)"))
        assertEquals(4.0, ev("min(bars, 4, 12)"))
        assertEquals(10.0, ev("clamp(bars, 2, 10)"))
        assertEquals(3.0, ev("round(2.6)"))
        assertEquals(-120.0, ev("off"))
        assertEquals(setOf("swapBar", "beat", "bars"), Expr("swapBar + beat * bars").names())
    }

    @Test fun errorsNameThePosition() {
        val unknown = assertFailsWith<ExprException> { ev("swapbar + 1") }
        assertEquals(0, unknown.position)
        assertTrue(unknown.message!!.contains("unknown name 'swapbar'"))
        assertFailsWith<ExprException> { ev("1 / (bars - 16)") }
        assertFailsWith<ExprException> { ev("2 +") }
        assertFailsWith<ExprException> { ev("clamp(1, 2)") }
        assertFailsWith<ExprException> { ev("foo(1)") }
        assertFailsWith<ExprException> { ev("") }
        assertFailsWith<ExprException> { ev("3 4") }
    }
}
