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

import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.payload.*

internal interface FrameHandler {

    fun handleNext(payload: Payload)

    fun handlePayload(frame: RequestFrame) {
        if (frame.next || frame.type.isRequestType) handleNext(frame.payload)
        if (frame.complete) handleComplete()
    }

    fun handleComplete()
    fun handleError(cause: Throwable)
    fun handleCancel()
    fun handleRequestN(n: Int)

    fun cleanup(cause: Throwable?)
}

internal interface RequesterFrameHandler : FrameHandler, ReceiveFrameHandler {
    override fun handleCancel() {
        //should be called only for RC
    }

    override fun handleRequestN(n: Int) {
        //should be called only for RC
    }
}

internal interface ResponderFrameHandler : FrameHandler, SendFrameHandler {

    override fun handleNext(payload: Payload) {
        //should be called only for RC
    }

    override fun handleComplete() {
        //should be called only for RC
    }

    override fun handleError(cause: Throwable) {
        //should be called only for RC
    }
}

internal interface ReceiveFrameHandler {
    fun onReceiveComplete()
    fun onReceiveCancelled(cause: Throwable): Boolean // if true, then request is cancelled
}

internal interface SendFrameHandler {
    fun onSendComplete()
    fun onSendFailed(cause: Throwable): Boolean // if true, then request is failed
}
