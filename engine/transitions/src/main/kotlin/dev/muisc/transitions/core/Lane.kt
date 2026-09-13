package dev.muisc.transitions.core

import dev.muisc.dsp.gain.Curves
import dev.muisc.transitions.AutomationLane
import dev.muisc.transitions.FadeLaw
import dev.muisc.transitions.LanePoint

/**
 * Frame-domain automation (gain, filter cutoff, ...) used by strategies while rendering; converted to an
 * [AutomationLane] in seconds for the plan / Lab with [toAutomationLane].
 *
 * A lane is a sorted list of breakpoints `(frame, value, shapeToNext)`. Before the first point it holds the first
 * value, after the last point the last value, and between two points the value follows the segment's [FadeLaw]:
 *  - rising segment: `v0 + (v1 - v0) * fadeIn(shape, t)`;
 *  - falling segment: `v1 + (v0 - v1) * fadeOut(shape, t)` with `fadeOut(t) = fadeIn(1 - t)`,
 *
 * so a 1 → 0 EQUAL_POWER segment is `cos(pi/2 t)` and its 0 → 1 partner `sin(pi/2 t)` (power-complementary), and a
 * falling EXP segment mirrors the rising one. LINEAR and S_CURVE are symmetric so the distinction does not matter.
 * An empty lane reads 1.0 everywhere (unity). Breakpoint values are Doubles; [fillGains] renders float gains
 * segment by segment with no per-sample lookup, allocation or lambda.
 */
class Lane(val id: String) {
    /** One breakpoint; [shapeToNext] is the law used to reach the next point. */
    data class Point(val frame: Long, val value: Double, val shapeToNext: FadeLaw = FadeLaw.LINEAR)

    private val pts = ArrayList<Point>()

    /** Breakpoints sorted by frame (read-only view, distinct frames). */
    val points: List<Point> get() = pts

    val isEmpty: Boolean get() = pts.isEmpty()
    val size: Int get() = pts.size
    val startFrame: Long get() = if (pts.isEmpty()) 0L else pts.first().frame
    val endFrame: Long get() = if (pts.isEmpty()) 0L else pts.last().frame

    /** Adds (or replaces, at an existing frame) a breakpoint; returns this lane for chaining. */
    fun add(frame: Long, value: Double, shape: FadeLaw = FadeLaw.LINEAR): Lane {
        require(value.isFinite()) { "lane value must be finite" }
        val p = Point(frame, value, shape)
        var i = pts.size
        while (i > 0 && pts[i - 1].frame > frame) i--
        if (i > 0 && pts[i - 1].frame == frame) pts[i - 1] = p else pts.add(i, p)
        return this
    }

    /** Index of the segment containing [frame]: -1 before the first point, `size - 1` at/after the last. */
    private fun segmentAt(frame: Long): Int {
        if (frame < pts[0].frame) return -1
        var lo = 0; var hi = pts.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (pts[mid].frame <= frame) lo = mid else hi = mid }
        return if (pts[hi].frame <= frame) hi else lo
    }

    /** Value of the lane at [frame]. */
    fun valueAt(frame: Long): Double {
        if (pts.isEmpty()) return 1.0
        val i = segmentAt(frame)
        if (i < 0) return pts[0].value
        val p0 = pts[i]
        if (i >= pts.size - 1) return p0.value
        val p1 = pts[i + 1]
        return interpolate(p0.value, p1.value, p0.shapeToNext, (frame - p0.frame).toDouble() / (p1.frame - p0.frame))
    }

    /**
     * Fills `out[offset until offset + n]` with the lane sampled at frames `fromFrame + i`, walking the segments once.
     * Returns [out].
     */
    fun fillGains(out: FloatArray, fromFrame: Long, n: Int = out.size, offset: Int = 0): FloatArray {
        require(n >= 0 && offset >= 0 && offset + n <= out.size) { "out too small for $n samples at $offset" }
        if (n == 0) return out
        if (pts.isEmpty()) { java.util.Arrays.fill(out, offset, offset + n, 1f); return out }
        var i = 0
        val first = pts[0]
        if (fromFrame < first.frame) {
            val cnt = minOf(n.toLong(), first.frame - fromFrame).toInt()
            java.util.Arrays.fill(out, offset, offset + cnt, first.value.toFloat())
            i = cnt
        }
        if (i >= n) return out
        var seg = segmentAt(fromFrame + i)
        while (i < n) {
            val p0 = pts[seg]
            if (seg == pts.size - 1) {
                java.util.Arrays.fill(out, offset + i, offset + n, p0.value.toFloat())
                break
            }
            val p1 = pts[seg + 1]
            val frame = fromFrame + i
            val cnt = minOf((n - i).toLong(), p1.frame - frame).toInt()
            val w = (p1.frame - p0.frame).toDouble()
            val base = (frame - p0.frame).toDouble()
            val v0 = p0.value; val v1 = p1.value
            val o = offset + i
            if (p0.shapeToNext == FadeLaw.LINEAR) {
                val slope = (v1 - v0) / w
                for (k in 0 until cnt) out[o + k] = (v0 + slope * (base + k)).toFloat()
            } else {
                val shape = CrossfadeLaw.shape(p0.shapeToNext)
                val inv = 1.0 / w
                if (v1 >= v0) {
                    val span = v1 - v0
                    for (k in 0 until cnt) out[o + k] = (v0 + span * Curves.fadeIn(shape, (base + k) * inv)).toFloat()
                } else {
                    val span = v0 - v1
                    for (k in 0 until cnt) out[o + k] = (v1 + span * Curves.fadeOut(shape, (base + k) * inv)).toFloat()
                }
            }
            i += cnt
            seg++
        }
        return out
    }

    /** Multiplies `channel[start until start + n]` in place by the lane sampled from [fromFrame] (block-wise, no allocation beyond one scratch block). */
    fun applyInPlace(channel: FloatArray, fromFrame: Long, start: Int = 0, n: Int = channel.size - start, scratch: FloatArray? = null) {
        if (n <= 0) return
        val g = scratch ?: FloatArray(minOf(4096, n))
        var done = 0
        while (done < n) {
            val m = minOf(g.size, n - done)
            fillGains(g, fromFrame + done, m, 0)
            val s = start + done
            for (i in 0 until m) channel[s + i] *= g[i]
            done += m
        }
    }

    /**
     * Seconds-domain lane for the plan / Lab. Non-linear segments are densified with `curvePoints - 1` intermediate
     * points so the plotted curve shows the actual law.
     */
    fun toAutomationLane(sampleRate: Int, curvePoints: Int = 8): AutomationLane {
        require(sampleRate > 0)
        val out = ArrayList<LanePoint>()
        for (i in pts.indices) {
            val p = pts[i]
            out.add(LanePoint(p.frame.toDouble() / sampleRate, p.value))
            if (i < pts.size - 1 && p.shapeToNext != FadeLaw.LINEAR) {
                val q = pts[i + 1]
                val w = q.frame - p.frame
                for (j in 1 until curvePoints) {
                    val t = j.toDouble() / curvePoints
                    out.add(LanePoint((p.frame + t * w) / sampleRate, interpolate(p.value, q.value, p.shapeToNext, t)))
                }
            }
        }
        return AutomationLane(id, out)
    }

    override fun toString(): String = "Lane($id, ${pts.size} points)"

    companion object {
        /** Segment value between [v0] and [v1] at `t in [0, 1]` under [law] (rising uses fadeIn, falling fadeOut). */
        fun interpolate(v0: Double, v1: Double, law: FadeLaw, t: Double): Double {
            val shape = CrossfadeLaw.shape(law)
            return if (v1 >= v0) v0 + (v1 - v0) * Curves.fadeIn(shape, t) else v1 + (v0 - v1) * Curves.fadeOut(shape, t)
        }

        /** Two-point lane from ([fromFrame], [from]) to ([toFrame], [to]) along [law]. */
        fun fade(id: String, fromFrame: Long, from: Double, toFrame: Long, to: Double, law: FadeLaw = FadeLaw.LINEAR): Lane =
            Lane(id).add(fromFrame, from, law).add(toFrame, to)
    }
}
