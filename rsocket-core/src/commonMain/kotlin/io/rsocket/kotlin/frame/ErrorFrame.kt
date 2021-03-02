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

package io.rsocket.kotlin.frame

import io.ktor.utils.io.core.*
import io.rsocket.kotlin.*

internal class ErrorFrame(
    override val streamId: Int,
    val throwable: Throwable
) : Frame(FrameType.Error) {
    override val flags: Int get() = 0
    val errorCode get() = (throwable as? RSocketError)?.errorCode ?: ErrorCode.ApplicationError

    override fun release(): Unit = Unit

    override fun BytePacketBuilder.writeSelf() {
        writeInt(errorCode)
        writeText(throwable.message ?: "")
    }

    override fun StringBuilder.appendFlags(): Unit = Unit
    override fun StringBuilder.appendSelf() {
        append("\nError code: ").append(errorCode).append("[").append(throwable::class.simpleName).append("]")
        if (throwable.message != null) append(" Message: ").append(throwable.message)
    }
}

internal fun ByteReadPacket.readError(streamId: Int): ErrorFrame {
    val errorCode = readInt()
    val message = readText()
    return ErrorFrame(streamId, RSocketError(streamId, errorCode, message))
}
