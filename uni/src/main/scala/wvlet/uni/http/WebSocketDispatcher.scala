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
import scala.util.control.NonFatal

/**
  * Maps decoded [[WsEvent]]s to [[WebSocketHandler]] callbacks and outbound control frames, shared
  * by the Native and Node.js backends. The platform-specific `sendPong` and `notifyClose` (the
  * exactly-once onClose latch) are passed in; everything else is identical across backends.
  */
private[http] object WebSocketDispatcher extends LogSupport:

  def dispatch(
      handler: WebSocketHandler,
      ctx: WebSocketContext,
      sendPong: Array[Byte] => Unit,
      notifyClose: () => Unit,
      event: WsEvent
  ): Unit =
    event match
      case WsEvent.Message(WebSocketFrame.OpText, data) =>
        deliver(
          handler,
          ctx,
          () => handler.onTextMessage(ctx, new String(data, StandardCharsets.UTF_8))
        )
      case WsEvent.Message(_, data) =>
        deliver(handler, ctx, () => handler.onBinaryMessage(ctx, data))
      case WsEvent.Ping(data) =>
        sendPong(data)
      case WsEvent.Pong(_) =>
      // ignore unsolicited pongs
      case WsEvent.PeerClose(code, _) =>
        // onClose before the echo, matching the original Native ordering (RFC 6455 §5.5.1).
        notifyClose()
        ctx.close(code, "")
      case WsEvent.Fail(code, reason) =>
        // onClose is driven by the connection teardown after this terminal event.
        ctx.close(code, reason)

  private def deliver(handler: WebSocketHandler, ctx: WebSocketContext, action: () => Unit): Unit =
    try
      action()
    catch
      case NonFatal(e) =>
        safeOnError(handler, ctx, e)

  def safeOnError(handler: WebSocketHandler, ctx: WebSocketContext, e: Throwable): Unit =
    try
      handler.onError(ctx, e)
    catch
      case NonFatal(e2) =>
        warn(s"onError error: ${e2.getMessage}")

end WebSocketDispatcher
