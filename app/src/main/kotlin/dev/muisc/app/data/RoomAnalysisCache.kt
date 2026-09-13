package dev.muisc.app.data

import dev.muisc.analysis.AnalysisCache
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.AnalysisDao
import dev.muisc.app.data.db.SongDao
import dev.muisc.app.data.db.TrackAnalysisEntity

/**
 * [AnalysisCache] backed by the `track_analysis` Room table, with a small in-memory LRU in front so the coordinator's
 * repeated lookups for the same pair do not re-parse JSON. The engine interface is synchronous, so the DAO methods
 * used here are the blocking ones; callers must be off the main thread (the engine only calls from worker threads).
 *
 * `put` also flips `Song.hasAnalysis` for the song whose URI/path equals the analysis `sourceId`, which is what the
 * library screens use to show the "analysed" indicator without joining the cache table.
 */
class RoomAnalysisCache(
    private val dao: AnalysisDao,
    private val songDao: SongDao? = null,
    private val memoryEntries: Int = 64,
) : AnalysisCache {

    private val memory = object : LinkedHashMap<String, TrackAnalysis>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackAnalysis>?): Boolean = size > memoryEntries
    }

    private fun key(fingerprint: String, sampleRate: Int, version: Int) = "$fingerprint|$sampleRate|$version"

    override fun get(fingerprint: String, sampleRate: Int, version: Int): TrackAnalysis? {
        val k = key(fingerprint, sampleRate, version)
        synchronized(memory) { memory[k] }?.let { return it }
        val entity = try {
            dao.getBlocking(fingerprint, sampleRate, version)
        } catch (e: Exception) {
            null
        } ?: return null
        val parsed = try {
            TrackAnalysis.fromJson(entity.json)
        } catch (e: Exception) {
            // Corrupt or incompatible row: treat as a miss; the next put overwrites it.
            return null
        }
        synchronized(memory) { memory[k] = parsed }
        return parsed
    }

    override fun put(analysis: TrackAnalysis) {
        val k = key(analysis.fingerprint, analysis.sampleRate, analysis.version)
        synchronized(memory) { memory[k] = analysis }
        dao.upsertBlocking(
            TrackAnalysisEntity(
                fingerprint = analysis.fingerprint,
                sampleRate = analysis.sampleRate,
                version = analysis.version,
                sourceId = analysis.sourceId,
                json = analysis.toJson(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        try {
            songDao?.markAnalysedBySourceBlocking(analysis.sourceId)
        } catch (e: Exception) {
            // Best effort: the indicator is cosmetic.
        }
    }

    override fun clear() {
        synchronized(memory) { memory.clear() }
        dao.clearBlocking()
    }
}
