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
import io.rsocket.kotlin.internal.handler.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*

internal class RSocketResponder(
    private val connection: PriorityConnection,
    private val requestScope: CoroutineScope,
    private val requestHandler: RSocket,
) {

    fun handleMetadataPush(metadata: ByteReadPacket): Job = requestScope.launch {
        try {
            requestHandler.metadataPush(metadata)
        } finally {
            metadata.release()
        }
    }

    fun handleFireAndForget(payload: Payload, handler: ResponderFireAndForgetFrameHandler): Job = requestScope.launch {
        try {
            requestHandler.fireAndForget(payload)
        } finally {
            handler.onSendComplete()
            payload.release()
        }
    }

    fun handleRequestResponse(payload: Payload, id: Int, handler: ResponderRequestResponseFrameHandler): Job = requestScope.launch {
        handler.sendOrFail(this, id, payload) {
            val response = requestHandler.requestResponse(payload)
            connection.sendNextCompletePayload(id, response)
        }
    }

    fun handleRequestStream(payload: Payload, id: Int, handler: ResponderRequestStreamFrameHandler): Job = requestScope.launch {
        handler.sendOrFail(this, id, payload) {
            requestHandler.requestStream(payload).collectLimiting(handler.limiter) { connection.sendNextPayload(id, it) }
            connection.sendCompletePayload(id)
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    @ExperimentalStreamsApi
    fun handleRequestChannel(payload: Payload, id: Int, handler: ResponderRequestChannelFrameHandler): Job = requestScope.launch {
        val payloads = requestFlow { strategy, initialRequest ->
            handler.receiveOrCancel(requestScope, id) {
                connection.sendRequestN(id, initialRequest)
                emitAllWithRequestN(handler.channel, strategy) { connection.sendRequestN(id, it) }
            }
        }
        handler.sendOrFail(this, id, payload) {
            requestHandler.requestChannel(payload, payloads).collectLimiting(handler.limiter) { connection.sendNextPayload(id, it) }
            connection.sendCompletePayload(id)
        }
    }

    private suspend inline fun SendFrameHandler.sendOrFail(
        scope: CoroutineScope,
        id: Int,
        payload: Payload,
        block: () -> Unit
    ) {
        try {
            block()
            onSendComplete()
        } catch (cause: Throwable) {
//            println("RES_FAIL: ${cause.stackTraceToString()}")
            val isFailed = onSendFailed(cause)
            if (scope.isActive && isFailed) connection.sendError(id, cause)
            fail(cause)
        } finally {
            payload.release()
        }
    }

    private suspend inline fun <T> ReceiveFrameHandler.receiveOrCancel(
        scope: CoroutineScope,
        id: Int,
        block: () -> T
    ): T {
        try {
            val result = block()
            onReceiveComplete()
            return result
        } catch (cause: Throwable) {
//            println("RES_CANCEL: ${cause.stackTraceToString()}")
            val isCancelled = onReceiveCancelled(cause)
            if (scope.isActive && isCancelled) connection.sendCancel(id)
            throw cause
        }
    }

}
