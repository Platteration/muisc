package dev.muisc.analysis

import dev.muisc.analysis.model.TrackAnalysis
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Persistent [AnalysisCache] on the local file system (JVM / CLI; Android uses its own Room-backed cache).
 *
 * One gzipped JSON file per `(fingerprint, sampleRate, version)` at
 * `dir/<sha1(fingerprint)>-<sampleRate>-v<version>.json.gz` (the fingerprint is hashed so any string — even
 * one carrying a decoder id or path characters — yields a safe, bounded file name). Writes are atomic: the
 * JSON is written to a temporary file in the same directory and then moved over the target with
 * `ATOMIC_MOVE` (falling back to a plain replace on file systems without atomic rename), so a crash never
 * leaves a half-written entry and concurrent readers see either the old or the new file. Corrupt or
 * unreadable entries (truncated gzip, invalid JSON, a schema the current model cannot decode) are treated as
 * misses, deleted best-effort and overwritten by the next [put].
 *
 * A small in-memory LRU ([memoryEntries] analyses) fronts the files so repeated lookups in one session do not
 * hit the disk. All methods are thread-safe.
 */
class FileAnalysisCache(val dir: File, val memoryEntries: Int = 64) : AnalysisCache {
    init {
        require(memoryEntries >= 0)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create cache directory $dir")
    }

    private val memory = object : LinkedHashMap<String, TrackAnalysis>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackAnalysis>?): Boolean = size > memoryEntries
    }

    /** File that holds (or would hold) the entry. */
    fun fileFor(fingerprint: String, sampleRate: Int, version: Int = TrackAnalysis.CURRENT_VERSION): File =
        File(dir, "${sha1(fingerprint)}-$sampleRate-v$version$SUFFIX")

    @Synchronized
    override fun get(fingerprint: String, sampleRate: Int, version: Int): TrackAnalysis? {
        val key = key(fingerprint, sampleRate, version)
        if (memoryEntries > 0) memory[key]?.let { return it }
        val file = fileFor(fingerprint, sampleRate, version)
        if (!file.isFile) return null
        val analysis = try {
            GZIPInputStream(file.inputStream().buffered()).use { TrackAnalysis.fromJson(it.readBytes().toString(StandardCharsets.UTF_8)) }
        } catch (e: Exception) {
            // corrupt entry: ignore (and drop it so the next put overwrites cleanly)
            file.delete()
            return null
        }
        // Defensive: the file name is derived from the key, but the content must agree with it too.
        if (analysis.fingerprint != fingerprint || analysis.sampleRate != sampleRate || analysis.version != version) {
            file.delete()
            return null
        }
        if (memoryEntries > 0) memory[key] = analysis
        return analysis
    }

    @Synchronized
    override fun put(analysis: TrackAnalysis) {
        val target = fileFor(analysis.fingerprint, analysis.sampleRate, analysis.version)
        val tmp = File(dir, "${target.name}.${System.nanoTime()}.${Thread.currentThread().id}$TMP_SUFFIX")
        try {
            GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(analysis.toJson().toByteArray(StandardCharsets.UTF_8)) }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        if (memoryEntries > 0) memory[key(analysis.fingerprint, analysis.sampleRate, analysis.version)] = analysis
    }

    /** Removes every cache entry (and leftover temporary files) from [dir] and empties the memory front. */
    @Synchronized
    override fun clear() {
        memory.clear()
        dir.listFiles()?.forEach { f -> if (f.isFile && (f.name.endsWith(SUFFIX) || f.name.endsWith(TMP_SUFFIX))) f.delete() }
    }

    /** Number of entries on disk (diagnostics). */
    fun entryCount(): Int = dir.listFiles()?.count { it.isFile && it.name.endsWith(SUFFIX) } ?: 0

    private fun key(fp: String, sr: Int, v: Int) = "$fp|$sr|$v"

    companion object {
        const val SUFFIX = ".json.gz"
        private const val TMP_SUFFIX = ".tmp"

        /** Default location on the JVM: `~/.muisc/analysis`. */
        fun defaultDir(): File = File(System.getProperty("user.home"), ".muisc/analysis")

        internal fun sha1(s: String): String = Fingerprint.hex(MessageDigest.getInstance("SHA-1").digest(s.toByteArray(StandardCharsets.UTF_8)))
    }
}
