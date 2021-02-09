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
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.internal.handler.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

internal class RSocketRequester(
    override val job: CompletableJob,
    private val pool: BufferPool,
    private val requestScope: CoroutineScope,
    private val connection: PriorityConnection,
    private val streamStorage: StreamStorage
) : RSocket {
    override suspend fun metadataPush(metadata: ByteReadPacket) {
        checkActive(metadata)

        metadata.closeOnError {
            connection.sendMetadataPush(metadata)
        }
    }

    override suspend fun fireAndForget(payload: Payload) {
        checkActive(payload)

        val id = streamStorage.nextId()

        try {
            connection.sendRequestPayload(FrameType.RequestFnF, id, payload)
        } catch (cause: Throwable) {
            payload.release()
            if (job.isActive) connection.sendCancel(id)
            throw cause
        }
    }

    override suspend fun requestResponse(payload: Payload): Payload {
        checkActive(payload)

        val id = streamStorage.nextId()

        val deferred = CompletableDeferred<Payload>()
        val handler = RequesterRequestResponseFrameHandler(id, streamStorage, pool, deferred)
        streamStorage.save(id, handler)

        return handler.receiveOrCancel(id, payload) {
            connection.sendRequestPayload(FrameType.RequestResponse, id, payload)
            deferred.await()
        }
    }

    @ExperimentalStreamsApi
    override fun requestStream(payload: Payload): Flow<Payload> = requestFlow { strategy, initialRequest ->
        checkActive(payload)

        val id = streamStorage.nextId()

        val channel = SafeChannel<Payload>(Channel.UNLIMITED)
        val handler = RequesterRequestStreamFrameHandler(id, streamStorage, pool, channel)
        streamStorage.save(id, handler)

        handler.receiveOrCancel(id, payload) {
            connection.sendRequestPayload(FrameType.RequestStream, id, payload, initialRequest)
            emitAllWithRequestN(channel, strategy) { connection.sendRequestN(id, it) }
        }
    }

    @ExperimentalStreamsApi
    override fun requestChannel(initPayload: Payload, payloads: Flow<Payload>): Flow<Payload> = requestFlow { strategy, initialRequest ->
        checkActive(initPayload)

        val id = streamStorage.nextId()

        val channel = SafeChannel<Payload>(Channel.UNLIMITED)
        val limiter = Limiter(0)
        val sender = Job(requestScope.coroutineContext[Job])
        val handler = RequesterRequestChannelFrameHandler(id, streamStorage, pool, limiter, sender, channel)
        streamStorage.save(id, handler)

        handler.receiveOrCancel(id, initPayload) {
            connection.sendRequestPayload(FrameType.RequestChannel, id, initPayload, initialRequest)
            requestScope.launch(sender) {
                handler.sendOrFail(id) {
                    payloads.collectLimiting(limiter) { connection.sendNextPayload(id, it) }
                    connection.sendCompletePayload(id)
                }
            }
            emitAllWithRequestN(channel, strategy) { connection.sendRequestN(id, it) }
        }
    }


    private suspend inline fun SendFrameHandler.sendOrFail(
        id: Int,
        block: () -> Unit
    ) {
        try {
            block()
            onSendComplete()
        } catch (cause: Throwable) {
//            println("REQ_FAIL: ${cause.stackTraceToString()}")
            val isFailed = onSendFailed(cause)
            if (job.isActive && isFailed) connection.sendError(id, cause)
            fail(cause)
        }
    }

    private suspend inline fun <T> ReceiveFrameHandler.receiveOrCancel(
        id: Int,
        payload: Payload,
        block: () -> T
    ): T {
        try {
            val result = block()
            onReceiveComplete()
            return result
        } catch (cause: Throwable) {
//            println("REQ_CANCEL: ${cause.stackTraceToString()}")
            payload.release()
            val isCancelled = onReceiveCancelled(cause)
            if (job.isActive && isCancelled) connection.sendCancel(id)
            throw cause
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun checkActive(closeable: Closeable) {
        if (job.isActive) return

        closeable.close()

        val error = job.getCancellationException()
        throw error.cause ?: error
    }
}
