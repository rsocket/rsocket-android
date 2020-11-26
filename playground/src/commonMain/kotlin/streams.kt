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

import io.rsocket.kotlin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

@ExperimentalStreamsApi
private suspend fun s() {
    val flow = flow {
        val strategy = currentCoroutineContext()[RequestStrategy]!!.provide()
        var i = strategy.firstRequest()
        println("INIT: $i")
        var r = 0
        while (i > 0) {
            emit(r++)
            val n = strategy.nextRequest()
            println("")
            if (n > 0) i += n
            i--
        }
    }

    flow.flowOn(PrefetchStrategy(64, 16)).onEach { println(it) }.launchIn(GlobalScope)

    val ch = Channel<Int>()

    flow.flowOn(ChannelStrategy(ch)).onEach { println(it) }.launchIn(GlobalScope)

    delay(100)
    ch.send(5)
    delay(100)
    ch.send(5)
    delay(100)
    ch.send(5)
}
