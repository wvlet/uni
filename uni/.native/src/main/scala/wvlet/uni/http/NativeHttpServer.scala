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
  * fixed pool of `workerThreads` workers, each serving one request at a time. Between requests, a
  * keep-alive connection waits in an epoll/kqueue poller ([[NativeIdlePoller]]) and holds no
  * worker, so open connections are limited only by the fd limit. On Windows, which has no poller
  * yet, each connection keeps its worker while open.
  */
class NativeHttpServer(config: NativeServerConfig)
    extends SocketHttpServer(
      config,
      SocketServerLimits(
        backlog = config.backlog,
        request = HttpReaderLimits(
          maxInitialLineLength = config.maxInitialLineLength,
          maxHeaderSize = config.maxHeaderSize,
          maxContentLength = config.maxRequestSize
        ),
        requestTimeoutMillis = config.requestReadTimeoutMillis,
        writeTimeoutMillis = config.writeTimeoutMillis,
        requestDecompression = config.requestDecompression,
        maxDecompressedRequestSize = config.maxDecompressedRequestSize,
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

  override protected def newIdlePoller(idleTimeoutMillis: Int): Option[IdlePoller] =
    NativeIdlePoller.create(idleTimeoutMillis, config.name)

  override protected def onStarted(): Unit =
    // A server's live data is small, so Boehm's heap would stay a few MB and collect constantly
    // under load; start it at ServerHeapBytes (uni_gc_shim.c). No effect with other GCs.
    SocketShim.uni_gc_expand_heap_to(NativeHttpServer.ServerHeapBytes)
    debug(s"Native server started at ${localAddress}")

end NativeHttpServer

object NativeHttpServer:
  // The Boehm heap a server starts with, unless GC_INITIAL_HEAP_SIZE is set
  private[http] final val ServerHeapBytes = 16L << 20

  def apply(config: NativeServerConfig): NativeHttpServer = new NativeHttpServer(config)

  private def daemonThreadFactory(name: String): ThreadFactory =
    (r: Runnable) =>
      val t = Thread(r)
      t.setDaemon(true)
      t.setName(name)
      t
