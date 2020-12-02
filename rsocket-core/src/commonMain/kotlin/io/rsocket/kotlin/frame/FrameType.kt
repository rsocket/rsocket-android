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

import io.rsocket.kotlin.frame.io.*

enum class FrameType(val encodedType: Int, flags: Int = Flags.Empty) {
    Reserved(0x00),

    //CONNECTION
    Setup(0x01, Flags.CanHaveData or Flags.CanHaveMetadata),
    Lease(0x02, Flags.CanHaveMetadata),
    KeepAlive(0x03, Flags.CanHaveData),

    // METADATA
    MetadataPush(0x0C, Flags.CanHaveMetadata),

    //REQUEST
    RequestFnF(0x05, Flags.CanHaveData or Flags.CanHaveMetadata or Flags.Fragmentable or Flags.Request),
    RequestResponse(0x04, Flags.CanHaveData or Flags.CanHaveMetadata or Flags.Fragmentable or Flags.Request),
    RequestStream(0x06, Flags.CanHaveMetadata or Flags.CanHaveData or Flags.HasInitialRequest or Flags.Fragmentable or Flags.Request),
    RequestChannel(0x07, Flags.CanHaveMetadata or Flags.CanHaveData or Flags.HasInitialRequest or Flags.Fragmentable or Flags.Request),

    // DURING REQUEST
    RequestN(0x08),
    Cancel(0x09),

    // RESPONSE
    Payload(0x0A, Flags.CanHaveData or Flags.CanHaveMetadata or Flags.Fragmentable),
    Error(0x0B, Flags.CanHaveData),

    // RESUMPTION
    Resume(0x0D),
    ResumeOk(0x0E),

    Extension(0x3F, Flags.CanHaveData or Flags.CanHaveMetadata);

    val hasInitialRequest: Boolean = flags check Flags.HasInitialRequest
    val isRequestType: Boolean = flags check Flags.Request
    val isFragmentable: Boolean = flags check Flags.Fragmentable
    val canHaveMetadata: Boolean = flags check Flags.CanHaveMetadata
    val canHaveData: Boolean = flags check Flags.CanHaveData

    private object Flags {
        const val Empty = 0
        const val HasInitialRequest = 1
        const val Request = 2
        const val Fragmentable = 4
        const val CanHaveMetadata = 8
        const val CanHaveData = 16
    }

    companion object {
        private val encodedTypes: Array<FrameType?>

        init {
            val maximumEncodedType = values().map(FrameType::encodedType).maxOrNull() ?: 0
            encodedTypes = arrayOfNulls(maximumEncodedType + 1)
            values().forEach { encodedTypes[it.encodedType] = it }
        }

        operator fun invoke(encodedType: Int): FrameType =
            encodedTypes[encodedType] ?: throw IllegalArgumentException("Frame type $encodedType is unknown")
    }
}
