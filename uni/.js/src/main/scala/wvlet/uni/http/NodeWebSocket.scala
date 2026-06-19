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
          request.header(HttpHeader.SecWebSocketKey).filter(_.nonEmpty) match
            case None =>
              // A WebSocket upgrade without a Sec-WebSocket-Key is malformed (RFC 6455 §4.2.1).
              writeHttpClose(socket, Response.badRequest("Missing Sec-WebSocket-Key"))
            case Some(key) =>
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
          if !decoder.feed(bytes)(ev => dispatch(handler, ctx, () => notifyClose(), ev)) then
            // A terminal event (peer close / protocol failure) was emitted; flush and close.
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

      try
        handler.onOpen(ctx)
      catch
        case NonFatal(e) =>
          safeOnError(handler, ctx, e)

      // Wire teardown, then feed any bytes already read past the request headers before live data.
      val onData: js.Function1[js.Dynamic, Unit] =
        (chunk: js.Dynamic) => drive(NodeBytes.toBytes(chunk))
      val onClose: js.Function1[js.Dynamic, Unit] = (_: js.Dynamic) => notifyClose()
      socket.applyDynamic("on")("close", onClose)
      socket.applyDynamic("on")("error", onClose)
      socket.applyDynamic("on")("data", onData)
      if !js.isUndefined(head) && head != null && head.length.asInstanceOf[Int] > 0 then
        drive(NodeBytes.toBytes(head))
    catch
      case NonFatal(e) =>
        warn(s"WebSocket accept error: ${e.getMessage}")
        notifyClose()
        destroyQuietly(socket)

  end accept

  private def dispatch(
      handler: WebSocketHandler,
      ctx: NodeWebSocketContext,
      notifyClose: () => Unit,
      event: WsEvent
  ): Unit =
    event match
      case WsEvent.Message(WebSocketFrame.OpText, data) =>
        deliverText(handler, ctx, data)
      case WsEvent.Message(_, data) =>
        deliverBinary(handler, ctx, data)
      case WsEvent.Ping(data) =>
        ctx.sendPong(data)
      case WsEvent.Pong(_) =>
      // ignore unsolicited pongs
      case WsEvent.PeerClose(code, _) =>
        notifyClose()
        ctx.close(code, "") // echo the peer's close code (RFC 6455 §5.5.1)
      case WsEvent.Fail(code, reason) =>
        ctx.close(code, reason)

  private def deliverText(
      handler: WebSocketHandler,
      ctx: NodeWebSocketContext,
      data: Array[Byte]
  ): Unit =
    try
      handler.onTextMessage(ctx, new String(data, StandardCharsets.UTF_8))
    catch
      case NonFatal(e) =>
        safeOnError(handler, ctx, e)

  private def deliverBinary(
      handler: WebSocketHandler,
      ctx: NodeWebSocketContext,
      data: Array[Byte]
  ): Unit =
    try
      handler.onBinaryMessage(ctx, data)
    catch
      case NonFatal(e) =>
        safeOnError(handler, ctx, e)

  private def safeOnError(handler: WebSocketHandler, ctx: WebSocketContext, e: Throwable): Unit =
    try
      handler.onError(ctx, e)
    catch
      case NonFatal(e2) =>
        warn(s"onError error: ${e2.getMessage}")

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
      sb.append(HttpHeader.Connection).append(": close\r\n")
      sb.append(HttpHeader.ContentLength).append(": ").append(body.length).append("\r\n\r\n")
      val head = sb.toString.getBytes(StandardCharsets.ISO_8859_1)
      socket.applyDynamic("write")(NodeBytes.toUint8Array(head ++ body))
      endQuietly(socket)
    catch
      case NonFatal(e) =>
        destroyQuietly(socket)

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
      socket.applyDynamic("write")(
        NodeBytes.toUint8Array(WebSocketFrame.encodeFrame(opcode, payload))
      )
    catch
      case NonFatal(e) =>
        debug(s"WebSocket write failed: ${e.getMessage}")

end NodeWebSocketContext
