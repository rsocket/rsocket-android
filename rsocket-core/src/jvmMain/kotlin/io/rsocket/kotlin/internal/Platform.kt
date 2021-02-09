package io.rsocket.kotlin.internal

internal actual suspend inline fun fail(cause: Throwable) {
    throw cause
}
