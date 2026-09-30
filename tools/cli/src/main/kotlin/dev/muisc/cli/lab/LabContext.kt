package dev.muisc.cli.lab

import com.github.ajalt.clikt.core.CliktError
import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.AudioSourceId
import dev.muisc.cli.CliContext
import dev.muisc.cli.RenderSupport
import dev.muisc.cli.SynthCommand
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.PlannerCustomization
import dev.muisc.transitions.custom.PresetResolution
import dev.muisc.transitions.custom.UserProfile
import dev.muisc.transitions.planner.DefaultTransitionPlanner
import dev.muisc.transitions.recipe.RecipeCatalog
import dev.muisc.transitions.recipe.RecipeLibrary
import dev.muisc.transitions.recipe.RecipeSet
import java.io.File
import java.security.SecureRandom

/**
 * Everything the Lab needs from the engine, built on the same [CliContext] wiring every command uses (decoder,
 * analysis cache, audio loader, stem separator, player streams, pair analyzer, the `--prefs`/`--set-pref`/`--style`
 * /`--preset` preferences and the user's profile), plus what the Lab adds on top:
 *
 *  - a **reloadable** strategy registry (the 14 built-ins, the modifiers and one `recipe:<id>` per usable recipe in
 *    `<profile>/recipes`), rebuilt by [reloadRecipes] after the page saves a recipe;
 *  - the session's **tracks**, keyed by short ids (`t1`, `t2`, ...) so the page never sends a file path back except
 *    when the user adds a track;
 *  - the per-session **render directory**: every WAV the Lab writes goes there and only files from there are served.
 *
 * The planner uses the profile's customization (pins, learned weights, presets) exactly as the CLI does, and reads
 * them live: a pin set or a vote recorded from the page changes the very next plan.
 */
class LabContext(val cli: CliContext, val sessionDir: File) : AutoCloseable {

    val profile: UserProfile = cli.profile ?: throw CliktError("the Lab needs a profile directory (--profile-dir)")
    val recipesDir: File = CliContext.recipesDir(profile.dir)
    val renderDir: File = File(sessionDir, "renders").apply { mkdirs() }

    /** The recipe library as last loaded and the registry built from it. Replaced as a whole by [reloadRecipes]. */
    class Catalog(val recipeSet: RecipeSet, val registry: DefaultStrategyRegistry, val warnings: List<String>)

    @Volatile var catalog: Catalog = loadCatalog()
        private set

    val registry: DefaultStrategyRegistry get() = catalog.registry

    /** Pins, learned weights and presets of the profile (plus the `--preset` session pin when one was given). */
    val customization: PlannerCustomization = profile.customization(CliContext.sessionPin(cli.preset))

    private val random = SecureRandom()

    /** Re-reads the built-in and user recipes and rebuilds the registry (after a save from the page). */
    fun reloadRecipes(): Catalog {
        catalog = loadCatalog()
        return catalog
    }

    private fun loadCatalog(): Catalog {
        val set = RecipeLibrary(recipesDir).load()
        val built = RecipeCatalog.build(DefaultStrategyRegistry.default(), set)
        val warnings = set.errors.map { "recipe $it" } + built.skipped.map { (id, why) -> "recipe '$id' is not available: $why" }
        return Catalog(set, built.registry, warnings)
    }

    fun planner(registry: DefaultStrategyRegistry = this.registry): DefaultTransitionPlanner =
        DefaultTransitionPlanner(registry, cli.pairAnalyzer, customization = customization)

    /**
     * The preferences for one request. [styleId] null or blank: the command line's as they are (`--prefs`,
     * `--set-pref`, `--style`, `--preset` already folded in by the command). Otherwise the page's style takes the
     * place of the command line's `--style` — prefs file ← that style ← `--set-pref` ← `--rate`/`--channels`, then
     * active presets and `--preset` folded into `paramOverrides` — exactly the prefs `MuiscCommand` builds for
     * `--style <styleId>`. Styles never stack.
     */
    fun prefs(styleId: String?): TransitionPrefs {
        if (styleId.isNullOrBlank()) return cli.prefs
        val style = profile.style(styleId) ?: throw LabError(400, "unknown style '$styleId'. Known: ${profile.styles.all().joinToString(", ") { it.id }}")
        return PresetResolution.fold(cli.prefsWithStyle(style), profile.presetLookup, cli.preset)
    }

    fun features(a: TrackRef, b: TrackRef, prefs: TransitionPrefs): PairFeatures = cli.pairAnalyzer.features(a.analysis, b.analysis, prefs)

    // ---- tracks --------------------------------------------------------------------------------------------------

    /** A track of this session: a short id, its file and its analysis. Waveform peaks are computed on first use. */
    class LabTrack(val id: String, val file: File, val ref: TrackRef) {
        @Volatile internal var peaks: Peaks? = null
        val name: String get() = file.nameWithoutExtension
    }

    private val tracks = LinkedHashMap<String, LabTrack>()
    private var nextTrack = 1

    fun tracks(): List<LabTrack> = synchronized(tracks) { tracks.values.toList() }

    fun track(id: String): LabTrack = synchronized(tracks) { tracks[id] } ?: throw LabError(404, "no track '$id' in this session")

    /** Analyses [file] (cache-fronted) and adds it; adding the same file twice returns the existing track. */
    fun addFile(file: File): LabTrack {
        val f = file.absoluteFile
        synchronized(tracks) { tracks.values.firstOrNull { it.file == f } }?.let { return it }
        val ref = cli.trackRef(f)
        synchronized(tracks) {
            tracks.values.firstOrNull { it.file == f }?.let { return it }
            val t = LabTrack("t${nextTrack++}", f, ref)
            tracks[t.id] = t
            return t
        }
    }

    /**
     * Adds a file, or every supported audio file in a directory (not recursive, sorted by name, at most
     * [MAX_DIR_FILES]). Files that cannot be decoded are skipped and reported, never fatal for the rest.
     */
    fun addPath(path: File, progress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> }): AddResult {
        val f = path.absoluteFile
        if (!f.exists()) throw LabError(404, "no such file or directory: ${path.path}")
        val files = if (f.isDirectory) {
            f.listFiles { x -> x.isFile && x.extension.lowercase() in CliContext.SUPPORTED_EXTENSIONS && !x.name.startsWith(".") }
                ?.sortedBy { it.name }.orEmpty().take(MAX_DIR_FILES)
        } else listOf(f)
        if (files.isEmpty()) throw LabError(400, "no supported audio files in ${path.path} (expected ${CliContext.SUPPORTED_EXTENSIONS.joinToString(", ")})")
        val added = ArrayList<LabTrack>()
        val skipped = ArrayList<String>()
        files.forEachIndexed { i, file ->
            progress(i, files.size, file.name)
            try {
                added += addFile(file)
            } catch (e: CliktError) {
                skipped += e.message ?: file.name
            } catch (e: RuntimeException) {
                skipped += "${file.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (added.isEmpty() && skipped.isNotEmpty()) throw LabError(400, skipped.joinToString("; "))
        return AddResult(added, skipped)
    }

    class AddResult(val added: List<LabTrack>, val skipped: List<String>)

    /** Writes the four synthetic fixture songs (the `muisc synth --set` corpus) into the session and adds them. */
    fun addFixtures(): List<LabTrack> {
        val dir = File(sessionDir, "fixtures").apply { mkdirs() }
        return SynthCommand.FIXTURES.map { (id, make) ->
            val file = File(dir, "$id.wav")
            if (!file.isFile) RenderSupport.writeWav(file, make(cli.sampleRate, 0).render())
            addFile(file)
        }
    }

    /** Min/max waveform peaks of a whole track, streamed from the decoder (never the whole file in memory). */
    fun trackPeaks(t: LabTrack): Peaks {
        t.peaks?.let { return it }
        val p = cli.decoder.open(AudioSourceId(t.file.path)).use { Peaks.of(it, PEAK_BINS, t.ref.analysis.durationSec) }
        t.peaks = p
        return p
    }

    // ---- renders -------------------------------------------------------------------------------------------------

    /** A fresh file name in the render directory: `<prefix>-<16 random hex>.wav`. */
    fun newRenderFile(prefix: String): File {
        val bytes = ByteArray(8).also { random.nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it) }
        return File(renderDir, "$prefix-$id.wav")
    }

    /**
     * The render-directory file called [name], or null. Only names the Lab itself produces are accepted (letters,
     * digits and dashes, `.wav`), and the resolved file must sit directly inside the render directory — the threat
     * is a request like `/files/..%2F..%2Fhome%2Fme%2F.ssh%2Fid_rsa` reading anything the process can read.
     */
    fun servedFile(name: String): File? {
        if (!SERVED_NAME.matches(name)) return null
        val dir = renderDir.canonicalFile
        val f = File(dir, name).canonicalFile
        if (f.parentFile != dir || !f.isFile) return null
        return f
    }

    fun writeWav(file: File, audio: AudioBuffer) = RenderSupport.writeWav(file, audio)

    override fun close() {
        // The render directory is the Lab's own scratch space: remove it with everything in it.
        sessionDir.deleteRecursively()
    }

    companion object {
        const val PEAK_BINS = 1500
        const val MAX_DIR_FILES = 500
        val SERVED_NAME = Regex("^[a-z0-9][a-z0-9-]{0,80}\\.wav$")
    }
}
