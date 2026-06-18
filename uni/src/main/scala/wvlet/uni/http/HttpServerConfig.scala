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
  * Platform-neutral HTTP server configuration. Backend-specific configs (e.g. `NettyServerConfig`,
  * `NodeServerConfig`) implement this trait so the common surface — name/host/port, the request
  * handler, filters, and the start lifecycle — is identical across platforms. Backend-specific
  * tuning (Netty transport options, Node.js options, ...) lives on the concrete config.
  */
trait HttpServerConfig:
  def name: String
  def host: String
  def port: Int
  def handler: RxHttpHandler
  def filters: Seq[RxHttpFilter]

  /**
    * The request handler with all configured filters applied, in order. Shared by every backend so
    * filter semantics stay consistent across platforms.
    */
  def effectiveHandler: RxHttpHandler =
    if filters.isEmpty then
      handler
    else
      val chained = RxHttpFilter.chain(filters)
      RxHttpHandler { request =>
        chained.apply(request, handler)
      }

  /**
    * Start the server and return the running server instance. Backends override this with a
    * covariant return type (e.g. `NettyHttpServer`).
    */
  def start(): HttpServer

  /**
    * Start the server, run the given block, then stop the server. The block runs as soon as
    * `start()` returns, so this is only safe on backends that bind synchronously (Netty). On
    * asynchronously-binding backends (Node.js) use [[startAndAwait]] instead, which waits for the
    * server to be ready before running the block.
    */
  def start[A](block: HttpServer => A): A =
    val server = start()
    try block(server)
    finally server.stop()

  /**
    * Start the server, wait until it is listening, run the given async block, then stop the server
    * once the block's Rx completes (or fails). This works uniformly across backends — including
    * Node.js, whose bind is asynchronous — so it is the portable way to run a server in tests and
    * apps.
    */
  def startAndAwait[A](block: HttpServer => Rx[A]): Rx[A] =
    val server = start()
    server
      .whenReady
      .flatMap(block)
      .tapOn { case _ =>
        server.stop()
      }

end HttpServerConfig
