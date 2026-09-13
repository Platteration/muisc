package dev.muisc.transitions.live

import dev.muisc.transitions.FadeLaw
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * Small, documented builders for every [LivePlan] kind. [DefaultLivePlanFactory] decides *where* (frames, bars) from
 * the analyses; these functions turn that decision into nodes, so tests and the CLI can build a plan of a given kind
 * directly. All frames are output frames of the segment except the `Long` track positions; every builder clamps its
 * node frames into `[0, outputFrames]` and sets `aToFrame = min(aFromFrame + outputFrames, aTotalFrames)`.
 *
 * Gain conventions: deck A's lane starts at 1 and ends at 0, deck B's starts at 0 (B is silent at output frame 0) and
 * ends at 1 (unity at the seam), always [FadeLaw.EQUAL_POWER] between the two decks (uncorrelated material). When a
 * [LiveNode.LowSwap] is present the gain lanes drive the mids/highs only; the low band belongs to the swap.
 */
object LivePlanBuilders {
    const val KIND_CROSSFADE = "crossfade"
    const val KIND_PHRASE_CUT = "phraseCut"
    const val KIND_BASS_SWAP = "bassSwap"
    const val KIND_FILTER_SWEEP = "filterSweep"
    const val KIND_ECHO_OUT = "echoOut"

    /** Default corner of the bass hand-over (A's kick/bass region ends, B's begins). */
    const val BASS_SPLIT_HZ = 200.0
    /** Filter sweep defaults (DESIGN §4.8): A's high-pass from 20 Hz to 4 kHz, B's low-pass opening from 300 Hz. */
    const val SWEEP_HP_FROM_HZ = 20.0
    const val SWEEP_HP_TO_HZ = 4000.0
    const val SWEEP_RESONANCE = 1.5
    const val SWEEP_LP_FROM_HZ = 300.0
    const val SWEEP_LP_TO_HZ = 20000.0
    const val SWEEP_LP_Q = 0.7071067811865476

    /**
     * Complementary equal-power gain lanes: A `1 → 0` and B `0 → 1` over `[fromFrame, toFrame)`, so the summed power
     * of two uncorrelated decks stays constant through the fade.
     */
    fun equalPowerGains(fromFrame: Int, toFrame: Int): List<LiveNode.Gain> {
        val f0 = fromFrame.coerceAtLeast(0)
        val f1 = toFrame.coerceAtLeast(f0 + 1)
        return listOf(
            LiveNode.Gain(Deck.A, listOf(LivePoint(f0, 1f, FadeLaw.EQUAL_POWER), LivePoint(f1, 0f))),
            LiveNode.Gain(Deck.B, listOf(LivePoint(f0, 0f, FadeLaw.EQUAL_POWER), LivePoint(f1, 1f))),
        )
    }

    /**
     * `crossfade` — the floor: A fades out and B fades in equal-power over [fadeFrames] starting right at [aFromFrame]
     * ("now"). Needs no analysis at all; the length is the only musical choice (1.5 s for a skip, longer for a
     * deadline miss on a long outro).
     */
    fun crossfade(aFromFrame: Long, aTotalFrames: Long, bFromFrame: Long, fadeFrames: Int): LivePlan {
        val n = fadeFrames.coerceAtLeast(1)
        return LivePlan(KIND_CROSSFADE, aFromFrame, aTo(aFromFrame, aTotalFrames, n), bFromFrame, n, equalPowerGains(0, n))
    }

    /**
     * `phraseCut` — a DJ's hard cut: A stops on a phrase boundary ([aCutFrame]) and B starts on it. The [cutFrames]
     * (16 ms by default in the factory) equal-power gains exist only to declick the seam; B is at unity immediately
     * after, so its first downbeat replaces A's phrase start.
     */
    fun phraseCut(aCutFrame: Long, aTotalFrames: Long, bFromFrame: Long, cutFrames: Int): LivePlan {
        val n = cutFrames.coerceAtLeast(1)
        return LivePlan(KIND_PHRASE_CUT, aCutFrame, aTo(aCutFrame, aTotalFrames, n), bFromFrame, n, equalPowerGains(0, n))
    }

    /**
     * `bassSwap` — the classic EQ mix: with B nudged to A's tempo ([ratio] input frames per output frame, settling to
     * 1.0 by [settleFrame] so the seam is unstretched), B's mids/highs come in under A's over the whole segment
     * (equal-power) while the bass stays with A until [swapAtFrame] (an A downbeat), where the low band hands over in
     * [swapFrames] (a beat): only one kick and bass line at a time, no low-end mud. Segment starts on an A downbeat
     * with B's mix-in downbeat.
     */
    fun bassSwap(
        aFromFrame: Long, aTotalFrames: Long, bFromFrame: Long, outputFrames: Int,
        swapAtFrame: Int, swapFrames: Int, ratio: Double, settleFrame: Int, splitHz: Double = BASS_SPLIT_HZ,
    ): LivePlan {
        val n = outputFrames.coerceAtLeast(1)
        val at = swapAtFrame.coerceIn(0, n)
        val swap = swapFrames.coerceIn(1, (n - at).coerceAtLeast(1))
        val nodes = ArrayList<LiveNode>(4)
        nodes += LiveNode.Rate(ratio, settleFrame.coerceIn(0, n))
        nodes += LiveNode.LowSwap(at, swap, splitHz)
        nodes += equalPowerGains(0, n)
        return LivePlan(KIND_BASS_SWAP, aFromFrame, aTo(aFromFrame, aTotalFrames, n), bFromFrame, n, nodes)
    }

    /**
     * `filterSweep` — resonant sweep out / open in: A's high-pass climbs (exponentially, constant octaves per second)
     * from [aHpFromHz] to [aHpToHz] over the segment so its bass, then its body, disappear into a thin resonant
     * whistle, while B's low-pass opens from [bLpFromHz] to full band under it; both gain lanes cross equal-power.
     * Tempos need not match — the filters mask the rhythmic clash while the two grooves overlap.
     */
    fun filterSweep(
        aFromFrame: Long, aTotalFrames: Long, bFromFrame: Long, outputFrames: Int, sampleRate: Int,
        aHpFromHz: Double = SWEEP_HP_FROM_HZ, aHpToHz: Double = SWEEP_HP_TO_HZ, resonance: Double = SWEEP_RESONANCE,
        bLpFromHz: Double = SWEEP_LP_FROM_HZ, bLpToHz: Double = SWEEP_LP_TO_HZ, bLpQ: Double = SWEEP_LP_Q,
    ): LivePlan {
        val n = outputFrames.coerceAtLeast(1)
        val nyq = 0.45 * sampleRate.coerceAtLeast(1)
        val nodes = ArrayList<LiveNode>(4)
        nodes += LiveNode.Sweep(Deck.A, highPass = true, fromHz = aHpFromHz.coerceIn(1.0, nyq), toHz = aHpToHz.coerceIn(1.0, nyq), q = resonance, fromFrame = 0, toFrame = n)
        nodes += LiveNode.Sweep(Deck.B, highPass = false, fromHz = bLpFromHz.coerceIn(1.0, nyq), toHz = bLpToHz.coerceIn(1.0, nyq), q = bLpQ, fromFrame = 0, toFrame = n)
        nodes += equalPowerGains(0, n)
        return LivePlan(KIND_FILTER_SWEEP, aFromFrame, aTo(aFromFrame, aTotalFrames, n), bFromFrame, n, nodes)
    }

    /**
     * `echoOut` — beat-synced echo tail: B is brought in equal-power under A's last bar (`[0, cutFrame)`); on the
     * downbeat at [cutFrame] A's dry signal is cut and only its feedback delay ([delayFrames], [feedback], loop
     * low-pass at [dampHz]) rings out over B for [tailFrames]. A's gain lane fades the last quarter of the tail so
     * the echo is exactly contained in the segment; B is at unity from the cut on.
     */
    fun echoOut(
        aFromFrame: Long, aTotalFrames: Long, bFromFrame: Long, cutFrame: Int, delayFrames: Int, tailFrames: Int,
        feedback: Float = 0.6f, dampHz: Double = 4000.0,
    ): LivePlan {
        val cut = cutFrame.coerceAtLeast(1)
        val tail = tailFrames.coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE - cut)
        val n = cut + tail
        val fadeFrom = cut + (tail.toLong() * 3 / 4).toInt()
        val nodes = ArrayList<LiveNode>(3)
        nodes += LiveNode.Echo(cut, delayFrames.coerceAtLeast(1), feedback, dampHz)
        nodes += LiveNode.Gain(Deck.A, listOf(LivePoint(0, 1f), LivePoint(fadeFrom, 1f, FadeLaw.EQUAL_POWER), LivePoint(n, 0f)))
        nodes += LiveNode.Gain(Deck.B, listOf(LivePoint(0, 0f, FadeLaw.EQUAL_POWER), LivePoint(cut, 1f)))
        return LivePlan(KIND_ECHO_OUT, aFromFrame, aTo(aFromFrame, aTotalFrames, n), bFromFrame, n, nodes)
    }

    /** Echoes needed for [feedback] to decay to [floorDb] (≥ 1; 1 for a feedback outside (0, 1)). */
    fun echoesToFloor(feedback: Float, floorDb: Double = -60.0): Int {
        if (feedback <= 0f || feedback >= 1f) return 1
        return ceil(ln(10.0.pow(floorDb / 20.0)) / ln(feedback.toDouble())).toInt().coerceAtLeast(1)
    }

    /** Frames until an echo train of [delayFrames] at [feedback] has decayed to [floorDb]. */
    fun echoTailFrames(delayFrames: Int, feedback: Float, floorDb: Double = -60.0): Int =
        (echoesToFloor(feedback, floorDb).toLong() * delayFrames.coerceAtLeast(1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /**
     * Structural problems of a plan (empty when valid): unknown kind, frames outside the track, node frames outside
     * `[0, outputFrames]`, unsorted gain points, a Rate ratio outside the live range, an echo cut after the end.
     */
    fun problems(plan: LivePlan): List<String> {
        val out = ArrayList<String>()
        if (plan.kind !in KINDS) out += "unknown kind '${plan.kind}'"
        if (plan.aFromFrame < 0) out += "aFromFrame ${plan.aFromFrame} < 0"
        if (plan.bFromFrame < 0) out += "bFromFrame ${plan.bFromFrame} < 0"
        if (plan.aToFrame - plan.aFromFrame > plan.outputFrames) out += "A range longer than the segment"
        val n = plan.outputFrames
        fun inRange(name: String, f: Int) { if (f < 0 || f > n) out += "$name $f outside [0, $n]" }
        for (node in plan.nodes) when (node) {
            is LiveNode.Gain -> {
                if (node.points.isEmpty()) out += "empty gain lane on ${node.deck}"
                for (p in node.points) { inRange("gain point", p.frame); if (!p.value.isFinite() || p.value < 0f) out += "gain value ${p.value}" }
                for (i in 1 until node.points.size) if (node.points[i].frame < node.points[i - 1].frame) out += "gain points not sorted on ${node.deck}"
            }
            is LiveNode.Rate -> { inRange("settleFrame", node.settleFrame); if (!node.ratio.isFinite() || node.ratio <= 0.0) out += "rate ratio ${node.ratio}" }
            is LiveNode.LowSwap -> { inRange("lowSwap.atFrame", node.atFrame); inRange("lowSwap end", node.atFrame + node.swapFrames); if (node.swapFrames < 0) out += "negative swapFrames" }
            is LiveNode.Sweep -> { inRange("sweep.fromFrame", node.fromFrame); inRange("sweep.toFrame", node.toFrame); if (node.toFrame < node.fromFrame) out += "sweep ends before it starts"; if (node.q <= 0.0 || node.fromHz <= 0.0 || node.toHz <= 0.0) out += "sweep parameters" }
            is LiveNode.Echo -> { inRange("echo.cutFrame", node.cutFrame); if (node.delayFrames < 1) out += "echo delay < 1"; if (node.feedback < 0f || node.feedback >= 1f) out += "echo feedback ${node.feedback}" }
        }
        if (plan.framesConsumedFromB() < 0) out += "negative B consumption"
        return out
    }

    val KINDS: Set<String> = setOf(KIND_CROSSFADE, KIND_PHRASE_CUT, KIND_BASS_SWAP, KIND_FILTER_SWEEP, KIND_ECHO_OUT)

    private fun aTo(aFromFrame: Long, aTotalFrames: Long, outputFrames: Int): Long =
        minOf(aFromFrame + outputFrames, aTotalFrames).coerceAtLeast(aFromFrame)
}
