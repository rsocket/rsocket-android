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

import io.rsocket.kotlin.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.flow.*
import kotlinx.coroutines.*

internal class RSocketResponder(
    private val state: RSocketState,
    private val requestHandler: RSocket,
) : Cancelable by state {

    fun handleMetadataPush(frame: MetadataPushFrame) {
        state.launch {
            requestHandler.metadataPush(frame.metadata)
        }.invokeOnCompletion {
            frame.release()
        }
    }

    fun handleFireAndForget(frame: RequestFrame) {
        state.launch {
            requestHandler.fireAndForget(frame.payload)
        }.invokeOnCompletion {
            frame.release()
        }
    }

    fun handlerRequestResponse(frame: RequestFrame): Unit = with(state) {
        val streamId = frame.streamId
        launchCancelable(streamId) {
            val response = requestOrCancel(streamId) {
                requestHandler.requestResponse(frame.payload)
            } ?: return@launchCancelable
            if (isActive) send(NextCompletePayloadFrame(streamId, response))
        }.invokeOnCompletion {
            frame.release()
        }
    }

    fun handleRequestStream(initFrame: RequestFrame): Unit = with(state) {
        val streamId = initFrame.streamId
        launchCancelable(streamId) {
            val response = requestOrCancel(streamId) {
                requestHandler.requestStream(initFrame.payload)
            } ?: return@launchCancelable
            response.collectLimiting(
                streamId,
                RequestStreamResponderFlowCollector(state, streamId, initFrame.initialRequest)
            )
            send(CompletePayloadFrame(streamId))
        }.invokeOnCompletion {
            initFrame.release()
        }
    }

    fun handleRequestChannel(initFrame: RequestFrame): Unit = with(state) {
        val streamId = initFrame.streamId
        val receiver = createReceiverFor(streamId, initFrame)

        val request = RequestChannelResponderFlow(streamId, receiver, state)

        launchCancelable(streamId) {
            val response = requestOrCancel(streamId) {
                requestHandler.requestChannel(request)
            } ?: return@launchCancelable
            response.collectLimiting(
                streamId,
                RequestStreamResponderFlowCollector(state, streamId, initFrame.initialRequest)
            )
            send(CompletePayloadFrame(streamId))
        }.invokeOnCompletion {
            initFrame.release()
            if (it != null) receiver.cancelConsumed(it) //TODO check it
        }
    }

    private inline fun <T : Any> CoroutineScope.requestOrCancel(streamId: Int, block: () -> T): T? =
        try {
            block()
        } catch (e: Throwable) {
            if (isActive) {
                state.send(ErrorFrame(streamId, e))
                cancel("Request handling failed", e) //KLUDGE: can be related to IR, because using `throw` fails on JS IR and Native
            }
            null
        }

}
