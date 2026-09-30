package dev.muisc.cli

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.metrics.MetricsReport
import dev.muisc.metrics.Verdict
import java.io.File
import kotlin.random.Random

/**
 * The data side of `muisc eval`: which pairs to try, what one tried pair produced, and the aggregates the report
 * prints. Kept apart from the command so it can be tested without rendering anything.
 */
object LibraryEval {

    /** Why a pair was chosen. */
    enum class Source(val label: String) {
        SHUFFLE("consecutive in a shuffled order"),
        STRETCH("hardest by tempo stretch"),
        KEY("hardest by key distance"),
    }

    /** An ordered pair of track indices, why it was picked, and (for shuffle pairs) the pair before it in the order. */
    data class Pick(val a: Int, val b: Int, val source: Source, val previousInChain: Int? = null)

    /** Hardness of an ordered pair, as the pair analyzer sees it. */
    data class Hardness(val stretchPercent: Double, val camelotDistance: Int)

    /** At most this many ordered pairs are scored for hardness (a random subset when the library has more). */
    const val HARDNESS_POOL = 20_000

    /**
     * Samples up to [count] distinct ordered pairs of `0 until trackCount`, deterministically from [rnd]:
     * half are consecutive pairs of shuffled orders (a new shuffle starts when one order is used up), a quarter
     * the hardest by tempo stretch and a quarter the hardest by key distance (Camelot distance, then stretch), each
     * scored with [hardness] over all ordered pairs, or [HARDNESS_POOL] random ones in a large library. When a quota
     * cannot be met without repeating a pair, the remainder is filled from the other lists. Never more than
     * `trackCount × (trackCount − 1)` pairs.
     */
    fun samplePairs(trackCount: Int, count: Int, rnd: Random, hardness: (Int, Int) -> Hardness): List<Pick> {
        if (trackCount < 2 || count <= 0) return emptyList()
        val total = trackCount.toLong() * (trackCount - 1)
        val wanted = minOf(count.toLong(), total).toInt()
        val shuffleQuota = (wanted + 1) / 2
        val stretchQuota = (wanted - shuffleQuota + 1) / 2
        val keyQuota = wanted - shuffleQuota - stretchQuota

        val taken = LinkedHashSet<Pair<Int, Int>>()
        val out = ArrayList<Pick>()
        fun take(p: Pick): Boolean {
            if (out.size >= wanted || !taken.add(p.a to p.b)) return false
            out += p
            return true
        }

        // Shuffle chains: consecutive pairs of one shuffled order; the chain restarts with a fresh shuffle.
        val shuffleCandidates = ArrayList<Pick>()
        var rounds = 0
        val seen = HashSet<Pair<Int, Int>>()
        while (shuffleCandidates.size < wanted && rounds < 50) {
            val order = (0 until trackCount).shuffled(rnd)
            var prev: Int? = null
            for (i in 0 until order.size - 1) {
                val pair = order[i] to order[i + 1]
                if (seen.add(pair)) {
                    shuffleCandidates += Pick(pair.first, pair.second, Source.SHUFFLE, prev)
                    prev = shuffleCandidates.size - 1
                } else {
                    prev = null
                }
            }
            rounds++
        }

        // Hardness pool.
        val pool: List<Pair<Int, Int>> = if (total <= HARDNESS_POOL) {
            (0 until trackCount).flatMap { a -> (0 until trackCount).filter { it != a }.map { b -> a to b } }
        } else {
            val set = LinkedHashSet<Pair<Int, Int>>()
            while (set.size < HARDNESS_POOL) {
                val a = rnd.nextInt(trackCount)
                val b = rnd.nextInt(trackCount)
                if (a != b) set += a to b
            }
            set.toList()
        }
        val scored = pool.map { it to hardness(it.first, it.second) }
        val byStretch = scored.sortedWith(compareByDescending<Pair<Pair<Int, Int>, Hardness>> { it.second.stretchPercent }.thenBy { it.first.first }.thenBy { it.first.second })
            .map { Pick(it.first.first, it.first.second, Source.STRETCH) }
        val byKey = scored.sortedWith(compareByDescending<Pair<Pair<Int, Int>, Hardness>> { it.second.camelotDistance }.thenByDescending { it.second.stretchPercent }.thenBy { it.first.first }.thenBy { it.first.second })
            .map { Pick(it.first.first, it.first.second, Source.KEY) }

        // Shuffle picks keep their chain link only when the pair before them in the chain was also taken.
        val chainIndex = HashMap<Int, Int>() // candidate index -> output index
        fun takeShuffle(limit: Int) {
            for ((ci, c) in shuffleCandidates.withIndex()) {
                if (out.size >= limit) return
                if (chainIndex.containsKey(ci)) continue
                val prevOut = c.previousInChain?.let { chainIndex[it] }
                if (take(c.copy(previousInChain = prevOut))) chainIndex[ci] = out.size - 1
            }
        }
        fun takeFrom(list: List<Pick>, quota: Int) {
            var n = 0
            for (p in list) { if (n >= quota) return; if (take(p)) n++ }
        }
        takeShuffle(shuffleQuota)
        takeFrom(byStretch, stretchQuota)
        takeFrom(byKey, keyQuota)
        takeShuffle(wanted)
        takeFrom(byStretch, wanted - out.size)
        takeFrom(byKey, wanted - out.size)
        return out
    }

    /** One tried pair: what the planner picked, how it rendered and how to reproduce it. */
    class Row(
        val index: Int,
        val source: Source,
        val a: File,
        val b: File,
        val stretchPercent: Double,
        val camelotDistance: Int,
        val previous: String?,
        val strategyId: String?,
        val modifiers: List<String>,
        val score: Double,
        val metrics: MetricsReport?,
        /** Wall time of render + metric evaluation. */
        val renderMillis: Long,
        val seconds: Double,
        val error: String?,
        val reproduce: String,
    ) {
        /** Worst metric verdict, or FAIL when the render threw (an error is counted as a failure). */
        val verdict: Verdict get() = metrics?.worst ?: Verdict.FAIL
        val failCount: Int get() = metrics?.failures?.size ?: Int.MAX_VALUE
        val warnCount: Int get() = metrics?.warnings?.size ?: 0
        val problems: String get() = error?.let { "render error: $it" }
            ?: metrics!!.metrics.filter { it.verdict != Verdict.PASS }.joinToString(", ") { "${it.id} ${Fmt.num(it.value, 3)} ${it.verdict}" }
    }

    /** Per-strategy aggregate. */
    class StrategyStats(val id: String, val chosen: Int, val fails: Int, val warns: Int, val medianRenderMillis: Double)

    /** Per-metric aggregate over the rendered pairs. */
    class MetricStats(val id: String, val pass: Int, val warn: Int, val fail: Int) {
        val total: Int get() = pass + warn + fail
    }

    /** Distribution of one confidence value over the analysed tracks. */
    class Distribution(val label: String, val values: DoubleArray, val gate: Double, val gateNote: String) {
        val min: Double get() = quantile(0.0)
        val p10: Double get() = quantile(0.1)
        val median: Double get() = quantile(0.5)
        val p90: Double get() = quantile(0.9)
        val max: Double get() = quantile(1.0)
        val below: Int get() = values.count { it < gate }

        /** Counts in five buckets `[0, .2), [.2, .4), [.4, .6), [.6, .8), [.8, 1]`. */
        fun histogram(): IntArray {
            val h = IntArray(5)
            for (v in values) h[(v * 5).toInt().coerceIn(0, 4)]++
            return h
        }

        fun quantile(q: Double): Double {
            if (values.isEmpty()) return Double.NaN
            val s = values.sorted()
            val pos = q * (s.size - 1)
            val lo = pos.toInt()
            val hi = minOf(lo + 1, s.size - 1)
            return s[lo] + (s[hi] - s[lo]) * (pos - lo)
        }
    }

    /** A track whose analysis is below a gate the planner uses, with the gates it misses. */
    class LowConfidence(val file: File, val reasons: List<String>)

    const val GRID_GATE = 0.5
    const val KEY_GATE = 0.6
    const val TEMPO_GATE = 0.5

    fun distributions(analyses: List<TrackAnalysis>): List<Distribution> = listOf(
        Distribution("tempo confidence", analyses.map { it.tempo.confidence.toDouble() }.toDoubleArray(), TEMPO_GATE, "reported only; no strategy gates on it"),
        Distribution("grid confidence", analyses.map { it.grid.confidence.toDouble() }.toDoubleArray(), GRID_GATE, "beat-matched strategies need ≥ $GRID_GATE on both tracks"),
        Distribution("key strength", analyses.map { it.key.strength.toDouble() }.toDoubleArray(), KEY_GATE, "harmonicBlend needs ≥ $KEY_GATE on both tracks"),
    )

    fun lowConfidence(tracks: List<Pair<File, TrackAnalysis>>): List<LowConfidence> = tracks.mapNotNull { (f, a) ->
        val r = ArrayList<String>()
        if (a.grid.confidence < GRID_GATE) r += "grid ${Fmt.num(a.grid.confidence.toDouble(), 2)} < $GRID_GATE"
        if (a.key.strength < KEY_GATE) r += "key ${Fmt.num(a.key.strength.toDouble(), 2)} < $KEY_GATE"
        if (a.tempo.confidence < TEMPO_GATE) r += "tempo ${Fmt.num(a.tempo.confidence.toDouble(), 2)} < $TEMPO_GATE"
        if (r.isEmpty()) null else LowConfidence(f, r)
    }.sortedBy { it.file.path }

    fun strategyStats(rows: List<Row>): List<StrategyStats> =
        rows.filter { it.strategyId != null }.groupBy { it.strategyId!! }.map { (id, rs) ->
            StrategyStats(id, rs.size, rs.count { it.verdict == Verdict.FAIL }, rs.count { it.verdict == Verdict.WARN }, median(rs.map { it.renderMillis.toDouble() }))
        }.sortedWith(compareByDescending<StrategyStats> { it.chosen }.thenBy { it.id })

    fun metricStats(rows: List<Row>): List<MetricStats> {
        val ids = LinkedHashSet<String>()
        for (r in rows) r.metrics?.metrics?.forEach { ids += it.id }
        return ids.map { id ->
            val vs = rows.mapNotNull { it.metrics?.verdict(id) }
            MetricStats(id, vs.count { it == Verdict.PASS }, vs.count { it == Verdict.WARN }, vs.count { it == Verdict.FAIL })
        }
    }

    /** Worst first: render errors, then FAIL before WARN before PASS, then more failing metrics, more warnings. */
    fun worst(rows: List<Row>, n: Int): List<Row> = rows
        .sortedWith(
            compareByDescending<Row> { it.error != null }
                .thenByDescending { it.verdict.ordinal }
                .thenByDescending { if (it.error != null) 0 else it.failCount }
                .thenByDescending { it.warnCount }
                .thenBy { it.index },
        )
        .filter { it.verdict != Verdict.PASS }
        .take(n)

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** FAIL rate over all tried pairs (render errors count as failures). */
    fun failRate(rows: List<Row>): Double = if (rows.isEmpty()) 0.0 else rows.count { it.verdict == Verdict.FAIL }.toDouble() / rows.size

    fun warnRate(rows: List<Row>): Double = if (rows.isEmpty()) 0.0 else rows.count { it.verdict == Verdict.WARN }.toDouble() / rows.size

    /** `0.1`, `10%` or `10 %` → 0.1; null for anything else or outside 0..1. */
    fun parseRate(text: String): Double? {
        val t = text.trim()
        val v = if (t.endsWith("%")) t.dropLast(1).trim().toDoubleOrNull()?.div(100.0) else t.toDoubleOrNull()
        return v?.takeIf { it.isFinite() && it in 0.0..1.0 }
    }

    /** POSIX-shell single quoting, so a reproduce command survives spaces and quotes in paths. */
    fun shellQuote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "/._-+=:,@%" }) s else "'" + s.replace("'", "'\\''") + "'"
}
