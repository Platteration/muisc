package dev.muisc.player

/** Monotonic time source used by the coordinator for every deadline and duration; injectable for tests. */
interface Clock {
    fun nowNanos(): Long
    fun nowMillis(): Long = nowNanos() / 1_000_000L
}

/** The JVM's monotonic clock. */
object SystemClock : Clock {
    override fun nowNanos(): Long = System.nanoTime()
}

/** Manually advanced clock for tests. */
class FakeClock(startNanos: Long = 0L) : Clock {
    @Volatile
    var nanos: Long = startNanos

    override fun nowNanos(): Long = nanos

    fun advanceNanos(n: Long) { nanos += n }
    fun advanceMillis(ms: Long) { nanos += ms * 1_000_000L }
    fun advanceSeconds(s: Double) { nanos += Math.round(s * 1e9) }
}
