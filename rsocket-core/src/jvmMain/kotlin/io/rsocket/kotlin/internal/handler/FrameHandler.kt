package io.rsocket.kotlin.internal.handler

import io.rsocket.kotlin.frame.io.*
import kotlinx.coroutines.*

internal actual abstract class ResponderFrameHandler
actual constructor(pool: BufferPool) : BaseResponderFrameHandler(pool) {
    actual override var hasMetadata: Boolean = false
    actual override var job: Job? = null
}

internal actual abstract class RequesterFrameHandler
actual constructor(pool: BufferPool) : BaseRequesterFrameHandler(pool) {
    actual override var hasMetadata: Boolean = false
}
