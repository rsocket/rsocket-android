package io.rsocket.kotlin.internal

import kotlinx.coroutines.*
import kotlin.coroutines.*

internal actual suspend inline fun fail(cause: Throwable) {
    coroutineContext.job.cancel("Request failed", cause)
}
