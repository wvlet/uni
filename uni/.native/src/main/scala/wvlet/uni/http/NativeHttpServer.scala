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
import wvlet.uni.rx.{Cancelable, OnCompletion, OnError, OnNext, Rx, RxRunner}

import java.nio.charset.StandardCharsets
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
      NativeSocket.enableKeepAlive(clientFd)
      // Poll before recv so an idle/slow/disconnected client can't pin a worker forever: an idle
      // keep-alive connection waits up to idleTimeoutMillis for the next request; once a request has
      // started arriving, subsequent reads wait up to readTimeoutMillis. A timeout (or hangup) maps
      // to an empty chunk, which the reader treats as end-of-connection.
      val reader = HttpConnectionReader(
        idle =>
          val timeout =
            if idle then
              config.idleTimeoutMillis
            else
              config.readTimeoutMillis
          if NativeSocket.waitReadable(clientFd, timeout) == 1 then
            NativeSocket.recvChunk(clientFd)
          else
            Array.emptyByteArray
        ,
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
            webSocketRouteFor(request) match
              case Some(route) =>
                // WebSocket upgrade: the WS handler owns the connection until it closes.
                handleWebSocketUpgrade(clientFd, request, route)
                continue = false
              case None =>
                if !handleHttpRequest(clientFd, request) then
                  continue = false
      end while
    catch
      case NonFatal(e) =>
        debug(s"Connection handling error: ${e.getMessage}")
    finally
      NativeSocket.close(clientFd)

  /**
    * Handle one non-WebSocket request. Returns whether the connection may stay open (keep-alive).
    */
  private def handleHttpRequest(clientFd: Int, request: Request): Boolean =
    val response  = runHandler(request)
    val keepAlive = clientWantsKeepAlive(request)
    val isHead    = request.method == HttpMethod.HEAD
    val sent      =
      if response.isEventStream then
        if isHead then
          // HEAD: send the streaming response headers but no event body.
          NativeSocket.sendAll(
            clientFd,
            HttpResponseWriter.serializeSseHeaders(response, keepAlive)
          )
        else
          streamSse(clientFd, response, keepAlive)
      else
        // A HEAD response carries headers (incl. Content-Length) but no body.
        NativeSocket.sendAll(
          clientFd,
          HttpResponseWriter.serialize(response, keepAlive, includeBody = !isHead)
        )
    keepAlive && sent

  private def clientWantsKeepAlive(request: Request): Boolean =
    !request.header(HttpHeader.Connection).exists(_.equalsIgnoreCase("close"))

  /**
    * Stream a Server-Sent Events response with chunked transfer encoding. Each `ServerSentEvent` is
    * written as one chunk; the worker thread blocks until the event stream terminates (SSE is
    * long-lived by design, so no handler-await timeout is applied here). A failed chunk write
    * cancels the subscription. Returns whether the connection is still usable for keep-alive.
    *
    * The worker is parked until the stream terminates, but it periodically probes the socket (every
    * idleTimeoutMillis) so a client that disconnects while the stream is *idle* is detected and the
    * subscription cancelled, rather than leaking the worker until the next event fails to write.
    */
  private def streamSse(clientFd: Int, response: Response, keepAlive: Boolean): Boolean =
    if !NativeSocket.sendAll(clientFd, HttpResponseWriter.serializeSseHeaders(response, keepAlive))
    then
      false
    else
      val done = CountDownLatch(1)
      val ok   = AtomicBoolean(true)
      // Delegate Cancelable: RxRunner.run can emit synchronously (Rx.fromSeq) before the real
      // subscription handle is assigned, and an async source may emit on another thread — so the
      // cancel flag and handle are atomics for correct cross-thread visibility.
      val cancelled                = AtomicBoolean(false)
      val realSubscription         = AtomicReference[Cancelable](Cancelable.empty)
      val subscription: Cancelable = Cancelable { () =>
        cancelled.set(true)
        realSubscription.get().cancel
      }
      // The event source may emit on another thread (e.g. a timer), so socket writes happen there
      // while this worker probes/closes the connection. Serialize writes with the teardown under a
      // lock so the fd is never closed mid-send (which could leak bytes into a reused fd).
      val writeLock = Object()
      realSubscription.set(
        RxRunner.run(response.events) {
          case OnNext(event) =>
            writeLock.synchronized {
              if !cancelled.get() then
                val data = event
                  .asInstanceOf[ServerSentEvent]
                  .toContent
                  .getBytes(StandardCharsets.UTF_8)
                if !NativeSocket.sendAll(clientFd, HttpResponseWriter.chunk(data)) then
                  ok.set(false)
                  subscription.cancel
                  done.countDown()
            }
          case OnError(e) =>
            warn(s"SSE stream error: ${e.getMessage}", e)
            ok.set(false)
            done.countDown()
          case OnCompletion =>
            done.countDown()
        }
      )
      // Wait for the stream to terminate, but probe the socket between waits so an idle client
      // disconnect is detected promptly (a disconnected peer reads as readable-then-EOF or a hangup).
      var alive = true
      while alive && !done.await(config.idleTimeoutMillis.toLong, TimeUnit.MILLISECONDS) do
        NativeSocket.waitReadable(clientFd, 0) match
          case -1 =>
            alive = false // peer hung up
          case 1 =>
            // SSE clients shouldn't send; a readable socket that yields no bytes is EOF (disconnect).
            if NativeSocket.recvChunk(clientFd).isEmpty then
              alive = false
          case _ =>
            () // genuinely idle but still connected
      if !alive then
        // Set cancelled under the lock so any in-flight emitter write finishes first and no new write
        // starts; the connection can then be closed safely after this returns.
        writeLock.synchronized {
          cancelled.set(true)
        }
        subscription.cancel
      // Terminate the chunked body if the client is still there.
      ok.get() && alive && NativeSocket.sendAll(clientFd, HttpResponseWriter.finalChunk)

  /**
    * Run the handler and block for its single response. The handler may be asynchronous (Rx), so
    * the worker thread waits on a latch for the first event.
    */
  private def runHandler(request: Request): Response =
    try
      awaitRx(handler.handle(request))
    catch
      case NonFatal(e) =>
        warn(s"Error handling request: ${e.getMessage}", e)
        Response.internalServerError(e.getMessage)

  /**
    * Block for the single Response produced by an Rx (the request handler, or a WebSocket upgrade
    * filter gate), bounded by the handler timeout so a never-completing Rx can't wedge the worker.
    */
  private def awaitRx(rx: Rx[Response]): Response =
    val latch  = CountDownLatch(1)
    val result = AtomicReference[Response]()
    RxRunner.runOnce(rx) {
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
    if latch.await(config.handlerTimeoutMillis, TimeUnit.MILLISECONDS) then
      result.get()
    else
      warn(s"Handler timed out after ${config.handlerTimeoutMillis} ms")
      Response.serviceUnavailable

  private def webSocketRouteFor(request: Request): Option[WebSocketRoute] =
    if config.webSocketRoutes.isEmpty || !isWebSocketUpgrade(request) then
      None
    else
      config.webSocketRoutes.find(_.path == request.path)

  private def isWebSocketUpgrade(request: Request): Boolean =
    request.header(HttpHeader.Connection).exists(_.toLowerCase.contains("upgrade")) &&
      request.header(HttpHeader.Upgrade).exists(_.equalsIgnoreCase("websocket"))

  /**
    * Gate the upgrade through the route's filter (2xx allows; anything else rejects), then write
    * the 101 handshake and hand the connection to [[NativeWebSocket.serve]] (which blocks this
    * worker for the WebSocket's lifetime).
    */
  private def handleWebSocketUpgrade(clientFd: Int, request: Request, route: WebSocketRoute): Unit =
    WebSocketHandshake.validate(request) match
      case Left(rejection) =>
        // Malformed handshake: missing key (400), or unsupported Sec-WebSocket-Version (426).
        NativeSocket.sendAll(
          clientFd,
          HttpResponseWriter.serialize(rejection, keepAlive = false, includeBody = true)
        )
      case Right(key) =>
        // Capture the request as threaded through the filter, so attributes a filter adds during
        // the handshake reach the WebSocketContext (matching the Netty backend).
        val upgradeRequest = AtomicReference[Request](request)
        val gate           = RxHttpHandler { req =>
          upgradeRequest.set(req)
          Rx.single(Response.ok)
        }
        val verdict =
          try
            awaitRx(route.filter.apply(request, gate))
          catch
            case NonFatal(e) =>
              warn(s"WebSocket upgrade filter error: ${e.getMessage}", e)
              Response.internalServerError(e.getMessage)
        if !verdict.isSuccessful then
          NativeSocket.sendAll(
            clientFd,
            HttpResponseWriter.serialize(verdict, keepAlive = false, includeBody = true)
          )
        else
          val handshake =
            s"HTTP/1.1 101 Switching Protocols\r\n" + s"${HttpHeader.Upgrade}: websocket\r\n" +
              s"${HttpHeader.Connection}: Upgrade\r\n" +
              s"${HttpHeader.SecWebSocketAccept}: ${WebSocketFrame.acceptKey(key)}\r\n\r\n"
          if NativeSocket.sendAll(clientFd, handshake.getBytes(StandardCharsets.ISO_8859_1)) then
            val accepted = upgradeRequest.get()
            NativeWebSocket.serve(
              clientFd,
              accepted,
              route.handlerFactory(accepted),
              config.webSocketMaxFrameSize
            )

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
