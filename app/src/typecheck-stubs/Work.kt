@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.work

import android.app.Notification
import android.content.Context
import java.util.concurrent.TimeUnit

open class Data {
    companion object {
        val EMPTY = Data()
    }
}

fun workDataOf(vararg pairs: Pair<String, Any?>): Data = Data()

open class WorkerParameters

open class ForegroundInfo(notificationId: Int, notification: Notification)

open class ListenableWorker(val appContext: Context, val params: WorkerParameters) {
    val applicationContext: Context get() = appContext
    val isStopped: Boolean get() = false

    open class Result {
        companion object {
            fun success(): Result = Result()
            fun success(outputData: Data): Result = Result()
            fun failure(): Result = Result()
            fun failure(outputData: Data): Result = Result()
            fun retry(): Result = Result()
        }
    }
}

abstract class CoroutineWorker(appContext: Context, params: WorkerParameters) : ListenableWorker(appContext, params) {
    abstract suspend fun doWork(): Result
    open suspend fun getForegroundInfo(): ForegroundInfo = throw IllegalStateException("not implemented")
    suspend fun setProgress(data: Data) {}
    suspend fun setForeground(info: ForegroundInfo) {}
}

enum class BackoffPolicy { EXPONENTIAL, LINEAR }
enum class ExistingWorkPolicy { REPLACE, KEEP, APPEND, APPEND_OR_REPLACE }
enum class ExistingPeriodicWorkPolicy { UPDATE, KEEP, CANCEL_AND_REENQUEUE }
enum class OutOfQuotaPolicy { RUN_AS_NON_EXPEDITED_WORK_REQUEST, DROP_WORK_REQUEST }
enum class NetworkType { NOT_REQUIRED, CONNECTED, UNMETERED }

class Constraints private constructor() {
    class Builder {
        fun setRequiresCharging(value: Boolean): Builder = this
        fun setRequiresBatteryNotLow(value: Boolean): Builder = this
        fun setRequiresDeviceIdle(value: Boolean): Builder = this
        fun setRequiredNetworkType(type: NetworkType): Builder = this
        fun build(): Constraints = Constraints()
    }

    companion object {
        val NONE = Constraints()
    }
}

abstract class WorkRequest {
    companion object {
        const val MIN_BACKOFF_MILLIS = 10_000L
        const val DEFAULT_BACKOFF_DELAY_MILLIS = 30_000L
    }
}

class OneTimeWorkRequest : WorkRequest() {
    class Builder(workerClass: Class<out ListenableWorker>) {
        fun setConstraints(constraints: Constraints): Builder = this
        fun setBackoffCriteria(policy: BackoffPolicy, backoffDelay: Long, timeUnit: TimeUnit): Builder = this
        fun setExpedited(policy: OutOfQuotaPolicy): Builder = this
        fun setInitialDelay(duration: Long, timeUnit: TimeUnit): Builder = this
        fun build(): OneTimeWorkRequest = OneTimeWorkRequest()
    }
}

class PeriodicWorkRequest : WorkRequest() {
    class Builder(workerClass: Class<out ListenableWorker>, repeatInterval: Long, repeatIntervalTimeUnit: TimeUnit) {
        fun setConstraints(constraints: Constraints): Builder = this
        fun setBackoffCriteria(policy: BackoffPolicy, backoffDelay: Long, timeUnit: TimeUnit): Builder = this
        fun setInitialDelay(duration: Long, timeUnit: TimeUnit): Builder = this
        fun build(): PeriodicWorkRequest = PeriodicWorkRequest()
    }
}

inline fun <reified W : ListenableWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequest.Builder =
    OneTimeWorkRequest.Builder(W::class.java)

inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(
    repeatInterval: Long,
    repeatIntervalTimeUnit: TimeUnit,
): PeriodicWorkRequest.Builder = PeriodicWorkRequest.Builder(W::class.java, repeatInterval, repeatIntervalTimeUnit)

open class WorkManager {
    open fun enqueueUniquePeriodicWork(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest): Any? = null
    open fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest): Any? = null
    open fun cancelUniqueWork(name: String): Any? = null

    companion object {
        @JvmStatic
        fun getInstance(context: Context): WorkManager = WorkManager()
    }
}
