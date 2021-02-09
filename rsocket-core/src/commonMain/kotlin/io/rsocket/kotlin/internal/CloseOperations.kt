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

internal inline fun <T : Closeable, R> T.closeOnError(block: (T) -> R): R {
    try {
        return block(this)
    } catch (e: Throwable) {
        close()
        throw e
    }
}

internal fun ReceiveChannel<*>.cancelConsumed(cause: Throwable?) {
    cancel(cause?.let { it as? CancellationException ?: CancellationException("Channel was consumed, consumer had failed", it) })
}

//TODO Can be removed after fix of https://github.com/Kotlin/kotlinx.coroutines/issues/2435
internal fun ReceiveChannel<Closeable>.closeReceivedElements() {
    try {
        while (true) poll()?.close() ?: break
    } catch (e: Throwable) {
    }
}

@SharedImmutable
private val onUndeliveredCloseable: (Closeable) -> Unit = Closeable::close

@Suppress("FunctionName")
internal fun <E : Closeable> SafeChannel(capacity: Int): Channel<E> = Channel(capacity, onUndeliveredElement = onUndeliveredCloseable)

//TODO check after fix of https://github.com/Kotlin/kotlinx.coroutines/issues/2435
// and https://github.com/Kotlin/kotlinx.coroutines/issues/974
internal fun <E : Closeable> SendChannel<E>.safeOffer(element: E) {
    try {
        if (!offer(element)) element.close()
    } catch (cause: Throwable) {
        element.close()
        throw cause
    }
}

internal fun Channel<out Closeable>.fullClose(cause: Throwable?) {
    closeReceivedElements()
    close(cause)
    cancelConsumed(cause)
}
