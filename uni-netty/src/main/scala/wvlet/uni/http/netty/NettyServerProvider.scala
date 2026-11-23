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
  override def start(config: HttpServerConfig): HttpServer = NettyServerProvider
    .nettyConfig(config)
    .start()

object NettyServerProvider:

  /** The Netty config for `Http.server`'s settings, which win over the `Netty.options`. */
  private[netty] def nettyConfig(config: HttpServerConfig): NettyServerConfig =
    val base =
      config
        .backendOptions
        .collect { case o: NettyOptions =>
          o
        }
        .foldLeft(NettyServerConfig())((c, o) => o.applyTo(c))
    base.copy(
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
        .getOrElse(base.webSocketPingIntervalMillis),
      maxInitialLineLength = config.maxInitialLineLength.getOrElse(base.maxInitialLineLength),
      maxHeaderSize = config.maxHeaderSize.getOrElse(base.maxHeaderSize),
      requestReadTimeoutMillis = config
        .requestReadTimeoutMillis
        .getOrElse(base.requestReadTimeoutMillis),
      writeTimeoutMillis = config.writeTimeoutMillis.getOrElse(base.writeTimeoutMillis),
      idleTimeoutMillis = config.idleTimeoutMillis.orElse(base.idleTimeoutMillis),
      requestDecompression = config.requestDecompression.getOrElse(base.requestDecompression),
      maxDecompressedRequestSize = config
        .maxDecompressedRequestSize
        .getOrElse(base.maxDecompressedRequestSize),
      maxStreamingConnections = config
        .maxStreamingConnections
        .getOrElse(base.maxStreamingConnections),
      // Netty counts the shutdown deadline in whole seconds, and needs at least one
      shutdownTimeoutSeconds = config
        .shutdownTimeoutMillis
        .map(ms => math.max(1L, (ms + 999) / 1000))
        .getOrElse(base.shutdownTimeoutSeconds)
    )

  end nettyConfig

end NettyServerProvider
