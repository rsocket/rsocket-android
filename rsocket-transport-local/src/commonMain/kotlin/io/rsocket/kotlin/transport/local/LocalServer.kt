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

@file:OptIn(TransportApi::class)

package io.rsocket.kotlin.transport.local

import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.internal.*
import io.ktor.utils.io.pool.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.native.concurrent.*

@Suppress("FunctionName")
@OptIn(DangerousInternalIoApi::class)
public fun LocalServer(parentJob: Job? = null): LocalServer = LocalServer(parentJob, ChunkBuffer.Pool)

public class LocalServer
@DangerousInternalIoApi
internal constructor(
    parentJob: Job?,
    private val pool: ObjectPool<ChunkBuffer>,
) : Cancelable, ServerTransport<Job>, ClientTransport {
    private val connections = Channel<Connection>()
    override val job: Job = SupervisorJob(parentJob)

    override suspend fun connect(): Connection {
        val clientChannel = SafeChannel<ByteReadPacket>(Channel.UNLIMITED)
        val serverChannel = SafeChannel<ByteReadPacket>(Channel.UNLIMITED)
        val connectionJob = Job(job)
        val clientConnection = LocalConnection(serverChannel, clientChannel, pool, connectionJob)
        val serverConnection = LocalConnection(clientChannel, serverChannel, pool, connectionJob)
        connections.send(serverConnection)
        return clientConnection
    }

    override fun start(accept: suspend (Connection) -> Unit): Job = GlobalScope.launch(job) {
        connections.consumeEach { launch(job) { accept(it) } }
    }
}

@SharedImmutable
private val onUndeliveredCloseable: (Closeable) -> Unit = Closeable::close

@Suppress("FunctionName")
private fun <E : Closeable> SafeChannel(capacity: Int): Channel<E> = Channel(capacity, onUndeliveredElement = onUndeliveredCloseable)
