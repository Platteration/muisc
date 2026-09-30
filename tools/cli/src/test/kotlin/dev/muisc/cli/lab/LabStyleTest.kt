package dev.muisc.cli.lab

import dev.muisc.cli.CliContext
import dev.muisc.cli.MuiscCommand
import dev.muisc.cli.runStandalone
import dev.muisc.transitions.TransitionPrefs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The page's style menu picks a style *in place of* the command line's `--style`, never on top of it: the prefs
 * the Lab plans with for style S are the ones `muisc <cmd> --style S` would use with the same other options.
 */
class LabStyleTest {
    private val root: File = Files.createTempDirectory("lab-style-").toFile()

    @AfterEach
    fun cleanup() {
        root.deleteRecursively()
    }

    /** Builds a [LabContext] over the real [MuiscCommand] wiring and hands back what [probe] computed from it. */
    private class Probe(val probe: (LabContext) -> Map<String, TransitionPrefs>, val sessionDir: File) : MuiscCommand("probe") {
        var result: Map<String, TransitionPrefs> = emptyMap()
        override fun execute(ctx: CliContext) {
            LabContext(ctx, sessionDir).use { result = probe(it) }
        }
    }

    private fun run(vararg args: String, probe: (LabContext) -> Map<String, TransitionPrefs>): Map<String, TransitionPrefs> {
        val p = Probe(probe, File(root, "session"))
        runStandalone(p, root, *args)
        return p.result
    }

    @Test
    fun `picking the command line's own style on the page changes nothing`() {
        val r = run("--style", "club") { lab -> mapOf("null" to lab.prefs(null), "club" to lab.prefs("club")) }
        assertEquals(r["null"], r["club"])
    }

    @Test
    fun `another style replaces the command line's and --set-pref still wins`() {
        val sets = arrayOf("--set-pref", "strategyWeights.bassSwap=2.5", "--set-pref", "energy=0.45")
        val fromLab = run("--style", "club", *sets) { lab -> mapOf("smooth" to lab.prefs("smooth")) }["smooth"]!!
        val fromCli = run("--style", "smooth", *sets) { lab -> mapOf("cli" to lab.prefs(null)) }["cli"]!!
        assertEquals(fromCli, fromLab)
        assertEquals(2.5, fromLab.strategyWeights["bassSwap"])
        assertEquals(0.45, fromLab.energy)
        // Nothing of club is left: its key lock and its crossfade weight are gone.
        val unstyled = run(*sets) { lab -> mapOf("none" to lab.prefs(null)) }["none"]!!
        assertEquals(unstyled.keyLock, fromLab.keyLock)
        assertNotEquals(0.7, fromLab.strategyWeights["crossfade"])
    }
}
