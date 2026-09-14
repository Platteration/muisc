package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktError
import dev.muisc.audio.synth.Mode
import dev.muisc.metrics.Metric
import dev.muisc.metrics.MetricsReport
import dev.muisc.metrics.Verdict
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.TransitionPrefs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Unit tests for the parsing and formatting the CLI does itself (no engine, no audio). */
class CliParsingTest {

    // ---- prefs ----------------------------------------------------------------------------------------------

    @Test
    fun `set-pref covers every scalar preference`() {
        val p = PrefsIo.apply(
            TransitionPrefs(),
            listOf(
                "enabled=false", "allowInAlbums=yes", "keepAlbumFlowInShuffle=1", "maxStretchPercent=6.5",
                "maxPitchShiftSemitones=2", "keyLock=off", "targetLufs=-16", "preferredOverlapBars=24",
                "energy=0.9", "varietyPenalty=0.1", "sampleRate=48000", "channels=1",
            ),
        )
        assertEquals(false, p.enabled)
        assertEquals(true, p.allowInAlbums)
        assertEquals(true, p.keepAlbumFlowInShuffle)
        assertEquals(6.5, p.maxStretchPercent)
        assertEquals(2.0, p.maxPitchShiftSemitones)
        assertEquals(false, p.keyLock)
        assertEquals(-16.0, p.targetLufs)
        assertEquals(24, p.preferredOverlapBars)
        assertEquals(0.9, p.energy)
        assertEquals(0.1, p.varietyPenalty)
        assertEquals(48000, p.sampleRate)
        assertEquals(1, p.channels)
    }

    @Test
    fun `set-pref covers the collection preferences`() {
        val p = PrefsIo.apply(
            TransitionPrefs(),
            listOf(
                "disabledStrategies=echoOut, brakeStop",
                "strategyWeights.bassSwap=1.5",
                "strategyWeights.crossfade=0.2",
                "paramOverrides.bassSwap.overlapBars=12",
                "paramOverrides.bassSwap.lowHz=180",
            ),
        )
        assertEquals(setOf("echoOut", "brakeStop"), p.disabledStrategies)
        assertEquals(mapOf("bassSwap" to 1.5, "crossfade" to 0.2), p.strategyWeights)
        assertEquals(mapOf("overlapBars" to "12", "lowHz" to "180"), p.paramOverrides["bassSwap"])
    }

    @Test
    fun `targetLufs accepts nan to switch normalisation off`() {
        assertTrue(PrefsIo.apply(TransitionPrefs(), listOf("targetLufs=nan")).targetLufs.isNaN())
        assertTrue(PrefsIo.apply(TransitionPrefs(), listOf("targetLufs=off")).targetLufs.isNaN())
    }

    @Test
    fun `malformed preferences are rejected with the key in the message`() {
        assertContains(assertFailsWith<CliktError> { PrefsIo.apply(TransitionPrefs(), listOf("energy")) }.message ?: "", "KEY=VALUE")
        assertContains(assertFailsWith<CliktError> { PrefsIo.apply(TransitionPrefs(), listOf("energy=loud")) }.message ?: "", "energy")
        assertContains(assertFailsWith<CliktError> { PrefsIo.apply(TransitionPrefs(), listOf("keyLock=maybe")) }.message ?: "", "boolean")
        assertContains(assertFailsWith<CliktError> { PrefsIo.apply(TransitionPrefs(), listOf("paramOverrides.bassSwap=12")) }.message ?: "", "paramOverrides.<strategyId>.<paramId>")
    }

    @Test
    fun `prefs round-trip through a json file and are then overridden`(@TempDir dir: File) {
        val file = File(dir, "prefs.json")
        file.writeText(PrefsIo.PRETTY.encodeToString(TransitionPrefs.serializer(), TransitionPrefs(energy = 0.75, preferredOverlapBars = 32)))
        val loaded = PrefsIo.resolve(file, listOf("energy=0.25"), rate = 48000, channels = 1)
        assertEquals(0.25, loaded.energy, "the --set-pref override must win over the file")
        assertEquals(32, loaded.preferredOverlapBars, "the file value must survive")
        assertEquals(48000, loaded.sampleRate)
        assertEquals(1, loaded.channels)
    }

    @Test
    fun `an unreadable prefs file names the file`(@TempDir dir: File) {
        val file = File(dir, "broken.json").also { it.writeText("{ not json") }
        assertContains(assertFailsWith<CliktError> { PrefsIo.load(file) }.message ?: "", "broken.json")
    }

    @Test
    fun `an out-of-range rate or channel count is refused`() {
        assertContains(assertFailsWith<CliktError> { PrefsIo.resolve(null, emptyList(), 100, null) }.message ?: "", "--rate")
        assertContains(assertFailsWith<CliktError> { PrefsIo.resolve(null, emptyList(), null, 7) }.message ?: "", "--channels")
    }

    // ---- keys -----------------------------------------------------------------------------------------------

    @Test
    fun `synth parses every key spelling`() {
        assertEquals(0 to Mode.MAJOR, SynthCommand.parseKey("C"))
        assertEquals(9 to Mode.MINOR, SynthCommand.parseKey("Am"))
        assertEquals(6 to Mode.MINOR, SynthCommand.parseKey("F#m"))
        assertEquals(6 to Mode.MINOR, SynthCommand.parseKey("Fsm"))
        assertEquals(10 to Mode.MAJOR, SynthCommand.parseKey("Bb"))
        assertEquals(0 to Mode.MAJOR, SynthCommand.parseKey("C major"))
        assertEquals(9 to Mode.MINOR, SynthCommand.parseKey("A minor"))
        assertContains(assertFailsWith<CliktError> { SynthCommand.parseKey("H") }.message ?: "", "cannot parse key")
    }

    // ---- sweep axes -----------------------------------------------------------------------------------------

    private val intSpec = ParamSpec.IntSpec("bars", "Bars", default = 8, min = 4, max = 32)
    private val doubleSpec = ParamSpec.DoubleSpec("gain", "Gain", default = 0.0, min = -6.0, max = 6.0)
    private val choiceSpec = ParamSpec.ChoiceSpec("law", "Law", default = "LINEAR", choices = listOf("LINEAR", "EQUAL_POWER"))

    @Test
    fun `a range axis is inclusive and clamped to the declared range`() {
        assertEquals(listOf("4", "8", "12", "16"), SweepCommand.Axis.parse("bars=4:16:4", listOf(intSpec)).values)
        // 2 is below the minimum of 4, so it collapses onto it.
        assertEquals(listOf("4", "17", "32"), SweepCommand.Axis.parse("bars=2:32:3", listOf(intSpec)).values)
        assertEquals(1, SweepCommand.Axis.parse("bars=8:8:1", listOf(intSpec)).values.size)
    }

    @Test
    fun `an explicit list axis is taken verbatim`() {
        assertEquals(listOf("LINEAR", "EQUAL_POWER"), SweepCommand.Axis.parse("law=LINEAR,EQUAL_POWER", listOf(choiceSpec)).values)
        assertEquals(listOf("-3", "0", "3"), SweepCommand.Axis.parse("gain=-3,0,3", listOf(doubleSpec)).values)
    }

    @Test
    fun `axis errors name the parameter and the expected syntax`() {
        assertContains(assertFailsWith<CliktError> { SweepCommand.Axis.parse("nope=1:2:3", listOf(intSpec)) }.message ?: "", "unknown parameter")
        assertContains(assertFailsWith<CliktError> { SweepCommand.Axis.parse("bars", listOf(intSpec)) }.message ?: "", "ID=LO:HI:STEPS")
        assertContains(assertFailsWith<CliktError> { SweepCommand.Axis.parse("bars=a:b:2", listOf(intSpec)) }.message ?: "", "not a number")
        assertContains(assertFailsWith<CliktError> { SweepCommand.Axis.parse("bars=1:2:0", listOf(intSpec)) }.message ?: "", "at least 1")
        assertContains(assertFailsWith<CliktError> { SweepCommand.Axis.parse("law=1:2:2", listOf(choiceSpec)) }.message ?: "", "choice parameter")
    }

    // ---- --set ----------------------------------------------------------------------------------------------

    @Test
    fun `set assignments keep their order and reject a missing value`() {
        assertEquals(mapOf("a" to "1", "b" to "two"), Sets.parse(listOf("a=1", "b=two")))
        assertEquals(mapOf("a" to "x=y"), Sets.parse(listOf("a=x=y")), "only the first = separates")
        assertContains(assertFailsWith<CliktError> { Sets.parse(listOf("a")) }.message ?: "", "ID=VALUE")
        assertContains(assertFailsWith<CliktError> { Sets.parse(listOf("=1")) }.message ?: "", "ID=VALUE")
    }

    // ---- formatting -----------------------------------------------------------------------------------------

    @Test
    fun `seconds switch to minutes and specials stay readable`() {
        assertEquals("1.50 s", Fmt.sec(1.5))
        assertEquals("1:05.25", Fmt.sec(65.25))
        assertEquals("n/a", Fmt.sec(Double.NaN))
        assertEquals("+inf", Fmt.num(Double.POSITIVE_INFINITY))
        assertEquals("n/a", Fmt.db(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `bars are derived from the tempo`() {
        assertEquals(4.0, Fmt.bars(44100 * 8, 120.0, 44100), 1e-9, "8 s at 120 BPM is 4 bars")
        assertTrue(Fmt.bars(1000, 0.0, 44100).isNaN())
    }

    @Test
    fun `the table pads every column but the last`() {
        val text = Fmt.table(listOf(listOf("a", "bb"), listOf("ccc", "d")))
        assertEquals(listOf("a    bb", "ccc  d"), text.lines())
    }

    @Test
    fun `csv cells are quoted only when they need it`() {
        assertEquals("plain", Fmt.csvEscape("plain"))
        assertEquals("\"a,b\"", Fmt.csvEscape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Fmt.csvEscape("say \"hi\""))
    }

    @Test
    fun `the metrics block leads with the worst verdict`() {
        val report = MetricsReport(
            listOf(
                Metric("clicks", 0.0, "count", Verdict.PASS, 0.0, 0.0),
                Metric("levelJumpDb", 9.0, "dB", Verdict.FAIL, 4.0, 6.0),
                Metric("truePeakDbtp", -0.8, "dBTP", Verdict.WARN, -1.0, -0.5),
            ),
        )
        val lines = Fmt.metrics(report).lines()
        assertContains(lines[0], "FAIL")
        assertContains(lines[0], "levelJumpDb")
        assertContains(lines[2], "levelJumpDb", message = "the worst metric must be the first row")
        assertTrue(lines.last().contains("clicks"), "passing metrics come last")
    }

    // ---- report writers -------------------------------------------------------------------------------------

    @Test
    fun `level matching attenuates the loud renders towards the quietest`() {
        val rows = listOf(
            Reports.Row("loud", "loud.wav", null, emptyMap(), MetricsReport(), lufs = -8.0),
            Reports.Row("quiet", "quiet.wav", null, emptyMap(), MetricsReport(), lufs = -20.0),
        )
        Reports.levelMatch(rows)
        assertEquals(1.0, rows[1].playbackGain, 1e-9, "the quietest render plays at unity")
        assertTrue(rows[0].playbackGain < 0.3, "a 12 LU louder render must be attenuated, got ${rows[0].playbackGain}")
    }

    @Test
    fun `the svg plot survives a constant series`(@TempDir dir: File) {
        val file = File(dir, "flat.svg")
        Reports.writeSvg(file, "flat", "x", "y", listOf(1.0 to 5.0, 2.0 to 5.0, 3.0 to 5.0))
        val svg = file.readText()
        assertContains(svg, "<polyline")
        assertTrue(!svg.contains("NaN"), "a degenerate range must not produce NaN coordinates")
    }

    @Test
    fun `the svg plot says so when there is nothing to plot`(@TempDir dir: File) {
        val file = File(dir, "empty.svg")
        Reports.writeSvg(file, "empty", "x", "y", listOf(1.0 to Double.NaN))
        assertContains(file.readText(), "no finite values")
    }

    @Test
    fun `html output escapes the values it is given`(@TempDir dir: File) {
        val file = File(dir, "index.html")
        Reports.writeHtml(file, "T & <b>", "sub", listOf(Reports.Row("<id>", "a.wav", null, mapOf("k" to "<v>"), MetricsReport())))
        val html = file.readText()
        assertContains(html, "T &amp; &lt;b&gt;")
        assertContains(html, "&lt;id&gt;")
        assertTrue(!html.contains("<b>"), "raw markup leaked into the page")
    }

    // ---- side-car naming ------------------------------------------------------------------------------------

    @Test
    fun `side-cars sit next to the wav and replace only its extension`(@TempDir dir: File) {
        val out = File(dir, "take 1.wav")
        assertEquals(File(dir, "take 1.plan.json").absolutePath, RenderSupport.sidecar(out, ".plan.json").absolutePath)
        val noExt = File(dir, "take2")
        assertEquals(File(dir, "take2.context.wav").absolutePath, RenderSupport.sidecar(noExt, ".context.wav").absolutePath)
    }

    @Test
    fun `the cache directory follows the override then the environment then the home default`(@TempDir dir: File) {
        assertEquals(dir, CliContext.defaultCacheDir(dir))
        val fallback = CliContext.defaultCacheDir(null)
        assertTrue(fallback.path.endsWith(".muisc/analysis") || fallback.path == System.getenv("MUISC_CACHE"), "unexpected default ${fallback.path}")
    }
}
