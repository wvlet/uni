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
  * An HTTP server configuration that runs unchanged on every platform: `start()` serves it with
  * Netty on the JVM (from uni-netty, found on the classpath), `NativeServer` on Scala Native, and
  * `NodeServer` on Scala.js. Create one with [[Http.server]].
  *
  * It holds the settings every backend shares. For backend-only options, pass a [[ServerBackend]]
  * to [[withBackend]], e.g. from a JVM-only source file or a platform's `Design`:
  * {{{
  * Http.server.withPort(8080).withBackend(NettyServerConfig().withHandlerExecutorThreads(8))
  * }}}
  */
case class HttpServerConfig(
    name: String = "http-server",
    // Loopback by default; use withHost("0.0.0.0") to accept connections from other hosts
    host: String = "127.0.0.1",
    port: Int = 8080,
    handler: RxHttpHandler = RxHttpHandler.notFound,
    filters: Seq[RxHttpFilter] = Seq.empty,
    // WebSocket routes, matched by path during the HTTP upgrade handshake
    override val webSocketRoutes: Seq[WebSocketRoute] = Nil,
    // Browser origins allowed to open WebSocket connections, besides same-origin ("*" allows all)
    override val allowedOrigins: Seq[String] = Nil,
    // Host names accepted in the Host header (DNS rebinding protection). Empty: loopback names for
    // a loopback-bound server, every host otherwise ("*" allows all)
    override val allowedHosts: Seq[String] = Nil,
    // Include exception messages in 500 responses (for development only)
    override val detailedErrors: Boolean = false,
    // Maximum size (bytes) of a request body. None: the backend's default
    maxContentLength: Option[Int] = None,
    // Maximum size (bytes) of an inbound WebSocket message. None: the backend's default
    webSocketMaxFrameSize: Option[Int] = None,
    // Ping/pong heartbeat interval for WebSocket connections (0 disables it). None: the backend's
    // default
    webSocketPingIntervalMillis: Option[Int] = None,
    // Backend configs for backend-only options; each backend uses the one of its own type
    backends: Seq[ServerBackend] = Nil
) extends HttpServerConfigBase:

  def withName(name: String): HttpServerConfig = copy(name = name)
  def withHost(host: String): HttpServerConfig = copy(host = host)
  def withPort(port: Int): HttpServerConfig    = copy(port = port)

  def withHandler(handler: HttpHandler): HttpServerConfig = copy(handler =
    RxHttpHandler.fromSync(handler)
  )

  def withHandler(f: Request => Response): HttpServerConfig = copy(handler =
    RxHttpHandler.fromFunction(f)
  )

  def withRxHandler(handler: RxHttpHandler): HttpServerConfig = copy(handler = handler)

  def withRxHandler(f: Request => Rx[Response]): HttpServerConfig = copy(handler = RxHttpHandler(f))

  def withFilter(filter: RxHttpFilter): HttpServerConfig = copy(filters = filters :+ filter)

  def withFilters(filters: Seq[RxHttpFilter]): HttpServerConfig = copy(filters =
    this.filters ++ filters
  )

  /** Register a WebSocket route; the factory creates a fresh handler per accepted connection. */
  def withWebSocketRoute(path: String)(
      handlerFactory: Request => WebSocketHandler
  ): HttpServerConfig = withWebSocketRoute(path, RxHttpFilter.identity)(handlerFactory)

  /** Register a WebSocket route with a filter that gates the upgrade handshake (e.g. for auth). */
  def withWebSocketRoute(path: String, filter: RxHttpFilter)(
      handlerFactory: Request => WebSocketHandler
  ): HttpServerConfig = copy(webSocketRoutes =
    webSocketRoutes :+ WebSocketRoute(path, handlerFactory, filter)
  )

  /**
    * Allow WebSocket connections from these browser origins (e.g. `https://app.example.com`) in
    * addition to same-origin pages. Pass `"*"` to allow every origin.
    */
  def withAllowedOrigins(origins: String*): HttpServerConfig = copy(allowedOrigins = origins.toSeq)

  /**
    * Accept only requests whose `Host` header names one of these hosts (port ignored), to prevent
    * DNS rebinding. Entries are exact names or `*.suffix`; `"*"` allows every host.
    */
  def withAllowedHosts(hosts: String*): HttpServerConfig = copy(allowedHosts = hosts.toSeq)

  /** Accept every `Host` header. Exposes a loopback server to DNS rebinding from web pages. */
  def noHostCheck: HttpServerConfig = withAllowedHosts("*")

  def withDetailedErrors(enabled: Boolean): HttpServerConfig = copy(detailedErrors = enabled)
  def noDetailedErrors: HttpServerConfig                     = withDetailedErrors(false)

  /**
    * Limit a request body to this many bytes; larger requests are rejected. On Scala Native the
    * limit covers the request headers too.
    */
  def withMaxContentLength(bytes: Int): HttpServerConfig =
    require(bytes > 0, "maxContentLength must be positive")
    copy(maxContentLength = Some(bytes))

  def withWebSocketMaxFrameSize(sizeInBytes: Int): HttpServerConfig =
    require(sizeInBytes > 0, "webSocketMaxFrameSize must be positive")
    copy(webSocketMaxFrameSize = Some(sizeInBytes))

  def withWebSocketPingIntervalMillis(millis: Int): HttpServerConfig =
    require(millis >= 0, "webSocketPingIntervalMillis must be >= 0")
    copy(webSocketPingIntervalMillis = Some(millis))

  /**
    * Use this backend config for backend-only options (Netty transport, Native worker threads,
    * ...). The backend of its type starts from it instead of its own defaults, then applies this
    * config's settings on top: name, host, port, handler, filters, WebSocket routes, allowed
    * origins and hosts, and detailed errors always come from here, and the size limits when set
    * here. Other backends ignore it, so you can pass one config per platform; for the same type,
    * the last wins.
    */
  def withBackend(backend: ServerBackend): HttpServerConfig = copy(backends = backends :+ backend)

  /**
    * Start the server on this platform's backend. On the JVM this fails with
    * `IllegalStateException` when no backend (uni-netty) is on the classpath. On Scala.js the bind
    * is asynchronous: use [[startAndAwait]] or `whenReady` before reading `localPort`.
    */
  override def start(): HttpServer = HttpCompat.startServer(this)

end HttpServerConfig
