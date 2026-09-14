package dev.muisc.app.playback

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import dev.muisc.analysis.AnalysisProgress
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.PairOverride
import dev.muisc.app.data.db.Song
import dev.muisc.app.data.db.TransitionDao
import dev.muisc.app.data.db.TransitionLog
import dev.muisc.app.data.prefs.SettingsRepository
import dev.muisc.audio.EngineStreamFactory
import dev.muisc.audio.WavIo
import dev.muisc.metrics.ArtifactMetrics
import dev.muisc.player.AudioSink
import dev.muisc.player.EngineCommand
import dev.muisc.player.EngineLimits
import dev.muisc.player.ProgramPlayer
import dev.muisc.player.SinkPump
import dev.muisc.transitions.Applicability
import dev.muisc.transitions.DefaultPairAnalyzer
import dev.muisc.transitions.PairAnalyzer
import dev.muisc.transitions.Params
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.PlaybackProgram
import dev.muisc.transitions.RankedPlans
import dev.muisc.transitions.RenderContext
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.Segment
import dev.muisc.transitions.StrategyRegistry
import dev.muisc.transitions.TrackRef
import dev.muisc.transitions.TransitionModifier
import dev.muisc.transitions.TransitionPlan
import dev.muisc.transitions.TransitionPlanner
import dev.muisc.transitions.TransitionPrefs
import dev.muisc.transitions.TransitionRenderer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Transition Lab over the *same* planner, renderer and loader the playback engine uses — that is the whole
 * point of the Lab: what you hear here is what the coordinator would install, and the exported `renderKey` is
 * reproducible on the CLI.
 *
 *  - [analysis] / [plan] / [render] run on [Dispatchers.Default] through [AndroidAnalysisService] (Room cache) and
 *    the shared [TransitionRenderer].
 *  - [audition] builds a small [PlaybackProgram] of A's tail + the rendered segment + B's head and plays it through
 *    a **second** [ProgramPlayer] on the same sink policy, after pausing the main playback.
 *  - [export] writes `<name>.wav`, `<name>.plan.json` and `<name>.report.json` to the public Music/Muisc/Renders
 *    folder (MediaStore on API 29+, the classic path below it, app-private storage if both are refused).
 *  - [rate] nudges `TransitionPrefs.strategyWeights` by ±0.1 and logs the vote; [pinForPair] writes a
 *    `PairOverride`.
 */
class TransitionLabImpl(
    context: Context,
    /** Main playback, paused before an audition starts. */
    private val controller: EngineController?,
    private val analyses: AndroidAnalysisService,
    private val registry: StrategyRegistry,
    private val planner: TransitionPlanner,
    private val renderer: TransitionRenderer,
    private val streams: EngineStreamFactory,
    private val sampleRate: Int,
    private val channels: Int,
    private val limits: EngineLimits,
    private val settings: SettingsRepository,
    private val transitionDao: TransitionDao,
    private val sinkFactory: (Int, Int) -> AudioSink,
    private val prefsProvider: () -> TransitionPrefs,
    private val pairAnalyzer: PairAnalyzer = DefaultPairAnalyzer(),
) : TransitionLabApi {

    private val appContext: Context = context.applicationContext
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    @Volatile
    private var audition: AuditionHandle? = null

    // ================================================================================================ analysis

    override suspend fun analysis(song: Song, progress: ((LabProgress) -> Unit)?): TrackAnalysis =
        withContext(Dispatchers.Default) {
            analyses.analysisOf(
                QueueManager.sourceOf(song),
                urgent = true,
                progress = AnalysisProgress { stage, fraction -> progress?.invoke(LabProgress(stage, fraction.toFloat())) },
            )
        }

    override suspend fun plan(a: Song, b: Song, prefs: TransitionPrefs, seed: Long): RankedPlans =
        withContext(Dispatchers.Default) {
            val engine = enginePrefs(prefs)
            val aRef = trackRef(a)
            val bRef = trackRef(b)
            planner.plan(aRef, bRef, engine, seed, null)
        }

    override suspend fun render(
        a: Song,
        b: Song,
        strategyId: String,
        params: Params,
        modifiers: List<String>,
        prefs: TransitionPrefs,
        seed: Long,
        progress: ((LabProgress) -> Unit)?,
    ): RenderedTransition = withContext(Dispatchers.Default) {
        val engine = enginePrefs(prefs)
        val strategy = registry.strategy(strategyId)
            ?: throw IllegalArgumentException("unknown strategy '$strategyId'")
        progress?.invoke(LabProgress(STAGE_ANALYSE, 0f))
        val aRef = trackRef(a)
        val bRef = trackRef(b)
        progress?.invoke(LabProgress(STAGE_PLAN, 0f))
        val features = pairAnalyzer.features(aRef.analysis, bRef.analysis, engine)
        val resolved = params.resolve(strategy.params)
        var plan: TransitionPlan = strategy.plan(aRef.analysis, bRef.analysis, features, resolved, engine, seed)
        val mods: List<TransitionModifier> = modifiers.mapNotNull { registry.modifier(it) }
        for (modifier in mods) {
            val modifierParams = Params.defaults(modifier.params).withAll(engine.paramOverrides[modifier.id] ?: emptyMap())
            plan = modifier.adjustPlan(plan, aRef.analysis, bRef.analysis, features, modifierParams, engine)
        }
        if (mods.isNotEmpty() && plan.modifiers.isEmpty()) plan = plan.copy(modifiers = mods.map { it.id })
        val candidate = PlanCandidate(strategy, Applicability.of(1.0, "lab"), 1.0, plan, mods)
        val ctx = RenderContext(engine, seed) { fraction ->
            progress?.invoke(LabProgress(STAGE_RENDER, fraction.coerceIn(0.0, 1.0).toFloat()))
        }
        renderer.render(aRef, bRef, candidate, features, ctx)
    }

    // ================================================================================================ audition

    override fun audition(a: Song, b: Song, rendered: RenderedTransition, contextSec: Double): Flow<Float> = flow {
        stopAudition()
        controller?.pause()
        val engine = enginePrefs(prefsProvider())
        val aRef = trackRef(a)
        val bRef = trackRef(b)
        val program = auditionProgram(aRef, bRef, rendered, contextSec)
        val total = max(1L, program.totalFrames)
        val player = ProgramPlayer(sampleRate, channels, limits, streams, engine, realtime = true)
        val sink = sinkFactory(sampleRate, channels)
        val pump = SinkPump(player, sink)
        player.submit(EngineCommand.SetProgram(program))
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                pump.run()
            } catch (t: Throwable) {
                // An audition that fails just stops; the Lab shows the last position.
            } finally {
                try {
                    sink.close()
                } catch (t: Throwable) {
                    // nothing to do
                }
                try {
                    player.close()
                } catch (t: Throwable) {
                    // nothing to do
                }
            }
        }, "muisc-audition")
        val handle = AuditionHandle(pump, thread)
        audition = handle
        thread.start()
        try {
            emit(0f)
            while (thread.isAlive) {
                emit((pump.framesWritten.toDouble() / total).coerceIn(0.0, 1.0).toFloat())
                delay(AUDITION_TICK_MS)
            }
            emit(1f)
        } finally {
            handle.stop()
            if (audition === handle) audition = null
        }
    }.flowOn(Dispatchers.Default)

    override fun stopAudition() {
        val handle = audition
        audition = null
        handle?.stop()
    }

    /** A's tail (contextSec) + the rendered segment + B's head, exactly the splice the player would produce. */
    private fun auditionProgram(a: TrackRef, b: TrackRef, rendered: RenderedTransition, contextSec: Double): PlaybackProgram {
        val contextFrames = max(0L, Math.round(contextSec * sampleRate))
        val plan = rendered.plan
        val aFrom = max(0L, plan.aExitFrame - contextFrames)
        val aTo = max(aFrom, plan.aExitFrame)
        val bTotal = scaleToEngine(b.analysis.totalFrames, b.analysis.sampleRate)
        val bFrom = plan.bEntryFrame.coerceAtLeast(0L)
        val bTo = if (bTotal > 0L) min(bTotal, bFrom + contextFrames) else bFrom + contextFrames
        return PlaybackProgram(
            listOf(
                Segment.Body(a, aFrom, aTo),
                Segment.Rendered(a, b, rendered),
                Segment.Body(b, bFrom, max(bFrom, bTo)),
            ),
        )
    }

    // ================================================================================================ export

    override suspend fun export(a: Song, b: Song, rendered: RenderedTransition): String = withContext(Dispatchers.IO) {
        val base = exportBaseName(a, b, rendered)
        val metrics = try {
            ArtifactMetrics.evaluate(rendered, null)
        } catch (t: Throwable) {
            null
        }
        val planJson = json.encodeToString(TransitionPlan.serializer(), rendered.plan)
        val report = rendered.report
        val reportJson = buildJsonObject {
            put("strategyId", rendered.plan.strategyId)
            put("modifiers", JsonArray(rendered.plan.modifiers.map { JsonPrimitive(it) }))
            put("renderKey", report.renderKey)
            put("renderMillis", report.renderMillis)
            put("peak", report.peak)
            put("truePeakDbtp", report.truePeakDbtp)
            put("integratedLufs", report.integratedLufs)
            put("outputFrames", rendered.audio.frames)
            put("sampleRate", rendered.audio.sampleRate)
            put("warnings", JsonArray(report.warnings.map { JsonPrimitive(it) }))
            put("markers", JsonArray(rendered.markers.map { JsonPrimitive("${it.frame}:${it.label}") }))
            put("metrics", metrics?.let { json.parseToJsonElement(it.toJson(true)) } ?: JsonNull)
        }.toString()

        val wavPath = write("$base.wav", MIME_WAV, audio = true) { out -> WavIo.write(out, rendered.audio, WavIo.Encoding.PCM24) }
        write("$base.plan.json", MIME_JSON, audio = false) { out -> out.write(planJson.toByteArray(Charsets.UTF_8)) }
        write("$base.report.json", MIME_JSON, audio = false) { out -> out.write(reportJson.toByteArray(Charsets.UTF_8)) }
        wavPath
    }

    private fun exportBaseName(a: Song, b: Song, rendered: RenderedTransition): String {
        val key = rendered.report.renderKey.take(8)
        val name = sanitize(a.title) + "-" + sanitize(b.title) + "-" + rendered.plan.strategyId +
            if (key.isNotEmpty()) "-$key" else ""
        return name.take(96)
    }

    private fun sanitize(text: String): String {
        val cleaned = text.trim().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return cleaned.trim('_').ifEmpty { "track" }
    }

    /** Writes one file to the public renders folder; returns the path it ended up at. */
    private fun write(displayName: String, mime: String, audio: Boolean, body: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return writeViaMediaStore(displayName, mime, audio, body)
            } catch (t: Throwable) {
                // Falls through to the app-private folder below.
            }
        } else {
            try {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_SUBDIR)
                return writeToDir(dir, displayName, body)
            } catch (t: Throwable) {
                // Missing WRITE_EXTERNAL_STORAGE on API < 29: fall through.
            }
        }
        val fallbackDir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC), PUBLIC_SUBDIR)
        return writeToDir(fallbackDir, displayName, body)
    }

    private fun writeViaMediaStore(displayName: String, mime: String, audio: Boolean, body: (OutputStream) -> Unit): String {
        val resolver = appContext.contentResolver
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (audio) RELATIVE_MUSIC else RELATIVE_DOWNLOADS
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $displayName")
        try {
            resolver.openOutputStream(uri)?.use { out -> body(out) } ?: throw IOException("cannot open $uri")
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (e: Exception) {
                // Best effort cleanup of the pending row.
            }
            throw t
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return relativePath + displayName
    }

    private fun writeToDir(dir: File, displayName: String, body: (OutputStream) -> Unit): String {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val file = File(dir, displayName)
        FileOutputStream(file).use { out -> body(out) }
        return file.absolutePath
    }

    // ================================================================================================ feedback

    override suspend fun rate(a: Song, b: Song, strategyId: String, thumbsUp: Boolean) {
        val delta = if (thumbsUp) WEIGHT_STEP else -WEIGHT_STEP
        settings.updateTransition { current ->
            val weight = (current.strategyWeights[strategyId] ?: 1.0) + delta
            current.copy(strategyWeights = current.strategyWeights + (strategyId to weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT)))
        }
        try {
            transitionDao.insertLog(
                TransitionLog(
                    aSongId = a.id,
                    bSongId = b.id,
                    strategyId = strategyId,
                    paramsJson = EMPTY_PARAMS_JSON,
                    score = 0.0,
                    outcome = if (thumbsUp) "thumbs-up" else "thumbs-down",
                    createdAt = System.currentTimeMillis(),
                ),
            )
        } catch (t: Throwable) {
            // The weight nudge is the part that matters; the log is diagnostics.
        }
    }

    override suspend fun pinForPair(a: Song, b: Song, strategyId: String?, params: Params?) {
        if (strategyId == null) {
            transitionDao.deleteOverride(a.id, b.id)
            return
        }
        val paramsJson = json.encodeToString(Params.serializer(), params ?: Params.EMPTY)
        transitionDao.upsertOverride(PairOverride(aSongId = a.id, bSongId = b.id, strategyId = strategyId, paramsJson = paramsJson))
    }

    // ================================================================================================ helpers

    /** Prefs as the engine must see them on this device (the stored rate may come from another device or the CLI). */
    private fun enginePrefs(prefs: TransitionPrefs): TransitionPrefs =
        if (prefs.sampleRate == sampleRate && prefs.channels == channels) prefs
        else prefs.copy(sampleRate = sampleRate, channels = channels)

    /** Analysed (cache first) and wrapped as the engine's [TrackRef]; blocking, always called off the main thread. */
    private fun trackRef(song: Song): TrackRef =
        QueueManager.trackRef(song, analyses.analysisOf(QueueManager.sourceOf(song), urgent = true))

    private fun scaleToEngine(frame: Long, analysisRate: Int): Long =
        if (analysisRate <= 0 || analysisRate == sampleRate) frame else Math.round(frame * (sampleRate.toDouble() / analysisRate))

    /** One running audition; stopping it ends the pump thread, which closes the sink and the player. */
    private class AuditionHandle(private val pump: SinkPump, private val thread: Thread) {
        @Volatile private var stopped = false

        fun stop() {
            if (stopped) return
            stopped = true
            pump.stop()
            try {
                thread.join(STOP_JOIN_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private companion object { const val STOP_JOIN_MS = 500L }
    }

    companion object {
        const val STAGE_ANALYSE = "analyse"
        const val STAGE_PLAN = "plan"
        const val STAGE_RENDER = "render"

        const val WEIGHT_STEP = 0.1
        const val MIN_WEIGHT = 0.1
        const val MAX_WEIGHT = 2.0

        const val AUDITION_TICK_MS = 100L

        const val MIME_WAV = "audio/wav"
        const val MIME_JSON = "application/json"

        /** DESIGN §8: exports land in Music/Muisc/Renders; the JSON side-cars go to Download/Muisc/Renders. */
        const val RELATIVE_MUSIC = "Music/Muisc/Renders/"
        const val RELATIVE_DOWNLOADS = "Download/Muisc/Renders/"
        const val PUBLIC_SUBDIR = "Muisc/Renders"

        private const val EMPTY_PARAMS_JSON = "{\"values\":{}}"
    }
}
