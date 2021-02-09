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

package io.rsocket.kotlin.core

import io.rsocket.kotlin.logging.*
import kotlinx.coroutines.*

public class RSocketServerBuilder internal constructor() {
    public var loggerFactory: LoggerFactory = DefaultLoggerFactory
    public var maxFragmentSize: Int = 0
        set(value) {
            require(value == 0 || value >= 64) {
                "maxFragmentSize should be zero (no fragmentation) or greater than or equal to 64, but was $value"
            }
            field = value
        }
    public var connectionBuffer: Int = 64
        set(value) {
            require(value >= 0)
            field = value
        }

    //    Dispatchers.Unconfined //NATIVE
    public var requestDispatcher: CoroutineDispatcher = Dispatchers.Default

    private val interceptors: InterceptorsBuilder = InterceptorsBuilder()

    public fun interceptors(configure: InterceptorsBuilder.() -> Unit) {
        interceptors.configure()
    }

    internal fun build(): RSocketServer = RSocketServer(
        loggerFactory = loggerFactory,
        maxFragmentSize = maxFragmentSize,
        connectionBuffer = connectionBuffer,
        requestDispatcher = requestDispatcher,
        interceptors = interceptors.build()
    )
}

public fun RSocketServer(configure: RSocketServerBuilder.() -> Unit = {}): RSocketServer {
    val builder = RSocketServerBuilder()
    builder.configure()
    return builder.build()
}
