/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.http.netty

import wvlet.uni.http.{HttpServer, WebSocketRelaySpec, WebSocketRoute}

/** Readable rejections and the relay on the Netty server, with the JDK client. */
class NettyWebSocketRelayTest extends WebSocketRelaySpec:
  override protected def startServer(routes: Seq[WebSocketRoute]): HttpServer = routes
    .foldLeft(
      NettyServerConfig()
        .withPort(0)
        .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
    )(_.withWebSocketRoute(_))
    .start()
