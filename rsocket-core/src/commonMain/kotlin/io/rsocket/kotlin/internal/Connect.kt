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
import io.rsocket.kotlin.*
import io.rsocket.kotlin.core.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.handler.*
import kotlinx.coroutines.*
import kotlin.native.concurrent.*

@TransportApi
internal suspend fun Connection.connect(
    isServer: Boolean,
    interceptors: Interceptors,
    connectionConfig: ConnectionConfig,
    acceptor: ConnectionAcceptor
): RSocket = Connect(this, isServer, interceptors, connectionConfig, acceptor).start()

// TODO will be replaced by configurable requestScope context
//  for now needed to mute errors on K/N
@SharedImmutable
private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }

@TransportApi
private class Connect(
    private val connection: Connection,
    private val isServer: Boolean,
    private val interceptors: Interceptors,
    private val connectionConfig: ConnectionConfig,
    private val acceptor: ConnectionAcceptor
) {
    private val keepAliveHandler = KeepAliveHandler(connectionConfig.keepAlive)
    private val priorityConnection = PriorityConnection()
    private val streamStorage = StreamStorage(StreamId(isServer))
    private val requestScope = CoroutineScope(SupervisorJob(connection.job) + exceptionHandler)

    init {
        connection.job.invokeOnCompletion {
            priorityConnection.close(it)
            streamStorage.cleanup(it)
            connectionConfig.setupPayload.release()
        }
    }

    suspend fun start(): RSocket {
        val requester = interceptors.wrapRequester(RSocketRequester(connection.job, requestScope, priorityConnection, streamStorage))

        val responder = with(interceptors.wrapAcceptor(acceptor)) {
            ConnectionAcceptorContext(connectionConfig, requester).accept()
        }
        val requestHandler = interceptors.wrapResponder(responder)

        // link completing of connection and requestHandler
        connection.job.invokeOnCompletion(requestHandler.job::completeWith)
        requestHandler.job.invokeOnCompletion(connection.job::completeWith)

        startWith(RSocketResponder(priorityConnection, requestScope, requestHandler))
        return requester
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun startWith(responder: RSocketResponder) {
        CoroutineScope(connection.job + Dispatchers.Unconfined).apply {
            // start keepalive ticks
            launch(start = CoroutineStart.UNDISPATCHED) {
                while (true) {
                    if (!keepAliveHandler.tick(connection.job)) continue
                    priorityConnection.sendKeepAlive(true, 0, ByteReadPacket.Empty)
                }
            }

            // start sending frames to connection
            launch(start = CoroutineStart.UNDISPATCHED) {
                while (true) priorityConnection.receive().closeOnError { connection.sendFrame(it) }
            }

            // start frame handling
            launch(start = CoroutineStart.UNDISPATCHED) {
                while (true) connection.receiveFrame().handleWith(responder)
            }
        }
    }

    private suspend fun Frame.handleWith(responder: RSocketResponder): Unit = closeOnError { frame ->
        when (streamId) {
            0    -> when (frame) {
                is MetadataPushFrame -> responder.handleMetadataPush(frame.metadata)
                is ErrorFrame        -> connection.job.completeExceptionally(frame.throwable)
                is KeepAliveFrame    -> {
                    keepAliveHandler.mark()
                    if (frame.respond) priorityConnection.sendKeepAlive(false, 0, frame.data)
                }
                is LeaseFrame        -> frame.release().also { error("lease isn't implemented") }
                else                 -> frame.release()
            }
            else -> when (isServer.xor(streamId % 2 != 0)) {
                true  -> streamStorage.handleRequesterFrame(frame)
                false -> streamStorage.handleResponderFrame(frame) { handleRequestFrame(it, responder) }
            }
        }
    }

    private fun handleRequestFrame(requestFrame: RequestFrame, responder: RSocketResponder): ResponderFrameHandler {
        val id = requestFrame.streamId
        val initialRequest = requestFrame.initialRequest
        val payload = requestFrame.payload

        return when (requestFrame.type) {
            FrameType.RequestFnF      -> ResponderFireAndForgetFrameHandler(payload, id, streamStorage, responder)
            FrameType.RequestResponse -> ResponderRequestResponseFrameHandler(payload, id, streamStorage, responder)
            FrameType.RequestStream   -> ResponderRequestStreamFrameHandler(payload, id, streamStorage, responder, initialRequest)
            FrameType.RequestChannel  -> ResponderRequestChannelFrameHandler(payload, id, streamStorage, responder, initialRequest)
            else                      -> error("Wrong request frame type") // should never happen
        }
    }

}

private fun CompletableJob.completeWith(error: Throwable?) {
    when (error) {
        null                     -> complete()
        is CancellationException -> cancel(error)
        else                     -> completeExceptionally(error)
    }
}
