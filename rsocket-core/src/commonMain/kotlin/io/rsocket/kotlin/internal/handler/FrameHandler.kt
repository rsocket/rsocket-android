package io.rsocket.kotlin.internal.handler

import io.ktor.utils.io.core.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*

internal expect abstract class ResponderFrameHandler(pool: BufferPool) : BaseResponderFrameHandler {
    override var hasMetadata: Boolean
    override var job: Job?
}

internal expect abstract class RequesterFrameHandler(pool: BufferPool) : BaseRequesterFrameHandler {
    override var hasMetadata: Boolean
}

internal abstract class FrameHandler(pool: BufferPool) {
    private val data = BytePacketBuilder(0, pool)
    private val metadata = BytePacketBuilder(0, pool)
    protected abstract var hasMetadata: Boolean

    private fun handleNextFragment(frame: RequestFrame) {
        data.writePacket(frame.payload.data)
        when (val meta = frame.payload.metadata) {
            null -> Unit
            else -> {
                hasMetadata = true
                metadata.writePacket(meta)
            }
        }
        if (frame.follows && !frame.complete) return

        val payload = Payload(data.build(), if (hasMetadata) metadata.build() else null)
        hasMetadata = false
        handleNext(payload)
    }

    protected abstract fun handleNext(payload: Payload)

    fun handlePayload(frame: RequestFrame) {
        if (frame.next || frame.type.isRequestType) handleNextFragment(frame)
        if (frame.complete) handleComplete()
    }

    abstract fun handleComplete()
    abstract fun handleError(cause: Throwable)
    abstract fun handleCancel()
    abstract fun handleRequestN(n: Int)

    abstract fun cleanup(cause: Throwable?)
}

internal abstract class BaseRequesterFrameHandler(pool: BufferPool) : FrameHandler(pool), ReceiveFrameHandler {
    override fun handleCancel() {
        //should be called only for RC
    }

    override fun handleRequestN(n: Int) {
        //should be called only for RC
    }
}

internal abstract class BaseResponderFrameHandler(pool: BufferPool) : FrameHandler(pool), SendFrameHandler {
    protected abstract var job: Job?

    protected abstract fun start(payload: Payload): Job

    override fun handleNext(payload: Payload) {
        if (job == null) job = start(payload)
        else handleNextPayload(payload)
    }

    protected open fun handleNextPayload(payload: Payload) {
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
