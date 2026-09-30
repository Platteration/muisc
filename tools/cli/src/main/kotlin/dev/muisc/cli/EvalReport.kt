package dev.muisc.cli

import dev.muisc.metrics.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * The three outputs of `muisc eval`: `report.html` (self-contained: inline CSS, no scripts, no external resources),
 * `results.csv` (one row per pair, one value and one verdict column per metric) and `results.json` (the summary,
 * the analysis distributions and every row).
 */
class EvalReport(
    val inputs: List<File>,
    val tracks: Int,
    val skipped: List<Pair<File, String>>,
    val distributions: List<LibraryEval.Distribution>,
    val lowConfidence: List<LibraryEval.LowConfidence>,
    val rows: List<LibraryEval.Row>,
    val seed: Long,
    val failOn: Double?,
    val worstCount: Int,
    val prefsFile: File,
) {
    val failRate: Double get() = LibraryEval.failRate(rows)
    val warnRate: Double get() = LibraryEval.warnRate(rows)
    val errors: Int get() = rows.count { it.error != null }
    val medianRenderMillis: Double get() = LibraryEval.median(rows.filter { it.error == null }.map { it.renderMillis.toDouble() })
    val exceeded: Boolean get() = failOn != null && failRate > failOn

    private val metricIds: List<String> get() = LibraryEval.metricStats(rows).map { it.id }

    // ---- CSV ----------------------------------------------------------------------------------------------------

    fun writeCsv(file: File) {
        val ids = metricIds
        val sb = StringBuilder()
        sb.append(Fmt.csvRow(listOf("pair", "source", "a", "b", "stretch_percent", "camelot_distance", "previous", "strategy", "modifiers", "score",
            "verdict", "render_ms", "seconds", "error") + ids + ids.map { "${it}_verdict" } + listOf("reproduce"))).append('\n')
        for (r in rows) {
            sb.append(Fmt.csvRow(
                listOf(
                    "${r.index}", r.source.name.lowercase(), r.a.path, r.b.path, Fmt.num(r.stretchPercent, 3), "${r.camelotDistance}",
                    r.previous.orEmpty(), r.strategyId.orEmpty(), r.modifiers.joinToString("+"), Fmt.num(r.score, 4),
                    r.verdict.name, "${r.renderMillis}", Fmt.num(r.seconds, 3), r.error.orEmpty(),
                ) + ids.map { id -> r.metrics?.value(id)?.let { csvNum(it) } ?: "" } +
                    ids.map { id -> r.metrics?.verdict(id)?.name ?: "" } + listOf(r.reproduce),
            )).append('\n')
        }
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    // ---- JSON ---------------------------------------------------------------------------------------------------

    fun writeJson(file: File) {
        val root = buildJsonObject {
            putJsonObject("summary") {
                put("tracks", tracks)
                put("skipped", skipped.size)
                put("pairs", rows.size)
                put("seed", seed)
                put("failRate", failRate)
                put("warnRate", warnRate)
                put("renderErrors", errors)
                put("medianRenderMillis", num(medianRenderMillis))
                put("failOn", failOn?.let { JsonPrimitive(it) } ?: JsonNull)
                put("failOnExceeded", exceeded)
                put("prefs", prefsFile.path)
            }
            putJsonArray("inputs") { for (i in inputs) add(i.path) }
            putJsonArray("skippedFiles") { for ((f, why) in skipped) addJsonObject { put("file", f.path); put("reason", why) } }
            putJsonArray("analysis") {
                for (d in distributions) addJsonObject {
                    put("label", d.label); put("min", num(d.min)); put("p10", num(d.p10)); put("median", num(d.median))
                    put("p90", num(d.p90)); put("max", num(d.max)); put("gate", d.gate); put("belowGate", d.below)
                    putJsonArray("histogram") { for (c in d.histogram()) add(c) }
                }
            }
            putJsonArray("lowConfidence") {
                for (l in lowConfidence) addJsonObject { put("file", l.file.path); putJsonArray("reasons") { for (r in l.reasons) add(r) } }
            }
            putJsonArray("strategies") {
                for (s in LibraryEval.strategyStats(rows)) addJsonObject {
                    put("id", s.id); put("chosen", s.chosen); put("fail", s.fails); put("warn", s.warns); put("medianRenderMillis", num(s.medianRenderMillis))
                }
            }
            putJsonArray("metrics") {
                for (m in LibraryEval.metricStats(rows)) addJsonObject {
                    put("id", m.id); put("pass", m.pass); put("warn", m.warn); put("fail", m.fail)
                }
            }
            putJsonArray("worst") { for (w in LibraryEval.worst(rows, worstCount)) add(w.index) }
            putJsonArray("rows") {
                for (r in rows) addJsonObject {
                    put("pair", r.index); put("source", r.source.name.lowercase()); put("a", r.a.path); put("b", r.b.path)
                    put("stretchPercent", num(r.stretchPercent)); put("camelotDistance", r.camelotDistance)
                    put("previous", r.previous?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("strategy", r.strategyId?.let { JsonPrimitive(it) } ?: JsonNull)
                    putJsonArray("modifiers") { for (m in r.modifiers) add(m) }
                    put("score", num(r.score)); put("verdict", r.verdict.name); put("renderMillis", r.renderMillis); put("seconds", num(r.seconds))
                    put("error", r.error?.let { JsonPrimitive(it) } ?: JsonNull)
                    putJsonObject("metrics") {
                        for (m in r.metrics?.metrics.orEmpty()) putJsonObject(m.id) {
                            put("value", num(m.value)); put("verdict", m.verdict.name); put("unit", m.unit)
                        }
                    }
                    put("reproduce", r.reproduce)
                }
            }
        }
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(PRETTY.encodeToString(JsonObject.serializer(), root) + "\n")
    }

    /** A metric value for the CSV: 6 decimals, `NaN` / `Infinity` spelled out (a missing metric is an empty cell). */
    private fun csvNum(v: Double): String = when {
        v.isNaN() -> "NaN"
        v == Double.POSITIVE_INFINITY -> "Infinity"
        v == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> Fmt.num(v, 6)
    }

    /** JSON has no NaN or infinity: those become null. */
    private fun num(v: Double): JsonElement = if (v.isFinite()) JsonPrimitive(v) else JsonNull

    // ---- HTML ---------------------------------------------------------------------------------------------------

    fun writeHtml(file: File) {
        val sb = StringBuilder()
        fun esc(s: String) = Fmt.htmlEscape(s)
        sb.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>Muisc library eval</title>\n<style>\n").append(CSS).append("\n</style></head><body>\n")
        sb.append("<h1>Library evaluation</h1>\n")
        sb.append("<p class=\"sub\">").append(esc(inputs.joinToString(", ") { it.path })).append(" · ").append(tracks).append(" tracks")
        if (skipped.isNotEmpty()) sb.append(" (").append(skipped.size).append(" skipped)")
        sb.append(" · ").append(rows.size).append(" pairs · seed ").append(seed).append("</p>\n")

        // Headline.
        val verdictClass = when {
            exceeded -> "fail"
            failRate > 0 -> "warn"
            else -> "pass"
        }
        sb.append("<div class=\"cards\">")
        card(sb, "FAIL rate", Fmt.pct(failRate), verdictClass, "${rows.count { it.verdict == Verdict.FAIL }} of ${rows.size} pairs" +
            (failOn?.let { " · limit ${Fmt.pct(it)}" + if (exceeded) " — EXCEEDED" else " — ok" } ?: ""))
        card(sb, "WARN rate", Fmt.pct(warnRate), if (warnRate > 0) "warn" else "pass", "${rows.count { it.verdict == Verdict.WARN }} pairs")
        card(sb, "Render errors", "$errors", if (errors > 0) "fail" else "pass", "renders that threw (counted as FAIL)")
        card(sb, "Median render", "${Fmt.num(medianRenderMillis, 0)} ms", "", "render + metrics, per pair")
        sb.append("</div>\n")

        // Analysis.
        sb.append("<h2>Analysis</h2>\n<table><thead><tr><th>value</th><th class=\"n\">min</th><th class=\"n\">p10</th><th class=\"n\">median</th><th class=\"n\">p90</th><th class=\"n\">max</th><th>histogram (0 → 1)</th><th class=\"n\">below gate</th><th>gate</th></tr></thead><tbody>\n")
        for (d in distributions) {
            val h = d.histogram()
            val peak = (h.maxOrNull() ?: 0).coerceAtLeast(1)
            sb.append("<tr><td class=\"id\">").append(esc(d.label)).append("</td>")
            for (v in listOf(d.min, d.p10, d.median, d.p90, d.max)) sb.append("<td class=\"n\">").append(Fmt.num(v)).append("</td>")
            sb.append("<td><span class=\"hist\">")
            for (c in h) sb.append("<span class=\"bar\" style=\"height:").append(4 + 28 * c / peak).append("px\" title=\"").append(c).append("\"></span>")
            sb.append("</span></td>")
            sb.append("<td class=\"n").append(if (d.below > 0) " warn" else "").append("\">").append(d.below).append("</td>")
            sb.append("<td class=\"note\">&lt; ").append(d.gate).append(": ").append(esc(d.gateNote)).append("</td></tr>\n")
        }
        sb.append("</tbody></table>\n")
        if (lowConfidence.isNotEmpty()) {
            sb.append("<details><summary>").append(lowConfidence.size).append(" track(s) below a gate</summary><table><tbody>\n")
            for (l in lowConfidence) sb.append("<tr><td>").append(esc(l.file.path)).append("</td><td>").append(esc(l.reasons.joinToString(", "))).append("</td></tr>\n")
            sb.append("</tbody></table></details>\n")
        }
        if (skipped.isNotEmpty()) {
            sb.append("<details><summary>").append(skipped.size).append(" file(s) skipped</summary><table><tbody>\n")
            for ((f, why) in skipped) sb.append("<tr><td>").append(esc(f.path)).append("</td><td>").append(esc(why)).append("</td></tr>\n")
            sb.append("</tbody></table></details>\n")
        }

        // Strategies.
        sb.append("<h2>What the planner chose</h2>\n<table><thead><tr><th>strategy</th><th class=\"n\">chosen</th><th>share</th><th class=\"n\">FAIL</th><th class=\"n\">WARN</th><th class=\"n\">median ms</th></tr></thead><tbody>\n")
        val rendered = rows.count { it.strategyId != null }.coerceAtLeast(1)
        for (s in LibraryEval.strategyStats(rows)) {
            val share = s.chosen.toDouble() / rendered
            sb.append("<tr><td class=\"id\">").append(esc(s.id)).append("</td><td class=\"n\">").append(s.chosen).append("</td>")
            sb.append("<td><span class=\"share\"><span style=\"width:").append(Fmt.num(share * 100, 1)).append("%\"></span></span> ").append(Fmt.pct(share)).append("</td>")
            sb.append("<td class=\"n").append(if (s.fails > 0) " fail" else "").append("\">").append(s.fails).append("</td>")
            sb.append("<td class=\"n").append(if (s.warns > 0) " warn" else "").append("\">").append(s.warns).append("</td>")
            sb.append("<td class=\"n\">").append(Fmt.num(s.medianRenderMillis, 0)).append("</td></tr>\n")
        }
        sb.append("</tbody></table>\n")

        // Metrics.
        sb.append("<h2>Metrics</h2>\n<table><thead><tr><th>metric</th><th class=\"n\">PASS</th><th class=\"n\">WARN</th><th class=\"n\">FAIL</th><th class=\"n\">FAIL rate</th></tr></thead><tbody>\n")
        for (m in LibraryEval.metricStats(rows)) {
            sb.append("<tr><td class=\"id\">").append(esc(m.id)).append("</td><td class=\"n\">").append(m.pass).append("</td>")
            sb.append("<td class=\"n").append(if (m.warn > 0) " warn" else "").append("\">").append(m.warn).append("</td>")
            sb.append("<td class=\"n").append(if (m.fail > 0) " fail" else "").append("\">").append(m.fail).append("</td>")
            sb.append("<td class=\"n\">").append(Fmt.pct(if (m.total == 0) 0.0 else m.fail.toDouble() / m.total)).append("</td></tr>\n")
        }
        sb.append("</tbody></table>\n")

        // Worst renders.
        val worst = LibraryEval.worst(rows, worstCount)
        sb.append("<h2>Worst renders</h2>\n")
        if (worst.isEmpty()) sb.append("<p class=\"sub\">Every pair passed every metric.</p>\n")
        else {
            sb.append("<p class=\"sub\">Each command re-renders the pair exactly (same seed, previous strategy, prefs, profile and cache) and writes the segment plus an 8 s context render to listen to.</p>\n<ol class=\"worst\">\n")
            for (w in worst) {
                sb.append("<li><span class=\"v ").append(w.verdict.name.lowercase()).append("\">").append(w.verdict.name).append("</span> <b>")
                    .append(esc(w.strategyId ?: "—")).append("</b> · ").append(esc(w.a.name)).append(" → ").append(esc(w.b.name))
                    .append(" <span class=\"note\">(pair ").append(w.index).append(", ").append(esc(w.source.label)).append(")</span><br>")
                    .append(esc(w.problems)).append("<pre>").append(esc(w.reproduce)).append("</pre></li>\n")
            }
            sb.append("</ol>\n")
        }

        // All pairs.
        sb.append("<h2>All pairs</h2>\n<table><thead><tr><th class=\"n\">#</th><th>why</th><th>A → B</th><th class=\"n\">stretch %</th><th class=\"n\">Camelot</th><th>strategy</th><th class=\"n\">score</th><th>verdict</th><th class=\"n\">ms</th><th>problems</th></tr></thead><tbody>\n")
        for (r in rows) {
            sb.append("<tr><td class=\"n\">").append(r.index).append("</td><td>").append(r.source.name.lowercase()).append("</td>")
            sb.append("<td>").append(esc(r.a.name)).append(" → ").append(esc(r.b.name)).append("</td>")
            sb.append("<td class=\"n\">").append(Fmt.num(r.stretchPercent, 1)).append("</td><td class=\"n\">").append(r.camelotDistance).append("</td>")
            sb.append("<td>").append(esc((r.strategyId ?: "—") + if (r.modifiers.isEmpty()) "" else " +" + r.modifiers.joinToString("+"))).append("</td>")
            sb.append("<td class=\"n\">").append(Fmt.num(r.score, 3)).append("</td>")
            sb.append("<td class=\"v ").append(r.verdict.name.lowercase()).append("\">").append(r.verdict.name).append("</td>")
            sb.append("<td class=\"n\">").append(r.renderMillis).append("</td><td class=\"note\">").append(esc(r.problems)).append("</td></tr>\n")
        }
        sb.append("</tbody></table>\n")
        sb.append("<p class=\"sub\">Metrics measure clicks, level jumps, peaks, seam identity and beat alignment. They cannot tell you whether a transition sounds good: listen to the worst ones, and to a few that passed.</p>\n")
        sb.append("</body></html>\n")
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    private fun card(sb: StringBuilder, title: String, value: String, cls: String, note: String) {
        sb.append("<div class=\"card ").append(cls).append("\"><div class=\"t\">").append(Fmt.htmlEscape(title)).append("</div><div class=\"big\">")
            .append(Fmt.htmlEscape(value)).append("</div><div class=\"note\">").append(Fmt.htmlEscape(note)).append("</div></div>")
    }

    private companion object {
        val PRETTY = Json { prettyPrint = true }

        val CSS = """
            :root { color-scheme: light dark; --bg: #fbfbfa; --fg: #1b1b1a; --muted: #666; --line: #ddd; --head: #f0f0ee;
                    --pass: #14622f; --warn-bg: #fff6d6; --warn: #6b5000; --fail-bg: #ffe1e1; --fail: #8a1616; --bar: #4a6fa5; }
            @media (prefers-color-scheme: dark) {
              :root { --bg: #16161a; --fg: #ececed; --muted: #9a9aa2; --line: #33333a; --head: #22222a;
                      --pass: #6ee39a; --warn-bg: #3a3115; --warn: #f0d488; --fail-bg: #451c1c; --fail: #ff9d9d; --bar: #8fb0e0; }
            }
            body { font: 14px/1.5 ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 0; padding: 24px 16px; background: var(--bg); color: var(--fg); }
            h1 { font-size: 20px; margin: 0 0 4px; }
            h2 { font-size: 16px; margin: 28px 0 8px; }
            .sub, .note { color: var(--muted); }
            .sub { margin: 0 0 16px; }
            table { border-collapse: collapse; width: 100%; display: block; overflow-x: auto; }
            th, td { border: 1px solid var(--line); padding: 5px 8px; text-align: left; vertical-align: middle; }
            thead th { background: var(--head); }
            .n { text-align: right; font-variant-numeric: tabular-nums; }
            td.id { font-weight: 600; }
            .pass { color: var(--pass); }
            .warn { background: var(--warn-bg); color: var(--warn); }
            .fail { background: var(--fail-bg); color: var(--fail); font-weight: 600; }
            .v { padding: 1px 6px; border-radius: 3px; font-weight: 600; }
            .cards { display: flex; flex-wrap: wrap; gap: 12px; }
            .card { border: 1px solid var(--line); border-radius: 6px; padding: 10px 14px; min-width: 160px; flex: 1 1 160px; }
            .card .t { color: var(--muted); font-size: 12px; }
            .card .big { font-size: 22px; font-weight: 700; font-variant-numeric: tabular-nums; }
            .hist { display: inline-flex; align-items: flex-end; gap: 2px; height: 32px; }
            .hist .bar { display: inline-block; width: 12px; background: var(--bar); }
            .share { display: inline-block; width: 120px; height: 8px; background: var(--line); vertical-align: middle; }
            .share span { display: block; height: 8px; background: var(--bar); }
            pre { white-space: pre-wrap; word-break: break-all; background: var(--head); padding: 6px 8px; border-radius: 4px; margin: 4px 0 12px; font-size: 12px; }
            ol.worst li { margin-bottom: 6px; }
            details { margin: 8px 0; }
        """.trimIndent()
    }
}
