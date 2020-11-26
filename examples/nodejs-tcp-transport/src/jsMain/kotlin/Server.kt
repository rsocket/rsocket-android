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

import io.ktor.utils.io.core.*
import io.ktor.utils.io.js.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.core.*
import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.payload.*
import io.rsocket.kotlin.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import net.*
import org.khronos.webgl.*

fun main() {
    //create transport
    val serverTransport = NodeJsTcpServerTransport(9000) {
        println("Server started")
    }

    //start server
    RSocketServer().bind(serverTransport) {
        RSocketRequestHandler {
            requestResponse {
                println("Received: ${it.data.readText()}")
                buildPayload { data("Hello from nodejs") }
            }
        }
    }
}

//TODO fun interface with lambda fail on IR
@OptIn(TransportApi::class)
fun NodeJsTcpServerTransport(port: Int, onStart: () -> Unit = {}): ServerTransport<Server> = object : ServerTransport<Server> {
    override fun start(accept: suspend (Connection) -> Unit): Server =
        //create nodejs TCP server
        createServer {
            //wrap TCP connection with RSocket connection and start server
            GlobalScope.launch { accept(NodeJsTcpConnection(it)) }
        }.listen(port, onStart)
}

// nodejs TCP transport connection - may not work in all cases, not tested properly
@OptIn(TransportApi::class)
class NodeJsTcpConnection(private val socket: Socket) : Connection {
    override val job: Job = Job()

    private val sendChannel = Channel<ByteReadPacket>(8)
    private val receiveChannel = Channel<ByteReadPacket>(8)

    init {
        //setup closing of job/socket
        socket.on("close") { _ -> job.cancel() }
        job.invokeOnCompletion { if (!socket.writableEnded) socket.end(it?.message ?: "Closed") }

        handleSending()
        handleReceiving()
    }

    // get packets from send channel and put them in socket
    private fun handleSending() {
        GlobalScope.launch(job) {
            sendChannel.consumeEach { packet ->
                val buffer = buildPacket {
                    writeLength(packet.remaining.toInt())
                    writePacket(packet)
                }.readArrayBuffer()
                socket.write(Uint8Array(buffer))
            }
        }
    }

    // get buffers from socket and put them in receive channel
    private fun handleReceiving() {

        fun savePacket(packet: ByteReadPacket) {
            GlobalScope.launch(job) { receiveChannel.send(packet) }
        }

        var expectedFrameLength = 0
        val packetBuilder = BytePacketBuilder()

        fun buildAndSend(from: ByteReadPacket) {
            val packet = buildPacket {
                writePacket(from, expectedFrameLength)
            }
            expectedFrameLength = 0
            savePacket(packet)
        }

        //returns true if length read and awaiting for more data to read frame
        //returns false if no bytes to read length
        fun loopUntilEnoughBytes(): Boolean {
            // loop while packetBuilder has enough bytes to read length or length and frame
            while (expectedFrameLength == 0) {
                if (packetBuilder.size >= 3) {
                    val tempPacket = packetBuilder.build()
                    expectedFrameLength = tempPacket.readLength()

                    //if enough data to read frame
                    if (tempPacket.remaining >= expectedFrameLength) buildAndSend(tempPacket)

                    packetBuilder.writePacket(tempPacket) //write rest back
                } else return false
            }
            return true
        }

        // subscribe on nodejs TCP data events
        socket.on("data") { buffer: Buffer ->
            //put buffer data to packetBuilder to work with it
            packetBuilder.writeFully(buffer.buffer)
            if (!loopUntilEnoughBytes()) return@on

            //if length received and enough data for frame
            if (packetBuilder.size >= expectedFrameLength) {
                val tempPacket = packetBuilder.build()
                buildAndSend(tempPacket)
                packetBuilder.writePacket(tempPacket) //write rest back

                loopUntilEnoughBytes() //if builder has more bytes - handle them
            }
        }
    }

    override suspend fun send(packet: ByteReadPacket) {
        sendChannel.send(packet)
    }

    override suspend fun receive(): ByteReadPacket {
        return receiveChannel.receive()
    }
}
