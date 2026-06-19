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
  def serve(
      clientFd: Int,
      request: Request,
      handler: WebSocketHandler,
      maxFrameSize: Int,
      pingIntervalMillis: Int
  ): Unit =
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

      runReadLoop(
        clientFd,
        maxFrameSize,
        pingIntervalMillis,
        expectMasked = true,
        initial = Array.emptyByteArray,
        handler,
        ctx,
        ctx.sendPong,
        ctx.sendPing,
        () => notifyClose()
      )
    catch
      case NonFatal(e) =>
        WebSocketDispatcher.safeOnError(handler, ctx, e)
    finally
      notifyClose()

  end serve

  /**
    * Poll/read/dispatch loop shared by the Native server and client. Uses `poll` so a periodic
    * heartbeat (when `pingIntervalMillis > 0`) can ping an idle peer and close it if a Ping goes
    * unanswered; with the heartbeat off it blocks (poll timeout -1) like a plain `recv`. `initial`
    * is any bytes already read past the handshake (client side). Exceptions propagate to the
    * caller.
    */
  private[http] def runReadLoop(
      fd: Int,
      maxFrameSize: Int,
      pingIntervalMillis: Int,
      expectMasked: Boolean,
      initial: Array[Byte],
      handler: WebSocketHandler,
      ctx: WebSocketContext,
      sendPong: Array[Byte] => Unit,
      sendPing: Array[Byte] => Unit,
      notifyClose: () => Unit
  ): Unit =
    val decoder   = WebSocketFrameDecoder(maxFrameSize, expectMasked)
    val heartbeat =
      if pingIntervalMillis > 0 then
        WebSocketHeartbeat()
      else
        null
    val pollTimeout =
      if pingIntervalMillis > 0 then
        pingIntervalMillis
      else
        -1
    def feed(bytes: Array[Byte]): Boolean =
      decoder.feed(bytes)(ev =>
        WebSocketDispatcher.dispatch(
          handler,
          ctx,
          sendPong,
          notifyClose,
          ev,
          () =>
            if heartbeat != null then
              heartbeat.onActivity()
        )
      )
    var open = initial.isEmpty || feed(initial)
    while open do
      NativeSocket.waitReadable(fd, pollTimeout) match
        case 1 =>
          val chunk = NativeSocket.recvChunk(fd)
          if chunk.isEmpty then
            open = false
          else
            open = feed(chunk)
        case 0 =>
          // Heartbeat tick (only reached when pingIntervalMillis > 0).
          if heartbeat != null then
            heartbeat.onTick() match
              case WebSocketHeartbeat.Decision.SendPing =>
                sendPing(Array.emptyByteArray)
              case WebSocketHeartbeat.Decision.Close =>
                ctx.close(1011, "ping timeout")
                open = false
              case WebSocketHeartbeat.Decision.Idle =>
                ()
        case _ =>
          open = false // hangup/error

  end runReadLoop

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

  private[http] def sendPing(payload: Array[Byte]): Unit =
    if !closed.get() then
      writeFrame(WebSocketFrame.OpPing, payload)

  private def sendFrameIfOpen(opcode: Int, payload: Array[Byte]): Unit =
    if !closed.get() then
      writeFrame(opcode, payload)

  private def writeFrame(opcode: Int, payload: Array[Byte]): Unit = writeLock.synchronized {
    NativeSocket.sendAll(clientFd, WebSocketFrame.encodeFrame(opcode, payload))
  }

end NativeWebSocketContext
