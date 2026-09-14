package dev.muisc.cli

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.metrics.Metric
import dev.muisc.metrics.MetricsReport
import dev.muisc.metrics.Verdict
import dev.muisc.transitions.TransitionPlan
import java.util.Locale

/** Number, time and table formatting shared by every command (always `Locale.ROOT`, so output is machine-parsable). */
object Fmt {

    fun num(v: Double, decimals: Int = 2): String = when {
        v.isNaN() -> "n/a"
        v == Double.POSITIVE_INFINITY -> "+inf"
        v == Double.NEGATIVE_INFINITY -> "-inf"
        else -> String.format(Locale.ROOT, "%.${decimals}f", v)
    }

    /** `12.34 s` / `1:23.4` for anything over a minute. */
    fun sec(v: Double): String = when {
        v.isNaN() -> "n/a"
        v < 60.0 -> "${num(v, 2)} s"
        else -> String.format(Locale.ROOT, "%d:%05.2f", (v / 60).toInt(), v % 60)
    }

    fun db(v: Double, decimals: Int = 1): String = if (v.isNaN() || v.isInfinite()) "n/a" else "${num(v, decimals)} dB"
    fun db(v: Float, decimals: Int = 1): String = db(v.toDouble(), decimals)

    fun pct(v: Double): String = "${num(v * 100, 1)} %"

    fun frames(f: Long, sampleRate: Int): String = "$f (${sec(f.toDouble() / sampleRate)})"

    /** `bpm` → bar count of a segment that long. */
    fun bars(frames: Int, bpm: Double, sampleRate: Int, beatsPerBar: Int = 4): Double {
        if (bpm <= 0 || sampleRate <= 0) return Double.NaN
        val beats = frames.toDouble() / sampleRate * (bpm / 60.0)
        return beats / beatsPerBar
    }

    /** A left-aligned fixed-width table; the first row is the header. */
    fun table(rows: List<List<String>>, indent: String = ""): String {
        if (rows.isEmpty()) return ""
        val cols = rows.maxOf { it.size }
        val width = IntArray(cols)
        for (r in rows) for (i in r.indices) width[i] = maxOf(width[i], r[i].length)
        return rows.joinToString("\n") { r ->
            indent + r.mapIndexed { i, c -> if (i == r.size - 1) c else c.padEnd(width[i]) }.joinToString("  ").trimEnd()
        }
    }

    fun verdictMark(v: Verdict): String = when (v) {
        Verdict.PASS -> "PASS"
        Verdict.WARN -> "WARN"
        Verdict.FAIL -> "FAIL"
    }

    fun metric(m: Metric): List<String> =
        listOf(verdictMark(m.verdict), m.id, num(m.value, 4) + (if (m.unit.isNotEmpty()) " ${m.unit}" else ""), "warn ${num(m.warnAt, 3)} / fail ${num(m.failAt, 3)}")

    /** Metrics table, worst first, with a verdict headline. */
    fun metrics(report: MetricsReport, indent: String = "  "): String = buildString {
        append(indent).append("metrics: ").append(verdictMark(report.worst))
        val problems = report.metrics.filter { it.verdict != Verdict.PASS }
        if (problems.isNotEmpty()) append(" — ").append(problems.joinToString(", ") { "${it.id} ${num(it.value, 3)}" })
        append('\n')
        append(table(listOf(listOf("verdict", "metric", "value", "thresholds")) + report.metrics.sortedByDescending { it.verdict.ordinal }.map { metric(it) }, indent))
    }

    /** One-line summary of a plan: strategy, modifiers, length in bars and frames, windows. */
    fun planLine(plan: TransitionPlan, bpm: Double, sampleRate: Int): String = buildString {
        append(plan.strategyId)
        if (plan.modifiers.isNotEmpty()) append(" +").append(plan.modifiers.joinToString("+"))
        append("  ").append(num(bars(plan.expectedOutputFrames, bpm, sampleRate), 1)).append(" bars")
        append(" / ").append(plan.expectedOutputFrames).append(" frames (").append(sec(plan.expectedOutputFrames.toDouble() / sampleRate)).append(')')
        append("  aExit ").append(plan.aExitFrame).append(" bEntry ").append(plan.bEntryFrame)
    }

    /** `Am (8A)` — key with its Camelot code. */
    fun key(analysis: TrackAnalysis): String = "${analysis.key.key.shortName} (${analysis.key.camelot.code})"

    fun csvEscape(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun csvRow(cells: List<String>): String = cells.joinToString(",") { csvEscape(it) }

    fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
