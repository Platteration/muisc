package dev.muisc.player

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioSourceId
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput

/** State of one queue edge (track i → i+1), shown as the queue badge. */
sealed interface CoordinatorState {
    data class Gated(val reason: String) : CoordinatorState
    data object Analysing : CoordinatorState
    data class Planned(val strategyId: String, val score: Double) : CoordinatorState
    data class Rendering(val progress: Double) : CoordinatorState
    data class Ready(val strategyId: String) : CoordinatorState
    data class Live(val kind: String, val reason: String) : CoordinatorState
    data class Failed(val reason: String) : CoordinatorState
}

enum class PowerMode { NORMAL, SAVER, STRICT_SAVER }

/** Supplies analyses (cache first; `urgent` = needed for the current pair, run now on the coordinator thread). */
interface AnalysisService {
    suspend fun analysis(track: AudioSourceId, urgent: Boolean): TrackAnalysis
}

/** Install checks a render must pass before the coordinator installs it (clicks, level jumps, true peak, seam identity). */
interface RenderGate {
    /** Full check with the decoded windows the strategy saw. */
    fun accept(rendered: RenderedTransition, input: TransitionInput): Boolean

    /**
     * Cheap check when no decoded windows are available: rejects non-finite samples, empty audio and renders whose
     * own report lists clicks or clipping.
     */
    fun accept(rendered: RenderedTransition): Boolean {
        val audio = rendered.audio
        if (audio.frames == 0) return false
        for (c in 0 until audio.channelCount) { val x = audio[c]; for (v in x) if (!v.isFinite()) return false }
        return rendered.report.warnings.none { it.startsWith("click") || it.startsWith("clipping") }
    }

    companion object {
        /** Accepts everything that passes the cheap report check. */
        val DEFAULT: RenderGate = object : RenderGate {
            override fun accept(rendered: RenderedTransition, input: TransitionInput): Boolean = accept(rendered)
        }
    }
}

/** One queue entry as the host knows it before analysis. */
data class QueueItem(
    val sourceId: AudioSourceId,
    val albumId: String? = null,
    val title: String = "",
    val artist: String = "",
    /** Stable identity (defaults to the source id); becomes `TrackRef.id`. */
    val id: String = sourceId.value,
)
