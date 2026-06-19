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
  * Entry point for creating a Scala Native HTTP server. This is the Native counterpart of
  * `NettyServer` (JVM) and `NodeServer` (JS); all three produce an [[HttpServer]] from a shared
  * [[HttpServerConfig]], so handler and filter code is identical across platforms.
  *
  * The server is built directly on POSIX TCP sockets (no external dependency): a daemon accept loop
  * dispatches each connection to a worker thread pool, where requests are parsed, run through the
  * handler, and answered with HTTP/1.1.
  */
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

  def withWebSocketRoute(path: String)(
      handlerFactory: Request => WebSocketHandler
  ): NativeServerConfig = NativeServerConfig().withWebSocketRoute(path)(handlerFactory)

  def withWebSocketRoute(path: String, filter: RxHttpFilter)(
      handlerFactory: Request => WebSocketHandler
  ): NativeServerConfig = NativeServerConfig().withWebSocketRoute(path, filter)(handlerFactory)

/**
  * Configuration for [[NativeHttpServer]], mirroring `NettyServerConfig`/`NodeServerConfig`'s
  * common surface plus a few Native-specific knobs.
  */
case class NativeServerConfig(
    name: String = "native-server",
    host: String = "0.0.0.0",
    port: Int = 8080,
    handler: RxHttpHandler = RxHttpHandler.notFound,
    filters: Seq[RxHttpFilter] = Seq.empty,
    // listen() backlog
    backlog: Int = 128,
    // Maximum size (bytes) of request headers + body
    maxRequestSize: Int = 1024 * 1024,
    // Size of the worker thread pool that handles connections
    workerThreads: Int = 16,
    // Max time to wait for a handler's Rx to produce a response before returning 503
    handlerTimeoutMillis: Long = 30000,
    // WebSocket routes, matched by path during the HTTP upgrade handshake
    override val webSocketRoutes: Seq[WebSocketRoute] = Nil,
    // Maximum size (bytes) of an inbound WebSocket message
    webSocketMaxFrameSize: Int = 1024 * 1024
) extends HttpServerConfig:

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

  def withWorkerThreads(threads: Int): NativeServerConfig =
    require(threads > 0, "workerThreads must be positive")
    copy(workerThreads = threads)

  def withHandlerTimeoutMillis(millis: Long): NativeServerConfig =
    require(millis > 0, "handlerTimeoutMillis must be positive")
    copy(handlerTimeoutMillis = millis)

  /** Register a WebSocket route; the factory creates a fresh handler per accepted connection. */
  def withWebSocketRoute(path: String)(
      handlerFactory: Request => WebSocketHandler
  ): NativeServerConfig = withWebSocketRoute(path, RxHttpFilter.identity)(handlerFactory)

  /** Register a WebSocket route with a filter that gates the upgrade handshake (e.g. for auth). */
  def withWebSocketRoute(path: String, filter: RxHttpFilter)(
      handlerFactory: Request => WebSocketHandler
  ): NativeServerConfig = copy(webSocketRoutes =
    webSocketRoutes :+ WebSocketRoute(path, handlerFactory, filter)
  )

  def withWebSocketMaxFrameSize(sizeInBytes: Int): NativeServerConfig =
    require(sizeInBytes > 0, "webSocketMaxFrameSize must be positive")
    copy(webSocketMaxFrameSize = sizeInBytes)

  /**
    * Start the server and return the running instance. Binding is synchronous, so the inherited
    * `start[A](block)` lifecycle form is safe.
    */
  override def start(): NativeHttpServer =
    val server = NativeHttpServer(this)
    server.start()
    server

end NativeServerConfig
