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

import io.ktor.client.engine.okhttp.*
import io.ktor.server.jetty.*
import io.ktor.server.netty.*
import io.ktor.server.tomcat.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

//cio client and cio server - default for tests

class CIOWebSocketTransportTest : WebSocketTransportTest(ClientCIO, ServerCIO)

class OkHttpClientWebSocketTransportTest : WebSocketTransportTest(OkHttp, ServerCIO)

class NettyServerWebSocketTransportTest : WebSocketTransportTest(ClientCIO, Netty)

class JettyServerWebSocketTransportTest : WebSocketTransportTest(ClientCIO, Jetty)

class TomcatServerWebSocketTransportTest : WebSocketTransportTest(ClientCIO, Tomcat)
