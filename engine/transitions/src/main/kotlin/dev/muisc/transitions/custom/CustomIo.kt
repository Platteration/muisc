package dev.muisc.transitions.custom

import dev.muisc.transitions.Params
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The JSON dialect of the user's customization files (presets, styles, pins, feedback). Strict about unknown keys,
 * like recipes: in a hand-edited file an unknown key is almost always a typo, and a typo that is silently ignored
 * is a setting that silently does nothing. A file that does not parse is reported and skipped, never fatal.
 */
object CustomJson {
    val json: Json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        explicitNulls = false
    }

    /** Ids of presets and styles: lowercase letters, digits and dashes, starting with a letter or digit, ≤ 64 chars. */
    val ID: Regex = Regex("[a-z0-9][a-z0-9-]{0,63}")

    /** Throws [IllegalArgumentException] naming [what] when [id] is not a valid id. */
    fun requireId(id: String, what: String) {
        require(ID.matches(id)) { "$what id '$id' must be lowercase letters, digits and dashes (1–64 chars, starting with a letter or digit)" }
    }
}

/**
 * [Params] as a flat JSON object (`{"overlapBars": "8", "lowHz": 180}`) instead of the engine's `{"values": {...}}`,
 * so hand-written presets read naturally. Numbers and booleans are accepted and stored as their text; nested
 * objects, arrays and `null` are rejected.
 */
object FlatParamsSerializer : KSerializer<Params> {
    private val delegate = MapSerializer(String.serializer(), String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Params) = delegate.serialize(encoder, value.values.toSortedMap())

    override fun deserialize(decoder: Decoder): Params {
        if (decoder !is JsonDecoder) return Params(delegate.deserialize(decoder))
        val element: JsonElement = decoder.decodeJsonElement()
        val obj = element as? JsonObject ?: throw IllegalArgumentException("params must be an object of name → value")
        return Params(obj.entries.associate { (k, v) ->
            val p = v as? JsonPrimitive
            if (p == null || p is JsonNull) throw IllegalArgumentException("param '$k' must be a number, boolean or string")
            k to p.content
        })
    }
}

/**
 * File helpers shared by the stores.
 *
 * [write] is atomic: the text goes to a temporary file in the same directory, which is then moved over the target
 * (an atomic rename where the file system supports it, a plain replacing move otherwise), so a crash mid-write
 * leaves either the old file or the new one, never half of each.
 *
 * [preserve] moves a file that could not be parsed aside (`name.corrupt`, `name.corrupt.1`, ...) before a store
 * rewrites it, so data that failed to load is never silently overwritten.
 */
object AtomicFiles {
    fun write(file: File, text: String) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory for ${file.path}")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create directory ${dir.path}")
        val tmp = File.createTempFile(".${file.name}.", ".tmp", dir)
        try {
            tmp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Moves [file] to the first free `name.corrupt[.n]` next to it and returns the new location. */
    fun preserve(file: File): File {
        var n = 0
        var target: File
        do {
            target = File(file.parentFile, file.name + ".corrupt" + if (n == 0) "" else ".$n")
            n++
        } while (target.exists())
        Files.move(file.toPath(), target.toPath())
        return target
    }
}

/**
 * A directory holding one JSON file per item (`<id>.json`), read with the safety-net rule of AGENTS.md §5: a file
 * that cannot be read or parsed, or whose id does not match its file name, is reported in [warnings] and skipped;
 * it never hides the other items. Files whose names start with a dot, and anything not ending in `.json`, are
 * ignored. The directory is re-read on every [list] / [get], so edits made by hand are picked up.
 */
internal class JsonDirectory<T>(
    val dir: File,
    private val serializer: KSerializer<T>,
    private val idOf: (T) -> String,
    private val what: String,
    /** Extra validation of a decoded item; return a problem to skip it. */
    private val check: (T) -> String? = { null },
) {
    @Volatile var warnings: List<String> = emptyList()
        private set

    fun list(): List<T> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".") }?.sortedBy { it.name }.orEmpty()
        val out = ArrayList<T>(files.size)
        val problems = ArrayList<String>()
        for (f in files) {
            val item = read(f, problems) ?: continue
            out += item
        }
        warnings = problems
        return out.sortedBy(idOf)
    }

    fun get(id: String): T? {
        val f = fileOf(id)
        if (!f.isFile) return null
        val problems = ArrayList<String>()
        val item = read(f, problems)
        if (problems.isNotEmpty()) warnings = problems
        return item
    }

    fun save(item: T) {
        val id = idOf(item)
        CustomJson.requireId(id, what)
        val f = fileOf(id)
        if (f.isFile && read(f, ArrayList()) == null) AtomicFiles.preserve(f)
        AtomicFiles.write(f, CustomJson.json.encodeToString(serializer, item) + "\n")
    }

    fun delete(id: String): Boolean {
        if (!CustomJson.ID.matches(id)) return false
        val f = fileOf(id)
        return f.isFile && f.delete()
    }

    fun fileOf(id: String): File = File(dir, "$id.json")

    private fun read(f: File, problems: MutableList<String>): T? {
        val item = try {
            CustomJson.json.decodeFromString(serializer, f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            problems += "skipped $what file ${f.path}: ${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}"
            return null
        }
        val id = idOf(item)
        if (f.name != "$id.json") {
            problems += "skipped $what file ${f.path}: it holds '$id' (the file must be named $id.json)"
            return null
        }
        check(item)?.let {
            problems += "skipped $what file ${f.path}: $it"
            return null
        }
        return item
    }
}
