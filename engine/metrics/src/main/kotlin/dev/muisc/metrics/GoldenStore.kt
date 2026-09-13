package dev.muisc.metrics

import java.io.File

/**
 * Golden fingerprints on disk, laid out as DESIGN.md §9 prescribes:
 * `<dir>/<strategyId>/<pairId>.json`, one small JSON file per (strategy, pair), no binary assets.
 *
 * The store is intentionally dumb — read, write, list, delete — so the golden test and the
 * `./gradlew updateGoldens` task share exactly the same paths. Ids are sanitised (anything outside
 * `[A-Za-z0-9._-]` becomes `_`) so a pair id built from track titles can be used directly.
 */
class GoldenStore(val dir: File) {

    constructor(path: String) : this(File(path))

    /** The file a fingerprint for (strategyId, pairId) lives in (it need not exist). */
    fun file(strategyId: String, pairId: String): File =
        File(File(dir, sanitize(strategyId)), sanitize(pairId) + EXTENSION)

    fun exists(strategyId: String, pairId: String): Boolean = file(strategyId, pairId).isFile

    /** The stored fingerprint, or null when there is none yet. */
    fun load(strategyId: String, pairId: String): GoldenFingerprint? {
        val f = file(strategyId, pairId)
        if (!f.isFile) return null
        return GoldenFingerprint.fromJson(f.readText(Charsets.UTF_8))
    }

    /** Writes [fingerprint] (pretty-printed, so a golden diff is reviewable in a pull request). */
    fun save(strategyId: String, pairId: String, fingerprint: GoldenFingerprint): File {
        val f = file(strategyId, pairId)
        f.parentFile?.mkdirs()
        f.writeText(fingerprint.toJson(pretty = true), Charsets.UTF_8)
        return f
    }

    fun delete(strategyId: String, pairId: String): Boolean = file(strategyId, pairId).delete()

    /** Strategy ids that have at least one stored fingerprint, sorted. */
    fun strategies(): List<String> =
        dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    /** Pair ids stored for [strategyId], sorted. */
    fun pairs(strategyId: String): List<String> =
        File(dir, sanitize(strategyId)).listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXTENSION) }
            ?.map { it.name.removeSuffix(EXTENSION) }
            ?.sorted()
            ?: emptyList()

    override fun toString(): String = "GoldenStore(${dir.path})"

    companion object {
        const val EXTENSION = ".json"

        /** Default location of the checked-in goldens, relative to the repository root. */
        const val DEFAULT_DIR = "engine/transitions/src/test/resources/golden"

        fun sanitize(id: String): String = buildString(id.length) {
            for (c in id) append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
        }
    }
}
