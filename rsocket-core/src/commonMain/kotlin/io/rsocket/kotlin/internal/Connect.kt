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
import io.ktor.utils.io.core.internal.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.core.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.handler.*
import kotlinx.coroutines.*

@TransportApi
internal suspend fun Connection.connect(
    isServer: Boolean,
    maxFragmentSize: Int,
    connectionBuffer: Int,
    requestDispatcher: CoroutineDispatcher,
    interceptors: Interceptors,
    connectionConfig: ConnectionConfig,
    acceptor: ConnectionAcceptor
): RSocket = Connect(this, isServer, maxFragmentSize, connectionBuffer, requestDispatcher, interceptors, connectionConfig, acceptor).start()

@TransportApi
private class Connect(
    private val connection: Connection,
    private val isServer: Boolean,
    maxFragmentSize: Int,
    connectionBuffer: Int,
    requestDispatcher: CoroutineDispatcher,
    private val interceptors: Interceptors,
    private val connectionConfig: ConnectionConfig,
    private val acceptor: ConnectionAcceptor
) {
    private val keepAliveHandler = KeepAliveHandler(connectionConfig.keepAlive)

    @OptIn(DangerousInternalIoApi::class) //because of pool
    private val priorityConnection = PriorityConnection(connection.pool, maxFragmentSize, connectionBuffer)
    private val streamStorage = StreamStorage(StreamId(isServer))
    private val requestScope = CoroutineScope(SupervisorJob(connection.job) + requestDispatcher)
    private val connectionScope = CoroutineScope(connection.job + Dispatchers.Unconfined)

    init {
        connection.job.invokeOnCompletion {
            priorityConnection.close(it)
            streamStorage.cleanup(it)
        }
    }

    suspend fun start(): RSocket {
        val requester = createRequester()
        val responderDeferred = async { createResponder(requester) }
        // start keepalive ticks
        launchWhileActive {
            if (keepAliveHandler.tick(connection.job)) priorityConnection.sendKeepAlive(true, 0, ByteReadPacket.Empty)
        }

        // start sending frames to connection
        launchWhileActive {
            priorityConnection.receive().closeOnError { connection.sendFrame(it) }
        }

        launch {
            // start frame handling for zero stream and requester side
            // after this, requester RSocket is ready to do requests and accept responses
            // that's needed to be able to use requester RSocket inside ConnectionAcceptor
            // when connection will be accepted or if we will receive request from responder, we should await
            startFrameHandling({ responderDeferred.isActive }, { responderDeferred.await() })
            val responder = responderDeferred.await()
            startFrameHandling({ true }, { responder })
        }
        responderDeferred.await()
        return requester
    }

    @OptIn(DangerousInternalIoApi::class) //because of pool
    private fun createRequester(): RSocket {
        val requester = RSocketRequester(connection.job, connection.pool, requestScope, priorityConnection, streamStorage)
        return interceptors.wrapRequester(requester)
    }

    private suspend fun createResponder(requester: RSocket): RSocketResponder {
        val rSocketResponder = with(interceptors.wrapAcceptor(acceptor)) {
            ConnectionAcceptorContext(connectionConfig, requester).accept()
        }
        val requestHandler = interceptors.wrapResponder(rSocketResponder)

        // link completing of connection and requestHandler
        connection.job.invokeOnCompletion(requestHandler.job::completeWith)
        requestHandler.job.invokeOnCompletion(connection.job::completeWith)

        return RSocketResponder(priorityConnection, requestScope, requestHandler)
    }

    private suspend inline fun startFrameHandling(
        isActive: () -> Boolean,
        getResponder: () -> RSocketResponder,
    ) {
        while (connection.isActive && isActive()) connection.receiveFrame().closeOnError { frame ->
            when (frame.streamId) {
                0    -> when (frame) {
                    is MetadataPushFrame -> getResponder().handleMetadataPush(frame.metadata)
                    is ErrorFrame        -> connection.job.completeExceptionally(frame.throwable)
                    is KeepAliveFrame    -> {
                        keepAliveHandler.mark()
                        if (frame.respond) priorityConnection.sendKeepAlive(false, 0, frame.data) else Unit
                    }
                    is LeaseFrame        -> frame.release().also { error("lease isn't implemented") }
                    else                 -> frame.release()
                }
                else -> when (isServer.xor(frame.streamId % 2 != 0)) {
                    true  -> streamStorage.handleRequesterFrame(frame)
                    false -> {
                        val responder = getResponder()
                        streamStorage.handleResponderFrame(frame) { handleRequestFrame(it, responder) }
                    }
                }
            }
        }
    }

    private fun handleRequestFrame(requestFrame: RequestFrame, responder: RSocketResponder): ResponderFrameHandler {
        val id = requestFrame.streamId
        val initialRequest = requestFrame.initialRequest

        @OptIn(DangerousInternalIoApi::class) //because of pool
        val handler = when (requestFrame.type) {
            FrameType.RequestFnF      -> ResponderFireAndForgetFrameHandler(id, streamStorage, connection.pool, responder)
            FrameType.RequestResponse -> ResponderRequestResponseFrameHandler(id, streamStorage, connection.pool, responder)
            FrameType.RequestStream   -> ResponderRequestStreamFrameHandler(id, streamStorage, connection.pool, responder, initialRequest)
            FrameType.RequestChannel  -> ResponderRequestChannelFrameHandler(id, streamStorage, connection.pool, responder, initialRequest)
            else                      -> error("Wrong request frame type") // should never happen
        }
        handler.handlePayload(requestFrame)
        return handler
    }


    //helper functions to start coroutines in specific scope and loop while connection is active

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> async(block: suspend CoroutineScope.() -> T): Deferred<T> {
        return connectionScope.async(start = CoroutineStart.UNDISPATCHED, block = block)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launch(block: suspend CoroutineScope.() -> Unit) {
        connectionScope.launch(start = CoroutineStart.UNDISPATCHED, block = block)
    }

    private inline fun launchWhileActive(crossinline block: suspend () -> Unit) {
        launch {
            while (connection.isActive) block()
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
