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

import wvlet.uni.http.{
  HttpHandler,
  HttpServerConfigBase,
  Request,
  Response,
  RxHttpFilter,
  RxHttpHandler,
  WebSocketHandler,
  WebSocketRoute
}
import wvlet.uni.rx.Rx

import java.net.InetSocketAddress

/**
  * Entry point for creating a Netty-based HTTP server
  */
object NettyServer:

  def withPort(port: Int): NettyServerConfig = NettyServerConfig().withPort(port)

  def withHandler(handler: HttpHandler): NettyServerConfig = NettyServerConfig().withHandler(
    handler
  )

  def withHandler(f: Request => Response): NettyServerConfig = NettyServerConfig().withHandler(f)

  def withRxHandler(handler: RxHttpHandler): NettyServerConfig = NettyServerConfig().withRxHandler(
    handler
  )

  def withRxHandler(f: Request => Rx[Response]): NettyServerConfig = NettyServerConfig()
    .withRxHandler(f)

/**
  * Configuration for NettyServer with builder pattern
  */
case class NettyServerConfig(
    name: String = "netty-server",
    // Loopback by default; use withHost("0.0.0.0") to accept connections from other hosts
    host: String = "127.0.0.1",
    port: Int = 8080,
    handler: RxHttpHandler = RxHttpHandler.notFound,
    filters: Seq[RxHttpFilter] = Seq.empty,
    // Maximum size of an aggregated request body, after request decompression
    maxContentLength: Int = 65536,
    maxInitialLineLength: Int = 4096,
    maxHeaderSize: Int = 8192,
    useNativeTransport: Boolean = true,
    // Graceful shutdown configuration
    shutdownQuietPeriodSeconds: Long = 2,
    shutdownTimeoutSeconds: Long = 30,
    // Whether to register a JVM shutdown hook for SIGTERM/SIGINT handling
    registerShutdownHook: Boolean = false,
    // Number of threads for the handler executor group. When set, request handlers
    // run on a separate thread pool instead of Netty's event loop threads.
    // Set this to match expected concurrent long-running requests (e.g., upstream
    // proxy calls) to prevent them from starving the event loop.
    handlerExecutorThreads: Option[Int] = None,
    // WebSocket routes, matched by path during the HTTP upgrade handshake.
    override val webSocketRoutes: Seq[WebSocketRoute] = Nil,
    // Maximum size (in bytes) of an inbound WebSocket payload. Bounds both a single frame's payload
    // and the aggregated message (continuation frames are coalesced).
    webSocketMaxFrameSize: Int = 1024 * 1024,
    // Ping/pong heartbeat interval for WebSocket connections (0 disables it): the server pings an
    // idle connection and closes it if the peer stops responding to pings.
    webSocketPingIntervalMillis: Int = 0,
    // Close a connection that does not deliver a complete request within this time, measured from
    // connection open or the end of the previous response (0 disables). Bounds slowloris and idle
    // keep-alive connections.
    requestReadTimeoutMillis: Int = 30000,
    // Decode request bodies sent with Content-Encoding gzip/deflate (and x- aliases) before any
    // filter or handler sees them
    requestDecompression: Boolean = true,
    // Maximum decoded size of a compressed request body; uncompressed requests are not counted
    maxDecompressedRequestSize: Int = 64 * 1024 * 1024,
    // Maximum number of concurrently open Server-Sent Events streams; more get 503. Also bounds the
    // threads consuming the streams.
    maxStreamingConnections: Int = 256,
    // Browser origins allowed to open WebSocket connections, besides same-origin ("*" allows all)
    override val allowedOrigins: Seq[String] = Nil,
    // Host names accepted in the Host header (DNS rebinding protection). Empty: loopback names for
    // a loopback-bound server, every host otherwise ("*" allows all)
    override val allowedHosts: Seq[String] = Nil,
    // Include exception messages in 500 responses (for development only)
    override val detailedErrors: Boolean = false
) extends HttpServerConfigBase:

  def withName(name: String): NettyServerConfig                      = copy(name = name)
  def withHost(host: String): NettyServerConfig                      = copy(host = host)
  def withPort(port: Int): NettyServerConfig                         = copy(port = port)
  def withMaxContentLength(maxContentLength: Int): NettyServerConfig = copy(maxContentLength =
    maxContentLength
  )

  def withMaxInitialLineLength(maxInitialLineLength: Int): NettyServerConfig = copy(
    maxInitialLineLength = maxInitialLineLength
  )

  def withMaxHeaderSize(maxHeaderSize: Int): NettyServerConfig = copy(maxHeaderSize = maxHeaderSize)
  def withUseNativeTransport(useNativeTransport: Boolean): NettyServerConfig = copy(
    useNativeTransport = useNativeTransport
  )

  def noNativeTransport: NettyServerConfig = withUseNativeTransport(false)

  def withShutdownQuietPeriod(seconds: Long): NettyServerConfig =
    require(seconds >= 0, "shutdownQuietPeriodSeconds must be non-negative")
    copy(shutdownQuietPeriodSeconds = seconds)

  def withShutdownTimeout(seconds: Long): NettyServerConfig =
    require(seconds > 0, "shutdownTimeoutSeconds must be positive")
    copy(shutdownTimeoutSeconds = seconds)

  def withGracefulShutdown(
      quietPeriodSeconds: Long = 2,
      timeoutSeconds: Long = 30
  ): NettyServerConfig =
    require(quietPeriodSeconds >= 0, "quietPeriodSeconds must be non-negative")
    require(timeoutSeconds > 0, "timeoutSeconds must be positive")
    copy(shutdownQuietPeriodSeconds = quietPeriodSeconds, shutdownTimeoutSeconds = timeoutSeconds)

  def withShutdownHook: NettyServerConfig = copy(registerShutdownHook = true)
  def noShutdownHook: NettyServerConfig   = copy(registerShutdownHook = false)

  def withHandlerExecutorThreads(threads: Int): NettyServerConfig =
    require(threads > 0, "handlerExecutorThreads must be positive")
    copy(handlerExecutorThreads = Some(threads))

  def withHandler(handler: HttpHandler): NettyServerConfig = copy(handler =
    RxHttpHandler.fromSync(handler)
  )

  def withHandler(f: Request => Response): NettyServerConfig = copy(handler =
    RxHttpHandler.fromFunction(f)
  )

  def withRxHandler(handler: RxHttpHandler): NettyServerConfig = copy(handler = handler)

  def withRxHandler(f: Request => Rx[Response]): NettyServerConfig = copy(handler =
    RxHttpHandler(f)
  )

  def withFilter(filter: RxHttpFilter): NettyServerConfig = copy(filters = filters :+ filter)

  def withFilters(filters: Seq[RxHttpFilter]): NettyServerConfig = copy(filters =
    this.filters ++ filters
  )

  /**
    * Register a WebSocket route at the given path. The handler factory creates a fresh
    * [[WebSocketHandler]] per accepted connection.
    */
  def withWebSocketRoute(path: String)(
      handlerFactory: Request => WebSocketHandler
  ): NettyServerConfig = withWebSocketRoute(path, RxHttpFilter.identity)(handlerFactory)

  /**
    * Register a WebSocket route with a filter that gates the upgrade handshake (e.g. for auth).
    * Returning a non-2xx response (or an empty `Rx`) from the filter rejects the upgrade.
    */
  def withWebSocketRoute(path: String, filter: RxHttpFilter)(
      handlerFactory: Request => WebSocketHandler
  ): NettyServerConfig = copy(webSocketRoutes =
    webSocketRoutes :+ WebSocketRoute(path, handlerFactory, filter)
  )

  def withWebSocketMaxFrameSize(sizeInBytes: Int): NettyServerConfig =
    require(sizeInBytes > 0, "webSocketMaxFrameSize must be positive")
    copy(webSocketMaxFrameSize = sizeInBytes)

  def withWebSocketPingIntervalMillis(millis: Int): NettyServerConfig =
    require(millis >= 0, "webSocketPingIntervalMillis must be >= 0")
    copy(webSocketPingIntervalMillis = millis)

  def withRequestReadTimeoutMillis(millis: Int): NettyServerConfig =
    require(millis >= 0, "requestReadTimeoutMillis must be >= 0")
    copy(requestReadTimeoutMillis = millis)

  def noRequestReadTimeout: NettyServerConfig = withRequestReadTimeoutMillis(0)

  /**
    * Decode request bodies sent with `Content-Encoding: gzip` or `deflate` (and the `x-gzip` /
    * `x-deflate` aliases) before any filter or handler sees them (enabled by default). The decoded
    * size of a compressed request is bounded by [[withMaxDecompressedRequestSize]]; a malformed
    * body gets a plain-text 400 and closes the connection. Other encodings pass through unchanged.
    * To authenticate requests before inflating them, use [[noRequestDecompression]] and decode the
    * body in a filter.
    */
  def withRequestDecompression: NettyServerConfig = copy(requestDecompression = true)
  def noRequestDecompression: NettyServerConfig   = copy(requestDecompression = false)

  /**
    * Maximum decoded size (in bytes) of a compressed request body (default: 64 MiB). A larger body
    * gets a plain-text 413 and closes the connection. Uncompressed requests are not limited by
    * this; the decoded body must also fit in [[withMaxContentLength]].
    */
  def withMaxDecompressedRequestSize(sizeInBytes: Int): NettyServerConfig =
    require(sizeInBytes > 0, "maxDecompressedRequestSize must be positive")
    copy(maxDecompressedRequestSize = sizeInBytes)

  /**
    * Allow WebSocket connections from these browser origins (e.g. `https://app.example.com`) in
    * addition to same-origin pages. Pass `"*"` to allow every origin.
    */
  def withAllowedOrigins(origins: String*): NettyServerConfig = copy(allowedOrigins = origins.toSeq)

  /**
    * Accept only requests whose `Host` header names one of these hosts (port ignored), to prevent
    * DNS rebinding. Entries are exact names or `*.suffix`; `"*"` allows every host. Replaces the
    * default, which for a loopback-bound server is `localhost`, `*.localhost`, `127.0.0.1` and
    * `[::1]`.
    */
  def withAllowedHosts(hosts: String*): NettyServerConfig = copy(allowedHosts = hosts.toSeq)

  /** Accept every `Host` header. Exposes a loopback server to DNS rebinding from web pages. */
  def noHostCheck: NettyServerConfig = withAllowedHosts("*")

  /**
    * Limit the number of concurrently open Server-Sent Events streams; further stream requests get
    * 503. It also bounds the threads that consume the streams' events.
    */
  def withMaxStreamingConnections(n: Int): NettyServerConfig =
    require(n > 0, "maxStreamingConnections must be positive")
    copy(maxStreamingConnections = n)

  def withDetailedErrors(enabled: Boolean): NettyServerConfig = copy(detailedErrors = enabled)
  def noDetailedErrors: NettyServerConfig                     = withDetailedErrors(false)

  /**
    * Start the server and return the running server instance
    */
  override def start(): NettyHttpServer =
    val server = NettyHttpServer(this)
    server.start()
    server

end NettyServerConfig
