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

import java.nio.charset.StandardCharsets
import scala.scalajs.js
import scala.util.control.NonFatal

/**
  * Node.js WebSocket (RFC 6455) handling on a raw `net.Socket` from the http server's `'upgrade'`
  * event. Node has no built-in WebSocket server, so the handshake + framing are done by hand,
  * reusing the cross-platform [[WebSocketFrame]] / [[WebSocketFrameDecoder]] that the Native
  * backend also uses. All socket I/O runs on the single-threaded event loop, so a plain `var`
  * guards exactly-once `onClose`, and every write is wrapped (an uncaught throw on the loop crashes
  * Node).
  */
private[http] object NodeWebSocket extends LogSupport:

  /**
    * Handle an HTTP `'upgrade'`: validate it's a registered WebSocket route, gate it through the
    * route filter, write the `101` handshake, then bridge socket data to the [[WebSocketHandler]].
    */
  def handleUpgrade(
      req: js.Dynamic,
      socket: js.Dynamic,
      head: js.Dynamic,
      routes: Seq[WebSocketRoute],
      maxFrameSize: Int
  ): Unit =
    try
      val request = buildRequest(req)
      routeFor(request, routes) match
        case None =>
          destroyQuietly(socket)
        case Some(route) =>
          WebSocketHandshake.validate(request) match
            case Left(rejection) =>
              // Malformed handshake: missing key (400), or unsupported Sec-WebSocket-Version (426).
              writeHttpClose(socket, rejection)
            case Right(key) =>
              gateAndAccept(socket, head, key, route, request, maxFrameSize)
    catch
      case NonFatal(e) =>
        warn(s"WebSocket upgrade error: ${e.getMessage}")
        destroyQuietly(socket)

  private def gateAndAccept(
      socket: js.Dynamic,
      head: js.Dynamic,
      key: String,
      route: WebSocketRoute,
      request: Request,
      maxFrameSize: Int
  ): Unit =
    // Capture the request as threaded through the filter, so attributes a filter adds during the
    // handshake reach the WebSocketContext (matching the Netty/Native backends).
    var upgradeRequest = request
    val gate           = RxHttpHandler { r =>
      upgradeRequest = r
      Rx.single(Response.ok)
    }
    RxRunner.runOnce(route.filter.apply(request, gate)) {
      case OnNext(response) =>
        val resp = response.asInstanceOf[Response]
        if resp.isSuccessful then
          accept(socket, head, key, route, upgradeRequest, maxFrameSize)
        else
          writeHttpClose(socket, resp)
      case OnError(e) =>
        warn(s"WebSocket upgrade filter error: ${e.getMessage}", e)
        writeHttpClose(socket, Response.internalServerError(e.getMessage))
      case OnCompletion =>
        // Filter completed without a verdict: close the connection rather than leave it hanging.
        destroyQuietly(socket)
    }

  private def accept(
      socket: js.Dynamic,
      head: js.Dynamic,
      key: String,
      route: WebSocketRoute,
      request: Request,
      maxFrameSize: Int
  ): Unit =
    // The route filter may be asynchronous; if the client disconnected while it ran, the socket is
    // already gone — skip the upgrade entirely (no onOpen, so no dangling onClose).
    if isDestroyed(socket) then
      return

    val handler = route.handlerFactory(request)
    val ctx     = NodeWebSocketContext(socket, request)
    val decoder = WebSocketFrameDecoder(maxFrameSize)
    var closed  = false

    def notifyClose(): Unit =
      if !closed then
        closed = true
        try
          handler.onClose(ctx)
        catch
          case NonFatal(e) =>
            warn(s"onClose error: ${e.getMessage}")

    def drive(bytes: Array[Byte]): Unit =
      if !closed then
        try
          if !decoder.feed(bytes)(ev =>
              WebSocketDispatcher.dispatch(handler, ctx, ctx.sendPong, () => notifyClose(), ev)
            )
          then
            // A terminal event (peer close / protocol failure) was emitted. Fire onClose now (the
            // Native backend does this via its read-loop's `finally`; the socket 'close' event may
            // not arrive for a half-open peer), then flush our close frame and FIN.
            notifyClose()
            endQuietly(socket)
        catch
          case NonFatal(e) =>
            warn(s"WebSocket read error: ${e.getMessage}")
            notifyClose()
            destroyQuietly(socket)

    try
      val handshake =
        s"HTTP/1.1 101 Switching Protocols\r\n" + s"${HttpHeader.Upgrade}: websocket\r\n" +
          s"${HttpHeader.Connection}: Upgrade\r\n" +
          s"${HttpHeader.SecWebSocketAccept}: ${WebSocketFrame.acceptKey(key)}\r\n\r\n"
      socket.applyDynamic("write")(
        NodeBytes.toUint8Array(handshake.getBytes(StandardCharsets.ISO_8859_1))
      )

      // Wire teardown before onOpen so a disconnect during the handshake can't slip through; then
      // call onOpen, attach data, and feed any bytes already read past the request headers.
      val onData: js.Function1[js.Dynamic, Unit] =
        (chunk: js.Dynamic) => drive(NodeBytes.toBytes(chunk))
      val onClose: js.Function1[js.Dynamic, Unit] = (_: js.Dynamic) => notifyClose()
      // Resume reading once a back-pressured write has drained (see NodeWebSocketContext.writeFrame).
      val onDrain: js.Function0[Unit] =
        () =>
          if !closed then
            try
              socket.applyDynamic("resume")()
            catch
              case NonFatal(_) =>
                ()
      socket.applyDynamic("on")("close", onClose)
      socket.applyDynamic("on")("error", onClose)
      socket.applyDynamic("on")("drain", onDrain)

      try
        handler.onOpen(ctx)
      catch
        case NonFatal(e) =>
          WebSocketDispatcher.safeOnError(handler, ctx, e)

      socket.applyDynamic("on")("data", onData)
      // Attaching 'data' switches the socket to flowing mode, undoing a pause from a back-pressured
      // onOpen write; re-apply it if the write buffer is still full ('drain' will resume).
      if needsDrain(socket) then
        socket.applyDynamic("pause")()
      if !js.isUndefined(head) && head != null && head.length.asInstanceOf[Int] > 0 then
        drive(NodeBytes.toBytes(head))
    catch
      case NonFatal(e) =>
        warn(s"WebSocket accept error: ${e.getMessage}")
        notifyClose()
        destroyQuietly(socket)
    end try

  end accept

  private def isDestroyed(socket: js.Dynamic): Boolean =
    val d = socket.destroyed
    !js.isUndefined(d) && d != null && d.asInstanceOf[Boolean]

  /**
    * True if the socket's write buffer is full (a prior write() returned false, not yet drained).
    */
  private def needsDrain(socket: js.Dynamic): Boolean =
    val n = socket.writableNeedDrain
    !js.isUndefined(n) && n != null && n.asInstanceOf[Boolean]

  private def buildRequest(req: js.Dynamic): Request =
    val method     = HttpMethod.of(req.method.asInstanceOf[String]).getOrElse(HttpMethod.GET)
    val uri        = req.url.asInstanceOf[String]
    val builder    = HttpMultiMap.newBuilder
    val rawHeaders =
      if js.isUndefined(req.rawHeaders) || req.rawHeaders == null then
        js.Array[String]()
      else
        req.rawHeaders.asInstanceOf[js.Array[String]]
    var i = 0
    while i + 1 < rawHeaders.length do
      builder.add(rawHeaders(i), rawHeaders(i + 1))
      i += 2
    val headers = builder.result()
    Request(
      method = method,
      uri = uri,
      headers = headers,
      content = HttpContent.fromBytes(Array.emptyByteArray, headers)
    )

  private def routeFor(request: Request, routes: Seq[WebSocketRoute]): Option[WebSocketRoute] =
    if routes.isEmpty || !isWebSocketUpgrade(request) then
      None
    else
      routes.find(_.path == request.path)

  private def isWebSocketUpgrade(request: Request): Boolean =
    request.header(HttpHeader.Connection).exists(_.toLowerCase.contains("upgrade")) &&
      request.header(HttpHeader.Upgrade).exists(_.equalsIgnoreCase("websocket"))

  /** Write a short HTTP response (rejecting the upgrade) and half-close the socket. */
  private def writeHttpClose(socket: js.Dynamic, response: Response): Unit =
    try
      val body = response.content.toContentBytes
      val sb   = StringBuilder()
      sb.append("HTTP/1.1 ")
        .append(response.status.code)
        .append(" ")
        .append(response.status.reason)
        .append("\r\n")
      // Copy the response's own headers (e.g. Sec-WebSocket-Version on a 426), minus the ones set
      // explicitly below.
      response
        .headers
        .entries
        .foreach { case (name, value) =>
          if !name.equalsIgnoreCase(HttpHeader.Connection) &&
            !name.equalsIgnoreCase(HttpHeader.ContentLength) &&
            !name.equalsIgnoreCase(HttpHeader.ContentType)
          then
            appendHeader(sb, name, value)
        }
      // Content-Type from an explicit header or the body's type (matching the Native serializer).
      response
        .headers
        .get(HttpHeader.ContentType)
        .orElse(response.content.contentType.map(_.value))
        .foreach { ct =>
          appendHeader(sb, HttpHeader.ContentType, ct)
        }
      sb.append(HttpHeader.Connection).append(": close\r\n")
      sb.append(HttpHeader.ContentLength).append(": ").append(body.length).append("\r\n\r\n")
      val head = sb.toString.getBytes(StandardCharsets.ISO_8859_1)
      socket.applyDynamic("write")(NodeBytes.toUint8Array(head ++ body))
      endQuietly(socket)
    catch
      case NonFatal(e) =>
        destroyQuietly(socket)

  /**
    * Append a header line, dropping it if the name or value contains CR/LF (defends the raw-socket
    * write against HTTP response splitting / CRLF injection from a filter-supplied header).
    */
  private def appendHeader(sb: StringBuilder, name: String, value: String): Unit =
    if !containsCrlf(name) && !containsCrlf(value) then
      sb.append(name).append(": ").append(value).append("\r\n")

  private def containsCrlf(s: String): Boolean = s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0

  private def endQuietly(socket: js.Dynamic): Unit =
    try
      socket.applyDynamic("end")()
    catch
      case NonFatal(e) =>
        debug(s"socket end failed: ${e.getMessage}")

  private def destroyQuietly(socket: js.Dynamic): Unit =
    try
      socket.applyDynamic("destroy")()
    catch
      case NonFatal(_) =>
        ()

end NodeWebSocket

/**
  * Node [[WebSocketContext]]. Single-threaded event loop, so a plain `var closed` guards writes;
  * all socket writes are wrapped (an uncaught throw on the loop crashes the process).
  */
private[http] class NodeWebSocketContext(socket: js.Dynamic, override val request: Request)
    extends WebSocketContext
    with LogSupport:

  private var closed = false

  override def send(text: String): Unit = writeIfOpen(
    WebSocketFrame.OpText,
    text.getBytes(StandardCharsets.UTF_8)
  )

  override def send(data: Array[Byte]): Unit = writeIfOpen(WebSocketFrame.OpBinary, data)

  override def close(): Unit = close(1000, "")

  override def close(statusCode: Int, reason: String): Unit =
    if !closed then
      closed = true
      writeFrame(WebSocketFrame.OpClose, WebSocketFrame.closePayload(statusCode, reason))
      // Flush the close frame, then FIN — the socket 'close' event drives onClose. Not destroy(),
      // which could drop the still-buffered close frame.
      try
        socket.applyDynamic("end")()
      catch
        case NonFatal(e) =>
          debug(s"socket end failed: ${e.getMessage}")

  private[http] def sendPong(payload: Array[Byte]): Unit =
    if !closed then
      writeFrame(WebSocketFrame.OpPong, payload)

  private def writeIfOpen(opcode: Int, payload: Array[Byte]): Unit =
    if !closed then
      writeFrame(opcode, payload)

  private def writeFrame(opcode: Int, payload: Array[Byte]): Unit =
    try
      val accepted =
        socket.applyDynamic("write")(
          NodeBytes.toUint8Array(WebSocketFrame.encodeFrame(opcode, payload))
        )
      // Backpressure: write() returns false when the kernel send buffer is full. Pause reading so a
      // slow consumer can't make us buffer unbounded; the 'drain' listener (wired in accept) resumes.
      if !accepted.asInstanceOf[Boolean] then
        socket.applyDynamic("pause")()
    catch
      case NonFatal(e) =>
        debug(s"WebSocket write failed: ${e.getMessage}")

end NodeWebSocketContext
