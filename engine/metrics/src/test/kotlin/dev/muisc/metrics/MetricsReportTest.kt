package dev.muisc.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsReportTest {

    private val report = MetricsReport(
        listOf(
            Metric.upper("clicks", 0.0, "count", 0.0, 0.0),
            Metric.upper("levelJumpDb", 4.5, "dB", 4.0, 6.0),
            Metric.lower("seamCorrelation", 0.9995, "", 0.9999, 0.999),
            Metric.upper("silenceGapMs", 12.0, "ms", 50.0, Double.NaN),
            Metric.info("renderMillis", 42.0, "ms"),
        ),
    )

    @Test
    fun thresholdDirectionDecidesTheVerdict() {
        assertEquals(Verdict.PASS, Metric.upper("x", 3.9, warnAt = 4.0, failAt = 6.0).verdict)
        assertEquals(Verdict.WARN, Metric.upper("x", 4.1, warnAt = 4.0, failAt = 6.0).verdict)
        assertEquals(Verdict.FAIL, Metric.upper("x", 6.1, warnAt = 4.0, failAt = 6.0).verdict)
        assertEquals(Verdict.PASS, Metric.lower("x", 1.0, warnAt = 0.9999, failAt = 0.999).verdict)
        assertEquals(Verdict.WARN, Metric.lower("x", 0.99985, warnAt = 0.9999, failAt = 0.999).verdict)
        assertEquals(Verdict.FAIL, Metric.lower("x", 0.5, warnAt = 0.9999, failAt = 0.999).verdict)
        // A NaN threshold is "no threshold in that direction".
        assertEquals(Verdict.WARN, Metric.upper("x", 1e9, warnAt = 50.0, failAt = Double.NaN).verdict)
        assertEquals(Verdict.PASS, Metric.info("x", 1e9).verdict)
        // -inf passes an upper threshold (silence, a residual of exactly zero).
        assertEquals(Verdict.PASS, Metric.upper("x", Double.NEGATIVE_INFINITY, warnAt = -60.0, failAt = -40.0).verdict)
    }

    @Test
    fun worstAndLookupsReflectTheMetrics() {
        assertEquals(Verdict.WARN, report.worst)
        assertTrue(report.installable)
        assertEquals(4.5, report.value("levelJumpDb"))
        assertEquals(Verdict.WARN, report.verdict("seamCorrelation"))
        assertNull(report.value("nope"))
        assertNull(report.verdict("nope"))
        assertEquals(listOf("levelJumpDb", "seamCorrelation"), report.warnings.map { it.id })
        assertEquals(emptyList(), report.failures)
        assertEquals(Verdict.PASS, MetricsReport.EMPTY.worst)

        val failing = MetricsReport(report.metrics + Metric.upper("nanInf", 3.0, "count", 0.0, 0.0))
        assertEquals(Verdict.FAIL, failing.worst)
        assertTrue(!failing.installable)
        assertEquals(listOf("nanInf"), failing.failures.map { it.id })
    }

    @Test
    fun verdictOrdering() {
        assertTrue(Verdict.FAIL.worseThan(Verdict.WARN))
        assertTrue(Verdict.WARN.worseThan(Verdict.PASS))
        assertTrue(!Verdict.PASS.worseThan(Verdict.PASS))
        assertEquals(Verdict.FAIL, Verdict.worst(Verdict.FAIL, Verdict.WARN))
        assertEquals(Verdict.WARN, Verdict.worst(Verdict.PASS, Verdict.WARN))
        assertTrue(Verdict.WARN.isProblem && Verdict.FAIL.isProblem && !Verdict.PASS.isProblem)
    }

    @Test
    fun jsonRoundTripKeepsEveryFieldIncludingSpecialValues() {
        val withSpecials = MetricsReport(
            report.metrics + listOf(
                Metric.upper("tailContainedDb", Double.NEGATIVE_INFINITY, "dB", -60.0, -40.0),
                Metric.upper("beatAlignmentMs", 3.0, "ms", 5.0, Double.NaN),
            ),
        )
        val json = withSpecials.toJson()
        val back = MetricsReport.fromJson(json)
        assertEquals(withSpecials.metrics.size, back.metrics.size)
        assertEquals(withSpecials, back)
        assertEquals(Double.NEGATIVE_INFINITY, back.value("tailContainedDb"))
        assertTrue(back.metric("beatAlignmentMs")!!.failAt.isNaN())
        assertEquals(json, back.toJson())
        assertTrue(withSpecials.toJson(pretty = true).contains('\n'))
        assertEquals(withSpecials, MetricsReport.fromJson(withSpecials.toJson(pretty = true)))
    }

    @Test
    fun csvHeaderAndRowLineUp() {
        val header = report.csvHeader().split(',')
        val row = report.toCsvRow().split(',')
        assertEquals(header.size, row.size)
        assertEquals("worst", header[0])
        assertEquals(listOf("clicks", "levelJumpDb", "seamCorrelation", "silenceGapMs", "renderMillis"), header.drop(1))
        assertEquals("WARN", row[0])
        assertEquals("0", row[1])
        assertEquals("4.50000", row[2])
        assertEquals(MetricsReport.csvHeader(report.metrics.map { it.id }), report.csvHeader())
    }

    @Test
    fun valueFormattingIsStableAndReadable() {
        assertEquals("nan", MetricsReport.format(Double.NaN))
        assertEquals("inf", MetricsReport.format(Double.POSITIVE_INFINITY))
        assertEquals("-inf", MetricsReport.format(Double.NEGATIVE_INFINITY))
        assertEquals("0", MetricsReport.format(0.0))
        assertEquals("-50", MetricsReport.format(-50.0))
        assertEquals("0.00100000", MetricsReport.format(1e-3))
        assertEquals("4.50000", MetricsReport.format(4.5))
    }

    @Test
    fun reportsCanBeMerged() {
        val extra = MetricsReport(listOf(Metric.upper("clicks", 9.0, "count", 0.0, 0.0), Metric.upper("dcOffsetDb", -80.0, "dBFS", Double.NaN, -50.0)))
        val merged = report + extra
        assertEquals(report.metrics.size + 1, merged.metrics.size)
        assertEquals(0.0, merged.value("clicks"), "the first report wins for ids it already has")
        assertEquals(-80.0, merged.value("dcOffsetDb"))
    }

    @Test
    fun summaryListsTheWorstFirst() {
        val lines = report.summary().lines()
        assertTrue(lines[0].startsWith("MetricsReport: WARN"))
        assertTrue(lines[1].contains("levelJumpDb") || lines[1].contains("seamCorrelation"), report.summary())
        assertTrue(lines.last().contains("PASS"))
        assertTrue(report.summary().contains("warn 4, fail 6"))
    }
}
