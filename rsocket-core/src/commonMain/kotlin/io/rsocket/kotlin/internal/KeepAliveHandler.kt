package io.rsocket.kotlin.internal

import io.rsocket.kotlin.*
import io.rsocket.kotlin.keepalive.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.time.*
import kotlin.time.TimeSource.*

@OptIn(ExperimentalTime::class)
internal class KeepAliveHandler(private val keepAlive: KeepAlive) {
    private val lastMark: AtomicRef<TimeMark> = atomic(Monotonic.markNow()) // mark initial timestamp for keepalive

    fun mark() {
        lastMark.value = Monotonic.markNow()
    }

    suspend fun tick(job: Job): Boolean {
        delay(keepAlive.interval)
        if (lastMark.value.elapsedNow() < keepAlive.maxLifetime) return true
        job.cancel("Keep-alive failed", RSocketError.ConnectionError("No keep-alive for ${keepAlive.maxLifetime}"))
        return false
    }
}
