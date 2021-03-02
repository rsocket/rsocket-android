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

import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.handler.*

internal class StreamStorage(private val streamId: StreamId) {
    private val handlers: IntMap<FrameHandler> = IntMap()

    fun nextId(): Int = streamId.next(handlers)

    fun save(id: Int, handler: FrameHandler) {
        handlers[id] = handler
    }

    fun remove(id: Int): FrameHandler? {
        return handlers.remove(id)
    }

    fun contains(id: Int): Boolean {
        return id in handlers
    }

    fun cleanup(error: Throwable?) {
        val values = handlers.values()
        handlers.clear()
        values.forEach {
            it.cleanup(error)
        }
    }

    inline fun handleResponderFrame(frame: Frame, block: (RequestFrame) -> ResponderFrameHandler) {
        handleFrame(frame) { handlers[it.streamId] = block(it) }
    }

    fun handleRequesterFrame(frame: Frame) {
        handleFrame(frame, Frame::release)
    }

    private inline fun handleFrame(frame: Frame, onRequestFrame: (RequestFrame) -> Unit) {
        val id = frame.streamId
        when (frame) {
            is RequestNFrame -> handlers[id]?.handleRequestN(frame.requestN)
            is CancelFrame   -> handlers[id]?.handleCancel()
            is ErrorFrame    -> handlers[id]?.handleError(frame.throwable)
            is RequestFrame  -> when (frame.type) {
                FrameType.Payload -> handlers[id]?.handlePayload(frame) ?: frame.release()
                else              -> onRequestFrame(frame)
            }
            else             -> frame.release()
        }
    }
}
