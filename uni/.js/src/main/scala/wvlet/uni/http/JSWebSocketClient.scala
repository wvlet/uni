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

import wvlet.uni.rx.Rx

import scala.concurrent.{ExecutionContext, Promise}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.*
import scala.util.control.NonFatal

/**
  * Minimal facade for the global `WebSocket` (browsers and Node.js >= 22, which provides it as a
  * stable global). The runtime performs the handshake, masking, and framing.
  */
@js.native
@JSGlobal("WebSocket")
private class JsWebSocket(url: String) extends js.Object:
  var binaryType: String                        = js.native
  var onopen: js.Function1[js.Any, Unit]        = js.native
  var onmessage: js.Function1[js.Dynamic, Unit] = js.native
  var onclose: js.Function1[js.Any, Unit]       = js.native
  var onerror: js.Function1[js.Any, Unit]       = js.native
  def send(data: String): Unit                  = js.native
  def send(data: Int8Array): Unit               = js.native
  def close(code: Int, reason: String): Unit    = js.native
  def close(): Unit                             = js.native

/**
  * JavaScript WebSocket client backed by the global `WebSocket`. Bridges its events to the shared
  * [[WebSocketHandler]] / [[WebSocketContext]].
  */
class JSWebSocketClient extends WebSocketClient:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  override def connect(uri: String, handler: WebSocketHandler): Rx[WebSocketContext] =
    val opened = Promise[WebSocketContext]()
    val ws     = new JsWebSocket(uri)
    ws.binaryType = "arraybuffer"
    val ctx    = JSWebSocketContext(ws, Request.get(uri))
    var closed = false

    def notifyClose(): Unit =
      if !closed then
        closed = true
        try
          handler.onClose(ctx)
        catch
          case NonFatal(_) =>
            ()

    def deliver(action: () => Unit): Unit =
      try
        action()
      catch
        case NonFatal(e) =>
          WebSocketDispatcher.safeOnError(handler, ctx, e)

    ws.onopen =
      (_: js.Any) =>
        deliver(() => handler.onOpen(ctx))
        opened.trySuccess(ctx)
    ws.onmessage =
      (event: js.Dynamic) =>
        val data = event.data
        if js.typeOf(data) == "string" then
          val text = data.asInstanceOf[String]
          deliver(() => handler.onTextMessage(ctx, text))
        else
          // binaryType = "arraybuffer", so binary frames arrive as an ArrayBuffer.
          val bytes = Int8Array(data.asInstanceOf[ArrayBuffer]).toArray
          deliver(() => handler.onBinaryMessage(ctx, bytes))
    ws.onclose = (_: js.Any) => notifyClose()
    ws.onerror =
      (_: js.Any) =>
        // Transport error maps to onClose (matching the other backends); surface pre-open failures.
        opened.tryFailure(RuntimeException(s"WebSocket connection failed: ${uri}"))
        notifyClose()

    Rx.future(opened.future)

  end connect

end JSWebSocketClient

object JSWebSocketClient:
  def apply(): JSWebSocketClient = new JSWebSocketClient()

/** [[WebSocketContext]] over the global `WebSocket`. `request` is the connect request. */
private class JSWebSocketContext(ws: JsWebSocket, override val request: Request)
    extends WebSocketContext:

  override def send(text: String): Unit = ws.send(text)

  // Standard Scala.js conversion (no Node-specific helper) so this works in browsers too. ws.send
  // accepts any ArrayBufferView; the signed Int8Array shares the same bytes.
  override def send(data: Array[Byte]): Unit = ws.send(data.toTypedArray)

  override def close(): Unit = ws.close()

  override def close(statusCode: Int, reason: String): Unit = ws.close(statusCode, reason)

end JSWebSocketContext
