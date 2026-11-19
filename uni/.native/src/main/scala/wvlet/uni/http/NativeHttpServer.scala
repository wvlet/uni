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

import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}

/**
  * Scala Native HTTP server backed by POSIX TCP sockets: the shared [[SocketHttpServer]] with a
  * fixed pool of `workerThreads` workers, each serving one connection at a time.
  */
class NativeHttpServer(config: NativeServerConfig)
    extends SocketHttpServer(
      config,
      SocketServerLimits(
        backlog = config.backlog,
        maxRequestSize = config.maxRequestSize,
        handlerTimeoutMillis = config.handlerTimeoutMillis,
        idleTimeoutMillis = config.idleTimeoutMillis,
        readTimeoutMillis = config.readTimeoutMillis,
        webSocketMaxFrameSize = config.webSocketMaxFrameSize,
        webSocketPingIntervalMillis = config.webSocketPingIntervalMillis,
        maxStreamingConnections = config.effectiveMaxStreamingConnections
      )
    ):

  override protected def listen(host: String, port: Int, backlog: Int): SocketListener =
    NativeSocketListener(host, port, backlog)

  override protected def newWorkerPool(): ExecutorService = Executors.newFixedThreadPool(
    config.workerThreads,
    NativeHttpServer.daemonThreadFactory(s"${config.name}-worker")
  )

  override protected def onStarted(): Unit = debug(s"Native server started at ${localAddress}")

end NativeHttpServer

object NativeHttpServer:
  def apply(config: NativeServerConfig): NativeHttpServer = new NativeHttpServer(config)

  private def daemonThreadFactory(name: String): ThreadFactory =
    (r: Runnable) =>
      val t = Thread(r)
      t.setDaemon(true)
      t.setName(name)
      t
