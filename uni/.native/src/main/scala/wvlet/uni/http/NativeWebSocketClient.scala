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
import wvlet.uni.rx.Rx

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.util.control.NonFatal

/**
  * Scala Native WebSocket client over a raw POSIX socket: connects, performs the handshake, then
  * drives the shared [[WebSocketFrameDecoder]] (client mode — server frames are unmasked) on a
  * daemon reader thread, bridging to a [[WebSocketHandler]]. Outbound frames are masked (RFC 6455
  * §5.3). Only `ws://` is supported (no TLS on the raw socket).
  */
class NativeWebSocketClient extends WebSocketClient with LogSupport:

  override def connect(uri: String, handler: WebSocketHandler): Rx[WebSocketContext] =
    try
      val target = NativeWebSocketClient.parse(uri)
      val fd     = NativeSocket.connect(target.host, target.port)
      try
        val key     = WebSocketFrame.newClientKey()
        val request =
          s"GET ${target.path} HTTP/1.1\r\nHost: ${target.host}:${target.port}\r\n" +
            s"Upgrade: websocket\r\nConnection: Upgrade\r\n" +
            s"Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        if !NativeSocket.sendAll(fd, request.getBytes(StandardCharsets.ISO_8859_1)) then
          throw HttpException.connectionFailed("Failed to send the WebSocket upgrade request")

        val handshake = NativeWebSocketClient.readHandshake(fd)
        if handshake.status != 101 then
          throw HttpException.connectionFailed(s"WebSocket upgrade rejected: ${handshake.status}")
        if handshake.headers.getOrElse("sec-websocket-accept", "") != WebSocketFrame.acceptKey(key)
        then
          throw HttpException.connectionFailed("Invalid Sec-WebSocket-Accept in the handshake")

        val ctx                 = NativeWebSocketClientContext(fd, Request.get(uri))
        val closeNotified       = AtomicBoolean(false)
        def notifyClose(): Unit =
          if closeNotified.compareAndSet(false, true) then
            try
              handler.onClose(ctx)
            catch
              case NonFatal(e) =>
                warn(s"onClose error: ${e.getMessage}")

        // onOpen before the reader starts, so it precedes any delivered message.
        try
          handler.onOpen(ctx)
        catch
          case NonFatal(e) =>
            WebSocketDispatcher.safeOnError(handler, ctx, e)

        val reader = Thread(() =>
          try
            val decoder = WebSocketFrameDecoder(
              NativeWebSocketClient.MaxFrameSize,
              expectMasked = false
            )
            def feed(bytes: Array[Byte]): Boolean =
              decoder.feed(bytes)(ev =>
                WebSocketDispatcher.dispatch(handler, ctx, ctx.sendPong, () => notifyClose(), ev)
              )
            var open = handshake.leftover.isEmpty || feed(handshake.leftover)
            while open do
              val chunk = NativeSocket.recvChunk(fd)
              if chunk.isEmpty then
                open = false
              else
                open = feed(chunk)
          catch
            case NonFatal(e) =>
              WebSocketDispatcher.safeOnError(handler, ctx, e)
          finally
            notifyClose()
            NativeSocket.close(fd)
        )
        reader.setDaemon(true)
        reader.setName("native-ws-client-reader")
        reader.start()

        Rx.single(ctx)
      catch
        case NonFatal(e) =>
          NativeSocket.close(fd)
          Rx.exception(e)
      end try
    catch
      case NonFatal(e) =>
        Rx.exception(e)

end NativeWebSocketClient

object NativeWebSocketClient:
  private final val MaxFrameSize = 1024 * 1024

  def apply(): NativeWebSocketClient = new NativeWebSocketClient()

  private case class Target(host: String, port: Int, path: String)

  /**
    * The parsed handshake response: status code, lower-cased headers, and any buffered frame bytes.
    */
  private case class Handshake(status: Int, headers: Map[String, String], leftover: Array[Byte])

  private def parse(uri: String): Target =
    if uri.startsWith("wss://") then
      throw HttpException.connectionFailed("wss:// (TLS) is not supported by the Native client")
    val rest      = uri.stripPrefix("ws://")
    val slash     = rest.indexOf('/')
    val authority =
      if slash >= 0 then
        rest.substring(0, slash)
      else
        rest
    val path =
      if slash >= 0 then
        rest.substring(slash)
      else
        "/"
    val colon = authority.indexOf(':')
    val host  =
      if colon >= 0 then
        authority.substring(0, colon)
      else
        authority
    val port =
      if colon >= 0 then
        authority.substring(colon + 1).toInt
      else
        80
    Target(host, port, path)

  /**
    * Read the HTTP upgrade response up to the header terminator; retain any frame bytes that
    * follow.
    */
  private def readHandshake(fd: Int): Handshake =
    val buf = mutable.ArrayBuffer.empty[Byte]
    var end = -1
    while end < 0 do
      val chunk = NativeSocket.recvChunk(fd)
      if chunk.isEmpty then
        throw HttpException.connectionFailed("Connection closed during the WebSocket handshake")
      buf ++= chunk
      end = headerEnd(buf)
    val head     = new String(buf.slice(0, end).toArray, StandardCharsets.ISO_8859_1)
    val leftover = buf.slice(end + 4, buf.length).toArray
    val lines    = head.split("\r\n")
    val status   = lines(0).split(" ")(1).toInt
    val headers  =
      lines
        .drop(1)
        .flatMap { line =>
          val c = line.indexOf(':')
          if c > 0 then
            Some(line.substring(0, c).trim.toLowerCase -> line.substring(c + 1).trim)
          else
            None
        }
        .toMap
    Handshake(status, headers, leftover)

  private def headerEnd(buf: mutable.ArrayBuffer[Byte]): Int =
    var i = 0
    while i + 3 < buf.length do
      if buf(i) == '\r' && buf(i + 1) == '\n' && buf(i + 2) == '\r' && buf(i + 3) == '\n' then
        return i
      i += 1
    -1

end NativeWebSocketClient

/**
  * Native client [[WebSocketContext]]. Outbound frames are masked (client->server requirement);
  * writes are serialized so `send`/`close` are safe from any thread.
  */
private class NativeWebSocketClientContext(clientFd: Int, override val request: Request)
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
    NativeSocket.sendAll(
      clientFd,
      WebSocketFrame.encodeMaskedFrame(opcode, payload, WebSocketFrame.newMaskingKey())
    )
  }

end NativeWebSocketClientContext
