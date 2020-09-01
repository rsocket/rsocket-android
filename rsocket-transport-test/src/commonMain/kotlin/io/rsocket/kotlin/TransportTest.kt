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
import io.rsocket.kotlin.flow.*
import io.rsocket.kotlin.keepalive.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*
import kotlin.time.*

@OptIn(ExperimentalTime::class)
abstract class TransportTest(private val timeout: Duration = 10.minutes) {
    private var client: RSocket? = null

    abstract suspend fun init(): RSocket

    private suspend fun client(): RSocket = when (val c = client) {
        null -> init().also { client = it }
        else -> c
    }

    @AfterTest
    fun clean() = test {
        client().job.cancelAndJoin()
    }

    @Suppress("FunctionName")
    private fun Payload(metadataPresent: Int): Payload {
        val metadata = when (metadataPresent % 5) {
            0    -> null
            1    -> ""
            else -> MOCK_METADATA
        }
        return Payload(MOCK_DATA, metadata)
    }

    @Test
    fun fireAndForget10() = test(timeout) {
        val client = client()
        (1..10).map { async { client.fireAndForget(Payload(it)) } }.awaitAll()
    }

    @Test
    fun largePayloadFireAndForget10() = test(timeout) {
        val client = client()
        (1..10).map { async { client.fireAndForget(LARGE_PAYLOAD) } }.awaitAll()
    }

    @Test
    fun metadataPush10() = test(timeout) {
        val client = client()
        (1..10).map { async { client.metadataPush(ByteReadPacket(MOCK_DATA.encodeToByteArray())) } }.awaitAll()
    }

    @Test
    fun largePayloadMetadataPush10() = test(timeout) {
        val client = client()
        (1..10).map { async { client.metadataPush(ByteReadPacket(LARGE_DATA.encodeToByteArray())) } }.awaitAll()
    }

    @Test
    fun requestChannel0() = test(10.seconds) {
        val client = client()
        val list = client.requestChannel(emptyFlow()).toList()
        assertTrue(list.isEmpty())
    }

    @Test
    fun requestChannel1() = test(10.seconds) {
        val client = client()
        val list = client.requestChannel(flowOf(Payload(0))).onEach { it.release() }.toList()
        assertEquals(1, list.size)
    }

    @Test
    fun requestChannel3() = test(timeout) {
        val client = client()
        val request = RequestingFlow {
            repeat(3) { emit(Payload(it)) }
        }
        val list = client.requestChannel(request).requesting(RequestStrategy(3)).onEach { it.release() }.toList()
        assertEquals(3, list.size)
    }

    @Test
    fun largePayloadRequestChannel200() = test(timeout) {
        val client = client()
        val request = RequestingFlow {
            repeat(200) { emit(LARGE_PAYLOAD) }
        }
        val list = client.requestChannel(request).requesting(RequestStrategy(Int.MAX_VALUE)).onEach { it.release() }.toList()
        assertEquals(200, list.size)
    }

    @Test
    fun requestChannel20000() = test(timeout) {
        val client = client()
        val request = RequestingFlow {
            repeat(20_000) { emit(Payload(7)) }
        }
        val list = client.requestChannel(request).requesting(RequestStrategy(Int.MAX_VALUE)).onEach {
            assertEquals(MOCK_DATA, it.data.readText())
            assertEquals(MOCK_METADATA, it.metadata?.readText())
        }.toList()
        assertEquals(20_000, list.size)
    }

    @Test
    fun requestChannel200000() = test(timeout) {
        val client = client()
        val request = RequestingFlow {
            repeat(200_000) { emit(Payload(it)) }
        }
        (1..10).map {
            async(Dispatchers.Default) {
                val list = client.requestChannel(request).requesting(RequestStrategy(Int.MAX_VALUE)).onEach { it.release() }.toList()
                assertEquals(200_000, list.size)
            }
        }.awaitAll()

    }

    @Test
    fun requestChannel256x512() = test(null) {
        val client = client()
        val request = RequestingFlow {
            repeat(512) {
                emit(Payload(it))
            }
        }
        (0..256).map {
            async(Dispatchers.Default) {
                withTimeout(timeout) {
                    val list = client.requestChannel(request).onEach { it.release() }.toList()
                    assertEquals(512, list.size)
                }
            }
        }.awaitAll()
    }

    @Test
    fun requestResponse1() = test(timeout) {
        val client = client()
        client.requestResponse(Payload(1)).let(::checkPayload)
    }

    @Test
    fun requestResponse10() = test(timeout) {
        val client = client()
        (1..10).map { async { client.requestResponse(Payload(it)).let(::checkPayload) } }.awaitAll()
    }

    @Test
    fun requestResponse100() = test(timeout) {
        val client = client()
        (1..100).map { async { client.requestResponse(Payload(it)).let(::checkPayload) } }.awaitAll()
    }

    @Test
    fun largePayloadRequestResponse100() = test(timeout) {
        val client = client()
        (1..100).map { async { client.requestResponse(LARGE_PAYLOAD) } }.awaitAll().onEach { it.release() }
    }

    @Test
    fun requestResponse10000() = test(timeout) {
        val client = client()
        (1..10000).map { async { client.requestResponse(Payload(3)).let(::checkPayload) } }.awaitAll()
    }

    @Test
    fun requestResponse100000() = test(timeout) {
        val client = client()
        (1..10).map {
            async(Dispatchers.Default) { repeat(100000) { client.requestResponse(Payload(3)).let(::checkPayload) } }
        }.awaitAll()
    }

    @Test
    fun requestStream5() = test(timeout) {
        val client = client()
        val list = client.requestStream(Payload(3)).onEach { checkPayload(it) }.take(5).toList()
        assertEquals(5, list.size)
    }

    @Test
    fun requestStream10000() = test(timeout) {
        val client = client()
        val list = client.requestStream(Payload(3)).onEach { checkPayload(it) }.toList()
        assertEquals(10000, list.size)
    }

    fun checkPayload(payload: Payload) {
        assertEquals(TestRSocket.data, payload.data.readText())
        assertEquals(TestRSocket.metadata, payload.metadata?.readText())
    }

    companion object {
        val MOCK_DATA: String = "test-data"
        val MOCK_METADATA: String = "metadata"
        val LARGE_DATA by lazy { readLargePayload("words.shakespeare.txt.gz") }
        val LARGE_PAYLOAD by lazy { Payload(LARGE_DATA, LARGE_DATA) }

        val ACCEPTOR: RSocketAcceptor = { TestRSocket() }
        val CONNECTOR_CONFIG = RSocketConnectorConfiguration(keepAlive = KeepAlive(3.seconds, 100.minutes))
        val SERVER_CONFIG = RSocketServerConfiguration()
    }
}

class TestRSocket : RSocket {
    override val job: Job = Job()

    override fun metadataPush(metadata: ByteReadPacket): Unit = metadata.release()

    override fun fireAndForget(payload: Payload): Unit = payload.release()

    override suspend fun requestResponse(payload: Payload): Payload {
        payload.release()
        return Payload(data, metadata)
    }

    override fun requestStream(payload: Payload): RequestingFlow<Payload> = RequestingFlow {
        repeat(10000) { emit(requestResponse(payload)) }
    }

    override fun requestChannel(payloads: RequestingFlow<Payload>): RequestingFlow<Payload> = RequestingFlow {
        payloads.collect { emit(it) }
    }

    companion object {
        const val data = "hello world"
        const val metadata = "metadata"
    }
}
