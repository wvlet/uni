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
import wvlet.uni.rx.{Cancelable, OnCompletion, OnFailure, OnError, OnNext, Rx, RxRunner}

import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, ExecutorService, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/**
  * The limits of a [[SocketHttpServer]], set by each platform from its own config and defaults.
  */
private[http] case class SocketServerLimits(
    // listen() backlog
    backlog: Int,
    // Request line, header, and body size limits
    request: HttpReaderLimits,
    // Max time to receive a whole request once its first bytes arrive (0 disables), as Netty's
    // RequestReadTimeoutHandler bounds slowloris clients
    requestTimeoutMillis: Int,
    // Close a connection whose response write makes no progress for this long (0 disables)
    writeTimeoutMillis: Int,
    // Decode gzip/deflate request bodies before any filter or handler sees them
    requestDecompression: Boolean,
    // Maximum decoded size of a compressed request body
    maxDecompressedRequestSize: Int,
    // Max time to wait for a handler's Rx to produce a response before returning 503 (0 disables)
    handlerTimeoutMillis: Long,
    // Max time a kept-alive connection may sit idle (awaiting the next request) before it is closed
    idleTimeoutMillis: Int,
    // Max time to wait for more bytes once a request has started arriving before closing
    readTimeoutMillis: Int,
    webSocketMaxFrameSize: Int,
    webSocketPingIntervalMillis: Int,
    // Maximum number of concurrent long-lived connections (SSE, WebSocket); more get 503
    maxStreamingConnections: Int
)

private[http] object SocketServerLimits:
  final val DefaultMaxContentLength           = 1024 * 1024
  final val DefaultTimeoutMillis              = 30000
  final val DefaultMaxDecompressedRequestSize = 64 * 1024 * 1024

/**
  * An HTTP/1.1 server over blocking sockets, shared by Scala Native (`NativeHttpServer`) and the
  * JVM's built-in server. A daemon accept thread hands each accepted connection to a worker; the
  * worker parses requests, runs the (filter-composed) handler, and writes responses on that same
  * thread, keeping the connection alive unless the client requests close. Each platform supplies
  * the listening socket and the worker pool.
  *
  * Binding is synchronous, so [[whenReady]] completes immediately (like the Netty backend).
  */
abstract class SocketHttpServer private[http] (
    config: HttpServerConfigBase,
    limits: SocketServerLimits
) extends HttpServer
    with LogSupport:

  /** Bind and listen on the configured host and port. */
  protected def listen(host: String, port: Int, backlog: Int): SocketListener

  /** The pool that runs one task per connection, for the connection's lifetime. */
  protected def newWorkerPool(): ExecutorService

  /** Called once the server is listening. */
  protected def onStarted(): Unit = ()

  /**
    * A poller that watches connections between requests, so they don't hold a worker while idle.
    * None (the JVM, whose virtual threads park cheaply) keeps each connection on one task.
    */
  protected def newIdlePoller(idleTimeoutMillis: Int): Option[IdlePoller] = None

  private val handler: RxHttpHandler = config.effectiveHandler

  private val started = AtomicBoolean(false)
  private val running = AtomicBoolean(false)
  private val stopped = AtomicBoolean(false)

  @volatile
  private var listener: SocketListener = null

  @volatile
  private var boundPort: Int = -1

  @volatile
  private var acceptThread: Thread = null

  @volatile
  private var workerPool: ExecutorService = null

  @volatile
  private var writeWatchdog: Thread = null

  @volatile
  private var idlePoller: IdlePoller = null

  private val allowedHosts  = config.effectiveAllowedHosts
  private val streamLimiter = ConnectionLimiter(limits.maxStreamingConnections)
  private val connections   = ConcurrentHashMap.newKeySet[ConnectionState]()

  // Marks this server's connection threads, so stop() called from a handler doesn't wait on itself
  private val onConnectionThread = ThreadLocal.withInitial[java.lang.Boolean](() => false)

  private val acceptWarnings   = RateLimitedWarning((msg, e) => warn(msg, e))
  private val watchdogWarnings = RateLimitedWarning((msg, e) => warn(msg, e))

  /** The number of open connections, for leak checks in tests. */
  private[http] def openConnections: Int = connections.size()

  /** The read buffer size of each open connection, for tests. */
  private[http] def readBufferSizes: Seq[Int] =
    val sizes = Seq.newBuilder[Int]
    connections.forEach { state =>
      val r = state.reader
      if r != null then
        sizes += r.bufferCapacity
    }
    sizes.result()

  /** The number of streaming slots in use, for leak checks in tests. */
  private[http] def activeStreams: Int = streamLimiter.activeCount

  def start(): Unit =
    if !started.compareAndSet(false, true) then
      throw IllegalStateException("Server is already started")

    val l = listen(config.host, config.port, limits.backlog)
    listener = l
    boundPort = l.port

    workerPool = newWorkerPool()
    idlePoller = newIdlePoller(limits.idleTimeoutMillis).orNull

    running.set(true)
    val t = Thread(() => acceptLoop())
    t.setDaemon(true)
    t.setName(s"${config.name}-acceptor")
    t.start()
    acceptThread = t
    if limits.writeTimeoutMillis > 0 then
      val w = Thread(() => watchWrites())
      w.setDaemon(true)
      w.setName(s"${config.name}-write-watchdog")
      w.start()
      writeWatchdog = w
    onStarted()

  end start

  /**
    * Accept connections until stop(). The loop outlives any error, as Netty's event loop does: if
    * this thread died, the server would stop accepting while still reporting itself running, and
    * [[awaitTermination]] would return.
    */
  private def acceptLoop(): Unit =
    // Consecutive failures back off from 10 ms up to 1 s, so a persistent error (EMFILE, too many
    // open files) neither spins the CPU nor floods the log
    var backoffMillis = 0L
    while running.get() do
      try
        val conn = listener.accept()
        if conn == null then
          // While stopping, a failed accept means the listening socket was closed
          if running.get() then
            backoffMillis = SocketHttpServer.nextBackoff(backoffMillis)
            acceptWarnings.warn(
              s"Failed to accept a connection (e.g. too many open files); retrying in ${backoffMillis} ms"
            )
            Thread.sleep(backoffMillis)
        else
          backoffMillis = 0L
          dispatch(conn)
      catch
        case e: Throwable =>
          if running.get() then
            backoffMillis = SocketHttpServer.nextBackoff(backoffMillis)
            acceptWarnings.warn(s"Error in the accept loop: ${e}", e)
            try
              Thread.sleep(backoffMillis)
            catch
              case _: InterruptedException =>
                ()
    end while

  end acceptLoop

  private def dispatch(conn: SocketConnection): Unit =
    val pool = workerPool
    if pool != null && running.get() then
      // execute fails if stop() raced in between, or when no thread can be started (out of
      // memory); close the connection rather than leak it, and let the accept loop log the error
      try
        pool.execute(() => handleConnection(conn))
      catch
        case e: Throwable =>
          conn.close()
          if running.get() then
            throw e
    else
      conn.close()

  /**
    * Abort connections whose response write has made no progress for `writeTimeoutMillis`, so a
    * client that stops reading can't pin a worker. The aborted write fails, and the worker closes
    * the connection.
    */
  private def watchWrites(): Unit =
    val timeoutNanos = limits.writeTimeoutMillis * 1000000L
    val tick         = math.max(10L, math.min(1000L, limits.writeTimeoutMillis / 4L))
    while !stopped.get() do
      // Survive any error, so write timeouts never silently stop for every connection
      try
        Thread.sleep(tick)
        val now = System.nanoTime()
        connections.forEach { state =>
          try
            if state.conn.abortIfStalled(now, timeoutNanos) then
              debug(
                s"Closed a connection whose response write stalled for ${limits
                    .writeTimeoutMillis} ms"
              )
          catch
            case NonFatal(e) =>
              debug(s"Error aborting a connection: ${e.getMessage}")
        }
      catch
        case _: InterruptedException =>
          ()
        case e: Throwable =>
          watchdogWarnings.warn(s"Error in the write watchdog: ${e}", e)

  private def handleConnection(socket: SocketConnection): Unit = ConnectionSession(socket).run()

  /**
    * One accepted connection and the state that outlives a single worker turn: the reader (with any
    * buffered bytes) and the request deadline. A worker runs [[run]] until the connection closes,
    * or, with an [[IdlePoller]], until the connection waits for its next request. Then the poller
    * owns it and calls [[resume]] (a worker runs it again) or [[expire]] (it is closed). The
    * connection is closed exactly once, by whichever side owns it.
    */
  private class ConnectionSession(socket: SocketConnection) extends IdleConnection:
    private val conn  = WriteTrackingConnection(socket)
    private val state = ConnectionState(conn)
    connections.add(state)

    // Read with timeouts so an idle/slow/disconnected client can't pin a worker forever: an idle
    // keep-alive connection waits up to idleTimeoutMillis for the next request. Once a request has
    // started arriving, each read waits up to readTimeoutMillis, and the whole request must arrive
    // within requestTimeoutMillis. A timeout reads as 0 bytes, and the reader answers 408.
    private val requestTimeoutNanos = limits.requestTimeoutMillis * 1000000L
    private var requestDeadline     = 0L
    private val reader              = HttpConnectionReader(
      (buf, off, len, idle) =>
        // After stop() begins, don't wait for another request (see drainConnections)
        if !running.get() && state.idle then
          -1
        else if idle then
          conn.read(buf, off, len, limits.idleTimeoutMillis)
        else if requestTimeoutNanos <= 0 then
          conn.read(buf, off, len, limits.readTimeoutMillis)
        else
          val now = System.nanoTime()
          if requestDeadline == 0L then
            requestDeadline = now + requestTimeoutNanos
          val left = (requestDeadline - now) / 1000000L
          if left <= 0 then
            0
          else
            conn.read(buf, off, len, math.min(limits.readTimeoutMillis.toLong, left).toInt)
      ,
      limits.request,
      () => conn.write(HttpResponseWriter.Continue100)
    )

    state.reader = reader

    // Read what is already waiting, without waiting, before parking (see IdlePoller.park)
    private val readNow: HttpConnectionReader.ReadNow =
      (buf, off, len) => conn.read(buf, off, len, 0)

    /** Serve requests on this worker until the connection closes or is handed to the poller. */
    def run(): Unit =
      var parked = false
      onConnectionThread.set(true)
      try
        parked = serve()
      catch
        case NonFatal(e) =>
          // Disconnects and malformed requests surface as ReadResult values, so an exception here
          // is a bug (or memory corruption) worth seeing
          warn(s"Connection handling error: ${e.getMessage}", e)
      finally
        onConnectionThread.remove()
        if !parked then
          finish()

    /** Returns true when the poller took the connection, false when it should close. */
    private def serve(): Boolean =
      var continue = true
      while continue do
        state.idle = true
        requestDeadline = 0L
        // Between requests, give the worker back: the poller resumes the connection once its next
        // request arrives. Bytes already buffered (a pipelined request) or already waiting on the
        // socket are served right away, and a peer that has closed ends the connection.
        // drainLineBreaks also shrinks a buffer grown for a large request, so a kept-alive
        // connection holds only a small one between requests
        val drained = reader.drainLineBreaks()
        val poller  = idlePoller
        if poller != null && running.get() && drained then
          val n = reader.readWaiting(readNow)
          if n < 0 then
            return false
          if n == 0 && poller.park(socket, this) then
            return true
        val result = reader.readRequest()
        state.idle = false
        result match
          case ReadResult.Closed =>
            continue = false
          case ReadResult.Error(status, message) =>
            conn.write(
              HttpResponseWriter.serialize(
                Response(status).withTextContent(message),
                keepAlive = false,
                includeBody = true
              )
            )
            continue = false
          case ReadResult.Req(received, keepAlive) =>
            // As in Netty's pipeline, decode the body before the host check, filters, and handler
            val decoded =
              if limits.requestDecompression then
                RequestDecompression.decode(received, limits.maxDecompressedRequestSize)
              else
                Right(received)
            decoded match
              case Left(rejection) =>
                conn.write(
                  HttpResponseWriter.serialize(rejection, keepAlive = false, includeBody = true)
                )
                continue = false
              case Right(request) =>
                continue = serveRequest(state, request, keepAlive)
      end while
      false

    end serve

    override def resume(): Unit =
      val pool = workerPool
      try
        pool.execute(() => run())
      catch
        case NonFatal(_) =>
          // The pool is shutting down: close the connection instead of leaking it
          finish()

    override def expire(): Unit = finish()

    private def finish(): Unit =
      connections.remove(state)
      conn.close()

  end ConnectionSession

  /** Serve one parsed request. Returns whether the connection may serve another one. */
  private def serveRequest(state: ConnectionState, request: Request, keepAlive: Boolean): Boolean =
    val conn = state.conn
    HttpServerSecurity.checkHost(request, allowedHosts) match
      case Some(rejection) =>
        debug(s"Rejected request for host ${request.header(HttpHeader.Host)}")
        conn.write(HttpResponseWriter.serialize(rejection, keepAlive = false, includeBody = true))
        false
      case None =>
        webSocketRouteFor(request) match
          case Some(route) =>
            // WebSocket upgrade: the WS handler owns the connection until it closes.
            handleWebSocketUpgrade(state, request, route)
            false
          case None =>
            handleHttpRequest(state, request, keepAlive)

  /**
    * Handle one non-WebSocket request. Returns whether the connection may stay open (keep-alive).
    */
  private def handleHttpRequest(
      state: ConnectionState,
      request: Request,
      requestKeepAlive: Boolean
  ): Boolean =
    val conn     = state.conn
    val response = runHandler(request)
    // Once stop() begins, answer with Connection: close so the client opens no new request here
    val keepAlive = requestKeepAlive && running.get()
    val isHead    = request.method == HttpMethod.HEAD
    val sent      =
      if response.isEventStream then
        if isHead then
          // HEAD: send the streaming response headers but no event body.
          conn.write(HttpResponseWriter.serializeSseHeaders(response, keepAlive))
        else
          withStreamingSlot(state) {
            streamSse(state, response, keepAlive)
          }.getOrElse(false)
      else
        response.content match
          case file: HttpContent.FileContent if !HttpResponseWriter.bodyForbidden(response) =>
            sendFile(conn, response, file, keepAlive, includeBody = !isHead)
          case _ =>
            // A HEAD response carries headers (incl. Content-Length) but no body, and is never
            // compressed, so its headers match what GET would send uncompressed (as in Netty)
            val encoded =
              if isHead then
                response
              else
                ResponseCompression.encode(request, response)
            HttpResponseWriter.write(conn, encoded, keepAlive, includeBody = !isHead)
    keepAlive && sent

  end handleHttpRequest

  /**
    * Send a file body in bounded chunks, so the file is never held in memory. Returns false when
    * the connection can't be reused: a failed send, or a file that ended before its Content-Length
    * (the client then sees a truncated response).
    */
  private def sendFile(
      conn: SocketConnection,
      response: Response,
      file: HttpContent.FileContent,
      keepAlive: Boolean,
      includeBody: Boolean
  ): Boolean =
    // Open before sending anything, so a file that can't be read still gets an error response
    val opened =
      try
        Right(StaticFilesCompat.openAt(file.path, file.offset))
      catch
        case NonFatal(e) =>
          warn(s"Failed to open ${file.path}: ${e.getMessage}", e)
          Left(e)
    opened match
      case Left(e) =>
        conn.write(HttpResponseWriter.serialize(errorResponse(e), keepAlive, includeBody))
      case Right(in) =>
        try
          var ok = conn.write(HttpResponseWriter.serializeHead(response, keepAlive, file.length))
          if includeBody then
            val buf       = new Array[Byte](SocketHttpServer.FileChunkSize)
            var remaining = file.length
            while ok && remaining > 0 do
              val n = in.read(buf, 0, math.min(buf.length.toLong, remaining).toInt)
              if n < 0 then
                warn(s"${file.path} ended ${remaining} bytes before its Content-Length")
                ok = false
              else
                remaining -= n
                ok = conn.write(buf, 0, n)
          ok
        catch
          case NonFatal(e) =>
            warn(s"Failed to send ${file.path}: ${e.getMessage}", e)
            false
        finally
          in.close()
    end match

  end sendFile

  /**
    * Run a long-lived connection (SSE stream, WebSocket) in one of the limited streaming slots.
    * Each one parks a worker until it closes, so without a cap a few idle clients would occupy
    * every worker and starve ordinary requests. Over the limit, answer 503 and return `None`.
    */
  private def withStreamingSlot[A](state: ConnectionState)(body: => A): Option[A] =
    val conn = state.conn
    if !streamLimiter.tryAcquire() then
      warn(
        s"Too many concurrent streaming connections (limit ${streamLimiter
            .limit}); rejecting with 503"
      )
      conn.write(
        HttpResponseWriter.serialize(
          Response.serviceUnavailable,
          keepAlive = false,
          includeBody = true
        )
      )
      None
    else
      // stop() closes a streaming connection at once rather than waiting for it to end, as Netty
      // closes WebSockets; an SSE stream may never end, and EventSource clients reconnect
      state.streaming = true
      if !running.get() then
        // stop() began before the flag was set, so it may have missed this connection
        state.conn.interrupt()
      try
        Some(body)
      finally
        state.streaming = false
        streamLimiter.release()

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
  private def streamSse(state: ConnectionState, response: Response, keepAlive: Boolean): Boolean =
    val conn = state.conn
    if !conn.write(HttpResponseWriter.serializeSseHeaders(response, keepAlive)) then
      false
    else
      val done = CountDownLatch(1)
      val ok   = AtomicBoolean(true)
      // stop() ends the stream at once, rather than at the next disconnect probe
      val drained = AtomicBoolean(false)
      state.onDrain =
        () =>
          drained.set(true)
          done.countDown()
      if !running.get() then
        state.onDrain.run()
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
                if !conn.write(HttpResponseWriter.chunk(data)) then
                  ok.set(false)
                  subscription.cancel
                  done.countDown()
            }
          case OnFailure(e) =>
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
      val probe = new Array[Byte](64)
      try
        while alive && !done.await(limits.idleTimeoutMillis.toLong, TimeUnit.MILLISECONDS) do
          // SSE clients shouldn't send, so any bytes are discarded; -1 is a disconnect.
          if conn.read(probe, 0, probe.length, 0) < 0 then
            alive = false
      catch
        case _: InterruptedException =>
          // stop() past its deadline: end the stream, and keep the flag for the worker
          alive = false
          Thread.currentThread().interrupt()
      finally
        state.onDrain = null
      if drained.get() then
        alive = false
      if !alive then
        // Set cancelled under the lock so any in-flight emitter write finishes first and no new write
        // starts; the connection can then be closed safely after this returns.
        writeLock.synchronized {
          cancelled.set(true)
        }
        subscription.cancel
      // Terminate the chunked body if the client is still there.
      ok.get() && alive && conn.write(HttpResponseWriter.finalChunk)
    end if

  end streamSse

  /**
    * Run the handler and block for its single response. The handler may be asynchronous (Rx), so
    * the worker thread waits on a latch for the first event.
    */
  private def runHandler(request: Request): Response =
    try
      checkHeaders(awaitRx(handler.handle(request)))
    catch
      case NonFatal(e) =>
        if HttpServerSecurity.isClientError(e) then
          debug(s"Error handling request: ${e.getMessage}", e)
        else
          warn(s"Error handling request: ${e.getMessage}", e)
        errorResponse(e)

  private def errorResponse(e: Throwable): Response = HttpServerSecurity.errorResponse(
    e,
    config.detailedErrors
  )

  /**
    * Replace a response whose headers contain CR/LF with a 500: the writer copies header values
    * verbatim, so such a value would split the response.
    */
  private def checkHeaders(response: Response): Response =
    try
      HttpServerSecurity.validateHeaders(response.headers)
      response
    catch
      case e: IllegalArgumentException =>
        warn(s"Invalid response: ${e.getMessage}", e)
        errorResponse(e)

  /**
    * Block for the single Response produced by an Rx (the request handler, or a WebSocket upgrade
    * filter gate), bounded by the handler timeout so a never-completing Rx can't wedge the worker.
    */
  private def awaitRx(rx: Rx[Response], onEmpty: Response = Response.notFound): Response =
    rx match
      case Rx.SingleOp(v) =>
        // A synchronous handler (Rx.single): evaluate it here, as the runner would, without its
        // latch and event objects
        try
          v.eval
        catch
          case NonFatal(e) =>
            handlerError(e)
      case _ =>
        awaitRxAsync(rx, onEmpty)

  private def handlerError(e: Throwable): Response =
    if HttpServerSecurity.isClientError(e) then
      debug(s"Error in handler: ${e.getMessage}", e)
    else
      warn(s"Error in handler: ${e.getMessage}", e)
    errorResponse(e)

  private def awaitRxAsync(rx: Rx[Response], onEmpty: Response): Response =
    val latch  = CountDownLatch(1)
    val result = AtomicReference[Response]()
    val run    =
      RxRunner.runOnce(rx) {
        case OnNext(response) =>
          result.set(response.asInstanceOf[Response])
          latch.countDown()
        case OnFailure(e) =>
          result.set(handlerError(e))
          latch.countDown()
        case OnCompletion =>
          // Completed without emitting a response.
          result.compareAndSet(null, onEmpty)
          latch.countDown()
      }
    val completed =
      try
        if limits.handlerTimeoutMillis <= 0 then
          latch.await()
          true
        else
          latch.await(limits.handlerTimeoutMillis, TimeUnit.MILLISECONDS)
      catch
        case e: InterruptedException =>
          // stop() past its deadline: stop the handler's work too
          run.cancel
          throw e
    if completed then
      result.get()
    else
      // Cancel the handler's work, so a timed-out request doesn't keep running with no one to
      // answer
      run.cancel
      warn(s"Handler timed out after ${limits.handlerTimeoutMillis} ms")
      Response.serviceUnavailable

  end awaitRxAsync

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
    * the 101 handshake and hand the connection to [[SocketWebSocket.serve]] (which blocks this
    * worker for the WebSocket's lifetime).
    */
  private def handleWebSocketUpgrade(
      state: ConnectionState,
      request: Request,
      route: WebSocketRoute
  ): Unit =
    val conn = state.conn
    WebSocketHandshake.validate(request, config.allowedOrigins) match
      case Left(rejection) =>
        // Disallowed origin (403), missing key (400), or unsupported version (426)
        conn.write(HttpResponseWriter.serialize(rejection, keepAlive = false, includeBody = true))
      case Right(key) =>
        // Capture the request as threaded through the filter, so attributes a filter adds during
        // the handshake reach the WebSocketContext (matching the Netty backend).
        val upgradeRequest = AtomicReference[Request](request)
        val gate           = RxHttpHandler { req =>
          upgradeRequest.set(req)
          Rx.single(Response.ok)
        }
        // With readable rejections, an empty Rx is a 403 (closed as 4403); otherwise a 404.
        val onEmpty =
          if route.closeOnRejection then
            Response.forbidden
          else
            Response.notFound
        val verdict =
          try
            checkHeaders(awaitRx(route.filter.apply(request, gate), onEmpty))
          catch
            case NonFatal(e) =>
              if HttpServerSecurity.isClientError(e) then
                debug(s"WebSocket upgrade filter error: ${e.getMessage}", e)
              else
                warn(s"WebSocket upgrade filter error: ${e.getMessage}", e)
              errorResponse(e)
        if !verdict.isSuccessful && !route.closeOnRejection then
          conn.write(HttpResponseWriter.serialize(verdict, keepAlive = false, includeBody = true))
        else
          // A readable rejection completes the handshake and closes at once with 4000 + status.
          val served =
            if verdict.isSuccessful then
              route
            else
              WebSocketRejection.rejectingRoute(route, verdict)
          // Take a streaming slot only for a completed handshake, so an HTTP rejection gets its own
          // status (403/400/426) and never holds a slot. A readable rejection holds one for its
          // close handshake; with every slot taken it gets 503 instead of 44xx.
          withStreamingSlot(state) {
            val handshake =
              s"HTTP/1.1 101 Switching Protocols\r\n" + s"${HttpHeader.Upgrade}: websocket\r\n" +
                s"${HttpHeader.Connection}: Upgrade\r\n" +
                s"${HttpHeader.SecWebSocketAccept}: ${WebSocketFrame.acceptKey(key)}\r\n\r\n"
            if conn.write(handshake.getBytes(StandardCharsets.ISO_8859_1)) then
              val accepted = upgradeRequest.get()
              SocketWebSocket.serve(
                conn,
                accepted,
                served.handlerFactory(accepted),
                limits.webSocketMaxFrameSize,
                limits.webSocketPingIntervalMillis
              )
          }
    end match

  end handleWebSocketUpgrade

  override def whenReady: Rx[HttpServer] = Rx.single(this)

  override def isRunning: Boolean = running.get() && !stopped.get()

  override def localPort: Int = boundPort

  override def localAddress: String = s"${config.host}:${boundPort}"

  override def stop(): Unit =
    if !stopped.compareAndSet(false, true) then
      return
    running.set(false)
    if onConnectionThread.get() then
      // Called from a handler (e.g. a /shutdown endpoint): waiting here would wait for this very
      // request to finish, so shut down on another thread and return, as the Netty backend does.
      // Not a daemon: connection threads are virtual (daemons), and awaitTermination returns once
      // the listener closes, so this thread keeps the JVM alive until the drain finishes
      val t = Thread(() => shutdown())
      t.setName(s"${config.name}-shutdown")
      t.start()
    else
      shutdown()

  end stop

  private def shutdown(): Unit =
    // Closing the listening socket unblocks accept() so the accept thread can exit. Guard it so a
    // close failure can't abort the rest of shutdown.
    if listener != null then
      try
        listener.close()
      catch
        case NonFatal(e) =>
          debug(s"Error closing server socket: ${e.getMessage}")
    // As the Netty backend does (ADR 2026-10-27): close idle connections now and let busy ones
    // close after their last response, then force-close whatever is left at the deadline. The
    // poller closes the idle connections it holds; a worker that would park one after this closes
    // it itself, since the reader sees the server stopping.
    Option(idlePoller).foreach { poller =>
      try
        poller.close()
      catch
        case NonFatal(e) =>
          debug(s"Error closing the idle poller: ${e.getMessage}")
    }
    drainConnections(onlyIdle = true)
    Option(workerPool).foreach { pool =>
      pool.shutdown()
      if !pool.awaitTermination(SocketHttpServer.ShutdownTimeoutMillis, TimeUnit.MILLISECONDS) then
        drainConnections(onlyIdle = false)
        pool.shutdownNow()
        pool.awaitTermination(SocketHttpServer.ForcedShutdownTimeoutMillis, TimeUnit.MILLISECONDS)
    }
    debug(s"${config.name} stopped")

  end shutdown

  /**
    * Wake the worker of each connection waiting for its next request or streaming (or every
    * connection, when `onlyIdle` is false), so it sees the server stopping and closes. The worker
    * closes the connection itself, so a connection is never closed under a thread still using it.
    */
  private def drainConnections(onlyIdle: Boolean): Unit = connections.forEach { state =>
    if !onlyIdle || state.idle || state.streaming then
      try
        state.conn.interrupt()
        val wake = state.onDrain
        if wake != null then
          wake.run()
      catch
        case NonFatal(e) =>
          debug(s"Error interrupting a connection: ${e.getMessage}")
  }

  override def awaitTermination(): Unit = Option(acceptThread).foreach(_.join())

end SocketHttpServer

/** An open connection, and whether its worker is waiting for the next request or streaming. */
private class ConnectionState(val conn: WriteTrackingConnection):
  @volatile
  var idle: Boolean = true

  // Serving an SSE stream or a WebSocket, which stop() closes without waiting
  @volatile
  var streaming: Boolean = false

  // The connection's request reader, set once the session starts; read by tests only
  @volatile
  var reader: HttpConnectionReader = null

  // Ends a stream that waits on something other than the socket (an SSE source), on stop()
  @volatile
  var onDrain: Runnable = null

private[http] object SocketHttpServer:
  // Bytes read from a file per send of a streamed response
  private val FileChunkSize = 64 * 1024
  // How long stop() waits for in-flight requests, as Netty's default shutdown timeout
  private final val ShutdownTimeoutMillis       = 30000L
  private final val ForcedShutdownTimeoutMillis = 1000L
  private final val MinAcceptBackoffMillis      = 10L
  private final val MaxAcceptBackoffMillis      = 1000L

  /** The next wait after a failed accept: doubling from 10 ms, up to 1 s. */
  private[http] def nextBackoff(lastMillis: Long): Long =
    if lastMillis <= 0 then
      MinAcceptBackoffMillis
    else
      math.min(lastMillis * 2, MaxAcceptBackoffMillis)

/**
  * Logs a recurring warning at most once per interval, with the number of repeats it held back, so
  * a persistent failure (such as running out of file descriptors) is visible without flooding the
  * log.
  */
private[http] class RateLimitedWarning(
    log: (String, Throwable) => Unit,
    intervalMillis: Long = 5000L
):
  private var lastNanos  = 0L
  private var suppressed = 0

  def warn(message: String, cause: Throwable = null): Unit =
    val now  = System.nanoTime()
    val emit = synchronized {
      if lastNanos != 0L && now - lastNanos < intervalMillis * 1000000L then
        suppressed += 1
        None
      else
        val held = suppressed
        lastNanos = now
        suppressed = 0
        Some(held)
    }
    emit.foreach { held =>
      val repeats =
        if held > 0 then
          s" (${held} more since the last warning)"
        else
          ""
      log(s"${message}${repeats}", cause)
    }

end RateLimitedWarning

/**
  * A connection that records when its current write started, so the server's watchdog can abort a
  * write that makes no progress. A write goes out in pieces, and each piece restarts the clock, so
  * a slow but moving client is never cut off.
  */
private[http] class WriteTrackingConnection(underlying: SocketConnection) extends SocketConnection:
  // System.nanoTime() when the current piece started, or 0 while no write is in progress
  @volatile
  private var pieceStarted = 0L

  // Set when the write watchdog aborts this connection because the peer stopped reading
  @volatile
  private var abortedStalled = false

  def stalled: Boolean = abortedStalled

  /** Abort a write that made no progress for `timeoutNanos`. Returns true if it aborted. */
  def abortIfStalled(nowNanos: Long, timeoutNanos: Long): Boolean =
    if writeStalled(nowNanos, timeoutNanos) then
      abortedStalled = true
      underlying.abort()
      true
    else
      false

  private def writeStalled(nowNanos: Long, timeoutNanos: Long): Boolean =
    val t = pieceStarted
    t != 0L && nowNanos - t > timeoutNanos

  override def read(buf: Array[Byte], off: Int, len: Int, timeoutMillis: Int): Int = underlying
    .read(buf, off, len, timeoutMillis)

  override def write(data: Array[Byte], off: Int, len: Int): Boolean =
    var pos   = off
    val until = off + len
    var ok    = true
    try
      while ok && pos < until do
        val n = math.min(WriteTrackingConnection.PieceSize, until - pos)
        pieceStarted = math.max(System.nanoTime(), 1L)
        ok = underlying.write(data, pos, n)
        pos += n
    finally pieceStarted = 0L
    ok

  override def interrupt(): Unit = underlying.interrupt()
  override def abort(): Unit     = underlying.abort()
  override def close(): Unit     = underlying.close()

end WriteTrackingConnection

private object WriteTrackingConnection:
  private final val PieceSize = 64 * 1024
