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

package io.rsocket.kotlin.transport.ktor

import io.ktor.network.sockets.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.internal.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.frame.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.coroutines.*
import kotlin.native.concurrent.*

@OptIn(KtorExperimentalAPI::class, TransportApi::class, DangerousInternalIoApi::class)
internal class TcpConnection(private val socket: Socket) : Connection, CoroutineScope {
    override val job: Job = Job(socket.socketContext)
    override val coroutineContext: CoroutineContext = job + Dispatchers.Unconfined

    private val sendChannel = SafeChannel<ByteReadPacket>(8)
    private val receiveChannel = SafeChannel<ByteReadPacket>(8)

    init {
        launch {
            socket.openWriteChannel(autoFlush = true).use {
                while (isActive) {
                    val packet = sendChannel.receive()
                    val length = packet.remaining.toInt()
                    writePacket {
                        writeLength(length)
                        writePacket(packet)
                    }
                }
            }
        }
        launch {
            socket.openReadChannel().apply {
                while (isActive) {
                    val length = readPacket(3).readLength()
                    val packet = readPacket(length)
                    receiveChannel.send(packet)
                }
            }
        }
    }

    override suspend fun send(packet: ByteReadPacket): Unit = sendChannel.send(packet)

    override suspend fun receive(): ByteReadPacket = receiveChannel.receive()
}

@SharedImmutable
private val onUndeliveredCloseable: (Closeable) -> Unit = Closeable::close

@Suppress("FunctionName")
private fun <E : Closeable> SafeChannel(capacity: Int): Channel<E> = Channel(capacity, onUndeliveredElement = onUndeliveredCloseable)
