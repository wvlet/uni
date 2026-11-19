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

import java.io.{IOException, InputStream, OutputStream}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket, SocketTimeoutException}
import java.util.concurrent.{ExecutorService, Executors}

/**
  * The JVM's built-in HTTP/1.1 server, which `Http.server` starts when no backend such as uni-netty
  * is on the classpath. It runs the HTTP and WebSocket code it shares with the Scala Native server
  * over `java.net` sockets, with one virtual thread per connection: a blocking read parks the
  * virtual thread, and the JDK's poller (epoll, kqueue, or IOCP) resumes it when bytes arrive. The
  * handler runs on the connection's own thread, with no hand-off to another pool.
  */
private[http] class JvmHttpServer(config: HttpServerConfig)
    extends SocketHttpServer(config, JvmHttpServer.limits(config)):

  override protected def listen(host: String, port: Int, backlog: Int): SocketListener =
    JvmSocketListener(host, port, backlog)

  override protected def newWorkerPool(): ExecutorService = Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name(s"${config.name}-", 0).factory()
  )

  override protected def onStarted(): Unit = info(
    s"Started the built-in HTTP server at http://${localAddress}. Add uni-netty for request decompression"
  )

end JvmHttpServer

private[http] object JvmHttpServer:

  /**
    * Virtual threads stop pinning their carrier in `synchronized` from JDK 24 (JEP 491). Before
    * that, a few connections blocked inside `synchronized` could stall every other connection.
    */
  final val MinJdkVersion = 24

  def start(config: HttpServerConfig): JvmHttpServer =
    val server = JvmHttpServer(config)
    server.start()
    server

  /**
    * Limits from the `Http.server` settings, with the Native server's defaults otherwise. A parked
    * virtual thread costs only memory, so streaming connections are capped like Netty's (256)
    * rather than by a worker pool.
    */
  private def limits(config: HttpServerConfig): SocketServerLimits = SocketServerLimits(
    backlog = 1024,
    maxRequestSize = config.maxContentLength.getOrElse(1024 * 1024),
    handlerTimeoutMillis = 30000,
    idleTimeoutMillis = 30000,
    readTimeoutMillis = 30000,
    webSocketMaxFrameSize = config.webSocketMaxFrameSize.getOrElse(1024 * 1024),
    webSocketPingIntervalMillis = config.webSocketPingIntervalMillis.getOrElse(0),
    maxStreamingConnections = 256
  )

end JvmHttpServer

/** A [[SocketConnection]] over a `java.net.Socket`. */
private[http] class JvmSocketConnection(socket: Socket) extends SocketConnection:

  private val in: InputStream   = socket.getInputStream
  private val out: OutputStream = socket.getOutputStream

  // The SO_TIMEOUT last set, to skip the call when the timeout doesn't change
  private var soTimeout = -2

  override def read(buf: Array[Byte], off: Int, len: Int, timeoutMillis: Int): Int =
    // SO_TIMEOUT 0 means no limit, so "don't wait" becomes the shortest wait
    val t =
      if timeoutMillis < 0 then
        0
      else
        math.max(timeoutMillis, 1)
    try
      if t != soTimeout then
        socket.setSoTimeout(t)
        soTimeout = t
      val n = in.read(buf, off, len)
      if n > 0 then
        n
      else
        -1
    catch
      case _: SocketTimeoutException =>
        0
      case _: IOException =>
        -1

  override def write(data: Array[Byte], off: Int, len: Int): Boolean =
    try
      out.write(data, off, len)
      true
    catch
      case _: IOException =>
        false

  override def interrupt(): Unit =
    try
      socket.shutdownInput()
      socket.shutdownOutput()
    catch
      case _: IOException =>
        ()

  override def close(): Unit =
    try
      socket.close()
    catch
      case _: IOException =>
        ()

end JvmSocketConnection

/** A listening `java.net.ServerSocket`. */
private[http] class JvmSocketListener(host: String, requestedPort: Int, backlog: Int)
    extends SocketListener:

  private val serverSocket =
    val s = ServerSocket()
    s.setReuseAddress(true)
    s.bind(InetSocketAddress(InetAddress.getByName(host), requestedPort), backlog)
    s

  override def port: Int = serverSocket.getLocalPort

  override def accept(): SocketConnection =
    try
      val socket = serverSocket.accept()
      // Responses go out in one write each, so don't hold small ones back (Nagle)
      socket.setTcpNoDelay(true)
      socket.setKeepAlive(true)
      JvmSocketConnection(socket)
    catch
      case _: IOException =>
        null

  override def close(): Unit =
    try
      serverSocket.close()
    catch
      case _: IOException =>
        ()

end JvmSocketListener
