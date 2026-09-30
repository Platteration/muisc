package dev.muisc.transitions.recipe

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where a recipe came from. */
enum class RecipeOrigin { BUILT_IN, USER }

/** Whether a loaded recipe is in use. */
enum class RecipeStatus {
    /** In use: valid, and not replaced by another recipe with the same id. */
    ACTIVE,

    /** Has errors (see its problems); not used. */
    INVALID,

    /** A built-in replaced by a user recipe with the same id. */
    SHADOWED,

    /** Another file of the same origin already defines this id; not used. */
    DUPLICATE,
}

/** One recipe found by [RecipeLibrary.load]. [source] is the resource path or the file path. */
class RecipeEntry(
    val recipe: TransitionRecipe,
    val origin: RecipeOrigin,
    val source: String,
    val file: File?,
    val status: RecipeStatus,
    val problems: List<RecipeProblem>,
) {
    val id: String get() = recipe.id
    val active: Boolean get() = status == RecipeStatus.ACTIVE
    val errors: List<RecipeProblem> get() = problems.filter { it.isError }
    val warnings: List<RecipeProblem> get() = problems.filter { !it.isError }

    override fun toString(): String = "$id ($origin, $status, ${problems.size} problem(s)) from $source"
}

/** A problem found while loading, with the file or resource it concerns. */
data class LibraryProblem(val source: String, val problem: RecipeProblem) {
    override fun toString(): String = "$source: $problem"
}

/**
 * The outcome of [RecipeLibrary.load]: every recipe that could be read ([entries], built-ins first, in index order,
 * then user files by name) and every problem found, including files that could not be read at all.
 */
class RecipeSet(val entries: List<RecipeEntry>, val problems: List<LibraryProblem>) {
    /** The recipes in use, one per id. */
    val active: List<RecipeEntry> get() = entries.filter { it.active }

    /** The recipes in use. */
    val recipes: List<TransitionRecipe> get() = active.map { it.recipe }

    /** The recipe in use with [id], or null. */
    operator fun get(id: String): RecipeEntry? = entries.firstOrNull { it.id == id && it.active }

    /** Every loaded entry with [id] (active or not). */
    fun all(id: String): List<RecipeEntry> = entries.filter { it.id == id }

    val errors: List<LibraryProblem> get() = problems.filter { it.problem.isError }
}

/** What [RecipeLibrary.save], [RecipeLibrary.delete] and [RecipeLibrary.duplicate] did. */
class LibraryResult(val ok: Boolean, val file: File?, val problems: List<RecipeProblem>, val recipe: TransitionRecipe? = null) {
    override fun toString(): String = (if (ok) "ok" else "failed") + (file?.let { " ($it)" } ?: "") + problems.joinToString("") { "\n  $it" }
}

/**
 * The recipes available to the player: built-ins shipped as classpath resources, plus the user's own recipe files
 * in [userDir] (on the JVM usually [defaultUserDir], `~/.muisc/recipes`; on Android any app directory).
 *
 * **Built-ins** live under `recipes/` on the classpath and are listed, one file name per line, in
 * `recipes/index.txt` (a classpath directory cannot be listed from inside a jar). Blank lines and `#` comments are
 * ignored.
 *
 * **User recipes** are the `*.json` files directly in [userDir] (names starting with `.` are ignored; that is where
 * [save] keeps its temporary files).
 *
 * **Loading is a safety net.** A file that cannot be read, is not valid JSON, or whose recipe has errors is reported
 * in [RecipeSet.problems] and skipped; it never stops the other recipes from loading. A user recipe with the same id
 * as a built-in replaces (shadows) it, with a warning; an INVALID user recipe does not, so the built-in stays in use.
 * When two user files define the same id, the first by file name is used and the other is reported.
 *
 * [save] writes atomically (a temporary file in the same directory, then a rename over the target), so a crash
 * never leaves a half-written recipe. It never overwrites a file that holds a different recipe or cannot be read, and
 * it writes nothing while more than one file holds the recipe's id.
 *
 * A file nested deeper than [RecipeCodec.MAX_NESTING] levels, or with an expression nested deeper than
 * [Expr.MAX_DEPTH], is reported like any other bad file. Those two limits bound every recursion in reading and
 * validating a recipe, so no file can exhaust the stack.
 */
class RecipeLibrary(
    val userDir: File?,
    val validator: RecipeValidator = RecipeValidator(),
    private val classLoader: ClassLoader = RecipeLibrary::class.java.classLoader,
    private val resourceDir: String = BUILT_IN_DIR,
) {

    /** Reads every built-in and user recipe. Never throws for bad files; see [RecipeSet.problems]. */
    @Synchronized
    fun load(): RecipeSet {
        val entries = ArrayList<RecipeEntry>()
        val problems = ArrayList<LibraryProblem>()
        val builtIns = loadBuiltIns(problems)
        val users = loadUser(problems)

        // Duplicates within one origin: first wins.
        fun dedupe(list: List<RecipeEntry>, what: String): List<RecipeEntry> {
            val firstById = HashMap<String, RecipeEntry>()
            return list.map { e ->
                if (e.status != RecipeStatus.ACTIVE) return@map e
                val first = firstById[e.id]
                if (first == null) { firstById[e.id] = e; e } else {
                    val p = RecipeProblem.error("id", "$what ${first.source} already defines the recipe '${e.id}', so this one is skipped; give it another id")
                    problems += LibraryProblem(e.source, p)
                    e.with(RecipeStatus.DUPLICATE, p)
                }
            }
        }
        val builtInList = dedupe(builtIns, "the built-in")
        val userList = dedupe(users, "the file")

        val userActive = userList.filter { it.active }.associateBy { it.id }
        for (b in builtInList) {
            val u = userActive[b.id]
            entries += if (b.active && u != null) b.with(RecipeStatus.SHADOWED) else b
        }
        val builtInActive = builtInList.filter { it.active }.associateBy { it.id }
        for (u in userList) {
            val b = builtInActive[u.id]
            entries += when {
                b == null -> u
                u.active -> {
                    val p = RecipeProblem.warning("id", "this recipe replaces the built-in recipe '${u.id}' (it has the same id); rename its id to keep both")
                    problems += LibraryProblem(u.source, p)
                    u.with(u.status, p)
                }
                else -> {
                    val p = RecipeProblem.warning("id", "because this file has errors, the built-in recipe '${u.id}' is used instead")
                    problems += LibraryProblem(u.source, p)
                    u.with(u.status, p)
                }
            }
        }
        return RecipeSet(entries, problems)
    }

    /**
     * Saves [recipe] to [userDir], choosing the file the way [load] and [delete] do: the user file whose recipe with
     * this id is in use (ACTIVE; the first by file name), or, when no file's recipe with this id is in use, the only
     * file holding the id (a draft with errors, being fixed); when no file holds the id, a new `<id>.json`.
     *
     * Refuses a recipe with errors unless [allowErrors], an id that is not a valid file name, a new `<id>.json` that
     * already exists holding a different recipe or that cannot be read, and an id that more than one user file
     * holds: which file to write is then ambiguous, so the error names the files and none is written (delete or
     * rename the extra ones first). The result carries the validator's problems.
     */
    @Synchronized
    fun save(recipe: TransitionRecipe, allowErrors: Boolean = false): LibraryResult {
        val dir = userDir ?: return fail("there is no user recipe directory to save to")
        if (!RecipeValidator.ID_PATTERN.matches(recipe.id)) {
            return fail("the id '${recipe.id}' may only use lowercase letters, digits and dashes; it is also the file name")
        }
        val report = validator.validate(recipe)
        if (!report.valid && !allowErrors) {
            return LibraryResult(false, null, listOf(RecipeProblem.error("", "not saved: the recipe has errors")) + report.problems, recipe)
        }
        val holders = loadUser(ArrayList()).filter { it.id == recipe.id }
        val inUse = holders.firstOrNull { it.active } ?: holders.singleOrNull()
        val others = holders.filter { it !== inUse }
        if (others.isNotEmpty()) {
            val names = holders.joinToString(", ") { it.file?.name ?: it.source } + (inUse?.file?.let { " (in use: ${it.name})" } ?: " (none in use)")
            return LibraryResult(
                false, null,
                listOf(RecipeProblem.error("", "not saved: ${holders.size} files hold the recipe '${recipe.id}': $names; delete or rename the ones you do not want, then save again")) + report.problems,
                recipe,
            )
        }
        val existing = inUse?.file
        val target = existing ?: File(dir, "${recipe.id}$SUFFIX")
        if (existing == null && target.exists()) {
            val held = RecipeCodec.parse(target).recipe
            val why = if (held == null) "exists and could not be read as a recipe" else "holds the recipe '${held.id}'"
            return LibraryResult(false, null, listOf(RecipeProblem.error("", "not saved: ${target.name} $why; move or rename that file first")) + report.problems, recipe)
        }
        try {
            writeAtomically(dir, target, RecipeCodec.encode(recipe))
        } catch (e: IOException) {
            return LibraryResult(false, null, listOf(RecipeProblem.error("", "could not write ${target.path}: ${e.message ?: e.javaClass.simpleName}")) + report.problems, recipe)
        } catch (e: SecurityException) {
            return LibraryResult(false, null, listOf(RecipeProblem.error("", "not allowed to write ${target.path}: ${e.message ?: e.javaClass.simpleName}")) + report.problems, recipe)
        }
        return LibraryResult(true, target, report.problems, recipe)
    }

    /** Deletes the user recipe [id] (its file). Built-ins cannot be deleted; deleting a user recipe that shadowed one brings the built-in back. */
    @Synchronized
    fun delete(id: String): LibraryResult {
        val set = load()
        val user = set.all(id).filter { it.origin == RecipeOrigin.USER }
        val target = user.firstOrNull { it.active } ?: user.firstOrNull()
        if (target?.file == null) {
            val msg = if (set.all(id).any { it.origin == RecipeOrigin.BUILT_IN }) "'$id' is a built-in recipe and cannot be deleted" else "there is no user recipe '$id'"
            return fail(msg)
        }
        val file = target.file
        try {
            Files.deleteIfExists(file.toPath())
        } catch (e: IOException) {
            return LibraryResult(false, file, listOf(RecipeProblem.error("", "could not delete ${file.path}: ${e.message ?: e.javaClass.simpleName}")))
        } catch (e: SecurityException) {
            return LibraryResult(false, file, listOf(RecipeProblem.error("", "not allowed to delete ${file.path}: ${e.message ?: e.javaClass.simpleName}")))
        }
        val notes = ArrayList<RecipeProblem>()
        if (set.all(id).any { it.origin == RecipeOrigin.BUILT_IN }) notes += RecipeProblem.warning("", "the built-in recipe '$id' is used again")
        val others = user.filter { it !== target }
        if (others.isNotEmpty()) notes += RecipeProblem.warning("", "other files still define '$id': ${others.joinToString(", ") { it.file?.name ?: it.source }}")
        return LibraryResult(true, file, notes, target.recipe)
    }

    /**
     * Saves a copy of the recipe [id] (built-in or user; the one in use) under [newId], named [newName] (default:
     * the original name plus " (copy)"), with its own revision reset to 1.
     */
    @Synchronized
    fun duplicate(id: String, newId: String, newName: String? = null): LibraryResult {
        val set = load()
        val source = set[id] ?: set.all(id).firstOrNull()
            ?: return fail("there is no recipe '$id'")
        if (set.all(newId).isNotEmpty()) return fail("a recipe with the id '$newId' already exists")
        val copy = source.recipe.copy(id = newId, name = newName ?: "${source.recipe.name} (copy)", version = 1)
        return save(copy)
    }

    // ---- loading ----------------------------------------------------------------------------------------------

    private fun loadBuiltIns(problems: MutableList<LibraryProblem>): List<RecipeEntry> {
        val indexPath = "$resourceDir/$INDEX"
        val index = try {
            classLoader.getResourceAsStream(indexPath)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            problems += LibraryProblem(indexPath, RecipeProblem.error("", "cannot read the built-in recipe index: ${e.message}"))
            return emptyList()
        }
        if (index == null) {
            problems += LibraryProblem(indexPath, RecipeProblem.error("", "the built-in recipe index is missing, so no built-in recipes are available"))
            return emptyList()
        }
        val out = ArrayList<RecipeEntry>()
        for (raw in index.lines()) {
            val name = raw.substringBefore('#').trim()
            if (name.isEmpty()) continue
            val path = "$resourceDir/$name"
            val parsed = try {
                classLoader.getResourceAsStream(path)?.use { RecipeCodec.parse(it) }
            } catch (e: IOException) {
                RecipeParseResult(null, listOf(RecipeProblem.error("", "cannot read: ${e.message}")))
            }
            if (parsed == null) {
                problems += LibraryProblem(path, RecipeProblem.error("", "listed in $INDEX but missing"))
                continue
            }
            entry(parsed, RecipeOrigin.BUILT_IN, path, null, problems)?.let { out += it }
        }
        return out
    }

    private fun loadUser(problems: MutableList<LibraryProblem>): List<RecipeEntry> {
        val dir = userDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        if (!dir.isDirectory) {
            problems += LibraryProblem(dir.path, RecipeProblem.error("", "the user recipe location is a file, not a directory"))
            return emptyList()
        }
        val files = listJson(dir) ?: run {
            problems += LibraryProblem(dir.path, RecipeProblem.error("", "cannot list the user recipe directory"))
            return emptyList()
        }
        return files.mapNotNull { f -> entry(RecipeCodec.parse(f), RecipeOrigin.USER, f.path, f, problems) }
    }

    private fun entry(parsed: RecipeParseResult, origin: RecipeOrigin, source: String, file: File?, problems: MutableList<LibraryProblem>): RecipeEntry? {
        val recipe = parsed.recipe
        if (recipe == null) {
            parsed.problems.forEach { problems += LibraryProblem(source, it) }
            return null
        }
        val found = parsed.locate(validator.validate(recipe).problems)
        found.forEach { problems += LibraryProblem(source, it) }
        val status = if (found.any { it.isError }) RecipeStatus.INVALID else RecipeStatus.ACTIVE
        return RecipeEntry(recipe, origin, source, file, status, found)
    }

    private fun RecipeEntry.with(status: RecipeStatus, extra: RecipeProblem? = null) =
        RecipeEntry(recipe, origin, source, file, status, if (extra == null) problems else problems + extra)

    private fun listJson(dir: File): List<File>? =
        dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(SUFFIX, ignoreCase = true) }?.sortedBy { it.name }

    private fun fail(message: String) = LibraryResult(false, null, listOf(RecipeProblem.error("", message)))

    companion object {
        /** Classpath directory of the built-in recipes. */
        const val BUILT_IN_DIR = "recipes"

        /** File (inside [BUILT_IN_DIR]) listing the built-in recipe files. */
        const val INDEX = "index.txt"

        const val SUFFIX = ".json"

        /** `~/.muisc/recipes` — the JVM default for user recipes. */
        fun defaultUserDir(): File = File(System.getProperty("user.home") ?: ".", ".muisc/recipes")

        /** Only the built-in recipes (no user directory). */
        fun builtInOnly(): RecipeLibrary = RecipeLibrary(null)

        /**
         * A small, valid recipe to start from: a beat-matched equal-power blend with one knob (its length), B's
         * lows held back until halfway. Every lane respects the boundary rule.
         */
        fun starter(id: String, name: String = id): TransitionRecipe = TransitionRecipe(
            id = id,
            name = name,
            description = "A starting point: a beat-matched blend with B's bass held back until halfway. Change the lanes, then validate it.",
            vars = linkedMapOf(
                "len" to RecipeVar(16.0, 8.0, 32.0, label = "Length", unit = "bars", doc = "How many bars both songs play together", integer = true),
            ),
            timing = RecipeTiming(lengthBars = Expr("len")),
            a = DeckRecipe(
                level = listOf(RecipePoint(Expr.of(0), Expr.of(1), RecipeCurve.EQUAL_POWER), RecipePoint(Expr("bars"), Expr.of(0))),
            ),
            b = DeckRecipe(
                level = listOf(RecipePoint(Expr.of(0), Expr.of(0), RecipeCurve.EQUAL_POWER), RecipePoint(Expr("bars"), Expr.of(1))),
                low = listOf(RecipePoint(Expr("bars / 2 - 1"), Expr.of(-12), RecipeCurve.S_CURVE), RecipePoint(Expr("bars / 2"), Expr.of(0))),
            ),
        )

        private fun writeAtomically(dir: File, target: File, text: String) {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create directory $dir")
            val tmp = File(dir, ".${target.name}.${System.nanoTime()}.tmp")
            try {
                tmp.writeText(text, Charsets.UTF_8)
                try {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }
}
