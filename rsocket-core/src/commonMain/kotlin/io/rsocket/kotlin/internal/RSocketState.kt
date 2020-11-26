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

package io.rsocket.kotlin.internal

import io.rsocket.kotlin.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.flow.*
import io.rsocket.kotlin.keepalive.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

@OptIn(
    InternalCoroutinesApi::class,
    TransportApi::class,
    ExperimentalStreamsApi::class
)
internal class RSocketState(
    private val connection: Connection,
    keepAlive: KeepAlive,
) : Cancelable by connection {
    private val prioritizer = Prioritizer()
    private val requestScope = CoroutineScope(SupervisorJob(job))
    private val scope = CoroutineScope(job)

    val receivers: IntMap<Channel<RequestFrame>> = IntMap()
    private val senders: IntMap<Job> = IntMap()
    private val limits: IntMap<LimitingFlowCollector> = IntMap()

    private val keepAliveHandler = KeepAliveHandler(keepAlive, this::sendPrioritized)

    fun send(frame: Frame) {
        prioritizer.send(frame)
    }

    fun sendPrioritized(frame: Frame) {
        prioritizer.sendPrioritized(frame)
    }

    fun createReceiverFor(streamId: Int, initFrame: RequestFrame? = null): ReceiveChannel<RequestFrame> {
        val receiver = SafeChannel<RequestFrame>(Channel.UNLIMITED)
        initFrame?.let(receiver::offer) //used only in RequestChannel on responder side
        receivers[streamId] = receiver
        return receiver
    }

    inline fun <R> consumeReceiverFor(streamId: Int, block: () -> R): R {
        var cause: Throwable? = null
        try {
            return block()
        } catch (e: Throwable) {
            cause = e
            throw e
        } finally {
            if (isActive && streamId in receivers) {
                if (cause != null) send(CancelFrame(streamId))
                receivers.remove(streamId)?.close(cause)
            }
        }
    }

    suspend fun collectStream(
        streamId: Int,
        receiver: ReceiveChannel<RequestFrame>,
        strategy: RequestStrategy.Element,
        collector: FlowCollector<Payload>,
    ): Unit = consumeReceiverFor(streamId) {
        //TODO fragmentation
        for (frame in receiver) {
            if (frame.complete) return //TODO check next flag
            collector.emit(frame.payload)
            val next = strategy.nextRequest()
            if (next > 0) send(RequestNFrame(streamId, next))
        }
    }

    suspend inline fun Flow<Payload>.collectLimiting(
        streamId: Int,
        limitingCollector: LimitingFlowCollector,
    ): Unit = coroutineScope {
        limits[streamId] = limitingCollector
        try {
            collect(limitingCollector)
        } catch (e: Throwable) {
            limits.remove(streamId)
            //if isn't active, then, that stream was cancelled, and so no need for error frame
            if (isActive) send(ErrorFrame(streamId, e))
            cancel("Collect failed", e) //KLUDGE: can be related to IR, because using `throw` fails on JS IR and Native
        }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = requestScope.launch(block = block)

    fun launchCancelable(streamId: Int, block: suspend CoroutineScope.() -> Unit): Job {
        val job = launch(block)
        job.invokeOnCompletion { if (isActive) senders.remove(streamId) }
        senders[streamId] = job
        return job
    }

    private fun handleFrame(responder: RSocketResponder, frame: Frame) {
        when (val streamId = frame.streamId) {
            0    -> when (frame) {
                is ErrorFrame        -> {
                    cancel("Zero stream error", frame.throwable)
                    frame.release() //TODO
                }
                is KeepAliveFrame    -> keepAliveHandler.receive(frame)
                is LeaseFrame        -> {
                    frame.release()
                    error("lease isn't implemented")
                }

                is MetadataPushFrame -> responder.handleMetadataPush(frame)
                else                 -> {
                    //TODO log
                    frame.release()
                }
            }
            else -> when (frame) {
                is RequestNFrame -> limits[streamId]?.updateRequests(frame.requestN)
                is CancelFrame   -> senders.remove(streamId)?.cancel()
                is ErrorFrame    -> {
                    receivers.remove(streamId)?.close(frame.throwable)
                    frame.release()
                }
                is RequestFrame  -> when (frame.type) {
                    FrameType.Payload         -> receivers[streamId]?.safeOffer(frame) ?: frame.release()
                    FrameType.RequestFnF      -> responder.handleFireAndForget(frame)
                    FrameType.RequestResponse -> responder.handlerRequestResponse(frame)
                    FrameType.RequestStream   -> responder.handleRequestStream(frame)
                    FrameType.RequestChannel  -> responder.handleRequestChannel(frame)
                    else                      -> error("never happens")
                }
                else             -> {
                    //TODO log
                    frame.release()
                }
            }
        }
    }

    fun start(requestHandler: RSocket) {
        val responder = RSocketResponder(this, requestHandler)
        keepAliveHandler.startIn(scope)
        requestHandler.job.invokeOnCompletion { cancel("Request handled stopped", it) }
        job.invokeOnCompletion { error ->
            requestHandler.cancel("Connection closed", error)
            receivers.values().forEach {
                it.close((error as? CancellationException)?.cause ?: error)
            }
            receivers.clear()
            limits.clear()
            senders.clear()
            prioritizer.close(error)
        }
        scope.launch {
            while (connection.isActive) {
                val frame = prioritizer.receive()
                frame.closeOnError { connection.sendFrame(frame) }
            }
        }
        scope.launch {
            while (connection.isActive) {
                val frame = connection.receiveFrame()
                frame.closeOnError { handleFrame(responder, frame) }
            }
        }
    }
}
