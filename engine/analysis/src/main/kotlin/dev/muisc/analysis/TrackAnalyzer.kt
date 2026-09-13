package dev.muisc.analysis

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.audio.AudioBuffer

/** Progress callback: [fraction] 0..1, [stage] a short label like "beats" or "key". */
fun interface AnalysisProgress {
    fun onProgress(stage: String, fraction: Double)
    companion object { val NONE = AnalysisProgress { _, _ -> } }
}

/**
 * The full analysis pipeline. Input is the entire decoded track at the engine sample rate (any channel
 * count); output is an immutable [TrackAnalysis]. Implementations must be pure Kotlin, deterministic
 * for a given input, and never throw on silence or very short input (they return low-confidence results).
 */
interface TrackAnalyzer {
    fun analyze(audio: AudioBuffer, sourceId: String, fingerprint: String, progress: AnalysisProgress = AnalysisProgress.NONE): TrackAnalysis
}

/** Persistent cache of analyses keyed by fingerprint + version + sample rate. */
interface AnalysisCache {
    fun get(fingerprint: String, sampleRate: Int, version: Int = TrackAnalysis.CURRENT_VERSION): TrackAnalysis?
    fun put(analysis: TrackAnalysis)
    fun clear()
}

/** In-memory cache (tests, CLI single runs). */
class MemoryAnalysisCache : AnalysisCache {
    private val map = HashMap<String, TrackAnalysis>()
    private fun key(fp: String, sr: Int, v: Int) = "$fp|$sr|$v"
    @Synchronized override fun get(fingerprint: String, sampleRate: Int, version: Int) = map[key(fingerprint, sampleRate, version)]
    @Synchronized override fun put(analysis: TrackAnalysis) { map[key(analysis.fingerprint, analysis.sampleRate, analysis.version)] = analysis }
    @Synchronized override fun clear() = map.clear()
}
