package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.parse
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.BuiltInStyles
import dev.muisc.transitions.custom.FeedbackLearner
import dev.muisc.transitions.custom.FilePresetStore
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.custom.PrefsPatch
import dev.muisc.transitions.custom.StyleProfile
import dev.muisc.transitions.custom.UserProfile
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The customization commands (`preset`, `style`, `pin`, `rate`) and the shared `--profile-dir` / `--style` /
 * `--preset` options. Commands are instantiated directly (they are registered in `allCommands()` separately); every
 * run gets its own profile directory and the shared analysis cache, so nothing touches `~/.muisc`.
 */
class CustomizationCommandsTest {

    companion object {
        @JvmStatic
        @TempDir
        lateinit var root: File

        private val cache: File get() = File(root, "cache")

        /** The option that says A and B are track identities rather than files. */
        private const val IDS = "--identities"
        private lateinit var songs: Cli.Songs

        @JvmStatic
        @BeforeAll
        fun setUp() {
            cache.mkdirs()
            File(root, "songs").mkdirs()
            songs = Cli.songs(File(root, "songs"), cache)
        }
    }

    @TempDir
    lateinit var profileDir: File

    /** Runs [command] with [args] plus `--profile-dir` and `--cache-dir`; returns stdout + stderr. */
    private fun run(command: CliktCommand, vararg args: String): String {
        val captured = ByteArrayOutputStream()
        val out = System.out
        val err = System.err
        val stream = PrintStream(captured, true, StandardCharsets.UTF_8.name())
        System.setOut(stream); System.setErr(stream)
        try {
            command.parse(args.toList() + listOf("--profile-dir", profileDir.absolutePath, "--cache-dir", cache.absolutePath))
        } finally {
            stream.flush(); System.setOut(out); System.setErr(err)
        }
        return captured.toString(StandardCharsets.UTF_8.name())
    }

    /** Test-only command that prints what [MuiscCommand] resolved. */
    private class Probe : MuiscCommand("probe") {
        override fun help(context: Context) = "probe"
        var prefs: TransitionPrefs? = null
        var pinFor: PairPin? = null
        var presetId: String? = null
        override fun execute(ctx: CliContext) {
            prefs = ctx.prefs
            pinFor = ctx.customization.pins.pin("fp-x", "fp-y")
            presetId = ctx.preset?.id
        }
    }

    // ---- profile dir ----------------------------------------------------------------------------------------

    @Test
    fun `profile dir defaults to MUISC_HOME then the home directory`() {
        assertEquals(File("/x/explicit"), CliContext.defaultProfileDir(File("/x/explicit")) { "/y" })
        assertEquals(File("/y/home"), CliContext.defaultProfileDir(null) { if (it == "MUISC_HOME") "/y/home" else null })
        assertEquals(File(System.getProperty("user.home"), ".muisc"), CliContext.defaultProfileDir(null) { null })
        assertEquals(File(System.getProperty("user.home"), ".muisc"), CliContext.defaultProfileDir(null) { " " })
    }

    // ---- presets --------------------------------------------------------------------------------------------

    @Test
    fun `preset save, list, show and delete`() {
        val saved = run(PresetCommand(), "save", "my-swap", "--strategy", "bassSwap", "--set", "overlapBars=12", "--set", "lowHz=180", "--name", "My swap", "--modifier", "none")
        assertContains(saved, "saved preset 'my-swap' for bassSwap")
        val stored = FilePresetStore(File(profileDir, "presets")).get("my-swap")!!
        assertEquals(mapOf("overlapBars" to "12", "lowHz" to "180"), stored.params.values)
        assertEquals(emptyList(), stored.modifiers)

        val list = run(PresetCommand(), "list")
        assertContains(list, "tight-bass-swap")
        assertContains(list, "built-in")
        assertContains(list, "my-swap")
        assertContains(list, "overlapBars=12")

        val show = run(PresetCommand(), "show", "my-swap")
        assertContains(show, "\"strategyId\": \"bassSwap\"")
        assertContains(show, "← preset")
        assertContains(show, "modifiers: none")

        assertContains(run(PresetCommand(), "delete", "my-swap"), "deleted preset 'my-swap'")
        assertNull(FilePresetStore(File(profileDir, "presets")).get("my-swap"))
        assertContains(assertFailsWith<CliktError> { run(PresetCommand(), "delete", "my-swap") }.message!!, "no user preset")
        assertContains(assertFailsWith<CliktError> { run(PresetCommand(), "delete", "tight-bass-swap") }.message!!, "built-in")
    }

    @Test
    fun `preset save rejects bad values, unknown strategies and built-in ids`() {
        assertContains(assertFailsWith<CliktError> { run(PresetCommand(), "save", "x", "--strategy", "bassSwap", "--set", "overlapBars=99") }.message!!, "outside 8..32")
        assertContains(assertFailsWith<CliktError> { run(PresetCommand(), "save", "x", "--strategy", "bassSwap", "--set", "nope=1") }.message!!, "unknown parameter 'nope'")
        assertContains(assertFailsWith<CliktError> { run(PresetCommand(), "save", "x", "--strategy", "bassSwap", "--set", "swapLaw=LOUD") }.message!!, "must be one of")
        assertContains(assertFailsWith<CliktError> { run(PresetCommand(), "save", "x", "--strategy", "noSuch") }.message!!, "unknown strategy 'noSuch'")
        assertContains(assertFailsWith<CliktError> { run(PresetCommand(), "save", "tight-bass-swap", "--strategy", "bassSwap") }.message!!, "built-in")
        assertContains(assertFailsWith<CliktError> { run(PresetCommand(), "save", "Bad Id", "--strategy", "bassSwap") }.message!!, "lowercase")
        assertContains(assertFailsWith<CliktError> { run(PresetCommand(), "save", "x", "--strategy", "bassSwap", "--modifier", "warp") }.message!!, "unknown modifier")
        // A recipe that is not registered yet is saved without value checks, and says so.
        assertContains(run(PresetCommand(), "save", "dub-knobs", "--strategy", "recipe:dub", "--set", "swapBar=6"), "were not checked")
        assertFalse(File(profileDir, "presets/x.json").exists())
    }

    @Test
    fun `a corrupt preset file is reported and the rest still works`() {
        run(PresetCommand(), "save", "good", "--strategy", "crossfade", "--set", "fadeSec=5")
        File(profileDir, "presets/bad.json").writeText("{ nope")
        val out = run(PresetCommand(), "list")
        assertContains(out, "warning: skipped preset file")
        assertContains(out, "bad.json")
        assertContains(out, "good")
    }

    // ---- styles ---------------------------------------------------------------------------------------------

    @Test
    fun `style list and show`() {
        val list = run(StyleCommand(), "list")
        for (s in BuiltInStyles.all) assertContains(list, "${s.id}: ${s.description}")
        val show = run(StyleCommand(), "show", "purist")
        assertContains(show, "maxStretchPercent = 3.0")
        assertContains(show, "maxStretchPercent: 8.0 → 3.0")
        assertContains(show, "disabled: ")
        assertContains(show, "preset for phraseCut: — → dry-cut")
        assertContains(assertFailsWith<CliktError> { run(StyleCommand(), "show", "nope") }.message!!, "unknown style 'nope'")
    }

    @Test
    fun `--style applies before --set-pref, and user styles load from the profile`() {
        val probe = Probe()
        run(probe, "--style", "club", "--set-pref", "energy=0.2")
        val p = assertNotNull(probe.prefs)
        assertEquals(0.2, p.energy, "--set-pref wins over the style")
        assertEquals(PrefsPatch.PREFER_BOOST, p.strategyWeights["bassSwap"]!!, 1e-12)
        assertEquals("tight-bass-swap", p.activePresets["bassSwap"])
        assertEquals(mapOf("overlapBars" to "8", "swapBar" to "4", "swapBeats" to "1"), p.paramOverrides["bassSwap"], "active presets are folded into the overrides")

        UserProfile(profileDir).styles.save(StyleProfile("mine", "Mine", "test", PrefsPatch(varietyPenalty = 0.9)))
        val probe2 = Probe()
        run(probe2, "--style", "mine")
        assertEquals(0.9, probe2.prefs!!.varietyPenalty)
        assertContains(assertFailsWith<CliktError> { run(Probe(), "--style", "nope") }.message!!, "unknown style 'nope'")
    }

    @Test
    fun `--set-pref activePresets and the preset precedence`() {
        val probe = Probe()
        run(probe, "--set-pref", "activePresets.crossfade=quick-crossfade", "--set-pref", "paramOverrides.crossfade.law=LINEAR")
        assertEquals(mapOf("fadeSec" to "3", "law" to "LINEAR"), probe.prefs!!.paramOverrides["crossfade"])
        val over = Probe()
        run(over, "--set-pref", "activePresets.crossfade=quick-crossfade", "--set-pref", "paramOverrides.crossfade.fadeSec=7")
        assertEquals("7", over.prefs!!.paramOverrides["crossfade"]!!["fadeSec"], "explicit overrides beat the active preset")
        val cleared = PrefsIo.apply(TransitionPrefs(activePresets = mapOf("crossfade" to "x")), listOf("activePresets.crossfade=none"))
        assertTrue(cleared.activePresets.isEmpty())
    }

    @Test
    fun `--preset pins every pair to the preset's strategy and folds its values`() {
        val probe = Probe()
        run(probe, "--preset", "quick-crossfade", "--set-pref", "paramOverrides.crossfade.fadeSec=9")
        assertEquals("quick-crossfade", probe.presetId)
        assertEquals("3", probe.prefs!!.paramOverrides["crossfade"]!!["fadeSec"], "the chosen preset wins for its strategy")
        val pin = assertNotNull(probe.pinFor)
        assertEquals("crossfade", pin.strategyId)
        assertEquals("quick-crossfade", pin.presetId)
        assertContains(assertFailsWith<CliktError> { run(Probe(), "--preset", "nope") }.message!!, "unknown preset 'nope'")
        run(PresetCommand(), "save", "dub-knobs", "--strategy", "recipe:dub")
        assertContains(assertFailsWith<CliktError> { run(Probe(), "--preset", "dub-knobs") }.message!!, "not registered")
        // Without --preset there is no session pin.
        val plain = Probe()
        run(plain)
        assertNull(plain.pinFor)
    }

    @Test
    fun `render --preset renders that strategy with the preset's values`() {
        val wav = File(root, "out/preset.wav")
        val text = run(RenderCommand(), songs.a.absolutePath, songs.b.absolutePath, "-o", wav.absolutePath, "--preset", "quick-crossfade")
        assertContains(text, "strategy: crossfade")
        val plan = File(wav.parentFile, "preset.plan.json").readText()
        assertContains(plan, "\"strategyId\": \"crossfade\"")
        assertContains(plan, "\"fadeSec\": \"3\"")
    }

    // ---- pins -----------------------------------------------------------------------------------------------

    @Test
    fun `pin set, list and clear by identity`() {
        assertContains(run(PinCommand(), "list"), "no pins")
        val set = run(PinCommand(), "set", "fp-a", "fp-b", "echoOut", "--preset", "dub-echo", "--set", "tailBars=6", IDS, "--note", "my favourite")
        assertContains(set, "pinned echoOut (preset dub-echo)")
        assertContains(set, "warning: 'fp-a' is not the identity of any analysed track")
        assertContains(set, "warning: 'fp-b' is not the identity of any analysed track")
        val pin = UserProfile(profileDir).pins.pin("fp-a", "fp-b")!!
        assertEquals("dub-echo", pin.presetId)
        assertEquals(mapOf("tailBars" to "6"), pin.params!!.values)
        assertEquals("my favourite", pin.note)
        val list = run(PinCommand(), "list")
        assertContains(list, "echoOut")
        assertContains(list, "tailBars=6")
        assertContains(list, "1 pin(s)")
        assertContains(assertFailsWith<CliktError> { run(PinCommand(), "set", "fp-a", "fp-b", "echoOut", "--preset", "tight-bass-swap", IDS) }.message!!, "is for bassSwap")
        assertContains(assertFailsWith<CliktError> { run(PinCommand(), "set", "fp-a", "fp-b", "warp", IDS) }.message!!, "unknown strategy")
        assertContains(assertFailsWith<CliktError> { run(PinCommand(), "set", "fp-a", "fp-b", "echoOut", "--set", "tailBars=99", IDS) }.message!!, "outside")
        assertContains(run(PinCommand(), "clear", "fp-a", "fp-b", IDS), "cleared")
        assertContains(assertFailsWith<CliktError> { run(PinCommand(), "clear", "fp-a", "fp-b", IDS) }.message!!, "no pin")
    }

    private fun analysisOf(f: File) = CliContext(TransitionPrefs(), cache).use { it.trackRef(f).analysis }

    @Test
    fun `analyze prints the full identity that pins are keyed by`() {
        val an = analysisOf(songs.a)
        assertTrue(an.identity != an.fingerprint, "the current analyzer keys identity on the audio, not the file")
        val text = Cli.run(cache, "analyze", songs.a.absolutePath)
        assertTrue(text.lines().any { it.trim().startsWith("identity") && it.contains(an.identity) }, text)
        val json = Cli.run(cache, "analyze", songs.a.absolutePath, "--json")
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1)).jsonObject
        assertEquals(an.identity, obj["identity"]?.jsonPrimitive?.content, json.take(300))
        assertEquals(an.fingerprint, obj["fingerprint"]?.jsonPrimitive?.content)
    }

    @Test
    fun `pin set with analysis fingerprints stores the tracks' identities, so the planner honours it`() {
        val a = analysisOf(songs.a)
        val b = analysisOf(songs.b)
        val out = run(PinCommand(), "set", a.fingerprint, b.fingerprint, "crossfade", "--preset", "long-crossfade", IDS)
        val pins = UserProfile(profileDir).pins
        assertNotNull(pins.pin(a.identity, b.identity), "stored under the identities (output: $out)")
        assertNull(pins.pin(a.fingerprint, b.fingerprint))
        assertContains(Cli.run(cache, "plan", songs.a.absolutePath, songs.b.absolutePath, "--profile-dir", profileDir.absolutePath), "pinned by you")
        assertContains(out, "is an analysis fingerprint")
        assertContains(out, "now: pinned by you")

        // `pin list` shows the identities in full, so they can be copied.
        val list = run(PinCommand(), "list")
        assertContains(list, a.identity)
        assertContains(list, b.identity)

        // clear accepts the fingerprints too, and the identities work directly with no warning.
        assertContains(run(PinCommand(), "clear", a.fingerprint, b.fingerprint, IDS), "cleared")
        assertNull(UserProfile(profileDir).pins.pin(a.identity, b.identity))
        val direct = run(PinCommand(), "set", a.identity, b.identity, "crossfade", IDS)
        assertContains(direct, "now: pinned by you")
        assertFalse(direct.contains("warning"), direct)

        // A pin set from the files is cleared by fingerprint.
        run(PinCommand(), "clear", a.identity, b.identity, IDS)
        run(PinCommand(), "set", songs.a.absolutePath, songs.b.absolutePath, "crossfade")
        assertContains(run(PinCommand(), "clear", a.fingerprint, b.fingerprint, IDS), "cleared")
    }

    @Test
    fun `a pin on real files is honoured by plan, and a blocked pin says why`() {
        val unpinned = Cli.run(cache, "plan", songs.a.absolutePath, songs.b.absolutePath, "--profile-dir", profileDir.absolutePath)
        assertFalse(unpinned.substringAfter("candidates (").lines().drop(2).first().contains("crossfade"), "the crossfade is not already first")
        val set =run(PinCommand(), "set", songs.a.absolutePath, songs.b.absolutePath, "crossfade", "--preset", "long-crossfade")
        assertContains(set, "now: pinned by you")
        val plan = Cli.run(cache, "plan", songs.a.absolutePath, songs.b.absolutePath, "--profile-dir", profileDir.absolutePath)
        val firstCandidate = plan.substringAfter("candidates (").lines().drop(2).first()
        assertContains(firstCandidate, "crossfade")
        assertContains(plan, "pinned by you")
        assertContains(plan, "preset 'long-crossfade' (Long 10-second crossfade) from your pin")
        // The reverse direction is not pinned.
        assertFalse(Cli.run(cache, "plan", songs.b.absolutePath, songs.a.absolutePath, "--profile-dir", profileDir.absolutePath).contains("pinned by you"))

        // 120 → 140 BPM is beyond the 8 % stretch limit, so a bass swap pin cannot be honoured.
        val blocked = run(PinCommand(), "set", songs.a.absolutePath, songs.c.absolutePath, "bassSwap")
        assertContains(blocked, "warning: pinned bassSwap not used")
        assertContains(blocked, "normal ranking applies")
    }

    @Test
    fun `render --set keeps the pair's pinned preset and params under the value it changes`() {
        run(PinCommand(), "set", songs.a.absolutePath, songs.b.absolutePath, "crossfade", "--preset", "quick-crossfade", "--set", "law=S_CURVE")
        val pinned = File(root, "out/pin-plain.wav")
        run(RenderCommand(), songs.a.absolutePath, songs.b.absolutePath, "-o", pinned.absolutePath)
        val plainPlan = File(pinned.parentFile, "pin-plain.plan.json").readText()
        assertContains(plainPlan, "\"fadeSec\": \"3\"")
        assertContains(plainPlan, "\"law\": \"S_CURVE\"")

        val tweaked = File(root, "out/pin-set.wav")
        val text = run(RenderCommand(), songs.a.absolutePath, songs.b.absolutePath, "-o", tweaked.absolutePath, "--set", "alignToBeat=false")
        assertContains(text, "strategy: crossfade")
        val plan = File(tweaked.parentFile, "pin-set.plan.json").readText()
        assertContains(plan, "\"alignToBeat\": \"false\"")
        assertContains(plan, "\"fadeSec\": \"3\"", message = "the pin's preset value survives --set")
        assertContains(plan, "\"law\": \"S_CURVE\"", message = "the pin's own value survives --set")

        // --set on a key the pin sets wins over the pin (layer 5 over layer 4).
        val over = File(root, "out/pin-over.wav")
        run(RenderCommand(), songs.a.absolutePath, songs.b.absolutePath, "-o", over.absolutePath, "--set", "law=LINEAR")
        val overPlan = File(over.parentFile, "pin-over.plan.json").readText()
        assertContains(overPlan, "\"law\": \"LINEAR\"")
        assertContains(overPlan, "\"fadeSec\": \"3\"")
    }

    @Test
    fun `render --strategy of a strategy the planner skipped still uses the pair's pin`() {
        run(PinCommand(), "set", songs.a.absolutePath, songs.b.absolutePath, "phraseCut", "--set", "tailMs=0")
        val wav = File(root, "out/pin-forced.wav")
        // Disabled, so the planner does not rank it and --strategy builds it outside the ranking.
        run(RenderCommand(), songs.a.absolutePath, songs.b.absolutePath, "-o", wav.absolutePath, "--strategy", "phraseCut", "--set-pref", "disabledStrategies=phraseCut")
        val plan = File(wav.parentFile, "pin-forced.plan.json").readText()
        assertContains(plan, "\"strategyId\": \"phraseCut\"")
        assertContains(plan, "\"tailMs\": \"0\"")
    }

    @Test
    fun `sweep keeps the pair's pin under the swept values`() {
        run(PinCommand(), "set", songs.a.absolutePath, songs.b.absolutePath, "crossfade", "--preset", "quick-crossfade")
        val out = File(root, "out/pin-sweep")
        run(SweepCommand(), songs.a.absolutePath, songs.b.absolutePath, "--strategy", "crossfade", "--param", "alignToBeat=0:1:2", "-o", out.absolutePath)
        val csv = File(out, "sweep.csv").readLines()
        val col = csv.first().split(',').indexOf("seconds")
        assertTrue(col >= 0, csv.first())
        val seconds = csv.drop(1).map { it.split(',')[col].toDouble() }
        assertEquals(2, seconds.size, csv.toString())
        // quick-crossfade is 3 s; the default fade is 6 s. The segment is the fade plus a few milliseconds of guard.
        for (s in seconds) assertTrue(s < 4.0, "segment of $s s: the pin's fadeSec=3 was dropped (${csv.joinToString(" | ")})")
    }

    // ---- ratings --------------------------------------------------------------------------------------------

    @Test
    fun `rate records ratings and rate show prints the learned weights`() {
        assertContains(run(RateCommand(), "show"), "no ratings yet")
        run(RateCommand(), songs.a.absolutePath, songs.b.absolutePath, "bassSwap", "up")
        val second = run(RateCommand(), songs.a.absolutePath, songs.b.absolutePath, "bassSwap", "5")
        val expected = "%.2f".format(FeedbackLearner.multiplier(dev.muisc.transitions.custom.RatingTally(2, 2.0)))
        assertContains(second, "bassSwap: learned ×$expected from 2 ratings in '")
        assertTrue(File(profileDir, "feedback.json").isFile)
        val show = run(RateCommand(), "show")
        assertContains(show, "bassSwap")
        assertContains(show, "×$expected")
        // The planner in the next command uses it.
        val plan = Cli.run(cache, "plan", songs.a.absolutePath, songs.b.absolutePath, "--profile-dir", profileDir.absolutePath)
        if (plan.contains("  bassSwap — ")) assertContains(plan, "learned ×$expected from 2 ratings")

        assertContains(assertFailsWith<CliktError> { run(RateCommand(), songs.a.absolutePath, songs.b.absolutePath, "bassSwap", "7") }.message!!, "up, down or 1..5")
        assertContains(assertFailsWith<CliktError> { run(RateCommand(), songs.a.absolutePath, songs.b.absolutePath, "warp", "up") }.message!!, "unknown strategy")
        assertContains(assertFailsWith<CliktError> { run(RateCommand(), "sideways") }.message!!, "usage")
    }
}
