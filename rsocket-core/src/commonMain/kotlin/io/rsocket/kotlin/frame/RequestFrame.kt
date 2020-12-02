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

@file:Suppress("FunctionName")

package io.rsocket.kotlin.frame

import io.ktor.utils.io.core.*
import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.payload.*

class RequestFrame(
    override val type: FrameType,
    override val streamId: Int,
    val follows: Boolean,
    val complete: Boolean,
    val next: Boolean,
    val initialRequest: Int,
    val payload: Payload,
) : Frame(type) {
    override val flags: Int
        get() {
            var flags = 0
            if (payload.metadata != null) flags = flags or Flags.Metadata
            if (follows) flags = flags or Flags.Follows
            if (complete) flags = flags or Flags.Complete
            if (next) flags = flags or Flags.Next
            return flags
        }

    override fun release() {
        payload.release()
    }

    override fun BytePacketBuilder.writeSelf() {
        if (initialRequest > 0) writeInt(initialRequest)
        writePayload(payload)
    }

    override fun StringBuilder.appendFlags() {
        appendFlag('M', payload.metadata != null)
        appendFlag('F', follows)
        appendFlag('C', complete)
        appendFlag('N', next)
    }

    override fun StringBuilder.appendSelf() {
        if (initialRequest > 0) append("\nInitial request: ").append(initialRequest)
        appendPayload(payload)
    }
}

internal fun ByteReadPacket.readRequest(pool: BufferPool, type: FrameType, streamId: Int, flags: Int, withInitial: Boolean): RequestFrame {
    val fragmentFollows = flags check Flags.Follows
    val complete = flags check Flags.Complete
    val next = flags check Flags.Next

    val initialRequest = if (withInitial) readInt() else 0
    val payload = readPayload(pool, flags)
    return RequestFrame(type, streamId, fragmentFollows, complete, next, initialRequest, payload)
}

//TODO rename or remove on fragmentation implementation
internal fun RequestFireAndForgetFrame(streamId: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.RequestFnF, streamId, false, false, false, 0, payload)

internal fun RequestResponseFrame(streamId: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.RequestResponse, streamId, false, false, false, 0, payload)

internal fun RequestStreamFrame(streamId: Int, initialRequestN: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.RequestStream, streamId, false, false, false, initialRequestN, payload)

internal fun RequestChannelFrame(streamId: Int, initialRequestN: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.RequestChannel, streamId, false, false, false, initialRequestN, payload)

fun NextPayloadFrame(streamId: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.Payload, streamId, false, false, true, 0, payload)

internal fun CompletePayloadFrame(streamId: Int): RequestFrame =
    RequestFrame(FrameType.Payload, streamId, false, true, false, 0, Payload.Empty)

internal fun NextCompletePayloadFrame(streamId: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.Payload, streamId, false, true, true, 0, payload)
