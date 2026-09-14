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
import dev.muisc.transitions.DefaultTransitionRenderer
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.planner.DefaultTransitionPlanner
import dev.muisc.transitions.synthetic.JvmTrackAudioLoader
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everything a command needs from the engine, wired once: the JVM decoder, the on-disk analysis cache, the
 * [AnalysisService] in front of it, the shipped strategy registry, the planner, the audio loader and the renderer.
 *
 * Built by [MuiscCommand] from the options every command shares (`--cache-dir`, `--prefs`, `--set-pref`,
 * `--rate`, `--channels`), so `muisc render --set-pref energy=0.9` and `muisc ab --set-pref energy=0.9` mean
 * exactly the same thing.
 */
class CliContext(val prefs: TransitionPrefs, val cacheDir: File) : AutoCloseable {

    val decoder: AudioDecoder = JavaSoundDecoder()
    val cache: FileAnalysisCache = FileAnalysisCache(cacheDir)
    val analysisService: AnalysisService = AnalysisService(
        analyzer = DefaultTrackAnalyzer(),
        cache = cache,
        decoder = decoder,
        engineSampleRate = prefs.sampleRate,
        channels = prefs.channels,
    )
    val registry: DefaultStrategyRegistry = DefaultStrategyRegistry.default()
    val pairAnalyzer: DefaultPairAnalyzer = DefaultPairAnalyzer()
    val planner: DefaultTransitionPlanner = DefaultTransitionPlanner(registry, pairAnalyzer)
    val loader: JvmTrackAudioLoader = JvmTrackAudioLoader(decoder)
    val separator: PseudoStemSeparator = PseudoStemSeparator()
    val renderer: DefaultTransitionRenderer = DefaultTransitionRenderer(loader, registry, separator)
    val streams: JvmEngineStreamFactory = JvmEngineStreamFactory(decoder)

    val sampleRate: Int get() = prefs.sampleRate
    val channels: Int get() = prefs.channels

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

    /** Seed for the deterministic jitter in the planner and for every render. */
    val seed by option("--seed", metavar = "N", help = "Deterministic seed (default 0).").long().default(0L)

    private var started = 0L

    final override fun run() {
        started = System.nanoTime()
        val prefs = PrefsIo.resolve(prefsFile, prefAssignments, rateOpt, channelsOpt)
        CliContext(prefs, CliContext.defaultCacheDir(cacheDirOpt)).use { ctx ->
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
        "disabledStrategies", "strategyWeights.<id>", "paramOverrides.<strategyId>.<paramId>",
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

    fun resolve(file: File?, assignments: List<String>, rate: Int?, channels: Int?): TransitionPrefs {
        var prefs = apply(load(file), assignments)
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
