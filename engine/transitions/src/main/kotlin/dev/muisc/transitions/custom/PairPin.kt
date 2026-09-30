package dev.muisc.transitions.custom

import dev.muisc.transitions.Params
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * "Always use this transition from A into B": a strategy (optionally with a preset and/or explicit params) chosen by
 * the user for one ORDERED pair of tracks. B → A is a different pair. The tracks are identified by
 * [dev.muisc.analysis.model.TrackAnalysis.identity] (a hash of the decoded audio), so a pin keeps working when the
 * files are copied, touched or re-tagged; the property names keep the word "fingerprint" for file-format stability.
 *
 * When the pinned strategy is applicable to the pair the planner ranks it first; when it is blocked (or disabled,
 * unknown, or its `plan()` fails) the planner falls back to the normal ranking and says why in the explanation.
 *
 * @property aLabel / bLabel human-readable names for listings (file names); not used for matching.
 * @property note shown in the explanation next to "pinned by you".
 */
@Serializable
data class PairPin(
    val aFingerprint: String,
    val bFingerprint: String,
    val strategyId: String,
    val presetId: String? = null,
    @Serializable(with = FlatParamsSerializer::class)
    val params: Params? = null,
    val aLabel: String = "",
    val bLabel: String = "",
    val note: String = "",
) {
    /** The pair this pin applies to. */
    val key: Pair<String, String> get() = aFingerprint to bFingerprint

    companion object {
        /** Fingerprint that matches every track; used for session-wide pins such as the CLI's `--preset`. */
        const val ANY: String = "*"
    }
}

/** Finds the pin for an ordered pair. */
fun interface PinLookup {
    fun pin(aFingerprint: String, bFingerprint: String): PairPin?

    companion object {
        val NONE: PinLookup = PinLookup { _, _ -> null }

        /** [first]'s pin when it has one, else [second]'s. */
        fun chain(first: PinLookup, second: PinLookup): PinLookup = PinLookup { a, b -> first.pin(a, b) ?: second.pin(a, b) }

        /** A lookup that returns [pin] for every pair. */
        fun always(pin: PairPin): PinLookup = PinLookup { _, _ -> pin }
    }
}

/** The user's pins. At most one pin per ordered pair: setting a pin replaces the previous one for that pair. */
interface PinStore : PinLookup {
    fun list(): List<PairPin>
    fun set(pin: PairPin)

    /** Removes the pin for (a, b); false when there was none. */
    fun clear(aFingerprint: String, bFingerprint: String): Boolean
    val warnings: List<String>
}

internal fun validatePin(p: PairPin) {
    require(p.aFingerprint.isNotBlank() && p.bFingerprint.isNotBlank()) { "a pin needs both fingerprints" }
    require(p.strategyId.isNotBlank()) { "a pin needs a strategy id" }
    p.presetId?.let { CustomJson.requireId(it, "preset") }
}

class InMemoryPinStore(initial: Collection<PairPin> = emptyList()) : PinStore {
    private val items = LinkedHashMap<Pair<String, String>, PairPin>()

    init { initial.forEach { set(it) } }

    @Synchronized override fun pin(aFingerprint: String, bFingerprint: String): PairPin? = items[aFingerprint to bFingerprint]
    @Synchronized override fun list(): List<PairPin> = items.values.sortedWith(PIN_ORDER)
    @Synchronized override fun set(pin: PairPin) { validatePin(pin); items[pin.key] = pin }
    @Synchronized override fun clear(aFingerprint: String, bFingerprint: String): Boolean = items.remove(aFingerprint to bFingerprint) != null
    override val warnings: List<String> get() = emptyList()
}

internal val PIN_ORDER: Comparator<PairPin> = compareBy({ it.aFingerprint }, { it.bFingerprint })

/**
 * Pins in one JSON file (`{"version": 1, "pins": [ ... ]}`), written atomically. The file is re-read when it changes
 * on disk. A pin entry that does not parse is skipped and reported in [warnings]; a file that does not parse at all
 * yields no pins and a warning. In both cases the file is moved aside (`pins.json.corrupt`) before the next write,
 * so nothing that failed to load is overwritten.
 */
class FilePinStore(val file: File) : PinStore {
    private var cache: List<PairPin> = emptyList()
    private var stamp: Pair<Long, Long>? = null
    private var unreadable = false

    @Volatile override var warnings: List<String> = emptyList()
        private set

    @Synchronized override fun pin(aFingerprint: String, bFingerprint: String): PairPin? =
        load().firstOrNull { it.aFingerprint == aFingerprint && it.bFingerprint == bFingerprint }

    @Synchronized override fun list(): List<PairPin> = load()

    @Synchronized override fun set(pin: PairPin) {
        validatePin(pin)
        val next = load().filter { it.key != pin.key } + pin
        write(next)
    }

    @Synchronized override fun clear(aFingerprint: String, bFingerprint: String): Boolean {
        val current = load()
        val next = current.filter { it.key != (aFingerprint to bFingerprint) }
        if (next.size == current.size) return false
        write(next)
        return true
    }

    private fun write(pins: List<PairPin>) {
        if (unreadable && file.isFile) AtomicFiles.preserve(file)
        val obj = buildJsonObject {
            put("version", 1)
            put("pins", JsonArray(pins.sortedWith(PIN_ORDER).map { CustomJson.json.encodeToJsonElement(PairPin.serializer(), it) }))
        }
        AtomicFiles.write(file, CustomJson.json.encodeToString(JsonObject.serializer(), obj) + "\n")
        stamp = null
        load()
    }

    private fun load(): List<PairPin> {
        if (!file.isFile) {
            cache = emptyList(); stamp = null; unreadable = false; warnings = emptyList()
            return cache
        }
        val now = file.lastModified() to file.length()
        if (now == stamp) return cache
        val problems = ArrayList<String>()
        val pins = ArrayList<PairPin>()
        unreadable = false
        try {
            val root = CustomJson.json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
                ?: throw IllegalArgumentException("expected an object with a \"pins\" array")
            val version = (root["version"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
            if (version > 1) throw IllegalArgumentException("pins file version $version is newer than this engine understands (1)")
            val arr = root["pins"] as? JsonArray ?: throw IllegalArgumentException("expected a \"pins\" array")
            arr.forEachIndexed { i, el ->
                try {
                    val p = CustomJson.json.decodeFromJsonElement(PairPin.serializer(), el)
                    validatePin(p)
                    pins.removeAll { it.key == p.key }
                    pins += p
                } catch (e: Exception) {
                    problems += "skipped pin #$i in ${file.path} (the file will be kept as ${file.name}.corrupt on the next change): " +
                        (e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName)
                }
            }
        } catch (e: Exception) {
            unreadable = true
            problems += "cannot read pins file ${file.path} (it will be kept as ${file.name}.corrupt on the next change): " +
                (e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName)
        }
        // Any skipped content means the next write must keep the original file aside.
        unreadable = problems.isNotEmpty()
        cache = pins.sortedWith(PIN_ORDER)
        stamp = now
        warnings = problems
        return cache
    }
}
