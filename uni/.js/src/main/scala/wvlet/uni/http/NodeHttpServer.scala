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

import scala.concurrent.{ExecutionContext, Promise}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.*

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
    server.applyDynamic("listen")(config.port, config.host, onListening)

  end start

  private def handleRequest(req: js.Dynamic, res: js.Dynamic): Unit =
    // Node accepts arbitrary verbs; an unknown method must not crash the process (the throw would
    // be uncaught here, outside the onEnd try). Respond with 400 instead.
    val method =
      HttpMethod.of(req.method.asInstanceOf[String]) match
        case Some(m) =>
          m
        case None =>
          writeResponse(res, Response.badRequest(s"Unsupported HTTP method: ${req.method}"))
          return
    val uri = req.url.asInstanceOf[String]

    // rawHeaders is a flat [k, v, k, v, ...] array, preserving duplicate header names.
    val headersBuilder = HttpMultiMap.newBuilder
    val rawHeaders     = req.rawHeaders.asInstanceOf[js.Array[String]]
    var i              = 0
    while i + 1 < rawHeaders.length do
      headersBuilder.add(rawHeaders(i), rawHeaders(i + 1))
      i += 2
    val headers = headersBuilder.result()

    val bodyChunks = Array.newBuilder[Byte]

    val onData: js.Function1[js.Dynamic, Unit] =
      (chunk: js.Dynamic) =>
        bodyChunks ++= toBytes(chunk)
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

  end handleRequest

  private def runResponse(res: js.Dynamic, rx: Rx[Response]): Unit =
    RxRunner.runOnce(rx) { event =>
      // For async handlers this runs later on the microtask queue, outside handleRequest's
      // try/catch, so guard the write itself: a failed write (e.g. client gone, headers already
      // sent) must be logged, not thrown uncaught (which would crash the Node process).
      try
        event match
          case OnNext(response) =>
            writeResponse(res, response.asInstanceOf[Response])
          case OnError(e) =>
            warn(s"Error in Rx handler: ${e.getMessage}", e)
            writeResponse(res, Response.internalServerError(e.getMessage))
          case OnCompletion =>
            writeResponse(res, Response.notFound)
      catch
        case e: Throwable =>
          warn(s"Failed to write response: ${e.getMessage}", e)
    }

  private def writeResponse(res: js.Dynamic, response: Response): Unit =
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
        res.applyDynamic("end")(toUint8Array(bytes))

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

    RxRunner.run(response.events) {
      case OnNext(event) =>
        res.applyDynamic("write")(event.asInstanceOf[ServerSentEvent].toContent)
      case OnError(e) =>
        warn(s"SSE stream error: ${e.getMessage}", e)
        res.applyDynamic("end")()
      case OnCompletion =>
        res.applyDynamic("end")()
    }

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

  /**
    * Copy a Node `Buffer`/`Uint8Array` chunk into a JVM byte array. The chunk may be a view into a
    * larger pooled buffer, so honor its `byteOffset`/`length`.
    */
  private def toBytes(chunk: js.Dynamic): Array[Byte] =
    val u8 = chunk.asInstanceOf[Uint8Array]
    Int8Array(u8.buffer, u8.byteOffset, u8.length).toArray

  /**
    * View a JVM byte array as a Node-compatible `Uint8Array` (Node rejects `Int8Array` response
    * bodies). The two share the same underlying bytes; only the signed/unsigned interpretation
    * differs, which Node ignores when writing raw bytes.
    */
  private def toUint8Array(bytes: Array[Byte]): Uint8Array =
    val i8 = bytes.toTypedArray
    Uint8Array(i8.buffer, i8.byteOffset, i8.length)

  override def stop(): Unit =
    if running && server != null then
      server.applyDynamic("close")()
      running = false
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

  override def localPort: Int =
    val addr = boundAddress
    if addr == null then
      -1
    else
      addr.port.asInstanceOf[Int]

  // Report the actually-bound host:port (matching Netty), falling back to the configured host
  // before the server is listening.
  override def localAddress: String =
    val addr = boundAddress
    if addr == null then
      s"${config.host}:${localPort}"
    else
      s"${addr.address.asInstanceOf[String]}:${addr.port.asInstanceOf[Int]}"

end NodeHttpServer

object NodeHttpServer:
  def apply(config: NodeServerConfig): NodeHttpServer = new NodeHttpServer(config)
