package io.rsocket.kotlin.internal.handler

import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

internal class RequesterRequestChannelFrameHandler(
    private val id: Int,
    private val streamStorage: StreamStorage,
    pool: BufferPool,
    private val limiter: Limiter,
    private val sender: Job,
    private val channel: Channel<Payload>,
) : RequesterFrameHandler(pool), SendFrameHandler {

    override fun handleNext(payload: Payload) {
//        println("REQUESTER: handleNext")
        channel.offer(payload)
    }

    override fun handleComplete() {
//        println("REQUESTER: handleComplete")
        channel.close()
    }

    override fun handleError(cause: Throwable) {
//        println("REQUESTER: handleError")
        streamStorage.remove(id)
        channel.fullClose(cause)
        sender.cancel("Request failed [Error frame]", cause)
    }

    override fun handleCancel() {
//        println("REQUESTER: handleCancel")
        sender.cancel()
    }

    override fun handleRequestN(n: Int) {
//        println("REQUESTER: handleRequestN")
        limiter.updateRequests(n)
    }

    override fun cleanup(cause: Throwable?) {
        channel.fullClose(cause)
        sender.cancel("Connection closed", cause)
    }

    override fun onReceiveComplete() {
//        println("REQUESTER: onRequestComplete [${!sender.isActive}]")
        if (!sender.isActive) streamStorage.remove(id)
    }

    override fun onReceiveCancelled(cause: Throwable): Boolean {
//        println("REQUESTER: onRequestCancelled [$cause]")
        val isCancelled = streamStorage.remove(id) != null
        if (isCancelled) sender.cancel("Request failed [Request cancelled]", cause)
        return isCancelled
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onSendComplete() {
//        println("REQUESTER: onSendComplete [${channel.isClosedForSend}]")
        if (channel.isClosedForSend) streamStorage.remove(id) //TODO
    }

    //true if need to send error
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onSendFailed(cause: Throwable): Boolean {
//        println("REQUESTER: onSendFailed [$cause]")
        if (sender.isCancelled) return false //cancelled

        val isFailed = streamStorage.remove(id) != null
        if (isFailed) channel.fullClose(cause)
        return isFailed
    }
}
