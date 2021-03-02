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

package io.rsocket.kotlin

import io.ktor.utils.io.core.*
import io.rsocket.kotlin.core.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.keepalive.*
import io.rsocket.kotlin.payload.*
import io.rsocket.kotlin.test.*
import io.rsocket.kotlin.transport.*
import kotlinx.coroutines.*
import kotlin.test.*

class ConnectionEstablishmentTest : SuspendTest, TestWithLeakCheck {
    @Test
    fun responderRejectSetup() = test {
        val errorMessage = "error"
        val sendingRSocket = CompletableDeferred<RSocket>()

        val connection = TestConnection()

        val serverTransport = ServerTransport { accept ->
            GlobalScope.async { accept(connection) }
        }

        val deferred = RSocketServer().bind(serverTransport) {
            sendingRSocket.complete(requester)
            error(errorMessage)
        }

        connection.sendToReceiver(
            SetupFrame(
                version = Version.Current,
                honorLease = false,
                keepAlive = KeepAlive(),
                resumeToken = null,
                payloadMimeType = PayloadMimeType(),
                payload = payload("setup") //should be released
            )
        )

        assertFailsWith(RSocketError.Setup.Rejected::class, errorMessage) { deferred.await() }

        connection.test {
            expectFrame { frame ->
                assertTrue(frame is ErrorFrame)
                assertTrue(frame.throwable is RSocketError.Setup.Rejected)
                assertEquals(errorMessage, frame.throwable.message)
            }
            val sender = sendingRSocket.await()
            assertFalse(sender.isActive)
            val error = expectError()
            assertTrue(error is RSocketError.Setup.Rejected)
            assertEquals(errorMessage, error.message)
        }
    }

    @Test
    fun requesterReleaseSetupPayloadOnFailedAcceptor() = test {
        val connection = TestConnection()
        val p = payload("setup")
        assertFailsWith(IllegalStateException::class, "failed") {
            RSocketConnector {
                connectionConfig {
                    setupPayload { p }
                }
                acceptor {
                    assertTrue(config.setupPayload.data.isNotEmpty)
                    assertTrue(p.data.isNotEmpty)
                    error("failed")
                }
            }.connect(ClientTransport { connection })
        }
        assertTrue(p.data.isEmpty)
    }

}
