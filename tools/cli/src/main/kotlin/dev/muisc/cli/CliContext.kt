package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.muisc.analysis.AnalysisProgress
import dev.muisc.analysis.AnalysisService
import dev.muisc.analysis.DefaultTrackAnalyzer
import dev.muisc.analysis.FileAnalysisCache
import dev.muisc.audio.AudioDecodeException
import dev.muisc.audio.AudioDecoder
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.jvm.JavaSoundDecoder
import dev.muisc.dsp.stems.PseudoStemSeparator
import dev.muisc.player.JvmEngineStreamFactory
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.recipe.RecipeCatalog
import dev.muisc.transitions.recipe.RecipeLibrary
import dev.muisc.transitions.recipe.RecipeSet
import dev.muisc.transitions.DefaultTransitionRenderer
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.custom.PairPin
import dev.muisc.transitions.custom.PlannerCustomization
import dev.muisc.transitions.custom.PresetResolution
import dev.muisc.transitions.custom.StrategyPreset
import dev.muisc.transitions.custom.StyleProfile
import dev.muisc.transitions.custom.UserProfile
import dev.muisc.transitions.planner.DefaultTransitionPlanner
import dev.muisc.transitions.synthetic.JvmTrackAudioLoader
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everything a command needs from the engine, wired once: the JVM decoder, the on-disk analysis cache, the
 * [AnalysisService] in front of it, the shipped strategy registry, the planner, the audio loader and the renderer.
 *
 * Built by [MuiscCommand] from the options every command shares (`--cache-dir`, `--prefs`, `--set-pref`,
 * `--rate`, `--channels`, `--profile-dir`, `--style`, `--preset`), so `muisc render --set-pref energy=0.9` and
 * `muisc ab --set-pref energy=0.9` mean exactly the same thing.
 *
 * @param profile the user's customization directory; its pins, learned weights and presets feed the [planner].
 *   Null (the default) plans without customization.
 * @param preset the `--preset` in force: the planner treats every pair as pinned to that preset's strategy (it
 *   still falls back when the strategy is blocked for the pair) and the preset's values are folded into
 *   [prefs]`.paramOverrides` by [MuiscCommand].
 * @param restyle the command line's prefs resolved again with another style in place of `--style` (see
 *   [prefsWithStyle]). Null for a context built without a command line: [prefs] then count as unstyled.
 */
class CliContext(
    val prefs: TransitionPrefs,
    val cacheDir: File,
    val profile: UserProfile? = null,
    val preset: StrategyPreset? = null,
    private val restyle: ((StyleProfile?) -> TransitionPrefs)? = null,
) : AutoCloseable {

    /**
     * The prefs the command line gives with [style] in place of its `--style` (null: no style), in [PrefsIo.resolve]'s
     * order — prefs file ← style ← `--set-pref` ← `--rate`/`--channels` — and before presets are folded. So a style
     * picked later (the Lab's style menu) replaces the command line's instead of compounding with it.
     */
    fun prefsWithStyle(style: StyleProfile?): TransitionPrefs = restyle?.invoke(style) ?: (style?.apply(prefs) ?: prefs)

    val decoder: AudioDecoder = JavaSoundDecoder()
    val cache: FileAnalysisCache = FileAnalysisCache(cacheDir)
    val analysisService: AnalysisService = AnalysisService(
        analyzer = DefaultTrackAnalyzer(),
        cache = cache,
        decoder = decoder,
        engineSampleRate = prefs.sampleRate,
        channels = prefs.channels,
    )
    /** Built-in recipes plus the user's own from `<profile>/recipes`; a broken user recipe is reported and skipped. */
    val recipeSet: RecipeSet = RecipeLibrary(profile?.let { recipesDir(it.dir) }).load()
    private val recipeCatalog: RecipeCatalog.Result = RecipeCatalog.build(DefaultStrategyRegistry.default(), recipeSet)

    /** The 14 built-in strategies followed by one `recipe:<id>` strategy per usable recipe. */
    val registry: DefaultStrategyRegistry = recipeCatalog.registry

    /** Recipe problems worth telling the user about: errors in recipe files and recipes left out of the registry. */
    val recipeWarnings: List<String> = recipeSet.errors.map { "recipe $it" } +
        recipeCatalog.skipped.map { (id, why) -> "recipe '$id' is not available: $why" }
    val pairAnalyzer: DefaultPairAnalyzer = DefaultPairAnalyzer()
    val customization: PlannerCustomization = profile?.customization(sessionPin(preset)) ?: PlannerCustomization.NONE
    val planner: DefaultTransitionPlanner = DefaultTransitionPlanner(registry, pairAnalyzer, customization = customization)
    val loader: JvmTrackAudioLoader = JvmTrackAudioLoader(decoder)
    val separator: PseudoStemSeparator = PseudoStemSeparator()
    val renderer: DefaultTransitionRenderer = DefaultTransitionRenderer(loader, registry, separator)
    val streams: JvmEngineStreamFactory = JvmEngineStreamFactory(decoder)

    val sampleRate: Int get() = prefs.sampleRate
    val channels: Int get() = prefs.channels

    /** The profile, or a readable error for commands that need one. */
    fun requireProfile(): UserProfile = profile ?: throw CliktError("this command needs a profile directory (--profile-dir)")

    /** Analyses [file] (cache-fronted) and wraps it in a [TrackRef]; every decoding failure becomes a readable error. */
    fun trackRef(file: File, force: Boolean = false, progress: AnalysisProgress = AnalysisProgress.NONE): TrackRef {
        val f = file.absoluteFile
        if (!f.isFile) throw CliktError("no such audio file: ${file.path}")
        val source = AudioSourceId(f.path)
        if (!decoder.canDecode(source)) {
            throw CliktError("unsupported audio file: ${file.path} (expected one of ${SUPPORTED_EXTENSIONS.joinToString(", ")})")
        }
        val analysis = try {
            analysisService.analysisOf(source, progress, force)
        } catch (e: AudioDecodeException) {
            throw CliktError("cannot decode ${file.path}: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            throw CliktError("cannot analyse ${file.path}: ${e.message ?: e.javaClass.simpleName}")
        }
        return TrackRef(
            id = f.path,
            source = source,
            analysis = analysis,
            albumId = f.parentFile?.name,
            title = f.nameWithoutExtension,
        )
    }

    fun features(a: TrackRef, b: TrackRef): PairFeatures = pairAnalyzer.features(a.analysis, b.analysis, prefs)

    override fun close() {
        loader.close()
    }

    companion object {
        val SUPPORTED_EXTENSIONS = listOf("wav", "aif", "aiff", "mp3", "flac", "ogg")

        /** `--cache-dir` ← `MUISC_CACHE` ← `~/.muisc/analysis`. */
        fun defaultCacheDir(explicit: File?): File {
            explicit?.let { return it }
            System.getenv("MUISC_CACHE")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            return File(System.getProperty("user.home") ?: ".", ".muisc/analysis")
        }

        /** `--profile-dir` ← `MUISC_HOME` ← `~/.muisc` (holds `presets/`, `styles/`, `pins.json`, `feedback.json`). */
        /** Where user recipes live inside a profile directory. */
        fun recipesDir(profileDir: File): File = File(profileDir, "recipes")

        fun defaultProfileDir(explicit: File?, env: (String) -> String? = System::getenv): File {
            explicit?.let { return it }
            env("MUISC_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            return File(System.getProperty("user.home") ?: ".", ".muisc")
        }

        /** The session-wide pin `--preset` stands for: that preset's strategy for every pair. */
        fun sessionPin(preset: StrategyPreset?): PairPin? =
            preset?.let { PairPin(PairPin.ANY, PairPin.ANY, it.strategyId, presetId = it.id, note = "--preset ${it.id}") }
    }
}

/**
 * Base of every `muisc` subcommand: the shared wiring options plus the small output helpers.
 *
 * `run()` is final; subcommands implement [execute] and get a ready [CliContext] that is closed afterwards.
 * Exceptions that are not already [CliktError] are rewritten into readable one-liners (a stack trace is printed
 * only with `--debug`), so an unsupported file or a broken plan never dumps a JVM trace on the user.
 */
abstract class MuiscCommand(name: String) : CliktCommand(name = name) {

    private val cacheDirOpt by option("--cache-dir", metavar = "DIR", help = "Analysis cache directory (default \$MUISC_CACHE or ~/.muisc/analysis).").file(canBeFile = false)
    private val prefsFile by option("--prefs", metavar = "FILE", help = "TransitionPrefs JSON file.").file(mustExist = true, canBeDir = false)
    private val prefAssignments by option("--set-pref", metavar = "KEY=VALUE", help = "Override one preference, e.g. --set-pref energy=0.8 (repeatable).").multiple()
    private val rateOpt by option("--rate", metavar = "HZ", help = "Engine sample rate (default 44100).").int()
    private val channelsOpt by option("--channels", metavar = "N", help = "Engine channel count (default 2).").int()
    private val debug by option("--debug", help = "Print a stack trace when a command fails.").flag()
    private val profileDirOpt by option("--profile-dir", metavar = "DIR", help = "Presets, styles, pins and ratings (default \$MUISC_HOME or ~/.muisc).").file(canBeFile = false)
    private val styleOpt by option("--style", metavar = "ID", help = "Apply a listening style to the prefs (see `muisc style list`); --set-pref still wins.")
    private val presetOpt by option("--preset", metavar = "ID", help = "Plan every pair with this preset's strategy and values when it applies (see `muisc preset list`).")

    /** Seed for the deterministic jitter in the planner and for every render. */
    val seed by option("--seed", metavar = "N", help = "Deterministic seed (default 0).").long().default(0L)

    private var started = 0L

    final override fun run() {
        started = System.nanoTime()
        val profile = UserProfile(CliContext.defaultProfileDir(profileDirOpt))
        for (w in profile.warnings()) echo("warning: $w", err = true)
        val style: StyleProfile? = styleOpt?.let { id ->
            profile.style(id) ?: throw CliktError("unknown style '$id'. Known: ${profile.styles.all().joinToString(", ") { it.id }}")
        }
        val preset: StrategyPreset? = presetOpt?.let { id ->
            profile.presetLookup.preset(id) ?: throw CliktError("unknown preset '$id'. Known: ${profile.presets.all().joinToString(", ") { it.id }}")
        }
        val loaded = PrefsIo.load(prefsFile)
        val restyle = { s: StyleProfile? -> PrefsIo.resolve(loaded, prefAssignments, rateOpt, channelsOpt, s) }
        val resolved = restyle(style)
        // Fold active presets (and --preset) into paramOverrides so every code path that re-plans from the overrides
        // (render --set, forced strategies) sees the same values as the planner.
        val prefs = PresetResolution.fold(resolved, profile.presetLookup, preset)
        CliContext(prefs, CliContext.defaultCacheDir(cacheDirOpt), profile, preset, restyle).use { ctx ->
            for (w in ctx.recipeWarnings) echo("warning: $w", err = true)
            if (preset != null && ctx.registry.strategy(preset.strategyId) == null) {
                throw CliktError("preset '${preset.id}' is for strategy '${preset.strategyId}', which is not registered. Known: ${ctx.registry.strategyIds.joinToString(", ")}")
            }
            try {
                execute(ctx)
            } catch (e: CliktError) {
                throw e
            } catch (e: InterruptedException) {
                throw CliktError("interrupted")
            } catch (e: Exception) {
                if (debug) e.printStackTrace()
                throw CliktError("${commandName} failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    abstract fun execute(ctx: CliContext)

    /** Milliseconds since the command started. */
    fun elapsedMs(): Long = (System.nanoTime() - started) / 1_000_000

    /** Prints `<label> in 1.23 s` — every command that renders ends with one of these. */
    fun echoElapsed(label: String) = echo("$label in ${Fmt.sec(elapsedMs() / 1000.0)}")
}

/** Loading and overriding [TransitionPrefs]. */
object PrefsIo {
    val JSON: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
        isLenient = true
    }
    val PRETTY: Json = Json(JSON) { prettyPrint = true }

    val KEYS = listOf(
        "enabled", "allowInAlbums", "keepAlbumFlowInShuffle", "maxStretchPercent", "maxPitchShiftSemitones",
        "keyLock", "targetLufs", "preferredOverlapBars", "energy", "varietyPenalty", "sampleRate", "channels",
        "disabledStrategies", "strategyWeights.<id>", "paramOverrides.<strategyId>.<paramId>", "activePresets.<strategyId>",
    )

    fun load(file: File?): TransitionPrefs {
        if (file == null) return TransitionPrefs()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            throw CliktError("cannot read prefs file ${file.path}: ${e.message}")
        }
        return try {
            JSON.decodeFromString(TransitionPrefs.serializer(), text)
        } catch (e: Exception) {
            throw CliktError("cannot parse prefs file ${file.path}: ${e.message}")
        }
    }

    /** Prefs file ← [style] ← `--set-pref` [assignments] ← `--rate` / `--channels`. */
    fun resolve(file: File?, assignments: List<String>, rate: Int?, channels: Int?, style: StyleProfile? = null): TransitionPrefs =
        resolve(load(file), assignments, rate, channels, style)

    /** [loaded] (an already read prefs file) ← [style] ← `--set-pref` [assignments] ← `--rate` / `--channels`. */
    fun resolve(loaded: TransitionPrefs, assignments: List<String>, rate: Int?, channels: Int?, style: StyleProfile? = null): TransitionPrefs {
        var prefs = apply(style?.apply(loaded) ?: loaded, assignments)
        if (rate != null) {
            if (rate < 8000 || rate > 192_000) throw CliktError("--rate $rate is out of range (8000..192000)")
            prefs = prefs.copy(sampleRate = rate)
        }
        if (channels != null) {
            if (channels !in 1..2) throw CliktError("--channels $channels is out of range (1..2)")
            prefs = prefs.copy(channels = channels)
        }
        return prefs
    }

    fun apply(base: TransitionPrefs, assignments: List<String>): TransitionPrefs {
        var p = base
        for (a in assignments) {
            val i = a.indexOf('=')
            if (i <= 0) throw CliktError("--set-pref expects KEY=VALUE, got '$a'")
            val key = a.substring(0, i).trim()
            val value = a.substring(i + 1).trim()
            p = set(p, key, value)
        }
        return p
    }

    private fun set(p: TransitionPrefs, key: String, v: String): TransitionPrefs = when {
        key == "enabled" -> p.copy(enabled = bool(key, v))
        key == "allowInAlbums" -> p.copy(allowInAlbums = bool(key, v))
        key == "keepAlbumFlowInShuffle" -> p.copy(keepAlbumFlowInShuffle = bool(key, v))
        key == "maxStretchPercent" -> p.copy(maxStretchPercent = num(key, v))
        key == "maxPitchShiftSemitones" -> p.copy(maxPitchShiftSemitones = num(key, v))
        key == "keyLock" -> p.copy(keyLock = bool(key, v))
        key == "targetLufs" -> p.copy(targetLufs = if (v.equals("nan", true) || v.equals("off", true)) Double.NaN else num(key, v))
        key == "preferredOverlapBars" -> p.copy(preferredOverlapBars = int(key, v))
        key == "energy" -> p.copy(energy = num(key, v))
        key == "varietyPenalty" -> p.copy(varietyPenalty = num(key, v))
        key == "sampleRate" -> p.copy(sampleRate = int(key, v))
        key == "channels" -> p.copy(channels = int(key, v))
        key == "disabledStrategies" -> p.copy(disabledStrategies = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
        key.startsWith("strategyWeights.") -> {
            val id = key.removePrefix("strategyWeights.")
            p.copy(strategyWeights = p.strategyWeights + (id to num(key, v)))
        }
        key.startsWith("paramOverrides.") -> {
            val rest = key.removePrefix("paramOverrides.").split('.')
            if (rest.size != 2) throw CliktError("paramOverrides key must be paramOverrides.<strategyId>.<paramId>, got '$key'")
            val (sid, pid) = rest
            p.copy(paramOverrides = p.paramOverrides + (sid to (p.paramOverrides[sid].orEmpty() + (pid to v))))
        }
        key.startsWith("activePresets.") -> {
            val sid = key.removePrefix("activePresets.")
            if (sid.isEmpty()) throw CliktError("activePresets key must be activePresets.<strategyId>, got '$key'")
            p.copy(activePresets = if (v.isEmpty() || v == "none") p.activePresets - sid else p.activePresets + (sid to v))
        }
        else -> throw CliktError("unknown preference '$key'. Known keys: ${KEYS.joinToString(", ")}")
    }

    private fun bool(key: String, v: String): Boolean = when (v.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> throw CliktError("preference '$key' expects a boolean, got '$v'")
    }

    private fun num(key: String, v: String): Double = v.toDoubleOrNull() ?: throw CliktError("preference '$key' expects a number, got '$v'")
    private fun int(key: String, v: String): Int = v.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: throw CliktError("preference '$key' expects an integer, got '$v'")
}
