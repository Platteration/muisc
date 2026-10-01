package dev.muisc.transitions.sequence

import dev.muisc.audio.AudioSourceId
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.planner.DefaultTransitionPlanner
import dev.muisc.transitions.planner.StructTables
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SetSequencerTest {

    private val prefs = TransitionPrefs()
    private val model = PairCostModel()
    private val planner = DefaultTransitionPlanner(DefaultStrategyRegistry.default())

    private fun meanCost(items: List<SequenceItem>, order: List<Int>): Double =
        (0 until order.size - 1).map { model.cost(items[order[it]].analysis, items[order[it + 1]].analysis, prefs) }.average()

    /**
     * What the planner itself says about each consecutive pair of [order]: how many pairs have no beat-domain
     * candidate at all, and how many get a beat-domain strategy as the planner's first choice.
     */
    private class PlannerStats(val pairs: Int, val bestIsBeatMatched: Int, val noBeatMatchedMove: Int) {
        val noBeatRate: Double get() = noBeatMatchedMove.toDouble() / pairs
        override fun toString() = "best is beat-matched $bestIsBeatMatched, no beat-matched move $noBeatMatchedMove of $pairs"
    }

    private fun plannerStats(items: List<SequenceItem>, order: List<Int>): PlannerStats {
        fun ref(i: Int) = TrackRef(items[i].id, AudioSourceId(items[i].id), items[i].analysis!!)
        var best = 0; var noBeat = 0
        for (k in 0 until order.size - 1) {
            val ranked = planner.plan(ref(order[k]), ref(order[k + 1]), prefs)
            if (ranked.best.strategy.id in StructTables.BEAT_DOMAIN_IDS) best++
            if (ranked.candidates.none { it.strategy.id in StructTables.BEAT_DOMAIN_IDS }) noBeat++
        }
        return PlannerStats(order.size - 1, best, noBeat)
    }

    private fun assertPermutation(n: Int, order: List<Int>) {
        assertEquals(n, order.size, "every song placed once")
        assertEquals((0 until n).toSet(), order.toSet(), "every song placed once")
    }

    // ------------------------------------------------------------------------------------------ beats random

    /**
     * Against random orders of the same 150 songs: the model's mean pair cost, and two things the planner says about
     * the pairs (pairs with no beat-domain candidate at all; pairs whose best plan is beat-domain).
     *
     * Crossfade-only pairs are not checked: on this library the planner always offers moves besides crossfade
     * (echoOut, filterSweep, phraseCut, ...) and never ranks crossfade first, in random orders as much as in sequenced
     * ones, so "crossfade-only pairs must not increase" read 0 <= 0 for any order, the input order included.
     */
    @Test
    fun `the sequenced order beats random orders on a synthetic library`() {
        val items = SyntheticLibrary.items(150, seed = 7)
        val randoms = (0 until 20).map { s -> items.indices.shuffled(Random(1000 + s)) }
        val randomMeans = randoms.map { meanCost(items, it) }
        val randomStats = randoms.take(5).map { plannerStats(items, it) }
        val randomNoBeat = randomStats.map { it.noBeatRate }.average()

        for ((label, result) in listOf(
            "full" to SetSequencer().full(items, SequenceOptions(seed = 1)),
            "online" to SetSequencer().online(items.size, SequenceOptions(seed = 1)) { items[it] },
        )) {
            assertPermutation(items.size, result.order)
            val stats = plannerStats(items, result.order)
            println(
                "$label: mean cost ${"%.4f".format(result.meanCost)} vs random ${"%.4f".format(randomMeans.average())} " +
                    "(best random ${"%.4f".format(randomMeans.min())}); planner: $stats; random (5 orders): " +
                    "no beat-matched move ${"%.3f".format(randomNoBeat)}, best is beat-matched ${randomStats.map { it.bestIsBeatMatched }}",
            )
            assertEquals(meanCost(items, result.order), result.meanCost, 1e-9, "$label: reported costs are the model's")
            assertTrue(result.meanCost < randomMeans.min(), "$label: mean pair cost ${result.meanCost} must beat every random order (best ${randomMeans.min()})")
            assertTrue(stats.noBeatRate < randomNoBeat, "$label: pairs without a beat-matched move ${stats.noBeatRate} must be rarer than in random orders ($randomNoBeat)")
            // Twice the most of any random order: the input order itself (another arbitrary order) has 13 such pairs
            // against 4-10 in the five random orders, so "more than every random order" would pass a sequencer that
            // returned its input unchanged.
            assertTrue(
                stats.bestIsBeatMatched >= 2 * randomStats.maxOf { it.bestIsBeatMatched },
                "$label: pairs whose best plan is beat-matched (${stats.bestIsBeatMatched}) must be at least twice as many as in any random order (${randomStats.map { it.bestIsBeatMatched }})",
            )
        }
    }

    // ------------------------------------------------------------------------------------------ determinism, variety

    @Test
    fun `same seed gives the same order, another seed another order`() {
        val items = SyntheticLibrary.items(60, seed = 3)
        val seq = SetSequencer()
        for (variety in listOf(0.0, 0.3)) {
            val a = seq.full(items, SequenceOptions(seed = 11, variety = variety))
            val b = seq.full(items, SequenceOptions(seed = 11, variety = variety))
            assertEquals(a.order, b.order, "full, variety $variety")
            val c = seq.online(items.size, SequenceOptions(seed = 11, variety = variety)) { items[it] }
            val d = seq.online(items.size, SequenceOptions(seed = 11, variety = variety)) { items[it] }
            assertEquals(c.order, d.order, "online, variety $variety")
        }
        val others = (12L..16L).map { seq.full(items, SequenceOptions(seed = it)).order }
        val first = seq.full(items, SequenceOptions(seed = 11)).order
        assertTrue(others.all { it != first }, "each shuffle differs")
        val onlineOthers = (12L..16L).map { seq.online(items.size, SequenceOptions(seed = it)) { i -> items[i] }.order }
        assertTrue(onlineOthers.distinct().size == onlineOthers.size, "each online shuffle differs")
    }

    @Test
    fun `variety trades pair cost for difference but stays well below random`() {
        val items = SyntheticLibrary.items(100, seed = 5)
        val seq = SetSequencer()
        val random = (0 until 20).map { meanCost(items, items.indices.shuffled(Random(it))) }.average()
        fun avgCost(v: Double) = (1L..4L).map { seq.full(items, SequenceOptions(seed = it, variety = v)).meanCost }.average()
        val calm = avgCost(0.0)
        val lively = avgCost(1.0)
        println("variety 0: ${"%.4f".format(calm)}, variety 1: ${"%.4f".format(lively)}, random ${"%.4f".format(random)}")
        assertTrue(calm < lively, "variety 1 costs more than variety 0 ($calm vs $lively)")
        assertTrue(lively < random, "even variety 1 beats random ($lively vs $random)")
        // Two orders with the same start at variety 0 vs 1 differ in more places at variety 1.
        fun overlap(a: List<Int>, b: List<Int>): Int {
            val pairs = (0 until a.size - 1).map { a[it] to a[it + 1] }.toSet()
            return (0 until b.size - 1).count { (b[it] to b[it + 1]) in pairs }
        }
        val zeroA = seq.full(items, SequenceOptions(seed = 1, variety = 0.0, first = 0)).order
        val zeroB = seq.full(items, SequenceOptions(seed = 2, variety = 0.0, first = 0)).order
        val oneA = seq.full(items, SequenceOptions(seed = 1, variety = 1.0, first = 0)).order
        val oneB = seq.full(items, SequenceOptions(seed = 2, variety = 1.0, first = 0)).order
        assertTrue(overlap(oneA, oneB) < overlap(zeroA, zeroB), "shared neighbour pairs: variety 1 ${overlap(oneA, oneB)} < variety 0 ${overlap(zeroA, zeroB)}")
    }

    // ------------------------------------------------------------------------------------------ artist rule

    @Test
    fun `the same artist is not played back to back when the order allows it`() {
        // Identical analyses: only the artist rule can tell orders apart. Three artists, ten songs each, grouped by
        // artist in the input. Compared: the same sequencer with the rule and with sameArtistPenalty = 0 (not a random
        // order); without the rule both modes keep some of the input's neighbours.
        val one = SyntheticLibrary.analysis(Random(1), "same")
        val items = List(30) { SequenceItem("s$it", one, artist = "artist ${it % 3}".let { a -> if (it % 2 == 0) a.uppercase() else a }) }
        val grouped = items.sortedBy { it.artist.lowercase() }
        val seq = SetSequencer()
        for (mode in listOf("full", "online")) {
            val with = if (mode == "full") seq.full(grouped, SequenceOptions(seed = 4, variety = 0.0)) else seq.online(grouped.size, SequenceOptions(seed = 4, variety = 0.0)) { grouped[it] }
            val without = if (mode == "full") seq.full(grouped, SequenceOptions(seed = 4, variety = 0.0, sameArtistPenalty = 0.0))
            else seq.online(grouped.size, SequenceOptions(seed = 4, variety = 0.0, sameArtistPenalty = 0.0)) { grouped[it] }
            val counted = (0 until with.order.size - 1).count { SetSequencer.sameArtist(grouped[with.order[it]], grouped[with.order[it + 1]]) }
            assertEquals(counted, with.sameArtistPairs, "$mode: reported count")
            println("$mode: same-artist pairs ${with.sameArtistPairs} with the rule, ${without.sameArtistPairs} with sameArtistPenalty = 0")
            assertEquals(0, with.sameArtistPairs, "$mode: artist names match case-insensitively and are kept apart")
            assertTrue(without.sameArtistPairs > 0, "$mode: without the rule the grouped input keeps neighbours together")
        }
        // Soft rule: a single artist cannot be spread out, and that never blocks the order.
        val solo = List(5) { SequenceItem("x$it", one, artist = "Solo") }
        val r = seq.full(solo, SequenceOptions(seed = 1))
        assertPermutation(5, r.order)
        assertEquals(4, r.sameArtistPairs)
        assertEquals(0, SetSequencer().full(List(4) { SequenceItem("y$it", one, artist = " ") }, SequenceOptions()).sameArtistPairs, "blank artists never match")
    }

    // ------------------------------------------------------------------------------------------ arcs

    private fun spearman(x: List<Double>, y: List<Double>): Double {
        fun ranks(v: List<Double>): DoubleArray {
            val idx = v.indices.sortedBy { v[it] }
            val r = DoubleArray(v.size)
            idx.forEachIndexed { rank, i -> r[i] = rank.toDouble() }
            return r
        }
        val rx = ranks(x); val ry = ranks(y)
        val mx = rx.average(); val my = ry.average()
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in rx.indices) { num += (rx[i] - mx) * (ry[i] - my); dx += (rx[i] - mx) * (rx[i] - mx); dy += (ry[i] - my) * (ry[i] - my) }
        return num / kotlin.math.sqrt(dx * dy)
    }

    @Test
    fun `energy arcs shape the order`() {
        val items = SyntheticLibrary.items(80, seed = 9)
        val seq = SetSequencer()
        fun energies(order: List<Int>) = order.map { PairCostModel.energyProxy(items[it].analysis!!) }
        val positions = (0 until items.size).map { it.toDouble() }

        for (mode in listOf("full", "online")) {
            fun run(arc: EnergyArc) = if (mode == "full") seq.full(items, SequenceOptions(seed = 2, arc = arc))
            else seq.online(items.size, SequenceOptions(seed = 2, arc = arc)) { items[it] }
            val flat = spearman(positions, energies(run(EnergyArc.FLAT).order))
            val rising = spearman(positions, energies(run(EnergyArc.RISING).order))
            val waveOrder = run(EnergyArc.WAVE).order
            val wave = spearman((0 until items.size).map { EnergyArc.WAVE.target(it, items.size) }, energies(waveOrder))
            val flatWave = spearman((0 until items.size).map { EnergyArc.WAVE.target(it, items.size) }, energies(run(EnergyArc.FLAT).order))
            val peakOrder = run(EnergyArc.PEAK).order
            val peak = spearman((0 until items.size).map { EnergyArc.PEAK.target(it, items.size) }, energies(peakOrder))
            val flatPeak = spearman((0 until items.size).map { EnergyArc.PEAK.target(it, items.size) }, energies(run(EnergyArc.FLAT).order))
            println("$mode: rank correlation with the arc — rising ${"%.2f".format(rising)} (flat ${"%.2f".format(flat)}), wave ${"%.2f".format(wave)} (flat ${"%.2f".format(flatWave)}), peak ${"%.2f".format(peak)} (flat ${"%.2f".format(flatPeak)})")
            assertTrue(rising > 0.6 && rising > flat + 0.3, "$mode: rising arc correlates with position ($rising, flat $flat)")
            assertTrue(wave > 0.4 && wave > flatWave + 0.3, "$mode: wave arc follows the wave ($wave, flat $flatWave)")
            assertTrue(peak > 0.5 && peak > flatPeak + 0.3, "$mode: peak arc follows warm-up → peak → cool-down ($peak, flat $flatPeak)")
            assertPermutation(items.size, peakOrder)
        }
    }

    @Test
    fun `arc targets have the documented shape`() {
        assertTrue(EnergyArc.FLAT.target(3, 10).isNaN())
        assertEquals(0.0, EnergyArc.RISING.target(0, 11), 1e-12)
        assertEquals(1.0, EnergyArc.RISING.target(10, 11), 1e-12)
        assertEquals(0.0, EnergyArc.WAVE.target(0, 50), 1e-12)
        assertEquals(1.0, EnergyArc.WAVE.target(EnergyArc.WAVE_PERIOD / 2, 50), 1e-12)
        assertEquals(0.15, EnergyArc.PEAK.target(0, 11), 1e-12)
        assertEquals(1.0, EnergyArc.PEAK.target(7, 11), 1e-12)
        assertEquals(0.4, EnergyArc.PEAK.target(10, 11), 1e-12)
        assertEquals(EnergyArc.PEAK, EnergyArc.parse(" Peak "))
        assertEquals(null, EnergyArc.parse("zigzag"))
    }

    // ------------------------------------------------------------------------------------------ missing analyses

    @Test
    fun `songs without an analysis are placed and never block`() {
        val full = SyntheticLibrary.items(90, seed = 13)
        val rnd = Random(2)
        val items = full.map { if (rnd.nextInt(100) < 30) it.copy(analysis = null) else it }
        val missing = items.count { it.analysis == null }
        assertTrue(missing in 15..40)
        val seq = SetSequencer()
        for ((mode, r) in listOf(
            "full" to seq.full(items, SequenceOptions(seed = 3, arc = EnergyArc.RISING)),
            "online" to seq.online(items.size, SequenceOptions(seed = 3, arc = EnergyArc.RISING)) { items[it] },
        )) {
            assertPermutation(items.size, r.order)
            assertEquals(missing, r.unanalysed, "$mode: unanalysed count")
            for (k in 0 until r.order.size - 1) {
                val a = items[r.order[k]]; val b = items[r.order[k + 1]]
                if (a.analysis == null || b.analysis == null) assertEquals(PairCostModel.UNKNOWN_PAIR_COST, r.pairCosts[k], "$mode: a pair with an unanalysed song costs the neutral value")
            }
            // The analysed neighbours still mix better than in random orders.
            fun analysedMean(order: List<Int>) = (0 until order.size - 1)
                .filter { items[order[it]].analysis != null && items[order[it + 1]].analysis != null }
                .map { model.cost(items[order[it]].analysis, items[order[it + 1]].analysis, prefs) }.average()
            val random = (0 until 10).map { analysedMean(items.indices.shuffled(Random(it))) }.average()
            println("$mode: analysed pairs ${"%.4f".format(analysedMean(r.order))} vs random ${"%.4f".format(random)}")
            assertTrue(analysedMean(r.order) < random, "$mode: analysed pairs still beat random")
        }
        // Nothing analysed at all: still a full order.
        val none = full.map { it.copy(analysis = null) }
        assertPermutation(none.size, seq.full(none, SequenceOptions(seed = 1, arc = EnergyArc.PEAK)).order)
        assertPermutation(none.size, seq.online(none.size, SequenceOptions(seed = 1, arc = EnergyArc.PEAK)) { none[it] }.order)
    }

    // ------------------------------------------------------------------------------------------ edges and options

    @Test
    fun `tiny sets and a fixed first song`() {
        val items = SyntheticLibrary.items(12, seed = 21)
        val seq = SetSequencer()
        for (n in 0..4) {
            val sub = items.take(n)
            assertPermutation(n, seq.full(sub, SequenceOptions(seed = 1)).order)
            assertPermutation(n, seq.online(n, SequenceOptions(seed = 1)) { sub[it] }.order)
        }
        for (first in listOf(0, 5, 11)) {
            assertEquals(first, seq.full(items, SequenceOptions(seed = 9, first = first)).order.first(), "full keeps the first song")
            assertEquals(first, seq.full(items, SequenceOptions(seed = 9, first = first, arc = EnergyArc.RISING)).order.first(), "full keeps the first song with an arc")
            assertEquals(first, seq.online(items.size, SequenceOptions(seed = 9, first = first)) { items[it] }.order.first(), "online keeps the first song")
        }
        assertEquals(SequenceResult.Mode.FULL, seq.sequence(items, SequenceOptions(fullLimit = 12)).mode)
        assertEquals(SequenceResult.Mode.ONLINE, seq.sequence(items, SequenceOptions(fullLimit = 11)).mode)
    }

    @Test
    fun `the local search never ends worse than the greedy start and respects its budget`() {
        val items = SyntheticLibrary.items(60, seed = 17)
        val seq = SetSequencer()
        val greedyOnly = seq.full(items, SequenceOptions(seed = 5, variety = 0.0, searchBudget = 0))
        val searched = seq.full(items, SequenceOptions(seed = 5, variety = 0.0))
        assertEquals(0L, greedyOnly.work)
        println("greedy ${"%.4f".format(greedyOnly.meanCost)} → local search ${"%.4f".format(searched.meanCost)} (${searched.work} work units)")
        assertTrue(searched.meanCost < greedyOnly.meanCost, "2-opt / or-opt improve on the greedy order")
        val capped = seq.full(items, SequenceOptions(seed = 5, variety = 0.0, searchBudget = 5_000))
        assertTrue(capped.work <= 5_000 + items.size, "budget respected (${capped.work})")
    }

    @Test
    fun `online mode reads each song at most once, and only what it samples`() {
        val items = SyntheticLibrary.items(400, seed = 23, seconds = 60..90)
        val calls = IntArray(items.size)
        val seq = SetSequencer()
        val r = seq.online(items.size, SequenceOptions(seed = 1, poolSize = 16, horizon = 40)) { calls[it]++; items[it] }
        assertPermutation(items.size, r.order)
        assertTrue(calls.all { it <= 1 }, "no song is read twice")
        assertEquals(1 + 16 + 40, calls.sum(), "start + pool + one refill per sequenced slot")
        // With an arc, songs sent back under the deck are not read again.
        val arcCalls = IntArray(items.size)
        val withArc = seq.online(items.size, SequenceOptions(seed = 1, poolSize = 16, arc = EnergyArc.RISING)) { arcCalls[it]++; items[it] }
        assertPermutation(items.size, withArc.order)
        assertTrue(arcCalls.all { it == 1 }, "every song read exactly once")
    }

    @Test
    fun `online mode orders a 10,000 song library within the time bound`() {
        val items = SyntheticLibrary.items(10_000, seed = 31, seconds = 60..120)
        val seq = SetSequencer()
        seq.online(500, SequenceOptions(seed = 0)) { items[it] } // warm-up
        val t0 = System.nanoTime()
        val r = seq.online(items.size, SequenceOptions(seed = 1, arc = EnergyArc.WAVE)) { items[it] }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertPermutation(items.size, r.order)
        val sample = (0 until 5).map { meanCost(items, items.indices.shuffled(Random(it))) }.average()
        println("online N=10000: $ms ms, ${r.work} candidate scores, mean cost ${"%.4f".format(r.meanCost)} vs random ${"%.4f".format(sample)}")
        assertTrue(ms < ONLINE_10K_BOUND_MS, "10k songs in $ms ms (bound $ONLINE_10K_BOUND_MS ms)")
        assertTrue(r.meanCost < sample, "and it still beats random")
        // Default sequence() switches to the online mode for a whole library.
        assertEquals(SequenceResult.Mode.ONLINE, seq.sequence(items.take(SequenceOptions.DEFAULT_FULL_LIMIT + 1)).mode)
    }

    @Test
    fun `planner refinement blends the planner's best score into small sets only`() {
        val items = SyntheticLibrary.items(6, seed = 41)
        val refined = SetSequencer(planner = planner, plannerRefineMax = 6)
        val opts = SequenceOptions(seed = 1)
        val base = model.cost(items[0].analysis, items[1].analysis, prefs)
        fun ref(i: Int) = TrackRef(items[i].id, AudioSourceId(items[i].id), items[i].analysis!!)
        val best = planner.plan(ref(0), ref(1), prefs, 1).best.score
        assertEquals(0.5 * (1 - best.coerceIn(0.0, 1.0)) + 0.5 * base, refined.pairCost(items[0], items[1], opts, setSize = 6), 1e-12)
        assertEquals(base, refined.pairCost(items[0], items[1], opts, setSize = 7), 1e-12, "above the limit the planner is not consulted")
        assertEquals(PairCostModel.UNKNOWN_PAIR_COST, refined.pairCost(items[0].copy(analysis = null), items[1], opts, 6))
        assertPermutation(6, refined.full(items, opts).order)
        assertNotEquals(SetSequencer().full(items, opts).pairCosts, refined.full(items, opts).pairCosts)
    }

    private companion object {
        /** Generous for a shared 4-CPU runner; the measured time is printed. */
        const val ONLINE_10K_BOUND_MS = 15_000L
    }
}
