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
  * Entry point for creating a Node.js-based HTTP server on Scala.js. This is the Node counterpart
  * of `NettyServer` (JVM); both produce an [[HttpServer]] from a shared [[HttpServerConfig]], so
  * handler and filter code is identical across platforms.
  *
  * Requires a Node-compatible runtime (Node.js, Bun, Deno) — it uses the built-in `http` module.
  */
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
    host: String = "0.0.0.0",
    port: Int = 8080,
    handler: RxHttpHandler = RxHttpHandler.notFound,
    filters: Seq[RxHttpFilter] = Seq.empty
) extends HttpServerConfig:

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
