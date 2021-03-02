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

import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

internal class RequesterRequestChannelFrameHandler(
    private val id: Int,
    private val streamStorage: StreamStorage,
    private val limiter: Limiter,
    private val sender: Job,
    private val channel: Channel<Payload>,
) : RequesterFrameHandler, SendFrameHandler {

    override fun handleNext(payload: Payload) {
        channel.offer(payload)
    }

    override fun handleComplete() {
        channel.close()
    }

    override fun handleError(cause: Throwable) {
        streamStorage.remove(id)
        channel.fullClose(cause)
        sender.cancel("Request failed", cause)
    }

    override fun handleCancel() {
        sender.cancel()
    }

    override fun handleRequestN(n: Int) {
        limiter.updateRequests(n)
    }

    override fun cleanup(cause: Throwable?) {
        channel.fullClose(cause)
        sender.cancel("Connection closed", cause)
    }

    override fun onReceiveComplete() {
        if (!sender.isActive) streamStorage.remove(id)
    }

    override fun onReceiveCancelled(cause: Throwable): Boolean {
        val isCancelled = streamStorage.remove(id) != null
        if (isCancelled) sender.cancel("Request cancelled", cause)
        return isCancelled
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onSendComplete() {
        if (channel.isClosedForSend) streamStorage.remove(id)
    }

    override fun onSendFailed(cause: Throwable): Boolean {
        if (sender.isCancelled) return false

        val isFailed = streamStorage.remove(id) != null
        if (isFailed) channel.fullClose(cause)
        return isFailed
    }
}
