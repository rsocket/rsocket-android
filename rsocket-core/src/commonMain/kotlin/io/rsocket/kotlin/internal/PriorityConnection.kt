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
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.*
import kotlin.coroutines.*
import kotlin.native.concurrent.*

@SharedImmutable
private val selectFrame: suspend (Frame) -> Frame = { it }

internal class PriorityConnection {
    private val priorityChannel = SafeChannel<Frame>(Channel.UNLIMITED)
    private val commonChannel = SafeChannel<Frame>(Channel.UNLIMITED)

    suspend fun receive(): Frame {
        priorityChannel.poll()?.let { return it }
        commonChannel.poll()?.let { return it }
        return select {
            priorityChannel.onReceive(selectFrame)
            commonChannel.onReceive(selectFrame)
        }
    }

    private suspend fun send(frame: Frame) {
        coroutineContext.ensureActive()
        val channel = if (frame.streamId == 0) priorityChannel else commonChannel
        channel.send(frame)
    }

    suspend fun sendKeepAlive(respond: Boolean, lastPosition: Long, data: ByteReadPacket): Unit =
        send(KeepAliveFrame(respond, lastPosition, data))

    suspend fun sendMetadataPush(metadata: ByteReadPacket): Unit = send(MetadataPushFrame(metadata))

    suspend fun sendCancel(id: Int): Unit = withContext(NonCancellable) { send(CancelFrame(id)) }
    suspend fun sendError(id: Int, throwable: Throwable): Unit = withContext(NonCancellable) { send(ErrorFrame(id, throwable)) }
    suspend fun sendRequestN(id: Int, n: Int): Unit = send(RequestNFrame(id, n))

    suspend fun sendRequestPayload(type: FrameType, streamId: Int, payload: Payload, initialRequest: Int = 0) {
        sendRequestFrame(type, streamId, payload, false, false, initialRequest)
    }

    suspend fun sendNextPayload(streamId: Int, payload: Payload) {
        sendRequestFrame(FrameType.Payload, streamId, payload, false, true, 0)
    }

    suspend fun sendNextCompletePayload(streamId: Int, payload: Payload) {
        sendRequestFrame(FrameType.Payload, streamId, payload, true, true, 0)
    }

    suspend fun sendCompletePayload(streamId: Int) {
        sendRequestFrame(FrameType.Payload, streamId, Payload.Empty, true, false, 0)
    }

    private suspend fun sendRequestFrame(
        type: FrameType,
        streamId: Int,
        payload: Payload,
        complete: Boolean,
        next: Boolean,
        initialRequest: Int
    ) {
        send(RequestFrame(type, streamId, false, complete, next, initialRequest, payload))
    }

    fun close(error: Throwable?) {
        priorityChannel.fullClose(error)
        commonChannel.fullClose(error)
    }

}
