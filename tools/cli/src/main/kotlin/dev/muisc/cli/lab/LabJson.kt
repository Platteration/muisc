package dev.muisc.cli.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

/** A request the Lab refuses, with the HTTP status to answer and a message the page shows as is. */
class LabError(val status: Int, message: String) : RuntimeException(message)

/** JSON helpers shared by the Lab's handlers: one [Json] instance and tolerant accessors for request bodies. */
object LabJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = false
        explicitNulls = true
    }

    /** Parses a request body as a JSON object; anything else is a 400. */
    fun obj(text: String): JsonObject {
        if (text.isBlank()) return JsonObject(emptyMap())
        val el = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw LabError(400, "the request body is not valid JSON: ${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}")
        }
        return el as? JsonObject ?: throw LabError(400, "the request body must be a JSON object")
    }

    /**
     * A finite number rounded to [decimals] places as a JSON number, or `null` for NaN/∞ — JSON has no NaN, and a
     * metric that could not be measured must reach the page as "unknown", not as a parse error.
     */
    fun num(v: Double, decimals: Int = 4): JsonElement {
        if (!v.isFinite()) return JsonNull
        if (decimals < 0) return JsonPrimitive(v)
        val scale = Math.pow(10.0, decimals.toDouble())
        val r = (v * scale).roundToLong() / scale
        return JsonPrimitive(r)
    }

    fun num(v: Float, decimals: Int = 4): JsonElement = num(v.toDouble(), decimals)
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }

fun JsonObject.requireStr(key: String): String = str(key) ?: throw LabError(400, "'$key' is required")

fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.let { p ->
    if (p is JsonNull) null else p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() ?: throw LabError(400, "'$key' must be a number")
}

fun JsonObject.lng(key: String): Long? = (this[key] as? JsonPrimitive)?.let { p ->
    if (p is JsonNull) null else p.longOrNull ?: p.doubleOrNull?.toLong() ?: throw LabError(400, "'$key' must be a whole number")
}

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { p -> if (p is JsonNull) null else p.booleanOrNull }

/** A `{"id": "value"}` object with every value turned into its string form (numbers and booleans included). */
fun JsonObject.stringMap(key: String): Map<String, String> {
    val el = this[key] ?: return emptyMap()
    if (el is JsonNull) return emptyMap()
    val o = el as? JsonObject ?: throw LabError(400, "'$key' must be an object of id → value")
    val out = LinkedHashMap<String, String>()
    for ((k, v) in o) {
        val p = v as? JsonPrimitive ?: throw LabError(400, "'$key.$k' must be a number, a boolean or a string")
        if (p is JsonNull) continue
        out[k] = p.content
    }
    return out
}

/** A list of strings, or null when the key is absent or null (so "absent" and "empty" can mean different things). */
fun JsonObject.strList(key: String): List<String>? {
    val el = this[key] ?: return null
    if (el is JsonNull) return null
    val a = el as? JsonArray ?: throw LabError(400, "'$key' must be a list")
    return a.map { (it as? JsonPrimitive)?.contentOrNull ?: throw LabError(400, "'$key' must be a list of strings") }
}

fun JsonObject.objList(key: String): List<JsonObject> {
    val el = this[key] ?: return emptyList()
    if (el is JsonNull) return emptyList()
    val a = el as? JsonArray ?: throw LabError(400, "'$key' must be a list")
    return a.map { it as? JsonObject ?: throw LabError(400, "'$key' must be a list of objects") }
}

internal fun JsonElement.primitiveContent(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
