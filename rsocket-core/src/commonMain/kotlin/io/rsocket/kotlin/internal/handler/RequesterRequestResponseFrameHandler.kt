package io.rsocket.kotlin.internal.handler

import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*

internal class RequesterRequestResponseFrameHandler(
    private val id: Int,
    private val streamStorage: StreamStorage,
    pool: BufferPool,
    private val deferred: CompletableDeferred<Payload>
) : RequesterFrameHandler(pool) {
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
