package dev.muisc.metrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Outcome of one numeric check. `PASS` < `WARN` < `FAIL` (the ordinal is the severity, see [worst]).
 *
 * A `WARN` never blocks a render from being installed; a `FAIL` does (see `RenderGate` in DESIGN.md §2.7).
 */
@Serializable
enum class Verdict {
    PASS, WARN, FAIL;

    /** True for [WARN] and [FAIL]. */
    val isProblem: Boolean get() = this != PASS

    /** True when this verdict is strictly more severe than [other]. */
    fun worseThan(other: Verdict): Boolean = ordinal > other.ordinal

    companion object {
        /** The more severe of the two. */
        fun worst(a: Verdict, b: Verdict): Verdict = if (a.ordinal >= b.ordinal) a else b
    }
}

/**
 * One measured quantity with its thresholds and the verdict they produced.
 *
 * [warnAt] / [failAt] are the thresholds that were applied, kept in the metric so a report is self-describing
 * (the CLI and the Lab print "4.8 dB (warn 4, fail 6)" without knowing the metric). A threshold of `NaN` means
 * "this metric has no threshold in that direction" — `NaN` compares false against everything, so the
 * corresponding verdict can never be reached.
 *
 * The direction of a threshold is a property of the metric, not of this record: [upper] builds metrics where a
 * higher value is worse (clicks, level jump, true peak), [lower] metrics where a lower value is worse (seam
 * cross-correlation, stereo correlation).
 */
@Serializable
data class Metric(
    val id: String,
    val value: Double,
    val unit: String,
    val verdict: Verdict,
    val warnAt: Double,
    val failAt: Double,
) {
    /** `"clicks = 2 count (warn > 0, fail > 0) FAIL"`. */
    override fun toString(): String {
        val v = MetricsReport.format(value)
        val u = if (unit.isEmpty()) "" else " $unit"
        val thresholds = buildString {
            if (!warnAt.isNaN()) append("warn ").append(MetricsReport.format(warnAt))
            if (!failAt.isNaN()) { if (isNotEmpty()) append(", "); append("fail ").append(MetricsReport.format(failAt)) }
        }
        return "$id = $v$u" + (if (thresholds.isEmpty()) "" else " ($thresholds)") + " $verdict"
    }

    companion object {
        /** Metric where a HIGHER value is worse: `value > failAt` → FAIL, `value > warnAt` → WARN. */
        fun upper(id: String, value: Double, unit: String = "", warnAt: Double = Double.NaN, failAt: Double = Double.NaN): Metric =
            Metric(id, value, unit, if (value > failAt) Verdict.FAIL else if (value > warnAt) Verdict.WARN else Verdict.PASS, warnAt, failAt)

        /** Metric where a LOWER value is worse: `value < failAt` → FAIL, `value < warnAt` → WARN. */
        fun lower(id: String, value: Double, unit: String = "", warnAt: Double = Double.NaN, failAt: Double = Double.NaN): Metric =
            Metric(id, value, unit, if (value < failAt) Verdict.FAIL else if (value < warnAt) Verdict.WARN else Verdict.PASS, warnAt, failAt)

        /** Metric that is only reported, never judged (always [Verdict.PASS]). */
        fun info(id: String, value: Double, unit: String = ""): Metric =
            Metric(id, value, unit, Verdict.PASS, Double.NaN, Double.NaN)
    }
}

/**
 * The result of a metric run: an ordered list of [Metric]s (the order is the order in which
 * [ArtifactMetrics] computes them, so CSV columns are stable across runs).
 *
 * Serialisable with kotlinx.serialization; the JSON form allows `NaN` / `Infinity` (thresholds and
 * dB values legitimately use them), so it is read back with [fromJson], not a stock `Json`.
 */
@Serializable
data class MetricsReport(val metrics: List<Metric> = emptyList()) {

    /** The most severe verdict in the report ([Verdict.PASS] when empty). */
    val worst: Verdict get() {
        var w = Verdict.PASS
        for (m in metrics) if (m.verdict.ordinal > w.ordinal) w = m.verdict
        return w
    }

    /** True when nothing failed (warnings are allowed). */
    val installable: Boolean get() = worst != Verdict.FAIL

    fun metric(id: String): Metric? = metrics.firstOrNull { it.id == id }

    fun value(id: String): Double? = metric(id)?.value

    fun verdict(id: String): Verdict? = metric(id)?.verdict

    val failures: List<Metric> get() = metrics.filter { it.verdict == Verdict.FAIL }
    val warnings: List<Metric> get() = metrics.filter { it.verdict == Verdict.WARN }

    /** This report with [others] appended; ids already present are kept (first one wins). */
    operator fun plus(other: MetricsReport): MetricsReport {
        val ids = metrics.mapTo(HashSet()) { it.id }
        return MetricsReport(metrics + other.metrics.filter { it.id !in ids })
    }

    fun toJson(pretty: Boolean = false): String =
        (if (pretty) PRETTY_JSON else JSON).encodeToString(this)

    /** CSV header matching [toCsvRow] for this report's metric ids. */
    fun csvHeader(): String = csvHeader(metrics.map { it.id })

    /** `worst,<value>,<value>,...` in the report's metric order. */
    fun toCsvRow(): String = buildString {
        append(worst.name)
        for (m in metrics) { append(','); append(format(m.value)) }
    }

    /** Multi-line human-readable listing, worst metrics first. */
    fun summary(): String = buildString {
        append("MetricsReport: ").append(worst).append(" (").append(metrics.size).append(" metrics)")
        for (m in metrics.sortedByDescending { it.verdict.ordinal }) append('\n').append("  ").append(m)
    }

    override fun toString(): String = "MetricsReport($worst, ${metrics.size} metrics)"

    companion object {
        val EMPTY = MetricsReport(emptyList())

        /** JSON codec for metrics: special floating point values are allowed, defaults are written. */
        val JSON: Json = Json {
            allowSpecialFloatingPointValues = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val PRETTY_JSON: Json = Json(JSON) { prettyPrint = true }

        fun fromJson(json: String): MetricsReport = JSON.decodeFromString(serializer(), json)

        /** `worst,<id>,<id>,...` — the header of [toCsvRow] for a known metric order. */
        fun csvHeader(ids: List<String>): String = buildString {
            append("worst")
            for (id in ids) { append(','); append(id) }
        }

        /** CSV / display form of a metric value: 6 significant digits, `inf` / `-inf` / `nan` for the specials. */
        fun format(v: Double): String = when {
            v.isNaN() -> "nan"
            v == Double.POSITIVE_INFINITY -> "inf"
            v == Double.NEGATIVE_INFINITY -> "-inf"
            v == Math.rint(v) && Math.abs(v) < 1e9 -> v.toLong().toString()
            else -> String.format(Locale.ROOT, "%.6g", v)
        }
    }
}
