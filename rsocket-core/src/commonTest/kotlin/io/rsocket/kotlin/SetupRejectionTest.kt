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

package io.rsocket.kotlin

//class SetupRejectionTest : SuspendTest, TestWithLeakCheck {
//    @Test
//    fun responderRejectSetup() = test {
//        val errorMessage = "error"
//        val sendingRSocket = CompletableDeferred<RSocket>()
//
//        val connection = TestConnection()
//
//        val serverTransport = ServerTransport { accept ->
//            GlobalScope.async { accept(connection) }
//        }
//
//        val deferred = RSocketServer().bind(serverTransport) {
//            sendingRSocket.complete(requester)
//            error(errorMessage)
//        }
//
//        connection.sendToReceiver(SetupFrame(Version.Current, false, KeepAlive(), null, PayloadMimeType(), Payload.Empty))
//
//        assertFailsWith(RSocketError.Setup.Rejected::class, errorMessage) { deferred.await() }
//
//        connection.test {
//            expectFrame { frame ->
//                assertTrue(frame is ErrorFrame)
//                assertTrue(frame.throwable is RSocketError.Setup.Rejected)
//                assertEquals(errorMessage, frame.throwable.message)
//            }
//            val sender = sendingRSocket.await()
//            assertFalse(sender.isActive)
//            val error = expectError()
//            assertTrue(error is RSocketError.Setup.Rejected)
//            assertEquals(errorMessage, error.message)
//        }
//    }
//
////    @Test
////    fun requesterStreamsTerminatedOnZeroErrorFrame() = test {
////        val errorMessage = "error"
////        val connection = TestConnection()
////        val requester = RSocketRequester(connection, StreamId.client(), KeepAlive(), RequestStrategy.Default, {})
////        val deferred = GlobalScope.async { requester.requestResponse(Payload.Empty) }
////        delay(100)
////        connection.sendToReceiver(ErrorFrame(0, RSocketError.ConnectionError(errorMessage)))
////        assertFailsWith<RSocketError.ConnectionError>(errorMessage) { deferred.await() }
////    }
////
////    @Test
////    fun requesterNewStreamsTerminatedAfterZeroErrorFrame() = test {
////        val errorMessage = "error"
////        val connection = TestConnection()
////        val requester = RSocketRequester(connection, StreamId.client(), KeepAlive(), RequestStrategy.Default, {})
////        connection.sendToReceiver(ErrorFrame(0, RSocketError.ConnectionError(errorMessage)))
////        assertFailsWith<RSocketError.ConnectionError>(errorMessage) { requester.requestResponse(Payload.Empty) }
////    }
//}
