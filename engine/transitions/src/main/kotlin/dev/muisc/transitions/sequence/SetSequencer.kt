package dev.muisc.transitions.sequence

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioSourceId
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPlanner
import kotlin.math.abs
import kotlin.random.Random

/**
 * Orders a set of songs so that every neighbouring pair mixes well: "smart shuffle". It is for SHUFFLE and for an
 * explicit "order for mixing" action only; an album played in order or a playlist played as written is never
 * passed through it (the caller decides, the sequencer does not know the playback context).
 *
 * **Objective.** For an order `o`, minimise
 * `Σ_k [cost(o_k → o_k+1) + artist(o_k, o_k+1) + noise(o_k, o_k+1)] + arcWeight · Σ_k |q(o_k) − arc.target(k, n)|`
 * where `cost` is [PairCostModel] (optionally refined by the planner, see [plannerRefineMax]), `artist` is
 * [SequenceOptions.sameArtistPenalty] for two songs by the same artist, `noise` is the seeded variety term
 * (`variety · `[NOISE_SCALE]` · u(seed, A, B)`, u uniform 0..1) and `q` is a song's energy quantile in the set
 * ([PairCostModel.energyProxy]). Reported costs ([SequenceResult.pairCosts]) are `cost` alone.
 *
 * **Songs without an analysis** never block: every pair they are part of costs [PairCostModel.UNKNOWN_PAIR_COST]
 * (plus the artist rule and the noise, which need no analysis), they have no arc term, and they are placed
 * wherever the search puts them. In the online mode they are sampled and chosen like any other candidate.
 *
 * **Full mode** ([full]; [sequence] uses it up to [SequenceOptions.fullLimit] songs): the n×n cost matrix, a greedy
 * nearest-neighbour order from the start song, then local search (2-opt segment reversal with direction-aware
 * costs, and or-opt moves of 1–3 songs) with first improvement until no move improves or the deterministic
 * [SequenceOptions.searchBudget] is spent. With [SequenceOptions.first] set, position 0 never moves.
 *
 * **Online mode** ([online]; above the limit): each next slot is filled from a pool of
 * [SequenceOptions.poolSize] candidates sampled from a seeded random deck; the chosen one is replaced by the next
 * card of the deck. A candidate's score is its objective contribution minus an aging bonus
 * ([AGE_WEIGHT] · min(1, age / poolSize)) so hard-to-place songs do not sit in the pool forever. With an arc, the
 * energy quantile is taken among the songs seen so far, a candidate more than [ONLINE_ARC_BAND] off the target pays
 * [OUT_OF_BAND_PENALTY], and a pool member that has waited `poolSize / 4` steps and is off the next target goes back
 * under the deck (it is not read again). Only the start song and the songs that enter the pool are ever looked at,
 * so the work is `O(n · poolSize)` pair costs, and an analysis is fetched (via the `itemAt` callback, once per
 * song) only for songs that entered the pool. Positions past [SequenceOptions.horizon] keep the pool's and then
 * the deck's random order, and pairs involving a song that was never read are reported at
 * [PairCostModel.UNKNOWN_PAIR_COST].
 *
 * Deterministic: the same items, options and seed give the same order on every machine (no wall-clock budget).
 */
class SetSequencer(
    val costModel: PairCostModel = PairCostModel(),
    /** When set and the set has at most [plannerRefineMax] analysed songs, pair costs blend in the planner's best score. */
    val planner: TransitionPlanner? = null,
    val plannerRefineMax: Int = 0,
) {

    /** [full] up to [SequenceOptions.fullLimit] songs, [online] above. */
    fun sequence(items: List<SequenceItem>, options: SequenceOptions = SequenceOptions()): SequenceResult =
        if (items.size <= options.fullLimit) full(items, options) else online(items.size, options) { items[it] }

    /** The pair cost used for ordering (the planner refinement included when it applies to a set of [setSize]). */
    fun pairCost(a: SequenceItem, b: SequenceItem, options: SequenceOptions, setSize: Int = 2): Double {
        val base = costModel.cost(a.analysis, b.analysis, options.prefs)
        val p = planner ?: return base
        val aa = a.analysis ?: return base
        val ba = b.analysis ?: return base
        if (setSize > plannerRefineMax) return base
        val best = try {
            p.plan(ref(a, aa), ref(b, ba), options.prefs, options.seed).best.score
        } catch (e: RuntimeException) {
            return base
        }
        return PLANNER_BLEND * (1.0 - best.coerceIn(0.0, 1.0)) + (1.0 - PLANNER_BLEND) * base
    }

    // ============================================================================================ full

    /** Optimises the whole order (see the class comment). `O(n²)` pair costs; use [online] for whole libraries. */
    fun full(items: List<SequenceItem>, options: SequenceOptions = SequenceOptions()): SequenceResult {
        val n = items.size
        options.first?.let { require(it in 0 until n) { "first = $it is not an index of the ${n} items" } }
        if (n == 0) return SequenceResult(emptyList(), emptyList(), SequenceResult.Mode.FULL, 0, 0, 0)
        val cost = DoubleArray(n * n)
        val obj = DoubleArray(n * n)
        for (i in 0 until n) for (j in 0 until n) {
            if (i == j) continue
            val c = pairCost(items[i], items[j], options, n)
            cost[i * n + j] = c
            obj[i * n + j] = c + artistPenalty(items[i], items[j], options) + noise(options, i, j)
        }
        val arcActive = options.arc != EnergyArc.FLAT && options.arcWeight > 0.0
        val arc = if (arcActive) arcMatrix(items, options) else null
        val rnd = Random(options.seed)
        val start = options.first ?: rnd.nextInt(n)
        val order = greedy(n, start, obj, arc)
        val work = LocalSearch(n, obj, arc, if (options.first != null) 1 else 0, options.searchBudget).run(order)
        return result(items, order.toList(), SequenceResult.Mode.FULL, work) { a, b -> cost[a * n + b] }
    }

    private fun greedy(n: Int, start: Int, obj: DoubleArray, arc: DoubleArray?): IntArray {
        val used = BooleanArray(n)
        val order = IntArray(n)
        order[0] = start
        used[start] = true
        for (k in 1 until n) {
            val cur = order[k - 1]
            var best = -1
            var bestScore = Double.POSITIVE_INFINITY
            for (j in 0 until n) {
                if (used[j]) continue
                val s = obj[cur * n + j] + (arc?.get(j * n + k) ?: 0.0)
                if (s < bestScore) { bestScore = s; best = j }
            }
            order[k] = best
            used[best] = true
        }
        return order
    }

    /** `arc[t·n + k]` = arcWeight · |q(t) − target(k)|, 0 for songs without an analysis. */
    private fun arcMatrix(items: List<SequenceItem>, options: SequenceOptions): DoubleArray {
        val n = items.size
        val energies = items.map { it.analysis?.let(PairCostModel::energyProxy) }
        val known = energies.filterNotNull().sorted()
        val out = DoubleArray(n * n)
        for (t in 0 until n) {
            val e = energies[t] ?: continue
            val q = quantile(known, e)
            for (k in 0 until n) out[t * n + k] = options.arcWeight * abs(q - options.arc.target(k, n))
        }
        return out
    }

    /**
     * First-improvement local search over an open path with asymmetric pair costs [obj] and an optional positional
     * term [arc]. Positions below [lo] never change. Returns the work spent.
     */
    private class LocalSearch(val n: Int, val obj: DoubleArray, val arc: DoubleArray?, val lo: Int, val budget: Long) {
        var work = 0L
        private val fwd = DoubleArray(n)
        private val rev = DoubleArray(n)

        private fun c(a: Int, b: Int) = obj[a * n + b]
        private fun a(t: Int, k: Int) = arc!![t * n + k]

        private fun prefix(o: IntArray) {
            fwd[0] = 0.0; rev[0] = 0.0
            for (k in 1 until n) {
                fwd[k] = fwd[k - 1] + c(o[k - 1], o[k])
                rev[k] = rev[k - 1] + c(o[k], o[k - 1])
            }
        }

        fun run(o: IntArray): Long {
            if (n < 3) return 0
            prefix(o)
            var improved = true
            while (improved && work < budget) {
                improved = false
                if (twoOpt(o)) improved = true
                if (orOpt(o)) improved = true
            }
            return work
        }

        /** Reverse o[i..j] when it lowers the objective. */
        private fun twoOpt(o: IntArray): Boolean {
            var any = false
            for (i in lo until n - 1) {
                for (j in i + 1 until n) {
                    if (work >= budget) return any
                    work++
                    var before = fwd[j] - fwd[i]
                    var after = rev[j] - rev[i]
                    if (i > 0) { before += c(o[i - 1], o[i]); after += c(o[i - 1], o[j]) }
                    if (j < n - 1) { before += c(o[j], o[j + 1]); after += c(o[i], o[j + 1]) }
                    var delta = after - before
                    if (arc != null) {
                        work += (j - i + 1)
                        for (k in i..j) delta += a(o[i + j - k], k) - a(o[k], k)
                    }
                    if (delta < -EPS) {
                        var x = i; var y = j
                        while (x < y) { val t = o[x]; o[x] = o[y]; o[y] = t; x++; y-- }
                        prefix(o)
                        work += n
                        any = true
                    }
                }
            }
            return any
        }

        /** Move a segment of 1..3 songs elsewhere (no reversal) when it lowers the objective. */
        private fun orOpt(o: IntArray): Boolean {
            var any = false
            for (len in 1..3) {
                var i = lo
                while (i + len <= n) {
                    val s0 = o[i]; val s1 = o[i + len - 1]
                    val prev = if (i > 0) o[i - 1] else -1
                    val next = if (i + len < n) o[i + len] else -1
                    var removal = 0.0
                    if (prev >= 0) removal -= c(prev, s0)
                    if (next >= 0) removal -= c(s1, next)
                    if (prev >= 0 && next >= 0) removal += c(prev, next)
                    var moved = false
                    // Insert between o[p] and o[p + 1]; p = -1 is the very front, p = n - 1 the very end.
                    for (p in (lo - 1) until n) {
                        if (p in (i - 1)..(i + len - 1)) continue
                        if (work >= budget) return any
                        work++
                        val a = if (p >= 0) o[p] else -1
                        val b = if (p + 1 < n) o[p + 1] else -1
                        var delta = removal
                        if (a >= 0 && b >= 0) delta -= c(a, b)
                        if (a >= 0) delta += c(a, s0)
                        if (b >= 0) delta += c(s1, b)
                        if (arc != null) delta += arcDelta(o, i, len, p)
                        if (delta < -EPS) {
                            applyMove(o, i, len, p)
                            prefix(o)
                            work += n
                            any = true
                            moved = true
                            break
                        }
                    }
                    if (!moved) i++
                }
            }
            return any
        }

        /** Positional change of moving o[i until i+len] to after position p (in the original indexing). */
        private fun arcDelta(o: IntArray, i: Int, len: Int, p: Int): Double {
            var d = 0.0
            if (p < i - 1) {
                // Segment moves left to positions p+1 .. p+len; o[p+1 .. i-1] shift right by len.
                work += (i - p - 1 + len)
                for (m in 0 until len) d += a(o[i + m], p + 1 + m) - a(o[i + m], i + m)
                for (k in p + 1 until i) d += a(o[k], k + len) - a(o[k], k)
            } else {
                // Segment moves right to positions p-len+1 .. p; o[i+len .. p] shift left by len.
                work += (p - i - len + 1 + len)
                for (m in 0 until len) d += a(o[i + m], p - len + 1 + m) - a(o[i + m], i + m)
                for (k in i + len..p) d += a(o[k], k - len) - a(o[k], k)
            }
            return d
        }

        private fun applyMove(o: IntArray, i: Int, len: Int, p: Int) {
            val seg = IntArray(len) { o[i + it] }
            if (p < i - 1) {
                for (k in i - 1 downTo p + 1) o[k + len] = o[k]
                for (m in 0 until len) o[p + 1 + m] = seg[m]
            } else {
                for (k in i + len..p) o[k - len] = o[k]
                for (m in 0 until len) o[p - len + 1 + m] = seg[m]
            }
        }
    }

    // ============================================================================================ online

    /**
     * Windowed ordering for sets too large for [full] (whole libraries). [itemAt] is called at most once per index,
     * and only for the start song and the songs that enter the candidate pool, so a caller can fetch analyses
     * lazily. See the class comment.
     */
    fun online(size: Int, options: SequenceOptions = SequenceOptions(), itemAt: (Int) -> SequenceItem): SequenceResult {
        options.first?.let { require(it in 0 until size) { "first = $it is not an index of the $size items" } }
        if (size == 0) return SequenceResult(emptyList(), emptyList(), SequenceResult.Mode.ONLINE, 0, 0, 0)
        val loaded = arrayOfNulls<SequenceItem>(size)
        fun item(i: Int): SequenceItem = loaded[i] ?: itemAt(i).also { loaded[i] = it }
        val rnd = Random(options.seed)
        val start = options.first ?: rnd.nextInt(size)
        // The deck: every other index in a seeded random order (Fisher–Yates).
        val shuffled = IntArray(size - 1)
        run { var w = 0; for (i in 0 until size) if (i != start) shuffled[w++] = i }
        for (i in shuffled.size - 1 downTo 1) { val j = rnd.nextInt(i + 1); val t = shuffled[i]; shuffled[i] = shuffled[j]; shuffled[j] = t }
        val deck = ArrayDeque<Int>(shuffled.size)
        for (i in shuffled) deck.addLast(i)

        val arcActive = options.arc != EnergyArc.FLAT && options.arcWeight > 0.0
        val seenEnergies = SortedDoubles()
        val energy = DoubleArray(size) { Double.NaN }
        fun admit(i: Int) {
            val a = item(i).analysis ?: return
            val e = PairCostModel.energyProxy(a)
            energy[i] = e
            if (arcActive) seenEnergies.add(e)
        }

        val order = ArrayList<Int>(size)
        order += start
        admit(start)
        val pool = ArrayList<Int>(options.poolSize)
        val enteredAt = IntArray(size)
        fun refill(step: Int) {
            while (pool.size < options.poolSize && deck.isNotEmpty()) {
                val i = deck.removeFirst()
                if (loaded[i] == null) admit(i)
                enteredAt[i] = step
                pool += i
            }
        }
        refill(0)
        var work = 0L
        val sequenced = minOf(size - 1, options.horizon)
        for (step in 1..sequenced) {
            val cur = order.last()
            val target = if (arcActive) options.arc.target(step, size) else Double.NaN
            var bestAt = -1
            var bestScore = Double.POSITIVE_INFINITY
            for ((at, cand) in pool.withIndex()) {
                work++
                var s = pairCost(item(cur), item(cand), options, size) + artistPenalty(item(cur), item(cand), options) + noise(options, cur, cand)
                if (arcActive && !energy[cand].isNaN()) {
                    val off = abs(seenEnergies.quantile(energy[cand]) - target)
                    s += options.arcWeight * off
                    if (off > ONLINE_ARC_BAND) s += OUT_OF_BAND_PENALTY
                }
                s -= AGE_WEIGHT * minOf(1.0, (step - enteredAt[cand]).toDouble() / options.poolSize)
                if (s < bestScore) { bestScore = s; bestAt = at }
            }
            order += pool.removeAt(bestAt)
            if (arcActive && deck.isNotEmpty()) {
                // Songs that have waited a quarter of a pool's worth of steps and are off the arc go back under the
                // deck, so the pool keeps sampling what is left instead of filling up with songs for a later part of
                // the arc.
                val next = options.arc.target(step + 1, size)
                val evictAge = (options.poolSize / 4).coerceAtLeast(1)
                val it = pool.listIterator()
                while (it.hasNext()) {
                    val c = it.next()
                    if (step - enteredAt[c] < evictAge || energy[c].isNaN()) continue
                    if (abs(seenEnergies.quantile(energy[c]) - next) > ONLINE_ARC_BAND) { it.remove(); deck.addLast(c) }
                }
            }
            refill(step)
        }
        // Past the horizon: the pool, then the rest of the deck, in their sampled order.
        order.addAll(pool)
        order.addAll(deck)
        // Pairs past the horizon may include songs that were never loaded: no information, so the unknown cost.
        val costs = (0 until order.size - 1).map { k ->
            val a = loaded[order[k]]; val b = loaded[order[k + 1]]
            if (a == null || b == null) PairCostModel.UNKNOWN_PAIR_COST else pairCost(a, b, options, size)
        }
        val sameArtist = (0 until order.size - 1).count { k ->
            val a = loaded[order[k]]; val b = loaded[order[k + 1]]
            a != null && b != null && sameArtist(a, b)
        }
        val unanalysed = loaded.count { it != null && it.analysis == null }
        return SequenceResult(order, costs, SequenceResult.Mode.ONLINE, sameArtist, unanalysed, work)
    }

    // ============================================================================================ shared

    private fun result(items: List<SequenceItem>, order: List<Int>, mode: SequenceResult.Mode, work: Long, cost: (Int, Int) -> Double): SequenceResult {
        val costs = (0 until order.size - 1).map { cost(order[it], order[it + 1]) }
        val sameArtist = (0 until order.size - 1).count { sameArtist(items[order[it]], items[order[it + 1]]) }
        return SequenceResult(order, costs, mode, sameArtist, items.count { it.analysis == null }, work)
    }

    private fun artistPenalty(a: SequenceItem, b: SequenceItem, options: SequenceOptions): Double =
        if (sameArtist(a, b)) options.sameArtistPenalty else 0.0

    private fun ref(item: SequenceItem, analysis: TrackAnalysis) = TrackRef(item.id, AudioSourceId(analysis.sourceId), analysis, artist = item.artist)

    /** Running sorted multiset of energies for the online mode's quantiles. */
    private class SortedDoubles {
        private var values = DoubleArray(64)
        private var size = 0
        fun add(v: Double) {
            if (size == values.size) values = values.copyOf(size * 2)
            val at = lowerBound(v)
            System.arraycopy(values, at, values, at + 1, size - at)
            values[at] = v
            size++
        }
        fun quantile(v: Double): Double {
            if (size <= 1) return 0.5
            val lo = lowerBound(v)
            var hi = lo
            while (hi < size && values[hi] == v) hi++
            val rank = if (hi > lo) (lo + hi - 1) / 2.0 else lo - 0.5
            return (rank / (size - 1)).coerceIn(0.0, 1.0)
        }
        private fun lowerBound(v: Double): Int {
            var lo = 0; var hi = size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (values[mid] < v) lo = mid + 1 else hi = mid }
            return lo
        }
    }

    companion object {
        /** Scale of the variety noise: at variety 1 a pair's cost moves by up to this much. */
        const val NOISE_SCALE = 0.6

        /** Online mode: bonus for a candidate that has waited a whole pool's worth of steps. */
        const val AGE_WEIGHT = 0.15

        /**
         * Online mode with an arc: a candidate whose energy quantile is more than this far from the target pays
         * [OUT_OF_BAND_PENALTY]. The pool is a random sample of what is left, so about `2 · band` of it is in the band
         * at any time; choosing in-band songs uses up each energy range at the pace the arc moves through it, which is
         * what lets a window follow a rising or peaking arc over a whole library.
         */
        const val ONLINE_ARC_BAND = 0.2
        const val OUT_OF_BAND_PENALTY = 1.0

        /** Weight of the planner's `1 − best score` in a refined pair cost. */
        const val PLANNER_BLEND = 0.5

        private const val EPS = 1e-12

        fun sameArtist(a: SequenceItem, b: SequenceItem): Boolean =
            a.artist.isNotBlank() && a.artist.trim().equals(b.artist.trim(), ignoreCase = true)

        /** Seeded variety noise for the ordered pair (i, j): `variety · NOISE_SCALE · u`, u uniform in [0, 1). */
        fun noise(options: SequenceOptions, i: Int, j: Int): Double {
            if (options.variety <= 0.0) return 0.0
            var z = options.seed xor (i.toLong() * -0x61c8864680b583ebL) xor (j.toLong() * 0x5851f42d4c957f2dL)
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            val u = (z ushr 11).toDouble() / (1L shl 53).toDouble()
            return options.variety * NOISE_SCALE * u
        }

        /** Mid-rank quantile of [v] in the sorted list [sorted] (0.5 when fewer than two values). */
        fun quantile(sorted: List<Double>, v: Double): Double {
            if (sorted.size <= 1) return 0.5
            var lo = 0; var hi = sorted.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (sorted[mid] < v) lo = mid + 1 else hi = mid }
            var end = lo
            while (end < sorted.size && sorted[end] == v) end++
            val rank = if (end > lo) (lo + end - 1) / 2.0 else lo - 0.5
            return (rank / (sorted.size - 1)).coerceIn(0.0, 1.0)
        }
    }
}
