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

import wvlet.uni.http.{HttpHandler, Request, Response}
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
    host: String = "0.0.0.0",
    port: Int = 8080,
    handler: RxHttpHandler = RxHttpHandler.notFound,
    filters: Seq[RxHttpFilter] = Seq.empty,
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
    handlerExecutorThreads: Option[Int] = None
):

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
    * Start the server and return the running server instance
    */
  def start(): NettyHttpServer =
    val server = NettyHttpServer(this)
    server.start()
    server

  /**
    * Start the server and run the given block, then stop the server
    */
  def start[A](block: NettyHttpServer => A): A =
    val server = start()
    try block(server)
    finally server.stop()

end NettyServerConfig
