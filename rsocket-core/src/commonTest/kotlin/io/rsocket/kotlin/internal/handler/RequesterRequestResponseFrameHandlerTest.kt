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

package io.rsocket.kotlin.internal.handler

import io.rsocket.kotlin.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import io.rsocket.kotlin.test.*
import kotlinx.coroutines.*
import kotlin.test.*

class RequesterRequestResponseFrameHandlerTest : SuspendTest, TestWithLeakCheck {
    private val storage = StreamStorage(StreamId(true))
    private val deferred = CompletableDeferred<Payload>()
    private val handler = RequesterRequestResponseFrameHandler(1, storage, deferred).also { storage.save(1, it) }

    @Test
    fun testCompleteOnPayloadReceive() = test {
        handler.handlePayload(RequestFrame(FrameType.Payload, 1, false, false, true, 0, payload("hello")))
        assertTrue(deferred.isCompleted)
        assertEquals("hello", deferred.await().data.readText())
        handler.onReceiveComplete()
        assertFalse(storage.contains(1))
    }

    @Test
    fun testFailOnPayloadReceive() = test {
        handler.handleError(RSocketError.ApplicationError("failed"))
        assertTrue(deferred.isCompleted)
        assertFailsWith(RSocketError.ApplicationError::class, "failed") { deferred.await() }
        assertFalse(storage.contains(1))
    }

    @Test
    fun testFailOnCleanup() = test {
        handler.cleanup(IllegalStateException("failed"))
        assertTrue(deferred.isCompleted)
        assertFailsWith(CancellationException::class, "Connection closed") { deferred.await() }
    }
}
