package dev.muisc.transitions

import dev.muisc.analysis.model.TrackAnalysis
import java.security.MessageDigest

/**
 * Identity of a render (DESIGN.md §9):
 *
 * `RenderKey = sha256(a.fingerprint, b.fingerprint, TrackAnalysis.CURRENT_VERSION, strategyId, modifiers,
 * resolved params JSON, ENGINE_VERSION, prefs{sampleRate, channels, targetLufs, keyLock, maxStretchPercent}, seed)`
 *
 * Two renders with the same key are bit-identical on the same platform (the engine is deterministic); the key is
 * stamped into `RenderReport.renderKey`, printed by the CLI, included in Lab exports and used as the cache key
 * for retained renders. [material] is the exact string that is hashed — canonical (params sorted by id), so the
 * key never depends on map iteration order — and [compute] is its lowercase 64-hex-digit SHA-256.
 */
object RenderKey {
    /** Bump whenever a rendering change should invalidate every cached render. */
    const val ENGINE_VERSION: String = "0.1.0"

    /** Field separator of [material] (ASCII unit separator, never part of a fingerprint or id). */
    private const val SEP = "\u001f"

    /** The render key of [plan] between [a] and [b] under [prefs] with [seed]. */
    fun compute(a: TrackAnalysis, b: TrackAnalysis, plan: TransitionPlan, prefs: TransitionPrefs, seed: Long): String =
        compute(a.fingerprint, b.fingerprint, plan, prefs, seed)

    fun compute(fingerprintA: String, fingerprintB: String, plan: TransitionPlan, prefs: TransitionPrefs, seed: Long): String =
        sha256Hex(material(fingerprintA, fingerprintB, plan, prefs, seed))

    /** The canonical string hashed by [compute] (for diagnostics and tests). */
    fun material(fingerprintA: String, fingerprintB: String, plan: TransitionPlan, prefs: TransitionPrefs, seed: Long): String = buildString {
        append("a=").append(fingerprintA).append(SEP)
        append("b=").append(fingerprintB).append(SEP)
        append("analysis=").append(TrackAnalysis.CURRENT_VERSION).append(SEP)
        append("strategy=").append(plan.strategyId).append(SEP)
        append("modifiers=").append(plan.modifiers.joinToString(",")).append(SEP)
        append("params=").append(paramsJson(plan.params)).append(SEP)
        append("engine=").append(ENGINE_VERSION).append(SEP)
        append("prefs={sampleRate:").append(prefs.sampleRate)
        append(",channels:").append(prefs.channels)
        append(",targetLufs:").append(prefs.targetLufs)
        append(",keyLock:").append(prefs.keyLock)
        append(",maxStretchPercent:").append(prefs.maxStretchPercent).append('}').append(SEP)
        append("seed=").append(seed)
    }

    /** Canonical JSON object of [params]: keys sorted, values as JSON strings. */
    fun paramsJson(params: Params): String = buildString {
        append('{')
        var first = true
        for (k in params.values.keys.sorted()) {
            if (!first) append(',')
            first = false
            appendJsonString(k).append(':')
            appendJsonString(params.values.getValue(k))
        }
        append('}')
    }

    /** Lowercase hex SHA-256 of [s] (UTF-8). */
    fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(64)
        for (b in digest) { val v = b.toInt() and 0xFF; sb.append(HEX[v ushr 4]).append(HEX[v and 0xF]) }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun StringBuilder.appendJsonString(s: String): StringBuilder {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        return append('"')
    }
}
