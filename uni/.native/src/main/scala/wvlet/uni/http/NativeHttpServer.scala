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

import wvlet.uni.log.LogSupport
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxRunner}

import java.util.concurrent.{CountDownLatch, ExecutorService, Executors, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/**
  * Scala Native HTTP server backed by POSIX TCP sockets. A daemon accept thread hands each accepted
  * connection to a worker pool; workers parse HTTP/1.1 requests, run the (filter-composed) handler,
  * and write responses, keeping the connection alive unless the client requests close.
  *
  * Binding is synchronous, so [[whenReady]] completes immediately (like the Netty backend).
  */
class NativeHttpServer(config: NativeServerConfig) extends HttpServer with LogSupport:

  private val handler: RxHttpHandler = config.effectiveHandler

  private val started = AtomicBoolean(false)
  private val running = AtomicBoolean(false)
  private val stopped = AtomicBoolean(false)

  @volatile
  private var serverFd: Int = -1

  @volatile
  private var boundPort: Int = -1

  @volatile
  private var acceptThread: Thread = null

  @volatile
  private var workerPool: ExecutorService = null

  def start(): Unit =
    if !started.compareAndSet(false, true) then
      throw IllegalStateException("Server is already started")

    val (fd, port) = NativeSocket.bindAndListen(config.host, config.port, config.backlog)
    serverFd = fd
    boundPort = port

    workerPool = Executors.newFixedThreadPool(
      config.workerThreads,
      daemonThreadFactory(s"${config.name}-worker")
    )

    running.set(true)
    val t = Thread(() => acceptLoop())
    t.setDaemon(true)
    t.setName(s"${config.name}-acceptor")
    t.start()
    acceptThread = t
    debug(s"Native server started at ${localAddress}")

  end start

  private def acceptLoop(): Unit =
    while running.get() do
      val clientFd = NativeSocket.accept(serverFd)
      if clientFd < 0 then
        // A failed accept while still running is logged; while stopping it means the listening
        // socket was closed, so exit the loop. Sleep briefly to avoid a 100% CPU spin on a
        // persistent error (e.g. EMFILE — too many open files).
        if running.get() then
          debug("accept() failed; retrying")
          Thread.sleep(NativeHttpServer.AcceptErrorBackoffMillis)
      else
        val pool = workerPool
        if pool != null && running.get() then
          // execute can be rejected if stop() raced in between; close the fd rather than leak it.
          try
            pool.execute(() => handleConnection(clientFd))
          catch
            case NonFatal(_) =>
              NativeSocket.close(clientFd)
        else
          NativeSocket.close(clientFd)

  private def handleConnection(clientFd: Int): Unit =
    try
      val reader = HttpConnectionReader(
        () => NativeSocket.recvChunk(clientFd),
        config.maxRequestSize
      )
      var continue = true
      while continue do
        reader.readRequest() match
          case ReadResult.Closed =>
            continue = false
          case ReadResult.BadRequest(message) =>
            NativeSocket.sendAll(
              clientFd,
              HttpResponseWriter.serialize(
                Response.badRequest(message),
                keepAlive = false,
                includeBody = true
              )
            )
            continue = false
          case ReadResult.Req(request) =>
            val response  = runHandler(request)
            val keepAlive = clientWantsKeepAlive(request)
            // A HEAD response carries headers (incl. Content-Length) but no body.
            val includeBody = request.method != HttpMethod.HEAD
            val sent        = NativeSocket.sendAll(
              clientFd,
              HttpResponseWriter.serialize(response, keepAlive, includeBody)
            )
            if !keepAlive || !sent then
              continue = false
    catch
      case NonFatal(e) =>
        debug(s"Connection handling error: ${e.getMessage}")
    finally
      NativeSocket.close(clientFd)

  private def clientWantsKeepAlive(request: Request): Boolean =
    !request.header(HttpHeader.Connection).exists(_.equalsIgnoreCase("close"))

  /**
    * Run the handler and block for its single response. The handler may be asynchronous (Rx), so
    * the worker thread waits on a latch for the first event.
    */
  private def runHandler(request: Request): Response =
    try
      val latch  = CountDownLatch(1)
      val result = AtomicReference[Response]()
      RxRunner.runOnce(handler.handle(request)) {
        case OnNext(response) =>
          result.set(response.asInstanceOf[Response])
          latch.countDown()
        case OnError(e) =>
          warn(s"Error in handler: ${e.getMessage}", e)
          result.set(Response.internalServerError(e.getMessage))
          latch.countDown()
        case OnCompletion =>
          // Completed without emitting a response.
          result.compareAndSet(null, Response.notFound)
          latch.countDown()
      }
      // Bound the wait so a handler whose Rx never completes can't wedge the worker thread forever.
      if latch.await(config.handlerTimeoutMillis, TimeUnit.MILLISECONDS) then
        result.get()
      else
        warn(s"Handler timed out after ${config.handlerTimeoutMillis} ms")
        Response.serviceUnavailable
    catch
      case NonFatal(e) =>
        warn(s"Error handling request: ${e.getMessage}", e)
        Response.internalServerError(e.getMessage)

  private def daemonThreadFactory(name: String): ThreadFactory =
    (r: Runnable) =>
      val t = Thread(r)
      t.setDaemon(true)
      t.setName(name)
      t

  override def whenReady: Rx[HttpServer] = Rx.single(this)

  override def isRunning: Boolean = running.get() && !stopped.get()

  override def localPort: Int = boundPort

  override def localAddress: String = s"${config.host}:${boundPort}"

  override def stop(): Unit =
    if !stopped.compareAndSet(false, true) then
      return
    running.set(false)
    // Closing the listening socket unblocks accept() so the accept thread can exit. Guard it so a
    // close failure can't abort the rest of shutdown.
    if serverFd >= 0 then
      try
        NativeSocket.close(serverFd)
      catch
        case NonFatal(e) =>
          debug(s"Error closing server socket: ${e.getMessage}")
    Option(workerPool).foreach { pool =>
      pool.shutdown()
      if !pool.awaitTermination(NativeHttpServer.ShutdownTimeoutMillis, TimeUnit.MILLISECONDS) then
        pool.shutdownNow()
    }
    debug(s"Native server stopped")

  override def awaitTermination(): Unit = Option(acceptThread).foreach(_.join())

end NativeHttpServer

object NativeHttpServer:
  private final val ShutdownTimeoutMillis                 = 5000L
  private final val AcceptErrorBackoffMillis              = 10L
  def apply(config: NativeServerConfig): NativeHttpServer = new NativeHttpServer(config)
