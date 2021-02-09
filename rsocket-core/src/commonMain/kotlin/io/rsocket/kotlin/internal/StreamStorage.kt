package io.rsocket.kotlin.internal

import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.handler.*

internal class StreamStorage(private val streamId: StreamId) {
    private val handlers: IntMap<FrameHandler> = IntMap()

    fun nextId(): Int = streamId.next(handlers)

    fun save(id: Int, handler: FrameHandler) {
        handlers[id] = handler
    }

    fun remove(id: Int): FrameHandler? {
        return handlers.remove(id)
    }

    fun contains(id: Int): Boolean {
        return id in handlers
    }

    fun cleanup(error: Throwable?) {
        val values = handlers.values()
        handlers.clear()
        values.forEach {
            it.cleanup(error)
        }
    }

    inline fun handleResponderFrame(frame: Frame, block: (RequestFrame) -> ResponderFrameHandler) {
        handleFrame(frame) { handlers[it.streamId] = block(it) }
    }

    fun handleRequesterFrame(frame: Frame) {
        handleFrame(frame, Frame::release)
    }

    private inline fun handleFrame(frame: Frame, onRequestFrame: (RequestFrame) -> Unit) {
        val id = frame.streamId
        when (frame) {
            is RequestNFrame -> handlers[id]?.handleRequestN(frame.requestN)
            is CancelFrame   -> handlers[id]?.handleCancel()
            is ErrorFrame    -> handlers[id]?.handleError(frame.throwable)
            is RequestFrame  -> when (frame.type) {
                FrameType.Payload -> handlers[id]?.handlePayload(frame) ?: frame.release()
                else              -> onRequestFrame(frame)
            }
            else             -> frame.release()
        }
    }
}
