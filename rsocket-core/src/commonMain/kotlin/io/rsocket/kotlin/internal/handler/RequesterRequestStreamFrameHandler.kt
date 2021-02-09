package io.rsocket.kotlin.internal.handler

import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.channels.*

internal class RequesterRequestStreamFrameHandler(
    private val id: Int,
    private val streamStorage: StreamStorage,
    pool: BufferPool,
    private val channel: Channel<Payload>
) : RequesterFrameHandler(pool) {

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

    override fun cleanup(cause: Throwable?) {
        channel.fullClose(cause)
    }

    override fun onReceiveComplete() {
        streamStorage.remove(id)
    }

    override fun onReceiveCancelled(cause: Throwable): Boolean = streamStorage.remove(id) != null
}
