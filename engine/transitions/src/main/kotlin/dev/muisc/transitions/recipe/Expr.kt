package dev.muisc.transitions.recipe

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * A number, or a small arithmetic expression over recipe variables, written wherever a recipe needs a number.
 *
 * In JSON an expression is either a plain number (`8`, `-3.5`) or a string (`"swapBar + beat"`, `"bars - 1"`,
 * `"max(4, bars / 2)"`). Grammar:
 * ```
 * expr    := term (('+' | '-') term)*
 * term    := unary (('*' | '/') unary)*
 * unary   := '-' unary | primary
 * primary := number | name | name '(' expr (',' expr)* ')' | '(' expr ')'
 * ```
 * Functions: `min`, `max`, `clamp(x, lo, hi)`, `abs`, `round`, `floor`, `ceil`. Names resolve through the scope
 * given to [eval] — recipe variables plus the built-ins documented in [RecipeResolver] (`bars`, `total`, `beat`,
 * `off`, ...). Evaluation is pure and deterministic.
 */
@Serializable(with = ExprSerializer::class)
@JvmInline
value class Expr(val source: String) {

    /** Evaluates with [scope] resolving names; throws [ExprException] on syntax errors, unknown names or division by zero. */
    fun eval(scope: (String) -> Double?): Double = Parser(source, scope).parseAll()

    /** Every name referenced (variables and built-ins, not functions), for validation. */
    fun names(): Set<String> {
        val out = LinkedHashSet<String>()
        Parser(source) { name -> out += name; 1.0 }.also { runCatching { it.parseAll() } }
        return out
    }

    /** The literal value when the expression is a plain number, else null. */
    val literal: Double? get() = source.trim().toDoubleOrNull()

    override fun toString(): String = source

    companion object {
        fun of(value: Double): Expr = Expr(formatNumber(value))
        fun of(value: Int): Expr = Expr(value.toString())

        internal fun formatNumber(v: Double): String =
            if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()

        /** Functions available in expressions: name to arity (-1 = variadic, at least 1). */
        val FUNCTIONS: Map<String, Int> = mapOf("min" to -1, "max" to -1, "clamp" to 3, "abs" to 1, "round" to 1, "floor" to 1, "ceil" to 1)
    }

    private class Parser(private val s: String, private val scope: (String) -> Double?) {
        private var i = 0

        fun parseAll(): Double {
            if (s.isBlank()) fail("empty expression")
            val v = expr()
            skipWs()
            if (i < s.length) fail("unexpected '${s[i]}'")
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                skipWs()
                v = when (peek()) {
                    '+' -> { i++; v + term() }
                    '-' -> { i++; v - term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                skipWs()
                v = when (peek()) {
                    '*' -> { i++; v * unary() }
                    '/' -> { i++; val d = unary(); if (d == 0.0) fail("division by zero"); v / d }
                    else -> return v
                }
            }
        }

        private fun unary(): Double {
            skipWs()
            if (peek() == '-') { i++; return -unary() }
            if (peek() == '+') { i++; return unary() }
            return primary()
        }

        private fun primary(): Double {
            skipWs()
            val c = peek() ?: fail("expression ends too early")
            return when {
                c == '(' -> { i++; val v = expr(); expect(')'); v }
                c.isDigit() || c == '.' -> number()
                c.isLetter() || c == '_' -> nameOrCall()
                else -> fail("unexpected '$c'")
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i < s.length && s[i].isDigit()) { while (i < s.length && s[i].isDigit()) i++ } else i = save
            }
            return s.substring(start, i).toDoubleOrNull() ?: fail("bad number '${s.substring(start, i)}'", start)
        }

        private fun nameOrCall(): Double {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            val name = s.substring(start, i)
            skipWs()
            if (peek() == '(') {
                val arity = FUNCTIONS[name] ?: fail("unknown function '$name'", start)
                i++
                val args = ArrayList<Double>()
                skipWs()
                if (peek() != ')') {
                    args += expr()
                    while (true) { skipWs(); if (peek() == ',') { i++; args += expr() } else break }
                }
                expect(')')
                if (arity >= 0 && args.size != arity) fail("$name() takes $arity argument(s), got ${args.size}", start)
                if (arity < 0 && args.isEmpty()) fail("$name() needs at least one argument", start)
                return when (name) {
                    "min" -> args.min()
                    "max" -> args.max()
                    "clamp" -> args[0].coerceIn(minOf(args[1], args[2]), maxOf(args[1], args[2]))
                    "abs" -> abs(args[0])
                    "round" -> round(args[0])
                    "floor" -> floor(args[0])
                    "ceil" -> ceil(args[0])
                    else -> fail("unknown function '$name'", start)
                }
            }
            return scope(name) ?: fail("unknown name '$name'", start)
        }

        private fun expect(c: Char) { skipWs(); if (peek() != c) fail("expected '$c'"); i++ }
        private fun peek(): Char? = if (i < s.length) s[i] else null
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun fail(msg: String, at: Int = i): Nothing = throw ExprException(s, at, msg)
    }
}

/** A syntax or evaluation error in an [Expr], with the character position it refers to. */
class ExprException(val source: String, val position: Int, message: String) :
    IllegalArgumentException("$message in \"$source\" at column ${position + 1}")

/** JSON: an [Expr] is read from a number or a string, and written back as a number when it is one. */
object ExprSerializer : KSerializer<Expr> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.muisc.transitions.recipe.Expr", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Expr {
        val json = decoder as? JsonDecoder ?: return Expr(decoder.decodeString())
        val el = json.decodeJsonElement()
        val p = el as? JsonPrimitive ?: throw SerializationException("expected a number or an expression string, got $el")
        return Expr(p.content)
    }

    override fun serialize(encoder: Encoder, value: Expr) {
        val json = encoder as? JsonEncoder
        val n = value.literal
        if (json != null && n != null) {
            json.encodeJsonElement(if (n == floor(n) && abs(n) < 1e15) JsonPrimitive(n.toLong()) else JsonPrimitive(n))
        } else encoder.encodeString(value.source)
    }
}
