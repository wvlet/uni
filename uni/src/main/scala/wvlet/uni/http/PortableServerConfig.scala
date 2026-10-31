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
  * It holds the settings every backend shares. For backend-specific tuning (Netty transport, Native
  * worker threads, ...) use `NettyServer`, `NativeServer`, or `NodeServer` directly; the backends'
  * own defaults apply to everything not set here.
  */
case class PortableServerConfig(
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
    override val detailedErrors: Boolean = false
) extends HttpServerConfig:

  def withName(name: String): PortableServerConfig = copy(name = name)
  def withHost(host: String): PortableServerConfig = copy(host = host)
  def withPort(port: Int): PortableServerConfig    = copy(port = port)

  def withHandler(handler: HttpHandler): PortableServerConfig = copy(handler =
    RxHttpHandler.fromSync(handler)
  )

  def withHandler(f: Request => Response): PortableServerConfig = copy(handler =
    RxHttpHandler.fromFunction(f)
  )

  def withRxHandler(handler: RxHttpHandler): PortableServerConfig = copy(handler = handler)

  def withRxHandler(f: Request => Rx[Response]): PortableServerConfig = copy(handler =
    RxHttpHandler(f)
  )

  def withFilter(filter: RxHttpFilter): PortableServerConfig = copy(filters = filters :+ filter)

  def withFilters(filters: Seq[RxHttpFilter]): PortableServerConfig = copy(filters =
    this.filters ++ filters
  )

  /** Register a WebSocket route; the factory creates a fresh handler per accepted connection. */
  def withWebSocketRoute(path: String)(
      handlerFactory: Request => WebSocketHandler
  ): PortableServerConfig = withWebSocketRoute(path, RxHttpFilter.identity)(handlerFactory)

  /** Register a WebSocket route with a filter that gates the upgrade handshake (e.g. for auth). */
  def withWebSocketRoute(path: String, filter: RxHttpFilter)(
      handlerFactory: Request => WebSocketHandler
  ): PortableServerConfig = copy(webSocketRoutes =
    webSocketRoutes :+ WebSocketRoute(path, handlerFactory, filter)
  )

  /**
    * Allow WebSocket connections from these browser origins (e.g. `https://app.example.com`) in
    * addition to same-origin pages. Pass `"*"` to allow every origin.
    */
  def withAllowedOrigins(origins: String*): PortableServerConfig = copy(allowedOrigins =
    origins.toSeq
  )

  /**
    * Accept only requests whose `Host` header names one of these hosts (port ignored), to prevent
    * DNS rebinding. Entries are exact names or `*.suffix`; `"*"` allows every host.
    */
  def withAllowedHosts(hosts: String*): PortableServerConfig = copy(allowedHosts = hosts.toSeq)

  /** Accept every `Host` header. Exposes a loopback server to DNS rebinding from web pages. */
  def noHostCheck: PortableServerConfig = withAllowedHosts("*")

  def withDetailedErrors(enabled: Boolean): PortableServerConfig = copy(detailedErrors = enabled)
  def noDetailedErrors: PortableServerConfig                     = withDetailedErrors(false)

  /**
    * Start the server on this platform's backend. On the JVM this fails with
    * `IllegalStateException` when no backend (uni-netty) is on the classpath. On Scala.js the bind
    * is asynchronous: use [[startAndAwait]] or `whenReady` before reading `localPort`.
    */
  override def start(): HttpServer = HttpCompat.startServer(this)

end PortableServerConfig
