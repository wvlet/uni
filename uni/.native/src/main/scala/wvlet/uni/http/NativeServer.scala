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
  * Entry point for creating a Scala Native HTTP server. Deprecated: `Http.server` starts this
  * server on Scala Native, and `withBackend(Native.options...)` sets Native-only options.
  *
  * The server is built directly on POSIX TCP sockets (no external dependency): a daemon accept loop
  * dispatches each connection to a worker thread pool, where requests are parsed, run through the
  * handler, and answered with HTTP/1.1. Between requests, a keep-alive connection waits in an
  * epoll/kqueue poller and holds no worker (on Windows it keeps its worker).
  *
  * Build apps that use it with the Boehm GC (`GC.boehm`): Scala Native 0.5.12's default Immix GC
  * can corrupt live objects when several workers allocate at once (scala-native#5046, #5160), which
  * makes the server fail requests or abort under concurrent load. uni runs Boehm with one marker
  * thread, since a server's small heap makes Boehm collect often and one marker per core would
  * stall every worker each time (set `GC_MARKERS` to override). The server also starts Boehm with a
  * 16 MB heap, so it doesn't collect constantly (set `GC_INITIAL_HEAP_SIZE` to choose another
  * size).
  */
@deprecated(
  "Use Http.server; set Native-only options with withBackend(Native.options...)",
  "2026.1.39"
)
object NativeServer:

  def withPort(port: Int): NativeServerConfig = NativeServerConfig().withPort(port)

  def withHandler(handler: HttpHandler): NativeServerConfig = NativeServerConfig().withHandler(
    handler
  )

  def withHandler(f: Request => Response): NativeServerConfig = NativeServerConfig().withHandler(f)

  def withRxHandler(handler: RxHttpHandler): NativeServerConfig = NativeServerConfig()
    .withRxHandler(handler)

  def withRxHandler(f: Request => Rx[Response]): NativeServerConfig = NativeServerConfig()
    .withRxHandler(f)

end NativeServer

/**
  * Configuration for [[NativeHttpServer]], mirroring `NettyServerConfig`/`NodeServerConfig`'s
  * common surface plus a few Native-specific knobs.
  */
case class NativeServerConfig(
    name: String = "native-server",
    // Loopback by default; use withHost("0.0.0.0") to accept connections from other hosts
    host: String = "127.0.0.1",
    port: Int = 8080,
    handler: RxHttpHandler = RxHttpHandler.notFound,
    filters: Seq[RxHttpFilter] = Seq.empty,
    // listen() backlog
    backlog: Int = 128,
    // Maximum size (bytes) of a request body, as sent or after chunked decoding
    maxRequestSize: Int = 1024 * 1024,
    // Maximum length of the request line (414 when exceeded), as in Netty
    maxInitialLineLength: Int = 4096,
    // Maximum size of the request headers after the request line (431 when exceeded), as in Netty
    maxHeaderSize: Int = 8192,
    // Close a connection that doesn't deliver a whole request within this time once it starts
    // arriving (0 disables), as Netty's request read timeout does. Bounds slowloris clients.
    requestReadTimeoutMillis: Int = 30000,
    // Close a connection whose response write makes no progress for this long (0 disables), so a
    // client that stops reading can't hold a worker
    writeTimeoutMillis: Int = 30000,
    // Decode request bodies sent with Content-Encoding gzip/deflate (and x- aliases) before any
    // filter or handler sees them
    requestDecompression: Boolean = true,
    // Maximum decoded size of a compressed request body; uncompressed requests are not counted
    maxDecompressedRequestSize: Int = 64 * 1024 * 1024,
    // Size of the worker thread pool, which bounds concurrent requests (idle connections hold none).
    // Default: one per core, from 4 to 16 (16 on Windows); see NativeServerConfig.defaultWorkerThreads
    workerThreads: Int = NativeServerConfig.DefaultWorkerThreads,
    // Max time to wait for a handler's Rx to produce a response before returning 503
    handlerTimeoutMillis: Long = 30000,
    // Max time a kept-alive connection may sit idle (awaiting the next request) before it is closed
    idleTimeoutMillis: Int = 30000,
    // Max time to wait for more bytes once a request has started arriving before closing
    readTimeoutMillis: Int = 30000,
    // WebSocket routes, matched by path during the HTTP upgrade handshake
    override val webSocketRoutes: Seq[WebSocketRoute] = Nil,
    // Maximum size (bytes) of an inbound WebSocket message
    webSocketMaxFrameSize: Int = 1024 * 1024,
    // Ping/pong heartbeat interval for WebSocket connections (0 disables it): the server pings an
    // idle connection and closes it if the peer stops responding to pings.
    webSocketPingIntervalMillis: Int = 0,
    // Maximum number of concurrent long-lived connections (Server-Sent Events, WebSocket); more get
    // 503. None: workerThreads minus a reserve of up to 4 workers kept for ordinary requests
    maxStreamingConnections: Option[Int] = None,
    // Max time stop() waits for in-flight requests before closing their connections (0: at once)
    shutdownTimeoutMillis: Long = 30000,
    // Browser origins allowed to open WebSocket connections, besides same-origin ("*" allows all)
    override val allowedOrigins: Seq[String] = Nil,
    // Host names accepted in the Host header (DNS rebinding protection). Empty: loopback names for
    // a loopback-bound server, every host otherwise ("*" allows all)
    override val allowedHosts: Seq[String] = Nil,
    // Include exception messages in 500 responses (for development only)
    override val detailedErrors: Boolean = false
) extends HttpServerConfigBase:

  def withName(name: String): NativeServerConfig = copy(name = name)
  def withHost(host: String): NativeServerConfig = copy(host = host)
  def withPort(port: Int): NativeServerConfig    = copy(port = port)

  def withHandler(handler: HttpHandler): NativeServerConfig = copy(handler =
    RxHttpHandler.fromSync(handler)
  )

  def withHandler(f: Request => Response): NativeServerConfig = copy(handler =
    RxHttpHandler.fromFunction(f)
  )

  def withRxHandler(handler: RxHttpHandler): NativeServerConfig = copy(handler = handler)

  def withRxHandler(f: Request => Rx[Response]): NativeServerConfig = copy(handler =
    RxHttpHandler(f)
  )

  def withFilter(filter: RxHttpFilter): NativeServerConfig = copy(filters = filters :+ filter)

  def withFilters(filters: Seq[RxHttpFilter]): NativeServerConfig = copy(filters =
    this.filters ++ filters
  )

  def withBacklog(backlog: Int): NativeServerConfig =
    require(backlog > 0, "backlog must be positive")
    copy(backlog = backlog)

  def withMaxRequestSize(bytes: Int): NativeServerConfig =
    require(bytes > 0, "maxRequestSize must be positive")
    copy(maxRequestSize = bytes)

  def withMaxInitialLineLength(length: Int): NativeServerConfig =
    require(length > 0, "maxInitialLineLength must be positive")
    copy(maxInitialLineLength = length)

  def withMaxHeaderSize(bytes: Int): NativeServerConfig =
    require(bytes > 0, "maxHeaderSize must be positive")
    copy(maxHeaderSize = bytes)

  def withRequestReadTimeoutMillis(millis: Int): NativeServerConfig =
    require(millis >= 0, "requestReadTimeoutMillis must be >= 0")
    copy(requestReadTimeoutMillis = millis)

  def noRequestReadTimeout: NativeServerConfig = withRequestReadTimeoutMillis(0)

  def withWriteTimeoutMillis(millis: Int): NativeServerConfig =
    require(millis >= 0, "writeTimeoutMillis must be >= 0")
    copy(writeTimeoutMillis = millis)

  def noWriteTimeout: NativeServerConfig = withWriteTimeoutMillis(0)

  /**
    * Decode request bodies sent with `Content-Encoding: gzip` or `deflate` (enabled by default),
    * bounded by [[withMaxDecompressedRequestSize]]. To authenticate requests before inflating them,
    * use [[noRequestDecompression]] and decode the body in a filter.
    */
  def withRequestDecompression: NativeServerConfig = copy(requestDecompression = true)
  def noRequestDecompression: NativeServerConfig   = copy(requestDecompression = false)

  /** Maximum decoded size of a compressed request body (default: 64 MiB); larger ones get 413. */
  def withMaxDecompressedRequestSize(bytes: Int): NativeServerConfig =
    require(bytes > 0, "maxDecompressedRequestSize must be positive")
    copy(maxDecompressedRequestSize = bytes)

  def withWorkerThreads(threads: Int): NativeServerConfig =
    require(threads > 0, "workerThreads must be positive")
    copy(workerThreads = threads)

  def withHandlerTimeoutMillis(millis: Long): NativeServerConfig =
    require(millis > 0, "handlerTimeoutMillis must be positive")
    copy(handlerTimeoutMillis = millis)

  def withIdleTimeoutMillis(millis: Int): NativeServerConfig =
    require(millis > 0, "idleTimeoutMillis must be positive")
    copy(idleTimeoutMillis = millis)

  def withReadTimeoutMillis(millis: Int): NativeServerConfig =
    require(millis > 0, "readTimeoutMillis must be positive")
    copy(readTimeoutMillis = millis)

  /** Register a WebSocket route; the factory creates a fresh handler per accepted connection. */
  def withWebSocketRoute(path: String)(
      handlerFactory: Request => WebSocketHandler
  ): NativeServerConfig = withWebSocketRoute(path, RxHttpFilter.identity)(handlerFactory)

  /** Register a WebSocket route with a filter that gates the upgrade handshake (e.g. for auth). */
  def withWebSocketRoute(path: String, filter: RxHttpFilter)(
      handlerFactory: Request => WebSocketHandler
  ): NativeServerConfig = withWebSocketRoute(WebSocketRoute(path, handlerFactory, filter))

  /**
    * Register a configured WebSocket route, e.g. one built with
    * `WebSocketRoute(path, factory, authFilter).withCloseOnRejection()`.
    */
  def withWebSocketRoute(route: WebSocketRoute): NativeServerConfig = copy(webSocketRoutes =
    webSocketRoutes :+ route
  )

  def withWebSocketMaxFrameSize(sizeInBytes: Int): NativeServerConfig =
    require(sizeInBytes > 0, "webSocketMaxFrameSize must be positive")
    copy(webSocketMaxFrameSize = sizeInBytes)

  def withWebSocketPingIntervalMillis(millis: Int): NativeServerConfig =
    require(millis >= 0, "webSocketPingIntervalMillis must be >= 0")
    copy(webSocketPingIntervalMillis = millis)

  /**
    * Allow WebSocket connections from these browser origins (e.g. `https://app.example.com`) in
    * addition to same-origin pages. Pass `"*"` to allow every origin.
    */
  def withAllowedOrigins(origins: String*): NativeServerConfig = copy(allowedOrigins =
    origins.toSeq
  )

  /**
    * Accept only requests whose `Host` header names one of these hosts (port ignored), to prevent
    * DNS rebinding. Entries are exact names or `*.suffix`; `"*"` allows every host. Replaces the
    * default, which for a loopback-bound server is `localhost`, `*.localhost`, `127.0.0.1` and
    * `[::1]`.
    */
  def withAllowedHosts(hosts: String*): NativeServerConfig = copy(allowedHosts = hosts.toSeq)

  /** Accept every `Host` header. Exposes a loopback server to DNS rebinding from web pages. */
  def noHostCheck: NativeServerConfig = withAllowedHosts("*")

  /**
    * Limit the number of concurrently open Server-Sent Events streams and WebSocket connections;
    * further ones get 503. Each occupies a worker thread while open, so the limit must stay below
    * `workerThreads` to leave workers for ordinary requests.
    */
  def withMaxStreamingConnections(n: Int): NativeServerConfig =
    require(n > 0, "maxStreamingConnections must be positive")
    copy(maxStreamingConnections = Some(n))

  /**
    * The effective limit on long-lived connections: [[maxStreamingConnections]] if set, otherwise
    * `workerThreads` minus a reserve of `min(4, workerThreads / 2)` workers.
    */
  def effectiveMaxStreamingConnections: Int = maxStreamingConnections.getOrElse(
    workerThreads - math.min(4, workerThreads / 2)
  )

  /** How long `stop()` waits for in-flight requests (default: 30 s); 0 closes them at once. */
  def withShutdownTimeoutMillis(millis: Long): NativeServerConfig =
    require(millis >= 0, "shutdownTimeoutMillis must be >= 0")
    copy(shutdownTimeoutMillis = millis)

  def withDetailedErrors(enabled: Boolean): NativeServerConfig = copy(detailedErrors = enabled)
  def noDetailedErrors: NativeServerConfig                     = withDetailedErrors(false)

  /**
    * Start the server and return the running instance. Binding is synchronous, so the inherited
    * `start[A](block)` lifecycle form is safe.
    */
  override def start(): NativeHttpServer =
    val server = NativeHttpServer(this)
    server.start()
    server

end NativeServerConfig

object NativeServerConfig:
  /**
    * The default `workerThreads`: about one worker per core, at least 4 and at most 16. More
    * workers than cores queue on the Boehm GC's allocation lock, which showed as a 4-12 ms p99 on a
    * 2-core server with 16 workers (0.7 ms with 2). Windows keeps 16, because each connection holds
    * a worker there while it is open.
    */
  private[http] def defaultWorkerThreads(cores: Int, windows: Boolean): Int =
    if windows then
      16
    else
      math.min(16, math.max(4, cores))

  // availableProcessors follows the process's CPU affinity on Linux (taskset, cpusets)
  private[http] lazy val DefaultWorkerThreads: Int = defaultWorkerThreads(
    Runtime.getRuntime.availableProcessors(),
    scala.scalanative.meta.LinktimeInfo.isWindows
  )

end NativeServerConfig
