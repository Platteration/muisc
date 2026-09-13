package dev.muisc.transitions.sdk

import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import dev.muisc.transitions.TransitionPrefs

/**
 * Typed parameter declarations for a strategy or modifier — a thin, reflection-free layer on the committed
 * [ParamSpec] / [Params]. Each factory registers its spec in declaration order, so the object doubles as the
 * strategy's `params` list:
 *
 * ```kotlin
 * object P : ParamSet("crossfade") { val fadeSec = double("fadeSec", "Fade", 6.0, 1.0, 12.0, "s") }
 * override val params get() = P.specs
 * val fade = params.double(P.fadeSec)
 * ```
 *
 * [resolve] merges, in increasing precedence, the defaults, the per-strategy overrides from
 * `TransitionPrefs.paramOverrides[strategyId]` and the explicit params, so a plan always carries every value.
 */
abstract class ParamSet(val strategyId: String) {
    private val registered = ArrayList<ParamSpec>()

    /** All specs in declaration order. */
    val specs: List<ParamSpec> get() = registered

    /** Spec by id, or null. */
    fun spec(id: String): ParamSpec? = registered.firstOrNull { it.id == id }

    /** Params holding every default. */
    fun defaults(): Params = Params.defaults(registered)

    /** Every spec resolved: explicit [params] win over [prefs] overrides for this strategy, which win over defaults. Unknown keys are dropped. */
    fun resolve(params: Params, prefs: TransitionPrefs? = null): Params {
        val overrides = prefs?.paramOverrides?.get(strategyId).orEmpty()
        return Params(overrides + params.values).resolve(registered)
    }

    private fun <T : ParamSpec> register(spec: T): T {
        require(registered.none { it.id == spec.id }) { "duplicate param id '${spec.id}' in $strategyId" }
        registered.add(spec)
        return spec
    }

    protected fun double(id: String, label: String, default: Double, min: Double, max: Double, unit: String = "", doc: String = ""): ParamSpec.DoubleSpec {
        require(default in min..max) { "$id: default $default outside [$min, $max]" }
        return register(ParamSpec.DoubleSpec(id, label, default, min, max, unit, doc = doc))
    }

    protected fun int(id: String, label: String, default: Int, min: Int, max: Int, unit: String = "", doc: String = ""): ParamSpec.IntSpec {
        require(default in min..max) { "$id: default $default outside [$min, $max]" }
        return register(ParamSpec.IntSpec(id, label, default, min, max, unit, doc))
    }

    protected fun bool(id: String, label: String, default: Boolean, doc: String = ""): ParamSpec.BoolSpec =
        register(ParamSpec.BoolSpec(id, label, default, doc))

    protected fun choice(id: String, label: String, default: String, choices: List<String>, doc: String = ""): ParamSpec.ChoiceSpec =
        register(ParamSpec.ChoiceSpec(id, label, default, choices, doc))

    /** Choice spec over enum constant names (pass `E.entries` or a subset as [values]). */
    protected fun <E : Enum<E>> choice(id: String, label: String, default: E, values: Collection<E>, doc: String = ""): ParamSpec.ChoiceSpec =
        choice(id, label, default.name, values.map { it.name }, doc)
}
