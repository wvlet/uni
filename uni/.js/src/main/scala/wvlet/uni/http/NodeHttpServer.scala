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

import scala.concurrent.{ExecutionContext, Promise}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/**
  * Node.js-based HTTP server implementation, backed by the built-in `http` module. It implements
  * the platform-neutral [[HttpServer]] contract and runs the same [[RxHttpHandler]] /
  * [[RxHttpFilter]] chain as the JVM (Netty) backend.
  *
  * Node's socket bind is asynchronous, so [[localPort]] is only known after the underlying
  * `listening` event fires. Use [[whenReady]] (or `NodeServerConfig.startAndAwait`) before reading
  * the port or connecting a client.
  */
class NodeHttpServer(config: NodeServerConfig) extends HttpServer with LogSupport:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private val handler: RxHttpHandler = config.effectiveHandler

  private var server: js.Dynamic = null
  // Set synchronously in start() so a second start() before the async bind completes is rejected.
  private var started: Boolean = false
  // Set on the async `listening` event; cleared on stop().
  private var running: Boolean = false
  // Guards stop() so it is idempotent (avoids a double `server.close()`).
  private var stopped: Boolean = false

  private val readyPromise = Promise[NodeHttpServer]()

  // Node binds asynchronously, so readiness is signaled on the `listening` event (see start()).
  override def whenReady: Rx[NodeHttpServer] = Rx.future(readyPromise.future)

  def start(): Unit =
    if started then
      throw IllegalStateException("Server is already started")
    started = true

    val http = NodeModules.builtin("http")
    server =
      http.applyDynamic("createServer")(
        ((req: js.Dynamic, res: js.Dynamic) => handleRequest(req, res)): js.Function2[
          js.Dynamic,
          js.Dynamic,
          Unit
        ]
      )

    // A bind failure (EADDRINUSE/EACCES) emits `error`, not `listening`. Without this handler the
    // error is uncaught (process crash) and readyPromise never completes, hanging startAndAwait.
    val onError: js.Function1[js.Dynamic, Unit] =
      (err: js.Dynamic) =>
        val message =
          if js.isUndefined(err) || err == null then
            "unknown error"
          else
            s"${err}"
        warn(s"Node server error: ${message}")
        if !readyPromise.isCompleted then
          readyPromise.failure(RuntimeException(s"Node server failed to start: ${message}"))

    val onListening: js.Function0[Unit] =
      () =>
        running = true
        debug(s"Node server started at ${localAddress}")
        readyPromise.success(this)

    server.applyDynamic("on")("error", onError)

    // Node's http server has no built-in WebSocket; handle the raw `upgrade` event ourselves.
    if config.webSocketRoutes.nonEmpty then
      val onUpgrade: js.Function3[js.Dynamic, js.Dynamic, js.Dynamic, Unit] =
        (req: js.Dynamic, socket: js.Dynamic, head: js.Dynamic) =>
          NodeWebSocket.handleUpgrade(
            req,
            socket,
            head,
            config.webSocketRoutes,
            config.webSocketMaxFrameSize
          )
      server.applyDynamic("on")("upgrade", onUpgrade)

    server.applyDynamic("listen")(config.port, config.host, onListening)

  end start

  private def handleRequest(req: js.Dynamic, res: js.Dynamic): Unit =
    // The whole synchronous setup runs in the createServer callback, outside any later async
    // try/catch, so guard it: an error here (an unknown verb, a null/missing field, ...) must
    // produce a response, not an uncaught exception that crashes the Node process.
    try
      // Node accepts arbitrary verbs; an unknown method is a client error, not a server crash.
      val method =
        HttpMethod.of(req.method.asInstanceOf[String]) match
          case Some(m) =>
            m
          case None =>
            writeResponse(res, Response.badRequest(s"Unsupported HTTP method: ${req.method}"))
            return

      val uri = req.url.asInstanceOf[String]

      // rawHeaders is a flat [k, v, k, v, ...] array, preserving duplicate header names. Guard
      // against a non-standard/mock request object that lacks it.
      val headersBuilder = HttpMultiMap.newBuilder
      val rawHeaders     =
        if js.isUndefined(req.rawHeaders) || req.rawHeaders == null then
          js.Array[String]()
        else
          req.rawHeaders.asInstanceOf[js.Array[String]]
      var i = 0
      while i + 1 < rawHeaders.length do
        headersBuilder.add(rawHeaders(i), rawHeaders(i + 1))
        i += 2
      val headers = headersBuilder.result()

      val bodyChunks = Array.newBuilder[Byte]

      val onData: js.Function1[js.Dynamic, Unit] =
        (chunk: js.Dynamic) =>
          bodyChunks ++= NodeBytes.toBytes(chunk)
          ()

      val onEnd: js.Function0[Unit] =
        () =>
          try
            val content = HttpContent.fromBytes(bodyChunks.result(), headers)
            val request = Request(method = method, uri = uri, headers = headers, content = content)
            runResponse(res, handler.handle(request))
          catch
            case e: Throwable =>
              warn(s"Error handling request: ${e.getMessage}", e)
              writeResponse(res, Response.internalServerError(e.getMessage))

      req.applyDynamic("on")("data", onData)
      req.applyDynamic("on")("end", onEnd)
    catch
      case e: Throwable =>
        warn(s"Error initializing request: ${e.getMessage}", e)
        writeResponse(res, Response.internalServerError(e.getMessage))

  end handleRequest

  private def runResponse(res: js.Dynamic, rx: Rx[Response]): Unit =
    RxRunner.runOnce(rx) {
      case OnNext(response) =>
        writeResponse(res, response.asInstanceOf[Response])
      case OnError(e) =>
        warn(s"Error in Rx handler: ${e.getMessage}", e)
        writeResponse(res, Response.internalServerError(e.getMessage))
      case OnCompletion =>
        writeResponse(res, Response.notFound)
    }

  private def writeResponse(res: js.Dynamic, response: Response): Unit =
    // setHeader/end can throw if the client already disconnected (destroyed socket, headers
    // already sent). For async handlers this runs on the event loop outside any caller's try, so
    // an uncaught throw would crash the Node process — guard the whole write here. Every caller
    // (runResponse, handleRequest's catch blocks, the 400 path) relies on this not throwing.
    try
      if response.isEventStream then
        writeSseResponse(res, response)
      else
        setHeaders(res, response.headers)
        response
          .content
          .contentType
          .foreach { ct =>
            res.applyDynamic("setHeader")(HttpHeader.ContentType, ct.value)
          }
        res.updateDynamic("statusCode")(response.status.code)
        val bytes = response.content.toContentBytes
        if bytes.isEmpty then
          res.applyDynamic("end")()
        else
          // Node sets Content-Length automatically for a buffered body. The body must be a
          // Uint8Array/Buffer (Node rejects Int8Array), so view the bytes as unsigned.
          res.applyDynamic("end")(NodeBytes.toUint8Array(bytes))
    catch
      case e: Throwable =>
        warn(s"Failed to write response: ${e.getMessage}", e)

  private def writeSseResponse(res: js.Dynamic, response: Response): Unit =
    // Stream Server-Sent Events. Node uses chunked transfer encoding automatically when no
    // Content-Length is set. This mirrors NettyRequestHandler.sendSseResponse.
    res.applyDynamic("setHeader")(HttpHeader.ContentType, ContentType.TextEventStream.value)
    res.applyDynamic("setHeader")(HttpHeader.CacheControl, "no-cache")
    res.applyDynamic("setHeader")(HttpHeader.Connection, "keep-alive")
    response
      .headers
      .entries
      .foreach { case (name, value) =>
        if !name.equalsIgnoreCase(HttpHeader.ContentType) then
          res.applyDynamic("setHeader")(name, value)
      }
    res.updateDynamic("statusCode")(response.status.code)

    def endQuietly(): Unit =
      try
        res.applyDynamic("end")()
      catch
        case e: Throwable =>
          debug(s"SSE end failed: ${e.getMessage}")

    // RxRunner.run can emit synchronously (e.g. Rx.fromSeq), so a write failure may fire a callback
    // before run() returns and the real subscription is assigned. A delegate Cancelable lets us
    // both flip a `cancelled` flag (so subsequent synchronous events are skipped) and forward to
    // the real subscription once it exists — covering both the sync and async cases.
    var realSubscription: Cancelable = Cancelable.empty
    var cancelled                    = false
    val subscription: Cancelable     = Cancelable { () =>
      cancelled = true
      realSubscription.cancel
    }

    realSubscription =
      RxRunner.run(response.events) {
        case OnNext(event) =>
          // write can throw ("write after end"/destroyed socket) if the client left mid-stream;
          // on the event loop that would crash the process, so cancel the stream instead.
          if !cancelled then
            try
              res.applyDynamic("write")(event.asInstanceOf[ServerSentEvent].toContent)
            catch
              case e: Throwable =>
                warn(s"SSE write failed, cancelling stream: ${e.getMessage}", e)
                subscription.cancel
        case OnError(e) =>
          warn(s"SSE stream error: ${e.getMessage}", e)
          endQuietly()
        case OnCompletion =>
          endQuietly()
      }

    // If the client disconnects before the stream finishes, cancel the subscription so the
    // publisher stops running and we don't write to a closed response.
    val onClose: js.Function0[Unit] = () => subscription.cancel
    res.applyDynamic("on")("close", onClose)

  end writeSseResponse

  /**
    * Set response headers, preserving duplicate header names (e.g. multiple `Set-Cookie`) by
    * passing an array value to Node when a name appears more than once.
    */
  private def setHeaders(res: js.Dynamic, headers: HttpMultiMap): Unit = headers
    .entries
    .groupBy(_._1)
    .foreach { case (name, kvs) =>
      val values = kvs.map(_._2)
      if values.sizeIs == 1 then
        res.applyDynamic("setHeader")(name, values.head)
      else
        res.applyDynamic("setHeader")(name, values.toJSArray)
    }

  override def stop(): Unit =
    // Check `started`, not `running`: stop() may race a pending bind (started but not yet
    // listening), and the server handle must still be closed and any whenReady waiter unblocked.
    if started && !stopped && server != null then
      stopped = true
      try
        server.applyDynamic("close")()
      catch
        case e: Throwable =>
          debug(s"Node server close failed: ${e.getMessage}")
      running = false
      // If stopped before the bind completed, fail readiness so startAndAwait/whenReady don't hang.
      if !readyPromise.isCompleted then
        readyPromise.failure(IllegalStateException("Server stopped before it finished starting"))
      debug(s"Node server stopped")

  override def awaitTermination(): Unit =
    // Node has no blocking await; the event loop keeps the process alive while the server listens.
    ()

  override def isRunning: Boolean = running

  // The bound address object (`{address, port, family}`), or null before start() / before the
  // `listening` event fires (Node's `server.address()` returns null until then).
  private def boundAddress: js.Dynamic =
    if server == null then
      null
    else
      val addr = server.applyDynamic("address")()
      if js.isUndefined(addr) then
        null
      else
        addr

  // Before the server is listening, fall back to the configured port (matching NettyHttpServer,
  // which returns config.port pre-bind) rather than a -1 sentinel, for cross-backend consistency.
  override def localPort: Int =
    val addr = boundAddress
    if addr == null then
      config.port
    else
      addr.port.asInstanceOf[Int]

  // Report the actually-bound host:port (matching Netty), falling back to the configured host:port
  // before the server is listening. Reads boundAddress once rather than via localPort.
  override def localAddress: String =
    val addr = boundAddress
    if addr == null then
      s"${config.host}:${config.port}"
    else
      s"${addr.address.asInstanceOf[String]}:${addr.port.asInstanceOf[Int]}"

end NodeHttpServer

object NodeHttpServer:
  def apply(config: NodeServerConfig): NodeHttpServer = new NodeHttpServer(config)
