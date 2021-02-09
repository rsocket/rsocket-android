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

package io.rsocket.kotlin.core

import io.rsocket.kotlin.*
import io.rsocket.kotlin.keepalive.*
import io.rsocket.kotlin.logging.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*

public class RSocketConnectorBuilder internal constructor() {
    public var loggerFactory: LoggerFactory = DefaultLoggerFactory
    public var maxFragmentSize: Int = 0
        set(value) {
            require(value == 0 || value >= 64) {
                "maxFragmentSize should be zero (no fragmentation) or greater than or equal to 64, but was $value"
            }
            field = value
        }
    public var connectionBuffer: Int = 64
        set(value) {
            require(value >= 0)
            field = value
        }

    //    Dispatchers.Unconfined //NATIVE
    public var requestDispatcher: CoroutineDispatcher = Dispatchers.Default

    private val connectionConfig: ConnectionConfigBuilder = ConnectionConfigBuilder()
    private val interceptors: InterceptorsBuilder = InterceptorsBuilder()
    private var acceptor: ConnectionAcceptor? = null
    private var reconnectPredicate: ReconnectPredicate? = null

    public fun connectionConfig(configure: ConnectionConfigBuilder.() -> Unit) {
        connectionConfig.configure()
    }

    public fun interceptors(configure: InterceptorsBuilder.() -> Unit) {
        interceptors.configure()
    }

    public fun acceptor(block: ConnectionAcceptor?) {
        acceptor = block
    }

    public fun acceptor(block: suspend ConnectionAcceptorContext.() -> RSocket) {
        acceptor(ConnectionAcceptor(block))
    }

    /**
     * When configured, [RSocketConnector.connect] will return custom [RSocket] implementation,
     * which will try to reconnect if connection lost and [retries] are not exhausted with [predicate] returning `true`.
     *
     * **This is not Resumption**: by using [reconnectable] only connection will be re-established, streams will fail
     *
     * @param retries number of retries to do, if connection establishment failed
     */
    public fun reconnectable(retries: Long, predicate: suspend (cause: Throwable) -> Boolean = { true }) {
        reconnectPredicate = { cause, attempt -> predicate(cause) && attempt < retries }
    }

    /**
     * When configured, [RSocketConnector.connect] will return custom [RSocket] implementation,
     * which will try to reconnect if connection lost and [predicate] returns `true`.
     *
     * **This is not Resumption**: by using [reconnectable] only connection will be re-established, streams will fail
     *
     * @param predicate predicate for retry logic
     */
    public fun reconnectable(predicate: suspend (cause: Throwable, attempt: Long) -> Boolean) {
        reconnectPredicate = predicate
    }

    public class ConnectionConfigBuilder internal constructor() {
        public var keepAlive: KeepAlive = KeepAlive()
        public var payloadMimeType: PayloadMimeType = PayloadMimeType()
        private var setupPayload: (() -> Payload)? = null

        public fun setupPayload(block: (() -> Payload)?) {
            setupPayload = block
        }

        public fun setupPayload(payload: Payload): Unit = setupPayload { payload.copy() }

        internal fun producer(): () -> ConnectionConfig {
            val keepAlive = this.keepAlive
            val payloadMimeType = this.payloadMimeType
            val setupPayload = this.setupPayload
            return {
                ConnectionConfig(
                    keepAlive = keepAlive,
                    payloadMimeType = payloadMimeType,
                    setupPayload = setupPayload?.invoke() ?: Payload.Empty
                )
            }
        }
    }

    internal fun build(): RSocketConnector = RSocketConnector(
        loggerFactory = loggerFactory,
        maxFragmentSize = maxFragmentSize,
        connectionBuffer = connectionBuffer,
        requestDispatcher = requestDispatcher,
        interceptors = interceptors.build(),
        connectionConfigProvider = connectionConfig.producer(),
        acceptor = acceptor ?: defaultAcceptor,
        reconnectPredicate = reconnectPredicate
    )

    private companion object {
        private val defaultAcceptor: ConnectionAcceptor = ConnectionAcceptor { EmptyRSocket() }

        private class EmptyRSocket : RSocket {
            override val job: CompletableJob = Job()
        }
    }
}

public fun RSocketConnector(configure: RSocketConnectorBuilder.() -> Unit = {}): RSocketConnector {
    val builder = RSocketConnectorBuilder()
    builder.configure()
    return builder.build()
}
