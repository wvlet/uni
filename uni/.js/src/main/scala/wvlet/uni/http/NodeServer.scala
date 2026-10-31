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
package wvlet.uni.http

import wvlet.uni.rx.Rx

/**
  * Entry point for creating a Node.js-based HTTP server on Scala.js. Deprecated: `Http.server`
  * starts this server on Scala.js.
  *
  * Requires a Node-compatible runtime (Node.js, Bun, Deno) — it uses the built-in `http` module.
  */
@deprecated("Use Http.server", "2026.1.39")
object NodeServer:

  def withPort(port: Int): NodeServerConfig = NodeServerConfig().withPort(port)

  def withHandler(handler: HttpHandler): NodeServerConfig = NodeServerConfig().withHandler(handler)

  def withHandler(f: Request => Response): NodeServerConfig = NodeServerConfig().withHandler(f)

  def withRxHandler(handler: RxHttpHandler): NodeServerConfig = NodeServerConfig().withRxHandler(
    handler
  )

  def withRxHandler(f: Request => Rx[Response]): NodeServerConfig = NodeServerConfig()
    .withRxHandler(f)

/**
  * Configuration for [[NodeHttpServer]] with a builder pattern, mirroring `NettyServerConfig`'s
  * common surface.
  */
case class NodeServerConfig(
    name: String = "node-server",
    // Loopback by default; use withHost("0.0.0.0") to accept connections from other hosts
    host: String = "127.0.0.1",
    port: Int = 8080,
    handler: RxHttpHandler = RxHttpHandler.notFound,
    filters: Seq[RxHttpFilter] = Seq.empty,
    // WebSocket routes, matched by path during the HTTP upgrade handshake
    override val webSocketRoutes: Seq[WebSocketRoute] = Nil,
    // Maximum size (bytes) of an inbound WebSocket message
    webSocketMaxFrameSize: Int = 1024 * 1024,
    // Ping/pong heartbeat interval for WebSocket connections (0 disables it): the server pings an
    // idle connection and closes it if the peer stops responding to pings.
    webSocketPingIntervalMillis: Int = 0,
    // Maximum size (bytes) of a request body; larger requests get 413
    maxContentLength: Int = 1024 * 1024,
    // Browser origins allowed to open WebSocket connections, besides same-origin ("*" allows all)
    override val allowedOrigins: Seq[String] = Nil,
    // Host names accepted in the Host header (DNS rebinding protection). Empty: loopback names for
    // a loopback-bound server, every host otherwise ("*" allows all)
    override val allowedHosts: Seq[String] = Nil,
    // Include exception messages in 500 responses (for development only)
    override val detailedErrors: Boolean = false
) extends HttpServerConfigBase:

  def withName(name: String): NodeServerConfig = copy(name = name)
  def withHost(host: String): NodeServerConfig = copy(host = host)
  def withPort(port: Int): NodeServerConfig    = copy(port = port)

  def withHandler(handler: HttpHandler): NodeServerConfig = copy(handler =
    RxHttpHandler.fromSync(handler)
  )

  def withHandler(f: Request => Response): NodeServerConfig = copy(handler =
    RxHttpHandler.fromFunction(f)
  )

  def withRxHandler(handler: RxHttpHandler): NodeServerConfig = copy(handler = handler)

  def withRxHandler(f: Request => Rx[Response]): NodeServerConfig = copy(handler = RxHttpHandler(f))

  def withFilter(filter: RxHttpFilter): NodeServerConfig = copy(filters = filters :+ filter)

  def withFilters(filters: Seq[RxHttpFilter]): NodeServerConfig = copy(filters =
    this.filters ++ filters
  )

  /** Register a WebSocket route; the factory creates a fresh handler per accepted connection. */
  def withWebSocketRoute(path: String)(
      handlerFactory: Request => WebSocketHandler
  ): NodeServerConfig = withWebSocketRoute(path, RxHttpFilter.identity)(handlerFactory)

  /** Register a WebSocket route with a filter that gates the upgrade handshake (e.g. for auth). */
  def withWebSocketRoute(path: String, filter: RxHttpFilter)(
      handlerFactory: Request => WebSocketHandler
  ): NodeServerConfig = copy(webSocketRoutes =
    webSocketRoutes :+ WebSocketRoute(path, handlerFactory, filter)
  )

  def withWebSocketMaxFrameSize(sizeInBytes: Int): NodeServerConfig =
    require(sizeInBytes > 0, "webSocketMaxFrameSize must be positive")
    copy(webSocketMaxFrameSize = sizeInBytes)

  def withWebSocketPingIntervalMillis(millis: Int): NodeServerConfig =
    require(millis >= 0, "webSocketPingIntervalMillis must be >= 0")
    copy(webSocketPingIntervalMillis = millis)

  def withMaxContentLength(sizeInBytes: Int): NodeServerConfig =
    require(sizeInBytes > 0, "maxContentLength must be positive")
    copy(maxContentLength = sizeInBytes)

  /**
    * Allow WebSocket connections from these browser origins (e.g. `https://app.example.com`) in
    * addition to same-origin pages. Pass `"*"` to allow every origin.
    */
  def withAllowedOrigins(origins: String*): NodeServerConfig = copy(allowedOrigins = origins.toSeq)

  /**
    * Accept only requests whose `Host` header names one of these hosts (port ignored), to prevent
    * DNS rebinding. Entries are exact names or `*.suffix`; `"*"` allows every host. Replaces the
    * default, which for a loopback-bound server is `localhost`, `*.localhost`, `127.0.0.1` and
    * `[::1]`.
    */
  def withAllowedHosts(hosts: String*): NodeServerConfig = copy(allowedHosts = hosts.toSeq)

  /** Accept every `Host` header. Exposes a loopback server to DNS rebinding from web pages. */
  def noHostCheck: NodeServerConfig = withAllowedHosts("*")

  def withDetailedErrors(enabled: Boolean): NodeServerConfig = copy(detailedErrors = enabled)
  def noDetailedErrors: NodeServerConfig                     = withDetailedErrors(false)

  /**
    * Start the server and return the running server instance. Note that on Node.js the socket bind
    * is asynchronous: use [[NodeHttpServer.whenReady]] (or [[startAndAwait]]) before reading
    * [[NodeHttpServer.localPort]] or connecting a client, since the OS-assigned port (port 0) is
    * not known until the underlying `listening` event fires.
    */
  override def start(): NodeHttpServer =
    val server = NodeHttpServer(this)
    server.start()
    server

  /**
    * Unsupported on Node.js: the synchronous `start(block)` runs the block before the asynchronous
    * bind completes, so the server would not yet be listening. Use [[startAndAwait]] instead.
    */
  override def start[A](block: HttpServer => A): A =
    throw UnsupportedOperationException(
      "NodeServer binds asynchronously; use startAndAwait { server => Rx[...] } instead of start(block)."
    )

end NodeServerConfig
