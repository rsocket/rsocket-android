package io.rsocket.kotlin.internal.handler

import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*

internal class ResponderFireAndForgetFrameHandler(
    private val id: Int,
    private val streamStorage: StreamStorage,
    pool: BufferPool,
    private val responder: RSocketResponder,
) : ResponderFrameHandler(pool) {

    override fun start(payload: Payload): Job = responder.handleFireAndForget(payload, this)

    override fun handleCancel() {
        streamStorage.remove(id)
        job?.cancel()
    }

    override fun handleRequestN(n: Int) {
        //ignore
    }

    override fun cleanup(cause: Throwable?) {
        //ignore
    }

    override fun onSendComplete() {
        streamStorage.remove(id)
    }

    override fun onSendFailed(cause: Throwable): Boolean = false
}
