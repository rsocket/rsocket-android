/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

    // return boolean because of native
    suspend fun tick(job: CompletableJob): Boolean {
        delay(keepAlive.interval)
        if (lastMark.value.elapsedNow() < keepAlive.maxLifetime) return true
        job.completeExceptionally(RSocketError.ConnectionError("No keep-alive for ${keepAlive.maxLifetime}"))
        return false
    }
}
