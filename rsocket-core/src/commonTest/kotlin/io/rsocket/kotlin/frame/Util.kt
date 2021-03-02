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
import io.rsocket.kotlin.test.*
import kotlin.test.*

fun Frame.toPacketWithLength(): ByteReadPacket = buildPacket(InUseTrackingPool) {
    val packet = toPacket(InUseTrackingPool)
    writeLength(packet.remaining.toInt())
    writePacket(packet)
}

fun ByteReadPacket.toFrameWithLength(): Frame {
    val length = readLength()
    assertEquals(length, remaining.toInt())
    return readFrame(InUseTrackingPool)
}

fun Frame.loopFrame(): Frame = toPacket(InUseTrackingPool).readFrame(InUseTrackingPool)

internal fun RequestFireAndForgetFrame(streamId: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.RequestFnF, streamId, false, false, false, 0, payload)

internal fun RequestResponseFrame(streamId: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.RequestResponse, streamId, false, false, false, 0, payload)

internal fun RequestStreamFrame(streamId: Int, initialRequestN: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.RequestStream, streamId, false, false, false, initialRequestN, payload)

internal fun NextPayloadFrame(streamId: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.Payload, streamId, false, false, true, 0, payload)

internal fun CompletePayloadFrame(streamId: Int): RequestFrame =
    RequestFrame(FrameType.Payload, streamId, false, true, false, 0, Payload.Empty)

internal fun NextCompletePayloadFrame(streamId: Int, payload: Payload): RequestFrame =
    RequestFrame(FrameType.Payload, streamId, false, true, true, 0, payload)
