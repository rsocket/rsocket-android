package io.rsocket.kotlin.internal

import io.rsocket.kotlin.*
import io.rsocket.kotlin.payload.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

@ExperimentalStreamsApi
internal inline fun requestFlow(
    crossinline block: suspend FlowCollector<Payload>.(strategy: RequestStrategy.Element, initialRequest: Int) -> Unit
): Flow<Payload> = object : RequestFlow() {
    override suspend fun collect(collector: FlowCollector<Payload>, strategy: RequestStrategy.Element, initialRequest: Int) {
        collector.block(strategy, initialRequest)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@ExperimentalStreamsApi
internal suspend inline fun FlowCollector<Payload>.emitAllWithRequestN(
    channel: ReceiveChannel<Payload>,
    strategy: RequestStrategy.Element,
    crossinline onRequest: suspend (n: Int) -> Unit,
) {
    val collector = object : RequestFlowCollector(this, strategy) {
        override suspend fun onRequest(n: Int) {
            if (!channel.isClosedForReceive) onRequest(n)
        }
    }
    collector.emitAll(channel)
}

@ExperimentalStreamsApi
internal abstract class RequestFlow : Flow<Payload> {
    private val consumed = atomic(false)

    @InternalCoroutinesApi
    override suspend fun collect(collector: FlowCollector<Payload>) {
        check(!consumed.getAndSet(true)) { "RequestFlow can be collected just once" }

        val strategy = currentCoroutineContext().requestStrategy()
        val initial = strategy.firstRequest()
        collect(collector, strategy, initial)
    }

    abstract suspend fun collect(collector: FlowCollector<Payload>, strategy: RequestStrategy.Element, initialRequest: Int)
}

@ExperimentalStreamsApi
internal abstract class RequestFlowCollector(
    private val collector: FlowCollector<Payload>,
    private val strategy: RequestStrategy.Element,
) : FlowCollector<Payload> {
    override suspend fun emit(value: Payload): Unit = value.closeOnError {
        collector.emit(value)
        val next = strategy.nextRequest()
        if (next > 0) onRequest(next)
    }

    abstract suspend fun onRequest(n: Int)
}
