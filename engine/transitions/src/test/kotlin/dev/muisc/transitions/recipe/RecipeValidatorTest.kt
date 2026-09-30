package dev.muisc.transitions.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipeValidatorTest {
    private val validator = RecipeValidator()

    private fun p(at: String, v: String, curve: RecipeCurve = RecipeCurve.LINEAR) = RecipePoint(Expr(at), Expr(v), curve)

    /** A clean beat-matched blend (total = 16 + 2 + 2 = 20 bars) that every test breaks in one way. */
    private val good = TransitionRecipe(
        id = "test-blend", name = "Test blend",
        vars = linkedMapOf("len" to RecipeVar(16.0, 8.0, 32.0, integer = true)),
        timing = RecipeTiming(lengthBars = Expr("len")),
        a = DeckRecipe(level = listOf(p("0", "1", RecipeCurve.EQUAL_POWER), p("bars", "0"))),
        b = DeckRecipe(
            level = listOf(p("0", "0", RecipeCurve.EQUAL_POWER), p("bars", "1")),
            low = listOf(p("bars / 2 - 1", "off", RecipeCurve.S_CURVE), p("bars / 2", "0")),
        ),
    )

    private fun check(r: TransitionRecipe): RecipeReport = validator.validate(r)
    private fun RecipeReport.error(path: String): RecipeProblem = assertNotNull(errors.firstOrNull { it.path == path }, "no error at $path in\n$this")
    private fun RecipeReport.warning(path: String): RecipeProblem = assertNotNull(warnings.firstOrNull { it.path == path }, "no warning at $path in\n$this")
    private fun RecipeReport.noneAt(path: String) = assertTrue(problems.none { it.path == path }, "unexpected problem at $path in\n$this")

    @Test fun theBaseRecipeIsClean() {
        val r = check(good)
        assertTrue(r.problems.isEmpty(), r.toString())
        assertTrue(r.valid)
    }

    @Test fun idPattern() {
        val bad = check(good.copy(id = "Club Swap!"))
        assertTrue(bad.error("id").message.contains("'club-swap'"), bad.toString())
        assertFalse(bad.valid)
        check(good.copy(id = "club-swap-2")).noneAt("id")
    }

    @Test fun nameMustNotBeBlank() {
        check(good.copy(name = "  ")).error("name")
        check(good.copy(name = "X")).noneAt("name")
    }

    @Test fun ambitionRange() {
        check(good.copy(ambition = 1.5)).error("ambition")
        check(good.copy(ambition = -0.1)).error("ambition")
        check(good.copy(ambition = 1.0)).noneAt("ambition")
    }

    @Test fun formatVersion() {
        check(good.copy(format = TransitionRecipe.FORMAT_VERSION + 1)).error("format")
        check(good.copy(format = TransitionRecipe.FORMAT_VERSION)).noneAt("format")
    }

    @Test fun variableRanges() {
        check(good.copy(vars = linkedMapOf("len" to RecipeVar(16.0, 32.0, 8.0, integer = true)))).error("vars.len")
        check(good.copy(vars = linkedMapOf("len" to RecipeVar(40.0, 8.0, 32.0, integer = true)))).error("vars.len.default")
        val reserved = check(good.copy(vars = linkedMapOf("len" to RecipeVar(16.0, 8.0, 32.0), "total" to RecipeVar(1.0, 0.0, 2.0))))
        assertTrue(reserved.error("vars.total").message.contains("built-in name"))
        check(good.copy(vars = linkedMapOf("len" to RecipeVar(16.0, 8.0, 32.0), "my.var" to RecipeVar(1.0, 0.0, 2.0)))).error("vars.my.var")
        // Warnings: a knob nobody uses, and one that cannot move.
        val unused = check(good.copy(vars = linkedMapOf("len" to RecipeVar(16.0, 8.0, 32.0), "spare" to RecipeVar(1.0, 1.0, 1.0))))
        assertTrue(unused.valid)
        assertTrue(unused.warnings.any { it.path == "vars.spare" && it.message.contains("changes nothing") }, unused.toString())
        assertTrue(unused.warnings.any { it.path == "vars.spare" && it.message.contains("cannot be moved") }, unused.toString())
    }

    @Test fun laneValuesStayInTheirRange() {
        val loud = good.copy(a = good.a.copy(level = listOf(p("0", "1"), p("4", "3"), p("bars", "0"))))
        assertTrue(check(loud).error("a.level[1].v").message.contains("0 to 2"))
        check(good.copy(a = good.a.copy(level = listOf(p("0", "1"), p("4", "2"), p("bars", "0"))))).noneAt("a.level[1].v")
        check(good.copy(b = good.b.copy(hpf = listOf(p("0", "10"), p("4", "20"))))).error("b.hpf[0].v")
        check(good.copy(b = good.b.copy(high = listOf(p("0", "15"), p("4", "0"))))).error("b.high[0].v")
        check(good.copy(b = good.b.copy(high = listOf(p("0", "12"), p("4", "0"))))).noneAt("b.high[0].v")
        val send = good.copy(a = good.a.copy(echo = EchoRecipe(send = listOf(p("2", "0"), p("4", "1.5"), p("5", "0")))))
        check(send).error("a.echo.send[1].v")
    }

    @Test fun positionsStayInsideTheTransition() {
        val late = good.copy(b = good.b.copy(mid = listOf(p("2", "-3"), p("total + 1", "0"))))
        assertTrue(check(late).error("b.mid[1].at").message.contains("total = 20"), check(late).toString())
        check(good.copy(b = good.b.copy(mid = listOf(p("-1", "-3"), p("4", "0"))))).error("b.mid[0].at")
        check(good.copy(b = good.b.copy(mid = listOf(p("0", "-3"), p("total", "0"))))).noneAt("b.mid[1].at")
    }

    @Test fun boundaryRuleForDeckA() {
        // Deck A must be untouched at bar 0: EQ, filters, sends and freeze included.
        val eq = check(good.copy(a = good.a.copy(low = listOf(p("0", "-6"), p("4", "0")))))
        assertTrue(eq.error("a.low").message.contains("deck A must start"), eq.toString())
        check(good.copy(a = good.a.copy(hpf = listOf(p("2", "300"), p("4", "20"))))).error("a.hpf") // held before its first point
        check(good.copy(a = good.a.copy(echo = EchoRecipe(send = listOf(p("0", "0.5"), p("2", "0")))))).error("a.echo.send")
        check(good.copy(a = good.a.copy(reverb = ReverbRecipe(freeze = listOf(p("0", "1"), p("2", "0")))))).error("a.reverb.freeze")
        check(good.copy(a = good.a.copy(reverb = ReverbRecipe(send = listOf(p("0", "0.2")))))).error("a.reverb.send")
        // A vertical jump right at bar 0 is a click at the start, even though the value AT bar 0 is neutral.
        val jump = check(good.copy(a = good.a.copy(mid = listOf(p("0", "0"), p("0", "-6"), p("4", "0")))))
        assertTrue(jump.error("a.mid").message.contains("jumps"), jump.toString())
        // Passing: the change starts at bar 0 from neutral.
        check(good.copy(a = good.a.copy(low = listOf(p("0", "0"), p("4", "-6"))))).noneAt("a.low")
        check(good.copy(a = good.a.copy(hpf = listOf(p("2", "20"), p("4", "300"))))).noneAt("a.hpf")
    }

    @Test fun boundaryRuleForDeckB() {
        val filter = check(good.copy(b = good.b.copy(hpf = listOf(p("0", "400"), p("bars", "200")))))
        assertTrue(filter.error("b.hpf").message.contains("deck B must end"), filter.toString())
        check(good.copy(b = good.b.copy(level = listOf(p("0", "0"), p("bars", "0.8"))))).error("b.level")
        check(good.copy(b = good.b.copy(reverb = ReverbRecipe(send = listOf(p("0", "0.3")))))).error("b.reverb.send")
        check(good.copy(b = good.b.copy(stems = StemsRecipe(vocals = listOf(p("0", "off")))))).error("b.stems.vocals")
        // A step that only lands on neutral AT total jumps at the seam.
        val step = check(good.copy(b = good.b.copy(mid = listOf(p("2", "-6", RecipeCurve.STEP), p("total", "0")))))
        assertTrue(step.error("b.mid").message.contains("jumps"), step.toString())
        // Passing: neutral at and before the end.
        check(good.copy(b = good.b.copy(hpf = listOf(p("0", "400"), p("bars", "20"))))).noneAt("b.hpf")
        check(good.copy(b = good.b.copy(mid = listOf(p("2", "-6"), p("total", "0"))))).noneAt("b.mid")
    }

    @Test fun deckALevelShouldReachZeroByBars() {
        val r = check(good.copy(a = good.a.copy(level = listOf(p("0", "1"), p("bars", "0.5")))))
        assertTrue(r.valid, r.toString())
        assertTrue(r.warning("a.level").message.contains("declick"))
        check(good).noneAt("a.level")
    }

    @Test fun sendsShouldBeBackToZeroABarBeforeTheEnd() {
        fun withSend(vararg points: RecipePoint) = good.copy(a = good.a.copy(echo = EchoRecipe(send = points.toList())))
        val late = check(withSend(p("bars - 2", "0"), p("bars", "1"), p("total - beat", "0")))
        assertTrue(late.valid, late.toString())
        assertTrue(late.warning("a.echo.send").message.contains("force") || late.warning("a.echo.send").message.contains("cut it"), late.toString())
        val never = check(withSend(p("bars - 2", "0"), p("bars", "1")))
        assertTrue(never.warning("a.echo.send").message.contains("at the end"))
        check(withSend(p("bars - 2", "0"), p("bars", "1"), p("total - 1", "0"))).noneAt("a.echo.send")
        // The freeze counts too: a frozen tail never dies away.
        val frozen = good.copy(a = good.a.copy(reverb = ReverbRecipe(freeze = listOf(p("0", "0", RecipeCurve.STEP), p("bars", "1")))))
        check(frozen).warning("a.reverb.freeze")
        val released = good.copy(a = good.a.copy(reverb = ReverbRecipe(freeze = listOf(p("0", "0", RecipeCurve.STEP), p("bars - 1", "1", RecipeCurve.STEP), p("bars + 1", "0")))))
        check(released).noneAt("a.reverb.freeze")
    }

    @Test fun echoFeedbackAndBeats() {
        fun echo(fb: String, beats: String = "0.75") = good.copy(a = good.a.copy(echo = EchoRecipe(beats = Expr(beats), feedback = Expr(fb))))
        check(echo("0.97")).error("a.echo.feedback")
        check(echo("-0.1")).error("a.echo.feedback")
        check(echo("0.95")).noneAt("a.echo.feedback")
        check(echo("0.7", "0")).error("a.echo.beats")
        check(echo("0.7", "0.5")).noneAt("a.echo.beats")
    }

    @Test fun resonanceRange() {
        check(good.copy(a = good.a.copy(resonance = Expr("7")))).error("a.resonance")
        check(good.copy(b = good.b.copy(resonance = Expr("0.4")))).error("b.resonance")
        check(good.copy(a = good.a.copy(resonance = Expr("6")))).noneAt("a.resonance")
        check(good.copy(a = good.a.copy(resonance = Expr("0.5")))).noneAt("a.resonance")
    }

    @Test fun energyDeltaOrder() {
        check(good.copy(rules = RecipeRules(minEnergyDelta = 0.5, maxEnergyDelta = 0.2))).error("rules.minEnergyDelta")
        check(good.copy(rules = RecipeRules(minEnergyDelta = 0.2, maxEnergyDelta = 0.2))).noneAt("rules.minEnergyDelta")
        check(good.copy(rules = RecipeRules(baseScore = 1.2))).error("rules.baseScore")
    }

    @Test fun modifiersMustBeKnown() {
        val typo = check(good.copy(modifiers = listOf("textureCary")))
        assertTrue(typo.error("modifiers[0]").message.contains("did you mean 'textureCarry'?"), typo.toString())
        check(good.copy(modifiers = listOf("textureCarry", "tempoGlide"))).noneAt("modifiers[0]")
        // The known list is the caller's.
        val custom = RecipeValidator(knownModifiers = setOf("sparkle"))
        assertTrue(custom.validate(good.copy(modifiers = listOf("sparkle"))).valid)
        assertFalse(custom.validate(good.copy(modifiers = listOf("textureCarry"))).valid)
    }

    @Test fun stemsAreAWarning() {
        val r = check(good.copy(b = good.b.copy(stems = StemsRecipe(drums = listOf(p("0", "0"), p("4", "0"))))))
        assertTrue(r.valid, r.toString())
        assertTrue(r.warning("b.stems").message.contains("pseudo-stems"))
        check(good).noneAt("b.stems")
    }

    @Test fun everyBadExpressionIsReportedNotJustTheFirst() {
        val r = check(
            good.copy(
                a = good.a.copy(level = listOf(p("0", "1"), p("barz", "0"))),
                b = good.b.copy(low = listOf(p("bars / 2 - 1", "of"), p("bars / 2 +", "0"))),
                timing = good.timing.copy(lengthBars = Expr("bars")),
            ),
        )
        assertTrue(r.error("a.level[1].at").message.contains("did you mean 'bars'?"), r.toString())
        assertTrue(r.error("b.low[0].v").message.contains("did you mean 'off'?"), r.toString())
        assertTrue(r.error("b.low[1].at").message.contains("cannot read the expression"), r.toString())
        assertTrue(r.error("timing.lengthBars").message.contains("overlap length"), r.toString())
        // A division that is only zero for some values is not a syntax error.
        check(good.copy(b = good.b.copy(mid = listOf(p("0", "-12 / (len - 15)"), p("4", "0"))))).noneAt("b.mid[0].v")
    }

    @Test fun resolverRefusalsAreErrorsWithTheirPath() {
        val r = check(good.copy(timing = good.timing.copy(lowHz = Expr("5000"), highHz = Expr("4000"))))
        assertTrue(r.error("timing").message.contains("crossovers"), r.toString())
    }

    @Test fun gapInNoneTempoIsAWarning() {
        val none = good.copy(timing = RecipeTiming(lengthBars = Expr("len"), tempo = RecipeTempo.NONE, bEntersAtBar = Expr("bars + 2")))
        val r = check(none)
        assertTrue(r.valid, r.toString())
        assertTrue(r.warning("timing.bEntersAtBar").message.contains("music stops"))
        check(good.copy(timing = RecipeTiming(lengthBars = Expr("len"), tempo = RecipeTempo.NONE, bEntersAtBar = Expr("bars")))).noneAt("timing.bEntersAtBar")
    }

    @Test fun bEntersAtBarOutsideNoneTempoIsAWarning() {
        val r = check(good.copy(timing = good.timing.copy(bEntersAtBar = Expr("4"))))
        assertTrue(r.valid && r.warning("timing.bEntersAtBar").message.contains("only used in"), r.toString())
        check(good.copy(timing = good.timing.copy(bEntersAtBar = Expr("0.0")))).noneAt("timing.bEntersAtBar")
    }

    @Test fun pointsOutOfTimeOrderAreAWarning() {
        val r = check(good.copy(b = good.b.copy(mid = listOf(p("4", "0"), p("2", "-6")))))
        assertTrue(r.warning("b.mid").message.contains("not in time order"), r.toString())
    }

    // ---- every setting ------------------------------------------------------------------------------------------

    /** Fine at the defaults; at swapBar's maximum the handover lands after the end (total = 20). */
    private val brokenAtMax = good.copy(
        vars = linkedMapOf("len" to RecipeVar(16.0, 8.0, 16.0, integer = true), "swapBar" to RecipeVar(8.0, 2.0, 31.0, integer = true)),
        b = good.b.copy(low = listOf(p("swapBar", "off"), p("swapBar + beat", "0"))),
    )

    @Test fun aRecipeBrokenOnlyAtAVariablesMaximumIsCaught() {
        // At the defaults alone it is fine.
        val atDefaults = RecipeResolver.resolve(brokenAtMax)
        assertEquals(0.0, atDefaults.b.low.valueAt(atDefaults.totalBars))
        val r = check(brokenAtMax)
        assertFalse(r.valid, r.toString())
        val boundary = r.error("b.low")
        assertEquals("swapBar = 31", boundary.setting)
        assertTrue(boundary.message.startsWith("with swapBar = 31: deck B must end"), boundary.message)
        val position = r.error("b.low[0].at")
        assertEquals("swapBar = 31", position.setting)
        // Every problem names the setting that breaks it; none claims to happen at the defaults.
        assertTrue(r.errors.all { it.setting != null }, r.toString())
        // With the knob's range fixed, the recipe is valid.
        assertTrue(check(brokenAtMax.copy(vars = brokenAtMax.vars + ("swapBar" to RecipeVar(8.0, 2.0, 14.0, integer = true)))).valid)
    }

    @Test fun aVariableAtItsMinimumIsChecked() {
        // With len = 8 the swap at bar 12 lies after the end (total = 12).
        val r = check(good.copy(b = good.b.copy(low = listOf(p("12", "off"), p("12.25", "0")))))
        assertEquals("len = 8", r.error("b.low").setting)
    }

    @Test fun everyKnobAtItsMaximumIsChecked() {
        // Each knob alone at its max keeps the point inside (9 + 4 = 13, 4 + 12 = 16 <= 20); both at once do not (21 > 20).
        val r = check(
            good.copy(
                vars = linkedMapOf("len" to RecipeVar(16.0, 16.0, 16.0, integer = true), "x" to RecipeVar(4.0, 0.0, 9.0), "y" to RecipeVar(4.0, 0.0, 12.0)),
                b = good.b.copy(mid = listOf(p("0", "-3"), p("x + y", "0"))),
            ),
        )
        val e = r.error("b.mid[1].at")
        assertTrue(e.message.startsWith("with every knob at its maximum (len = 16, x = 9, y = 12)"), e.message)
    }
}
