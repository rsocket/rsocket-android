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
import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.native.concurrent.*

@SharedImmutable
private val selectFrame: suspend (Frame) -> Frame = { it }

private const val lengthSize = 3
private const val headerSize = 6
private const val fragmentOffset = lengthSize + headerSize
private const val fragmentOffsetWithMetadata = fragmentOffset + lengthSize

internal class PriorityConnection(
    private val pool: BufferPool,
    private val maxFragmentSize: Int,
    connectionBuffer: Int,
) {
    private val priorityChannel = SafeChannel<Frame>(connectionBuffer)
    private val commonChannel = SafeChannel<Frame>(connectionBuffer)

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
        sendFragmented(type, streamId, payload, false, false, initialRequest)
    }

    suspend fun sendNextPayload(streamId: Int, payload: Payload) {
        sendFragmented(FrameType.Payload, streamId, payload, false, true, 0)
    }

    suspend fun sendNextCompletePayload(streamId: Int, payload: Payload) {
        sendFragmented(FrameType.Payload, streamId, payload, true, true, 0)
    }

    suspend fun sendCompletePayload(streamId: Int) {
        send(RequestFrame(FrameType.Payload, streamId, false, true, false, 0, Payload.Empty))
    }

    private suspend fun sendFragmented(
        type: FrameType,
        streamId: Int,
        payload: Payload,
        complete: Boolean,
        next: Boolean,
        initialRequest: Int
    ) {
        if (!payload.isFragmentable(type.hasInitialRequest)) {
            send(RequestFrame(type, streamId, false, complete, next, initialRequest, payload))
            return
        }

        val data = payload.data
        val metadata = payload.metadata

        val fragmentSize = maxFragmentSize - fragmentOffset - (if (type.hasInitialRequest) Int.SIZE_BYTES else 0)

        var first = true
        var remaining = fragmentSize
        if (metadata != null) remaining -= lengthSize

        do {
            val metadataFragment = if (metadata != null && metadata.isNotEmpty) {
                if (!first) remaining -= lengthSize
                val length = min(metadata.remaining.toInt(), remaining)
                remaining -= length
                metadata.readPacket(pool, length)
            } else null

            val dataFragment = if (remaining > 0 && data.isNotEmpty) {
                val length = min(data.remaining.toInt(), remaining)
                remaining -= length
                data.readPacket(pool, length)
            } else {
                ByteReadPacket.Empty
            }

            val fType = if (first && type.isRequestType) type else FrameType.Payload
            val fragment = Payload(dataFragment, metadataFragment)
            val follows = metadata != null && metadata.isNotEmpty || data.isNotEmpty
            send(RequestFrame(fType, streamId, follows, (!follows && complete), !fType.isRequestType, initialRequest, fragment))
            first = false
            remaining = fragmentSize
        } while (follows)
    }

    private fun Payload.isFragmentable(hasInitialRequest: Boolean) = when (maxFragmentSize) {
        0    -> false
        else -> when (val meta = metadata) {
            null -> data.remaining > maxFragmentSize - fragmentOffset - (if (hasInitialRequest) Int.SIZE_BYTES else 0)
            else -> data.remaining + meta.remaining > maxFragmentSize - fragmentOffsetWithMetadata - (if (hasInitialRequest) Int.SIZE_BYTES else 0)
        }
    }

    fun close(error: Throwable?) {
        priorityChannel.closeReceivedElements()
        priorityChannel.close(error)
        priorityChannel.cancel()

        commonChannel.closeReceivedElements()
        commonChannel.close(error)
        commonChannel.cancel()
    }

}
