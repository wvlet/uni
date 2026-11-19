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
  * WebSocket (RFC 6455) over a [[SocketConnection]], shared by Scala Native and the JVM's built-in
  * server: the per-connection read loop driving the shared [[WebSocketFrameDecoder]] and bridging
  * events to a [[WebSocketHandler]]. Framing and the handshake accept key live in the
  * cross-platform [[WebSocketFrame]].
  */
private[http] object SocketWebSocket extends LogSupport:

  /**
    * Run a WebSocket connection: deliver `onOpen`, then read/dispatch frames until the peer closes
    * or the socket ends, guaranteeing `onClose` exactly once. Runs on the calling worker thread.
    */
  def serve(
      conn: SocketConnection,
      request: Request,
      handler: WebSocketHandler,
      maxFrameSize: Int,
      pingIntervalMillis: Int
  ): Unit =
    val closeState          = WebSocketCloseState()
    val ctx                 = SocketWebSocketContext(conn, request, closeState)
    def notifyClose(): Unit =
      // The write watchdog aborted a write the peer stopped reading (see WriteTrackingConnection).
      conn match
        case w: WriteTrackingConnection if w.stalled =>
          closeState.record(WebSocketClose.TryAgainLater, "write stalled")
        case _ =>
      closeState.notifyClose(handler, ctx)

    try
      try
        handler.onOpen(ctx)
      catch
        case NonFatal(e) =>
          WebSocketDispatcher.safeOnError(handler, ctx, e)

      runReadLoop(
        conn,
        maxFrameSize,
        pingIntervalMillis,
        expectMasked = true,
        initial = Array.emptyByteArray,
        handler,
        ctx,
        ctx.sendPong,
        ctx.sendPing,
        (code, reason) =>
          closeState.record(code, reason)
          notifyClose()
      )
    catch
      case NonFatal(e) =>
        WebSocketDispatcher.safeOnError(handler, ctx, e)
    finally
      notifyClose()

  end serve

  /**
    * Read/dispatch loop shared by the servers and the Native client. Reads with a timeout so a
    * periodic heartbeat (when `pingIntervalMillis > 0`) can ping an idle peer and close it if a
    * Ping goes unanswered; with the heartbeat off a read waits without a limit. `initial` is any
    * bytes already read past the handshake (client side). Exceptions propagate to the caller.
    */
  private[http] def runReadLoop(
      conn: SocketConnection,
      maxFrameSize: Int,
      pingIntervalMillis: Int,
      expectMasked: Boolean,
      initial: Array[Byte],
      handler: WebSocketHandler,
      ctx: WebSocketContext,
      sendPong: Array[Byte] => Unit,
      sendPing: Array[Byte] => Unit,
      onPeerClose: (Int, String) => Unit
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
          onPeerClose,
          ev,
          () =>
            if heartbeat != null then
              heartbeat.onActivity()
        )
      )
    var open = initial.isEmpty || feed(initial)
    val buf  = new Array[Byte](ReadBufferSize)
    while open do
      conn.read(buf, 0, buf.length, pollTimeout) match
        case n if n > 0 =>
          open = feed(java.util.Arrays.copyOf(buf, n))
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

  private final val ReadBufferSize = 16 * 1024

end SocketWebSocket

/**
  * Server-side [[WebSocketContext]] over a [[SocketConnection]]. Writes are serialized so
  * `send`/`close` are safe from any thread (the read loop also writes pong/close frames).
  */
private[http] class SocketWebSocketContext(
    conn: SocketConnection,
    override val request: Request,
    closeState: WebSocketCloseState
) extends WebSocketContext:

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
      closeState.record(
        WebSocketClose.sendableCode(statusCode),
        WebSocketClose.truncateReason(reason)
      )
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
    conn.write(WebSocketFrame.encodeFrame(opcode, payload))
  }

end SocketWebSocketContext
