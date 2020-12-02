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

@file:OptIn(ExperimentalTime::class, DangerousInternalIoApi::class)

import io.ktor.utils.io.core.internal.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.payload.*
import kotlin.native.concurrent.*
import kotlin.time.*

private fun dt(t: String, i: Int): String = "$t-$i".repeat(1000)
private fun db(t: String, i: Int): ByteArray = ByteArray(10000) { (i + it + t.length).toByte() }

@SharedImmutable
private val iterations = 1000

@SharedImmutable
private val datas = (0..iterations).map { dt("data", it) }

@SharedImmutable
private val metadatas = (0..iterations).map { dt("metadata", it) }

private inline fun <T> iterate(block: (Int) -> T): List<T> = (1..iterations).map(block)

fun payload(i: Int): Payload = buildPayload {
    data(datas[i])
    metadata(metadatas[i])
}

data class Stats(val max: Duration, val min: Duration, val avg: Duration)

fun benchmark() {

    //just work
    repeat(10) {
        iterate {
            NextPayloadFrame(1, payload(it)).toPacket(ChunkBuffer.Pool).readFrame(ChunkBuffer.Pool)
        }
    }

    //benchmark
    val result = (1..10).map {
        val list = iterate {
            val frame = NextPayloadFrame(1, payload(it))
            val (packet, writeDuration) = measureTimedValue { frame.toPacket(ChunkBuffer.Pool) }
            val (_, readDuration) = measureTimedValue { packet.readFrame(ChunkBuffer.Pool) }
            writeDuration to readDuration
        }

        val writeStats = list.map { it.first }.stats
        val readStats = list.map { it.second }.stats

//        println()
//        println("Run($it):")
//        println("write: $writeStats")
//        println("read:  $readStats")
        writeStats to readStats
    }

    printStats("write", result.map { it.first })
    printStats("read ", result.map { it.second })
    println()
}

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
//    println("max: (${min.max.toLongNanoseconds()} ... ${max.max.toLongNanoseconds()})")
//    println("min: (${min.min.toLongNanoseconds()} ... ${max.min.toLongNanoseconds()})")
    println("avg: (${min.avg.toLongNanoseconds()} ... ${max.avg.toLongNanoseconds()})")
}
