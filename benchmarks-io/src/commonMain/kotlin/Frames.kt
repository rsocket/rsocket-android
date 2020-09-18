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

import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.payload.*
import kotlin.time.*

fun payload(i: Int): Payload = Payload("data-$i".repeat(1000), "metadata-$i".repeat(1000))

@OptIn(ExperimentalTime::class)
data class Stats(val max: Duration, val min: Duration, val avg: Duration)

@OptIn(ExperimentalTime::class)
fun benchmark() {
    val iterations = 1000

    //just work
    (1..iterations * 10).map {
        NextPayloadFrame(1, payload(it)).toPacket().toFrame()
    }

    //benchmark
    val result = (1..10).map {
        val list = (1..iterations).map {
            val frame = NextPayloadFrame(1, payload(it))
            val (packet, writeDuration) = measureTimedValue { frame.toPacket() }
            val (_, readDuration) = measureTimedValue { packet.toFrame() }
            writeDuration to readDuration
        }

        val writeStats = list.map { it.first }.stats
        val readStats = list.map { it.second }.stats

        println()
        println("Run($it):")
        println("write: $writeStats")
        println("read:  $readStats")
        writeStats to readStats
    }

    println()
    printStats("write", result.map { it.first })
    printStats("read ", result.map { it.second })
}

@OptIn(ExperimentalTime::class)
val List<Duration>.stats
    get() = Stats(
        max = maxOrNull()!!,
        min = minOrNull()!!,
        avg = (sumOf { it.inMilliseconds } / size).milliseconds,
    )

val List<Stats>.maxStats
    get() = Stats(
        max = maxByOrNull { it.max }!!.max,
        min = maxByOrNull { it.min }!!.min,
        avg = maxByOrNull { it.avg }!!.avg,
    )

val List<Stats>.minStats
    get() = Stats(
        max = minByOrNull { it.max }!!.max,
        min = minByOrNull { it.min }!!.min,
        avg = minByOrNull { it.avg }!!.avg,
    )


fun printStats(tag: String, list: List<Stats>) {
    val min = list.minStats
    val max = list.maxStats
    println("Result ranges for '$tag':")
    println("max: (${min.max} ... ${max.max})")
    println("min: (${min.min} ... ${max.min})")
    println("avg: (${min.avg} ... ${max.avg})")
}
