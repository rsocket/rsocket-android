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

package io.rsocket.kotlin.transport.local

import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.internal.*
import io.ktor.utils.io.pool.*
import io.rsocket.kotlin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

@OptIn(DangerousInternalIoApi::class, TransportApi::class)
internal class LocalConnection(
    private val sender: Channel<ByteReadPacket>,
    private val receiver: Channel<ByteReadPacket>,
    override val pool: ObjectPool<ChunkBuffer>,
    parentJob: Job? = null,
) : Connection, Cancelable {
    override val job: Job = Job(parentJob)

    init {
        job.invokeOnCompletion {
            sender.close(it)
            receiver.close(it)
        }
    }

    override suspend fun send(packet: ByteReadPacket) {
        sender.send(packet)
    }

    override suspend fun receive(): ByteReadPacket {
        return receiver.receive()
    }
}
