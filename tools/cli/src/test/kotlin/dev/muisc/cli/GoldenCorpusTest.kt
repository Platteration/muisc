package dev.muisc.cli

import dev.muisc.metrics.GoldenCompare
import dev.muisc.metrics.GoldenFingerprint
import dev.muisc.metrics.GoldenStore
import dev.muisc.metrics.Metric
import dev.muisc.metrics.MetricsReport
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The golden-render regression: renders every registered strategy and every built-in recipe over
 * [GoldenCorpus.PAIRS] and compares each render with its committed fingerprint in [GoldenStore.DEFAULT_DIR]
 * (non-strict [dev.muisc.metrics.GoldenCompare]: plan equal, envelopes and log spectrum within tolerance, no
 * metric worse). A failure names the strategy, the pair and every feature that moved, with its size.
 *
 * `MUISC_UPDATE_GOLDENS=1` rewrites the goldens instead of comparing (and prints what moved). Do that only after an
 * INTENDED change, and review the diff; rewriting them to silence an unintended change is weakening a test
 * (AGENTS.md §4). See docs/TESTING.md.
 */
class GoldenCorpusTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "engine").isDirectory) return dir
            dir = dir.parentFile
        }
        fail("cannot find the repository root above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the stored form reads back through GoldenStore within 0,005 dB and exact elsewhere`() {
        val metrics = MetricsReport(listOf(Metric.upper("clicks", 0.0, "count", 0.0, 0.0), Metric.info("lufs", -14.123456789, "LUFS")))
        val original = GoldenFingerprint(
            planJson = "{\"strategyId\":\"x\",\"note\":\"quote \\\" and newline \\n\"}",
            rmsEnvelope20ms = floatArrayOf(-26.136585f, -0.004f, 0.0f, -120f, Float.NEGATIVE_INFINITY, Float.NaN, 3.14159f),
            bandEnvelopes = Array(4) { b -> FloatArray(5) { -10f * b - it * 0.3333f } },
            logSpec64Per250ms = Array(3) { f -> FloatArray(64) { -50.405285f + f + it * 0.017f } },
            pcm16Sha256 = "ab".repeat(32),
            metrics = metrics,
        )
        val dir = File(System.getProperty("java.io.tmpdir"), "muisc-golden-encode-${System.nanoTime()}")
        try {
            val file = GoldenStore(dir).file("recipe:x", "p_q")
            file.parentFile.mkdirs()
            file.writeText(GoldenCorpus.encode(original))
            val back = GoldenStore(dir).load("recipe:x", "p_q") ?: fail("not readable back")
            assertEquals(original.planJson, back.planJson)
            assertEquals(original.pcm16Sha256, back.pcm16Sha256)
            assertEquals(original.metrics, back.metrics)
            fun near(a: FloatArray, b: FloatArray) {
                assertEquals(a.size, b.size)
                for (i in a.indices) {
                    if (a[i].isFinite()) assertTrue(abs(a[i] - b[i]) <= 0.005f + 1e-6f, "element $i: ${a[i]} stored as ${b[i]}")
                    else assertEquals(a[i], b[i], "non-finite element $i")
                }
            }
            near(original.rmsEnvelope20ms, back.rmsEnvelope20ms)
            for (i in original.bandEnvelopes.indices) near(original.bandEnvelopes[i], back.bandEnvelopes[i])
            for (i in original.logSpec64Per250ms.indices) near(original.logSpec64Per250ms[i], back.logSpec64Per250ms[i])
            assertTrue(GoldenCompare.compare(back, original).matches, GoldenCompare.compare(back, original).summary())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `every strategy and built-in recipe renders like its committed golden`() {
        val dir = File(repoRoot(), GoldenStore.DEFAULT_DIR)
        val started = System.nanoTime()
        val run = GoldenCorpus.run()
        val seconds = (System.nanoTime() - started) / 1e9
        println("golden corpus: ${run.outcomes.size} cells, ${run.outcomes.count { it.status == GoldenCorpus.Status.RENDERED }} rendered, " +
            "${run.outcomes.count { it.status == GoldenCorpus.Status.NOT_APPLICABLE }} not applicable in ${"%.1f".format(seconds)} s")

        // Coverage: every strategy (built-in and recipe) must render on at least one pair, or it has no golden at all.
        val uncovered = run.strategies.filter { id -> run.outcomes.none { it.strategyId == id && it.status == GoldenCorpus.Status.RENDERED } }
        assertTrue(uncovered.isEmpty(), "strategies with no rendered pair in the corpus (choose GoldenCorpus.PAIRS so each one applies): $uncovered\n" +
            run.outcomes.filter { it.strategyId in uncovered }.joinToString("\n") { "  ${it.strategyId} / ${it.pairId}: ${it.status} ${it.reason.orEmpty()}" })
        assertTrue(run.strategies.any { it.startsWith("recipe:") }, "the corpus registry carries no built-in recipe: ${run.strategies}")

        if (System.getenv("MUISC_UPDATE_GOLDENS") == "1") {
            val lines = GoldenCorpus.write(run, dir)
            println("MUISC_UPDATE_GOLDENS=1: rewrote ${dir.path} (${lines.size} change(s))")
            for (l in lines) println("  $l")
            return
        }

        val problems = GoldenCorpus.compare(run, dir)
        if (problems.isNotEmpty()) {
            fail("${problems.size} golden problem(s):\n\n" + problems.joinToString("\n\n") + "\n\n" + GoldenCorpus.UPDATE_HINT)
        }
    }
}
