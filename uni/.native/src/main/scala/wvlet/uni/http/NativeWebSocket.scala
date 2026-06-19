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
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.util.control.NonFatal

/**
  * Native WebSocket (RFC 6455) over a raw POSIX socket: handshake accept-key, frame encode/decode,
  * and the per-connection read loop bridging frames to a [[WebSocketHandler]].
  */
private[http] object NativeWebSocket extends LogSupport:

  final val OpContinuation = 0x0
  final val OpText         = 0x1
  final val OpBinary       = 0x2
  final val OpClose        = 0x8
  final val OpPing         = 0x9
  final val OpPong         = 0xa

  private final val MagicGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  /** `Sec-WebSocket-Accept` = base64(SHA1(key + GUID)). */
  def acceptKey(key: String): String = Base64
    .getEncoder
    .encodeToString(Sha1.digest((key + MagicGuid).getBytes(StandardCharsets.US_ASCII)))

  /** Encode an unmasked server frame (FIN set). */
  def encodeFrame(opcode: Int, payload: Array[Byte]): Array[Byte] =
    val len    = payload.length
    val b0     = (0x80 | opcode).toByte
    val header =
      if len < 126 then
        Array[Byte](b0, len.toByte)
      else if len < 65536 then
        Array[Byte](b0, 126.toByte, ((len >>> 8) & 0xff).toByte, (len & 0xff).toByte)
      else
        val h = new Array[Byte](10)
        h(0) = b0
        h(1) = 127.toByte
        val l = len.toLong
        var i = 0
        while i < 8 do
          h(2 + i) = ((l >>> ((7 - i) * 8)) & 0xff).toByte
          i += 1
        h
    header ++ payload

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

    val buf             = mutable.ArrayBuffer.empty[Byte]
    def fill(): Boolean =
      val c = NativeSocket.recvChunk(clientFd)
      if c.isEmpty then
        false
      else
        buf ++= c
        true
    def ensure(n: Int): Boolean =
      while buf.length < n do
        if !fill() then
          return false
      true
    def take(n: Int): Array[Byte] =
      val a = buf.slice(0, n).toArray
      buf.remove(0, n)
      a

    try
      try
        handler.onOpen(ctx)
      catch
        case NonFatal(e) =>
          safeOnError(handler, ctx, e)

      val fragments      = mutable.ArrayBuffer.empty[Byte]
      var fragmentOpcode = -1
      var open           = true
      while open do
        if !ensure(2) then
          open = false
        else
          val b0        = buf(0) & 0xff
          val b1        = buf(1) & 0xff
          val fin       = (b0 & 0x80) != 0
          val rsv       = b0 & 0x70
          val opcode    = b0 & 0x0f
          val masked    = (b1 & 0x80) != 0
          val isControl = (opcode & 0x8) != 0
          var len       = b1 & 0x7f
          var header    = 2
          if len == 126 then
            if !ensure(4) then
              open = false
            else
              len = ((buf(2) & 0xff) << 8) | (buf(3) & 0xff)
              header = 4
          else if len == 127 then
            if !ensure(10) then
              open = false
            else
              var l = 0L
              var i = 0
              while i < 8 do
                l = (l << 8) | (buf(2 + i) & 0xff).toLong
                i += 1
              if l > maxFrameSize then
                len = -1 // signal too-big below
              else
                len = l.toInt
              header = 10

          if open && (len < 0 || len > maxFrameSize) then
            ctx.close(1009, "message too big")
            open = false
          else if open && (rsv != 0 || !masked) then
            // RSV must be 0 (no extension negotiated); client frames MUST be masked (RFC 6455 §5).
            ctx.close(1002, "protocol error")
            open = false
          else if open && isControl && (!fin || len > 125) then
            // Control frames must be final and at most 125 bytes (RFC 6455 §5.5).
            ctx.close(1002, "invalid control frame")
            open = false
          else if open then
            if !ensure(header + 4 + len) then
              open = false
            else
              buf.remove(0, header)
              val mask    = take(4) // client frames are masked (validated above)
              val payload = take(len)
              var i       = 0
              while i < payload.length do
                payload(i) = (payload(i) ^ mask(i % 4)).toByte
                i += 1
              opcode match
                case OpText | OpBinary =>
                  if fragmentOpcode != -1 then
                    ctx.close(1002, "data frame during a fragmented message")
                    open = false
                  else if fin then
                    deliver(handler, ctx, opcode, payload)
                  else
                    fragmentOpcode = opcode
                    fragments.clear()
                    fragments ++= payload
                case OpContinuation =>
                  if fragmentOpcode == -1 then
                    ctx.close(1002, "continuation without a start frame")
                    open = false
                  else
                    fragments ++= payload
                    if fragments.length > maxFrameSize then
                      ctx.close(1009, "message too big")
                      open = false
                    else if fin then
                      deliver(handler, ctx, fragmentOpcode, fragments.toArray)
                      fragments.clear()
                      fragmentOpcode = -1
                case OpClose =>
                  notifyClose()
                  // Echo the peer's close code (RFC 6455 §5.5.1).
                  val code =
                    if payload.length >= 2 then
                      ((payload(0) & 0xff) << 8) | (payload(1) & 0xff)
                    else
                      1000
                  ctx.close(code, "")
                  open = false
                case OpPing =>
                  ctx.sendPong(payload)
                case OpPong =>
                // ignore unsolicited pongs
                case _ =>
                  ctx.close(1002, s"unsupported opcode ${opcode}")
                  open = false
              end match
            end if
          end if
      end while
    catch
      case NonFatal(e) =>
        safeOnError(handler, ctx, e)
    finally
      notifyClose()
    end try

  end serve

  private def deliver(
      handler: WebSocketHandler,
      ctx: NativeWebSocketContext,
      opcode: Int,
      payload: Array[Byte]
  ): Unit =
    try
      if opcode == OpText then
        handler.onTextMessage(ctx, new String(payload, StandardCharsets.UTF_8))
      else
        handler.onBinaryMessage(ctx, payload)
    catch
      case NonFatal(e) =>
        safeOnError(handler, ctx, e)

  private def safeOnError(handler: WebSocketHandler, ctx: WebSocketContext, e: Throwable): Unit =
    try
      handler.onError(ctx, e)
    catch
      case NonFatal(e2) =>
        warn(s"onError error: ${e2.getMessage}")

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
    NativeWebSocket.OpText,
    text.getBytes(StandardCharsets.UTF_8)
  )

  override def send(data: Array[Byte]): Unit = sendFrameIfOpen(NativeWebSocket.OpBinary, data)

  override def close(): Unit = close(1000, "")

  override def close(statusCode: Int, reason: String): Unit =
    if closed.compareAndSet(false, true) then
      val reasonBytes = reason.getBytes(StandardCharsets.UTF_8)
      val payload     = new Array[Byte](2 + reasonBytes.length)
      payload(0) = ((statusCode >>> 8) & 0xff).toByte
      payload(1) = (statusCode & 0xff).toByte
      System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length)
      writeFrame(NativeWebSocket.OpClose, payload)

  private[http] def sendPong(payload: Array[Byte]): Unit =
    if !closed.get() then
      writeFrame(NativeWebSocket.OpPong, payload)

  private def sendFrameIfOpen(opcode: Int, payload: Array[Byte]): Unit =
    if !closed.get() then
      writeFrame(opcode, payload)

  private def writeFrame(opcode: Int, payload: Array[Byte]): Unit = writeLock.synchronized {
    NativeSocket.sendAll(clientFd, NativeWebSocket.encodeFrame(opcode, payload))
  }

end NativeWebSocketContext
