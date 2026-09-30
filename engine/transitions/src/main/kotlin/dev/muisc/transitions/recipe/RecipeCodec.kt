@file:OptIn(ExperimentalSerializationApi::class)

package dev.muisc.transitions.recipe

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * One thing wrong with (or worth fixing in) a recipe, with the JSON path it concerns (`a.level[2].v`, `vars.swapBar`,
 * `timing.lengthBars`; empty for the recipe as a whole) and, when the recipe was read from text, the line and
 * column of that path in the file.
 */
data class RecipeProblem(
    val severity: Severity,
    val path: String,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
    /** The knob setting under which the problem appears (`swapBar = 31`), or null when it appears at every setting checked. */
    val setting: String? = null,
) {
    enum class Severity {
        /** The recipe cannot be used. */
        ERROR,

        /** The recipe works, but something is probably not what the author meant. */
        WARNING,
    }

    val isError: Boolean get() = severity == Severity.ERROR

    /** `error at line 3, column 5 (a.levle): unknown key ...` */
    override fun toString(): String = buildString {
        append(if (isError) "error" else "warning")
        if (line != null) {
            append(" at line ").append(line)
            if (column != null) append(", column ").append(column)
        }
        if (path.isNotEmpty()) append(" (").append(path).append(')')
        append(": ").append(message)
    }

    companion object {
        fun error(path: String, message: String, setting: String? = null) = RecipeProblem(Severity.ERROR, path, message, setting = setting)
        fun warning(path: String, message: String, setting: String? = null) = RecipeProblem(Severity.WARNING, path, message, setting = setting)
    }
}

/** A line and column (both 1-based) in a recipe file. */
data class TextPosition(val line: Int, val column: Int)

/**
 * What [RecipeCodec.parse] found: the recipe when the text could be read as one, and every problem found on the way.
 * [positions] maps JSON paths (as used in [RecipeProblem.path]) to where they are written, so problems found later
 * (by [RecipeValidator]) can be pointed at a line with [locate].
 */
class RecipeParseResult(
    val recipe: TransitionRecipe?,
    val problems: List<RecipeProblem>,
    val positions: Map<String, TextPosition> = emptyMap(),
) {
    val ok: Boolean get() = recipe != null

    /** [problems] with line and column filled in from [positions] where they were missing. */
    fun locate(problems: List<RecipeProblem>): List<RecipeProblem> = problems.map { p ->
        if (p.line != null) p else positionOf(p.path)?.let { p.copy(line = it.line, column = it.column) } ?: p
    }

    /** The position of [path] or, failing that, of its nearest written parent (`a.level[3].v` → `a.level[3]` → `a.level`). */
    fun positionOf(path: String): TextPosition? {
        var p = path
        while (true) {
            positions[p]?.let { return it }
            val cut = maxOf(p.lastIndexOf('.'), p.lastIndexOf('['))
            if (cut <= 0) return null
            p = p.substring(0, cut)
        }
    }
}

/**
 * Reads and writes recipe files with messages a person can act on. Where [RecipeFormat.decode] throws on the first
 * mistake with a parser's message, [parse] never throws on bad input: it returns every problem it can find —
 * JSON syntax errors with their line and column, unknown keys with a "did you mean" suggestion drawn from the keys
 * valid at that spot, values of the wrong type, and missing required fields — each with its path.
 *
 * Parsing only checks the SHAPE of the file. Whether the recipe makes sense (ranges, the boundary rule, ...) is
 * [RecipeValidator]'s job.
 */
object RecipeCodec {

    /** Parses [text] (UTF-8 BOM tolerated). */
    fun parse(text: String): RecipeParseResult {
        val src = text.removePrefix("﻿")
        val positions = LinkedHashMap<String, TextPosition>()
        val lines = LineIndex(src)
        val root = try {
            JsonReader(src, lines, positions).readDocument()
        } catch (e: JsonSyntax) {
            val pos = lines.position(e.offset)
            return RecipeParseResult(null, listOf(RecipeProblem(RecipeProblem.Severity.ERROR, "", "JSON syntax: ${e.message}", pos.line, pos.column)), positions)
        }
        val problems = ArrayList<RecipeProblem>()
        ShapeChecker(problems).check(root, TransitionRecipe.serializer().descriptor, "")
        val located = RecipeParseResult(null, emptyList(), positions)
        if (problems.isNotEmpty()) return RecipeParseResult(null, located.locate(problems), positions)
        val recipe = try {
            RecipeFormat.json.decodeFromJsonElement(TransitionRecipe.serializer(), root)
        } catch (e: SerializationException) {
            return RecipeParseResult(null, listOf(RecipeProblem.error("", "could not read the recipe: ${e.message}")), positions)
        } catch (e: IllegalArgumentException) {
            return RecipeParseResult(null, listOf(RecipeProblem.error("", "could not read the recipe: ${e.message}")), positions)
        }
        return RecipeParseResult(recipe, emptyList(), positions)
    }

    /** Parses a file; an unreadable file is a problem, not an exception. */
    fun parse(file: File): RecipeParseResult {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return RecipeParseResult(null, listOf(RecipeProblem.error("", "cannot read ${file.name}: ${e.message ?: e.javaClass.simpleName}")))
        } catch (e: SecurityException) {
            return RecipeParseResult(null, listOf(RecipeProblem.error("", "not allowed to read ${file.name}: ${e.message ?: e.javaClass.simpleName}")))
        }
        return parse(text)
    }

    /** Parses a stream (read fully as UTF-8; not closed). */
    fun parse(stream: InputStream): RecipeParseResult {
        val text = try {
            stream.readBytes().toString(Charsets.UTF_8)
        } catch (e: IOException) {
            return RecipeParseResult(null, listOf(RecipeProblem.error("", "cannot read the recipe: ${e.message ?: e.javaClass.simpleName}")))
        }
        return parse(text)
    }

    /** The recipe as pretty-printed JSON in [RecipeFormat], with a trailing newline. */
    fun encode(recipe: TransitionRecipe): String = RecipeFormat.encode(recipe) + "\n"

    /** The closest of [candidates] to [word] when it is close enough to be a likely typo, else null. */
    fun suggest(word: String, candidates: Collection<String>): String? {
        val lower = word.lowercase()
        candidates.firstOrNull { it.lowercase() == lower }?.let { return it }
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (c in candidates) {
            val d = editDistance(lower, c.lowercase())
            if (d < bestD) { bestD = d; best = c }
        }
        val limit = maxOf(1, minOf(3, (word.length + 2) / 3))
        return if (best != null && bestD <= limit) best else null
    }

    internal fun editDistance(a: String, b: String): Int {
        // Optimal string alignment: insertions, deletions, substitutions and adjacent transpositions ("levle").
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
        return d[a.length][b.length]
    }

    // ---- shape check against the serial descriptors ------------------------------------------------------------

    private class ShapeChecker(val out: MutableList<RecipeProblem>) {
        private val exprName = ExprSerializer.descriptor.serialName

        fun check(el: JsonElement, desc: SerialDescriptor, path: String) {
            if (el is JsonNull) {
                if (!desc.isNullable) out += RecipeProblem.error(path, "null is not allowed here; expected ${describe(desc)}")
                return
            }
            if (desc.serialName.removeSuffix("?") == exprName) {
                if (el !is JsonPrimitive) out += RecipeProblem.error(path, "expected a number or an expression in quotes (like \"bars - 1\"), got ${kindOf(el)}")
                return
            }
            when (val kind = desc.kind) {
                StructureKind.CLASS, StructureKind.OBJECT -> checkObject(el, desc, path)
                StructureKind.LIST -> {
                    if (el !is JsonArray) { out += RecipeProblem.error(path, "expected a list [ ... ], got ${kindOf(el)}"); return }
                    val item = desc.getElementDescriptor(0)
                    el.forEachIndexed { i, e -> check(e, item, "$path[$i]") }
                }
                StructureKind.MAP -> {
                    if (el !is JsonObject) { out += RecipeProblem.error(path, "expected an object { ... }, got ${kindOf(el)}"); return }
                    val value = desc.getElementDescriptor(1)
                    for ((k, v) in el) check(v, value, join(path, k))
                }
                SerialKind.ENUM -> {
                    val names = (0 until desc.elementsCount).map { desc.getElementName(it) }
                    val s = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (s == null) {
                        out += RecipeProblem.error(path, "expected one of ${names.joinToString(", ") { "\"$it\"" }} in quotes, got ${kindOf(el)}")
                    } else if (s !in names) {
                        val hint = suggest(s, names)?.let { " (did you mean '$it'?)" } ?: ""
                        out += RecipeProblem.error(path, "'$s' is not a valid choice$hint. Choices: ${names.joinToString(", ")}")
                    }
                }
                is PrimitiveKind -> checkPrimitive(el, kind, path)
                else -> Unit // polymorphic / contextual: not used by the recipe format
            }
        }

        private fun checkObject(el: JsonElement, desc: SerialDescriptor, path: String) {
            if (el !is JsonObject) { out += RecipeProblem.error(path, "expected an object { ... }, got ${kindOf(el)}"); return }
            val names = (0 until desc.elementsCount).map { desc.getElementName(it) }
            for ((key, value) in el) {
                val i = names.indexOf(key)
                if (i < 0) {
                    val hint = suggest(key, names)?.let { "did you mean '$it'?" } ?: "valid keys here: ${names.joinToString(", ")}"
                    out += RecipeProblem.error(join(path, key), "unknown key '$key' ($hint)")
                } else {
                    check(value, desc.getElementDescriptor(i), join(path, key))
                }
            }
            for (i in 0 until desc.elementsCount) {
                if (!desc.isElementOptional(i) && desc.getElementName(i) !in el) {
                    out += RecipeProblem.error(path, "missing required key '${desc.getElementName(i)}'")
                }
            }
        }

        private fun checkPrimitive(el: JsonElement, kind: PrimitiveKind, path: String) {
            val p = el as? JsonPrimitive
            val ok = p != null && when (kind) {
                PrimitiveKind.STRING, PrimitiveKind.CHAR -> p.isString
                PrimitiveKind.BOOLEAN -> p.content == "true" || p.content == "false"
                PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> p.content.toLongOrNull() != null
                PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> p.content.toDoubleOrNull() != null
            }
            if (!ok) {
                val want = when (kind) {
                    PrimitiveKind.STRING, PrimitiveKind.CHAR -> "text in quotes"
                    PrimitiveKind.BOOLEAN -> "true or false"
                    PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> "a whole number"
                    PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> "a number"
                }
                out += RecipeProblem.error(path, "expected $want, got ${kindOf(el)}")
            }
        }

        private fun describe(desc: SerialDescriptor): String = when (desc.kind) {
            StructureKind.LIST -> "a list"
            StructureKind.CLASS, StructureKind.OBJECT, StructureKind.MAP -> "an object"
            PrimitiveKind.BOOLEAN -> "true or false"
            PrimitiveKind.STRING -> if (desc.serialName == exprName) "a number or an expression" else "text"
            is PrimitiveKind -> "a number"
            SerialKind.ENUM -> "one of " + (0 until desc.elementsCount).joinToString(", ") { desc.getElementName(it) }
            else -> "a value"
        }

        private fun kindOf(el: JsonElement): String = when (el) {
            is JsonObject -> "an object"
            is JsonArray -> "a list"
            JsonNull -> "null"
            is JsonPrimitive -> when {
                el.isString -> "the text \"${el.content.take(40)}\""
                el.content == "true" || el.content == "false" -> el.content
                else -> "the number ${el.content}"
            }
        }
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    // ---- a strict JSON reader that remembers where every path is written -------------------------------------

    private class JsonSyntax(val offset: Int, message: String) : Exception(message)

    private class LineIndex(text: String) {
        private val starts: IntArray = buildList {
            add(0)
            for (i in text.indices) if (text[i] == '\n') add(i + 1)
        }.toIntArray()

        fun position(offset: Int): TextPosition {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (starts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return TextPosition(lo + 1, offset - starts[lo] + 1)
        }
    }

    private class JsonReader(val s: String, val lines: LineIndex, val positions: MutableMap<String, TextPosition>) {
        var i = 0

        fun readDocument(): JsonElement {
            skipWs()
            if (i >= s.length) throw JsonSyntax(i, "the file is empty; a recipe is a JSON object { ... }")
            val v = value("")
            skipWs()
            if (i < s.length) throw JsonSyntax(i, "unexpected '${s[i]}' after the end of the recipe object")
            return v
        }

        private fun mark(path: String, at: Int) {
            if (path.isNotEmpty() && path !in positions) positions[path] = lines.position(at)
        }

        private fun value(path: String): JsonElement {
            skipWs()
            if (i >= s.length) throw JsonSyntax(i, "the file ends too early (a value is missing)")
            return when (val c = s[i]) {
                '{' -> obj(path)
                '[' -> arr(path)
                '"' -> JsonPrimitive(string())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                '\'' -> throw JsonSyntax(i, "text must be in double quotes (\"), not single quotes")
                else -> when {
                    c == '-' || c.isDigit() -> number()
                    c.isLetter() || c == '_' -> {
                        var end = i
                        while (end < s.length && (s[end].isLetterOrDigit() || s[end] == '_')) end++
                        throw JsonSyntax(i, "unexpected '${s.substring(i, end)}' (text and expressions must be in double quotes)")
                    }
                    else -> throw JsonSyntax(i, "unexpected '${printable(c)}' where a value was expected")
                }
            }
        }

        private fun obj(path: String): JsonObject {
            i++ // {
            val map = LinkedHashMap<String, JsonElement>()
            skipWs()
            if (peek() == '}') { i++; return JsonObject(map) }
            while (true) {
                skipWs()
                val keyAt = i
                when (peek()) {
                    '"' -> Unit
                    '}' -> throw JsonSyntax(i, "remove the comma before '}' (JSON does not allow a trailing comma)")
                    '\'' -> throw JsonSyntax(i, "keys must be in double quotes (\"), not single quotes")
                    null -> throw JsonSyntax(i, "the file ends inside an object (a '}' is missing)")
                    else -> throw JsonSyntax(i, "expected a key in double quotes, got '${printable(s[i])}'")
                }
                val key = string()
                val childPath = join(path, key)
                if (key in map) throw JsonSyntax(keyAt, "the key '$key' appears twice in the same object")
                mark(childPath, keyAt)
                skipWs()
                if (peek() != ':') throw JsonSyntax(i, "expected ':' after the key \"$key\"")
                i++
                map[key] = value(childPath)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return JsonObject(map) }
                    null -> throw JsonSyntax(i, "the file ends inside an object (a '}' is missing)")
                    else -> throw JsonSyntax(i, "expected ',' or '}' after the value of \"$key\", got '${printable(s[i])}' (a comma may be missing)")
                }
            }
        }

        private fun arr(path: String): JsonArray {
            i++ // [
            val list = ArrayList<JsonElement>()
            skipWs()
            if (peek() == ']') { i++; return JsonArray(list) }
            while (true) {
                skipWs()
                if (peek() == ']') throw JsonSyntax(i, "remove the comma before ']' (JSON does not allow a trailing comma)")
                val childPath = "$path[${list.size}]"
                mark(childPath, i)
                list += value(childPath)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return JsonArray(list) }
                    null -> throw JsonSyntax(i, "the file ends inside a list (a ']' is missing)")
                    else -> throw JsonSyntax(i, "expected ',' or ']' in a list, got '${printable(s[i])}' (a comma may be missing)")
                }
            }
        }

        private fun string(): String {
            val start = i
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonSyntax(start, "text starting here is never closed with a '\"'")
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        if (i + 1 >= s.length) throw JsonSyntax(i, "the file ends inside an escape sequence")
                        when (val e = s[i + 1]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i + 2, minOf(s.length, i + 6))
                                val code = (if (hex.length == 4) hex.toIntOrNull(16) else null)
                                    ?: throw JsonSyntax(i, "\\u must be followed by four hex digits")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw JsonSyntax(i, "'\\$e' is not a valid escape (use \\\\ for a backslash)")
                        }
                        i += 2
                    }
                    c == '\n' || c == '\r' -> throw JsonSyntax(start, "text starting here is never closed with a '\"' on the same line")
                    c < ' ' -> throw JsonSyntax(i, "control characters must be escaped inside text")
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun number(): JsonElement {
            val start = i
            if (peek() == '-') i++
            if (peek() == '0') i++
            else if (peek()?.isDigit() == true) while (peek()?.isDigit() == true) i++
            else throw JsonSyntax(start, "a number must have digits after '-'")
            if (peek() == '.') {
                i++
                if (peek()?.isDigit() != true) throw JsonSyntax(i, "a number needs digits after the decimal point")
                while (peek()?.isDigit() == true) i++
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek()?.isDigit() != true) throw JsonSyntax(i, "a number needs digits after the exponent")
                while (peek()?.isDigit() == true) i++
            }
            if (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) {
                throw JsonSyntax(start, "expressions must be in double quotes, e.g. \"${s.substring(start, minOf(s.length, start + 12)).trim()}...\"")
            }
            return JsonUnquotedLiteral(s.substring(start, i))
        }

        private fun literal(word: String, v: JsonElement): JsonElement {
            if (!s.startsWith(word, i)) {
                var end = i
                while (end < s.length && (s[end].isLetterOrDigit() || s[end] == '_')) end++
                val got = s.substring(i, end).ifEmpty { s[i].toString() }
                throw JsonSyntax(i, "unexpected '$got' (text and expressions must be in double quotes)")
            }
            val end = i + word.length
            if (end < s.length && (s[end].isLetterOrDigit() || s[end] == '_')) {
                throw JsonSyntax(i, "unexpected '${s.substring(i, end + 1)}...' (text and expressions must be in double quotes)")
            }
            i = end
            return v
        }

        private fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { i++; continue }
                if (c == '/' && i + 1 < s.length && (s[i + 1] == '/' || s[i + 1] == '*')) throw JsonSyntax(i, "JSON does not allow comments; put notes in \"description\" or a var's \"doc\"")
                break
            }
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null
        private fun printable(c: Char): String = if (c < ' ') "\\u%04x".format(c.code) else c.toString()
    }
}
