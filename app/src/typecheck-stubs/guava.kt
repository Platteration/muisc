@file:Suppress("unused", "UNUSED_PARAMETER")

package com.google.common.util.concurrent

import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * `com.google.common.util.concurrent.ListenableFuture`.
 *
 * Really a `java.util.concurrent.Future<V>` plus `addListener`. The inherited members are given bodies
 * here (they are abstract in Guava) purely so anonymous stub implementations stay one line long; the
 * *signatures* are the real ones, which is what the app code is checked against.
 */
interface ListenableFuture<V> : Future<V> {
    fun addListener(listener: Runnable, executor: Executor) {}
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = true
    override fun get(): V = throw UnsupportedOperationException()
    override fun get(timeout: Long, unit: TimeUnit): V = throw UnsupportedOperationException()
}

object Futures {
    fun immediateVoidFuture(): ListenableFuture<Void> = object : ListenableFuture<Void> {}
    fun <V> immediateFuture(value: V): ListenableFuture<V> = object : ListenableFuture<V> {}
    fun <V> immediateFailedFuture(t: Throwable): ListenableFuture<V> = object : ListenableFuture<V> {}
}

class SettableFuture<V> private constructor() : ListenableFuture<V> {
    fun set(value: V): Boolean = true
    fun setException(t: Throwable): Boolean = true
    companion object {
        fun <V> create(): SettableFuture<V> = SettableFuture()
    }
}
