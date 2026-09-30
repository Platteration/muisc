package dev.muisc.transitions.sdk

import dev.muisc.transitions.Params
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Optional self-description a [dev.muisc.transitions.TransitionStrategy] can implement so the planner does not need a
 * hard-coded table for it. User-authored recipes use this; built-in strategies may keep the planner's tables.
 */
interface StrategyTraits {
    /** 0..1: how showy the transition is (compared against the user's energy preference). */
    val ambition: Double

    /** True when the strategy phase-locks B to a master beat grid (it needs confident grids and a matchable tempo). */
    val beatDomain: Boolean

    /** True when the strategy needs stem separation of the decoded windows. */
    val needsStems: Boolean get() = false

    /**
     * What the transition does to the audio when played with [params] (the strategy's resolved parameters, as the
     * planner passes them to `plan()`) on a track with [beatsPerBar] beats per bar. The planner leaves out a
     * strategy that uses any of `prefs.excludedTechniques` (see [Technique]).
     *
     * Null means "not declared": such a strategy is never excluded by technique, only by id
     * (`prefs.disabledStrategies`), which is how the built-in strategies are handled.
     */
    fun techniques(params: Params, beatsPerBar: Int = 4): Set<Technique>? = null
}

/**
 * Something a transition does to the audio beyond levels and the 3-band EQ, so a listening style can rule it out
 * by what a technique actually does rather than by its id or its tags (`prefs.excludedTechniques`).
 */
@Serializable
enum class Technique(val label: String) {
    /** A (tempo-synced) echo / delay. */
    @SerialName("echo") ECHO("echo"),

    /** A reverb, including a frozen reverb tail. */
    @SerialName("reverb") REVERB("reverb"),

    /** A high-pass or low-pass filter (static or moving). */
    @SerialName("filter") FILTER("filter"),

    /** Stem separation: levels applied to drums, bass, vocals or other separately. */
    @SerialName("stems") STEMS("stems"),

    /** A master tempo that travels from one track's tempo to the other's across the overlap. */
    @SerialName("tempoGlide") TEMPO_GLIDE("tempo glide"),

    /** Audio that is in neither track: a generated bridge, riser or texture bed. */
    @SerialName("generated") GENERATED("generated material"),
}
