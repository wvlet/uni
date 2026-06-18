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
package wvlet.uni.http.netty

import io.netty.buffer.{ByteBufUtil, Unpooled}
import io.netty.channel.{Channel, ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.http.websocketx.{
  BinaryWebSocketFrame,
  CloseWebSocketFrame,
  PingWebSocketFrame,
  PongWebSocketFrame,
  TextWebSocketFrame,
  WebSocketCloseStatus,
  WebSocketFrame,
  WebSocketServerHandshaker
}
import wvlet.uni.http.{Request, WebSocketContext, WebSocketHandler}
import wvlet.uni.log.LogSupport

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/**
  * Netty-backed [[WebSocketContext]]. `send`/`close` use the channel's thread-safe `writeAndFlush`,
  * so they are callable from any thread.
  */
private[netty] class NettyWebSocketContext(
    channel: Channel,
    override val request: Request,
    private[netty] val handshaker: WebSocketServerHandshaker
) extends WebSocketContext:

  override def send(text: String): Unit = channel.writeAndFlush(TextWebSocketFrame(text))

  override def send(data: Array[Byte]): Unit = channel.writeAndFlush(
    BinaryWebSocketFrame(Unpooled.wrappedBuffer(data))
  )

  override def close(): Unit = close(
    WebSocketCloseStatus.NORMAL_CLOSURE.code(),
    WebSocketCloseStatus.NORMAL_CLOSURE.reasonText()
  )

  override def close(statusCode: Int, reason: String): Unit = handshaker.close(
    channel,
    CloseWebSocketFrame(statusCode, reason)
  )

end NettyWebSocketContext

/**
  * Bridges Netty WebSocket frames to a user [[WebSocketHandler]]. Control frames (close/ping/pong)
  * are handled here on purpose rather than via Netty's `WebSocketServerProtocolHandler`, so the
  * upgrade can be gated by an `RxHttpFilter` (see `NettyRequestHandler.handleWebSocketUpgrade`).
  */
private[netty] class NettyWebSocketHandler(
    handler: WebSocketHandler,
    wsContext: NettyWebSocketContext
) extends SimpleChannelInboundHandler[WebSocketFrame]
    with LogSupport:

  // Ensures onClose is delivered exactly once, whether triggered by an inbound Close frame or by
  // channelInactive.
  private val closeNotified = AtomicBoolean(false)

  private[netty] def notifyOpen(): Unit = safeInvoke(handler.onOpen(wsContext))

  override def channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame): Unit =
    frame match
      case t: TextWebSocketFrame =>
        safeInvoke(handler.onTextMessage(wsContext, t.text()))
      case b: BinaryWebSocketFrame =>
        safeInvoke(handler.onBinaryMessage(wsContext, ByteBufUtil.getBytes(b.content())))
      case c: CloseWebSocketFrame =>
        notifyClose()
        // Echo the close frame back and close the connection (retain to balance the auto-release).
        wsContext.handshaker.close(ctx.channel(), c.retain())
      case _: PingWebSocketFrame =>
        // Respond to ping with a pong carrying the same payload.
        ctx.writeAndFlush(PongWebSocketFrame(frame.content().retain()))
      case _: PongWebSocketFrame =>
      // Ignore unsolicited pongs.
      case other =>
        debug(s"Ignoring unsupported WebSocket frame: ${other.getClass.getName}")

  override def channelInactive(ctx: ChannelHandlerContext): Unit =
    notifyClose()
    super.channelInactive(ctx)

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    if NettyRequestHandler.isBenignIOException(cause) then
      debug(s"Benign WebSocket I/O exception: ${cause.getMessage}")
    else
      safeOnError(cause)
    ctx.close()

  private def notifyClose(): Unit =
    if closeNotified.compareAndSet(false, true) then
      try
        handler.onClose(wsContext)
      catch
        // onClose errors are logged, not routed to onError, to avoid re-entrancy during teardown.
        case NonFatal(e) =>
          warn(e)

  private def safeInvoke(body: => Unit): Unit =
    try
      body
    catch
      case NonFatal(e) =>
        safeOnError(e)

  private def safeOnError(e: Throwable): Unit =
    try
      handler.onError(wsContext, e)
    catch
      case NonFatal(e2) =>
        warn(e2)

end NettyWebSocketHandler
