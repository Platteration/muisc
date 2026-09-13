package dev.muisc.metrics

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden renders (DESIGN.md §9): a fingerprint round-trips through JSON unchanged, an identical render produces
 * no diff at all, and a render that is merely 1 dB louder — audio a plain hash comparison could only call
 * "different" — is reported as an envelope difference, band by band.
 */
class GoldenFingerprintTest {

    private val case = MetricsFixtures.crossfade()
    private val golden by lazy { GoldenFingerprint.of(case.rendered) }

    @Test
    fun fingerprintDescribesTheRender() {
        val fp = golden
        val blocks = case.audio.frames / Signals.msFrames(GoldenFingerprint.ENVELOPE_MS, case.audio.sampleRate)
        assertEquals(blocks, fp.rmsEnvelope20ms.size)
        assertEquals(GoldenFingerprint.BANDS, fp.bandEnvelopes.size)
        for (band in fp.bandEnvelopes) assertEquals(blocks, band.size)
        assertEquals(case.audio.frames / Signals.msFrames(GoldenFingerprint.SPECTRUM_MS, case.audio.sampleRate), fp.logSpec64Per250ms.size)
        for (frame in fp.logSpec64Per250ms) assertEquals(GoldenFingerprint.SPECTRUM_BANDS, frame.size)
        assertEquals(64, fp.pcm16Sha256.length, "sha-256 hex")
        assertEquals(case.plan, fp.plan())
        // The fingerprint's own metrics are taken without the sources, so the level check falls back to the
        // render's own onsets and may warn; nothing may FAIL. With the sources the same render is clean.
        assertTrue(fp.metrics.worst != Verdict.FAIL, fp.metrics.summary())
        assertEquals(
            Verdict.PASS,
            GoldenFingerprint.of(case.rendered, ArtifactMetrics.evaluate(case.rendered, case.input)).metrics.worst,
        )
        // The bands really are bands: four different envelopes, none louder than the full-band one.
        val bandLevels = fp.bandEnvelopes.map { it.average() }
        assertEquals(4, bandLevels.distinct().size, "band levels $bandLevels")
        val full = fp.rmsEnvelope20ms.average()
        assertTrue(bandLevels.all { it < full + 0.5 }, "band levels $bandLevels vs full $full")
        assertTrue(bandLevels.last() < full - 3.0, "the 4-22 kHz band carries little of this material")
        // Fingerprinting is deterministic.
        assertEquals(fp, GoldenFingerprint.of(case.rendered))
        assertEquals(fp.pcm16Sha256, GoldenFingerprint.pcm16Sha256(case.audio.copy()))
    }

    @Test
    fun jsonRoundTrip() {
        val json = golden.toJson()
        val back = GoldenFingerprint.fromJson(json)
        assertEquals(golden, back)
        assertEquals(json, back.toJson())
        assertEquals(golden.hashCode(), back.hashCode())
        assertEquals(golden.metrics, back.metrics)
        assertTrue(GoldenCompare.compare(golden, back).matches)
    }

    @Test
    fun anIdenticalRenderProducesNoDiff() {
        val diff = GoldenCompare.compare(golden, GoldenFingerprint.of(case.rendered), strict = true)
        assertTrue(diff.matches, diff.summary())
        assertTrue(diff.planEqual)
        assertTrue(diff.pcm16HashEqual)
        assertEquals(0.0, diff.rmsEnvelopeRmseDb)
        assertEquals(0.0, diff.spectrumRmseDb)
        assertEquals(0.0, diff.spectrumMaxDiffDb)
        assertEquals(emptyList(), diff.metricRegressions)
        assertEquals(emptyList(), diff.issues)
        assertTrue(diff.summary().startsWith("golden MATCH"))
    }

    @Test
    fun oneDecibelLouderIsReportedAsAnEnvelopeDifference() {
        val louder = MetricsFixtures.mutated(case.rendered) { it.applyGainInPlace(1.1220185f) } // +1 dB
        val diff = GoldenCompare.compare(golden, GoldenFingerprint.of(louder))
        assertTrue(!diff.matches, diff.summary())
        assertTrue(diff.planEqual, "the plan did not change, only the audio")
        assertEquals(1.0, diff.rmsEnvelopeRmseDb, 0.05)
        assertEquals(GoldenFingerprint.BANDS, diff.bandEnvelopeRmseDb.size)
        for (b in diff.bandEnvelopeRmseDb) assertEquals(1.0, b, 0.05)
        assertEquals(1.0, diff.spectrumMaxDiffDb, 0.05)
        assertTrue(!diff.pcm16HashEqual)
        assertTrue(diff.issues.any { it.startsWith("rms envelope RMSE") }, diff.issues.toString())
        assertTrue(diff.issues.count { it.contains("envelope RMSE") } == 1 + GoldenFingerprint.BANDS)
        assertTrue(diff.summary().contains("MISMATCH"))
    }

    @Test
    fun aQuarterDecibelStaysInsideTheTolerance() {
        val louder = MetricsFixtures.mutated(case.rendered) { it.applyGainInPlace(1.0292005f) } // +0.25 dB
        val diff = GoldenCompare.compare(golden, GoldenFingerprint.of(louder))
        assertEquals(0.25, diff.rmsEnvelopeRmseDb, 0.05)
        assertTrue(diff.matches, diff.summary())
        // ... unless the hash is demanded.
        assertTrue(!GoldenCompare.compare(golden, GoldenFingerprint.of(louder), strict = true).matches)
    }

    @Test
    fun aChangedPlanAndAWorseMetricAreBothReported() {
        val other = MetricsFixtures.crossfade(dev.muisc.transitions.Params.EMPTY.with("fadeSec", 4.0), seed = 5L)
        val diff = GoldenCompare.compare(golden, GoldenFingerprint.of(other.rendered))
        assertTrue(!diff.planEqual)
        assertTrue(diff.issues.first().startsWith("plan differs"), diff.summary())

        val clicked = MetricsFixtures.mutated(case.rendered) { buf ->
            val f = MetricsFixtures.onsetFreeFrame(case, buf.frames / 2, 80.0)
            for (ch in buf.channels) ch[f] += 0.5f
        }
        val regressed = GoldenCompare.compare(golden, GoldenFingerprint.of(clicked))
        val click = assertNotNull(regressed.metricRegressions.firstOrNull { it.id == ArtifactMetrics.CLICKS }, regressed.summary())
        assertEquals(Verdict.PASS, click.goldenVerdict)
        assertEquals(Verdict.FAIL, click.actualVerdict)
        assertTrue(!regressed.matches)
        assertTrue(regressed.issues.any { it.startsWith("metric regression") })
        // A metric that got BETTER is not a regression.
        assertEquals(emptyList(), GoldenCompare.regressions(GoldenFingerprint.of(clicked).metrics, golden.metrics))
    }

    @Test
    fun storeRoundTripsByStrategyAndPair() {
        val dir = Files.createTempDirectory("muisc-golden").toFile()
        try {
            val store = GoldenStore(dir)
            assertNull(store.load("crossfade", "A120-B126"))
            assertTrue(!store.exists("crossfade", "A120-B126"))
            val file = store.save("crossfade", "A120-B126", golden)
            assertTrue(file.isFile)
            assertEquals(dir.resolve("crossfade").resolve("A120-B126.json"), file)
            assertTrue(store.exists("crossfade", "A120-B126"))
            val loaded = assertNotNull(store.load("crossfade", "A120-B126"))
            assertEquals(golden, loaded)
            assertTrue(GoldenCompare.compare(loaded, GoldenFingerprint.of(case.rendered), strict = true).matches)
            assertEquals(listOf("crossfade"), store.strategies())
            assertEquals(listOf("A120-B126"), store.pairs("crossfade"))
            assertEquals(emptyList(), store.pairs("bassSwap"))
            assertTrue(store.delete("crossfade", "A120-B126"))
            assertNull(store.load("crossfade", "A120-B126"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun idsAreSanitisedIntoPaths() {
        assertEquals("a_b", GoldenStore.sanitize("a/b"))
        assertEquals("120_126_key8A", GoldenStore.sanitize("120>126 key8A"))
        assertEquals("cross-fade.v2_x", GoldenStore.sanitize("cross-fade.v2:x"))
    }
}
