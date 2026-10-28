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

import io.netty.channel.{
  ChannelDuplexHandler,
  ChannelFutureListener,
  ChannelHandlerContext,
  ChannelPromise
}
import io.netty.handler.codec.http.LastHttpContent

/**
  * Closes the connection once it has no request in flight after the server starts stopping, so
  * keep-alive connections do not outlive the executors their handlers run on.
  *
  * Counts requests received minus responses written, like [[RequestReadTimeoutHandler]]. Must sit
  * right after the HTTP codec so it sees decoded request and response objects.
  */
class ConnectionDrainHandler extends ChannelDuplexHandler:
  // Only touched on the channel's event loop
  private var ctx: ChannelHandlerContext = null
  private var pendingRequests            = 0
  private var draining                   = false

  override def handlerAdded(ctx: ChannelHandlerContext): Unit = this.ctx = ctx

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    if msg.isInstanceOf[LastHttpContent] then
      pendingRequests += 1
    ctx.fireChannelRead(msg)

  override def write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise): Unit =
    if msg.isInstanceOf[LastHttpContent] then
      pendingRequests -= 1
      if draining && pendingRequests <= 0 then
        // Close once the last pending response is on the wire
        val p = promise.unvoid()
        ctx.write(msg, p)
        p.addListener(ChannelFutureListener.CLOSE)
        return
    ctx.write(msg, promise)

  /**
    * Close now when idle or upgraded to WebSocket, otherwise after the pending responses. Must run
    * on the channel's event loop.
    */
  private[netty] def drain(): Unit =
    draining = true
    val upgraded = ctx.pipeline().get(NettyHttpServer.WebSocketHandlerName) != null
    if pendingRequests <= 0 || upgraded then
      ctx.close()

end ConnectionDrainHandler
