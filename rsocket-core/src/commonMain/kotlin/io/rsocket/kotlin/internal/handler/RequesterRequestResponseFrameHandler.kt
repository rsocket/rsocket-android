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

internal class RequesterRequestResponseFrameHandler(
    private val id: Int,
    private val streamStorage: StreamStorage,
    private val deferred: CompletableDeferred<Payload>
) : RequesterFrameHandler {
    override fun handleNext(payload: Payload) {
        deferred.complete(payload)
    }

    override fun handleComplete() {
        //ignore
    }

    override fun handleError(cause: Throwable) {
        streamStorage.remove(id)
        deferred.completeExceptionally(cause)
    }

    override fun cleanup(cause: Throwable?) {
        deferred.cancel("Connection closed", cause)
    }

    override fun onReceiveComplete() {
        streamStorage.remove(id)
    }

    override fun onReceiveCancelled(cause: Throwable): Boolean = streamStorage.remove(id) != null
}
