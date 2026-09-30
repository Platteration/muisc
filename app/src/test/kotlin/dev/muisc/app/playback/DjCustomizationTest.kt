package dev.muisc.app.playback

import dev.muisc.audio.synth.SyntheticSong
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.Params
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.BuiltInPresets
import dev.muisc.transitions.custom.ContextBucket
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.custom.Rating
import dev.muisc.transitions.recipe.Expr
import dev.muisc.transitions.recipe.RecipeCodec
import dev.muisc.transitions.recipe.RecipeLibrary
import dev.muisc.transitions.recipe.RecipeOrigin
import dev.muisc.transitions.recipe.RecipeStatus
import dev.muisc.transitions.recipe.RecipeTiming
import dev.muisc.transitions.synthetic.SyntheticTracks
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DjCustomization] is pure JVM: these tests run it against a temporary profile directory and the real engine. */
class DjCustomizationTest {

    private fun tempDir(): File = Files.createTempDirectory("muisc-dj").toFile()

    private val builtInRecipeCount = 9
    private val builtInStrategyCount = 14

    // ---------------------------------------------------------------------------------------------------- recipes

    @Test
    fun builtInRecipesLoadThroughTheClassLoaderAndJoinTheRegistry() {
        val dj = DjCustomization(tempDir())
        // Nothing is read in the constructor: the 14 built-in strategies plan alone until the recipes are loaded.
        assertTrue(dj.recipesLoading)
        assertEquals(builtInStrategyCount, dj.registry.strategies.size)

        val set = dj.reloadRecipes()
        assertFalse(dj.recipesLoading)
        assertEquals(builtInRecipeCount, set.active.size)
        assertTrue(set.active.all { it.origin == RecipeOrigin.BUILT_IN })
        assertEquals(builtInStrategyCount + builtInRecipeCount, dj.registry.strategies.size)
        assertNotNull(dj.registry.strategy("recipe:smooth-blend"))
        // The planner sees the same registry object, so the recipes are candidates without rebuilding it.
        assertEquals(builtInStrategyCount + builtInRecipeCount, dj.planner.current.registry.strategies.size)
        assertEquals(emptyList(), dj.problems())
    }

    @Test
    fun aBrokenUserRecipeIsReportedAndSkippedWithoutHidingTheOthers() {
        val dir = tempDir()
        File(dir, "recipes").mkdirs()
        File(dir, "recipes/broken.json").writeText("{ \"id\": \"broken\", ")
        File(dir, "recipes/mine.json").writeText(RecipeCodec.encode(RecipeLibrary.starter("mine")))
        val dj = DjCustomization(dir)
        val set = dj.reloadRecipes()
        assertEquals(builtInRecipeCount + 1, set.active.size)
        assertNotNull(dj.registry.strategy("recipe:mine"))
        val problems = dj.problems()
        assertTrue(problems.any { "broken.json" in it }, "problems: $problems")
    }

    @Test
    fun aMissingBuiltInIndexIsAProblemNotACrash() {
        val empty = URLClassLoader(arrayOf(), null)
        val dj = DjCustomization(tempDir(), classLoader = empty)
        val set = dj.reloadRecipes()
        assertEquals(0, set.active.size)
        assertEquals(builtInStrategyCount, dj.registry.strategies.size)
        assertTrue(dj.problems().any { "index is missing" in it }, "problems: ${dj.problems()}")
    }

    @Test
    fun importSavesAValidRecipeAndPutsItInTheRegistry() {
        val dir = tempDir()
        val dj = DjCustomization(dir)
        dj.reloadRecipes()
        val outcome = dj.importRecipeText(RecipeCodec.encode(RecipeLibrary.starter("my-blend", "My blend")))
        val imported = assertIs<RecipeImport.Imported>(outcome)
        assertEquals("my-blend", imported.id)
        assertTrue(File(dir, "recipes/my-blend.json").isFile)
        assertNotNull(dj.registry.strategy("recipe:my-blend"))
    }

    @Test
    fun importNeverOverwritesTheUsersRecipeWithoutBeingAskedTo() {
        val dir = tempDir()
        val dj = DjCustomization(dir)
        dj.reloadRecipes()
        assertIs<RecipeImport.Imported>(dj.importRecipeText(RecipeCodec.encode(RecipeLibrary.starter("mine", "First"))))
        val second = RecipeCodec.encode(RecipeLibrary.starter("mine", "Second"))

        val conflict = assertIs<RecipeImport.Conflict>(dj.importRecipeText(second))
        assertEquals("First", conflict.existingName)
        assertTrue("\"First\"" in File(dir, "recipes/mine.json").readText(), "the first recipe must still be on disk")

        assertIs<RecipeImport.Imported>(dj.importRecipeText(second, replace = true))
        assertTrue("\"Second\"" in File(dir, "recipes/mine.json").readText())
    }

    @Test
    fun importOfTextThatIsNotARecipeReportsWhereAndWritesNothing() {
        val dir = tempDir()
        val dj = DjCustomization(dir)
        val outcome = assertIs<RecipeImport.Unreadable>(dj.importRecipeText("{\n  \"id\": \"x\",\n  oops\n}"))
        assertTrue(outcome.problems.isNotEmpty())
        assertNotNull(outcome.problems.first().line, "a syntax error must say which line")
        assertFalse(File(dir, "recipes").exists() && File(dir, "recipes").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun aRecipeWithErrorsIsOnlyImportedWhenAllowedAndIsNeverUsed() {
        val dir = tempDir()
        val dj = DjCustomization(dir)
        dj.reloadRecipes()
        val bad = RecipeLibrary.starter("too-long").copy(timing = RecipeTiming(lengthBars = Expr("200")))
        val text = RecipeCodec.encode(bad)

        val refused = assertIs<RecipeImport.HasErrors>(dj.importRecipeText(text))
        assertTrue(refused.problems.any { it.isError })
        assertFalse(File(dir, "recipes/too-long.json").exists())

        assertIs<RecipeImport.Imported>(dj.importRecipeText(text, allowErrors = true))
        assertTrue(File(dir, "recipes/too-long.json").isFile)
        assertEquals(RecipeStatus.INVALID, dj.recipeSet.all("too-long").single().status)
        assertNull(dj.registry.strategy("recipe:too-long"), "an invalid recipe must never reach the planner")
        assertTrue(dj.problems().any { "too-long" in it })
    }

    @Test
    fun duplicateFindsAFreeIdAndDeleteOnlyRemovesUserRecipes() {
        val dj = DjCustomization(tempDir())
        dj.reloadRecipes()
        assertTrue(dj.duplicateRecipe("smooth-blend").ok)
        assertTrue(dj.duplicateRecipe("smooth-blend").ok)
        assertNotNull(dj.registry.strategy("recipe:smooth-blend-copy"))
        assertNotNull(dj.registry.strategy("recipe:smooth-blend-copy-2"))

        assertTrue(dj.deleteRecipe("smooth-blend-copy").ok)
        assertNull(dj.registry.strategy("recipe:smooth-blend-copy"))
        assertFalse(dj.deleteRecipe("smooth-blend").ok, "built-ins cannot be deleted")
        assertNotNull(dj.registry.strategy("recipe:smooth-blend"))
    }

    // ---------------------------------------------------------------------------------------------------- styles

    @Test
    fun aChosenStyleShapesPrefsIsStoredAndSurvivesARestart() {
        val dir = tempDir()
        val dj = DjCustomization(dir)
        val base = TransitionPrefs()
        assertEquals(base, dj.shape(base), "no style and no active preset leaves the prefs alone")

        val before = dj.version.value
        dj.chooseStyle("club")
        assertNotEquals(before, dj.version.value, "a style change must be announced so the engine re-shapes")
        val shaped = dj.shape(base)
        assertEquals(0.7, shaped.energy)
        assertTrue(shaped.keyLock)
        assertEquals("tight-bass-swap", shaped.activePresets["bassSwap"])
        // The active preset is folded into paramOverrides, as the CLI does for --style.
        assertEquals(BuiltInPresets.byId("tight-bass-swap")!!.params.values, shaped.paramOverrides["bassSwap"])

        assertEquals("club", DjCustomization(dir).style?.id, "the choice is read back on the next start")

        dj.chooseStyle(null)
        assertEquals(base, dj.shape(base))
        assertNull(DjCustomization(dir).style)
    }

    @Test
    fun anUnknownStyleIsRefusedAndAVanishedOneIsReported() {
        val dir = tempDir()
        val dj = DjCustomization(dir)
        assertFailsWith<IllegalArgumentException> { dj.chooseStyle("no-such-style") }
        File(dir, "style").writeText("gone\n")
        val restarted = DjCustomization(dir)
        assertNull(restarted.style)
        assertTrue(restarted.problems().any { "gone" in it })
    }

    // ---------------------------------------------------------------------------------------------------- one-off override

    private fun pair(): Pair<TrackRef, TrackRef> {
        val a = SyntheticTracks.trackRef(SyntheticSong(bpm = 124.0, tonic = 9, bars = 48, introBars = 8, outroBars = 8)).trackRef
        val b = SyntheticTracks.trackRef(SyntheticSong(bpm = 124.0, tonic = 4, bars = 48, introBars = 8, outroBars = 8, seed = 11)).trackRef
        return a to b
    }

    @Test
    fun aSessionOverrideRanksTheChosenTechniqueFirstUntilCleared() {
        val dj = DjCustomization(tempDir())
        dj.reloadRecipes()
        val (a, b) = pair()
        val prefs = TransitionPrefs()
        val normal = dj.planner.planExplained(a, b, prefs)
        assertTrue(normal.ranked.candidates.size >= 2)
        val chosen = normal.ranked.candidates.last().strategy.id
        assertNotEquals(chosen, normal.ranked.best.strategy.id)

        dj.sessionPins.set(
            SessionOverride(1L, 2L, setOf(a.analysis.identity), setOf(b.analysis.identity), PairPin(a.analysis.identity, b.analysis.identity, chosen)),
        )
        val pinned = dj.planner.planExplained(a, b, prefs)
        assertEquals(chosen, pinned.ranked.best.strategy.id)
        assertEquals(true, pinned.explanation.pin?.used)

        // The reverse pair is a different transition.
        assertNull(dj.planner.planExplained(b, a, prefs).explanation.pin)

        dj.sessionPins.clear()
        assertEquals(normal.ranked.best.strategy.id, dj.planner.planExplained(a, b, prefs).ranked.best.strategy.id)
    }

    @Test
    fun aSessionOverrideAlsoMatchesTheStandInAnalysisOfTheSameSong() {
        val dj = DjCustomization(tempDir())
        val (a, b) = pair()
        val standIn = a.copy(analysis = a.analysis.copy(fingerprint = QueueManager.PLACEHOLDER_PREFIX + 7L, contentHash = ""))
        dj.sessionPins.set(
            SessionOverride(
                7L, 8L,
                aKeys = setOf(a.analysis.identity, QueueManager.PLACEHOLDER_PREFIX + 7L),
                bKeys = setOf(b.analysis.identity, QueueManager.PLACEHOLDER_PREFIX + 8L),
                pin = PairPin(a.analysis.identity, b.analysis.identity, "crossfade"),
            ),
        )
        assertEquals(true, dj.planner.planExplained(standIn, b, TransitionPrefs()).explanation.pin?.used)
        // ...but not the stand-in of some other song.
        val other = a.copy(analysis = a.analysis.copy(fingerprint = QueueManager.PLACEHOLDER_PREFIX + 9L, contentHash = ""))
        assertNull(dj.planner.planExplained(other, b, TransitionPrefs()).explanation.pin)
    }

    @Test
    fun storedPinsAreKeyedByIdentityAndSteerThePlanner() {
        val dir = tempDir()
        val dj = DjCustomization(dir)
        val (a, b) = pair()
        val normal = dj.planner.planExplained(a, b, TransitionPrefs())
        val chosen = normal.ranked.candidates.last().strategy.id
        dj.setPin(PairPin(a.analysis.identity, b.analysis.identity, chosen, aLabel = "A", bLabel = "B"))
        assertEquals(chosen, dj.planner.planExplained(a, b, TransitionPrefs()).ranked.best.strategy.id)
        assertTrue(File(dir, "pins.json").isFile)
        assertEquals(1, DjCustomization(dir).pins().size)
        assertTrue(dj.removePin(a.analysis.identity, b.analysis.identity))
        assertEquals(normal.ranked.best.strategy.id, dj.planner.planExplained(a, b, TransitionPrefs()).ranked.best.strategy.id)
    }

    // ---------------------------------------------------------------------------------------------------- ratings

    @Test
    fun resetForgetsOneTechniqueOrEverythingKeepsABackupAndReachesThePlanner() {
        val dir = tempDir()
        val dj = DjCustomization(dir)
        val (a, b) = pair()
        val features = DefaultPairAnalyzer().features(a.analysis, b.analysis, TransitionPrefs())
        val bucket = ContextBucket.of(features)
        repeat(3) { dj.record("bassSwap", features, Rating.Up) }
        dj.record("crossfade", features, Rating.Down)
        assertEquals(setOf("bassSwap", "crossfade"), dj.learned().map { it.first }.toSet())
        assertEquals(3, dj.planner.current.customization.learner!!.tally("bassSwap", bucket).n)

        dj.resetLearned("crossfade")
        assertEquals(listOf("bassSwap"), dj.learned().map { it.first })
        assertTrue(File(dir, "feedback.json.bak").isFile, "the previous ratings are kept aside")
        assertEquals(0, dj.planner.current.customization.learner!!.tally("crossfade", bucket).n)
        assertEquals(3, dj.planner.current.customization.learner!!.tally("bassSwap", bucket).n)

        dj.resetLearned(null)
        assertEquals(emptyList(), dj.learned())
        assertFalse(File(dir, "feedback.json").exists())
        assertEquals(0, dj.planner.current.customization.learner!!.tally("bassSwap", bucket).n)
        assertEquals(emptyList(), DjCustomization(dir).learned(), "the reset survives a restart")
    }

    // ---------------------------------------------------------------------------------------------------- presets

    @Test
    fun savingAPresetKeepsOnlyTheTechniquesOwnValuesAndNeverOverwrites() {
        val dir = tempDir()
        val dj = DjCustomization(dir)
        val bassSwap = dj.registry.strategy("bassSwap")!!
        val params = Params(mapOf("overlapBars" to "8", "tempoGlide.maxBars" to "4", "notAParam" to "1"))
        val first = dj.savePreset("Tight swap!", bassSwap, params)
        val second = dj.savePreset("Tight swap!", bassSwap, params)
        assertEquals("tight-swap", first.id)
        assertEquals("tight-swap-2", second.id)
        assertEquals(mapOf("overlapBars" to "8"), first.params.values)
        assertEquals(2, DjCustomization(dir).presets().count { !dj.isBuiltInPreset(it.id) })
        assertFailsWith<IllegalArgumentException> { dj.deletePreset("tight-bass-swap") }
        assertTrue(dj.deletePreset("tight-swap"))
    }

    @Test
    fun anActivePresetIsFoldedIntoTheEnginePrefs() {
        val dj = DjCustomization(tempDir())
        val prefs = TransitionPrefs(activePresets = mapOf("crossfade" to "quick-crossfade"))
        assertEquals(mapOf("fadeSec" to "3"), dj.shape(prefs).paramOverrides["crossfade"])
        // An explicit override still wins over the preset.
        val explicit = prefs.copy(paramOverrides = mapOf("crossfade" to mapOf("fadeSec" to "5")))
        assertEquals("5", dj.shape(explicit).paramOverrides["crossfade"]!!["fadeSec"])
    }

    @Test
    fun idsAreDerivedSafely() {
        assertEquals("tight-8-bar-swap", DjCustomization.slug("  Tight 8-bar swap! "))
        assertEquals("preset", DjCustomization.slug("!!!"))
        assertEquals("a", DjCustomization.freeId("a", emptySet()))
        assertEquals("a-3", DjCustomization.freeId("a", setOf("a", "a-2")))
        assertEquals("smooth-blend", DjCustomization.recipeIdOf("recipe:smooth-blend"))
        assertNull(DjCustomization.recipeIdOf("bassSwap"))
    }
}
