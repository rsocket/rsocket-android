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
import io.ktor.util.network.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.transport.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

public fun TcpSocketBuilder.serverTransport(
    hostname: String = "0.0.0.0",
    port: Int = 0,
    configure: SocketOptions.AcceptorOptions.() -> Unit = {},
): TcpServer = serverTransport(NetworkAddress(hostname, port), configure)

public fun TcpSocketBuilder.serverTransport(
    localAddress: NetworkAddress? = null,
    configure: SocketOptions.AcceptorOptions.() -> Unit = {},
): TcpServer = TcpServer(bind(localAddress, configure))

@OptIn(KtorExperimentalAPI::class, TransportApi::class)
public class TcpServer(public val socket: ServerSocket) : ServerTransport<Job>, CoroutineScope {
    override val coroutineContext: CoroutineContext = socket.socketContext + Dispatchers.Unconfined

    override fun start(accept: suspend (Connection) -> Unit): Job = launch {
        while (isActive) {
            val clientSocket = socket.accept()
            val connection = TcpConnection(clientSocket)
            launch { accept(connection) }
        }
    }
}
