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

import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.native.concurrent.*

internal inline fun <T> Closeable.closeOnError(block: () -> T): T {
    try {
        return block()
    } catch (e: Throwable) {
        close()
        throw e
    }
}

internal fun ReceiveChannel<*>.cancelConsumed(cause: Throwable?) {
    cancel(cause?.let { it as? CancellationException ?: CancellationException("Channel was consumed, consumer had failed", it) })
}

@SharedImmutable
private val onUndeliveredCloseable: (Closeable) -> Unit = Closeable::close

@Suppress("FunctionName")
internal fun <E : Closeable> SafeChannel(capacity: Int): Channel<E> = Channel(capacity, onUndeliveredElement = onUndeliveredCloseable)

internal fun <E : Closeable> SendChannel<E>.safeOffer(element: E) {
    runCatching { if (!offer(element)) element.close() }.onFailure { element.close() }.getOrThrow()
}
