package dev.muisc.transitions.custom

import dev.muisc.transitions.PairFeatures
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.min

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

/**
 * Ratings of one strategy in one bucket: how many explicit ratings ([n]) and the sum of their values (each 0..1),
 * and how many implicit signals ([implicitN], a transition the listener skipped) and the sum of their values.
 * Implicit signals are stored as observations; [FeedbackLearner.multiplier] decides how much they weigh.
 */
@Serializable
data class RatingTally(val n: Int = 0, val sum: Double = 0.0, val implicitN: Int = 0, val implicitSum: Double = 0.0) {
    fun plus(r: Rating): RatingTally = copy(n = n + 1, sum = sum + r.value)

    /** Adds one implicit signal (see [FeedbackLearner.recordImplicit]). */
    fun plusImplicit(r: Rating): RatingTally = copy(implicitN = implicitN + 1, implicitSum = implicitSum + r.value)

    /** Neither a rating nor an implicit signal. */
    val isEmpty: Boolean get() = n == 0 && implicitN == 0
}

/**
 * The learned multiplier for one (strategy, bucket), with what it was learned from: [ratings] explicit ratings and
 * [implicit] implicit signals (skipped transitions).
 */
data class LearnedFactor(val multiplier: Double, val ratings: Int, val bucket: ContextBucket?, val implicit: Int = 0) {
    /** `learned ×1.12 from 5 ratings in 'matched, in key, rising'`; `... from 2 ratings and 3 skips in ...` with skips. */
    fun describe(): String {
        val parts = ArrayList<String>(2)
        if (ratings > 0 || implicit == 0) parts += "$ratings rating${if (ratings == 1) "" else "s"}"
        if (implicit > 0) parts += "$implicit skip${if (implicit == 1) "" else "s"}"
        return "learned ×${"%.2f".format(multiplier)} from ${parts.joinToString(" and ")}" + (bucket?.let { " in '${it.label}'" } ?: "")
    }

    /** At least one explicit rating or implicit signal is behind [multiplier]. */
    val isLearned: Boolean get() = ratings > 0 || implicit > 0

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
 * Implicit signals ([recordImplicit]: the listener skipped a transition during it or just after it, see
 * `TransitionCoordinator` in engine/player) are weaker evidence than a rating and bounded. `m` implicit signals of
 * values `q_j` (a skip is a down-vote, 0) count as `W = min(IMPLICIT_WEIGHT · m, IMPLICIT_CAP)` pseudo-ratings of
 * their mean value:
 *
 * ```
 * p = (K + Σ r_i + W · mean(q)) / (2K + n + W)
 * ```
 *
 * so one skip moves the multiplier a quarter as far as one down-vote (×0.97 against ×0.90 at K = 2), and skips alone
 * can never take it below `0.5 + K / (2K + IMPLICIT_CAP)` = ×0.83, however many there are. Adding an implicit
 * down-signal never raises the multiplier, and the bounds and monotonicity above hold for any mix.
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
            require(t.implicitN >= 0 && t.implicitSum >= 0.0 && t.implicitSum <= t.implicitN + 1e-9) { "invalid implicit tally for $s/$b: $t" }
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

    /** Records an implicit signal (default: a skipped transition, i.e. a weak down-vote); see the class comment. */
    @Synchronized
    fun recordImplicit(strategyId: String, bucket: ContextBucket, rating: Rating = Rating.Down) {
        require(strategyId.isNotBlank()) { "strategy id is blank" }
        val m = tallies.getOrPut(strategyId) { sortedMapOf() }
        m[bucket.key] = (m[bucket.key] ?: RatingTally()).plusImplicit(rating)
    }

    fun recordImplicit(strategyId: String, features: PairFeatures, rating: Rating = Rating.Down) =
        recordImplicit(strategyId, ContextBucket.of(features), rating)

    fun tally(strategyId: String, bucket: ContextBucket): RatingTally {
        refresher?.invoke()
        return synchronized(this) { tallies[strategyId]?.get(bucket.key) ?: RatingTally() }
    }

    fun factor(strategyId: String, bucket: ContextBucket): LearnedFactor {
        val t = tally(strategyId, bucket)
        if (t.isEmpty) return LearnedFactor(1.0, 0, bucket)
        return LearnedFactor(multiplier(t), t.n, bucket, t.implicitN)
    }

    fun factor(strategyId: String, features: PairFeatures): LearnedFactor = factor(strategyId, ContextBucket.of(features))

    /** Every rated (strategy, bucket) with its factor, sorted by strategy then bucket order. */
    fun table(): List<Pair<String, LearnedFactor>> {
        refresher?.invoke()
        return synchronized(this) {
            tallies.flatMap { (s, m) ->
                ContextBucket.ALL.mapNotNull { b -> m[b.key]?.let { s to LearnedFactor(multiplier(it), it.n, b, it.implicitN) } }
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

    private fun encode(t: Map<String, Map<String, RatingTally>>): String = encodeTallies(t)

    companion object {
        /** `K` of the Beta(K, K) prior: the number of neutral pseudo-ratings on each side. */
        const val PRIOR_STRENGTH: Double = 2.0

        /** Half-width of the multiplier range around 1. */
        const val SPAN: Double = 0.5
        const val MIN: Double = 1.0 - SPAN
        const val MAX: Double = 1.0 + SPAN

        /** How many ratings one implicit signal counts as. */
        const val IMPLICIT_WEIGHT: Double = 0.25

        /** The most rating-equivalents all implicit signals of one (strategy, bucket) together can count as. */
        const val IMPLICIT_CAP: Double = 2.0

        /** The file version this engine writes when a tally holds implicit signals (1 otherwise); the newest it reads. */
        const val FILE_VERSION: Int = 2

        fun multiplier(t: RatingTally): Double {
            val w = min(IMPLICIT_WEIGHT * t.implicitN, IMPLICIT_CAP)
            val implicitMass = if (t.implicitN == 0) 0.0 else t.implicitSum * (w / t.implicitN)
            val p = (PRIOR_STRENGTH + t.sum + implicitMass) / (2.0 * PRIOR_STRENGTH + t.n + w)
            return (1.0 + (p - 0.5) * 2.0 * SPAN).coerceIn(MIN, MAX)
        }

        /**
         * The file text of [t]. Version 1 (the format older engines read) unless a tally holds implicit signals,
         * which only version 2 has (`implicitN`, `implicitSum`).
         */
        internal fun encodeTallies(t: Map<String, Map<String, RatingTally>>): String {
            val version = if (t.values.any { m -> m.values.any { it.implicitN > 0 } }) FILE_VERSION else 1
            return CustomJson.json.encodeToString(FeedbackFile.serializer(), FeedbackFile(version, t))
        }

        /** Parses [text]; bucket keys that are not one of the eight are rejected. */
        fun fromJson(text: String): FeedbackLearner {
            val f = CustomJson.json.decodeFromString(FeedbackFile.serializer(), text)
            require(f.version <= FILE_VERSION) { "feedback file version ${f.version} is newer than this engine understands ($FILE_VERSION)" }
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
        underLock {
            load(FileStamp.of(file)) // what is on disk now, whatever the stamp says
            save { learner.record(strategyId, features, rating) }
        }
        return learner.factor(strategyId, features)
    }

    /**
     * Adds an implicit signal (a skipped transition; see [FeedbackLearner.recordImplicit]) to the tallies on disk and
     * saves the file, exactly like [record]: under the same lock, never overwriting what another store saved.
     */
    @Synchronized
    fun recordImplicit(strategyId: String, features: PairFeatures, rating: Rating = Rating.Down): LearnedFactor {
        require(strategyId.isNotBlank()) { "strategy id is blank" }
        underLock {
            load(FileStamp.of(file))
            save { learner.recordImplicit(strategyId, features, rating) }
        }
        return learner.factor(strategyId, features)
    }

    /**
     * Forgets every rating and implicit signal of [strategyId] (of every strategy when null), under the same lock as
     * [record], so a rating saved at the same moment by another thread, store or process is neither lost nor brought
     * back. The file as it was is first copied to [backup] (replacing an older backup; null keeps none); the file is
     * then rewritten with what is left, or removed when nothing is left. [learner] follows immediately.
     *
     * A file that cannot be read is copied to [backup] like any other; what is kept of it is what [learner] last
     * read from it (nothing, if it never could).
     */
    @Synchronized
    fun reset(strategyId: String?, backup: File? = File(file.path + BACKUP_SUFFIX)) {
        underLock {
            load(FileStamp.of(file))
            val keep = if (strategyId == null) emptyMap() else learner.current().filterKeys { it != strategyId }
            if (backup != null && file.isFile) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            save { learner.replaceAll(keep) }
        }
    }

    private fun <T> underLock(block: () -> T): T = FileLocks.withLock(file, onDegraded = { why ->
        lockProblem = why?.let { "ratings in ${file.path} were saved without the inter-process lock ($it); a rating saved at the same moment by another program may be lost" }
    }, block)

    /**
     * Applies [change] to [learner] (already loaded from disk) and writes the result, or removes the file when
     * nothing is left (only a [reset] leaves nothing). On failure memory goes back to the file.
     */
    private fun save(change: () -> Unit) {
        var saved = false
        try {
            change()
            if (learner.current().isEmpty()) {
                if (file.exists() && !file.delete() && file.exists()) throw IOException("cannot remove ${file.path}")
            } else {
                if (unreadable && file.isFile) AtomicFiles.preserve(file)
                AtomicFiles.write(file, learner.toJsonAsIs() + "\n")
            }
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

    companion object {
        /** Appended to the file name for the copy [reset] keeps (`feedback.json.bak`). */
        const val BACKUP_SUFFIX: String = ".bak"
    }
}
