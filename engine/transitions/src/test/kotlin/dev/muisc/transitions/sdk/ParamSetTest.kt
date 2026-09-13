package dev.muisc.transitions.sdk

import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import dev.muisc.transitions.TransitionPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParamSetTest {
    private object P : ParamSet("demo") {
        val overlapBars = int("overlapBars", "Overlap", 16, 4, 64, "bars")
        val fadeSec = double("fadeSec", "Fade", 6.0, 1.0, 12.0, "s", "fade length")
        val keyLock = bool("keyLock", "Key lock", true)
        val law = choice("law", "Law", FadeLaw.EQUAL_POWER, FadeLaw.entries)
        val mode = choice("mode", "Mode", "b", listOf("a", "b"))
    }

    @Test
    fun specsInDeclarationOrderWithDefaults() {
        assertEquals(listOf("overlapBars", "fadeSec", "keyLock", "law", "mode"), P.specs.map { it.id })
        assertEquals("demo", P.strategyId)
        assertTrue(P.spec("fadeSec") is ParamSpec.DoubleSpec)
        assertEquals("s", (P.spec("fadeSec") as ParamSpec.DoubleSpec).unit)
        assertEquals(listOf("LINEAR", "EQUAL_POWER", "S_CURVE", "EXP"), P.law.choices)
        val d = P.defaults()
        assertEquals(16, d.int(P.overlapBars)); assertEquals(6.0, d.double(P.fadeSec)); assertEquals(true, d.bool(P.keyLock))
        assertEquals("EQUAL_POWER", d.choice(P.law)); assertEquals("b", d.choice(P.mode))
        assertEquals(P.specs.size, d.values.size)
    }

    @Test
    fun resolvePrecedenceAndClamping() {
        val prefs = TransitionPrefs(paramOverrides = mapOf("demo" to mapOf("overlapBars" to "32", "fadeSec" to "3"), "other" to mapOf("overlapBars" to "8")))
        val r = P.resolve(Params.parse(listOf("fadeSec=99", "junk=1")), prefs)
        assertEquals(32, r.int(P.overlapBars), "prefs override beats the default")
        assertEquals(12.0, r.double(P.fadeSec), "explicit param beats the override and is clamped to the spec range")
        assertEquals(null, r["junk"], "unknown keys are dropped")
        assertEquals("true", r["keyLock"], "every spec is present after resolve")
        assertEquals(16, P.resolve(Params.EMPTY).int(P.overlapBars))
    }

    @Test
    fun rejectsDuplicateIdsAndBadDefaults() {
        assertFailsWith<IllegalArgumentException> { object : ParamSet("x") { val a = int("a", "A", 1, 0, 2); val b = int("a", "B", 1, 0, 2) } }
        assertFailsWith<IllegalArgumentException> { object : ParamSet("y") { val a = double("a", "A", 5.0, 0.0, 2.0) } }
    }
}
