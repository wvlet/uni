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

import wvlet.uni.http.{HttpServer, HttpServerProvider, HttpServerConfig}

/**
  * Serves [[wvlet.uni.http.Http.server]] with Netty on the JVM. Registered through ServiceLoader.
  */
class NettyServerProvider extends HttpServerProvider:
  override def start(config: HttpServerConfig): HttpServer =
    val base = config
      .backends
      .reverseIterator
      .collectFirst { case b: NettyServerConfig =>
        b
      }
      .getOrElse(NettyServerConfig())
    base
      .copy(
        name = config.name,
        host = config.host,
        port = config.port,
        handler = config.handler,
        filters = config.filters,
        webSocketRoutes = config.webSocketRoutes,
        allowedOrigins = config.allowedOrigins,
        allowedHosts = config.allowedHosts,
        detailedErrors = config.detailedErrors,
        maxContentLength = config.maxContentLength.getOrElse(base.maxContentLength),
        webSocketMaxFrameSize = config.webSocketMaxFrameSize.getOrElse(base.webSocketMaxFrameSize),
        webSocketPingIntervalMillis = config
          .webSocketPingIntervalMillis
          .getOrElse(base.webSocketPingIntervalMillis)
      )
      .start()
