package dev.muisc.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.muisc.app.data.db.Song
import dev.muisc.app.di.AppGraph
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Library pre-analysis (DESIGN §7.5): walks the songs that have no [dev.muisc.analysis.model.TrackAnalysis] yet and
 * analyses them **one at a time** at `THREAD_PRIORITY_BACKGROUND`, checkpointing after every track (the analysis
 * cache row *is* the checkpoint — a run that is stopped half-way simply resumes at the next un-analysed song).
 *
 * Constraints follow the "analyse only while charging" setting: charging when it is on, `batteryNotLow` when it is
 * off. A run stops after [MAX_TRACKS_PER_RUN] tracks or [MAX_RUN_MILLIS] so the scheduler stays in control, and
 * reports progress as `done` / `total` work data for the Settings screen.
 *
 * Analysing a track means decoding it in full (the analyser is not yet streaming), so a failure — a corrupt file,
 * an unsupported codec, or an `OutOfMemoryError` on a very long file — is caught per track: that song is skipped
 * for the rest of the process lifetime and the run continues with the next one.
 */
class AnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val context = applicationContext
        val songDao = try {
            AppGraph.db.songDao()
        } catch (t: Throwable) {
            return@withContext Result.failure()
        }
        val sampleRate = EngineGraph.engineSampleRate(context)
        val service = AndroidAnalysisService(
            context = context,
            cache = AppGraph.analysisCache,
            streams = AndroidEngineStreamFactory(context),
            sampleRate = sampleRate,
            channels = EngineGraph.CHANNELS,
        )
        val startedAt = System.currentTimeMillis()
        val processed = HashSet<Long>()
        var done = 0
        var failures = 0
        try {
            loop@ while (done + failures < MAX_TRACKS_PER_RUN) {
                ensureActive()
                if (isStopped) break
                if (System.currentTimeMillis() - startedAt > MAX_RUN_MILLIS) break
                // Songs already handled in this run are filtered out as well: a song whose "analysed" flag does not
                // flip (its source id matches neither uri nor path) must not make this loop spin.
                val batch = songDao.unanalysed(BATCH_SIZE).filter { it.id !in processed && !isSkipped(it.id) }
                if (batch.isEmpty()) break
                for (song in batch) {
                    ensureActive()
                    if (isStopped || done + failures >= MAX_TRACKS_PER_RUN) break@loop
                    if (System.currentTimeMillis() - startedAt > MAX_RUN_MILLIS) break@loop
                    processed += song.id
                    if (analyse(service, song)) done++ else failures++
                    setProgress(workDataOf(KEY_DONE to done, KEY_FAILED to failures, KEY_PENDING to batch.size))
                }
            }
        } finally {
            service.release()
        }
        if (done == 0 && failures > 0) Result.retry() else Result.success(workDataOf(KEY_DONE to done, KEY_FAILED to failures))
    }

    /** One track; true when an analysis was produced (or already existed). */
    private fun analyse(service: AndroidAnalysisService, song: Song): Boolean = try {
        service.analysisOf(QueueManager.sourceOf(song), urgent = false)
        true
    } catch (error: OutOfMemoryError) {
        skip(song.id)
        false
    } catch (t: Throwable) {
        skip(song.id)
        false
    }

    /** Only used on API < 31, where WorkManager runs expedited work in a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val UNIQUE_PERIODIC = "muisc-analysis-periodic"
        const val UNIQUE_NOW = "muisc-analysis-now"

        const val KEY_DONE = "done"
        const val KEY_FAILED = "failed"
        const val KEY_PENDING = "pending"

        /** Songs read per query; the DAO returns the newest un-analysed first. */
        const val BATCH_SIZE = 20
        const val MAX_TRACKS_PER_RUN = 60
        val MAX_RUN_MILLIS = TimeUnit.MINUTES.toMillis(20)

        const val CHANNEL_ID = "muisc_analysis"
        const val CHANNEL_NAME = "Library analysis"
        const val NOTIFICATION_ID = 4711
        const val NOTIFICATION_TITLE = "Analysing your library"

        /** Songs whose analysis threw; retried after the next process start, not in a loop. */
        private val skipped = HashSet<Long>()

        private fun skip(id: Long) {
            synchronized(skipped) { skipped += id }
        }

        private fun isSkipped(id: Long): Boolean = synchronized(skipped) { id in skipped }

        fun constraints(onlyWhileCharging: Boolean): Constraints = Constraints.Builder()
            .setRequiresCharging(onlyWhileCharging)
            .setRequiresBatteryNotLow(!onlyWhileCharging)
            .build()

        /** The standing job: every [PERIOD_HOURS] hours under the constraints the setting asks for. */
        fun enqueuePeriodic(context: Context, onlyWhileCharging: Boolean) {
            val request = PeriodicWorkRequestBuilder<AnalysisWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints(onlyWhileCharging))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            try {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
            } catch (t: Throwable) {
                // WorkManager not initialised (unit tests): pre-analysis is an optimisation, never a requirement.
            }
        }

        /**
         * "The library changed, analyse the new files now."
         *
         * [expedited] asks for an expedited job: on API 31+ that is a JobScheduler expedited job (no notification);
         * below it WorkManager needs a foreground service and calls [getForegroundInfo]. Expedited work may not
         * carry charging / battery constraints, so it is off by default — turn it on only if the manifest declares
         * a `dataSync` foreground-service type.
         */
        fun enqueueNow(context: Context, onlyWhileCharging: Boolean, expedited: Boolean = false) {
            val builder = OneTimeWorkRequestBuilder<AnalysisWorker>()
            if (expedited) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            } else {
                builder.setConstraints(constraints(onlyWhileCharging))
            }
            builder.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, builder.build())
            } catch (t: Throwable) {
                // As above: best effort.
            }
        }

        fun cancel(context: Context) {
            try {
                val manager = WorkManager.getInstance(context)
                manager.cancelUniqueWork(UNIQUE_PERIODIC)
                manager.cancelUniqueWork(UNIQUE_NOW)
            } catch (t: Throwable) {
                // Nothing to cancel.
            }
        }

        const val PERIOD_HOURS = 6L
    }
}
