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

package io.rsocket.kotlin.internal.handler

import io.rsocket.kotlin.*
import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

internal class ResponderRequestChannelFrameHandler(
    payload: Payload,
    private val id: Int,
    private val streamStorage: StreamStorage,
    private val responder: RSocketResponder,
    initialRequest: Int
) : ResponderFrameHandler, ReceiveFrameHandler {
    val limiter = Limiter(initialRequest)
    val channel = SafeChannel<Payload>(Channel.UNLIMITED)

    @OptIn(ExperimentalStreamsApi::class)
    private val job = responder.handleRequestChannel(payload, id, this)

    override fun handleNext(payload: Payload) {
        channel.offer(payload)
    }

    override fun handleComplete() {
        channel.close()
    }

    override fun handleError(cause: Throwable) {
        streamStorage.remove(id)
        channel.fullClose(cause)
    }

    override fun handleCancel() {
        streamStorage.remove(id)
        val cancelError = CancellationException("Request cancelled")
        channel.fullClose(cancelError)
        job.cancel(cancelError)
    }

    override fun handleRequestN(n: Int) {
        limiter.updateRequests(n)
    }

    override fun cleanup(cause: Throwable?) {
        channel.fullClose(cause)
    }

    override fun onSendComplete() {
        if (channel.isClosedForSend) streamStorage.remove(id)
    }

    override fun onSendFailed(cause: Throwable): Boolean {
        val isFailed = streamStorage.remove(id) != null
        if (isFailed) channel.fullClose(cause)
        return isFailed
    }

    override fun onReceiveComplete() {
        if (!job.isActive) streamStorage.remove(id)
    }

    override fun onReceiveCancelled(cause: Throwable): Boolean {
        if (!streamStorage.contains(id)) {
            if (job.isActive) job?.cancel("Request handling failed [Error frame]", cause)
        }
        return !job.isCancelled
    }
}
