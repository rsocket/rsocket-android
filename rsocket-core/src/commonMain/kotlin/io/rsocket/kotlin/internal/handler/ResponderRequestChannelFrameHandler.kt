package io.rsocket.kotlin.internal.handler

import io.rsocket.kotlin.*
import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*


internal class ResponderRequestChannelFrameHandler(
    private val id: Int,
    private val streamStorage: StreamStorage,
    pool: BufferPool,
    private val responder: RSocketResponder,
    initialRequest: Int
) : ResponderFrameHandler(pool), ReceiveFrameHandler {
    val limiter = Limiter(initialRequest)
    val channel = SafeChannel<Payload>(Channel.UNLIMITED)

    @ExperimentalStreamsApi
    override fun start(payload: Payload): Job = responder.handleRequestChannel(payload, id, this)
    override fun handleNextPayload(payload: Payload) {
//        println("RESPONDER: handleNextPayload")
        channel.offer(payload)
    }

    override fun handleComplete() {
//        println("RESPONDER: handleComplete")
        channel.close()
    }

    override fun handleError(cause: Throwable) {
//        println("RESPONDER: handleError")
        streamStorage.remove(id)
        channel.fullClose(cause)
    }

    override fun handleCancel() {
//        println("RESPONDER: handleCancel")
        streamStorage.remove(id)
        val cancelError = CancellationException("Request cancelled")
        channel.fullClose(cancelError)

        job?.cancel(cancelError)
    }

    override fun handleRequestN(n: Int) {
//        println("RESPONDER: handleRequestN")
        limiter.updateRequests(n)
    }

    override fun cleanup(cause: Throwable?) {
        channel.fullClose(cause)
    }

    override fun onSendComplete() {
//        println("RESPONDER: onHandleComplete [${channel.isClosedForSend}]")
        if (channel.isClosedForSend) streamStorage.remove(id)
    }

    override fun onSendFailed(cause: Throwable): Boolean {
//        println("RESPONDER: onHandleFailed [$cause]")
        val isFailed = streamStorage.remove(id) != null
        if (isFailed) channel.fullClose(cause)
        return isFailed
    }

    override fun onReceiveComplete() {
//        println("RESPONDER: onReceiveComplete [${!job!!.isActive}]")
        if (!job!!.isActive) streamStorage.remove(id)
    }

    override fun onReceiveCancelled(cause: Throwable): Boolean {
//        println("RESPONDER: onReceiveCancelled [$cause]")
        if (!streamStorage.contains(id)) {
            if (job?.isActive == true) job?.cancel("Request handling failed [Error frame]", cause)
        }
        return !job!!.isCancelled
    }
}
