package dev.muisc.transitions.custom

import dev.muisc.transitions.PairFeatures
import kotlinx.serialization.Serializable
import java.io.File

/** A user's verdict on one transition: thumbs up/down or 1..5 stars, mapped to a value in 0..1. */
sealed class Rating(
    /** 0 (worst) .. 1 (best). */
    val value: Double,
) {
    data object Up : Rating(1.0) { override fun toString() = "up" }
    data object Down : Rating(0.0) { override fun toString() = "down" }

    /** 1..5 stars → `(stars − 1) / 4`. */
    class Stars(val stars: Int) : Rating(((stars.also { require(it in 1..5) { "stars must be 1..5, got $it" } }) - 1) / 4.0) {
        override fun toString() = "$stars"
        override fun equals(other: Any?) = other is Stars && other.stars == stars
        override fun hashCode() = stars
    }

    companion object {
        /** `up`, `down` (also `+`, `-`, `good`, `bad`) or `1`..`5`. */
        fun parse(text: String): Rating = when (text.trim().lowercase()) {
            "up", "+", "good", "like" -> Up
            "down", "-", "bad", "dislike" -> Down
            else -> text.trim().toIntOrNull()?.takeIf { it in 1..5 }?.let { Stars(it) }
                ?: throw IllegalArgumentException("a rating is up, down or 1..5, got '$text'")
        }
    }
}

/**
 * The context a rating applies to: 2 × 2 × 2 = 8 buckets derived from [PairFeatures].
 *
 *  - [matched]: both grids confident (`features.beatMatchable`) and `stretchPercent ≤ `[MATCH_STRETCH_PERCENT]. The
 *    limit is fixed (not the user's max-stretch preference) so a pair never changes bucket when prefs change;
 *  - [inKey]: `camelotDistanceAfterShift ≤ 1`;
 *  - [rising]: `energyDelta ≥ 0` (B's head at least as energetic as A's tail).
 */
data class ContextBucket(val matched: Boolean, val inKey: Boolean, val rising: Boolean) {
    /** Stable storage key, e.g. `matched.inkey.rising`. */
    val key: String get() = "${if (matched) "matched" else "unmatched"}.${if (inKey) "inkey" else "clash"}.${if (rising) "rising" else "falling"}"

    /** Human label, e.g. `matched, in key, rising`. */
    val label: String get() = "${if (matched) "matched" else "unmatched"}, ${if (inKey) "in key" else "key clash"}, ${if (rising) "rising" else "falling"}"

    override fun toString(): String = label

    companion object {
        const val MATCH_STRETCH_PERCENT: Double = 8.0

        fun of(f: PairFeatures): ContextBucket = ContextBucket(
            matched = f.beatMatchable && f.stretchPercent <= MATCH_STRETCH_PERCENT,
            inKey = f.camelotDistanceAfterShift <= 1,
            rising = f.energyDelta >= 0.0,
        )

        /** All eight buckets in a fixed order. */
        val ALL: List<ContextBucket> = buildList {
            for (m in listOf(true, false)) for (k in listOf(true, false)) for (r in listOf(true, false)) add(ContextBucket(m, k, r))
        }

        fun parse(key: String): ContextBucket? = ALL.firstOrNull { it.key == key }
    }
}

/** Ratings of one strategy in one bucket: how many, and the sum of their values (each 0..1). */
@Serializable
data class RatingTally(val n: Int = 0, val sum: Double = 0.0) {
    fun plus(r: Rating): RatingTally = RatingTally(n + 1, sum + r.value)
}

/** The learned multiplier for one (strategy, bucket), with what it was learned from. */
data class LearnedFactor(val multiplier: Double, val ratings: Int, val bucket: ContextBucket?) {
    /** `learned ×1.12 from 5 ratings in 'matched, in key, rising'`. */
    fun describe(): String = "learned ×${"%.2f".format(multiplier)} from $ratings rating${if (ratings == 1) "" else "s"}" +
        (bucket?.let { " in '${it.label}'" } ?: "")

    companion object {
        val NEUTRAL = LearnedFactor(1.0, 0, null)
    }
}

/**
 * Learns from the user's ratings which strategies they like in which [ContextBucket], and turns that into a score
 * multiplier the planner applies.
 *
 * For a strategy and bucket with `n` ratings of values `r_i ∈ [0, 1]` (up = 1, down = 0, stars `(s − 1)/4`):
 *
 * ```
 * p = (K + Σ r_i) / (2K + n)           posterior mean of a Beta(K, K) prior, K = PRIOR_STRENGTH
 * multiplier = 1 + (p − ½) · 2 · SPAN = 0.5 + p     (SPAN = 0.5)
 * ```
 *
 * so the multiplier is 1.0 with no ratings, lies strictly inside (0.5, 1.5) (bounded), is pulled toward 1.0 when
 * there are few ratings (shrinkage: one up-vote gives ×1.10 at K = 2, five give ×1.28, twenty ×1.42), and never
 * drops when an up-vote is added (monotone: `p` rises by `(K + n − Σr) / ((2K+n)(2K+n+1)) > 0`). A down-vote
 * lowers it symmetrically. Ratings of one bucket do not affect another.
 *
 * Deterministic: the state is a sorted table of tallies; no clock, no randomness. Thread-safe.
 *
 * The learner of a [FileFeedbackStore] follows its file: before every read ([tally], [factor], [table], [snapshot],
 * [toJson]) it picks up ratings another store or process saved since, so a planner holding this learner sees them.
 */
class FeedbackLearner(initial: Map<String, Map<String, RatingTally>> = emptyMap()) {
    private val tallies = sortedMapOf<String, java.util.SortedMap<String, RatingTally>>()

    /** Set by [FileFeedbackStore]: brings the tallies up to date with the file. Called outside this object's lock. */
    @Volatile internal var refresher: (() -> Unit)? = null

    init {
        load(initial)
    }

    private fun load(initial: Map<String, Map<String, RatingTally>>) {
        for ((s, m) in initial) for ((b, t) in m) {
            require(t.n >= 0 && t.sum >= 0.0 && t.sum <= t.n + 1e-9) { "invalid tally for $s/$b: $t" }
            tallies.getOrPut(s) { sortedMapOf() }[b] = t
        }
    }

    /** Replaces every tally (used by [FileFeedbackStore] when the file changed). */
    @Synchronized
    internal fun replaceAll(next: Map<String, Map<String, RatingTally>>) {
        tallies.clear()
        load(next)
    }

    @Synchronized
    fun record(strategyId: String, bucket: ContextBucket, rating: Rating) {
        require(strategyId.isNotBlank()) { "strategy id is blank" }
        val m = tallies.getOrPut(strategyId) { sortedMapOf() }
        m[bucket.key] = (m[bucket.key] ?: RatingTally()).plus(rating)
    }

    fun record(strategyId: String, features: PairFeatures, rating: Rating) = record(strategyId, ContextBucket.of(features), rating)

    fun tally(strategyId: String, bucket: ContextBucket): RatingTally {
        refresher?.invoke()
        return synchronized(this) { tallies[strategyId]?.get(bucket.key) ?: RatingTally() }
    }

    fun factor(strategyId: String, bucket: ContextBucket): LearnedFactor {
        val t = tally(strategyId, bucket)
        if (t.n == 0) return LearnedFactor(1.0, 0, bucket)
        return LearnedFactor(multiplier(t), t.n, bucket)
    }

    fun factor(strategyId: String, features: PairFeatures): LearnedFactor = factor(strategyId, ContextBucket.of(features))

    /** Every rated (strategy, bucket) with its factor, sorted by strategy then bucket order. */
    fun table(): List<Pair<String, LearnedFactor>> {
        refresher?.invoke()
        return synchronized(this) {
            tallies.flatMap { (s, m) ->
                ContextBucket.ALL.mapNotNull { b -> m[b.key]?.let { s to LearnedFactor(multiplier(it), it.n, b) } }
            }
        }
    }

    /** Copy of the raw tallies (strategy → bucket key → tally). */
    fun snapshot(): Map<String, Map<String, RatingTally>> {
        refresher?.invoke()
        return current()
    }

    @Synchronized
    internal fun current(): Map<String, Map<String, RatingTally>> = tallies.mapValues { (_, m) -> m.toSortedMap() }.toSortedMap()

    fun toJson(): String = encode(snapshot())

    /** The file text of the tallies in memory, without refreshing them first. */
    internal fun toJsonAsIs(): String = encode(current())

    private fun encode(t: Map<String, Map<String, RatingTally>>): String =
        CustomJson.json.encodeToString(FeedbackFile.serializer(), FeedbackFile(1, t))

    companion object {
        /** `K` of the Beta(K, K) prior: the number of neutral pseudo-ratings on each side. */
        const val PRIOR_STRENGTH: Double = 2.0

        /** Half-width of the multiplier range around 1. */
        const val SPAN: Double = 0.5
        const val MIN: Double = 1.0 - SPAN
        const val MAX: Double = 1.0 + SPAN

        fun multiplier(t: RatingTally): Double {
            val p = (PRIOR_STRENGTH + t.sum) / (2.0 * PRIOR_STRENGTH + t.n)
            return (1.0 + (p - 0.5) * 2.0 * SPAN).coerceIn(MIN, MAX)
        }

        /** Parses [text]; bucket keys that are not one of the eight are rejected. */
        fun fromJson(text: String): FeedbackLearner {
            val f = CustomJson.json.decodeFromString(FeedbackFile.serializer(), text)
            require(f.version <= 1) { "feedback file version ${f.version} is newer than this engine understands (1)" }
            for ((s, m) in f.strategies) for (b in m.keys) require(ContextBucket.parse(b) != null) { "unknown context bucket '$b' for $s" }
            return FeedbackLearner(f.strategies)
        }
    }
}

@Serializable
internal data class FeedbackFile(val version: Int = 1, val strategies: Map<String, Map<String, RatingTally>> = emptyMap())

/**
 * [FeedbackLearner] persisted in one JSON file, which is the source of truth when several stores share it (the Lab
 * keeps one store for its lifetime while `muisc rate` or the app write the same file):
 *
 *  - [record] is a read-modify-write under [FileLocks] (an OS lock on `.feedback.json.lock`, shared with other
 *    processes): it re-reads the file, adds the rating to what is on disk and writes the result atomically, so a
 *    rating saved by another store or process is never overwritten;
 *  - [learner] is one object for the store's lifetime whose reads re-load the file when it changed on disk
 *    ([FileStamp]), so a planner holding it sees ratings made elsewhere.
 *
 * A file that cannot be read yields a warning; the learner keeps the last tallies it could read (none on the first
 * load), and the file is moved aside (`feedback.json.corrupt`) before the next rating is written, so the ratings in
 * it are never overwritten.
 */
class FileFeedbackStore(val file: File) {
    val learner: FeedbackLearner = FeedbackLearner()

    /** What the tallies in [learner] were read from; null = the file was missing. Meaningful once [loaded]. */
    private var stamp: FileStamp? = null
    private var loaded = false
    private var unreadable = false

    @Volatile private var loadProblem: String? = null
    @Volatile private var lockProblem: String? = null

    /** Problems with the file as it is now (it is re-read when it changed) and with the last write's lock. */
    val warnings: List<String>
        get() {
            refresh()
            return listOfNotNull(loadProblem, lockProblem)
        }

    init {
        refresh()
        learner.refresher = ::refresh
    }

    /** Re-reads the file when it changed since the tallies in [learner] were read. */
    @Synchronized
    fun refresh() {
        val now = FileStamp.of(file)
        if (loaded && now == stamp) return
        load(now)
    }

    private fun load(now: FileStamp?) {
        loaded = true
        stamp = now
        if (now == null) {
            learner.replaceAll(emptyMap()); unreadable = false; loadProblem = null
            return
        }
        try {
            learner.replaceAll(FeedbackLearner.fromJson(file.readText(Charsets.UTF_8)).current())
            unreadable = false
            loadProblem = null
        } catch (e: Exception) {
            if (!file.isFile) { // removed between the stat and the read
                stamp = null; learner.replaceAll(emptyMap()); unreadable = false; loadProblem = null
                return
            }
            unreadable = true
            loadProblem = "cannot read feedback file ${file.path} (it will be kept as ${file.name}.corrupt on the next rating): " +
                (e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName)
        }
    }

    /** Adds the rating to the ratings on disk and saves the file. */
    @Synchronized
    fun record(strategyId: String, features: PairFeatures, rating: Rating): LearnedFactor {
        require(strategyId.isNotBlank()) { "strategy id is blank" }
        FileLocks.withLock(file, onDegraded = { why ->
            lockProblem = why?.let { "ratings in ${file.path} were saved without the inter-process lock ($it); a rating saved at the same moment by another program may be lost" }
        }) {
            load(FileStamp.of(file)) // what is on disk now, whatever the stamp says
            var saved = false
            try {
                learner.record(strategyId, features, rating)
                if (unreadable && file.isFile) AtomicFiles.preserve(file)
                AtomicFiles.write(file, learner.toJsonAsIs() + "\n")
                saved = true
            } finally {
                if (saved) {
                    unreadable = false
                    loadProblem = null
                    stamp = FileStamp.of(file)
                } else {
                    loaded = false // the next read goes back to the file: memory never holds a rating that is not on disk
                }
            }
        }
        return learner.factor(strategyId, features)
    }
}
