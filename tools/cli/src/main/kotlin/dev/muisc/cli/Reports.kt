package dev.muisc.cli

import dev.muisc.metrics.MetricsReport
import dev.muisc.metrics.Verdict
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** CSV, HTML and SVG writers for the comparison commands (`ab`, `sweep`, `score`). */
object Reports {

    /** One row of an A/B or sweep table. */
    class Row(
        val id: String,
        val wav: String,
        val contextWav: String?,
        val extra: Map<String, String>,
        val metrics: MetricsReport,
        /** Playback gain that level-matches this render to the loudest one in the set (linear, ≤ 1). */
        var playbackGain: Double = 1.0,
        val lufs: Double = Double.NaN,
    )

    /** Sets [Row.playbackGain] so every row plays back at the level of the quietest render (no clipping). */
    fun levelMatch(rows: List<Row>) {
        val quietest = rows.mapNotNull { it.lufs.takeIf { v -> v.isFinite() } }.minOrNull() ?: return
        for (r in rows) {
            r.playbackGain = if (r.lufs.isFinite()) Math.pow(10.0, (quietest - r.lufs) / 20.0).coerceIn(0.01, 1.0) else 1.0
        }
    }

    fun metricIds(rows: List<Row>): List<String> {
        val ids = LinkedHashSet<String>()
        for (r in rows) for (m in r.metrics.metrics) ids += m.id
        return ids.toList()
    }

    fun extraKeys(rows: List<Row>): List<String> {
        val keys = LinkedHashSet<String>()
        for (r in rows) keys += r.extra.keys
        return keys.toList()
    }

    fun writeCsv(file: File, rows: List<Row>, idHeader: String = "id") {
        val extras = extraKeys(rows)
        val ids = metricIds(rows)
        val sb = StringBuilder()
        sb.append(Fmt.csvRow(listOf(idHeader) + extras + listOf("verdict") + ids + ids.map { "${it}_verdict" })).append('\n')
        for (r in rows) {
            sb.append(Fmt.csvRow(
                listOf(r.id) + extras.map { r.extra[it].orEmpty() } + listOf(r.metrics.worst.name) +
                    ids.map { id -> r.metrics.value(id)?.let { fmt(it) } ?: "" } +
                    ids.map { id -> r.metrics.verdict(id)?.name ?: "" },
            )).append('\n')
        }
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    private fun fmt(v: Double): String = when {
        v.isNaN() -> "NaN"
        v == Double.POSITIVE_INFINITY -> "Infinity"
        v == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> String.format(Locale.ROOT, "%.6g", v)
    }

    /**
     * A self-contained comparison page: one level-matched `<audio>` player per render (plus the context render
     * when there is one) and the metrics table, colour-coded by verdict. No JavaScript beyond setting the volume.
     */
    fun writeHtml(file: File, title: String, subtitle: String, rows: List<Row>) {
        val extras = extraKeys(rows)
        val ids = metricIds(rows)
        val sb = StringBuilder()
        sb.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(Fmt.htmlEscape(title)).append("</title>\n<style>\n").append(CSS).append("\n</style></head><body>\n")
        sb.append("<h1>").append(Fmt.htmlEscape(title)).append("</h1>\n")
        sb.append("<p class=\"sub\">").append(Fmt.htmlEscape(subtitle)).append("</p>\n")
        sb.append("<p class=\"sub\">Players are level-matched: each is attenuated to the quietest render so you compare the move, not the loudness.</p>\n")
        sb.append("<table><thead><tr><th>id</th>")
        for (e in extras) sb.append("<th>").append(Fmt.htmlEscape(e)).append("</th>")
        sb.append("<th>segment</th><th>in context</th><th>verdict</th>")
        for (id in ids) sb.append("<th>").append(Fmt.htmlEscape(id)).append("</th>")
        sb.append("</tr></thead><tbody>\n")
        for (r in rows) {
            sb.append("<tr><td class=\"id\">").append(Fmt.htmlEscape(r.id)).append("</td>")
            for (e in extras) sb.append("<td>").append(Fmt.htmlEscape(r.extra[e].orEmpty())).append("</td>")
            sb.append("<td>").append(player(r.wav, r.playbackGain)).append("</td>")
            sb.append("<td>").append(r.contextWav?.let { player(it, r.playbackGain) } ?: "—").append("</td>")
            sb.append("<td class=\"v ").append(r.metrics.worst.name.lowercase()).append("\">").append(r.metrics.worst.name).append("</td>")
            for (id in ids) {
                val m = r.metrics.metric(id)
                sb.append("<td class=\"n ").append((m?.verdict ?: Verdict.PASS).name.lowercase()).append("\">")
                    .append(m?.let { Fmt.num(it.value, 3) } ?: "—").append("</td>")
            }
            sb.append("</tr>\n")
        }
        sb.append("</tbody></table>\n")
        sb.append("<script>document.querySelectorAll('audio[data-gain]').forEach(function(a){a.volume=parseFloat(a.dataset.gain);});</script>\n")
        sb.append("</body></html>\n")
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    private fun player(src: String, gain: Double): String =
        "<audio controls preload=\"none\" data-gain=\"${Fmt.num(gain, 4)}\" src=\"${Fmt.htmlEscape(src)}\"></audio>"

    /** A plain line plot of [yLabel] against [xLabel] — no library, just an SVG polyline with ticks. */
    fun writeSvg(file: File, title: String, xLabel: String, yLabel: String, points: List<Pair<Double, Double>>) {
        val w = 720
        val h = 380
        val left = 72
        val right = 24
        val top = 48
        val bottom = 56
        val usable = points.filter { it.first.isFinite() && it.second.isFinite() }
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\" font-family=\"ui-sans-serif, system-ui, sans-serif\">\n")
        sb.append("<rect width=\"$w\" height=\"$h\" fill=\"#ffffff\"/>\n")
        sb.append("<text x=\"${w / 2}\" y=\"28\" text-anchor=\"middle\" font-size=\"16\" fill=\"#111\">").append(Fmt.htmlEscape(title)).append("</text>\n")
        if (usable.isEmpty()) {
            sb.append("<text x=\"${w / 2}\" y=\"${h / 2}\" text-anchor=\"middle\" font-size=\"13\" fill=\"#777\">no finite values to plot</text>\n</svg>\n")
            file.absoluteFile.parentFile?.mkdirs(); file.writeText(sb.toString()); return
        }
        var x0 = usable.minOf { it.first }; var x1 = usable.maxOf { it.first }
        var y0 = usable.minOf { it.second }; var y1 = usable.maxOf { it.second }
        if (abs(x1 - x0) < 1e-12) { x0 -= 0.5; x1 += 0.5 }
        if (abs(y1 - y0) < 1e-12) { y0 -= 0.5; y1 += 0.5 }
        val pad = (y1 - y0) * 0.08
        y0 -= pad; y1 += pad
        fun px(v: Double) = left + (v - x0) / (x1 - x0) * (w - left - right)
        fun py(v: Double) = h - bottom - (v - y0) / (y1 - y0) * (h - top - bottom)

        sb.append("<line x1=\"$left\" y1=\"${h - bottom}\" x2=\"${w - right}\" y2=\"${h - bottom}\" stroke=\"#333\"/>\n")
        sb.append("<line x1=\"$left\" y1=\"$top\" x2=\"$left\" y2=\"${h - bottom}\" stroke=\"#333\"/>\n")
        for (i in 0..4) {
            val xv = x0 + (x1 - x0) * i / 4.0
            val yv = y0 + (y1 - y0) * i / 4.0
            sb.append("<line x1=\"${fmtN(px(xv))}\" y1=\"${h - bottom}\" x2=\"${fmtN(px(xv))}\" y2=\"${h - bottom + 5}\" stroke=\"#333\"/>\n")
            sb.append("<text x=\"${fmtN(px(xv))}\" y=\"${h - bottom + 20}\" text-anchor=\"middle\" font-size=\"11\" fill=\"#333\">").append(Fmt.num(xv, 3)).append("</text>\n")
            sb.append("<line x1=\"${left - 5}\" y1=\"${fmtN(py(yv))}\" x2=\"$left\" y2=\"${fmtN(py(yv))}\" stroke=\"#333\"/>\n")
            sb.append("<line x1=\"$left\" y1=\"${fmtN(py(yv))}\" x2=\"${w - right}\" y2=\"${fmtN(py(yv))}\" stroke=\"#eee\"/>\n")
            sb.append("<text x=\"${left - 9}\" y=\"${fmtN(py(yv) + 4)}\" text-anchor=\"end\" font-size=\"11\" fill=\"#333\">").append(Fmt.num(yv, 3)).append("</text>\n")
        }
        val sorted = usable.sortedBy { it.first }
        sb.append("<polyline fill=\"none\" stroke=\"#1f77b4\" stroke-width=\"2\" points=\"")
            .append(sorted.joinToString(" ") { "${fmtN(px(it.first))},${fmtN(py(it.second))}" }).append("\"/>\n")
        for (p in sorted) sb.append("<circle cx=\"${fmtN(px(p.first))}\" cy=\"${fmtN(py(p.second))}\" r=\"3.5\" fill=\"#1f77b4\"/>\n")
        sb.append("<text x=\"${w / 2}\" y=\"${h - 12}\" text-anchor=\"middle\" font-size=\"12\" fill=\"#333\">").append(Fmt.htmlEscape(xLabel)).append("</text>\n")
        sb.append("<text x=\"16\" y=\"${h / 2}\" text-anchor=\"middle\" font-size=\"12\" fill=\"#333\" transform=\"rotate(-90 16 ${h / 2})\">").append(Fmt.htmlEscape(yLabel)).append("</text>\n")
        sb.append("</svg>\n")
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    private fun fmtN(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    /** The `score` matrix as HTML (rows = A, columns = B, cell = best strategy and its score). */
    fun writeMatrixHtml(file: File, title: String, tracks: List<String>, cells: Map<Pair<Int, Int>, Pair<String, Double>>) {
        val sb = StringBuilder()
        sb.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(Fmt.htmlEscape(title)).append("</title>\n<style>\n").append(CSS).append("\n</style></head><body>\n")
        sb.append("<h1>").append(Fmt.htmlEscape(title)).append("</h1>\n")
        sb.append("<p class=\"sub\">Rows are the outgoing track, columns the incoming one; each cell is the best strategy and its score.</p>\n")
        sb.append("<table><thead><tr><th>A \\ B</th>")
        for (t in tracks) sb.append("<th>").append(Fmt.htmlEscape(t)).append("</th>")
        sb.append("</tr></thead><tbody>\n")
        val best = cells.values.maxOfOrNull { it.second } ?: 1.0
        for (i in tracks.indices) {
            sb.append("<tr><th class=\"id\">").append(Fmt.htmlEscape(tracks[i])).append("</th>")
            for (j in tracks.indices) {
                val c = cells[i to j]
                if (c == null) sb.append("<td class=\"self\">—</td>")
                else {
                    val t = if (best > 0) (c.second / best).coerceIn(0.0, 1.0) else 0.0
                    sb.append("<td style=\"background:").append(heat(t)).append("\">")
                        .append(Fmt.htmlEscape(c.first)).append("<br><small>").append(Fmt.num(c.second, 3)).append("</small></td>")
                }
            }
            sb.append("</tr>\n")
        }
        sb.append("</tbody></table>\n</body></html>\n")
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    private fun heat(t: Double): String {
        val r = (255 - 90 * t).toInt().coerceIn(0, 255)
        val g = (255 - 35 * t).toInt().coerceIn(0, 255)
        val b = (255 - 150 * t).toInt().coerceIn(0, 255)
        return "rgb($r,$g,$b)"
    }

    private val CSS = """
        :root { color-scheme: light dark; }
        body { font: 14px/1.5 ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 0; padding: 24px 16px; background: #fbfbfa; color: #1b1b1a; }
        h1 { font-size: 20px; margin: 0 0 4px; }
        .sub { color: #666; margin: 0 0 16px; }
        table { border-collapse: collapse; width: 100%; max-width: 100%; }
        th, td { border: 1px solid #ddd; padding: 6px 8px; text-align: left; vertical-align: middle; white-space: nowrap; }
        thead th { background: #f0f0ee; position: sticky; top: 0; }
        td.n, td.v { text-align: right; font-variant-numeric: tabular-nums; }
        td.id, th.id { font-weight: 600; }
        td.self { background: #f6f6f4; color: #999; text-align: center; }
        .pass { color: #14622f; }
        .warn { background: #fff6d6; color: #6b5000; }
        .fail { background: #ffe1e1; color: #8a1616; font-weight: 600; }
        audio { width: 220px; height: 32px; }
        small { color: #666; }
        @media (prefers-color-scheme: dark) {
          body { background: #16161a; color: #ececed; }
          th, td { border-color: #33333a; }
          thead th { background: #22222a; }
          .sub, small { color: #9a9aa2; }
          td.self { background: #1d1d22; color: #66666e; }
          .pass { color: #6ee39a; }
          .warn { background: #3a3115; color: #f0d488; }
          .fail { background: #451c1c; color: #ff9d9d; }
        }
    """.trimIndent()
}
