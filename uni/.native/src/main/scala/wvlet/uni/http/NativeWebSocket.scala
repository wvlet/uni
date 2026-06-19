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
  * Native WebSocket (RFC 6455) over a raw POSIX socket: the per-connection read loop driving the
  * shared [[WebSocketFrameDecoder]] and bridging events to a [[WebSocketHandler]]. Framing and the
  * handshake accept key live in the cross-platform [[WebSocketFrame]].
  */
private[http] object NativeWebSocket extends LogSupport:

  /**
    * Run a WebSocket connection: deliver `onOpen`, then read/dispatch frames until the peer closes
    * or the socket ends, guaranteeing `onClose` exactly once. Runs on the calling worker thread.
    */
  def serve(clientFd: Int, request: Request, handler: WebSocketHandler, maxFrameSize: Int): Unit =
    val ctx                 = NativeWebSocketContext(clientFd, request)
    val closeNotified       = AtomicBoolean(false)
    def notifyClose(): Unit =
      if closeNotified.compareAndSet(false, true) then
        try
          handler.onClose(ctx)
        catch
          case NonFatal(e) =>
            warn(s"onClose error: ${e.getMessage}")

    try
      try
        handler.onOpen(ctx)
      catch
        case NonFatal(e) =>
          WebSocketDispatcher.safeOnError(handler, ctx, e)

      val decoder = WebSocketFrameDecoder(maxFrameSize)
      var open    = true
      while open do
        val chunk = NativeSocket.recvChunk(clientFd)
        if chunk.isEmpty then
          open = false // clean EOF
        else
          open =
            decoder.feed(chunk)(ev =>
              WebSocketDispatcher.dispatch(handler, ctx, ctx.sendPong, () => notifyClose(), ev)
            )
    catch
      case NonFatal(e) =>
        WebSocketDispatcher.safeOnError(handler, ctx, e)
    finally
      notifyClose()

  end serve

end NativeWebSocket

/**
  * Native [[WebSocketContext]]. Writes are serialized so `send`/`close` are safe from any thread
  * (the read loop also writes pong/close frames).
  */
private[http] class NativeWebSocketContext(clientFd: Int, override val request: Request)
    extends WebSocketContext:

  private val writeLock = Object()
  private val closed    = AtomicBoolean(false)

  override def send(text: String): Unit = sendFrameIfOpen(
    WebSocketFrame.OpText,
    text.getBytes(StandardCharsets.UTF_8)
  )

  override def send(data: Array[Byte]): Unit = sendFrameIfOpen(WebSocketFrame.OpBinary, data)

  override def close(): Unit = close(1000, "")

  override def close(statusCode: Int, reason: String): Unit =
    if closed.compareAndSet(false, true) then
      writeFrame(WebSocketFrame.OpClose, WebSocketFrame.closePayload(statusCode, reason))

  private[http] def sendPong(payload: Array[Byte]): Unit =
    if !closed.get() then
      writeFrame(WebSocketFrame.OpPong, payload)

  private def sendFrameIfOpen(opcode: Int, payload: Array[Byte]): Unit =
    if !closed.get() then
      writeFrame(opcode, payload)

  private def writeFrame(opcode: Int, payload: Array[Byte]): Unit = writeLock.synchronized {
    NativeSocket.sendAll(clientFd, WebSocketFrame.encodeFrame(opcode, payload))
  }

end NativeWebSocketContext
