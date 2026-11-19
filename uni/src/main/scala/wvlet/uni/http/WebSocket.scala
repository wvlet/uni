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

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/**
  * A handle to a single, established WebSocket connection. `send`/`close` are safe to call from any
  * thread. Implementations are provided per backend (e.g. Netty on the JVM).
  */
trait WebSocketContext:

  /**
    * The original HTTP upgrade request, including any attributes set by filters during the
    * handshake.
    */
  def request: Request

  /**
    * Send a text message to the client.
    */
  def send(text: String): Unit

  /**
    * Send a binary message to the client.
    */
  def send(data: Array[Byte]): Unit

  /**
    * Close the connection with a normal-closure (1000) status.
    */
  def close(): Unit

  /**
    * Close the connection with the given status code and reason. A code that may not be sent in a
    * close frame (1004–1006, 1015, or outside 1000–4999) is sent as 1011, and a reason longer than
    * 123 UTF-8 bytes is cut to fit. Runtime limits narrow this on two clients: the JDK client sends
    * 1012–1014 as 1011, and the JS client, whose `WebSocket` API accepts only 1000 and 3000–4999,
    * sends other codes as 1000.
    */
  def close(statusCode: Int, reason: String): Unit

end WebSocketContext

/**
  * Callbacks for a WebSocket connection. All methods have no-op defaults, so a handler overrides
  * only what it needs. A fresh handler instance is created per connection (via
  * [[WebSocketRoute.handlerFactory]]), so it may hold per-connection mutable state.
  */
trait WebSocketHandler:

  /**
    * Called once, after the handshake completes and before any message is delivered.
    */
  def onOpen(ctx: WebSocketContext): Unit = {}

  /**
    * Called for each complete text message from the client.
    */
  def onTextMessage(ctx: WebSocketContext, message: String): Unit = {}

  /**
    * Called for each complete binary message from the client.
    */
  def onBinaryMessage(ctx: WebSocketContext, message: Array[Byte]): Unit = {}

  /**
    * Called once when the connection closes, whether initiated by the client or the server.
    * Delivered exactly once. Override [[onClose(ctx, statusCode, reason)]] instead to see why the
    * connection closed.
    */
  def onClose(ctx: WebSocketContext): Unit = {}

  /**
    * Called once when the connection closes, with the status code and reason of the first close
    * frame sent or received: the peer's when it closed first, this side's when it did. The code is
    * 1006 when the connection ended without a close frame, and 1013 when this side closed it
    * because the peer stopped reading. Delivered exactly once; the default calls [[onClose(ctx)]].
    */
  def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit = onClose(ctx)

  /**
    * Called when a callback or the connection raises an error.
    */
  def onError(ctx: WebSocketContext, e: Throwable): Unit = {}

end WebSocketHandler

/**
  * A WebSocket endpoint registered on an [[HttpServerConfigBase]] by path, kept separate from the
  * RPC/router because WebSocket connections are stateful and bidirectional. The optional [[filter]]
  * gates the HTTP upgrade handshake (e.g. for authentication); returning a non-2xx response (or an
  * empty `Rx`) rejects the upgrade.
  *
  * @param handlerFactory
  *   creates a fresh [[WebSocketHandler]] per accepted connection, given the upgrade request.
  * @param closeOnRejection
  *   when true, a filter rejection accepts the upgrade and closes at once with code 4000 + the
  *   response status (401 becomes 4401), so Node and browser clients, which report a rejected
  *   upgrade as 1006, can read why. See [[withCloseOnRejection]].
  */
case class WebSocketRoute(
    path: String,
    handlerFactory: Request => WebSocketHandler,
    filter: RxHttpFilter = RxHttpFilter.identity,
    closeOnRejection: Boolean = false
):
  def withPath(path: String): WebSocketRoute = copy(path = path)

  def withHandlerFactory(handlerFactory: Request => WebSocketHandler): WebSocketRoute = copy(
    handlerFactory = handlerFactory
  )

  def withFilter(filter: RxHttpFilter): WebSocketRoute = copy(filter = filter)

  /**
    * Answer a [[filter]] rejection with a WebSocket close the client can read, instead of an HTTP
    * error status. The upgrade is accepted and closed at once, before the handler is created, with
    * code 4000 + the response status (401 becomes 4401, 403 becomes 4403) and the response body,
    * cut to 123 UTF-8 bytes, as the reason. A filter error is answered as its error response
    * (usually 4500), and an empty `Rx` as 4403.
    *
    * Host and origin checks run before the filter and stay plain HTTP 403, so a cross-site page
    * never completes a handshake. Malformed upgrades (400, 426) stay HTTP errors too.
    */
  def withCloseOnRejection(enabled: Boolean = true): WebSocketRoute = copy(closeOnRejection =
    enabled
  )

  def noCloseOnRejection: WebSocketRoute = withCloseOnRejection(false)

end WebSocketRoute

/**
  * Readable upgrade rejections ([[WebSocketRoute.withCloseOnRejection]]): the close a rejected
  * upgrade is answered with, shared by every server backend.
  */
private[http] object WebSocketRejection:

  /** Base of the close codes a rejection maps to: 4000 + the HTTP status. */
  final val CloseCodeBase = 4000

  /**
    * The route to serve when its filter answered with a non-2xx `response`: the same route with a
    * handler that closes at once with 4000 + the status.
    */
  def rejectingRoute(route: WebSocketRoute, response: Response): WebSocketRoute =
    val (code, reason) = closeFor(response)
    route.withHandlerFactory(_ => RejectingHandler(code, reason))

  /** The close code and reason for a rejection `response`. */
  def closeFor(response: Response): (Int, String) =
    val status = response.statusCode
    val code   =
      if status >= 100 && status <= 999 then
        CloseCodeBase + status
      else
        CloseCodeBase + HttpStatus.InternalServerError_500.code
    val body =
      try
        response.contentAsString.getOrElse("")
      catch
        case NonFatal(_) =>
          ""
    val reason =
      if body.nonEmpty then
        body
      else
        response.status.reason
    (code, WebSocketClose.truncateReason(reason))

  private class RejectingHandler(code: Int, reason: String) extends WebSocketHandler:
    override def onOpen(ctx: WebSocketContext): Unit = ctx.close(code, reason)

end WebSocketRejection

/**
  * Close-frame rules shared by every backend (RFC 6455 §5.5 and §7.4): which status codes may be
  * sent, and the reason's size limit.
  */
private[http] object WebSocketClose:
  final val NormalClosure   = 1000
  final val AbnormalClosure = 1006
  final val InternalError   = 1011
  final val TryAgainLater   = 1013

  /** A close frame is a control frame (at most 125 bytes): 2 bytes of code plus the reason. */
  final val MaxReasonBytes = 123

  /**
    * Outbound bytes a connection may queue for a peer that isn't reading, on the backends that
    * queue sends (Netty, Node, and the JDK client). Past it the connection closes with 1013.
    */
  final val MaxPendingWriteBytes = 16 * 1024 * 1024

  /** `code` when it may be sent in a close frame, else 1011 (internal error). */
  def sendableCode(code: Int): Int =
    if WebSocketFrameDecoder.isValidCloseCode(code) then
      code
    else
      InternalError

  /** `reason` cut to [[MaxReasonBytes]] UTF-8 bytes, on a character boundary. */
  def truncateReason(reason: String): String =
    if reason == null then
      ""
    // A char is at most 3 UTF-8 bytes, so 41 chars always fit.
    else if reason.length <= MaxReasonBytes / 3 then
      reason
    else
      val bytes = reason.getBytes(StandardCharsets.UTF_8)
      if bytes.length <= MaxReasonBytes then
        reason
      else
        var end = MaxReasonBytes
        // Back up to the first byte of a multi-byte sequence (continuation bytes are 10xxxxxx).
        while end > 0 && (bytes(end) & 0xc0) == 0x80 do
          end -= 1
        String(bytes, 0, end, StandardCharsets.UTF_8)

end WebSocketClose

/**
  * Tracks how a connection closed and delivers `onClose` exactly once. The first [[record]] wins:
  * the peer's close frame, or this side's close. With no record, `onClose` reports 1006.
  */
private[http] class WebSocketCloseState extends LogSupport:
  private val notified = AtomicBoolean(false)
  @volatile
  private var status: (Int, String) = null

  def record(statusCode: Int, reason: String): Unit = synchronized {
    if status == null then
      status = (statusCode, reason)
  }

  def notifyClose(handler: WebSocketHandler, ctx: WebSocketContext): Unit =
    if notified.compareAndSet(false, true) then
      val (code, reason) = synchronized {
        if status == null then
          (WebSocketClose.AbnormalClosure, "")
        else
          status
      }
      try
        handler.onClose(ctx, code, reason)
      catch
        case NonFatal(e) =>
          warn(s"onClose error: ${e.getMessage}")

end WebSocketCloseState
