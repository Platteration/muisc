package dev.muisc.transitions.custom

import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Round trips and the safety-net loading rule (AGENTS.md §5) of the preset, style and pin stores. */
class CustomStoresTest {

    private val preset = StrategyPreset("my-swap", "My swap", "bassSwap", Params(mapOf("overlapBars" to "12", "lowHz" to "180")), modifiers = listOf("tempoGlide"), note = "for testing")

    // ---- presets ------------------------------------------------------------------------------------------------

    @Test
    fun filePresetStoreRoundTrips(@TempDir dir: File) {
        val store = FilePresetStore(File(dir, "presets"))
        assertTrue(store.list().isEmpty(), "a missing directory is an empty store")
        store.save(preset)
        store.save(StrategyPreset("recipe-knobs", "Recipe knobs", "recipe:club-swap", Params(mapOf("swapBar" to "6"))))
        assertEquals(listOf("my-swap", "recipe-knobs"), store.list().map { it.id })
        assertEquals(preset, store.get("my-swap"))
        // A second store over the same directory sees the same data.
        assertEquals(store.list(), FilePresetStore(File(dir, "presets")).list())
        // Params are written as a flat object and no temp file is left behind.
        val text = File(dir, "presets/my-swap.json").readText()
        assertTrue(text.contains("\"overlapBars\": \"12\""), text)
        assertEquals(setOf("my-swap.json", "recipe-knobs.json"), File(dir, "presets").list()!!.toSet())
        // Replace and delete.
        store.save(preset.copy(name = "Renamed"))
        assertEquals("Renamed", store.get("my-swap")!!.name)
        assertTrue(store.delete("my-swap"))
        assertFalse(store.delete("my-swap"))
        assertNull(store.get("my-swap"))
        assertTrue(store.warnings.isEmpty())
    }

    @Test
    fun handWrittenPresetAcceptsNumbersAndBooleans(@TempDir dir: File) {
        File(dir, "hand.json").writeText("""{"id": "hand", "name": "Hand", "strategyId": "bassSwap", "params": {"overlapBars": 8, "detectOnsets": false, "swapLaw": "LINEAR"}}""")
        val p = FilePresetStore(dir).get("hand")!!
        assertEquals(mapOf("overlapBars" to "8", "detectOnsets" to "false", "swapLaw" to "LINEAR"), p.params.values)
        assertNull(p.modifiers)
    }

    @Test
    fun corruptPresetFilesAreSkippedAndReportedNeverBlockingTheRest(@TempDir dir: File) {
        val store = FilePresetStore(dir)
        store.save(preset)
        File(dir, "broken.json").writeText("{ this is not json")
        File(dir, "typo.json").writeText("""{"id": "typo", "name": "T", "strategyId": "bassSwap", "parms": {}}""")
        File(dir, "misnamed.json").writeText("""{"id": "other", "name": "O", "strategyId": "bassSwap"}""")
        File(dir, "tight-bass-swap.json").writeText("""{"id": "tight-bass-swap", "name": "Shadow", "strategyId": "bassSwap"}""")
        File(dir, "nested.json").writeText("""{"id": "nested", "name": "N", "strategyId": "bassSwap", "params": {"x": {"y": 1}}}""")
        File(dir, "notes.txt").writeText("ignored")
        assertEquals(listOf("my-swap"), store.list().map { it.id })
        val w = store.warnings
        assertEquals(5, w.size, w.toString())
        assertTrue(w.any { it.contains("broken.json") })
        assertTrue(w.any { it.contains("typo.json") && it.contains("parms") }, w.toString())
        assertTrue(w.any { it.contains("misnamed.json") && it.contains("other.json") })
        assertTrue(w.any { it.contains("tight-bass-swap.json") && it.contains("built-in") })
        assertTrue(w.any { it.contains("nested.json") })
        // The built-in is still the one that resolves.
        assertEquals("Tight 8-bar bass swap", store.withBuiltIns().preset("tight-bass-swap")!!.name)
    }

    @Test
    fun presetIdsAreValidatedAndBuiltInsAreReadOnly(@TempDir dir: File) {
        for (store in listOf(FilePresetStore(dir), InMemoryPresetStore())) {
            assertFailsWith<IllegalArgumentException> { store.save(preset.copy(id = "Bad Id")) }
            assertFailsWith<IllegalArgumentException> { store.save(preset.copy(id = "../escape")) }
            assertFailsWith<IllegalArgumentException> { store.save(preset.copy(id = "tight-bass-swap")) }
            assertFailsWith<IllegalArgumentException> { store.save(preset.copy(strategyId = " ")) }
            assertNull(store.get("../escape"))
            assertFalse(store.delete("../escape"))
        }
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun inMemoryPresetStoreBehavesLikeTheFileStore() {
        val store = InMemoryPresetStore(listOf(preset))
        assertEquals(preset, store.get("my-swap"))
        assertEquals(BuiltInPresets.all.map { it.id } + "my-swap", store.all().map { it.id })
        assertTrue(store.delete("my-swap"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun builtInPresetsAreValidForTheShippedStrategies() {
        val registry = DefaultStrategyRegistry.default()
        assertEquals(BuiltInPresets.all.size, BuiltInPresets.all.map { it.id }.toSet().size, "unique ids")
        for (p in BuiltInPresets.all) {
            CustomJson.requireId(p.id, "preset")
            val strategy = assertNotNull(registry.strategy(p.strategyId), p.id)
            for ((k, v) in p.params.values) {
                val spec = assertNotNull(strategy.params.firstOrNull { it.id == k }, "${p.id}: unknown param $k")
                when (spec) {
                    is ParamSpec.DoubleSpec -> assertTrue(v.toDouble() in spec.min..spec.max, "${p.id}.$k=$v")
                    is ParamSpec.IntSpec -> assertTrue(v.toInt() in spec.min..spec.max, "${p.id}.$k=$v")
                    is ParamSpec.BoolSpec -> assertTrue(v == "true" || v == "false")
                    is ParamSpec.ChoiceSpec -> assertTrue(v in spec.choices, "${p.id}.$k=$v")
                }
            }
        }
    }

    @Test
    fun atomicWriteReplacesAndPreserveKeepsCorruptCopies(@TempDir dir: File) {
        val f = File(dir, "x.json")
        AtomicFiles.write(f, "one")
        AtomicFiles.write(f, "two")
        assertEquals("two", f.readText())
        assertEquals(listOf("x.json"), dir.list()!!.toList())
        val kept = AtomicFiles.preserve(f)
        assertEquals("x.json.corrupt", kept.name)
        AtomicFiles.write(f, "three")
        assertEquals("x.json.corrupt.1", AtomicFiles.preserve(f).name)
        assertEquals("two", kept.readText())
    }

    // ---- styles -------------------------------------------------------------------------------------------------

    @Test
    fun fileStyleStoreRoundTripsAndSkipsBadFiles(@TempDir dir: File) {
        val store = FileStyleStore(dir)
        val mine = StyleProfile("late-night", "Late night", "calm but tight", PrefsPatch(energy = 0.25, weights = mapOf("echoOut" to 1.2), disable = setOf("brakeStop"), prefer = listOf("recipe:dub"), activePresets = mapOf("echoOut" to "dub-echo")))
        store.save(mine)
        assertEquals(mine, store.get("late-night"))
        assertEquals(mine, FileStyleStore(dir).find("late-night"))
        assertEquals("Club", store.find("club")!!.name)
        File(dir, "loud.json").writeText("""{"id": "loud", "name": "Loud", "patch": {"energy": 3.0}}""")
        File(dir, "junk.json").writeText("[]")
        File(dir, "club.json").writeText("""{"id": "club", "name": "Fake club"}""")
        assertEquals(listOf("late-night"), store.list().map { it.id })
        assertEquals(3, store.warnings.size, store.warnings.toString())
        assertTrue(store.warnings.any { it.contains("loud.json") && it.contains("energy 3.0 is outside 0..1") })
        assertEquals("Club", store.find("club")!!.name, "a file cannot shadow a built-in style")
        assertFailsWith<IllegalArgumentException> { store.save(mine.copy(id = "smooth")) }
        assertFailsWith<IllegalArgumentException> { store.save(mine.copy(id = "x", patch = PrefsPatch(varietyPenalty = -1.0))) }
        assertEquals(BuiltInStyles.all.map { it.id } + "late-night", store.all().map { it.id })
    }

    // ---- pins ---------------------------------------------------------------------------------------------------

    @Test
    fun filePinStoreRoundTripsReplacesAndClears(@TempDir dir: File) {
        val file = File(dir, "pins.json")
        val store = FilePinStore(file)
        assertNull(store.pin("a", "b"))
        store.set(PairPin("a", "b", "bassSwap", presetId = "tight-bass-swap", params = Params(mapOf("swapBar" to "6")), aLabel = "A.wav", bLabel = "B.wav"))
        store.set(PairPin("b", "a", "echoOut"))
        store.set(PairPin("a", "b", "filterSweep"))
        assertEquals("filterSweep", store.pin("a", "b")!!.strategyId, "one pin per ordered pair; the last set wins")
        assertEquals("echoOut", store.pin("b", "a")!!.strategyId, "pins are directional")
        val reread = FilePinStore(file)
        assertEquals(store.list(), reread.list())
        assertEquals(2, reread.list().size)
        assertTrue(reread.clear("a", "b"))
        assertFalse(reread.clear("a", "b"))
        assertNull(FilePinStore(file).pin("a", "b"))
        assertTrue(store.list().none { it.aFingerprint == "a" }, "a store notices when the file changes on disk")
        assertTrue(store.warnings.isEmpty())
        assertFailsWith<IllegalArgumentException> { store.set(PairPin("", "b", "x")) }
        assertFailsWith<IllegalArgumentException> { store.set(PairPin("a", "b", "x", presetId = "Bad Id")) }
    }

    @Test
    fun corruptPinEntriesAreSkippedAndTheFileIsKeptBeforeTheNextWrite(@TempDir dir: File) {
        val file = File(dir, "pins.json")
        file.writeText(
            """{"version": 1, "pins": [
              {"aFingerprint": "a", "bFingerprint": "b", "strategyId": "bassSwap"},
              {"aFingerprint": "c", "strategyId": "echoOut"},
              {"aFingerprint": "d", "bFingerprint": "e", "strategyId": "phraseCut", "colour": "red"}
            ]}""",
        )
        val store = FilePinStore(file)
        assertEquals(listOf("bassSwap"), store.list().map { it.strategyId })
        assertEquals(2, store.warnings.size, store.warnings.toString())
        val original = file.readText()
        store.set(PairPin("x", "y", "crossfade"))
        assertEquals(original, File(dir, "pins.json.corrupt").readText(), "the file with the skipped entries is kept")
        assertEquals(setOf("bassSwap", "crossfade"), FilePinStore(file).list().map { it.strategyId }.toSet())
        assertTrue(FilePinStore(file).warnings.isEmpty())
    }

    @Test
    fun unreadablePinFileYieldsNoPinsAndIsNeverOverwritten(@TempDir dir: File) {
        val file = File(dir, "pins.json")
        file.writeText("garbage")
        val store = FilePinStore(file)
        assertTrue(store.list().isEmpty())
        assertTrue(store.warnings.single().contains("cannot read pins file"))
        store.set(PairPin("a", "b", "crossfade"))
        assertEquals("garbage", File(dir, "pins.json.corrupt").readText())
        assertEquals(1, FilePinStore(file).list().size)
        // A newer format is refused, not misread.
        file.writeText("""{"version": 2, "pins": []}""")
        val newer = FilePinStore(file)
        assertTrue(newer.list().isEmpty())
        assertTrue(newer.warnings.single().contains("version 2"))
    }

    @Test
    fun userProfileLaysOutItsFilesAndCollectsWarnings(@TempDir dir: File) {
        val profile = UserProfile(dir)
        assertTrue(profile.warnings().isEmpty())
        profile.presets.save(preset)
        profile.pins.set(PairPin("a", "b", "bassSwap"))
        assertTrue(File(dir, "presets/my-swap.json").isFile)
        assertTrue(File(dir, "pins.json").isFile)
        assertEquals(preset, profile.presetLookup.preset("my-swap"))
        assertEquals("Tight 8-bar bass swap", profile.presetLookup.preset("tight-bass-swap")!!.name)
        File(dir, "styles").mkdirs()
        File(dir, "styles/bad.json").writeText("{")
        File(dir, "feedback.json").writeText("{")
        val w = UserProfile(dir).warnings()
        assertEquals(2, w.size, w.toString())
        assertTrue(w.any { it.contains("bad.json") } && w.any { it.contains("feedback.json") })
    }
}
