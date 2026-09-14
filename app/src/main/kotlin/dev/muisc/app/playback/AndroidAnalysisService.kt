package dev.muisc.app.playback

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import dev.muisc.analysis.AnalysisCache
import dev.muisc.analysis.AnalysisProgress
import dev.muisc.analysis.AnalysisOptions
import dev.muisc.analysis.DefaultTrackAnalyzer
import dev.muisc.analysis.Fingerprint
import dev.muisc.analysis.TrackAnalyzer
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.data.db.Song
import dev.muisc.audio.AudioSourceId
import dev.muisc.audio.EngineStreamFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import dev.muisc.player.AnalysisService as PlayerAnalysisService

/**
 * The app's [PlayerAnalysisService]: fingerprint → Room cache → (decode at the engine format → [TrackAnalyzer] →
 * cache). Decoding goes through the same [EngineStreamFactory] the player uses, so the analysis frame timeline is
 * exactly the timeline the player and the renderer see (decoder-relative, gapless-trimmed, engine rate).
 *
 * Threads (DESIGN §7.2): urgent work runs on one dedicated thread at `THREAD_PRIORITY_DEFAULT` (the coordinator
 * awaits it rather than running it inline, so a long analysis cannot stall the coordinator's event handling);
 * background pre-fetch runs on a second thread at `THREAD_PRIORITY_BACKGROUND`. One analysis per source at a time:
 * concurrent callers share the same [Deferred].
 *
 * Wake lock: an urgent analysis holds a `PARTIAL_WAKE_LOCK` for at most [WAKE_LOCK_MS] (released in `finally`),
 * so the work that a transition depends on finishes even if the screen goes off mid-track.
 *
 * **Instant start.** The very first track of a fresh queue may not be analysed yet, and a full analysis takes
 * seconds. [expectImmediate] marks that one source: its urgent analysis waits [placeholderTimeoutMs] and then
 * returns the supplied placeholder (empty grid) so audio starts now; the real analysis keeps running and lands in
 * the cache for the next time. Every other track waits for the real thing — they have minutes.
 */
class AndroidAnalysisService(
    context: Context,
    private val cache: AnalysisCache,
    private val streams: EngineStreamFactory,
    val sampleRate: Int,
    val channels: Int,
    private val analyzer: TrackAnalyzer = DefaultTrackAnalyzer(AnalysisOptions(maxSeconds = MAX_ANALYSIS_SEC)),
    val decoderId: String = defaultDecoderId(),
    private val placeholderTimeoutMs: Long = PLACEHOLDER_TIMEOUT_MS,
) : PlayerAnalysisService {

    private val appContext: Context = context.applicationContext

    private val urgentDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> priorityThread(r, "muisc-analysis", Process.THREAD_PRIORITY_DEFAULT) }.asCoroutineDispatcher()

    private val backgroundDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> priorityThread(r, "muisc-analysis-bg", Process.THREAD_PRIORITY_BACKGROUND) }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob())

    private val inFlightLock = Any()
    private val inFlight = HashMap<String, Deferred<TrackAnalysis>>()

    /** The one source the listener is waiting to hear, with the stand-in to use until its analysis lands. */
    @Volatile
    private var immediate: Pair<String, TrackAnalysis>? = null

    private val powerManager: PowerManager? = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    // ---------------------------------------------------------------------------------- host API

    /** Marks [source] as "the user is waiting for this one"; [placeholder] is used if the analysis is not quick. */
    fun expectImmediate(source: AudioSourceId, placeholder: TrackAnalysis) {
        immediate = source.value to placeholder
    }

    fun clearImmediate() {
        immediate = null
    }

    /** Cached analysis without decoding anything (reads the file's head and tail for the fingerprint). */
    fun cached(source: AudioSourceId): TrackAnalysis? = try {
        cache.get(fingerprintOf(source), sampleRate, TrackAnalysis.CURRENT_VERSION)
    } catch (e: Exception) {
        null
    }

    fun cached(song: Song): TrackAnalysis? = cached(QueueManager.sourceOf(song))

    override suspend fun analysis(track: AudioSourceId, urgent: Boolean): TrackAnalysis {
        cached(track)?.let { return it }
        val pending = immediate
        if (urgent && pending != null && pending.first == track.value) {
            val deferred = shared(track, urgent = true)
            return withTimeoutOrNull(placeholderTimeoutMs) { deferred.await() } ?: pending.second
        }
        return shared(track, urgent).await()
    }

    /** Blocking analysis for the Lab and the batch worker; never call from the main thread. */
    fun analysisOf(
        source: AudioSourceId,
        urgent: Boolean = false,
        progress: AnalysisProgress = AnalysisProgress.NONE,
        force: Boolean = false,
    ): TrackAnalysis {
        val fingerprint = fingerprintOf(source)
        if (!force) {
            try {
                cache.get(fingerprint, sampleRate, TrackAnalysis.CURRENT_VERSION)?.let {
                    progress.onProgress(STAGE_CACHED, 1.0)
                    return it
                }
            } catch (e: Exception) {
                // Unreadable cache row: analyse again.
            }
        }
        val lock = if (urgent) acquireWakeLock() else null
        try {
            progress.onProgress(STAGE_DECODE, 0.0)
            val analysis = streams.open(source, sampleRate, channels).use { stream ->
                analyzer.analyze(stream, source.value, fingerprint, progress)
            }
            try {
                cache.put(analysis)
            } catch (e: Exception) {
                // The analysis is still usable for this session even if it could not be stored.
            }
            return analysis
        } finally {
            if (lock != null && lock.isHeld) {
                try {
                    lock.release()
                } catch (e: RuntimeException) {
                    // "WakeLock under-locked" can only happen if it timed out first; nothing to do.
                }
            }
        }
    }

    fun analysisOf(song: Song, urgent: Boolean = false, progress: AnalysisProgress = AnalysisProgress.NONE): TrackAnalysis =
        analysisOf(QueueManager.sourceOf(song), urgent, progress)

    /** Full cache key of [source]: content fingerprint plus decoder identity (DESIGN §3.3). */
    fun fingerprintOf(source: AudioSourceId): String = contentFingerprint(source) + ":" + decoderId

    fun release() {
        scope.cancel()
        synchronized(inFlightLock) { inFlight.clear() }
        urgentDispatcher.close()
        backgroundDispatcher.close()
    }

    // ---------------------------------------------------------------------------------- internals

    /** One running analysis per source; every caller awaits the same [Deferred]. */
    private fun shared(source: AudioSourceId, urgent: Boolean): Deferred<TrackAnalysis> {
        val key = source.value
        synchronized(inFlightLock) {
            val running = inFlight[key]
            if (running != null && running.isActive) return running
            val dispatcher = if (urgent) urgentDispatcher else backgroundDispatcher
            val deferred = scope.async(dispatcher) { analysisOf(source, urgent) }
            inFlight[key] = deferred
            deferred.invokeOnCompletion {
                synchronized(inFlightLock) { if (inFlight[key] === deferred) inFlight.remove(key) }
            }
            return deferred
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS)
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun contentFingerprint(source: AudioSourceId): String {
        val value = source.value
        if (!value.startsWith("content://")) {
            val file = File(value)
            if (file.isFile) return Fingerprint.ofFile(file)
            return fallbackFingerprint(value, null)
        }
        val uri = Uri.parse(value)
        val (size, modifiedMs) = sizeAndModified(uri)
        if (size <= 0L) return fallbackFingerprint(value, uri)
        return try {
            Fingerprint.ofSource(size, modifiedMs) { openStream(uri) }
        } catch (e: IOException) {
            fallbackFingerprint(value, uri)
        }
    }

    /** Size (bytes) and modification time (epoch ms) of a MediaStore item; (-1, 0) when unknown. */
    private fun sizeAndModified(uri: Uri): Pair<Long, Long> {
        try {
            appContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val size = if (cursor.isNull(0)) -1L else cursor.getLong(0)
                    val modifiedSec = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                    if (size > 0L) return size to modifiedSec * 1000L
                }
            }
        } catch (e: Exception) {
            // Unreadable projection (a non-MediaStore provider): fall through to the descriptor.
        }
        val length = try {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }
        return (if (length > 0L) length else -1L) to 0L
    }

    /**
     * When the size is unknown the head/tail recipe cannot be used: hash whatever the first megabyte holds plus the
     * source string, which still separates different files and still changes when the content changes.
     */
    private fun fallbackFingerprint(value: String, uri: Uri?): String {
        val head = try {
            if (uri != null) openStream(uri).use { readUpTo(it, Fingerprint.HEAD_BYTES.toInt()) } else ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }
        val hashed = Fingerprint.ofBytes(head, head.size.toLong(), 0L)
        return hashed + "-" + Integer.toHexString(value.hashCode())
    }

    private fun openStream(uri: Uri): InputStream =
        appContext.contentResolver.openInputStream(uri)?.buffered(1 shl 16) ?: throw IOException("cannot open $uri")

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val buffer = ByteArray(1 shl 16)
        val out = java.io.ByteArrayOutputStream(minOf(max, 1 shl 20))
        var total = 0
        while (total < max) {
            val n = input.read(buffer, 0, minOf(buffer.size, max - total))
            if (n <= 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    companion object {
        const val STAGE_DECODE = "decode"
        const val STAGE_CACHED = "cached"
        const val WAKE_LOCK_TAG = "muisc:analysis"

        /** DESIGN §7.5: a render/analysis that must finish holds the wake lock for at most 15 s. */
        const val WAKE_LOCK_MS = 15_000L

        /** Longest a fresh queue waits for the current track's analysis before starting on a placeholder. */
        const val PLACEHOLDER_TIMEOUT_MS = 1_200L

        /** Analysis reads the whole track into memory; cap what a pathological file can cost (DJ sets, podcasts). */
        const val MAX_ANALYSIS_SEC = 900.0

        /**
         * Decoder identity in the cache key. The concrete MediaCodec component is only known once a file is opened,
         * so the platform build is used instead: a system update that swaps decoders invalidates the caches, which
         * is exactly the guarantee the key needs (frame positions are decoder-relative).
         */
        fun defaultDecoderId(): String = "mediacodec-" + Build.VERSION.SDK_INT

        private fun priorityThread(runnable: Runnable, name: String, priority: Int): Thread =
            Thread({
                Process.setThreadPriority(priority)
                runnable.run()
            }, name)
    }
}
