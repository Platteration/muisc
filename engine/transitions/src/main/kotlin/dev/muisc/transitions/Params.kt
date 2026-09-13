package dev.muisc.transitions

import kotlinx.serialization.Serializable

/**
 * Typed, self-describing tunable parameter of a strategy. Every strategy publishes its specs so the CLI
 * (`--set id=value`), presets and the in-app Transition Lab are generated from one source of truth.
 */
sealed interface ParamSpec {
    val id: String
    val label: String
    val doc: String
    val defaultString: String

    data class DoubleSpec(
        override val id: String, override val label: String, val default: Double, val min: Double, val max: Double,
        val unit: String = "", val step: Double = 0.0, override val doc: String = "",
    ) : ParamSpec { override val defaultString get() = default.toString() }

    data class IntSpec(
        override val id: String, override val label: String, val default: Int, val min: Int, val max: Int,
        val unit: String = "", override val doc: String = "",
    ) : ParamSpec { override val defaultString get() = default.toString() }

    data class BoolSpec(override val id: String, override val label: String, val default: Boolean, override val doc: String = "") : ParamSpec {
        override val defaultString get() = default.toString()
    }

    data class ChoiceSpec(override val id: String, override val label: String, val default: String, val choices: List<String>, override val doc: String = "") : ParamSpec {
        init { require(default in choices) { "default '$default' not in $choices" } }
        override val defaultString get() = default
    }
}

/** Parameter values as strings (human-readable in JSON and on the command line), parsed through the specs. */
@Serializable
data class Params(val values: Map<String, String> = emptyMap()) {
    fun double(spec: ParamSpec.DoubleSpec): Double = values[spec.id]?.toDoubleOrNull()?.coerceIn(spec.min, spec.max) ?: spec.default
    fun int(spec: ParamSpec.IntSpec): Int = values[spec.id]?.toDoubleOrNull()?.let { Math.round(it).toInt() }?.coerceIn(spec.min, spec.max) ?: spec.default
    fun bool(spec: ParamSpec.BoolSpec): Boolean = values[spec.id]?.let { it.equals("true", true) || it == "1" || it.equals("yes", true) || it.equals("on", true) } ?: spec.default
    fun choice(spec: ParamSpec.ChoiceSpec): String = values[spec.id]?.takeIf { it in spec.choices } ?: spec.default

    fun with(id: String, value: Any): Params = Params(values + (id to value.toString()))
    fun withAll(overrides: Map<String, String>): Params = Params(values + overrides)
    operator fun get(id: String): String? = values[id]
    val isEmpty: Boolean get() = values.isEmpty()

    /** Resolves every spec: explicit values win, otherwise defaults. Unknown keys are dropped. */
    fun resolve(specs: List<ParamSpec>): Params = Params(specs.associate { it.id to (values[it.id] ?: it.defaultString) })

    companion object {
        val EMPTY = Params()
        fun defaults(specs: List<ParamSpec>): Params = Params(specs.associate { it.id to it.defaultString })

        /** Parses `key=value` pairs (CLI `--set`). */
        fun parse(pairs: Iterable<String>): Params = Params(pairs.associate {
            val i = it.indexOf('=')
            require(i > 0) { "expected key=value, got '$it'" }
            it.substring(0, i).trim() to it.substring(i + 1).trim()
        })
    }
}
