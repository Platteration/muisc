package dev.muisc.transitions.sdk

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
}
