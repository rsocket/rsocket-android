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

import io.ktor.utils.io.core.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.test.*
import kotlinx.coroutines.*
import kotlin.test.*

class PriorityConnectionTest : SuspendTest, TestWithLeakCheck {
    private val connection = PriorityConnection()

    @Test
    fun testOrdering() = test {
        connection.sendCancel(1)
        connection.sendCancel(2)
        connection.sendCancel(3)

        assertEquals(1, connection.receive().streamId)
        assertEquals(2, connection.receive().streamId)
        assertEquals(3, connection.receive().streamId)
    }

    @Test
    fun testOrderingPriority() = test {
        connection.sendMetadataPush(ByteReadPacket.Empty)
        connection.sendKeepAlive(true, 0, ByteReadPacket.Empty)

        assertTrue(connection.receive() is MetadataPushFrame)
        assertTrue(connection.receive() is KeepAliveFrame)
    }

    @Test
    fun testPrioritization() = test {
        connection.sendCancel(5)
        connection.sendMetadataPush(ByteReadPacket.Empty)
        connection.sendCancel(1)
        connection.sendMetadataPush(ByteReadPacket.Empty)

        assertEquals(0, connection.receive().streamId)
        assertEquals(0, connection.receive().streamId)
        assertEquals(5, connection.receive().streamId)
        assertEquals(1, connection.receive().streamId)
    }

    @Test
    fun testAsyncReceive() = test {
        val deferred = CompletableDeferred<Frame>()
        launch(anotherDispatcher) {
            deferred.complete(connection.receive())
        }
        delay(100)
        connection.sendCancel(5)
        assertTrue(deferred.await() is CancelFrame)
    }

    @Test
    fun testPrioritizationAndOrdering() = test {
        connection.sendRequestN(1, 1)
        connection.sendMetadataPush(ByteReadPacket.Empty)
        connection.sendCancel(1)
        connection.sendKeepAlive(true, 0, ByteReadPacket.Empty)

        assertTrue(connection.receive() is MetadataPushFrame)
        assertTrue(connection.receive() is KeepAliveFrame)
        assertTrue(connection.receive() is RequestNFrame)
        assertTrue(connection.receive() is CancelFrame)
    }

    @Test
    fun testReleaseOnClose() = test {
        val packet = packet("metadata")
        val payload = payload("data")
        connection.sendMetadataPush(packet)
        connection.sendNextPayload(1, payload)

        assertTrue(packet.isNotEmpty)
        assertTrue(payload.data.isNotEmpty)

        connection.close(null)

        assertTrue(packet.isEmpty)
        assertTrue(payload.data.isEmpty)
    }
}
