package dev.muisc.cli.lab

import com.github.ajalt.clikt.core.CliktError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Slow work (analysis, renders, blind tests, sweeps) runs here as jobs the page polls at `/api/jobs/{id}`.
 *
 * One worker thread: the audio loader, the stem separator and the analysis cache are shared by every render, and one
 * render at a time keeps a second click from halving the speed of the first. Jobs queue in submission order.
 * The newest [KEEP] jobs are remembered; older finished ones are forgotten (their files stay until the Lab stops).
 */
class LabJobs(threads: Int = 1) : AutoCloseable {
    enum class Status { QUEUED, RUNNING, DONE, FAILED }

    class Job(val id: String, val kind: String) {
        @Volatile var status: Status = Status.QUEUED
        @Volatile var progress: Double = 0.0
        @Volatile var message: String = "queued"
        @Volatile var result: JsonElement? = null
        @Volatile var error: String? = null

        /** Progress in 0..1 plus a short stage text for the page's progress bar. */
        fun report(progress: Double, message: String? = null) {
            this.progress = progress.coerceIn(0.0, 1.0)
            if (message != null) this.message = message
        }

        fun toJson(): JsonObject = buildJsonObject {
            put("id", id)
            put("kind", kind)
            put("status", status.name.lowercase())
            put("progress", LabJson.num(progress, 3))
            put("message", message)
            result?.let { put("result", it) }
            error?.let { put("error", it) }
        }
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "muisc-lab-worker").apply { isDaemon = true }
    }
    private val jobs = LinkedHashMap<String, Job>()
    private val counter = AtomicLong()

    /** Queues [work]; its return value becomes the job's result, a thrown exception its error text. */
    fun submit(kind: String, work: (Job) -> JsonElement): Job {
        val job = Job("j${counter.incrementAndGet()}", kind)
        synchronized(jobs) {
            jobs[job.id] = job
            while (jobs.size > KEEP) {
                val oldest = jobs.entries.firstOrNull { it.value.status == Status.DONE || it.value.status == Status.FAILED } ?: break
                jobs.remove(oldest.key)
            }
        }
        executor.execute {
            job.status = Status.RUNNING
            job.message = "running"
            try {
                job.result = work(job)
                job.progress = 1.0
                job.message = "done"
                job.status = Status.DONE
            } catch (e: Throwable) {
                job.error = errorText(e)
                job.message = "failed"
                job.status = Status.FAILED
                if (e is VirtualMachineError) throw e
            }
        }
        return job
    }

    fun get(id: String): Job? = synchronized(jobs) { jobs[id] }

    override fun close() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        const val KEEP = 200

        fun errorText(e: Throwable): String = when (e) {
            is LabError, is CliktError -> e.message ?: e.javaClass.simpleName
            is OutOfMemoryError -> "out of memory (try a shorter context or fewer candidates)"
            else -> "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        }
    }
}
